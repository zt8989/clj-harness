(ns harness.infra.logging
  "Logback, configured from Clojure rather than from `logback.xml`, for two
  reasons that are both about this repo rather than about taste.

  THERE IS NO RESOURCE DIRECTORY. `:paths` is `[\"src\"]`, so an XML on the
  classpath would have to live in the source tree -- and configuration that is
  code-shaped, sitting next to the code it configures, is easier to keep honest
  than a file whose only reader is a library.

  AND THE ROOT IS DYNAMIC. Logback's file appender fixes its path when it starts,
  while everything else in this harness reads `harness.infra.home/root` per call, so a
  test binding or a `CLJ_HARNESS_HOME` change moves every OTHER file the process
  writes. `ensure!` is the seam that keeps that true here too: it reconfigures
  when the root has moved, so a test run writes its log lines into the test's temp
  root instead of the developer's real home -- the failure the whole test fixture
  exists to prevent, and the one a logging library reintroduces by caching a path
  at startup.

  ROTATION IS BY DATE **AND** SIZE, in one policy, and that is the entire reason
  Logback is here. `SizeAndTimeBasedRollingPolicy` writes
  `harness.2026-09-15.0.log`, then `.1.log` within the same day once the size cap
  is hit, then a new date's file at midnight. Either half alone is the bug: a
  date-only policy lets one bad afternoon produce a file nobody can open, and a
  size-only one buries 'what happened on Tuesday' among Wednesday's overflow.
  `max-history` and `total-size-cap` bound the whole thing, because rotation that
  never deletes is just a slower way to fill a disk.

  THE CONSOLE APPENDER WRITES TO STDERR, and that is load-bearing rather than
  tidy: `dev/harness/e2e_server.clj` prints exactly one line to STDOUT
  (`PRINT-READY {:port ..}`) for its spawner to parse, and a log line landing on
  stdout would be read as part of that handshake."
  (:require [clojure.java.io :as io]
            [harness.infra.home :as home])
  (:import [ch.qos.logback.classic Level Logger LoggerContext]
           [ch.qos.logback.classic.encoder PatternLayoutEncoder]
           [ch.qos.logback.classic.spi ILoggingEvent]
           [ch.qos.logback.core AppenderBase]
           [ch.qos.logback.core.encoder Encoder]
           [ch.qos.logback.core.rolling RollingFileAppender SizeAndTimeBasedRollingPolicy]
           [ch.qos.logback.core.util FileSize]
           [java.io Writer]
           [java.nio.charset StandardCharsets]
           [org.slf4j LoggerFactory]))

(def ^:private file-pattern
  "Percent-escapes follow logback's PatternLayout. `%ex` renders the whole stack
  trace, which is why nothing in this namespace calls `.printStackTrace` by
  hand."
  "%d{yyyy-MM-dd HH:mm:ss.SSS} %-5level [%thread] %logger{24} - %msg%n%ex")

(def ^:private console-pattern
  "%d{HH:mm:ss.SSS} %-5level %logger{24} - %msg%n%ex")

(def max-file-size
  "Roll to the next index within a day past this. 10 MB is a size a person can
  open in an editor, which is the point of rotating at all."
  "10MB")

(def max-history
  "Whole days of rotation kept, per Logback's own arithmetic on the date pattern.
  A month is long enough to answer 'what changed' and short enough that a
  forgotten process cannot fill a disk."
  30)

(def total-size-cap
  "The ceiling on the directory, on top of `max-history`: history bounds the
  COUNT and this bounds the SPACE, and a request loop can make one day enormous."
  "500MB")

(def ^:private configured-root
  "The root the current configuration was built for, or nil. See `ensure!`.

  READ AND WRITTEN ONLY UNDER `reconfigure-lock` -- the compare in `ensure!` and
  the reset in `configure!` are two halves of one decision, and splitting them
  across a lock boundary is the race this pair exists to close."
  (atom nil))

