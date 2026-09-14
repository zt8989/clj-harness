(ns harness.hooks-dispatch-test
  "harness.hooks.dispatch's external behavior: what a trigger does when nothing
  is declared, when something is, and what each exit code means.

  The stub commands are real files run by the real shell -- which is the point:
  exit codes, stderr-as-reason, hanging and missing binaries are exactly the
  things a mocked process would paper over."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [harness.home :as home]
            [harness.hooks :as hooks]
            [harness.hooks.dispatch :as dispatch]
            [harness.project :as project]))

(def ^:private root (str (System/getProperty "java.io.tmpdir") "/harness-hooks-dispatch-test"))
(def ^:private scripts (str root "/scripts"))

(io/delete-file root true)
(.mkdirs (io/file scripts))

(defn- script!
  "A stub command that does BODY, on PATH-free absolute invocation. Written with
  a shebang and made executable; the payload it received is captured beside it so
  a test can assert what the hook was TOLD, not just what it did."
  [name body]
  (let [f (io/file scripts name)]
    (spit f (str "#!/bin/sh\n" body "\n") :encoding "UTF-8")
    (.setExecutable f true)
    (str f)))

(defn- declared! [point decls]
  (.mkdirs (io/file (home/root)))
  (spit (str (home/root) "/hooks.edn") (pr-str {point decls}) :encoding "UTF-8"))

(defn- wipe [f]
  (io/delete-file (io/file (home/root) "hooks.edn") true)
  (io/delete-file (io/file scripts "payload.txt") true)
  (f)
  (io/delete-file (io/file (home/root) "hooks.edn") true))

(use-fixtures :each wipe)

(defn- fire
  ([point fact] (fire point fact nil))
  ([point fact {:keys [thread-id audit]}]
   (dispatch/fire {:point point
                   :thread-id thread-id
                   :fact fact
                   :audit audit
                   :run-id "r-1"})))

;; ----------------------------------------------------- nothing means nothing

