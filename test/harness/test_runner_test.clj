(ns harness.test-runner-test
  "The isolation verdict's three branches, and the time limits' branches, driven
  directly.

  WHY THIS FILE EXISTS AT ALL, and why it is not just 'the runner ran and was
  green': `harness.test-runner/isolation-verdict` decides the ONE claim this repo
  makes about its own test runs -- that they touch the developer's home nowhere --
  and until this file it had exactly one witness, the happy path. The two branches
  below it are the interesting ones, and BOTH were seen in the wild before either
  had a test: a suite that resolved the real store (the failure), and a file moved
  by the live harness session while a green suite ran (the note).

  THE TIME LIMITS ARE THE SECOND HALF, and for the same reason: 'the suite stopped
  returning' is the branch nobody can reach on purpose, because reaching it means
  hanging. So the deadline, the report and the exit code are driven here with a
  thunk that never returns and with plain maps -- no run is ever stuck to test that
  a stuck run is named.

  PURE INPUTS, which is the point of the verdict's shape: a File, two fingerprints
  and a set of paths. Nothing here opens a store, writes anything, or waits --
  which is also how a test can assert the failure branch WITHOUT doing the thing
  the failure is about. The one thing here that waits is the deadline test, and it
  waits 150ms and abandons a daemon thread."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [harness.infra.db :as db]
            [harness.infra.home :as home]
            [harness.test-runner :as runner]))

(def ^:private real-store
  ;; A PATH THAT IS NEVER OPENED, and one of the shapes a real home has. No file is
  ;; created: the verdict reads the fingerprints it is handed, so a path is enough.
  (io/file "/nowhere/the-developer/.clj-harness/harness.db"))

(defn- said
  "What F printed on the process's stderr, as one string."
  [f]
  (with-out-str (binding [*err* *out*] (f))))

