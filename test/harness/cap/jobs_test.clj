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

(defn- cleanup-dir!
  "A test that made a temp directory takes it away again (AGENTS.md), and the pid
  file in it is not the only thing that would otherwise accumulate."
  [dir]
  (support/wipe-tree! dir))

(defn- read-until-exit
  "Read until the answer carries a status line, then answer the accumulated read."
  [thread-id job-id]
  (support/read-until #(jobs/read-output thread-id job-id)
                      #(re-find #"\[exit" (:answer %))
                      10000))

(deftest a-job-prints-while-the-session-does-something-else
  ;; Ids are not asserted as `j1`: the counter belongs to the SESSION and never goes
  ;; back, so a job started by an earlier test pushes the numbering along. What is
  ;; asserted is the shape, and that the id the caller got is the id it can read.
  (let [id (jobs/start! "jt-a" {:command "echo one; echo two; echo three; exit 0"})]
    (testing "the answer is a job id, and nothing else"
      (is (re-matches #"j\d+" id)))
    (let [{:keys [answer lines]} (read-until-exit "jt-a" id)]
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
    (is (= ["only-once"]
           (:lines (support/read-until #(jobs/read-output "jt-c" id)
                                       #(some? (seq (:lines %))) 10000))))
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
    (is (support/alive? pid) "the job really is running")
    (jobs/shutdown!)
    (testing "the command and the child it started are both gone"
      (is (support/gone-within? pid 5000)))
    (testing "and no session can read it any more"
      (let [e (try (jobs/read-output "jt-h" id) nil (catch Exception e e))]
        (is (= :unknown-job (:reason (ex-data e))))))
    (cleanup-dir! dir)))

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
    (is (support/alive? pid) "the job really is running")
    (let [answer (jobs/stop! "jt-i" id)]
      (testing "the answer says it was stopped, not that it exited"
        (is (= "[stopped]" (last (clojure.string/split-lines answer)))))
      (testing "the command and the child it started are both gone"
        (is (support/gone-within? pid 5000))))
    (testing "and the job is forgotten, so a second stop finds nothing"
      (let [e (try (jobs/stop! "jt-i" id) nil (catch Exception e e))]
        (is (= :unknown-job (:reason (ex-data e)))))
      (let [e (try (jobs/read-output "jt-i" id) nil (catch Exception e e))]
        (is (= :unknown-job (:reason (ex-data e))))))
    (cleanup-dir! dir)))

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
    (read-until-exit "jt-k" id)
    (let [answer (jobs/stop! "jt-k" id)]
      (testing "a job that died on its own reports its own exit code"
        (is (= "[exit 3]" (last (clojure.string/split-lines answer)))))
      (testing "and is still forgotten, because remembering it forever is a leak"
        (let [e (try (jobs/read-output "jt-k" id) nil (catch Exception e e))]
          (is (= :unknown-job (:reason (ex-data e)))))))))

(deftest the-exit-line-arrives-with-the-output-that-went-with-it
  ;; The regression this exists for: asking the PROCESS whether it is gone can answer
  ;; `[exit 0]` while the last lines are still queued, and `(no new output)` + `[exit 0]`
  ;; reads as 'it printed nothing more'. So the status stays `[running]` until the pump
  ;; has seen the end of the stream -- which happens only after everything the command
  ;; wrote is in the tail. The invariant, stated once: the answer that says exit is an
  ;; answer that also carries the last line.
  (let [id (jobs/start! "jt-l" {:command "echo last-words; exit 0"})
        {:keys [answer]} (read-until-exit "jt-l" id)]
    (is (clojure.string/includes? answer "last-words"))
    (is (clojure.string/includes? answer "[exit 0]"))
    (testing "and it is over: reading again adds nothing"
      (is (= "(no new output)\n[exit 0]" (jobs/read-output "jt-l" id))))))

(deftest a-command-that-let-go-of-its-stdout-is-still-running
  ;; The other side of that rule. `exec 1>&-` closes the command's stdout while it
  ;; lives on, so the stream ends and the tail is complete -- but the command is NOT
  ;; gone, and reporting the exit of a process that is still there (or `[exit ?]`)
  ;; would be inventing one.
  (let [id (jobs/start! "jt-m" {:command "echo before-letting-go; exec 1>&-; sleep 30"})]
    (let [{:keys [answer]} (support/read-until #(jobs/read-output "jt-m" id)
                                               #(clojure.string/includes? (:answer %)
                                                                          "before-letting-go")
                                               10000)]
      (is (clojure.string/includes? answer "before-letting-go")))
    (Thread/sleep 1500)
    (is (= "[running]" (last (clojure.string/split-lines (jobs/read-output "jt-m" id)))))
    (jobs/stop! "jt-m" id)))

(deftest a-job-outlives-the-call-that-started-it
  ;; 决策 8 的另一面：**run 结束不杀它**。没有什么东西会在一轮 run 结束时收作业——
  ;; 这由「没有任何 run 结束的收尾路径」保证，而这里断言的是它最容易被弄坏的那一半：
  ;; 作业活得比发起它的那次调用久，也活得比本会话后来发起的调用久。
  (let [dir (support/temp-dir "jobs-outlive")
        pid-file (io/file dir "child.pid")
        id (jobs/start! "jt-n" {:command (str "sleep 30 & echo $! > "
                                              (shell/quote-arg (.getAbsolutePath pid-file))
                                              "; wait")})
        pid (do (Thread/sleep 1000)
                (Long/parseLong (clojure.string/trim (slurp pid-file :encoding "UTF-8"))))]
    (try
      (is (support/alive? pid))
      ;; ...the session does other work (another job, read, stopped) and comes back:
      (let [other (jobs/start! "jt-n" {:command "echo other; exit 0"})]
        (read-until-exit "jt-n" other)
        (jobs/stop! "jt-n" other))
      (is (clojure.string/includes? (jobs/read-output "jt-n" id) "[running]")
          "the first job is untouched by everything the session did after starting it")
      (is (support/alive? pid))
      (finally
        (jobs/shutdown!)
        (cleanup-dir! dir)))))

(deftest a-job-that-was-taken-out-does-not-come-back
  ;; The pump is still holding a `:next-line` when a job is stopped, and the stop
  ;; kills the process -- so its stdout ends right after the job left the registry.
  ;; Whatever the pump does in that moment must not put the job back: `update-in` on a
  ;; missing path ASSOCS what the function returns, so a nil-guarded update
  ;; (`(fn [j] (when j ...))`) re-creates the entry as nil, and every later refusal
  ;; lists a job that is not there.
  (let [id (jobs/start! "jt-o" {:command "sleep 5; echo too-late"})]
    (Thread/sleep 300)
    (jobs/stop! "jt-o" id)
    (Thread/sleep 1000)
    (let [e (try (jobs/read-output "jt-o" id) nil (catch Exception e e))]
      (is (= :unknown-job (:reason (ex-data e))))
      (is (not (contains? (set (:known (ex-data e))) id))
          "a job that was taken out must not come back as a nil entry"))))
