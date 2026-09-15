(ns harness.log
  "The backend's own error log: ONE call that writes where a person is watching
  and where a person can look later, so the two can never disagree.

  WHY BOTH IN ONE FUNCTION. A logger with two sinks and two call sites is a
  logger that eventually has only one -- somebody adds the file call, or the
  console call, and the failure that was visible becomes invisible or the one
  that was recorded becomes unrecorded. Here a caller says WHAT went wrong and
  this namespace decides where that goes, in one place, every time.

  THE FILE IS `<root>/harness.log`, append-only, one JSON object per line --
  the same discipline the session logs follow, for the same reason: a line is a
  fact, a reader parses it without a grammar, and an interrupted write costs one
  line rather than the file. `<root>` is harness.home's, read LIVE per call, so
  this follows CLJ_HARNESS_HOME and a test binding exactly as everything else
  does -- a logger holding a path from startup would write into the developer's
  real home during a test run, which is the failure the whole test fixture
  exists to prevent.

  NOT `logs/`. That directory holds the pre-projects-tree session jsonl files
  and is legacy residue nothing writes to any more; a second population of files
  in it would make 'what is in logs/' a question with two answers.

  LOGGING NEVER THROWS. A failing logger that replaced the error it was
  reporting would be worse than no logger at all -- the original problem would
  be gone from both sinks and the replacement one would be the logger's. So the
  file write is guarded and its own failure is reported to the console, which is
  the only sink left that cannot fail."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [harness.home :as home])
  (:import [java.io File]
           [java.time Instant]))

(def ^:private lock
  "One line is one write, and two threads appending at once would interleave
  halves of two objects into one unparseable line. The session log has the same
  lock for the same reason."
  (Object.))

(defn file
  "The error log's path, as a File. Computed per call -- see the namespace
  docstring on why nothing here caches it."
  ^File []
  (io/file (home/root) "harness.log"))

(def ^:private max-frames
  "How many stack frames the FILE keeps. The console gets the whole trace, which
  is where somebody is reading it now; the file keeps a bounded prefix, because
  it is append-only and unbounded traces are how one malformed request becomes
  a hundred megabytes of jsonl."
  12)

(defn- frames [^Throwable ex]
  (->> (.getStackTrace ex)
       (take max-frames)
       (mapv #(str (.getClassName ^StackTraceElement %)
                   "."
                   (.getMethodName ^StackTraceElement %)
                   " ("
                   (.getFileName ^StackTraceElement %)
                   ":"
                   (.getLineNumber ^StackTraceElement %)
                   ")"))))

(defn- causes [^Throwable ex]
  (loop [t (.getCause ex) acc []]
    (if (or (nil? t) (>= (count acc) 5))
      acc
      (recur (.getCause t) (conj acc (ex-message t))))))

(defn- line
  "The one object written to the file for an error. Field names follow the
  session logs': a reader that knows one knows the other."
  [kind ^Throwable ex context]
  (let [entry (cond-> {:ts      (str (Instant/now))
                       :level   "error"
                       :kind    kind
                       :class   (some-> ex class .getName)
                       :message (ex-message ex)}
                (seq context)   (assoc :context context)
                (seq (causes ex)) (assoc :causes (vec (remove nil? (causes ex))))
                (some? ex)      (assoc :trace (frames ex)))]
    ;; The data a failure carries is the most useful thing about it -- the path
    ;; that was missing, the thread that failed -- and ex-data is where this
    ;; codebase puts it. Rendered through pr-str because it is arbitrary EDN and
    ;; json/write-str would refuse a keyword or a set inside it.
    (if-some [d (ex-data ex)]
      (assoc entry :data (pr-str d))
      entry)))

(defn render
  "An error as the ONE sentence both sinks carry, so a console line and a file
  line can be matched by eye without a tool."
  [kind ^Throwable ex context]
  (str "[error] " (name kind)
       (when (seq context)
         (str " " (pr-str context)))
       ": " (or (ex-message ex) (some-> ex class .getName))))

(defn error!
  "Report EX, which happened while doing KIND, to the console AND to the file.

  CONTEXT is any EDN this codebase already puts on a failure -- a thread id, a
  path -- and it is written to both sinks rather than kept in one.

  Answers the Throwable, so a caller can write `(throw (log/error! :x t {}))`
  and keep a single exit path."
  ([kind ex] (error! kind ex {}))
  ([kind ex context]
  (let [sentence (render kind ex context)]
    (binding [*out* *err*]
      (println sentence)
      ;; The console gets the whole trace: it is for somebody reading NOW, and
      ;; cutting it short there would hide the frame they are looking for.
      ;;
      ;; printStackTrace takes a PrintStream or a PrintWriter, never a bare
      ;; Writer -- and *err* is a Writer in a test that has rebound it to a
      ;; StringWriter (which is how the tests below read what was printed). So
      ;; the wrapping is not defensive: without it, logging an error inside a
      ;; rebound console throws and the error it was reporting is lost.
      (when (instance? Throwable ex)
        (let [w (if (instance? java.io.PrintWriter *err*)
                  ^java.io.PrintWriter *err*
                  (java.io.PrintWriter. ^java.io.Writer *err*))]
          (.printStackTrace ^Throwable ex w)
          (.flush w)))
      (try
        (let [f (file)]
          (.mkdirs (.getParentFile f))
          (locking lock
            (spit f (str (json/write-str (line kind ex context)) "\n")
                  :encoding "UTF-8" :append true)))
        (catch Throwable write-failure
          ;; The file could not be written. Say so here rather than swallowing
          ;; it: the error above is still on the console, and 'the log is not
          ;; being written' is the next thing a person needs to know.
          (println (str "[error] could not write " (.getAbsolutePath (file))
                        ": " (ex-message write-failure)))))))
    ex))
