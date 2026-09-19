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
            [harness.cap.project :as project]
            [harness.test-support :as ts])
  (:import [ch.qos.logback.classic Logger LoggerContext]
           [ch.qos.logback.core Appender]
           [ch.qos.logback.core.rolling RollingFileAppender SizeAndTimeBasedRollingPolicy]
           [java.io StringWriter]
           [java.util.concurrent CountDownLatch]
           [java.util.regex Pattern]
           [org.slf4j LoggerFactory]))

(defn- fresh-home [name]
  (ts/temp-dir (str "log-test-" name)))

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

(defn- appenders
  "Every appender currently attached to the root logger, in Logback's order."
  []
  (vec (iterator-seq (.iteratorForAppenders (root-logger)))))

(defn- appender-names
  "The attached appenders' NAMES. Counted by name rather than only by count,
  because the failure being guarded against is two appenders carrying the SAME
  name -- a root logger like that writes every line twice while still having an
  innocent-looking iterator."
  []
  (mapv #(.getName ^Appender %) (appenders)))

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

(deftest add-appender-stacks-and-a-second-file-appender-aborts
  ;; THE TWO MEASUREMENTS `configure!`'s SHAPE RESTS ON. The no-hole ordering --
  ;; attach the replacements, THEN detach the old pair -- would only be available
  ;; if Logback held two of each at once, and it does not:
  ;;
  ;;   1. `Logger.addAppender` does NOT replace a same-named appender; it stacks
  ;;      (`AppenderAttachableImpl` -> `COWArrayList.addIfAbsent`, which compares
  ;;      by `equals`, and `AppenderBase` leaves `equals` as identity).
  ;;   2. A second `RollingFileAppender` on the same file-name pattern ABORTS its
  ;;      own start (logback's per-context collision map) and never writes a byte.
  ;;      The old rolling appender therefore has to be STOPPED before the new one
  ;;      starts, which forces detach-first and leaves the brief window in which
  ;;      the root logger carries no appenders -- the hole `configure!`'s
  ;;      docstring documents.
  ;;
  ;; Asserted so a Logback upgrade that changed either turns red here, beside the
  ;; comment in `configure!` that assumed it.
  (with-capture "appender-semantics" root _
    (testing "a same-named appender stacks rather than replacing"
      (let [extra ((ns-resolve 'harness.infra.logging 'console-appender) (StringWriter.))]
        (try
          (.addAppender (root-logger) extra)
          (is (= 3 (count (appenders)))
              "a second \"console\" stacked -- Logback does not replace by name")
          (is (= 2 (count (filter #(= "console" %) (appender-names)))))
          (finally
            (.detachAppender (root-logger) extra)
            (.stop extra)))
        (is (= 2 (count (appenders))) "removed again by identity")))
    (testing "a second file appender on the same pattern aborts its start"
      (let [other (fresh-home "collision")
            mk    (ns-resolve 'harness.infra.logging 'rolling-appender)
            a     (mk other)
            b     (mk other)]
        (try
          (is (.isStarted ^Appender a) "the first file appender for a path starts")
          (is (not (.isStarted ^Appender b))
              "the second on the SAME pattern aborts -- it never writes")
          (finally
            (.stop a)
            (.stop b)))))))

(deftest concurrent-ensure!-single-flights--two-appenders-and-no-doubled-line
  ;; THE RACE THE MONITOR CLOSES. Every thread reads the root, decides it moved,
  ;; and rebuilds. `ensure!` used to read the root and decide OUTSIDE any lock, so
  ;; two threads that both read the OLD root both rebuilt, and the interleaving
  ;; `detach/detach/add/add` left each appender installed twice -- after which
  ;; every line is written to the console eight times, for the rest of the process.
  ;;
  ;; THE WINDOW GATE FORCES THAT INTERLEAVING DETERMINISTICALLY. It wraps the
  ;; appender BUILDER, which `configure!` calls AFTER detaching every appender and
  ;; BEFORE adding the new pair: each thread detaches, piles up at the gate, and is
  ;; released together to add -- detach/detach/.../add/add/..., the exact order,
  ;; witnessed rather than hoped for. Under the fix the same gate sees ONE thread;
  ;; the rest queue on the monitor and never reach it.
  ;;
  ;; THE CONSOLE IS WHERE THE DUPLICATION SHOWS. The FILE cannot double even with
  ;; the bug, because Logback refuses the second rolling appender on the same
  ;; pattern (see the measurement test above), so the file assertion below passes
  ;; both before and after -- it is the ticket's own wording, kept for that
  ;; reason. The count-by-NAME assertion and the console assertion are what go red
  ;; on the old code.
  (let [old-root    (fresh-home "race-old")
        new-root    (fresh-home "race-new")
        out         (StringWriter.)
        console-out (StringWriter.)
        n           8
        markers     (mapv #(str "race-marker-" % "-end") (range n))]
    ;; The fixture's own configure! must run BEFORE the window is armed, or it
    ;; would be the call the gate catches.
    (binding [home/*root-override* old-root]
      (logging/configure! {:root old-root :console out}))
    ;; A PRIVATE BUILDER, resolved at runtime: the window has to sit between the
    ;; detach and the add, and that is where `configure!` builds the pair.
    (let [gate (ts/window-gate (ns-resolve 'harness.infra.logging 'console-appender)
                               10000)]
      (try
        (let [start  (ts/start-gate n 8000)
              logged (ts/start-gate n 8000)
              done   (CountDownLatch. n)]
          (dotimes [i n]
            (doto (Thread. ^Runnable
                           (fn []
                             (try
                               ((:arrive start))
                               ;; `*err*` is what a reconfigure with no explicit
                               ;; console target picks up, so binding it here is
                               ;; what lets the console be read back -- on the same
                               ;; writer whichever thread rebuilds.
                               (binding [home/*root-override* new-root
                                         *err*                 console-out]
                                 ;; Reconfigure, then -- only once every thread
                                 ;; has done so -- write one line of its own.
                                 (logging/ensure!)
                                 ((:arrive logged))
                                 (log/info! :race {:marker (markers i)}))
                               (catch Throwable _ nil)
                               (finally (.countDown done)))))
              (.setDaemon true)
              (.start)))
          ;; EVERY WAIT IS BOUNDED, so a premise that never comes true fails on its
          ;; assertion instead of hanging the suite. Under the fix only one thread
          ;; reaches the window and this times out -- and the release below still
          ;; runs, so the case goes on to assert.
          (ts/holds-within? #(= n ((:witness start))) 8000)
          (ts/holds-within? #(>= ((:entered gate)) n) 2000)
          ((:release gate))
          (is (.await done 20 java.util.concurrent.TimeUnit/SECONDS)
              "all eight threads finished")
          (testing "the root logger carries the console and rolling appenders, once each"
            (let [names (appender-names)]
              (is (= 2 (count names))
                  (str "exactly two appenders, got " (pr-str (frequencies names))))
              (is (= {"console" 1 "rolling" 1} (frequencies names)))))
          (testing "each thread's line reaches the console exactly once"
            (let [text (str console-out)]
              (doseq [[i m] (map-indexed vector markers)]
                (is (= 1 (count (re-seq (re-pattern (Pattern/quote m)) text)))
                    (str "console line " i " appears exactly once")))))
          (testing "and each thread's line is in the file exactly once"
            (let [text (file-text new-root)]
              (doseq [[i m] (map-indexed vector markers)]
                (is (= 1 (count (re-seq (re-pattern (Pattern/quote m)) text)))
                    (str "file line " i " appears exactly once")))
              (is (= n (count (re-seq #"race-marker-\d+-end" text)))
                  "no line was written twice"))))
        (finally (gate :restore))))))
