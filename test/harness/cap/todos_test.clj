(ns harness.cap.todos-test
  "`todo_write`: a session's task list, and the two claims worth pinning about it.

  IT IS IN THE STORE, which no in-process assertion can prove on its own -- a list
  kept in a map would pass every test in this file except the one that opens the
  database from ANOTHER PROCESS. So that case exists: one JVM writes, a second one
  reads, and the answer travels through a file.

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
  string (deps.edn's standing byte/encoding rule)."
  [^String form ^java.io.File out ^java.io.File user-home]
  (let [pb (doto (ProcessBuilder. ^java.util.List
                                  (vec ["clojure"
                                        (str "-J-Duser.home=" (.getAbsolutePath user-home))
                                        "-M" "-e" form]))
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
  (let [dir   (io/file (System/getProperty "java.io.tmpdir")
                       (str "harness-todos-" (System/nanoTime)))
        uhome (io/file (System/getProperty "java.io.tmpdir")
                       (str "harness-todos-home-" (System/nanoTime)))
        out   (io/file dir "answer.txt")]
    (.mkdirs dir)
    (.mkdirs uhome)
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
    (is (str/includes? content "do the thing"))
    (is (= ["do the thing"] (mapv :content (todos/items-for tid))))))

(deftest a-turn-that-was-never-registered-is-not-treated-as-two-calls
  ;; A direct `run!`, a replayed approval: one call at a time, which is the case the
  ;; rule is about.
  (tools/forget-turn!)
  (let [{:keys [error]} (call "todo_write" {:todos [{:content "alone" :status "pending"}]})]
    (is (false? error))))

;; ------------------------------------------------------------- what it answers

(deftest the-answer-says-what-is-done-and-what-is-next
  (let [answer (todos/render (todos/write! tid [{:content "read the code" :status "completed"}
                                               {:content "write the test" :status "in_progress"}
                                               {:content "run it" :status "pending"}]))]
    (is (str/includes? answer "3 items"))
    (is (str/includes? answer "1 in progress"))
    (is (str/includes? answer "1 completed"))
    (testing "and each line carries its state and its place in the list"
      (is (str/includes? answer "[x] 1. read the code"))
      (is (str/includes? answer "[~] 2. write the test"))
      (is (str/includes? answer "[ ] 3. run it")))))
