(ns harness.cap.jobs-test
  "Background jobs: what starts one, what it says, and what stops it.

  REAL PROCESSES, because that is what a job is -- and short ones, with a deadline
  on every wait, so a broken implementation fails rather than hangs."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [harness.cap.jobs :as jobs]
            [harness.infra.shell :as shell]
            [harness.test-support :as support]))

;; Every job this namespace starts is stopped on the way out, whatever happened in
;; the test: a suite that leaves a `sleep 30` behind is the leak this feature exists
;; to make impossible.
(use-fixtures :each (fn [f] (try (f) (finally (jobs/shutdown!)))))

(defn- alive? [pid]
  (boolean (when-let [h (.orElse (java.lang.ProcessHandle/of (long pid)) nil)]
             (.isAlive ^java.lang.ProcessHandle h))))

(defn- gone-within?
  "Polled: a process that was just killed can still be seen for a moment (a zombie
  until its parent reaps it), so a single read would be a coin toss."
  [pid ms]
  (let [deadline (+ (System/currentTimeMillis) (long ms))]
    (loop []
      (cond
        (not (alive? pid)) true
        (> (System/currentTimeMillis) deadline) false
        :else (do (Thread/sleep 50) (recur))))))

(defn- read-until
  "Read JOB-ID, accumulating what comes back, until PRED -- a fn of
  `{:answer .. :lines ..}` -- says it is enough, or MS runs out. Reads are the only
  way to see a job, so waiting for a job means reading it, which is exactly what the
  model has to do."
  [thread-id job-id pred ms]
  (let [deadline (+ (System/currentTimeMillis) (long ms))]
    (loop [seen []]
      (let [answer (jobs/read-output thread-id job-id)
            seen   (into seen (remove #(or (re-find #"^\[" %) (= "(no new output)" %))
                                      (clojure.string/split-lines answer)))
            state  {:answer answer :lines seen}]
        (cond
          (pred state) state
          (> (System/currentTimeMillis) deadline) state
          :else (do (Thread/sleep 50) (recur seen)))))))

