(ns harness.infra.log-test
  "The backend's error log: tools.logging → SLF4J → Logback, with a console
  appender and a rolling file appender.

  Driven through REAL Logback and a REAL file, because the two things worth
  knowing here are properties of the library rather than of our wrappers: that a
  line lands in `<root>/logs/harness.infra.log`, and that the appender's policy really
  is the date-and-size one. A stub appender would prove neither."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [harness.infra.home :as home]
            [harness.edge.http :as http]
            [harness.infra.log :as log]
            [harness.infra.logging :as logging]
            [harness.cap.project :as project])
  (:import [ch.qos.logback.classic Logger LoggerContext]
           [ch.qos.logback.core.rolling RollingFileAppender SizeAndTimeBasedRollingPolicy]
           [java.io StringWriter]
           [org.slf4j LoggerFactory]))

(defn- fresh-home [name]
  (let [dir (io/file (System/getProperty "java.io.tmpdir")
                     (str "clj-harness-log-test-" name "-" (System/currentTimeMillis)))]
    (.mkdirs dir)
    (str dir)))

(defn- log-file [root]
  (io/file root "logs" "harness.infra.log"))

(defn- file-text [root]
  (let [f (log-file root)]
    (if (.exists f) (slurp f :encoding "UTF-8") "")))

