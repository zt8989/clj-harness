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
            [harness.infra.log :as log]
            [harness.test-support :as support]))

;; Every job this namespace starts is stopped on the way out, whatever happened in
;; the test: a suite that leaves a `sleep 30` behind is the leak this feature exists
;; to make impossible. The RECORDS do NOT go with it: they are what outlives the process,
;; so a case that wants a home to itself says so itself (a `binding` of
;; `home/*root-override*`, the way `a-record-still-being-written-is-not-a-candidate`
;; does) rather than counting on the teardown to have swept up.
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

;; --------------------------------------------------------- telling the model
;;
;; The three verbs above are all things the MODEL does, and nobody-waiting is the whole
;; reason a job exists -- so there is a fourth channel: `take-notices!` hands back the
;; jobs that finished whose ending the model has not been given yet. It is the jobs half
;; of a session's pre-LLM step, and what these cases own is the arithmetic of "once":
;; who has been told, and who has not.

(deftest a-job-that-is-over-says-so-once
  (let [t "jt-tell"
        {:keys [id path]} (jobs/start! t {:command "echo said-this; exit 3"})]
    (record-until path #(re-find #"\[exit" %) 10000)
    (let [notices (jobs/take-notices! t)]
      (testing "one job, one notice: two facts, the command, and the line that reads it"
        (is (= 1 (count notices)))
        (is (= "user" (:role (first notices))) "a message like any other, like a skill body")
        (is (= (str "<job-ended id=\"" id "\">[exit 3]</job-ended>\n"
                    "<command>echo said-this; exit 3</command>\n"
                    "Read what it said with job_output {\"job\": \"" id "\"}.")
               (:content (first notices)))
            "which job, what it ran, how it went -- no tail, no record path, and where to read")
        (is (not (str/includes? (:content (first notices)) path))
            "the path is not in the notice: the `job` answer carried it, and the model has that")
        (is (= 3 (count (str/split-lines (:content (first notices)))))
            "three lines and no tail: the tag, the command, the read sentence"))
      (testing "and it is not said twice"
        (is (= [] (jobs/take-notices! t)))))))

(deftest a-job-that-is-still-running-has-nothing-to-say
  (let [t "jt-quiet"
        {:keys [path]} (jobs/start! t {:command "echo out; sleep 30"})]
    (record-until path #(re-find #"out" %) 10000)
    (is (= [] (jobs/take-notices! t)) "it has not finished, so there is no ending to hand over")))

(deftest asking-a-job-yourself-counts-as-being-told
  ;; A model that waited for it, or read it, has the ending in hand -- an ending it has
  ;; already read is not news, and telling it again would teach it to ignore notices.
  (let [t "jt-asked"
        {:keys [id]} (jobs/start! t {:command "echo hi; exit 0"})]
    (is (= "[exit 0]" (:status (jobs/output t id {:wait true :timeout 20000})))
        "the wait handed the ending over")
    (is (= [] (jobs/take-notices! t))))
  (testing "and a plain read of an ended job counts too"
    (let [t "jt-read-it"
          {:keys [id path]} (jobs/start! t {:command "exit 5"})]
      (record-until path #(re-find #"\[exit" %) 10000)
      (is (= "[exit 5]" (:status (jobs/output t id {}))))
      (is (= [] (jobs/take-notices! t))))))

(deftest stopping-a-job-counts-as-being-told
  (let [t "jt-stopped"
        {:keys [id]} (jobs/start! t {:command "sleep 30"})]
    (is (true? (:stopped? (jobs/stop! t id))))
    (is (= [] (jobs/take-notices! t)) "its answer WAS the ending")))

(deftest a-notice-is-the-same-size-whatever-the-record-is
  ;; A NOTICE IS A FACT, NOT AN ANSWER. It used to carry the end of the record (up to a
  ;; budget, with the truncation sentence when it did not fit); that made a reminder the
  ;; size of an answer, and a command that printed five thousand lines is announced in
  ;; exactly the same few bytes as one that printed nothing.
  (let [t "jt-size"
        ;; THE NEEDLE IS A WORD THE COMMAND PRINTS AND THE COMMAND DOES NOT CONTAIN. A
        ;; notice quotes the command now (that is how it says WHICH job), so a needle
        ;; written into the command as text -- `echo notice-proof-marker` -- is in the
        ;; notice by design and proves nothing; this one is assembled by the shell, so
        ;; only the RECORD holds it.
        big  (jobs/start! t {:command "seq 1 5000; echo notice-proof-$((3*5+2)); exit 0"})
        none (jobs/start! t {:command "exit 0"})]
    (record-until (:path big) #(re-find #"\[exit" %) 20000)
    (record-until (:path none) #(re-find #"\[exit" %) 10000)
    (let [notices (jobs/take-notices! t)
          bytes   (fn [m] (alength (.getBytes ^String (:content m) "UTF-8")))]
      (is (= 2 (count notices)) "two jobs, two notices")
      (doseq [n notices]
        (is (str/includes? (:content n) "[exit 0]"))
        (is (not (str/includes? (:content n) "notice-proof-17"))
            "nothing of what the command said -- the record is one call away")
        (is (< (bytes n) 400) (str "a notice is a line, not a report: " (bytes n) " bytes")))
      (is (< (- (bytes (first notices)) (bytes (second notices))) 200)
          "and the two are the same size, because what differs is not in them"))))

(deftest the-pre-llm-half-leaves-a-history-alone-when-there-is-nothing-to-say
  (let [history [{:role "user" :content "go"}]]
    (is (= history (jobs/before-llm history "jt-nobody")))
    (is (= "go" (:content (first (jobs/before-llm history "jt-nobody"))))
        "byte for byte the history it was handed, not a re-built one")))

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

(deftest the-process-going-away-takes-its-jobs-and-not-its-records
  ;; `shutdown!` is what the exit hook runs, and it is asserted by CALLING it: a
  ;; forked JVM against this repo's config home hangs (harness.cap.mcp-test records
  ;; that), so the reap is measured here and the hook's installation by the count
  ;; below.
  (let [dir (support/temp-dir "jobs-shutdown")
        pid-file (io/file dir "child.pid")
        {:keys [id path]} (jobs/start! "jt-h" {:command (support/child-command pid-file)})
        pid (support/child-pid pid-file 10000)]
    (is (some? pid) "the job's own child booted and named itself in the pid file")
    (is (support/alive? pid) "the job really is running")
    (let [said (slurp path :encoding "UTF-8")]
      (jobs/shutdown!)
      (testing "the command and the child it started are both gone"
        (is (support/gone-within? pid 5000)))
      (testing "and the record stays -- the job is the process's, the file is not"
        (is (.exists (io/file path)))
        (is (str/starts-with? (slurp path :encoding "UTF-8") said)
            "and what it had already said is still there, word for word"))
      (testing "and no session can ask about it any more"
        (let [e (try (jobs/stop! "jt-h" id) nil (catch Exception e e))]
          (is (= :unknown-job (:reason (ex-data e))))
          (is (str/includes? (ex-message e) "RECORD")
              "the refusal says the file is still there, so a reader does not take 'unknown job' for 'gone'"))))
    (cleanup-dir! dir)))

(deftest the-exit-hook-is-installed-once-and-only-once
  (let [installs (atom 0)]
    (try
      (with-redefs [jobs/install-hook! (fn [_] (swap! installs inc))]
        (jobs/reset-exit-hook!)
        (dotimes [_ 3] (jobs/ensure-exit-hook!))
        (is (= 1 @installs) "three calls, one hook -- two would run the reap twice"))
      (finally (jobs/ensure-exit-hook!)))))

(deftest stopping-a-job-takes-the-whole-tree-and-leaves-it-answerable
  (let [dir (support/temp-dir "jobs-stop")
        pid-file (io/file dir "child.pid")
        {:keys [id path]} (jobs/start! "jt-i" {:command (support/child-command pid-file)})
        pid (support/child-pid pid-file 10000)]
    (is (some? pid) "the job's own child booted and named itself in the pid file")
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
    (testing "and the job is still there to be asked about"
      ;; THE ENTRY OUTLIVES THE JOB, and that is what makes asking twice an ANSWER
      ;; rather than a refusal: the id was handed out by this session, and 'it is
      ;; over, here is how' is the truth about it. Only an id this session never had
      ;; is refused.
      (let [again (jobs/stop! "jt-i" id)]
        (is (false? (:stopped? again)) "this call is not the one that stopped it")
        (is (= "[stopped]" (:ending again))))
      (let [e (try (jobs/stop! "jt-i" "j-never-handout") nil (catch Exception e e))]
        (is (= :unknown-job (:reason (ex-data e))))))
    (cleanup-dir! dir)))

(deftest a-stopped-job-keeps-what-it-had-said
  ;; Stopping and reading are not interchangeable, and the order a caller chose must
  ;; not change what it can know: a stop that took the output with it would make the
  ;; model read first, before it knows whether it needs to.
  (let [{:keys [id path]} (jobs/start! "jt-j" {:command "echo nobody-read-this; sleep 30"})]
    ;; WAIT FOR THE LINE, not for a fixed interval: the shell has to boot before any
    ;; of this command runs, and a stop that lands first leaves a record saying only
    ;; `[stopped]` -- a case about what a stopped job KEEPS, failing over how long a
    ;; process takes to start.
    (record-until path #(re-find #"nobody-read-this" %) 10000)
    (jobs/stop! "jt-j" id)
    (is (some #{"nobody-read-this"} (record path)))
    (is (= "[stopped]" (last (record path))))))

(deftest a-job-that-ended-gives-its-exit-code
  (let [{:keys [id path]} (jobs/start! "jt-k" {:command "exit 3"})]
    (record-until path #(re-find #"\[exit" %) 10000)
    (is (= "[exit 3]" (last (record path))))
    (let [answer (jobs/stop! "jt-k" id)]
      (testing "a job that died on its own is not reported as stopped"
        (is (false? (:stopped? answer))))
      (testing "and asking again answers the same ending rather than refusing"
        (let [again (jobs/stop! "jt-k" id)]
          (is (false? (:stopped? again)))
          (is (= "[exit 3]" (:ending again))))))))

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
        {:keys [path]} (jobs/start! "jt-n" {:command (support/child-command pid-file)})
        pid (support/child-pid pid-file 10000)]
    (try
      (is (some? pid) "the job's own child booted and named itself in the pid file")
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

;; ------------------------------------------------------- the answer's ceiling
;;
;; A command can say more than one answer may carry. The arithmetic below is a pure
;; function of a string and a number, so it is asserted where it lives -- and the tool
;; seam's cases are about the ANSWER (a tail, an omitted count, a path, and a command
;; too quiet to have any of that).

(deftest a-tail-is-cut-by-bytes-and-at-a-line
  (testing "what fits comes back whole, and says nothing was left out"
    (is (= {:text "one\ntwo\n" :omitted 0}
           (jobs/tail-within-budget "one\ntwo\n" 8))
        "exactly at the budget is still inside it"))
  (testing "over it, the TAIL is what is kept"
    (let [{:keys [text omitted]} (jobs/tail-within-budget "one\ntwo\nthree\nfour\n" 12)]
      (is (= "three\nfour\n" text) "the last lines, not the first")
      (is (= 8 omitted) "and the bytes left out are counted, not estimated")))
  (testing "a cut lands on a line, never in the middle of one"
    (let [{:keys [text]} (jobs/tail-within-budget "alpha\nbravo\ncharlie\n" 10)]
      (is (= "charlie\n" text) "`bravo`'s back half is not a line")))
  (testing "and one enormous line is still better than nothing"
    (let [{:keys [text]} (jobs/tail-within-budget (str "head\n" (apply str (repeat 100 "x"))) 20)]
      (is (= (apply str (repeat 20 "x")) text) "no newline to cut at: the bytes are the answer")))
  (testing "a multi-byte character is whole or absent, never half of one"
    ;; Every character here is three bytes: a cut that ignored that would hand back
    ;; bytes that decode to a replacement character, which reads as corruption.
    (let [{:keys [text]} (jobs/tail-within-budget "一\n二\n三\n四\n" 8)]
      (is (= "三\n四\n" text))
      (is (= 8 (alength (.getBytes text "UTF-8")))))))

(deftest a-truncation-line-names-what-is-missing-and-where-the-rest-is
  (is (= "[truncated: omitted 12 bytes of stdout; the whole output is /tmp/x.log]"
         (jobs/truncation-line 12 "/tmp/x.log" "stdout")))
  (is (= "[truncated: omitted 12 bytes; the whole output is /tmp/x.log]"
         (jobs/truncation-line 12 "/tmp/x.log")))
  (testing "and when the record could not be written it says that instead of pointing"
    (let [line (jobs/truncation-line 12 nil)]
      (is (str/includes? line "could not be written"))
      (is (not (str/includes? line "nil"))))))

(deftest a-spilled-record-outlives-the-process-that-wrote-it
  (let [dir (support/temp-dir "jobs-spill")]
    (try
      (let [path (jobs/spill! "jt-spill" "the whole of what it said\n[exit 0]\n")]
        (is (str/starts-with? path (home/root)) "in the configuration home, where the fence is free")
        (is (= "the whole of what it said\n[exit 0]\n" (slurp path :encoding "UTF-8")))
        (testing "and it is not addressable as a job: nothing can be stopped or waited for"
          (let [e (try (jobs/stop! "jt-spill" "c1") nil (catch Exception e e))]
            (is (= :unknown-job (:reason (ex-data e))))))
        (jobs/shutdown!)
        (testing "and the process going away leaves it exactly as it was"
          (is (.exists (io/file path)))
          (is (= "the whole of what it said\n[exit 0]\n" (slurp path :encoding "UTF-8")))))
      (finally (cleanup-dir! dir)))))

;; ------------------------------------------------------------- the file that stays
;;
;; A RECORD IS THE ONE THING HERE THAT OUTLIVES ITS PROCESS, which makes two questions
;; worth asking that a shorter-lived file would not raise: what a process's file is
;; CALLED (so that the next run cannot write over it), and what a home is allowed to
;; cost (so that 'kept' does not become 'unbounded').

(deftest a-records-name-says-which-run-wrote-it
  ;; The collision this guards against is silent: the ids restart at `j1` in every
  ;; process (they are per session and in memory) and the file is opened TRUNCATING, so
  ;; without the tag the next run of this session would write its `j1` over this one's.
  (let [one (binding [jobs/*tag-override* "run-one"] (#'jobs/record-path "jt-run" "j1"))
        two (binding [jobs/*tag-override* "run-two"] (#'jobs/record-path "jt-run" "j1"))]
    (is (str/starts-with? one (home/root)) "still a path in the configuration home")
    (is (not= one two) "the same session and the same id, from another run, is another file")
    (is (str/ends-with? one "j1-run-one.log"))))

(deftest a-process-has-one-stamp-for-as-long-as-it-lives
  (let [tag (#'jobs/process-tag)]
    (is (= tag (#'jobs/process-tag))
        "asked twice, one answer -- a path an answer quoted must keep pointing at the same file")
    (is (re-matches #"\d{8}T\d{9}-\d+" tag)
        "this process's start, and the pid that keeps two of them apart")))

(deftest the-record-tree-is-capped-by-bytes-and-the-oldest-goes-first
  (let [dir (support/temp-dir "jobs-prune")]
    ;; A HOME OF THIS TEST'S OWN: the budget is about the whole tree, so the arithmetic
    ;; here has to be the only arithmetic in it.
    (binding [home/*root-override* dir]
      (let [tree (io/file (home/root) "jobs" "jt-prune")]
        (try
          (io/make-parents (io/file tree "old.log"))
          (doseq [[name age] [["old.log" 1000] ["middle.log" 2000] ["new.log" 3000]]]
            (let [f (io/file tree name)]
              (spit f (apply str (repeat 1000 "x")))
              (.setLastModified f (long age))))
          (with-redefs [jobs/record-tree-budget-bytes 2500]
            (let [gone (map #(.getName (io/file %)) (jobs/prune-records!))]
              (is (= ["old.log"] gone)
                  "one file is enough to get back under the budget, and it is the oldest")
              (is (.exists (io/file tree "middle.log")))
              (is (.exists (io/file tree "new.log")) "the newest is the last thing to go")))
          (testing "and under the budget nothing is deleted at all"
            (let [f (io/file tree "fresh.log")]
              (spit f "kept")
              (is (= [] (jobs/prune-records!)))))
          (finally (cleanup-dir! dir)))))))

(deftest a-record-still-being-written-is-not-a-candidate
  (let [dir (support/temp-dir "jobs-held")]
    ;; A HOME OF THIS TEST'S OWN: the sweep is over the whole tree by design, and what it
    ;; does to the rest of the tree is other cases' business.
    (binding [home/*root-override* dir]
      (let [{:keys [id path]} (jobs/start! "jt-held" {:command "printf 'not yet\n'; sleep 30"})
            f (io/file path)]
        (is (support/holds-within? #(str/includes? (slurp path :encoding "UTF-8") "not yet") 10000))
        (.setLastModified f 1000)
        (testing "however old its mtime says it is"
          (with-redefs [jobs/record-tree-budget-bytes 0]
            (is (= [] (jobs/prune-records!)) "nothing else is there to delete, and this one is held")
            (is (.exists f))
            (is (str/includes? (slurp path :encoding "UTF-8") "not yet"))))
        (jobs/stop! "jt-held" id)))))

(deftest a-sweep-that-cannot-delete-says-so-and-carries-on
  ;; THE FAILURE BRANCH IS THE HALF THAT NEEDS A TEST. A delete that WORKED is
  ;; visible in the answer -- the paths come back -- so that half is already held by
  ;; the cases above. A delete that did NOT work is visible NOWHERE unless the line
  ;; gets written: the sweep is best effort on purpose (no exception is coming), and
  ;; it leaves the tree over budget, which looks exactly like a tree that was small
  ;; enough all along. `delete-record!` makes the same argument about one file.
  ;;
  ;; THE CANDIDATE THAT WILL NOT GO IS A PATH THAT HAS VANISHED between the listing
  ;; and the delete. `File.delete` answers false for a file that is not there on
  ;; every platform, which is what makes this a test rather than a bet on one
  ;; filesystem's permissions or on a Windows handle being held open. The real file
  ;; listed behind it is the other half of the claim: the sweep must go ON, not stop
  ;; at the first thing that would not delete.
  (let [dir (support/temp-dir "jobs-stuck")]
    (binding [home/*root-override* dir]
      (try
        (let [tree   (io/file (home/root) "jobs" "jt-stuck")
              stuck  (io/file tree "vanished.log")
              real   (io/file tree "real.log")
              warned (atom [])]
          (io/make-parents real)
          (spit real (apply str (repeat 900 "x")))
          (with-redefs [jobs/record-files            (fn [] [stuck real])
                        jobs/record-tree-budget-bytes 0
                        log/warn!                     (fn [kind ctx] (swap! warned conj [kind ctx]))]
            (let [deleted (jobs/prune-records!)]
              (testing "the one that would not go is named, path and all"
                (is (= [[:jobs/record-not-pruned {:path (str stuck)}]] @warned)))
              (testing "and the sweep carried on instead of stopping there"
                (is (= [(str real)] deleted) "the deletable one still went")
                (is (not (.exists real))))
              (testing "and nothing was raised -- this runs on the way into a command"
                (is (vector? deleted) "the answer is still the paths that went")))))
          (finally (cleanup-dir! dir))))))

;; --------------------------------------------------------------- reading a job
;;
;; `output` answers two questions at once -- what it said, and whether it is over --
;; and it answers both off the record: the lines are the file, the status is the
;; file's last line. `wait` is the one thing a file cannot answer for a synchronous
;; caller, so it blocks on the job's own `:ended` promise.

(deftest a-reading-of-a-job-is-its-status-and-what-it-said
  (let [t "jt-read"
        {:keys [id]} (jobs/start! t {:command "echo one; echo two; sleep 30"})]
    (is (support/holds-within? #(= 2 (:total (jobs/output t id {}))) 10000)
        "the lines arrive as the command prints them")
    (let [{:keys [status lines from to total]} (jobs/output t id {})]
      (testing "a job that is still going says so, and shows what it has said"
        (is (= "[running]" status))
        (is (= ["one" "two"] lines))
        (is (= 1 from))
        (is (= 2 to))
        (is (= 2 total))))
    (testing "a job that has said nothing yet is running and empty, not an error"
      (let [{:keys [id]} (jobs/start! t {:command "sleep 30"})]
        (is (= {:status "[running]" :lines [] :from 1 :to 0 :total 0}
               (jobs/output t id {})))))
    (testing "and an id this session never had is refused by name"
      (let [e (try (jobs/output t "j-not-mine" {}) nil (catch Exception e e))]
        (is (= :unknown-job (:reason (ex-data e))))))))

(deftest a-line-that-looks-like-an-ending-is-not-one-while-the-job-runs
  ;; THE RECORD'S LAST LINE IS READ BACK, BUT IT IS NOT THE ONLY FACT: the claim on
  ;; the Writer is what makes the record closed, and a command is free to print
  ;; something that looks exactly like an ending line. Reading only the text would
  ;; report a running job as finished -- the same mistake in the opposite direction
  ;; from the `[exit N]` that arrives before the tail it belongs to.
  (let [t "jt-lookalike"
        {:keys [id]} (jobs/start! t {:command "echo '[exit 0]'; sleep 30"})]
    (is (support/holds-within? #(= 1 (:total (jobs/output t id {}))) 10000))
    (let [{:keys [status lines]} (jobs/output t id {})]
      (is (= "[running]" status) "the Writer is still open, so no ending has been written")
      (is (= ["[exit 0]"] lines) "while the line the command printed is shown as its output"))))

(deftest a-reading-can-wait-for-the-job-to-be-over
  (let [t "jt-wait"
        {:keys [id]} (jobs/start! t {:command "echo done; sleep 1; echo later"})]
    (let [started (System/currentTimeMillis)
          {:keys [status lines]} (jobs/output t id {:wait true :timeout 20000})
          elapsed (- (System/currentTimeMillis) started)]
      (testing "the answer is the ENDING, which is what waiting was for"
        (is (= "[exit 0]" status))
        (is (= ["done" "later"] lines)))
      (testing "and it really waited for it rather than guessing"
        (is (>= elapsed 900) (str "elapsed " elapsed "ms"))))
    (testing "and it waits for the ENDING, not for the stream to end"
      ;; `exec 1>&-` closes stdout and goes on living: its stream ends, its RECORD
      ;; does not. A `wait` that watched the stream would come back at once and be
      ;; wrong about a command that is still running.
      (let [{:keys [id]} (jobs/start! t {:command "exec 1>&-; echo hidden; sleep 30"})]
        (Thread/sleep 500)
        (let [started (System/currentTimeMillis)
              {:keys [status]} (jobs/output t id {:wait true :timeout 400})
              elapsed (- (System/currentTimeMillis) started)]
          (is (= "[running]" status) "no ending line means no end to report")
          (is (>= elapsed 300) (str "so the wait ran out rather than returning early: "
                                    elapsed "ms")))))
    (testing "while a wait that runs out answers the state of things, not an error"
      (let [{:keys [id]} (jobs/start! t {:command "sleep 30"})
            started (System/currentTimeMillis)
            {:keys [status]} (jobs/output t id {:wait true :timeout 300})
            elapsed (- (System/currentTimeMillis) started)]
        (is (= "[running]" status))
        (is (< elapsed 10000) (str "it came back at the timeout, not at the command's end: "
                                  elapsed "ms"))))))

(deftest a-wait-with-no-timeout-uses-the-default-instead-of-throwing
  ;; THE ONE CALL SHAPE NO OTHER TEST MAKES: `wait: true` with NO `timeout`. It used to
  ;; hand the DEFAULT to `long` UNCALLED -- `(long (or timeout
  ;; job-output-default-timeout-ms))` -- so this shape threw ClassCastException instead
  ;; of waiting, and every other test passes an explicit `:timeout` and so never saw it.
  ;; The job here ends on its own, so the default is never reached: what is pinned is
  ;; that the default is a NUMBER the wait can use. See
  ;; .scratch/llm-prefix-cache/issues/01-the-identity-hash-in-the-tool-table.md.
  (let [t "jt-wait-default"
        {:keys [id]} (jobs/start! t {:command "echo done"})
        started (System/currentTimeMillis)
        {:keys [status lines]} (jobs/output t id {:wait true})
        elapsed (- (System/currentTimeMillis) started)]
    (is (= "[exit 0]" status))
    (is (= ["done"] lines))
    (testing "and it came back at the job's ending, not at the default running out"
      (is (< elapsed (quot (long jobs/job-output-default-timeout-ms) 2))
          (str "elapsed " elapsed "ms, default " jobs/job-output-default-timeout-ms "ms")))))

(deftest a-reading-walks-a-record-with-offset-and-limit
  ;; The window is the TAIL by default -- what a job has just said -- and `offset`
  ;; asks for a stretch that begins somewhere, the way `read` does. The numbers in the
  ;; answer are the record's own line numbers, so they can be checked with `grep -n`.
  (let [t "jt-window"
        {:keys [id]} (jobs/start! t {:command "i=1; while [ $i -le 200 ]; do echo line-$i; i=$((i+1)); done; sleep 30"})]
    (is (support/holds-within? #(= 200 (:total (jobs/output t id {}))) 20000))
    (let [{:keys [lines from to total]} (jobs/output t id {})]
      (testing "the default window is the end of it, and a short record fits whole"
        (is (= 200 total))
        (is (= "line-200" (last lines)))
        (is (= 1 from) "200 short lines are inside the budget, so nothing is left out")
        (is (= total to))))
    (let [{:keys [lines from to]} (jobs/output t id {:offset 5 :limit 2})]
      (testing "and an explicit `offset` reads forward from a line the reader names"
        (is (= ["line-5" "line-6"] lines))
        (is (= 5 from))
        (is (= 6 to))))
    (testing "an offset past the end is an empty window, not an error"
      (let [{:keys [lines from to total]} (jobs/output t id {:offset 5000})]
        (is (= [] lines))
        (is (= 200 total) "the record still says how much it has")
        (is (= 201 from) "and the answer names the line after the last one")
        (is (< to from) "an empty window, said with numbers rather than with an error")))))

(deftest a-window-that-does-not-fit-stops-at-a-line-and-says-so
  (let [t "jt-big"
        {:keys [id]} (jobs/start! t {:command "seq 1 5000; sleep 30"})]
    (is (support/holds-within? #(= 5000 (:total (jobs/output t id {}))) 20000))
    (let [{:keys [lines from to total]} (jobs/output t id {})]
      (is (= 5000 total) "the record has every line of it")
      (is (= "5000" (last lines)))
      (is (> from 1))
      (is (= total to) "the window runs to the last line the command wrote")
      (is (< (alength (.getBytes (str/join "\n" lines) "UTF-8"))
             (* 2 jobs/answer-budget-bytes))
          "and it is inside the budget"))))