(deftest a-job-prints-while-the-session-does-something-else
  ;; Ids are not asserted as `j1`: the counter belongs to the SESSION and never goes
  ;; back, so a job started by an earlier test pushes the numbering along. What is
  ;; asserted is the shape, and that the id the caller got is the id it can read.
  (let [id (jobs/start! "jt-a" {:command "echo one; echo two; echo three; exit 0"})]
    (testing "the answer is a job id, and nothing else"
      (is (re-matches #"j\d+" id)))
    (let [{:keys [answer lines]} (read-until "jt-a" id #(re-find #"\[exit" (:answer %)) 10000)]
      (testing "the lines it printed come back, in order"
        (is (= ["one" "two" "three"] lines)))
      (testing "and the status line says how it ended"
        (is (re-find #"\[exit 0\]" answer))))))

(deftest nothing-new-is-not-the-same-as-finished
  (let [id (jobs/start! "jt-b" {:command "sleep 30"})]
    (testing "a job that has said nothing says so, and says it is still running"
      (is (= "(no new output)\n[running]" (jobs/read-output "jt-b" id))))
    (testing "reading it again does not wait for it to say something"
      (let [started (System/currentTimeMillis)
            answer  (jobs/read-output "jt-b" id)]
        (is (= "(no new output)\n[running]" answer))
        (is (< (- (System/currentTimeMillis) started) 2000))))))

(deftest the-lines-a-session-already-read-do-not-come-back
  ;; The cursor, which is the whole reason a read is stateful: a reader that got the
  ;; same lines twice would have to work out which of them were new.
  (let [id (jobs/start! "jt-c" {:command "echo only-once; sleep 30"})]
    (is (= ["only-once"] (:lines (read-until "jt-c" id #(some? (seq (:lines %))) 10000))))
    (let [again (jobs/read-output "jt-c" id)]
      (is (= "(no new output)\n[running]" again)))))

(deftest a-chatty-job-keeps-its-tail-and-counts-what-it-lost
  ;; 600 lines through a 500-line tail: the reader gets the LAST 500 and is told
  ;; about the 100 it never saw. The command sleeps after printing so the tail is
  ;; settled before the single read that is asserted on.
  (let [id (jobs/start! "jt-d" {:command (str "i=0; while [ $i -lt 600 ]; do echo line-$i;"
                                              " i=$((i+1)); done; sleep 30")})]
    (Thread/sleep 3000)
    (let [answer (jobs/read-output "jt-d" id)
          lines  (clojure.string/split-lines answer)
          body   (remove #(re-find #"^\[" %) lines)]
      (testing "the tail is the last 500 lines, not the first"
        (is (= 500 (count body)))
        (is (= "line-100" (first body)))
        (is (= "line-599" (last body))))
      (testing "and the answer says how many lines went by unread"
        (is (some #{"[100 earlier lines were dropped]"} lines)))
      (testing "the job is still running while it does that"
        (is (= "[running]" (last lines)))))))

(deftest a-job-belongs-to-the-session-that-started-it
  (let [id (jobs/start! "jt-e" {:command "sleep 30"})]
    (testing "another session cannot read it, and is told so by name"
      (let [e (try (jobs/read-output "jt-f" id) nil (catch Exception e e))]
        (is (some? e))
        (is (= :unknown-job (:reason (ex-data e))))
        (is (clojure.string/includes? (ex-message e) "This session has no background jobs"))))
    (testing "while the session that started it can"
      (is (clojure.string/includes? (jobs/read-output "jt-e" id) "[running]")))))

(deftest an-unknown-job-is-refused-with-the-ones-that-exist
  (let [a (jobs/start! "jt-g" {:command "sleep 30"})
        b (jobs/start! "jt-g" {:command "sleep 30"})]
    (testing "the refusal names the id that was asked for"
      (let [e (try (jobs/read-output "jt-g" "j9") nil (catch Exception e e))]
        (is (some? e))
        (is (clojure.string/includes? (ex-message e) "unknown job: j9"))))
    (testing "and lists the ids this session does have, so the caller can retry"
      (let [e (try (jobs/read-output "jt-g" "j9") nil (catch Exception e e))
            known (:known (ex-data e))]
        (is (every? (set known) [a b]))
        (is (clojure.string/includes? (ex-message e) a))
        (is (clojure.string/includes? (ex-message e) b))))))

(deftest the-process-going-away-takes-its-jobs-with-it
  ;; `shutdown!` is what the exit hook runs, and it is asserted by CALLING it: a
  ;; forked JVM against this repo's config home hangs (harness.cap.mcp-test records
  ;; that), so the reap is measured here and the hook's installation by the count
  ;; below.
  (let [dir (support/temp-dir "jobs-shutdown")
        pid-file (io/file dir "child.pid")
        id (jobs/start! "jt-h" {:command (str "sleep 30 & echo $! > "
                                              (shell/quote-arg (.getAbsolutePath pid-file))
                                              "; wait")})
        pid (do (Thread/sleep 1000)
                (Long/parseLong (clojure.string/trim (slurp pid-file :encoding "UTF-8"))))]
    (is (alive? pid) "the job really is running")
    (jobs/shutdown!)
    (testing "the command and the child it started are both gone"
      (is (gone-within? pid 5000)))
    (testing "and no session can read it any more"
      (let [e (try (jobs/read-output "jt-h" id) nil (catch Exception e e))]
        (is (= :unknown-job (:reason (ex-data e))))))))

(deftest the-exit-hook-is-installed-once-and-only-once
  (let [installs (atom 0)]
    (try
      (with-redefs [jobs/install-hook! (fn [_] (swap! installs inc))]
        (jobs/reset-exit-hook!)
        (dotimes [_ 3] (jobs/ensure-exit-hook!))
        (is (= 1 @installs) "three calls, one hook -- two would run the reap twice"))
      (finally (jobs/ensure-exit-hook!)))))

(deftest stopping-a-job-takes-the-whole-tree-and-forgets-it
  (let [dir (support/temp-dir "jobs-stop")
        pid-file (io/file dir "child.pid")
        id (jobs/start! "jt-i" {:command (str "sleep 30 & echo $! > "
                                              (shell/quote-arg (.getAbsolutePath pid-file))
                                              "; wait")})
        pid (do (Thread/sleep 1000)
                (Long/parseLong (clojure.string/trim (slurp pid-file :encoding "UTF-8"))))]
    (is (alive? pid) "the job really is running")
    (let [answer (jobs/stop! "jt-i" id)]
      (testing "the answer says it was stopped, not that it exited"
        (is (= "[stopped]" (last (clojure.string/split-lines answer)))))
      (testing "the command and the child it started are both gone"
        (is (gone-within? pid 5000))))
    (testing "and the job is forgotten, so a second stop finds nothing"
      (let [e (try (jobs/stop! "jt-i" id) nil (catch Exception e e))]
        (is (= :unknown-job (:reason (ex-data e)))))
      (let [e (try (jobs/read-output "jt-i" id) nil (catch Exception e e))]
        (is (= :unknown-job (:reason (ex-data e))))))))

(deftest stopping-a-job-returns-what-this-session-never-read
  ;; Stopping and reading are not interchangeable: a stop that dropped the unread
  ;; lines would make the order of two calls change what the model can know.
  (let [id (jobs/start! "jt-j" {:command "echo nobody-read-this; sleep 30"})]
    (Thread/sleep 1000)
    (let [answer (jobs/stop! "jt-j" id)
          lines  (clojure.string/split-lines answer)]
      (is (some #{"nobody-read-this"} lines))
      (is (= "[stopped]" (last lines))))))

(deftest a-job-that-ended-gives-its-exit-code-and-is-forgotten
  (let [id (jobs/start! "jt-k" {:command "exit 3"})]
    (read-until "jt-k" id #(re-find #"\[exit" (:answer %)) 10000)
    (let [answer (jobs/stop! "jt-k" id)]
      (testing "a job that died on its own reports its own exit code"
        (is (= "[exit 3]" (last (clojure.string/split-lines answer)))))
      (testing "and is still forgotten, because remembering it forever is a leak"
        (let [e (try (jobs/read-output "jt-k" id) nil (catch Exception e e))]
          (is (= :unknown-job (:reason (ex-data e)))))))))
