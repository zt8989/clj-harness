(ns harness.kernel.hooks.dispatch-test
  "harness.kernel.hooks.dispatch's external behavior: what a trigger does when nothing
  is declared, when something is, and what each exit code means.

  The stub commands are real files run by the real shell -- which is the point:
  exit codes, stderr-as-reason, hanging and missing binaries are exactly the
  things a mocked process would paper over."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [harness.kernel.hooks :as hooks]
            [harness.kernel.hooks.dispatch :as dispatch]
            [harness.cap.project :as project]
            [harness.test-support :as support]))

(use-fixtures :once support/with-builtins)

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
  (support/write-hooks! {point decls}))

(defn- wipe [f]
  (support/wipe-hooks!)
  (io/delete-file (io/file scripts "payload.txt") true)
  (f)
  (support/wipe-hooks!))

(use-fixtures :each wipe)

(defn- fire
  ([point fact] (fire point fact nil))
  ([point fact {:keys [thread-id audit]}]
   (dispatch/fire {:point point
                   :thread-id thread-id
                   :fact fact
                   :audit audit})))

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
  ;; The sleeps are deliberately far longer than the bound below: if the timeout
  ;; did NOT work the test waits the whole sleep and misses the bound by a mile,
  ;; instead of flirting with it on a loaded machine.
  (testing "an observer that hangs does not hold the run"
    (declared! :post-tool-use [{:command (script! "hang.sh" "sleep 60")
                                :timeout 300}])
    (let [started (System/currentTimeMillis)
          r (fire :post-tool-use {:tool_name "read"})
          elapsed (- (System/currentTimeMillis) started)]
      (is (= :allow (:verdict r)))
      (is (< elapsed 5000) (str "should have been killed at 300ms, took " elapsed "ms"))))
  (testing "a gate that hangs blocks"
    (declared! :pre-tool-use [{:command (script! "hang2.sh" "sleep 60")
                               :timeout 300}])
    (let [started (System/currentTimeMillis)
          r (fire :pre-tool-use {:tool_name "write"})
          elapsed (- (System/currentTimeMillis) started)]
      (is (= :block (:verdict r)))
      (is (str/includes? (:reason r) "timed out"))
      (is (< elapsed 5000) (str "the gate should have been killed too, took " elapsed "ms")))))

(deftest a-command-that-cannot-be-run-is-a-failure-not-an-exception
  (declared! :pre-tool-use [{:command "/nonexistent/never-a-command.sh"}])
  (let [r (fire :pre-tool-use {:tool_name "write"})]
    (testing "the run's fate is the point's call, not an exception's"
      (is (= :block (:verdict r)) "a gate that could not run does not silently allow")
      (is (string? (:reason r))))))

;; ------------------------------------------------------------------ payload