(defmacro with-capture
  "Run BODY with logging pointed at a fresh root, bound to ROOT and CONSOLE.

  THE BINDING IS NOT DECORATION. `harness.infra.log/error!` calls
  `harness.infra.logging/ensure!`, which reconfigures whenever `harness.infra.home/root` has
  moved -- so a test that configured a root WITHOUT moving home/root would have
  its configuration replaced by the fixture's on the very next log call, and
  would then be asserting against an empty file while the lines went somewhere
  else. That is the mechanism doing its job; the binding is how a test stands
  where it claims to stand. (Found exactly that way.)"
  [name root console & body]
  `(let [root# (fresh-home ~name)
         out#  (StringWriter.)]
     (binding [home/*root-override* root#]
       (logging/configure! {:root root# :console out#})
       (let [~root root#
             ~console out#]
         ~@body))))

(defn- root-logger ^Logger []
  (.getLogger ^LoggerContext (LoggerFactory/getILoggerFactory) Logger/ROOT_LOGGER_NAME))

(deftest the-rotation-policy-is-by-date-and-by-size
  ;; The requirement, asserted directly. It is the reason Logback is a dependency
  ;; at all, and nothing else in the suite would notice if the policy were
  ;; swapped for a plain one.
  (with-capture "policy" root _
    (let [app (.getAppender (root-logger) "rolling")]
      (is (instance? RollingFileAppender app) "the file half is a rolling appender")
      (let [^RollingFileAppender app app
            policy (.getRollingPolicy app)]
        (is (instance? SizeAndTimeBasedRollingPolicy policy)
            "BY DATE **AND** BY SIZE -- one policy, both dimensions")
        (let [^SizeAndTimeBasedRollingPolicy p policy
              pattern (.getFileNamePattern p)]
          (is (str/includes? pattern "%d{yyyy-MM-dd}")
              "the date is in the rotated name, so one day is one file")
          (is (str/includes? pattern "%i")
              "and the index is too, so a day that outgrows the cap keeps rolling")
          (is (str/includes? pattern "harness."))
          (is (str/starts-with? pattern (.getAbsolutePath (io/file root "logs")))
              "rotated files land beside the active one, under this root")))
      (is (str/ends-with? (.getFile ^RollingFileAppender app)
                          (.getAbsolutePath (log-file root)))))))

(deftest an-error-reaches-the-file-and-the-console-as-the-same-sentence
  (with-capture "both" root out
    (try (throw (ex-info "no provider named :beta" {:provider :beta}))
         (catch Throwable t (log/error! :run-refused t {:thread-id "t-1"})))
    (testing "the file, under the root's own logs directory"
      (is (.exists (log-file root)))
      (is (str/includes? (file-text root) "run-refused"))
      (is (str/includes? (file-text root) "thread-id=t-1"))
      (is (str/includes? (file-text root) "no provider named :beta"))
      (is (str/includes? (file-text root) "ERROR")))
    (testing "and the console carries the same sentence"
      (is (str/includes? (str out) "run-refused"))
      (is (str/includes? (str out) "thread-id=t-1")))
    (testing "with a real stack trace -- what handing the Throwable over buys"
      (is (str/includes? (file-text root) "ExceptionInfo"))
      ;; The munged class name of THIS namespace (hyphens become underscores),
      ;; so it moves with the namespace -- it is the namespace's own name in a
      ;; stack trace, not a fixed string.
      (is (str/includes? (str out) "harness.infra.log_test")))))

(deftest the-log-appends-and-does-not-truncate
  (with-capture "append" root _
    (dotimes [i 3]
      (try (throw (ex-info (str "failure " i) {}))
           (catch Throwable t (log/error! :twice t {}))))
    ;; COUNTED BY RECORD, NOT BY LINE: an ERROR is a timestamped line followed
    ;; by a stack trace, so counting newlines counts frames. The record is what
    ;; starts with a date.
    (let [records (filterv #(re-find #"^\d{4}-\d{2}-\d{2} " %)
                           (str/split-lines (file-text root)))]
      (is (= 3 (count records)) "three events, three records -- append, never truncate")
      ;; The message is the record's own line; the exception follows it as the
      ;; stack trace `%ex` renders, so the MESSAGE of each failure is found in
      ;; the trace's first line. Both are asserted, in order.
      (is (= ["failure 0" "failure 1" "failure 2"]
             (mapv second (re-seq #"ExceptionInfo: (failure \d)" (file-text root))))
          "appended in order, none overwritten"))))

(deftest a-call-with-no-exception-still-writes-a-line
  ;; Not every failure arrives as a Throwable -- a route can record one it built
  ;; as a value -- so the call must not depend on a stack trace being there.
  (with-capture "bare" root _
    (log/error! :refused (ex-info "just a sentence" {}) nil)
    (is (str/includes? (file-text root) "refused"))
    (is (str/includes? (file-text root) "just a sentence"))))

(deftest a-request-that-throws-is-answered-500-and-written-down
  ;; The net under the route table, driven through a route made to throw
  ;; something it does not expect -- the only case the net exists for, since
  ;; every route already catches what it DOES expect.
  (with-capture "net" root _
    (let [resp (with-redefs [project/binding-for
                             (fn [_] (throw (ex-info "storage is unreachable"
                                                     {:reason :unreachable})))]
                 (http/handler {:uri "/api/git" :request-method :get
                                :query-string "threadId=net-1" :headers {}}))]
      (testing "the client gets the server's own sentence, not a blank 500"
        (is (= 500 (:status resp)))
        (is (str/includes? (String. ^bytes (:body resp) "UTF-8") "storage is unreachable")))
      (testing "and the failure is on disk, with the route that raised it"
        (is (str/includes? (file-text root) "request-failed"))
        (is (str/includes? (file-text root) "storage is unreachable"))
        (is (str/includes? (file-text root) "/api/git"))))))

(deftest logging-follows-the-root-when-the-root-moves
  ;; The reason `logging/ensure!` exists. A test binding moves harness.infra.home/root,
  ;; and the file appender -- the one thing in this process that cannot read the
  ;; root per call -- has to follow it. Otherwise a test run writes into the
  ;; developer's real home, which is what the whole fixture exists to prevent.
  (let [a (fresh-home "move-a")
        b (fresh-home "move-b")]
    (binding [home/*root-override* a]
      (logging/ensure!)
      (log/error! :first (ex-info "to a" {}) nil))
    (binding [home/*root-override* b]
      (logging/ensure!)
      (log/error! :second (ex-info "to b" {}) nil))
    (is (str/includes? (file-text a) "to a"))
    (is (not (str/includes? (file-text a) "to b"))
        "the first root stopped being written to once the second was configured")
    (is (str/includes? (file-text b) "to b"))))

(deftest logging-a-failure-never-throws
  (with-capture "safe" _ _
    (is (instance? Throwable (log/error! :x (ex-info "m" {}))))
    (is (nil? (log/error! :x nil {})) "a nil Throwable answers nil rather than throwing")))
