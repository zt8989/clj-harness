(ns harness.cap.todos-test
  "`todo_write` / `todo_read`: a session's task list, and the claims worth pinning
  about it.

  IT IS IN THE STORE, which no in-process assertion can prove on its own -- a list
  kept in a map would pass every test in this file except the one that opens the
  database from ANOTHER PROCESS. So that case exists: one JVM writes, a second one
  reads, and the answer travels through a file.

  IT IS THE SAME LIST EITHER WAY: `todo_write` is answered with how many items were
  stored, `todo_read` with the items themselves -- one row each, in the order they
  were written -- and a list that was cleared answers exactly what a list that was
  never written answers.

  IT IS REPLACED RATHER THAN APPENDED, which is what makes it state (harness.infra.db
  keeps what can be rewritten) and what makes a message with two writes in it
  meaningless. The refusals below are the ones a model actually triggers: a bare
  string instead of an array, an item that is just its text, a status it invented."
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [harness.test-support :as support]
            [harness.infra.db :as db]
            [harness.infra.home :as home]
            [harness.cap.todos :as todos]
            [harness.kernel.tools :as tools]))

(def ^:private tid "todos-test")

(defn- wipe! []
  (when (.exists (home/db-file))
    (db/with-transaction
      (fn [c] (db/execute! c "DELETE FROM todos")))))

(use-fixtures :once support/with-builtins)

(use-fixtures :each
  (fn [f]
    (wipe!)
    (tools/forget-turn!)
    (f)
    (wipe!)
    (tools/forget-turn!)))

(defn- call
  ([name args] (call tid name args))
  ([thread-id name args]
   (tools/run! {:id "c" :type "function"
                :function {:name name :arguments (json/write-str args)}} thread-id)))

(defn- refused
  "The message `write!` refused ITEMS with -- nil when it did not refuse."
  [items]
  (try (todos/write! tid items) nil (catch Exception e (ex-message e))))

;; -------------------------------------------------------------- it is a row

(deftest a-written-list-is-a-row-in-the-store
  (let [items [{:content "read the code" :status "pending"}
               {:content "write the test" :status "in_progress"}]]
    (todos/write! tid items)
    (testing "read back through the store itself, not through the reader"
      (let [rows (db/select "SELECT thread_id, items FROM todos")]
        (is (= 1 (count rows)))
        (is (= tid (:thread-id (first rows))))
        (is (= items (json/read-str (:items (first rows)) :key-fn keyword)))))
    (testing "and the reader answers the same list"
      (is (= items (todos/items-for tid))))))

(deftest a-session-that-never-wrote-one-has-no-list
  (is (= [] (todos/items-for "never-wrote-anything")))
  (is (= [] (todos/items-for nil)) "and a thread with no id is not an error to read"))

(deftest writing-again-replaces-the-list-instead-of-adding-to-it
  (todos/write! tid [{:content "one" :status "pending"} {:content "two" :status "pending"}])
  (todos/write! tid [{:content "two" :status "completed"}])
  (is (= [{:content "two" :status "completed"}] (todos/items-for tid))
      "the first list is gone, not merged")
  (is (= 1 (count (db/select "SELECT thread_id FROM todos")))
      "and one session has one row, however many times it writes"))

(deftest an-empty-list-clears-it
  (todos/write! tid [{:content "one" :status "pending"}])
  (let [answer (todos/render (todos/write! tid []))]
    (is (= [] (todos/items-for tid)))
    (is (str/includes? answer "empty"))))

(deftest two-sessions-have-two-lists
  (todos/write! "session-a" [{:content "a's work" :status "pending"}])
  (todos/write! "session-b" [{:content "b's work" :status "completed"}])
  (is (= ["a's work"] (mapv :content (todos/items-for "session-a"))))
  (is (= ["b's work"] (mapv :content (todos/items-for "session-b")))))

;; ------------------------------------------------------ it outlives the process

