(ns harness.cap.jobs-test
  "Background jobs: what starts one, where its output goes, and what stops it.

  REAL PROCESSES, because that is what a job is -- and short ones, with a deadline
  on every wait, so a broken implementation fails rather than hangs.

  EVERY ASSERTION ABOUT WHAT A JOB SAID IS AN ASSERTION ABOUT ITS RECORD FILE,
  because that is what a job's output IS now: there is no read verb here and no
  cursor, so the test reads the file the way the model does."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [harness.cap.jobs :as jobs]
            [harness.infra.home :as home]
            [harness.infra.shell :as shell]
            [harness.test-support :as support]))

;; Every job this namespace starts is stopped on the way out, whatever happened in
;; the test: a suite that leaves a `sleep 30` behind is the leak this feature exists
;; to make impossible. The records go with it (`shutdown!`), so a case cannot read
;; one left by the case before it.
(use-fixtures :each (fn [f] (try (f) (finally (jobs/shutdown!)))))

(defn- cleanup-dir!
  "A test that made a temp directory takes it away again (AGENTS.md), and the pid
  file in it is not the only thing that would otherwise accumulate."
  [dir]
  (support/wipe-tree! dir))

(defn- record
  "JOB's record as it stands, as a vector of lines -- empty when the file is empty
  (a job that has not said anything yet) or not there at all."
  [path]
  (let [f (io/file path)]
    (if (.exists f)
      (let [text (slurp f :encoding "UTF-8")]
        (if (str/blank? text) [] (vec (str/split-lines text))))
      [])))

(defn- record-until
  "JOB's record, once PRED is true of the whole file -- or what it holds when MS runs
  out.

  THE SAME LOOP THE MODEL HAS TO RUN: the record is written by another thread and
  flushed per line, so 'wait for the job' is 'keep reading the file'. No cursor and no
  accumulation here either, for the same reason there is none in the module: the file
  is read fresh every time, and what it says is what it says."
  [path pred ms]
  (let [deadline (+ (System/currentTimeMillis) (long ms))]
    (loop []
      (let [answer (slurp path :encoding "UTF-8")]
        (cond
          (pred answer) answer
          (> (System/currentTimeMillis) deadline) answer
          :else (do (Thread/sleep 20) (recur)))))))