(def ^:private reconfigure-lock
  "The one monitor under which BOTH halves of a root change happen: deciding that
  the root has moved (`ensure!`) and rebuilding the appenders for it
  (`configure!`).

  WHY A MONITOR RATHER THAN THE ATOM. The atom is the state; borrowing it as a
  lock would make a reader think the state is doing the mutual exclusion. It is
  not -- the exclusion is a separate concern, so it gets a separate object.

  WITHOUT IT, two threads both read the old `configured-root` and both rebuild,
  and the `detach/detach/add/add` interleaving leaves each appender installed
  twice; every line is then written twice for the rest of the process. Dormant in
  production (the root only moves under test binding) and live under the test
  runner, which is where it was found."
  (Object.))

(defn- ^LoggerContext context []
  (LoggerFactory/getILoggerFactory))

(defn- encoder
  "A pattern encoder over OUT's layout. `PatternLayoutEncoder` rather than
  `PatternLayout` because the appenders take an Encoder, and this is the one
  that wraps a layout into bytes."
  ^Encoder [pattern]
  (doto (PatternLayoutEncoder.)
    (.setContext (context))
    (.setPattern pattern)
    ;; Pinned rather than inherited from the JVM default, which is the rule this
    ;; repo follows at every byte boundary -- a Chinese Windows JVM defaults to
    ;; GBK and would silently mangle a message on the way into a file.
    (.setCharset StandardCharsets/UTF_8)
    (.start)))

(defn- console-appender
  "An appender over OUT, which is System/err in production and a Writer in a test
  that wants to read what was logged.

  Logback's own `ConsoleAppender` resolves `System.err` when it starts and cannot
  be pointed anywhere else afterwards -- which would make 'the same sentence
  reached the console' untestable, and is why this builds the appender by hand:
  the target is the one thing the caller needs to vary."
  ^AppenderBase [^Writer out]
  (let [^Encoder enc (encoder console-pattern)]
    (doto (proxy [AppenderBase] []
            (append [^ILoggingEvent event]
              (locking out
                ;; THE ENCODER ANSWERS BYTES, and `str` over a byte array gives
                ;; "[B@1d6daa8a" -- which is what this appender wrote to the
                ;; console until a test read it back and found the address of an
                ;; array instead of a log line. Decoded explicitly as UTF-8, the
                ;; same rule the rest of this repo follows at every byte boundary.
                (.write out (String. ^bytes (.encode enc event) StandardCharsets/UTF_8))
                (.flush out))))
      ;; Named so a test can ask the context for it BY NAME rather than by
      ;; position: the order appenders come back in is logback's business.
      (.setName "console")
      ;; STARTED, and that is not a formality: logback's `doAppend` drops every
      ;; event for an appender that has not been started, silently. A hand-built
      ;; appender that forgets this one call is a logger that accepts everything
      ;; and writes nothing.
      (.setContext (context))
      (.start))))

(defn- rolling-appender
  "The file half: `<root>/logs/harness.infra.log` now, `harness.<date>.<n>.log` as it
  rotates."
  ^RollingFileAppender [^String root]
  (let [dir  (io/file root "logs")
        _    (.mkdirs dir)
        file (io/file dir "harness.infra.log")
        app  (RollingFileAppender.)]
    (.setContext app (context))
    (.setName app "rolling")
    (.setFile app (.getAbsolutePath file))
    (.setAppend app true)
    (.setEncoder app (encoder file-pattern))
    (let [policy (doto (SizeAndTimeBasedRollingPolicy.)
                   (.setContext (context))
                   (.setParent app)
                   (.setFileNamePattern (.getAbsolutePath
                                         (io/file dir "harness.%d{yyyy-MM-dd}.%i.log")))
                   (.setMaxFileSize (FileSize/valueOf max-file-size))
                   (.setMaxHistory (int max-history))
                   (.setTotalSizeCap (FileSize/valueOf total-size-cap)))]
      (.setRollingPolicy app policy)
      (.start policy))
    (.start app)
    app))

