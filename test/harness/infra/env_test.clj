(ns harness.infra.env-test
  "harness.infra.env: this machine's platform, the shell commands go to, and which
  command-line enhancers that shell can see.

  THE PROBE IS DRIVEN RATHER THAN OBSERVED. What it must never do is read the JVM's
  environment -- the shell is started as a LOGIN shell and its PATH is not the JVM's
  -- so the assertion watches the seam it uses (harness.infra.shell/run, the same one
  a tool call goes through) instead of comparing two answers that could be wrong in
  the same way. And both halves of the machine are handed in, so a Windows shell and
  a missing program are assertable here, on a machine that has neither."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [harness.infra.env :as env]
            [harness.infra.shell :as shell]
            [harness.test-support :as support]))

(def ^:private posix-shell
  {:command "/bin/bash" :kind :bash :posix? true :argv-prefix ["-lc"]})

(def ^:private pwsh
  {:command "/usr/local/bin/pwsh" :kind :pwsh :posix? false
   :argv-prefix ["-NoProfile" "-Command"]})

(deftest the-lines-state-the-platform-the-shell-and-both-halves-of-the-list
  (support/with-machine pwsh #{"rg" "git"}
    (fn []
      (let [lines (env/lines)]
        (testing "one line per fact, in the order the block reads best"
          (is (= 4 (count lines)))
          (is (str/starts-with? (first lines) "platform: "))
          (is (str/starts-with? (second lines) "shell: ")))
        (testing "the shell line REPORTS the resolution rather than deciding again"
          (is (str/includes? (second lines) "pwsh"))
          (is (str/includes? (second lines) "/usr/local/bin/pwsh"))
          (is (str/includes? (second lines) "-NoProfile -Command")))
        (testing "and the tool list says what is here AND what is not"
          ;; 'Write an `rg` and the machine has no rg' is the mistake this half exists
          ;; to prevent, so absence is an answer with a line of its own.
          (is (= "available: rg, git" (nth lines 2)))
          (is (= "not found: fd, jq" (nth lines 3))))))))

(deftest every-shape-of-the-list-can-be-said
  (testing "nothing from the list"
    (support/with-machine posix-shell #{}
      (fn []
        (is (= "available: (none from the list)" (nth (env/lines) 2))))))
  (testing "everything on it"
    (support/with-machine posix-shell (set env/enhancers)
      (fn []
        (is (= "not found: (nothing from the list)" (nth (env/lines) 3)))))))

(deftest a-question-that-could-not-be-put-is-said-not-guessed
  (support/with-machine posix-shell :unknown
    (fn []
      (is (str/includes? (nth (env/lines) 2) "available: unknown"))
      (is (not-any? #(str/includes? % "not found:") (env/lines))
          "nothing is claimed to be missing -- it was never asked"))))

(deftest a-machine-with-no-shell-says-nothing-can-be-run
  ;; The most important sentence the block can carry on such a machine: a model that
  ;; writes a command anyway should have been told not to.
  (support/with-machine nil :unknown
    (fn []
      (is (str/includes? (nth (env/lines) 1) "nothing can be run")))))

(deftest the-probe-asks-the-shell-and-never-the-jvm
  (let [asked (atom [])]
    (try
      (with-redefs [shell/run (fn [req] (swap! asked conj req) {:exit 0 :out "rg\n" :err ""})]
        (is (= #{"rg"} (env/probe*))))
      (finally (env/reset-probe!)))
    (is (= 1 (count @asked)) "one spawn, through the shell seam")
    (is (str/includes? (:command (first @asked)) "rg")
        "and the command it runs names the tools it is asking about")))

(deftest a-shell-that-will-not-start-is-unknown-rather-than-a-throw
  ;; Not a failure: a run must still start on a machine whose shell is broken, and the
  ;; block says it could not ask instead of disappearing.
  (try
    (with-redefs [shell/run (fn [_] (throw (ex-info "no shell here" {})))]
      (is (= :unknown (env/probe*))))
    (finally (env/reset-probe!))))

(deftest the-probe-is-answered-once-per-process
  (let [asked (atom 0)]
    (try
      (with-redefs [env/probe* (fn [] (swap! asked inc) #{"rg"})]
        (is (= #{"rg"} (env/probe)))
        (is (= #{"rg"} (env/probe)))
        (is (= 1 @asked) "asked once, then remembered"))
      (finally (env/reset-probe!)))
    (is (some? (env/probe)) "and the real answer is back afterwards")))

(deftest this-machines-platform-is-one-of-the-three
  (is (contains? #{"windows" "macos" "linux"} (env/platform))))

(deftest the-temp-dirs-are-this-machines-canonical-and-deduplicated
  ;; DRIVEN WITH THE OVERRIDE OFF, because the test runner stands its own in (see
  ;; harness.test-runner/isolate!) and this is the case that would otherwise never see
  ;; the machine's real answer.
  (binding [env/*temp-dir-override* nil]
    (let [dirs (env/temp-dirs)
          tmp  (.getCanonicalPath (io/file (System/getProperty "java.io.tmpdir")))
          slash-tmp (io/file "/tmp")]
      (is (contains? (set dirs) tmp)
          "java.io.tmpdir is there, canonical -- /var/... and /private/var/... are one answer")
      (is (= (seq dirs) (distinct (seq dirs))) "no directory is listed twice")
      (testing "POSIX /tmp is offered where it is a real absolute directory of its own"
        (if (and (.isAbsolute slash-tmp) (.isDirectory slash-tmp))
          (is (contains? (set dirs) (.getCanonicalPath slash-tmp))
              "both spellings a person actually writes are here")
          (is (= 1 (count dirs))
              "no /tmp on this platform, so java.io.tmpdir is the whole list"))))))

(deftest a-test-can-stand-its-own-temp-dirs-in
  (binding [env/*temp-dir-override* ["/nowhere/but-here"]]
    (is (= ["/nowhere/but-here"] (env/temp-dirs))
        "the override REPLACES the machine's answer rather than adding to it")))