(deftest nothing-happened-is-green-and-silent
  (is (true? (runner/isolation-verdict real-store [100 1] #{} [100 1]))
      "an untouched store, never opened here, is exactly what this fixture promises")
  (is (= "" (said #(runner/isolation-verdict real-store [100 1] #{} [100 1])))
      "and it says nothing: a green run has no note to print"))

(deftest a-store-this-process-opened-fails-the-run-by-name
  (testing "the file MOVED -- the plain case, and the one the fixture was built for"
    (let [path (.getAbsolutePath real-store)]
      (is (false? (runner/isolation-verdict real-store [100 1] #{path} [200 2])))
      (let [message (said #(runner/isolation-verdict real-store [100 1] #{path} [200 2]))]
        (is (str/includes? message "ISOLATION FAILURE"))
        (is (str/includes? message path) "the store's path, so the offender can be found")
        (is (str/includes? message "this process opened")
            "attributed to this process, which is the only thing it can be sure of"))))

  (testing "opened and NOT moved: a read into the wrong home is already the wrong home"
    ;; A suite that reads the developer's store is not hermetic even when it writes
    ;; nothing -- their rows, their config, their anchors would be part of the answer.
    ;; So this branch fails too, and says which of the two it is seeing.
    (let [path (.getAbsolutePath real-store)]
      (is (false? (runner/isolation-verdict real-store [100 1] #{path} [100 1])))
      (is (str/includes? (said #(runner/isolation-verdict real-store [100 1] #{path} [100 1]))
                         "unchanged"))))

  (testing "a DIFFERENT store than the one being asked about is not a hit"
    ;; The set is compared by absolute path, so the temp store this run actually
    ;; used cannot be mistaken for the developer's -- which is the whole reason the
    ;; set holds paths rather than, say, a count.
    (is (true? (runner/isolation-verdict real-store [100 1]
                                         #{(.getAbsolutePath (io/file "/tmp/x/harness.db"))}
                                         [200 2]))
        "somebody else's change, with our opening going to another path: a note, not a failure")))

(deftest somebody-elses-write-is-a-note-and-not-a-failure
  ;; THE CASE THAT MADE THIS VERDICT EXISTS: on 2026-09-20 a full suite run left the
  ;; developer's store byte-and-mtime identical, while a single anchored file read by
  ;; the LIVE HARNESS SESSION moved its mtime -- and the verdict, built from the file
  ;; alone, reported the neighbour as a violation. The run was green; the signal was
  ;; wrong. Now the file moving without an opening says so out loud and stays green.
  (is (true? (runner/isolation-verdict real-store [100 1] #{} [200 2])))
  (let [message (said #(runner/isolation-verdict real-store [100 1] #{} [200 2]))]
    (is (str/includes? message "ISOLATION NOTE"))
    (is (not (str/includes? message "ISOLATION FAILURE")))
    (is (str/includes? message "NEVER OPENED IT")
        "it names the one fact it can stand behind: this process did not go there")
    (is (str/includes? message "NOT a failure")))

  (testing "a store that APPEARED during the run is the same kind of news"
    (is (true? (runner/isolation-verdict real-store nil #{} [100 1]))
        "nil is absent: a store created by somebody else's session is still not ours")))

(deftest the-record-of-what-this-process-opened-is-what-decides-it
  ;; The two halves meet here: harness.infra.db REMEMBERS the paths, and the verdict
  ;; READS them. If the remembering ever stopped, this verdict would silently become
  ;; the file-only check it was -- so the seam is asserted rather than assumed.
  (testing "opening a store records its path, and the record can be emptied"
    (db/forget-store-paths-opened!)
    (is (= [] (sort (db/store-paths-opened))) "empty to start with, as the runner leaves it")
    (db/select "SELECT 1") ;; whatever the root in force is, this opens THAT store
    (is (contains? (db/store-paths-opened) (.getAbsolutePath (home/db-file)))
        "the store this process just opened is in the set")
    (db/forget-store-paths-opened!)
    (is (= [] (sort (db/store-paths-opened))) "and the runner's clearing call is what it says"))
  (db/forget-store-paths-opened!))

;; ------------------------------------------------------------------ the limits

(defn- a-thunk-that-never-returns
  "Parked on a promise nobody delivers, which is the shape of a hung test: it holds
  its thread and never comes back, and the THREAD is what the runner has to stop
  waiting on. A named def rather than a closure so the assertion below can look for
  it by name in the captured stack -- which is the whole claim of that branch."
  [p]
  @p)

(deftest a-thunk-that-returns-is-answered-with-its-value
  (let [r (runner/run-with-deadline 5000 (constantly 42))]
    (is (= :ok (:status r)))
    (is (= 42 (:value r)))
    (is (number? (:ms r)) "the cost comes back with it: that is the per-namespace line")))

(deftest a-thunk-that-throws-is-reported-not-thrown
  ;; Reported, because the caller has to be able to tell 'this namespace blew up
  ;; while it was running' from 'this namespace never came back': the second one is
  ;; a limit and the first one is a failure, and they exit differently.
  (let [boom (ex-info "boom" {})
        r    (runner/run-with-deadline 5000 #(throw boom))]
    (is (= :threw (:status r)))
    (is (identical? boom (:threw r)))))

(deftest a-thunk-past-its-deadline-is-a-timeout-with-its-stack
  (let [parked (promise)
        r      (runner/run-with-deadline 150 #(a-thunk-that-never-returns parked))]
    (try
      (is (= :timeout (:status r)))
      (is (= 150 (:budget-ms r)))
      (is (< (:ms r) 5000)
          "it stopped waiting at the limit: a passing version of this test cannot have waited for a thunk that never returns")
      (is (= "WAITING" (:state r)) "parked on the promise, and the state says which kind of stuck it is")
      (is (str/includes? (str/join "\n" (:stack r)) "a_thunk_that_never_returns")
          "the frame that is stuck, by name -- this is the jstack that used to be taken by hand")
      (finally (deliver parked :go) "let the abandoned thread go, now that it has been read"))))

(deftest a-namespace-timeout-names-it-and-says-where-it-was
  (let [text (str/join "\n" (runner/limit-report
                             {:kind :namespace
                              :subject 'harness.kernel.tools-test
                              :budget-ms 300000
                              :ms 300004
                              :state "RUNNABLE"
                              :stack ["harness.kernel.tools-test$a-test.invokeStatic(tools_test.clj:42)"]
                              :total 51
                              :ran ['harness.kernel.install-test 'harness.infra.db-test]
                              :not-run ['harness.edge.http-test]}))]
    (is (str/includes? text "harness.kernel.tools-test") "the namespace, which is what the reader needs first")
    (is (str/includes? text "300s") "the limit that was hit, in the unit the environment variable takes")
    (is (str/includes? text "exit code 2") "and what the process is about to do about it")
    (is (str/includes? text "RUNNABLE") "the state: a spin and a lock need different fixes")
    (is (str/includes? text "a-test.invokeStatic")
        "the frame, so the next step is reading code rather than reaching for jstack")
    (is (str/includes? text "2 namespaces that FINISHED")
        "how much of the suite is accounted for")
    (is (str/includes? text "never started: harness.edge.http-test")
        "and what did not get to run at all")))

(deftest a-load-timeout-says-nothing-has-run
  (let [text (str/join "\n" (runner/limit-report
                             {:kind :loading, :budget-ms 300000, :total 51, :ran []
                              :not-run ['harness.kernel.tools-test]
                              :stack ["clojure.lang.RT.load(RT.java:1)"]}))]
    (is (str/includes? text "LOADING"))
    (is (str/includes? text "Nothing has run yet") "no counters exist to report, and it should not pretend otherwise")
    (is (str/includes? text "clojure.lang.RT.load"))))

(deftest a-run-past-its-total-budget-names-what-never-ran
  (let [text (str/join "\n" (runner/limit-report
                             {:kind :run, :budget-ms 1800000, :ms 1800004, :total 51
                              :ran ['harness.kernel.install-test]
                              :not-run ['harness.edge.http-test 'harness.layers-test]}))]
    (is (str/includes? text "1800s"))
    (is (str/includes? text "1800.0s had elapsed") "how long it had actually been running")
    (is (str/includes? text "1 of 51") "how far it got")
    (is (str/includes? text "never started: harness.edge.http-test, harness.layers-test")
        "the names, because 'which of my tests did not run' is the only useful question here")))

(deftest a-limit-report-survives-a-map-that-is-missing-a-key
  ;; It is printed ON THE WAY OUT, so throwing here would turn 'the suite hit its
  ;; limit' into 'the suite hit its limit and then said nothing about it'.
  (is (seq (runner/limit-report {:kind :run, :budget-ms 1000, :total 2, :ran [], :not-run ['b]})))
  (is (seq (runner/limit-report {:kind :namespace, :subject 'a-test, :budget-ms 1000, :ran []}))))

(deftest a-limit-report-goes-to-stderr-so-a-blocked-stdout-cannot-swallow-it
  ;; The hang this whole section exists for can BE a blocked stdout: a test writing
  ;; to a pipe whose reader went away stops inside the write, and a verdict sent to
  ;; that same stream is the one message that never arrives.
  (let [err (java.io.StringWriter.)
        out (with-out-str (binding [*err* err]
                            (runner/print-limit-report! {:kind :run, :budget-ms 1000,
                                                         :total 2, :ran ['a], :not-run ['b]})))]
    (is (= "" out) "stdout is left untouched")
    (is (str/includes? (str err) "TEST RUN TIMEOUT"))))

(deftest a-limit-that-is-not-a-positive-number-of-seconds-is-refused
  (is (= 90000 (runner/limit-ms "90" 1000)) "a set limit is read as seconds")
  (is (= 1000 (runner/limit-ms nil 1000)) "unset is the default, and that is not an error")
  (doseq [bad ["" "0" "-5" "abc" "1.5" " 90"]]
    (is (thrown? clojure.lang.ExceptionInfo (runner/limit-ms bad 1000))
        (str "refused rather than silently ignored: " (pr-str bad)))))

(deftest the-exit-code-says-which-of-the-three-things-happened
  (is (= 0 (runner/exit-code {:status :ok, :counters {:test 1, :pass 1, :fail 0, :error 0}} true)))
  (is (= 1 (runner/exit-code {:status :ok, :counters {:test 1, :pass 0, :fail 1, :error 0}} true)))
  (is (= 1 (runner/exit-code {:status :ok, :counters {:test 1, :pass 1, :fail 0, :error 0}} false))
      "the isolation verdict folds into the same code as a failing test, as it always has")
  (is (= 2 (runner/exit-code {:status :timeout, :kind :namespace,
                              :counters {:test 0, :pass 0, :fail 0, :error 0}} true))
      "a limit is not a verdict")
  (is (= 2 (runner/exit-code {:status :timeout, :kind :namespace,
                              :counters {:test 9, :pass 8, :fail 1, :error 0}} false))
      "and it stays 2 when the namespaces that DID run had failures: the run never finished"))