(deftest the-hook-is-told-what-happened-on-stdin-as-json
  (let [capture (str root "/payload.json")
        cmd     (script! "capture.sh" (str "cat > " capture "; exit 0"))]
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

;; ---------------------------------------------- what a declaration runs

(deftest an-in-process-declaration-is-handed-the-payload-as-a-map
  ;; The same KEYS the wire carries -- the ones a command reads on stdin -- with
  ;; the VALUES kept, because a function can hold one and JSON can only hold text.
  ;; Deliberately not a JSON round trip: wrapping the payload to "unify" the two
  ;; sides would hand a function a string where the author had a map.
  (let [seen (atom nil)]
    (hooks/session-add! "hd-run" :pre-tool-use
                        {:run (fn [payload]
                                (reset! seen payload)
                                {:exit 0 :out "" :err ""})})
    (let [r (fire :pre-tool-use {:tool_name "bash" :tool_input {:command "ls"}}
                  {:thread-id "hd-run"})]
      (is (= :allow (:verdict r)))
      (is (= "PreToolUse" (get @seen "hook")))
      (is (= "hd-run" (get @seen "thread_id")))
      (is (= "bash" (get @seen "tool_name")))
      (is (= {:command "ls"} (get @seen "tool_input"))
          "the value arrives typed, not stringified"))))

(deftest an-in-process-declaration-that-throws-is-a-verdict-not-an-exception
  (hooks/session-add! "hd-run-boom" :pre-tool-use
                      {:run (fn [_] (throw (ex-info "the gate itself is broken" {})))})
  (let [r (fire :pre-tool-use {:tool_name "write"} {:thread-id "hd-run-boom"})]
    (testing "it did not escape the seam: the run's fate is the point's call"
      (is (= :block (:verdict r)) "a gate that could not decide does not decide yes"))
    (testing "and the reason says it could not be RUN, not that it said no"
      (is (str/includes? (:reason r) "could not be run"))
      (is (str/includes? (:reason r) "the gate itself is broken")))))

(deftest a-command-and-a-function-reach-the-same-verdict
  ;; The seam's whole claim, said once as an equivalence rather than twice as two
  ;; separate assertions: nothing below the seam can tell the two apart.
  (let [cmd (do (declared! :pre-tool-use
                           [{:command (script! "same.sh" "echo same words >&2; exit 2")}])
                (fire :pre-tool-use {:tool_name "write"} {:thread-id "hd-same-c"}))]
    ;; The file declares for every thread, so it is cleared before the second half
    ;; or the two halves would not be measuring the same thing.
    (support/wipe-hooks!)
    (hooks/session-add! "hd-same-r" :pre-tool-use
                        {:run (fn [_] {:exit 2 :out "" :err "same words"})})
    (let [inline (fire :pre-tool-use {:tool_name "write"} {:thread-id "hd-same-r"})]
      (is (= :block (:verdict cmd) (:verdict inline)))
      (is (= "same words" (:reason cmd) (:reason inline))))))

;; ------------------------------------------- the one point whose stdout is content

(deftest a-content-point-collects-every-declarations-text-in-order
  ;; SystemPrompt is the one row of the point table whose stdout IS the result.
  ;; Its contract is the OPPOSITE of first-block-wins: every matched declaration
  ;; runs and every one appends, because one hook must not be able to eat
  ;; another's text.
  (support/without-builtins! "hd-blocks")
  (let [out (fn [text] {:run (fn [_] {:exit 0 :out text :err ""})})]
    (hooks/session-add! "hd-blocks" :system-prompt (out "  first  "))
    (hooks/session-add! "hd-blocks" :system-prompt (out "second"))
    (hooks/session-add! "hd-blocks" :system-prompt (out "   \n  "))
    (let [audits (atom [])
          r (fire :system-prompt {} {:thread-id "hd-blocks"
                                     :audit #(swap! audits conj %)})]
      (testing "all three ran, and each contributed its trimmed text in declaration order"
        (is (= 3 (:matched r)))
        (is (= :allow (:verdict r)))
        (is (= ["first" "second"] (:blocks r))))
      (testing "a declaration whose output trims to nothing says nothing -- not an error"
        (is (= 2 (count (:blocks r)))))
      (testing "and the trigger still lands exactly one audit line"
        (is (= 1 (count @audits)))
        (is (= "SystemPrompt" (:point (first @audits))))
        (is (= 3 (:matched (first @audits))))))))

(deftest a-content-declaration-that-refuses-contributes-no-text
  (support/without-builtins! "hd-blocks-refused")
  (hooks/session-add! "hd-blocks-refused" :system-prompt
                      {:run (fn [_] {:exit 2
                                     :out "this must not be appended to anything"
                                     :err "no system prompt for you"})})
  (let [r (fire :system-prompt {} {:thread-id "hd-blocks-refused"})]
    (is (= :block (:verdict r)))
    (is (str/includes? (:reason r) "no system prompt for you"))
    (is (= [] (:blocks r))
        "a refused declaration's stdout is not text to assemble -- its stderr is the reason")))

(deftest the-blocks-cell-is-on-one-row-of-the-table-and-not-on-the-others
  (hooks/session-add! "hd-noblocks" :stop {:command (script! "quiet.sh" "exit 0")})
  (let [r (fire :stop {} {:thread-id "hd-noblocks"})]
    (is (= :allow (:verdict r)))
    (is (not (contains? r :blocks))
        "every other point answers with a verdict, and its return has to stay what it was")))