(defn configure!
  "Point the root logger at a console appender and a rolling file appender under
  ROOT (default: harness.infra.home/root). Answers the root it configured for.

  IDEMPOTENT BY RECONFIGURATION rather than by a guard: calling it twice replaces
  what was there and builds again, because the whole reason it exists is that the
  root can move. `ensure!` is what decides when that is necessary.

  SINGLE-FLIGHT. The rebuild runs under `reconfigure-lock`, the same monitor
  `ensure!` holds across its read of the root and its decision, so two callers
  cannot interleave a detach with an add and leave each appender installed twice.

  A LINE ARRIVING WHILE THE ROOT IS MOVING IS DROPPED. This is a deliberate,
  measured choice rather than an oversight. The old pair is detached and stopped
  FIRST (`detachAndStopAllAppenders`), which leaves a window in which the root
  logger carries no appenders at all; a line logged in that window reaches
  neither sink. The obvious way to avoid the hole -- attach the replacement
  before detaching the old one, so the line is duplicated rather than lost -- was
  MEASURED against logback-classic 1.5.6 and does not hold, for two reasons:

    1. `Logger.addAppender` does not replace a same-named appender, so adding
       without detaching stacks. It is `AppenderAttachableImpl.addAppender` ->
       `COWArrayList.addIfAbsent` -> `CopyOnWriteArrayList.addIfAbsent`, which
       compares with `equals`, and `AppenderBase` does not override `equals`, so
       a fresh appender is always 'absent' and always appended to the list.
    2. Worse, `RollingFileAppender.start` REFUSES a second appender whose
       file-name pattern collides with one already attached (a per-context
       collision map keyed by appender name). The new rolling appender would
       abort its own start and never write a byte -- so a same-root reconfigure
       (two servers started in one process) would leave the file appender dead.

       Detaching and stopping the old pair first is what removes it from that
       collision map and lets the replacement start. There is therefore no
       ordering in which the file appender is swapped without a gap, and the gap
       is the price of every reconfigure, not only a root move.

  The hole is bounded by how rarely this happens: `ensure!` only reconfigures
  when `harness.infra.home/root` has actually moved, which in production it does
  not. See `add-appender-stacks-and-a-second-file-appender-aborts` in
  harness.infra.log-test for the measurement."
  ([] (configure! {}))
  ([{:keys [root console]}]
   (locking reconfigure-lock
     (let [root (str (or root (home/root)))
           out  (or console *err*)
           ^LoggerContext ctx (context)
           root-logger (.getLogger ctx (Logger/ROOT_LOGGER_NAME))]
       ;; Detach first, so a second call replaces the appenders rather than adding
       ;; a second copy of each -- and, for the file half, so the old rolling
       ;; appender is out of logback's collision map before the new one starts.
       (.detachAndStopAllAppenders root-logger)
       (.setLevel root-logger Level/INFO)
       (.addAppender root-logger (console-appender out))
       ;; The file half is best-effort: a read-only root must not stop the process
       ;; from serving. The console appender above always made it, so a failure
       ;; here costs the file and not the signal.
       (try (.addAppender root-logger (rolling-appender root))
            (catch Throwable t
              (binding [*out* *err*]
                (println (str "harness.infra.logging: no file appender under " root " -- "
                              (ex-message t) "; logging to the console only")))))
       (reset! configured-root root)
       root))))

(defn ensure!
  "Configure if the root has moved since the last call, and answer the root.
  This is what `harness.infra.log` calls before every line -- the check is a string
  compare, and it is the price of a file appender in a process whose root is a
  live read everywhere else.

  THE READ AND THE DECISION ARE BOTH INSIDE THE MONITOR. `(home/root)` is read
  under `reconfigure-lock`, never before it: a value read outside the lock can
  already be stale by the time the lock is won, and two threads that each read the
  old root outside the lock would each decide to rebuild -- the exact detach/add
  interleaving `configure!` exists to prevent. The monitor is held across the
  rebuild as well, so the second thread re-reads a root that is now current and
  finds nothing to do. The lock is reentrant, so `configure!` taking it again on
  this thread is harmless.

  See `configure!` for what happens to a line that arrives in the middle of a
  move: it is dropped."
  []
  (locking reconfigure-lock
    (let [root (str (home/root))]
      (when (not= root @configured-root)
        (configure! {:root root}))
      root)))