(defn- in-a-fresh-jvm
  "Run FORM in a NEW JVM whose config root is THIS run's and whose OS home is
  USER-HOME, and answer {:exit :out}.

  A second process is the only way to prove the list is in the DATABASE: everything
  else in this file would pass for a list kept in a map.

  THE OS HOME TRAVELS TOO, and it is a temp directory the test makes: a child JVM
  inherits nothing of this process's `*user-home-override*`, so a child left to the
  JVM's own `user.home` would read the DEVELOPER'S ~/AGENTS.md and ~/.agents/skills.
  That is the rule for any test that forks a JVM (AGENTS.md), and it is the same pair
  the fixture pins in-process -- here the root must be this run's, because the point
  is that the child reads what this process wrote.

  The child's answer comes back through a FILE named by an environment variable,
  never through stdout: this machine's JDK prints four 'restricted method' WARNING
  lines to stdout when sqlite-jdbc loads its native library, so a child's stdout
  carries noise that has nothing to do with the form. And a path crossing a process
  boundary belongs in an environment variable rather than in an interpolated argv
  string (deps.edn's standing byte/encoding rule).

  NOR DOES THE FORM ITSELF TRAVEL IN ARGV, and this one is a Windows fact rather
  than a style: the JVM builds the child's command line out of the argv array, and an
  embedded double quote does not survive that trip -- `-e (System/getenv \"X\")`
  arrives as `-e (System/getenv X)` and the child dies with 'Unable to resolve symbol:
  X' (measured on this machine). Every form here quotes a string, so the form is
  written to a file and `clojure -M <file>` runs it; a script file has no such limit."
  [^String form ^java.io.File out ^java.io.File user-home]
  (let [script (io/file (.getParentFile out) (str (.getName out) ".form.clj"))
        _      (spit script form :encoding "UTF-8")
        pb (doto (ProcessBuilder. ^java.util.List
                                  (vec ["clojure"
                                        (str "-J-Duser.home=" (.getAbsolutePath user-home))
                                        "-M" (.getAbsolutePath script)]))
             (.directory (io/file (System/getProperty "user.dir")))
             (.redirectErrorStream true))]
    (.put (.environment pb) "CLJ_HARNESS_HOME" (home/root))
    (.put (.environment pb) "CLJ_HARNESS_TEST_OUT" (.getAbsolutePath out))
    (let [p (.start pb)
          out-text (slurp (.getInputStream p) :encoding "UTF-8")]
      (when-not (.waitFor p 120 java.util.concurrent.TimeUnit/SECONDS)
        (.destroyForcibly p)
        (throw (ex-info "a fresh JVM did not finish in 120s" {:form form})))
      {:exit (.exitValue p) :out out-text})))

(deftest the-list-outlives-the-process-that-wrote-it
  (let [dir   (support/temp-dir "todos")
        ;; `io/file` around the home because `in-a-fresh-jvm` spells it into the
        ;; child's argv and takes a File to do it (`temp-dir` answers a path string).
        uhome (io/file (support/temp-dir "todos-home"))
        out   (io/file dir "answer.txt")]
    (try
      (todos/write! tid [{:content "survives a restart" :status "in_progress"}])
      (let [r (in-a-fresh-jvm
               (str "(require 'harness.cap.todos)"
                    " (spit (System/getenv \"CLJ_HARNESS_TEST_OUT\")"
                    " (pr-str (harness.cap.todos/items-for \"todos-test\"))"
                    " :encoding \"UTF-8\")")
               out uhome)]
        (is (zero? (:exit r)) (str "the second JVM failed:\n" (:out r)))
        (is (= [{:content "survives a restart" :status "in_progress"}]
               (edn/read-string (slurp out :encoding "UTF-8")))
            "a process that never saw the write reads the list out of the store"))
      (finally
        (doseq [d [dir uhome]] (support/wipe-tree! d))))))

;; ------------------------------------------------------------- reading it back

(deftest a-written-list-reads-back-in-order-with-its-statuses
  ;; The order is the list's own, so it is compared as one: one row per item, in the
  ;; order it was written, then the total and the split in `render`'s vocabulary.
  (todos/write! tid [{:content "read the code" :status "pending"}
                     {:content "write the test" :status "in_progress"}
                     {:content "run it" :status "completed"}])
  (let [{:keys [content error]} (call "todo_read" {})]
    (is (false? error) content)
    (is (= ["- [ ] read the code"
            "- [~] write the test"
            "- [x] run it"
            "3 items (1 in progress, 1 completed)."]
           (str/split-lines content)))))

(deftest a-list-that-was-never-written-and-a-cleared-one-answer-the-same
  ;; 'No list' and 'an empty list' are two facts in the store and ONE answer to a
  ;; reader (`items-for` already made them one), so the read does not invent a second
  ;; sentence to tell them apart.
  (let [{never :content :keys [error]} (call "todo_read" {})]
    (is (false? error))
    (is (str/includes? never "empty") "the sentence says there is nothing planned")
    (todos/write! tid [{:content "one" :status "pending"}])
    (is (not= never (:content (call "todo_read" {})))
        "a list with something in it does not read as empty")
    (todos/write! tid [])
    (is (= never (:content (call "todo_read" {}))) "写空数组之后逐字同一句")
    (is (= (todos/render []) never)
        "and the receipt for a cleared list says the same sentence -- one vocabulary")))

(deftest a-read-changes-nothing-not-even-the-rows-timestamp
  (todos/write! tid [{:content "one" :status "pending"}
                     {:content "two" :status "in_progress"}])
  (let [stamp (fn [] (:updated-at (first (db/select (str "SELECT updated_at FROM todos"
                                                    " WHERE thread_id = ?")
                                              tid))))
        before (stamp)
        first-answer (:content (call "todo_read" {}))
        second-answer (:content (call "todo_read" {}))]
    (is (= first-answer second-answer) "连调两次答案逐字相同")
    (is (= before (stamp))
        "and the row's timestamp did not move: a read writes nothing, the row included")))

(deftest the-read-declares-no-parameters-and-no-read-only-claim
  (let [spec (first (filter #(= "todo_read" (get-in % [:function :name])) (tools/specs)))]
    (is (some? spec) "todo_read is in the table")
    (is (= {} (get-in spec [:function :parameters :properties]))
        "no parameters: the list belongs to the session")
    (is (= [] (get-in spec [:function :parameters :required])))
    (is (nil? (:read-only (get (tools/effective-tools tid) "todo_read")))
        "and no :read-only -- an exploring subagent's range must not move")))

(deftest a-read-with-no-session-in-scope-is-refused-by-name
  (testing "the reason is the family write! refuses with"
    (let [e (try (todos/read-back nil) nil (catch Exception e e))]
      (is (some? e))
      (is (= :no-session (:reason (ex-data e))))
      (is (str/includes? (ex-message e) "todo_read")
          "and it names the tool to call from a run")))
  (testing "同一个句子，只有工具名不同"
    (let [write-said (try (todos/write! nil []) nil (catch Exception e (ex-message e)))
          read-said  (try (todos/read-back nil) nil (catch Exception e (ex-message e)))]
      (is (string? write-said))
      (is (= write-said (str/replace read-said "todo_read" "todo_write")))))
  (testing "and through the seam it is a tool result, not a throw"
    (let [{:keys [content error]} (call nil "todo_read" {})]
      (is (true? error))
      (is (str/includes? content "session")))))

(deftest the-list-reads-back-in-another-process
  ;; Same shape as the case above, for the same reason -- the store is the only thing
  ;; that can answer this -- but through the READ's own answer: a second JVM renders
  ;; the list and writes THAT to the file, so the sentence a model would read is what
  ;; travels, not the values behind it.
  (let [dir   (support/temp-dir "todos-read")
        uhome (io/file (support/temp-dir "todos-read-home"))
        out   (io/file dir "answer.txt")]
    (try
      (todos/write! tid [{:content "read the code" :status "pending"}
                         {:content "write the test" :status "in_progress"}
                         {:content "run it" :status "completed"}])
      (let [r (in-a-fresh-jvm
               (str "(require 'harness.cap.todos)"
                    " (spit (System/getenv \"CLJ_HARNESS_TEST_OUT\")"
                    " (harness.cap.todos/read-back \"todos-test\")"
                    " :encoding \"UTF-8\")")
               out uhome)]
        (is (zero? (:exit r)) (str "the second JVM failed:\n" (:out r)))
        (is (= (todos/read-back tid) (slurp out :encoding "UTF-8"))
            "a process that never saw the write reads the same list back"))
      (finally
        (doseq [d [dir uhome]] (support/wipe-tree! d))))))

;; ------------------------------------------------------------- the refusals

(deftest every-refusal-names-what-to-send-instead
  (doseq [[label items fragment]
          [["a bare string instead of an array" "read the code" "array"]
           ["an item that is only its text" ["just the text"] "object"]
           ["an item with no content" [{:status "pending"}] "content"]
           ["an item with blank content" [{:content "   " :status "pending"}] "content"]
           ["an unknown status" [{:content "x" :status "doing"}] "pending"]
           ["two things in progress at once" [{:content "x" :status "in_progress"}
                                              {:content "y" :status "in_progress"}]
            "in_progress"]]]
    (let [message (refused items)]
      (is (string? message) label)
      (is (str/includes? message fragment) (str label " -> " message)))))

(deftest an-unknown-status-is-told-the-three-that-exist
  (let [message (refused [{:content "x" :status "doing"}])]
    (doseq [s todos/statuses]
      (is (str/includes? message s) (str s " must be named in the refusal")))))

(deftest a-list-longer-than-the-cap-is-refused-and-told-the-cap
  (let [items (mapv (fn [i] {:content (str "item " i) :status "pending"})
                    (range (inc todos/max-items)))
        message (refused items)]
    (is (string? message))
    (is (str/includes? message (str todos/max-items)))))

(deftest a-list-with-no-session-to-belong-to-is-refused
  ;; The row's key IS the session, so a list written without one would be readable
  ;; by nobody -- including its own writer on the next call.
  (let [message (try (todos/write! nil [{:content "x" :status "pending"}])
                     nil
                     (catch Exception e (ex-message e)))]
    (is (string? message))
    (is (str/includes? message "session")))
  (testing "and the same thing through the seam, with no thread in scope"
    (let [{:keys [content error]} (call nil "todo_write" {:todos [{:content "x" :status "pending"}]})]
      (is (true? error))
      (is (str/includes? content "session")))))

;; --------------------------------------------------------- one list per message

(defn- a-turn [n]
  (mapv (fn [i] {:id (str "call-" i)
                 :type "function"
                 :function {:name "todo_write" :arguments "{}"}})
        (range n)))

(deftest a-message-with-two-todo-writes-writes-neither
  ;; A list is replaced WHOLE, so two calls in one message have nothing to merge --
  ;; and a turn's calls run CONCURRENTLY, so 'the second one' is not a thing that
  ;; exists to refuse. The message is ambiguous, so neither is applied and the model
  ;; is told to send one.
  (tools/register-turn! tid (a-turn 2))
  (let [first-call  (call "todo_write" {:todos [{:content "from the first call" :status "pending"}]})
        second-call (call "todo_write" {:todos [{:content "from the second call" :status "pending"}]})]
    (is (true? (:error first-call)))
    (is (true? (:error second-call)))
    (is (str/includes? (:content first-call) "later message"))
    (is (= [] (todos/items-for tid)) "and nothing was written at all")))

(deftest one-todo-write-in-a-message-is-the-ordinary-case
  (tools/register-turn! tid (a-turn 1))
  (let [{:keys [content error]} (call "todo_write" {:todos [{:content "do the thing" :status "pending"}]})]
    (is (false? error) content)
    (is (str/includes? content "1 item stored") "the receipt")
    (is (not (str/includes? content "do the thing")) "with no echo of the item")
    (is (= ["do the thing"] (mapv :content (todos/items-for tid))))))

(deftest a-turn-that-was-never-registered-is-not-treated-as-two-calls
  ;; A direct `run!`, a replayed approval: one call at a time, which is the case the
  ;; rule is about.
  (tools/forget-turn!)
  (let [{:keys [error]} (call "todo_write" {:todos [{:content "alone" :status "pending"}]})]
    (is (false? error))))

;; ------------------------------------------------------------- what it answers

(deftest the-answer-is-a-receipt-not-the-list-again
  ;; The list was in the call that stored it, so the answer owes the FACT: how many
  ;; items there are, and how they stand. Repeating the items would pay twice for
  ;; tokens the model just sent -- and tell it nothing it did not already know.
  (let [items  [{:content "read the code" :status "completed"}
                {:content "write the test" :status "in_progress"}
                {:content "run it" :status "pending"}]
        answer (todos/render (todos/write! tid items))]
    (is (str/includes? answer "3 items"))
    (is (str/includes? answer "1 in progress"))
    (is (str/includes? answer "1 completed"))
    (testing "and none of the list came back"
      (doseq [item items]
        (is (not (str/includes? answer (:content item)))
            (str "the answer echoed " (pr-str (:content item)) ": " answer)))
      (is (not (str/includes? answer "[x]")) "no status markers either")
      (is (not (str/includes? answer "[~]")))
      (is (not (str/includes? answer "[ ]"))))
    (testing "while the list itself is stored exactly as it was sent"
      (is (= items (todos/items-for tid))))))