(defn- body-lines
  "The COMMAND's own lines: the record without the one line this repo appends to it."
  [answer]
  (remove #(re-find #"^\[(exit|stopped)" %) (str/split-lines answer)))

(deftest a-job-prints-into-a-file-of-its-own
  ;; Ids are not asserted as `j1`: the counter belongs to the SESSION and never goes
  ;; back, so a job started by an earlier test pushes the numbering along. What is
  ;; asserted is the shape, where the record is, and what is in it.
  (let [{:keys [id path]} (jobs/start! "jt-a" {:command "echo one; echo two; echo three; exit 0"})]
    (testing "the answer names the job and its record"
      (is (re-matches #"j\d+" id))
      (is (.exists (io/file path)) "the record exists as soon as the job does"))
    (testing "and the record is under this process's configuration home"
      ;; Which is what makes `read` / `grep` reach it without parking a human
      ;; (cap.project/fence lists the config home as free) and what makes `bash`
      ;; able to `tail` it at all.
      (is (str/starts-with? path (home/root))))
    (let [answer (record-until path #(re-find #"\[exit" %) 10000)]
      (testing "the lines it printed are in it, in order"
        (is (= ["one" "two" "three"] (vec (body-lines answer)))))
      (testing "and the last line says how it ended"
        (is (= "[exit 0]" (last (str/split-lines answer))))))))

(deftest a-job-that-has-not-printed-yet-has-an-empty-record
  (let [{:keys [path]} (jobs/start! "jt-b" {:command "sleep 30"})]
    (Thread/sleep 300)
    (is (= "" (slurp path :encoding "UTF-8"))
        "nothing invented: no header, no timestamps, no status line")))

(deftest every-line-a-chatty-job-prints-is-kept
  ;; 600 lines. The record is the WHOLE of it: there is no tail to fill up and
  ;; therefore no line counting what scrolled off -- which is the sentence this module
  ;; used to be proud of, and which this feature deletes rather than improves.
  (let [{:keys [path]} (jobs/start! "jt-d" {:command (str "i=0; while [ $i -lt 600 ]; do echo line-$i;"
                                                          " i=$((i+1)); done; sleep 30")})]
    (let [answer (record-until path #(= 600 (count (body-lines %))) 20000)
          lines  (vec (body-lines answer))]
      (testing "all of it, first line to last"
        (is (= 600 (count lines)))
        (is (= "line-0" (first lines)))
        (is (= "line-599" (last lines))))
      (testing "and nothing in the record talks about lines that went missing"
        (is (not-any? #(re-find #"dropped|earlier lines" %) lines))))))

(deftest a-job-belongs-to-the-session-that-started-it
  (let [{:keys [id]} (jobs/start! "jt-e" {:command "sleep 30"})]
    (testing "another session cannot stop it, and is told so by name"
      (let [e (try (jobs/stop! "jt-f" id) nil (catch Exception e e))]
        (is (some? e))
        (is (= :unknown-job (:reason (ex-data e))))
        (is (str/includes? (ex-message e) "This session has no background jobs"))))
    (testing "while the session that started it can"
      (is (true? (:stopped? (jobs/stop! "jt-e" id)))))))

(deftest an-unknown-job-is-refused-with-the-ones-that-exist
  (let [a (:id (jobs/start! "jt-g" {:command "sleep 30"}))
        b (:id (jobs/start! "jt-g" {:command "sleep 30"}))]
    (testing "the refusal names the id that was asked for"
      (let [e (try (jobs/stop! "jt-g" "j9") nil (catch Exception e e))]
        (is (some? e))
        (is (str/includes? (ex-message e) "unknown job: j9"))))
    (testing "and lists the ids this session does have, so the caller can retry"
      (let [e (try (jobs/stop! "jt-g" "j9") nil (catch Exception e e))
            known (:known (ex-data e))]
        (is (every? (set known) [a b]))
        (is (str/includes? (ex-message e) a))
        (is (str/includes? (ex-message e) b))))))

(deftest the-process-going-away-takes-its-jobs-and-their-records-with-it
  ;; `shutdown!` is what the exit hook runs, and it is asserted by CALLING it: a
  ;; forked JVM against this repo's config home hangs (harness.cap.mcp-test records
  ;; that), so the reap is measured here and the hook's installation by the count
  ;; below.
  (let [dir (support/temp-dir "jobs-shutdown")
        pid-file (io/file dir "child.pid")
        {:keys [id path]} (jobs/start! "jt-h" {:command (str "sleep 30 & echo $! > "
                                                             (shell/quote-arg (.getAbsolutePath pid-file))
                                                             "; wait")})
        pid (do (Thread/sleep 1000)
                (Long/parseLong (str/trim (slurp pid-file :encoding "UTF-8"))))]
    (is (support/alive? pid) "the job really is running")
    (jobs/shutdown!)
    (testing "the command and the child it started are both gone"
      (is (support/gone-within? pid 5000)))
    (testing "and the record is gone with them -- a job lives as long as the process"
      (is (not (.exists (io/file path)))))
    (testing "and no session can stop it any more"
      (let [e (try (jobs/stop! "jt-h" id) nil (catch Exception e e))]
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
        {:keys [id path]} (jobs/start! "jt-i" {:command (str "sleep 30 & echo $! > "
                                                             (shell/quote-arg (.getAbsolutePath pid-file))
                                                             "; wait")})
        pid (do (Thread/sleep 1000)
                (Long/parseLong (str/trim (slurp pid-file :encoding "UTF-8"))))]
    (is (support/alive? pid) "the job really is running")
    (let [answer (jobs/stop! "jt-i" id)]
      (testing "the answer says this call stopped it, and where the record is"
        (is (true? (:stopped? answer)))
        (is (= path (:path answer))))
      (testing "the command and the child it started are both gone"
        (is (support/gone-within? pid 5000))))
    (testing "the record SURVIVES the stop -- that is the moment you read it"
      (is (.exists (io/file path)))
      (is (= "[stopped]" (last (record path)))))
    (testing "and nothing is written into it after that last line"
      ;; The pump still holds a `:next-line` when the stop happens; if it could
      ;; write after `[stopped]`, the record would end on a line of output and read
      ;; like a command still running.
      (Thread/sleep 1500)
      (is (= "[stopped]" (last (record path)))))
    (testing "and the job is forgotten, so a second stop finds nothing"
      (let [e (try (jobs/stop! "jt-i" id) nil (catch Exception e e))]
        (is (= :unknown-job (:reason (ex-data e))))))
    (cleanup-dir! dir)))

(deftest a-stopped-job-keeps-what-it-had-said
  ;; Stopping and reading are not interchangeable, and the order a caller chose must
  ;; not change what it can know: a stop that took the output with it would make the
  ;; model read first, before it knows whether it needs to.
  (let [{:keys [id path]} (jobs/start! "jt-j" {:command "echo nobody-read-this; sleep 30"})]
    (Thread/sleep 1000)
    (jobs/stop! "jt-j" id)
    (is (some #{"nobody-read-this"} (record path)))
    (is (= "[stopped]" (last (record path))))))

(deftest a-job-that-ended-gives-its-exit-code-and-is-forgotten
  (let [{:keys [id path]} (jobs/start! "jt-k" {:command "exit 3"})]
    (record-until path #(re-find #"\[exit" %) 10000)
    (is (= "[exit 3]" (last (record path))))
    (let [answer (jobs/stop! "jt-k" id)]
      (testing "a job that died on its own is not reported as stopped"
        (is (false? (:stopped? answer))))
      (testing "and is still forgotten, because remembering it forever is a leak"
        (let [e (try (jobs/stop! "jt-k" id) nil (catch Exception e e))]
          (is (= :unknown-job (:reason (ex-data e)))))))))

(deftest the-exit-line-arrives-after-the-output-that-went-with-it
  ;; The invariant, stated once: the record that says `[exit N]` also carries the
  ;; last line the command wrote. A `[exit N]` written the moment the process is
  ;; gone would leave the record looking complete while the tail was still queued.
  (let [{:keys [path]} (jobs/start! "jt-l" {:command "echo last-words; exit 0"})]
    (let [answer (record-until path #(re-find #"\[exit" %) 10000)]
      (is (= ["last-words" "[exit 0]"] (str/split-lines answer))))))

(deftest a-command-that-let-go-of-its-stdout-gets-no-exit-line
  ;; `exec 1>&-` closes the command's stdout while it lives on, so the stream ends
  ;; and the record is complete -- but the command is NOT gone, and an exit line
  ;; here (or a `[exit ?]`) would be inventing one. The ABSENCE of that line is the
  ;; answer: this record's command is still running.
  (let [{:keys [id path]} (jobs/start! "jt-m" {:command "echo before-letting-go; exec 1>&-; sleep 30"})]
    (record-until path #(re-find #"before-letting-go" %) 10000)
    (Thread/sleep 2500)
    (is (= ["before-letting-go"] (record path)))
    (testing "and stopping it is what adds the only other line there is"
      (jobs/stop! "jt-m" id)
      (is (str/includes? (last (record path)) "[stopped]")))))

(deftest a-job-outlives-the-call-that-started-it
  ;; 决策 8 的另一面：**run 结束不杀它**。没有什么东西会在一轮 run 结束时收作业——
  ;; 这由「没有任何 run 结束的收尾路径」保证，而这里断言的是它最容易被弄坏的那一半：
  ;; 作业活得比发起它的那次调用久，也活得比本会话后来发起的调用久。
  (let [dir (support/temp-dir "jobs-outlive")
        pid-file (io/file dir "child.pid")
        {:keys [path]} (jobs/start! "jt-n" {:command (str "sleep 30 & echo $! > "
                                                          (shell/quote-arg (.getAbsolutePath pid-file))
                                                          "; wait")})
        pid (do (Thread/sleep 1000)
                (Long/parseLong (str/trim (slurp pid-file :encoding "UTF-8"))))]
    (try
      (is (support/alive? pid))
      ;; ...the session does other work (another job, read, stopped) and comes back:
      (let [other (:id (jobs/start! "jt-n" {:command "echo other; exit 0"}))]
        (jobs/stop! "jt-n" other))
      (is (support/alive? pid) "the first job is untouched by everything after it")
      (is (= [] (record path)) "and if it has said nothing yet, its record is empty")
      (finally
        (jobs/shutdown!)
        (cleanup-dir! dir)))))

(deftest a-command-that-never-starts-leaves-no-record
  ;; A file left behind by a command that never ran is a path a later reader would
  ;; find and believe. And the session must be able to tell that nothing happened.
  (let [records (io/file (home/root) "jobs")
        before (set (file-seq records))]
    (is (thrown? Exception (jobs/start! "jt-p" {:command "echo hi"
                                                :dir "no-such-directory-for-a-job"})))
    (is (= before (set (file-seq records))) "no file was left for a command that never ran")
    (testing "and the session's table is empty"
      (let [e (try (jobs/stop! "jt-p" "j1") nil (catch Exception e e))]
        (is (= :unknown-job (:reason (ex-data e))))
        (is (str/includes? (ex-message e) "This session has no background jobs"))))))

(deftest what-a-command-sends-away-is-read-off-the-command
  ;; TABLE-DRIVEN, because the judgement is a list of shapes rather than an
  ;; algorithm, and each row is one shape somebody will eventually get wrong.
  ;;
  ;; IT IS BEST EFFORT, AND THAT IS WHY IT NEVER REFUSES ANYTHING: the answer is
  ;; only ever a note. A MISSED redirection (a variable, `${TMP}/x`, a nested shell)
  ;; costs a note that was not printed; a FOUND one costs nothing at all.
  ;;
  ;; QUOTES MAKE A `>` A WORD, not an operator: `echo "a > b"` sends nothing away.
  ;; That is also the line this judgement draws around a nested shell -- the `>` inside
  ;; `sh -c 'x > /tmp/x'` is quoted text to this function, and a nested command is one
  ;; of the shapes it is allowed to miss.
  (doseq [[command target] [["x > /tmp/x"                    "/tmp/x"]
                            ["echo hi >> /tmp/x"            "/tmp/x"]
                            ["echo hi 2> /tmp/x"            "/tmp/x"]
                            ["echo hi &> /tmp/x"            "/tmp/x"]
                            ["echo hi > /dev/null"          "/dev/null"]
                            ["echo hi 2>&1"                 nil]
                            ["echo hi 2>&1 > /tmp/x"        "/tmp/x"]
                            ["cat f | tee /tmp/x"           nil]
                            ["echo \"a > b\""                nil]
                            ["echo x \\> /tmp/y"             nil]
                            ["echo hi"                      nil]]]
    (is (= target (jobs/output-redirect command)) (str "command: " command))))
