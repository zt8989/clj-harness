(ns harness.infra.llm-debug
  "The LLM traffic log: one JSON line per model call, holding the request VERBATIM
  and what came back -- written so that a question asked LATER, offline, has the
  bytes to answer it ('why did the vendor's prefix cache miss on call 41?').

  WHY A FILE OF ITS OWN, rather than a line in the rolling log
  (harness.infra.logging). Two reasons, and the second decides it. The VOLUME: a
  request carries the whole conversation and its tool table, so one call is tens to
  hundreds of kilobytes -- a size that does not belong in the file that also carries
  the process's ordinary news, and one that would bury it there. And the SHAPE:
  cache analysis is a question about PREFIXES, put by a program that lines the calls
  up and diffs them, so a line that is nothing but a JSON object is worth more than a
  `kind k=v` line a reader would have to take apart first.

  BOTH SIDES GO IN AS THE TEXT THAT CROSSED THE WIRE, and that is the point rather than
  a shortcut. The prefix cache keys on the BYTES THAT WENT OUT: an analysis that
  re-serialized a parsed map would be comparing a second spelling of the request -- key
  order, the empty `reasoning_content` pad, the tool table's order all free to differ --
  and 'the prefix was identical' would stop being a fact about the wire. So the request
  body goes in as a JSON string and a reader may hash it as it stands.

  THE RESPONSE'S `:body` IS THE RAW SSE FRAME TEXT, for the mirror-image reason: the
  folded `:message`/`:telemetry` beside it are a READING of the stream, and a reading
  cannot be checked against its source once the source is gone. 'The vendor never
  mentioned cached tokens' and 'our fold dropped them' look the same in a folded map and
  only the second one is a bug -- `usage.prompt_tokens_details.cached_tokens` is the
  number people come here to read, and this is its only copy. Both are logged, so the
  reading and the evidence behind it are on one line; scripts/llm-prefix-report.mjs
  re-reads the raw usage and says so when the two disagree.

  IT IS OFF UNTIL ASKED FOR: the switch is the CLJ_HARNESS_LLM_DEBUG environment
  variable (1 / true / yes / on, case and surrounding blanks aside). It is read on
  every call and never cached, for the same reason harness.infra.home reads its root
  per call -- a test binding and a deployment's environment both have to take effect
  mid-process, and a value resolved once would make the second one a lie.

  A LOG LINE MAY NEVER BREAK A RUN. A read-only home or a full disk costs the LINE
  and not the model call: the append is best-effort, and it says so once on stderr --
  the trade harness.infra.logging already makes for its own file appender.

  THE FILE IS BOUNDED BY SIZE, one generation deep: past `max-bytes` the current file
  becomes `llm-debug.1.jsonl` and a new one starts, so the tree holds at most two
  files. ONE generation rather than the logback policy's thirty days is a deliberate
  difference: this is a diagnostic someone turns on, reads, and turns off, and the
  cost of keeping a histogram of yesterday's traffic -- which nobody asked for -- is
  a directory that grows while nobody is looking."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [harness.infra.home :as home]))

(def env-var
  "The environment variable that turns this on. Named here rather than spelled at
  the read site so that the docstring, the tests and whoever sets it all name one
  string."
  "CLJ_HARNESS_LLM_DEBUG")

(def ^:dynamic *override*
  "TEST-ONLY switch: true or false to force the answer, nil (production) to ask the
  environment.

  A dynamic var rather than a seam function, because what it stands in for -- an
  environment variable -- cannot be set inside a running JVM: `System/getenv` is
  read-only, and a test that had to spawn a child process to flip one flag would be
  testing the spawner. BOUND, never set, the same rule as
  harness.infra.home/*root-override*."
  nil)

(def file-name
  "The live file, under `<root>/logs/` -- the same tree the process log is written
  into, since both answer 'what did this process do' and a second directory for one
  of them would be a second place to look."
  "llm-debug.jsonl")

(def rotated-name
  "The one previous generation. Its name deliberately does NOT end in the live one's
  suffix, so a reader that globs for the live file cannot pick up a stale one and
  read yesterday's traffic as today's."
  "llm-debug.1.jsonl")

(def max-bytes
  "How large the live file may grow before it is rotated away. 32 MB is a few hundred
  whole-conversation requests -- sessions enough to answer a cache question about,
  and small enough that a forgotten switch costs a bounded amount of disk."
  (* 32 1024 1024))

(defonce ^:private append-lock
  ;; One monitor around mkdirs + rotate + append, so the rotate cannot move a file
  ;; out from under another thread's append. Monitoring is the same choice
  ;; harness.infra.logging makes for the same shape of problem, and it is not a
  ;; substitute for the per-call `home/root` read: concurrent runs of two SESSIONS
  ;; are ordinary here (http-kit's pool), so the lock has to exist.
  (Object.))

(defonce ^:private warned
  ;; The failure note is said ONCE per process, not once per call: a read-only home
  ;; would otherwise put a line on stderr for every model call for the rest of the
  ;; session, which is a louder problem than the one it is reporting.
  (atom false))

(defn enabled-name?
  "TRUE for VALUE when it names the switch as ON. The four spellings are pinned
  here rather than being discovered by whoever sets the variable by hand; anything
  else -- including a value nobody meant as a switch, like \"0\" or \"false\" -- is
  off.

  PUBLIC BECAUSE IT IS THE WHOLE CONFIGURATION SURFACE: a test can pin every
  spelling without a JVM that has the variable set, which is a thing
  `System/getenv` does not allow."
  [value]
  (contains? #{"1" "true" "yes" "on"} (some-> value str/trim str/lower-case)))

(defn enabled?
  "TRUE when LLM traffic is being logged right now."
  []
  (if (some? *override*)
    *override*
    (enabled-name? (System/getenv env-var))))

(defn- live-file ^java.io.File [] (io/file (home/root) "logs" file-name))

(defn- rotated-file ^java.io.File [] (io/file (home/root) "logs" rotated-name))

(defn rotate-when-full!
  "Called under `append-lock` before an append: when F has reached CAP bytes, drop
  the previous generation and make F it.

  A FILE THAT IS NOT THERE HAS LENGTH ZERO, so the first append needs no special
  case -- and nothing is rotated on a switch nobody turned on, because this is only
  ever reached from an append.

  CAP IS A PARAMETER BECAUSE A TEST MUST NOT HAVE TO WRITE 32 MB TO SEE ONE RENAME:
  what is worth pinning is the decision, not the size. `record!` passes `max-bytes`."
  [^java.io.File f cap]
  (when (>= (.length f) cap)
    (let [older (rotated-file)]
      (io/delete-file older true)
      (.renameTo f older))))

(defn- note-failure!
  "Say once, on stderr, that the traffic log cannot be written -- and name the root
  it tried, because 'somewhere' is not an answer to 'why is my file empty'."
  [^Throwable t]
  (when (compare-and-set! warned false true)
    (binding [*out* *err*]
      (println (str "harness.infra.llm-debug: could not write " file-name " under "
                    (home/root) " -- " (ex-message t)
                    "; LLM traffic is not being logged")))))

(defn record!
  "Append ENTRY to the traffic log as one JSON line, with `:ts` (epoch millis)
  stamped on it here. Answers nil, and NEVER THROWS: a caller on the wire path is
  not the place to discover that a disk is full.

  THE ENCODING HAPPENS BEFORE THE LOCK, so the monitor covers only the mkdirs,
  the rotate and the append -- the parts that touch the file."
  [entry]
  (when (enabled?)
    (try
      (let [line (str (json/write-str (assoc entry :ts (System/currentTimeMillis))
                                      :escape-unicode false)
                      "\n")]
        (locking append-lock
          (let [f (live-file)]
            (.mkdirs (.getParentFile f))
            (rotate-when-full! f max-bytes)
            (spit f line :append true :encoding "UTF-8"))))
      (catch Throwable t (note-failure! t))))
  nil)