(deftest a-point-with-no-declarations-does-nothing-at-all
  (let [audits (atom [])
        r (fire :stop {} {:audit #(swap! audits conj %)})]
    (testing "the verdict is allow, and no command was needed to reach it"
      (is (= :allow (:verdict r)))
      (is (zero? (:matched r))))
    (testing "and NO audit line: an untouched run leaves no trace of the engine"
      (is (empty? @audits)))))

(deftest a-matcher-that-selects-nothing-is-also-nothing
  (declared! :pre-tool-use [{:command (script! "never.sh" "exit 2")
                             :matcher "bash"}])
  (let [audits (atom [])
        r (fire :pre-tool-use {:tool_name "read"} {:audit #(swap! audits conj %)})]
    (is (= :allow (:verdict r)))
    (is (empty? @audits) "a declaration that does not select this call is not an event")))

(deftest a-matcher-that-selects-this-call-runs-it
  (let [marker (str root "/ran.txt")]
    (declared! :pre-tool-use [{:command (script! "gate.sh" (str "echo ran > " marker " && exit 0"))
                               :matcher "bash|write"}])
    (let [r (fire :pre-tool-use {:tool_name "bash"})]
      (is (= :allow (:verdict r)))
      (is (= 1 (:matched r)))
      (is (.exists (io/file marker)) "the command really ran"))))

;; -------------------------------------------------------------- exit codes

(deftest exit-zero-allows
  (declared! :post-tool-use [{:command (script! "ok.sh" "exit 0")}])
  (let [r (fire :post-tool-use {:tool_name "read"})]
    (is (= :allow (:verdict r)))
    (is (= 1 (:matched r)))))

(deftest exit-two-blocks-and-stderr-is-the-reason
  (declared! :pre-tool-use [{:command (script! "gate.sh" "echo 'no writes before breakfast' >&2; exit 2")}])
  (let [r (fire :pre-tool-use {:tool_name "write"})]
    (testing "the block carries the AUTHOR's words -- they are what the model is told"
      (is (= :block (:verdict r)))
      (is (str/includes? (:reason r) "no writes before breakfast")))))

(deftest another-nonzero-is-a-failure-and-the-point-says-what-that-means
  (testing "an observer point (:on-error :proceed) carries on, and still audits"
    (declared! :post-tool-use [{:command (script! "broken.sh" "echo boom >&2; exit 3")}])
    (let [audits (atom [])
          r (fire :post-tool-use {:tool_name "read"} {:audit #(swap! audits conj %)})]
      (is (= :allow (:verdict r)) "a broken observer never changes the run")
      (is (str/includes? (:reason r) "boom") "but it is not hidden either")
      (is (= 1 (count @audits)))))
  (testing "a gate point (:on-error :block) blocks, because it could not decide"
    (declared! :pre-tool-use [{:command (script! "broken2.sh" "echo boom >&2; exit 3")}])
    (let [r (fire :pre-tool-use {:tool_name "write"})]
      (is (= :block (:verdict r)))
      (is (str/includes? (:reason r) "exited 3")))))

(deftest a-hang-is-bounded-and-means-the-same-as-a-failure
  (testing "an observer that hangs does not hold the run"
    (declared! :post-tool-use [{:command (script! "hang.sh" "sleep 5")
                                :timeout 300}])
    (let [started (System/currentTimeMillis)
          r (fire :post-tool-use {:tool_name "read"})
          elapsed (- (System/currentTimeMillis) started)]
      (is (= :allow (:verdict r)))
      (is (< elapsed 4000) (str "should have been killed at 300ms, took " elapsed "ms"))))
  (testing "a gate that hangs blocks"
    (declared! :pre-tool-use [{:command (script! "hang2.sh" "sleep 5")
                               :timeout 300}])
    (let [r (fire :pre-tool-use {:tool_name "write"})]
      (is (= :block (:verdict r)))
      (is (str/includes? (:reason r) "timed out")))))

(deftest a-command-that-cannot-be-run-is-a-failure-not-an-exception
  (declared! :pre-tool-use [{:command "/nonexistent/never-a-command.sh"}])
  (let [r (fire :pre-tool-use {:tool_name "write"})]
    (testing "the run's fate is the point's call, not an exception's"
      (is (= :block (:verdict r)) "a gate that could not run does not silently allow")
      (is (string? (:reason r))))))

;; ------------------------------------------------------------------ payload

(deftest the-hook-is-told-what-happened-on-stdin-as-json
  (let [capture (str root "/payload.json")
        out     (str root "/payload.out")
        cmd     (script! "capture.sh" (str "cat > " capture "; cp " capture " " out))]
    (declared! :pre-tool-use [{:command cmd}])
    (project/bind! "h-payload" root)
    (try
      (fire :pre-tool-use {:tool_name "bash" :tool_input "{\"command\":\"ls\"}"
                           :project_dir root})
      (let [p (json/read-str (slurp capture))]
        (testing "the point's own name, in the spelling the payload convention uses"
          (is (= "PreToolUse" (get p "hook"))))
        (testing "the session facts every payload carries"
          (is (= root (get p "project_dir"))))
        (testing "and the point's declared facts"
          (is (= "bash" (get p "tool_name")))
          (is (str/includes? (get p "tool_input") "ls"))))
      (finally (project/bind! "h-payload" nil)))))

(deftest the-payload-carries-only-what-the-point-declares
  (let [capture (str root "/payload2.json")
        cmd     (script! "capture2.sh" (str "cat > " capture "; exit 0"))]
    (declared! :stop [{:command cmd}])
    (fire :stop {:tool_name "read" :secret_extra "nope"})
    (let [p (json/read-str (slurp capture))]
      (testing "a fact the point does not declare is not in the payload"
        (is (nil? (get p "tool_name")))
        (is (nil? (get p "secret_extra"))))
      (testing "the common four are always there"
        (is (contains? p "hook")) (is (contains? p "thread_id")) (is (contains? p "project_dir"))))))

;; ------------------------------------------------------------------- audit

(deftest one-audit-line-per-trigger-that-had-work-to-do
  (declared! :post-tool-use [{:command (script! "a.sh" "exit 0")}
                             {:command (script! "b.sh" "echo nope >&2; exit 2")}])
  (let [audits (atom [])
        r (fire :post-tool-use {:tool_name "read"} {:audit #(swap! audits conj %)})]
    (is (= 1 (count @audits)) "one line per trigger, not one per declaration")
    (let [a (first @audits)]
      (is (= "PostToolUse" (:point a)) "the payload spelling names the line")
      (is (= 2 (:matched a)))
      (is (= :block (:verdict a)) "and the folded verdict is on it")
      (is (= 2 (count (:results a))) "with every declaration's own answer beside it"))))

(deftest the-first-block-wins-in-declaration-order
  (declared! :pre-tool-use [{:command (script! "first.sh" "echo first reason >&2; exit 2")}
                            {:command (script! "second.sh" "echo second reason >&2; exit 2")}])
  (let [r (fire :pre-tool-use {:tool_name "write"})]
    (testing "both ran -- one hook cannot hide another from being told"
      (is (= 2 (:matched r))))
    (testing "and the model is given the earliest gate's reason"
      (is (str/includes? (:reason r) "first reason")))))

(deftest an-unmatched-matcher-does-not-stop-a-later-declaration-from-running
  (let [marker (str root "/later.txt")]
    (declared! :pre-tool-use [{:command (script! "no.sh" "exit 2") :matcher "edit"}
                              {:command (script! "yes.sh" (str "echo ran > " marker "; exit 0"))
                               :matcher "bash"}])
    (let [r (fire :pre-tool-use {:tool_name "bash"})]
      (is (= :allow (:verdict r)))
      (is (= 1 (:matched r)))
      (is (.exists (io/file marker))))))
