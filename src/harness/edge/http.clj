(ns harness.edge.http
  "The AG-UI edge. One POST endpoint, SSE out, CORS so a page served from THIS
  MACHINE can call it directly whatever port it is on (there is no proxy in front
  of us). Alongside it, a small management
  edge of plain JSON endpoints -- /api/project, the session's project-directory
  binding, plus /api/project/pick, the OS folder dialog that feeds it -- which
  answers THREE things: a directory, a cancellation, or THIS MACHINE HAS NO
  DIALOG TO OPEN, and that last one is not another way of saying the human said
  no -- sharing the same CORS and logging.

  Also append-only JSONL logging: one file per thread, under the session's
  project's workspace in the home's projects tree -- the path is the one place
  where 'where things are' and 'who this session is' are joined (see
  log-dir-for). These line kinds.

    \"input\"   -- the client's RunAgentInput as received.
    \"event\"   -- every AG-UI frame we emitted.
    \"message\" -- one line per provider-shaped message the LLM saw or produced,
                   VERBATIM: the ASSEMBLED system message (prompt.md's frozen
                   opening plus what the SystemPrompt hooks appended), each inbound
                   message (per-run context rides as a trailing user message), and
                   every assistant reply / tool result the kernel appended.
    \"tools/*\" -- the tool execution lifecycle (pre-execute / execute /
                   post-execute), keyed by toolCallId. No wire frame at all.
    \"approval/decided\" -- a human's answer to a parked call, with the interrupt
                   id and whatever payload the client attached.
    \"provider/init\"   -- once per thread, on its first run: which provider and
                   model this session serves from, the source that chose them,
                   what the catalog resolved that to (endpoint + the model's
                   input/output modalities), and the fact that the api-key was
                   stripped. Never a per-run snapshot.
    \"provider/changed\" -- a mid-session change of the selection, before ->
                   after, with the session's whole tier afterwards and what it
                   resolved to, once the approving human's decision has been
                   consumed.
    \"project/bound\" -- a session's project-directory binding, BEFORE ->
                   AFTER (a first bind's before is null; rebinding moves the
                   root and lands another line, so the directory timeline
                   reads straight off the log). Written by the /api/project
                   endpoint, OUTSIDE any run (runId null). The reader takes
                   the last line, like any append-only record.
    \"session/rebuilt\" -- a rebuild action, recorded on the log it rebuilt:
                   the message count and the fact. The rebuild itself only
                   READS a log that is complete; the one write it can be forced
                   into is the line below.
    \"session/closed-off\" -- a rebuild found the log ending MID-RUN (a process
                   killed between a run's last frame and its terminal one) and
                   closed it instead of refusing: which run, at which frame, and
                   the frames appended -- a result for every call that never
                   answered, then RUN_ERROR. Written BEFORE the frames it names,
                   so the record explains a terminal frame that no run emitted.
                   A log that ends where it should is untouched by this.
    \"log/carried-back\" -- while the store could not answer (it was moved aside
                   and rebuilt empty), a session's records landed in the reserved
                   workspace; when the binding returned, the writer carried that
                   segment back into the conversation's file -- append, then rename
                   the source to <thread>.jsonl.carried-<stamp>. Names both paths
                   and how many lines moved. See `carry-back!`.
    \"log/carry-refused\" -- such a segment was found but NOT folded in, because
                   the two files' timestamps overlap and appending would read as
                   one conversation out of order. Names both paths and says why;
                   both files are left as they were. Said once per session per
                   process, not beside every record.

  All of it is a RECORD, never a source of truth -- the client owns the conversation,
  and the server never reads the file back."
  (:require [clojure.core.async :as async]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [harness.edge.ag-ui :as ag]
            [harness.kernel.event :as ev]
            [harness.cap.git :as git]
            [harness.kernel.hooks.dispatch :as hook]
            [harness.infra.home :as home]
            [harness.infra.log :as log]
            [harness.infra.logging :as logging]
            [harness.cap.providers :as providers]
            [harness.kernel.llm :as llm]
            [harness.kernel.loop :as loop]
            [harness.cap.preamble :as preamble]
            [harness.cap.project :as project]
            [harness.edge.replay :as replay]
            [harness.edge.context :as context]
            [harness.edge.stats :as stats]
            [harness.edge.trajectory :as trajectory]
            ;; skill-picker 的 /api/skills 用它（那一票在 main 上，本分支没有）：
            [harness.cap.skills :as skills]
            [harness.cap.system-prompt :as system-prompt]
            [harness.cap.subagents :as subagents]
            [harness.cap.hooks :as cap-hooks]
            [harness.cap.mcp :as cap-mcp]
            [harness.cap.tools :as cap-tools]
            [harness.kernel.tools :as tools]
            [org.httpkit.server :as hk])
  (:import [java.net URI]
           [java.nio.charset StandardCharsets]))

(def port 8080)

(def ui-origin
  "The origin this edge names when a request names no page of its own, and the
  value `start!` uses when the caller names none.

  AN ORIGIN, NOT A LIST, and not an echo of whatever `Origin` happens to arrive.
  A page served from THIS MACHINE is answered by rule instead, whatever port it is
  on (`localhost-page?` below) -- that is the case the dev UI is in, and the reason
  neither side has to be told the other's port. This one covers the other case: a
  UI served from somewhere else, whose origin nobody could guess, so it has to be
  said (`--ui-origin`). A deployment puts the page and this server behind one
  address, where no cross-origin request is made at all."
  "http://localhost:5173")

(defonce ^:private named-origin
  ;; THE ONE ORIGIN ALLOWED BY NAME, as opposed to the ones allowed by rule
  ;; (`localhost-page?`). Why a HOLDER rather than the def above it: which process
  ;; this is is not known until it starts -- a launcher may name a UI served from
  ;; somewhere else -- so the value has to be settable AFTER this file is loaded. A
  ;; value read straight into a header map is baked in at load time, which is the
  ;; trap `.scratch/tool-parity/spec.md` recorded -- an `alter-var-root` on the
  ;; string came too late for the map already built out of it.
  ;;
  ;; AND IT IS AN ATOM, NOT A DYNAMIC VAR, because every request is served on an
  ;; http-kit thread: a `binding` here would be silently ignored (AGENTS.md).
  (atom ui-origin))

(def ^:private loopback-hostnames
  "The host names a page served from THIS MACHINE arrives under. Matched WHOLE,
  never by suffix, so `localhost.example.com` is not one of them -- that is the
  difference between a rule and a hole."
  #{"localhost" "127.0.0.1" "::1"})

(defn- request-origin
  "What page is asking, as the browser declares it, or nil when nothing is -- a
  same-origin fetch, `curl`, or the suite.

  http-kit LOWER-CASES request header names, which is the one fact a reader needs
  here and the one way this could quietly always answer nil."
  [req]
  (get (:headers req) "origin"))

(defn- origin-host
  "The host inside an `Origin` value, or nil when the value is not a URL this edge
  can reason about. `null` -- what a sandboxed frame and a `file://` page send --
  is not a URL, so it answers nil here rather than being read as a host named
  'null'."
  [origin]
  (when (string? origin)
    (try
      (let [uri (URI. origin)]
        (when (contains? #{"http" "https"} (some-> (.getScheme uri) str/lower-case))
          (some-> (.getHost uri)
                  str/lower-case
                  (str/replace #"^\[|\]$" ""))))
      (catch Exception _ nil))))

(defn- localhost-page?
  "Is this a page served from this machine? ANY PORT, because the dev UI moves
  between them on its own: vite picks one, `--ui-port` moves it, and the backend
  moves with `--port`. A rule that named a port would put the two back in step by
  hand, which is the thing this branch exists to stop doing."
  [origin]
  (boolean (contains? loopback-hostnames (origin-host origin))))

(defn- cors-origin
  "WHICH ORIGIN THIS ANSWER NAMES, or nil when it names none.

  THIS ASKS THE REQUEST, rather than being told once at startup: a page on this
  machine is answered whatever port it is on, and a page elsewhere is answered
  only if it is the origin this process was started with. An origin that is
  neither gets NO CORS HEADER AT ALL -- the browser then refuses the answer, which
  is what a boundary is for, and naming some third origin would just be a lie
  about who this process talks to.

  A REQUEST THAT NAMES NO PAGE is answered `named-origin`, so every answer from
  this edge carries one shape of headers instead of two. Inert either way -- there
  is no browser to consult them -- and it is what the suite reads."
  [origin]
  (cond
    (nil? origin)            @named-origin
    (localhost-page? origin) origin
    (= origin @named-origin) origin
    :else                    nil))

(defn- cors-headers
  "The three headers an answer from this edge carries, or nil when it names no
  origin. A FUNCTION of the request's `Origin` rather than a constant map, because
  what it says depends on who is asking -- see `cors-origin`."
  [origin]
  (when-some [allowed (cors-origin origin)]
    {"Access-Control-Allow-Origin"  allowed
     "Access-Control-Allow-Methods" "GET, POST, OPTIONS"
     "Access-Control-Allow-Headers" "Content-Type"}))

;; ------------------------------------------------------------------- logging

(defonce ^:private log-lock
  ;; One line is one JSON object, and a reader parses the file line by line --
  ;; so a half-written line is not a smaller record, it is a broken file. Most
  ;; writers are the run's single consumer thread, but a hook fires where its
  ;; point is (a tool call's PostToolUse runs on that call's own thread), so two
  ;; lines can now be in flight at once. Serializing the append is what keeps the
  ;; writer's guarantee true without asking every caller to know about it.
  (Object.))

(def unbound-workspace
  "The workspace for sessions that belong to no project. RESERVED BY
  CONSTRUCTION, not by convention: sanitize maps everything outside
  [A-Za-z0-9._-] to an underscore and the path it is handed is always absolute,
  so a workspace name derived from a project begins with an underscore on Unix
  and with a drive letter on Windows -- never with a dot. A literal `_unbound`
  would collide with a project at /unbound, and two populations of logs sharing
  one directory is exactly the confusion this tree exists to remove."
  ".unbound")

(defn- workspace-for
  "The workspace directory for a project IDENTITY -- a canonical path, or nil for
  a session that belongs to no project.

  THIS IS THE ONE EXPRESSION THAT TURNS A PROJECT INTO A DIRECTORY. Two callers
  need it and they must not drift: the writer, which asks it about a session's
  project, and the sidebar's listing, which asks it about every project at once
  and then looks in the result. It reads from the CANONICAL path -- the project's
  identity, not the spelling a session was bound with -- so every session of one
  project lands in one workspace however that directory was spelled."
  [identity]
  (str (io/file (home/projects-dir)
                (if (some? identity)
                  (home/sanitize identity)
                  unbound-workspace))))

(defn- log-dir-for
  "The workspace directory THREAD-ID's log belongs in.

  The join between 'where things are' (harness.infra.home) and 'who this session is'
  (harness.cap.project) is made here, on the writing side: the project's identity
  comes from the store and the directory comes from the rule above. Keeping the
  join here is what leaves harness.infra.home knowing only the root and the naming rule,
  and what lets the reading side stay filesystem-only -- a listing and a lookup
  both walk the tree and ask the FILESYSTEM where a log is.

  A session the store has never heard of is normal here, not an error: the AG-UI
  edge accepts an id the client owns and the store has not been told about, and
  its log goes to the reserved workspace."
  [thread-id]
  (workspace-for (project/identity-for thread-id)))

(defn- log-file-for
  "The log file the writer owns for THREAD-ID."
  [thread-id]
  (home/log-file (log-dir-for thread-id) thread-id))

;; ------------------------------------- carrying an unbound segment back home
;;
;; THE ACCIDENT THIS REPAIRS (2026-09-18): the store was moved aside and rebuilt
;; empty, so project/identity-for answered nil for every session and the records a
;; live run kept producing landed in projects/.unbound/<thread>.jsonl. When the
;; store was restored the binding was back, so later records went to
;; projects/<workspace>/<thread>.jsonl again -- ONE CONVERSATION IN TWO FILES.
;; Replay, rebuild and the eval reader each read ONE file, so the unbound segment
;; was invisible to all of them. Nothing here asks the store: the writer notices
;; the leftover in the tree and puts it back.

(defonce ^:private carry-back-checked
  (atom #{}))
;; thread-ids whose leftover unbound segment this process has already DEALT WITH.
;; A third writer-side fact, kept apart from init-logged? and session-started? for
;; the same reason they are kept apart from each other: one atom per fact, named for
;; the fact it holds. It is set only when there WAS a segment to deal with, so a
;; thread that is not split yet is still checked on the next record -- and once a
;; carry (or a refusal) has happened, no later line repeats its audit.

(defn- unbound-dir
  "The reserved workspace, as the directory an unbound session's log lands in."
  []
  (io/file (home/projects-dir) unbound-workspace))

(defn- ts-range
  "The [earliest latest] :ts a jsonl file holds, or nil when the file is missing,
  empty, or holds no line with a numeric :ts. A line that does not parse is
  SKIPPED rather than treated as corruption: this reads a file another writer may
  have been appending to, and the last line may be half-written."
  [^java.io.File f]
  (when (.exists f)
    (let [ts (->> (str/split-lines (slurp f :encoding "UTF-8"))
                  (keep (fn [line]
                          (try (:ts (json/read-str line :key-fn keyword))
                               (catch Throwable _ nil))))
                  (filter number?)
                  vec)]
      (when (seq ts) [(reduce min ts) (reduce max ts)]))))

(defn- carry-audit!
  "One audit line about a leftover unbound segment, appended to F -- the
  conversation's own file. runId is nil: this happens on the way to a record, not
  inside a run. A caller that cannot afford this to throw swallows it."
  [^java.io.File f kind payload]
  (spit f (str (json/write-str {:ts (System/currentTimeMillis)
                                :runId nil :kind kind :payload payload}) "\n")
        :append true :encoding "UTF-8"))

(defn- carry-back!
  "If F -- THREAD-ID's destination log -- is in a PROJECT workspace and a segment
  of this session's log is still sitting in the reserved workspace, fold that
  segment back into F.

  APPEND, THEN RENAME: the segment's lines are appended to F in file order, and the
  source is renamed to <thread>.jsonl.carried-<stamp>. The new name deliberately
  does NOT end in .jsonl, so the listing (replay/logs-under) will not pick it up as
  a second conversation; the evidence stays on disk without being read as history.

  NOTHING IS APPENDED UNLESS THE RANGES ARE DISJOINT AND IN ORDER -- F must end at
  or before the segment begins. Any overlap, or a segment older than the file it
  would follow, means appending would read as one conversation in the wrong order,
  so both files are left as they were and an audit line names both paths and says
  why. This is the check the 2026-09-18 hand-repair did first, with `cat`.

  AT MOST ONCE PER THREAD PER PROCESS: once a segment has been carried or refused,
  the thread is in `carry-back-checked` and later records do not look again -- a
  refusal must not write its audit line beside every line of the conversation.

  IT NEVER THROWS INTO THE WRITER: a logging call that fails must not take a run
  down. Every failure -- an unreadable file, an unwritable tree, a rename that would
  not go -- leaves both files as they were and says so in an audit line. A nil
  parent (nowhere to write) is the only silent exit.

  CALLED FROM log! WITH log-lock HELD, so its appends serialize with every other
  writer in this process."
  [thread-id ^java.io.File f]
  (when-not (contains? @carry-back-checked thread-id)
    (try
      (let [dir (.getParentFile f)]
        (when (and dir
                   (not= (.getCanonicalPath ^java.io.File dir)
                         (.getCanonicalPath (unbound-dir))))
          ;; The destination is in a project workspace, so a leftover segment is
          ;; the only thing that could still be in the reserved one.
          (let [source (home/log-file (unbound-dir) thread-id)]
            (when (and (.exists source)
                       (pos? (.length source))
                       (not= (.getCanonicalPath ^java.io.File source)
                             (.getCanonicalPath f)))
              (swap! carry-back-checked conj thread-id)
              (let [dest-range (ts-range f)
                    src-range  (ts-range source)
                    from       (.getAbsolutePath ^java.io.File source)
                    to         (.getAbsolutePath ^java.io.File f)]
                (cond
                  (nil? src-range)
                  (carry-audit! f "log/carry-refused"
                                {:reason "the leftover segment holds no timestamped lines, so the two ranges cannot be checked"
                                 :from from :to to})

                  (and dest-range (not (<= (second dest-range) (first src-range))))
                  (carry-audit! f "log/carry-refused"
                                {:reason (str "the two files' timestamps overlap, so appending would read as one conversation out of order"
                                              " (the conversation's file ends at " (second dest-range)
                                              ", the leftover segment begins at " (first src-range) ")")
                                 :from from :to to
                                 :destination-last (second dest-range)
                                 :segment-first (first src-range)})

                  :else
                  (let [body    (slurp source :encoding "UTF-8")
                        body    (if (str/ends-with? body "\n") body (str body "\n"))
                        lines   (count (str/split-lines body))
                        renamed (io/file (unbound-dir)
                                         (str (home/sanitize thread-id) ".jsonl.carried-"
                                              (System/currentTimeMillis)))]
                    (spit f body :append true :encoding "UTF-8")
                    (if (.renameTo source renamed)
                      (carry-audit! f "log/carried-back"
                                    {:from from :to to :lines lines
                                     :kept-as (.getAbsolutePath ^java.io.File renamed)})
                      (carry-audit! f "log/carry-refused"
                                    {:reason "the segment was appended but its file could not be renamed; both copies remain"
                                     :from from :to to})))))))))
      (catch Throwable t
        (try
          (carry-audit! f "log/carry-refused"
                        {:reason (str "carrying the leftover unbound segment failed: " (ex-message t))
                         :from (.getAbsolutePath ^java.io.File (home/log-file (unbound-dir) thread-id))
                         :to   (.getAbsolutePath ^java.io.File f)})
          (catch Throwable _ nil))))))

(defn- log! [thread-id run-id kind payload]
  (let [f (log-file-for thread-id)
        line (str (json/write-str {:ts (System/currentTimeMillis)
                                   :runId run-id :kind kind :payload payload}) "\n")]
    (.mkdirs (.getParentFile f))
    (locking log-lock
      ;; BEFORE the record: a session whose binding came back after the store was
      ;; rebuilt gets its unbound segment carried into THIS file first, so the line
      ;; about to be written follows the segment rather than landing after a hole.
      (carry-back! thread-id f)
      (spit f line :append true :encoding "UTF-8"))))

(defn- move-log!
  "Carry THREAD-ID's log from one workspace into another, because a rebind moved
  the session.

  A CONVERSATION IS ONE FILE, and that is why this exists rather than letting the
  log stay where it started. Replay, rebuild and the eval reader all reconstruct
  a conversation from a single file; a session whose binding moved and whose
  history was therefore split in two would be unreadable by every one of them --
  and the rebind that caused it is an ordinary action, not an exotic one. So the
  bind carries the file with it.

  THE WHOLE TREE IS ASKED WHERE THIS SESSION'S LOGS ARE, not just the two
  workspaces involved. If the only file is the one being moved, the move is safe.
  If there is any other file under the same name -- the destination already holds
  one, or a copy sits in some third workspace -- the move is REFUSED BY NAME.
  Appending two files would read as one conversation in the wrong order (replay
  folds frames in FILE order), and overwriting would destroy a run's record;
  neither is acceptable, so the human is asked which file is the conversation.
  Asking the tree rather than `to` is what makes the refusal cover every way a
  home can arrive in that state, not just a repeat bind: a store that was
  quarantined and rebuilt no longer knows where a log belongs, a tree can be
  restored from a backup, and a process killed between the bind's commit and this
  rename leaves the file where it was.

  FROM-DIR nil means there is nowhere to move from (a first bind); that is the
  ordinary case, as is a session whose log does not exist yet because it has
  never run."
  [thread-id ^String from-dir ^String to-dir]
  (if (or (nil? from-dir) (= from-dir to-dir))
    nil
    (let [from  (home/log-file from-dir thread-id)
          to    (home/log-file to-dir thread-id)
          found (replay/logs-for (home/projects-dir) thread-id)]
      (when (.exists from)
        (when-some [other (first (remove #(and (= (.getCanonicalPath ^java.io.File from)
                                                   (.getCanonicalPath ^java.io.File %)))
                                         found))]
          (throw (ex-info (str "this session's log is being moved from "
                               (.getAbsolutePath ^java.io.File from)
                               " to " (.getAbsolutePath ^java.io.File to)
                               ", but it already has a log at "
                               (.getAbsolutePath ^java.io.File other)
                               ". Moving one onto the other would either lose a"
                               " run's record or read as one conversation in the"
                               " wrong order; move or remove one of them first, then"
                               " bind again")
                          {:thread-id thread-id
                           :paths [(.getAbsolutePath ^java.io.File from)
                                   (.getAbsolutePath ^java.io.File other)]})))
        (.mkdirs (.getParentFile to))
        (when-not (.renameTo from to)
          ;; A rename between two directories under one home does not fail for
          ;; want of a filesystem, so this is a real refusal and not a warning:
          ;; leaving the log behind would do the one thing this function exists
          ;; to prevent.
          (throw (ex-info (str "could not move the log " (.getAbsolutePath ^java.io.File from)
                               " to " (.getAbsolutePath ^java.io.File to))
                          {:thread-id thread-id
                           :paths [(.getAbsolutePath ^java.io.File from)
                                   (.getAbsolutePath ^java.io.File to)]})))))))

(defonce ^:private init-logged
  (atom #{}))
;; thread-ids whose provider/init line has already been written. Writer-side
;; state, and it lives with its only writer: deliberately NOT re-derived from the
;; log, because the edge must not read its own log, so "have I written the init
;; line yet" has to be remembered somewhere. It is a fact about the FILE, not a
;; copy of the conversation or of the provider.

(defonce ^:private session-started
  (atom #{}))
;; thread-ids whose SessionStart has already fired. A SECOND writer-side fact,
;; kept apart from the init line on purpose: the provider init LINE is not written
;; when a scripted pin serves the session (there is no resolution to record), but
;; the session still started, and a hook bound to SessionStart must fire once for
;; it either way. One atom per fact, each named for the fact it holds.

(defn- claim-once!
  "Add THREAD-ID to A and answer whether THIS call is the one that added it.

  ONE ATOM OPERATION, and it is the whole difference between 'fired once' and 'fired
  once per concurrent run'. The shape it replaces -- `(when-not (seen? id) (do-work)
  (mark-seen! id))` -- is three steps, and two runs of one thread both take the first
  one, so `SessionStart` fires twice and the provider/init line is written twice,
  against a document that promises exactly one line per thread. Two runs of one thread
  is the ordinary case: two tabs, or any client that is not this UI.

  THE CLAIM HAPPENS BEFORE THE WORK, deliberately. A run that dies in between leaves
  the fact marked as done and the line unwritten, which is the direction to fail in:
  what this exists to stop is the SECOND line, not to guarantee the first.

  TWO ATOMS, ONE PER FACT, and they stay two: a scripted pin means the init line is
  never written while the session still started, so 'the init line exists' and 'the
  session started' are different facts about the same thread."
  [^clojure.lang.Atom a thread-id]
  (let [[before _] (swap-vals! a conj thread-id)]
    (not (contains? before thread-id))))

(defn- log-messages!
  "One \"message\" line per provider-shaped message, VERBATIM. The submitted and
  the returned side of the message record both come through here."
  [thread-id run-id msgs]
  (doseq [m msgs]
    (log! thread-id run-id "message" m)))

;; ------------------------------------------------------------------- the edge

(def ^:private terminal #{"RUN_FINISHED" "RUN_ERROR"})

(defn- lifecycle-record
  "A tool-lifecycle or model-call kernel event -> the [kind payload] jsonl line it
  becomes, keyed by toolCallId like applepi's ADR-0021 audit lines. Nil for every
  other event kind. The audit line is additive: the event itself carries no wire
  frame, so the AG-UI conversion upstream of this is untouched.

  THE MODEL-CALL PAIR IS RECORDED AS IT ARRIVES -- the start's identity
  (:model / :base-url / :reasoning-effort) and the end's telemetry (:usage /
  :finish-reason / :model) with the vendor's own key names intact. Nothing is
  renamed or recomputed here: what the read side (harness.edge.stats) needs is the
  vendor's answer, not this edge's opinion of it. The two lines pair by ORDER --
  the nth model/start of a run is that run's nth call."
  [ev]
  (case (:type ev)
    :tool/pre-execute
    ["tools/pre-execute" (merge {:toolCallId (:id ev) :toolName (:name ev)}
                                (when-not (= :pass (:outcome ev))
                                  {:outcome (name (:outcome ev))})
                                (when (seq (:missing ev))
                                  {:missing (mapv name (:missing ev))}))]

    :tool/execute
    ["tools/execute" (merge {:toolCallId (:id ev) :toolName (:name ev)}
                            (when (:error ev) {:error (:error ev)}))]

    :tool/post-execute
    ["tools/post-execute" {:toolCallId (:id ev) :toolName (:name ev)}]

    :model/start
    ["model/start" (dissoc ev :type)]

    ;; AN EMPTY PAYLOAD IS AN ANSWER: {} here says 'this call reported nothing',
    ;; which is what a call that died mid-stream looks like. It is not the same as
    ;; zeroes, and the read side must not be handed a zero it can add up.
    :model/end
    ["model/end" (dissoc ev :type)]

    nil))

(defn- runner
  "Build the frame emitter for one run. Two http-kit rules have to hold at once:

    - the status and headers ride on the FIRST send!, not on the ring response, so a
      separate header-only send is not an option;
    - the LAST frame carries close-after-send?. Closing down a separate code path
      loses the response: http-kit buffers small writes, and a lone close discards
      whatever was never flushed. Watched a complete, correctly logged run deliver
      zero frames that way.

  WHICH IS WHY THE CALLER'S ORIGIN COMES IN AS AN ARGUMENT. `handler` merges the
  CORS headers onto the ring response, and for this one route that is not where
  the wire's headers come from -- so the value has to reach the first frame by
  another road, and this is it.

  Bodies are UTF-8 BYTES: this machine's JVM default charset is GBK, so handing
  http-kit a String would be a coin flip on any non-ASCII.

  IT REPORTS THE TERMINAL INTO STATE -- the run's one side channel to the close
  handler (`handle-run`), since `:on-open` and `:on-close` are two callbacks with
  nothing else in common: whether this stream was ever told to end. (What the run
  was DOING lives in the same atom and is set by the loop that drains the kernel,
  not here -- this function only ever sees frames.) `send!`'s own answer is NOT a
  liveness signal and is deliberately ignored: measured 2026-09-17 on a client that
  had RESET the connection, every `send!` still answered true, four thousand frames
  went into a socket nobody was reading, and http-kit called the close handler only
  when the server closed the channel itself. A TCP socket cannot be asked whether
  the peer is still listening, so 'the browser hung up' is not a fact this process
  can discover by writing."
  [thread-id run-id ch state origin]
  (let [first? (atom true)]
    (fn [frame]
      (log! thread-id run-id "event" frame)
      (let [body  (.getBytes (str "data: " (json/write-str frame) "\n\n")
                             StandardCharsets/UTF_8)
            head  (when @first?
                    {:status  200
                     :headers (merge (cors-headers origin) {"Content-Type" "text/event-stream"
                                           "Cache-Control" "no-cache"})
                     :body    body})
            last? (contains? terminal (:type frame))]
        (reset! first? false)
        (when last?
          (swap! state assoc :terminal (:type frame)))
        (hk/send! ch (or head body) last?)
        (when last?
          ;; LOGGED WHERE IT IS DISPATCHED, not where it was built: this is the frame
          ;; that carries close-after-send?, so 'the run reached a terminal frame' and
          ;; 'the stream was told to end' are one moment. The outcome is the wire's
          ;; own vocabulary (RUN_FINISHED / RUN_ERROR), and the reason rides along for
          ;; the one terminal that has one.
          ;;
          ;; AFTER the send, not before it: a log write is a synchronous file write,
          ;; and putting it in front would delay the frame that ends the run.
          (log/info! :run/terminal {:thread-id thread-id :run-id run-id
                                    :event     (:type frame)
                                    :reason    (:message frame)}))))))

(defn- resume-decisions
  "A client's resume entries -> the decisions the kernel replays, in order:
  {:interrupt-id .. :verdict :approved|:vetoed :payload ..}, each also naming the
  call it answers so the audit line can be keyed by toolCallId.

  The protocol allows only \"resolved\" and \"cancelled\"; anything else, and any
  interrupt this process never parked, is a hard error. Guessing an approval is
  the worst possible failure mode here, so it is refused rather than defaulted."
  [resume]
  (mapv (fn [{:keys [interruptId status payload]}]
          (let [verdict (case status
                          "resolved"  :approved
                          "cancelled" :vetoed
                          (throw (ex-info (str "unknown resume status: " status) {})))
                id      (str interruptId)
                rec     (tools/parked id)]
            (when-not rec
              (throw (ex-info (str "unknown interrupt: " id) {})))
            {:interrupt-id id :verdict verdict :payload payload
             :tool-call-id (:tool-call-id rec)}))
        resume))

(defn- provider-line
  "A provider, in the shape the jsonl records: the SELECTION (which provider,
  which model, what reasoning effort) and what the catalog RESOLVED it to (the
  endpoint and the model's modalities), plus the selection's source.

  RESOLVED IS RECORDED, NOT RE-DERIVED. The catalog is a living thing -- a
  built-in table gains a model, someone moves a base-url -- so a reader
  re-resolving an old init line months later would read today's answer as if it
  were that run's. Writing the resolution down is what keeps the log
  self-describing, and it is exactly why provider/changed carries its override
  rather than leaving a reader to reconstruct the tier.

  Sets render as sorted string vectors (harness.cap.providers/wire): two otherwise
  identical runs must not produce lines differing only in set ordering. The
  api-key is present as the fact that it was STRIPPED, never as a value -- so a
  reader sees it is not a leak rather than wondering whether it was forgotten."
  [provider source]
  (assoc (providers/wire provider) :source source :api-key :stripped))

(defn- guard-input-modalities!
  "Refuse a run whose messages carry a modality the selected model never declared
  it accepts -- an image aimed at a text-only model, typically.

  BEFORE the provider is called, which is the whole point: the vendor's own answer
  to an undeclared modality is a 400 whose body names nothing useful, arriving
  after the request has been paid for. This names the model and the modality, and
  the run never leaves the process.

  A model that declared NOTHING is not guarded (see ag/undeclared-input): no
  declaration is no promise, and enforcing one would be inventing a rule the
  configuration never stated.

  PROPERTY, NOT SECURITY. A configuration that declares :image for a text-only
  model is lying, and nothing here catches that -- the declaration is taken at its
  word. Same standing as the project fence: this stops a slip, and the failure it
  prevents is a confusing error message, not an exploit.

  Returns nil when the run may proceed, so the caller reads as a guard clause."
  [input provider]
  (let [bad (ag/undeclared-input (:messages input) (:input provider))]
    (when (seq bad)
      (throw (ex-info (str "model " (pr-str (or (:model provider) "(unnamed)"))
                           " does not accept " (pr-str (mapv name bad))
                           " input; it declares "
                           (pr-str (mapv name (sort-by name (:input provider))))
                           (when-let [p (:provider provider)] (str " (provider " p ")"))
                           " -- change the model, or send only what it declares")
                      {:model (:model provider)
                       :undeclared (vec bad)
                       :declared (vec (sort-by name (:input provider)))})))))

(defn- opening-blocks!
  "The messages this run OPENS WITH, other than the frozen system prompt: the
  session's instruction files, read fresh, and (from the skills ticket) the
  catalog. Runs INSIDE the edge's hook-sink binding, because folding an
  instruction file is a hook point and this is where it happens -- see
  run-agent! for why the binding wraps the set-up.

  Every file that was folded fires InstructionsLoaded with its path, and the
  verdict is DISCARDED: the point is an observer (:gate? false, :on-error
  :proceed), and there is nothing sensible for a loader to do about a hook that
  refused -- the file is already read. A file that was skipped does NOT fire:
  nothing was folded, and a hook announced for something that did not happen is
  worse than no hook.

  Reads the session's files on EVERY run rather than caching them per thread.
  The alternative -- remember what was loaded and only inject what changed -- is
  how an edited AGENTS.md stops taking effect until somebody restarts, and the
  cost of not caching is one small file read per run."
  [thread-id]
  (let [gathered (preamble/gather
                  {:files (project/preamble-files thread-id)
                   :roots (project/skill-roots thread-id)})]
    (doseq [{:keys [path]} (:instructions gathered)]
      (hook/emit :instructions-loaded {:path path}))
    (preamble/messages gathered)))

(defn- run-agent! [ch state input origin]
  (let [thread-id (str (:threadId input))
        run-id    (str (:runId input))
        ;; ONE emitter and ONE converter per run. The converter owns the open-message
        ;; state machine, so building it per event restarts every message id and
        ;; re-emits START frames -- which an AG-UI client treats as fatal.
        emit    (runner thread-id run-id ch state origin)
        convert (ag/outbound thread-id run-id)]
    (log! thread-id run-id "input" input)
    (async/go
      ;; A GO BLOCK'S EXCEPTION GOES NOWHERE: core.async throws it into the block's
      ;; own channel, which nobody reads -- so a consumer that dies takes the run
      ;; down in silence. The kernel's producer blocks on its next put, the client
      ;; waits for frames that will never come, and the record stops mid-sentence
      ;; with nothing anywhere saying why. That is the one failure in this file
      ;; that cannot be allowed to be quiet, so the whole run body is wrapped.
      (try
        (binding [hook/*sink* {:thread-id thread-id
                               :audit     (fn [payload]
                                            (log! thread-id run-id
                                                  (str "hook/" (:point payload))
                                                  (dissoc payload :point)))
                               :run-id    run-id}]
          ;; A malformed input, an unreadable prompt, a bad config, an image aimed at
          ;; a text-only model -- or a resume naming an interrupt this process never
          ;; parked -- blows up before the run starts. Catch it here and push a
          ;; well-formed RUN_STARTED..RUN_ERROR pair so the client sees a terminated
          ;; run rather than a broken stream.
          ;;
          ;; THE SYSTEM MESSAGE IS ASSEMBLED HERE, and it has to be HERE -- inside
          ;; the binding above -- or it silently loses its hooks: harness.system-
          ;; prompt fires SystemPrompt through this sink, and an unbound sink means
          ;; the point does not dispatch at all. A declaration at that point that
          ;; says no lands in the catch below as an ordinary refusal, with the
          ;; hook's own words as the RUN_ERROR reason.
          (let [[provider messages decisions resolved]
                (try (let [provider (providers/current-provider thread-id (:provider input))]
                       (guard-input-modalities! input provider)
                       [provider
                        ;; THE VENDOR'S THINKING-MODE REQUIREMENT IS MET HERE, on the
                        ;; list this run will log and send -- not inside `stream!`,
                        ;; where it would be easier and would make the `message` audit
                        ;; line disagree with what actually went out. See
                        ;; harness.kernel.llm/thinking-mode-history.
                        ;;
                        ;; AND THE SESSION'S OWN INJECTIONS ARE PART OF WHAT THIS RUN SUBMITS,
                        ;; so they are folded in HERE -- the `message` record's submitted
                        ;; side, written a few lines below, is this same list. The kernel
                        ;; still applies the same function before every LLM call (a skill
                        ;; loaded mid-run has to be visible to the very next one), and
                        ;; that is harmless because it is idempotent. It is also
                        ;; LOAD-BEARING here: which half of the record a message belongs
                        ;; to is decided by COUNT, so an injection the kernel made on its
                        ;; own would shift that boundary and file a client message as
                        ;; part of the kernel's answer.
                        ;; See harness.edge.trajectory/run-segments.
                        (llm/thinking-mode-history
                         (project/before-llm
                          (ag/inbound (:messages input) (system-prompt/assemble thread-id)
                                      (opening-blocks! thread-id)
                                      (:context input))
                          thread-id)
                         provider)
                        (resume-decisions (:resume input))
                        (providers/resolve-provider thread-id (:provider input))])
                     (catch Throwable t
                       ;; A run that could not even be set up -- no provider, a
                       ;; refused model -- is reported to the client as a
                       ;; RUN_ERROR frame AND written down, because the frame
                       ;; scrolls past in a browser and the reason somebody is
                       ;; staring at is often a configuration mistake they will
                       ;; want to read twice.
                       (log/error! :run-refused t {:thread-id thread-id})
                       (doseq [frame (into (vec (convert (ev/run-start)))
                                           (convert (ev/run-error (ex-message t))))]
                         (emit frame))
                       nil))]
            (when provider
              ;; THE RUN'S FIRST LINE IN THE PROCESS LOG, and the anchor every later
              ;; line about this run is read against: a run whose start has no
              ;; terminal, no close and no death beside it is one whose process
              ;; stopped between the two. The model rides along because 'which
              ;; provider did this go to' is the other half of 'and then what'.
              (log/info! :run/start {:thread-id thread-id :run-id run-id
                                     :model     (:model provider)
                                     :provider  (:provider provider)})
              ;; SessionStart fires on a session's FIRST run -- beside the provider
              ;; init line, because both answer "what is this conversation, as it
              ;; begins". It is an observer: its verdict is discarded. Every start is
              ;; a "new" one today; the rebuild path (:source "resume") is a later
              ;; ticket's.
              (when (claim-once! session-started thread-id)
                (hook/emit :session-start {:source "new"}))
              ;; The provider timeline, part 1: ONE init line per session, on its
              ;; first run. It lands after the input line and before the first
              ;; message line, so a reader meets "here is what this conversation is
              ;; served by" before it meets the conversation. Later runs of the same
              ;; thread do not repeat it -- the timeline is init plus changes, not a
              ;; snapshot per run.
              (when (and (nil? (providers/pinned-provider thread-id))
                         (claim-once! init-logged thread-id))
                (log! thread-id run-id "provider/init"
                      (provider-line provider (:source resolved))))
              ;; The decision record: what the human answered, next to the input that
              ;; carried it. The same verdict also lands on the resumed call's
              ;; tools/pre-execute line, keyed by toolCallId -- this row is the one
              ;; that carries the interrupt id and the client's payload.
              (doseq [d decisions]
                (log! thread-id run-id "approval/decided" d))
              ;; The provider timeline, part 2: any change a tool made during this
              ;; run, drained from the outbox. It lands after approval/decided
              ;; because the change is only written once the human's approval has
              ;; been consumed -- so the two lines read together as "approved, and
              ;; here is what it changed".
              ;;
              ;; A slice is the SELECTION, not the resolved endpoint: :before/:after
              ;; are what the change moved (a session can only move a knob), and
              ;; :override is the session's whole tier afterwards. The endpoint that
              ;; resulted is on :resolved, so a reader stepping the timeline sees
              ;; both "what was chosen" and "what that meant" at each step.
              (doseq [c (providers/take-provider-changes! thread-id)]
                (log! thread-id run-id "provider/changed"
                      {:verdict  (:verdict c :approved)
                       :before   (providers/wire (:before c)   providers/knobs)
                       :after    (providers/wire (:after c)    providers/knobs)
                       :trigger  (:trigger c)
                       :override (providers/wire (:override c) providers/knobs)
                       :resolved (providers/wire (:resolved c))}))
              ;; The message record, submitted side: what the first LLM call is about
              ;; to see. The ASSEMBLED system message -- prompt.md's frozen opening
              ;; with each SystemPrompt hook's text behind it -- plus every inbound
              ;; message in the provider's shape, one line each, VERBATIM. THE SESSION'S
              ;; OWN INJECTIONS ARE PART OF IT -- the skill bodies a `/name` in this
              ;; history asks for were folded in above, because that is what the first
              ;; LLM call is about to see. Context rides as a trailing user message --
              ;; it must never touch the system prompt, or the provider's prefill
              ;; (prompt cache) would miss every call. The appended text has no other
              ;; trace: it is server-side, it never becomes a frame, and this line is
              ;; where its weight is on the record. (The hook/SystemPrompt line records
              ;; the same run of it.)
              (log-messages! thread-id run-id messages)
              ;; Drain run-chan and convert each kernel event to AG-UI frames. The
              ;; stream closes via :run/end's RUN_FINISHED (or RUN_ERROR), or via
              ;; :run/interrupt's RUN_FINISHED carrying outcome.interrupts; the
              ;; :run/done history itself is never converted -- it is the returned
              ;; side of the message record instead.
              (let [events (loop/run-chan provider messages {:thread-id thread-id
                                                             :resume decisions
                                                             ;; The session's skill bodies
                                                             ;; go back in before every
                                                             ;; LLM call. Both edges pass
                                                             ;; the SAME function, so a
                                                             ;; rebuilt conversation carries
                                                             ;; what a live one did.
                                                             :before-llm project/before-llm})]
                (loop []
                  (when-let [ev (async/<! events)]
                    (if (= :run/done (:type ev))
                      (do
                        ;; What happened to this run's MCP servers, drained from the
                        ;; mcp outbox. IT LANDS AT THE END because that is when the
                        ;; fact exists: a server is connected on the way to this run's
                        ;; first LLM call (the seam asks for the roster), so at the
                        ;; provider/changed drain above nothing has happened yet. A run
                        ;; that never reached the provider leaves the line for the next.
                        (doseq [e (cap-mcp/take-events!)]
                          (log! thread-id run-id "mcp/server" e))
                      ;; Returned side of the message record: every message the kernel
                      ;; appended after the vector it was handed -- assistant replies
                      ;; VERBATIM (the history holds the provider message unrebuilt,
                      ;; reasoning and tool calls intact) and each tool result as the
                      ;; tool message submitted on the next call. THE COUNT IS TAKEN
                      ;; AGAINST THAT SAME VECTOR -- the one logged a few lines up -- and
                      ;; that is what keeps the halves from overlapping: the history
                      ;; STARTS as exactly the messages on the record, so everything
                      ;; past their count is the kernel's own. :run/done follows
                      ;; RUN_ERROR too, so any run the kernel started leaves its full
                      ;; message tail on disk -- but it lands one beat AFTER the
                      ;; terminal frame, so a reader racing the consumer may not see
                      ;; it yet.
                      (log-messages! thread-id run-id
                                     (subvec (:history ev) (count messages))))
                      (do ;; Tool-lifecycle events are audit lines, not wire frames:
                          ;; each lands as its own jsonl line, keyed by toolCallId.
                          (when-let [[kind payload] (lifecycle-record ev)]
                            (log! thread-id run-id kind payload)
                            ;; WHAT THE RUN IS DOING, KEPT FOR THE WAY OUT. Only the
                            ;; close handler and the drop warning read it, and both
                            ;; are read when the run is over -- a run that stops
                            ;; mid-flight and leaves no statement of where it stopped
                            ;; is the exact hole this fills.
                            (swap! state assoc :last (str kind " " (:toolName payload)))
                            ;; A CALL THAT DID NOT RUN GETS A LINE, and only such a
                            ;; call does: :pass is every ordinary call, and a line per
                            ;; ordinary call is noise to scroll past. The rest are
                            ;; decisions somebody made or a gate that fired -- the
                            ;; answer to 'why didn't my tool run', which otherwise
                            ;; lives only in the thread's own jsonl.
                            (when-let [outcome (:outcome payload)]
                              (log/info! :run/tool-not-run
                                         {:thread-id thread-id :run-id run-id
                                          :tool      (:toolName payload)
                                          :outcome   outcome})))
                          (doseq [frame (convert ev)] (emit frame))
                          (recur)))))
                ;; THE CHANNEL CLOSED, AND THIS IS WHERE A RUN SAYS WHETHER IT GOT
                ;; TO SAY GOODBYE. The kernel closes it after :run/done, so every
                ;; normal ending emits a terminal frame first and lands here quiet.
                ;; A silent arrival means the run stopped without one -- a crashed
                ;; consumer, a tool thread that died holding a result, a channel
                ;; closed from underneath -- and the record would otherwise end
                ;; mid-sentence with nothing anywhere saying so.
                (when-not (:terminal @state)
                  (log/warn! :run/events-closed-without-terminal
                             {:thread-id thread-id :run-id run-id
                              :last      (:last @state)}))))))
      (catch Throwable t
        (log/error! :run/crashed t {:thread-id thread-id :run-id run-id
                                    :last      (:last @state)})
        ;; THE STREAM IS ENDED RATHER THAN LEFT OPEN: a client parked on a run
        ;; that will never send another frame has nothing to look at and nothing
        ;; to report, which is the state this whole wrapper exists to shorten.
        (try (hk/close ch) (catch Throwable _ nil)))))))


(defn- answer-of
  "The last thing a subagent SAID, out of the history its run produced. Walks back
  past the rounds that only called tools -- an assistant message whose content is
  empty because the whole round was tool calls is a step, not an answer -- so what
  goes back to the delegating model is the subagent's conclusion rather than
  whatever happened to be on the wire last.

  A history with nothing to say answers the sentence below rather than an empty
  string, because those are different facts and only one of them is true: '' reads
  as a subagent that chose to say nothing, which is not the same as one that never
  got to say anything."
  [history]
  (or (->> history
           (filter #(= "assistant" (:role %)))
           (map #(str/trim (str (:content %))))
           (remove str/blank?)
           last)
      (str "the subagent's run produced no answer -- it stopped before it said"
           " anything. Treat the task as not done.")))

(defn- run-subagent!
  "The `run` door harness.cap.subagents installs: ONE delegation, as a whole
  conversation on THREAD-ID -- its own provider request, its own tool calls, its own
  record.

  IT LIVES HERE BECAUSE RUNNING A CONVERSATION IS THE EDGE'S. The provider, the
  jsonl writer and the hook sink are this namespace's, and a second implementation of
  them inside the capability is how the two would drift.

  NO FRAMES ARE SENT, AND THE FRAMES ARE STILL WRITTEN DOWN. Nothing is watching
  this run: its entire output is one string, which is the result of the tool call
  that asked for it, so there is no emitter, no client and nobody to fail to reach.
  What IS built is the same AG-UI conversion a client's run gets, and every frame it
  produces lands in the subagent's jsonl as an `event` line -- because the record's
  SHAPE is what makes it readable, and both readers of a conversation (rebuild, and
  replay/history) fold those lines and nothing else. A record that held only
  `message` lines would rebuild as a conversation with one turn in it.

  THE HOOK SINK IS REBOUND AROUND IT, and to the SUBAGENT's id: same reason the agent
  route binds one at all (a hook whose verdict nobody records changes a run
  silently), and so a hook fired inside a subagent's call lands in that subagent's
  record rather than being filed under the conversation that delegated.

  SYNCHRONOUS ON PURPOSE. The delegating call IS a tool call on the parent's run
  thread, and its result is this function's return value -- there is nothing for that
  thread to do until the answer exists, so it waits here rather than parking a
  callback the parent's run would have to be joined back together from later. Two
  delegations in one turn still run at the same time, because they are two tool calls
  on two threads.

  THE PROVIDER IS THE PARENT'S, RESOLVED NOW. A subagent has no session of its own to
  resolve from, and re-resolving from the default tier would quietly move it to a
  different model than the conversation that delegated to it is holding."
  [{:keys [parent-thread-id thread-id definition task]}]
  (let [run-id   (str (java.util.UUID/randomUUID))
        provider (providers/current-provider parent-thread-id)
        ;; ONE converter per run, like the agent route's: it owns the open-message
        ;; state machine, so building it per event would restart every message id.
        convert  (ag/outbound thread-id run-id)]
    (log! thread-id run-id "input"
          {:threadId    thread-id
           :runId       run-id
           :delegatedBy parent-thread-id
           :subagent    (:name definition)
           :messages    [{:role "user" :content task}]})
    (binding [hook/*sink* {:thread-id thread-id
                           :audit     (fn [payload]
                                        (log! thread-id run-id
                                              (str "hook/" (:point payload))
                                              (dissoc payload :point)))
                           :run-id    run-id}]
      ;; INSIDE THE BINDING, both of them: the system message fires SystemPrompt and
      ;; the opening blocks fire InstructionsLoaded, and a point fired with no sink
      ;; does not dispatch at all. Same order as the agent route, for the same reason.
      (let [messages (llm/thinking-mode-history
                      (project/before-llm
                       (ag/inbound [{:role "user" :content task}]
                                   (system-prompt/assemble thread-id)
                                   (opening-blocks! thread-id)
                                   nil)
                       thread-id)
                      provider)]
        (log! thread-id run-id "provider/init" (provider-line provider :inherited))
        (log-messages! thread-id run-id messages)
        (let [events (loop/run-chan provider messages {:thread-id  thread-id
                                                       :resume     []
                                                       :before-llm project/before-llm})]
          (loop []
            (if-let [ev (async/<!! events)]
              (if (= :run/done (:type ev))
                (do
                  ;; The returned side of the message record, counted against the
                  ;; vector logged above -- the same boundary the agent route keeps,
                  ;; for the same reason: everything past that count is the kernel's
                  ;; own.
                  (log-messages! thread-id run-id (subvec (:history ev) (count messages)))
                  {:answer (answer-of (:history ev))})
                (do
                  ;; THE FRAMES GO ON THE RECORD, and nowhere else -- see the
                  ;; docstring: they are what makes this conversation rebuildable by
                  ;; the same reader every other conversation is. The terminal event
                  ;; is NOT converted, exactly as the agent route does not convert it:
                  ;; its frame is the one the run ends on, and it arrives from
                  ;; :run/end -- a converter handed :run/done has no clause for it.
                  (doseq [frame (convert ev)]
                    (log! thread-id run-id "event" frame))
                  (when-let [[kind payload] (lifecycle-record ev)]
                    (log! thread-id run-id kind payload))
                  (recur)))
              {:answer (answer-of [])})))))))

(defn- handle-run [req]
  (let [input     (json/read-str (slurp (:body req) :encoding "UTF-8") :key-fn keyword)
        thread-id (str (:threadId input))
        run-id    (str (:runId input))
        ;; WHAT PAGE IS ASKING, read HERE because this is the one route whose
        ;; headers do not come from the ring response: they ride on the first
        ;; frame. `handler` reads the same header for every other route.
        origin    (request-origin req)
        ;; THE RUN'S LIVE STATE, and the only thing the emitter and the close
        ;; handler share. `:on-open` and `:on-close` are two callbacks on
        ;; different threads with nothing else in common, so a fact one of them
        ;; knows and the other must report lives here.
        state     (atom {:terminal nil :last nil})]
    ;; as-channel wants no status or headers of its own. run-agent! returns immediately
    ;; -- the run is driven by a go loop draining the core.async channel -- so it does
    ;; not block the worker that :on-open runs on.
    ;;
    ;; ONE LINE PER STREAM END, SAYING WHETHER THE RUN SAID GOODBYE. Paired with
    ;; `run/start`, that is what a reader needs to tell a finished run from one
    ;; that stopped mid-flight, and the 'last' names where it stopped.
    ;;
    ;; WHAT IT CANNOT SAY IS WHO LEFT. A browser that aborts its fetch does not
    ;; become visible here: an aborted fetch, a stopped tab and a live tab that
    ;; simply stopped being sent anything all look the same from this side, and
    ;; the client-side wording the browser puts on a run it cut off is its own
    ;; ('BodyStreamBuffer was aborted'). What happens in the process is
    ;; unambiguous either way -- NOTHING cancels a run when a client goes, so the
    ;; run keeps running and its record keeps growing -- and that asymmetry is
    ;; what makes the two failures tellable apart afterwards: a record that ends
    ;; mid-tool with no terminal frame, beside a `run/start` and no `:shutdown`,
    ;; is a run still going; beside a `:shutdown`, it is a process that was stopped.
    (hk/as-channel req
                   {:on-open  (fn [ch] (run-agent! ch state input origin))
                    :on-close (fn [_ch status]
                                (let [{:keys [terminal last]} @state]
                                  (if terminal
                                    (log/info! :run/stream-closed
                                               {:thread-id thread-id :run-id run-id
                                                :status    status
                                                :terminal  terminal})
                                    ;; NOT OBSERVED YET, AND KEPT ANYWAY: http-kit
                                    ;; reports `:server-close` even for a peer that
                                    ;; has reset the connection (measured), so this
                                    ;; branch is the one place a client-side close
                                    ;; WOULD show up if the server ever starts
                                    ;; hearing about one. Silent when it fires is
                                    ;; how a lost stream stays unexplained.
                                    (log/warn! :run/stream-closed-before-terminal
                                               {:thread-id thread-id :run-id run-id
                                                :status    status
                                                :last      last}))))})))

;; ----------------------------------------------------- the management edge
;;
;; Plain request/response JSON, alongside the streaming AG-UI edge. Small on
;; purpose: each endpoint is a thin wrapper over one harness namespace call.
;; Responses are UTF-8 BYTES, like every other body this server writes -- the
;; JVM default charset is GBK here.

(defn- api-response
  "One JSON answer. NO CORS HEADERS HERE: which origin an answer may name is a
  fact about the REQUEST, and this function is handed a status and a body -- it
  has ninety-odd call sites and none of them knows what page is asking. `handler`
  merges them at the one exit instead, which is also where a reader should look."
  [status body]
  {:status  status
   :headers {"Content-Type" "application/json; charset=utf-8"}
   :body    (.getBytes (json/write-str body) StandardCharsets/UTF_8)})

(defn- query-params
  "A request's raw query string -> a {name value} map. Hand-rolled because the
  edge runs without ring middleware, and one parameter does not justify the
  dependency's weight."
  [^String qs]
  (into {}
        (for [kv (str/split (or qs "") #"&")
              :when (seq kv)
              :let [[k v] (str/split kv #"=" 2)]]
          [(java.net.URLDecoder/decode k "UTF-8")
           (java.net.URLDecoder/decode (or v "") "UTF-8")])))

;; ------------------------------------------------- the native folder dialog
;;
;; The browser cannot hand us an absolute path -- a web file input gives a File
;; object with no real location -- so the dialog runs where the process lives,
;; drawn by the platform that owns the window. Which platform that is decides
;; WHICH dialog: this machine either has one or it does not, and the two facts
;; a caller must be able to tell apart are "the human said no" and "there was
;; no dialog to say no to". Cancelled is silence; unavailable is an answer.

(def ^:dynamic *dialog-launcher*
  "The process that draws a dialog, as a seam: ARGV in, {:out <text> :exit <int>}
  out, or a throw when the process could not be started at all. Tests rebind
  this and answer without a process; the real one starts one and then WAITS
  FOR A HUMAN, which no test run may do.

  Bytes become a String as UTF-8 and only UTF-8: this JVM's default is GBK on
  Chinese Windows, and a directory's name is not obliged to be ASCII."
  (fn [argv]
    (let [proc (-> (ProcessBuilder. ^"[Ljava.lang.String;" (into-array String argv))
                   (.redirectErrorStream true)
                   (.start))
          out  (String. (.readAllBytes (.getInputStream proc)) StandardCharsets/UTF_8)
          code (.waitFor proc)]
      {:out out :exit code})))

(def ^:private bom
  "The UTF-8 byte-order mark, as a string so that it can be removed LITERALLY:
  clojure.string treats a string `match` as text, not as a pattern, so an
  anchored \"^\\uFEFF\" would look for a caret that is not there. A Windows
  console writes one even when it was told not to, and PowerShell is no
  exception -- so it comes off here rather than being trusted downstream."
  (str (char 0xFEFF)))

(defn- chosen-path
  "A dialog's output as the path it named. Two things come off it and nothing
  else: a UTF-8 BOM, and the newline the process added. A directory with a
  space in it keeps its space, and a Chinese name keeps its characters."
  [out]
  (-> (str out)
      (str/replace-first bom "")
      str/trim))

(def ^:private unavailable-reason
  "The folder dialog could not be opened on this machine -- type the directory
   path instead.")

(defn- osascript-directory!
  "macOS's native folder dialog, as a CHOICE (see `pick-choice`). Drawn by
  osascript, which owns its own window and so does not have to borrow the
  human's focus from whatever they are typing in.

  osascript answers a nonzero exit for Cancel, which is why a nonzero here is
  a cancellation and not a failure: a person dismissing the window is not an
  error. A process that could not be STARTED -- no osascript on this machine,
  which is exactly what happens when some other platform lands here -- is the
  other thing, and it says so."
  []
  (try
    (let [{:keys [out exit]} (*dialog-launcher*
                              ["osascript" "-e"
                               "POSIX path of (choose folder with prompt \"选择一个项目目录 -- select a project directory\")"])
          path               (chosen-path out)]
      (cond
        (not (zero? exit)) {:status :cancelled}
        (str/blank? path)  {:status :cancelled}
        :else              {:status :picked :dir path}))
    (catch Throwable _ {:status :unavailable :reason unavailable-reason})))

(def ^:private powershell-script
  "Windows' native folder dialog, as one PowerShell command. WinForms'
  FolderBrowserDialog, because it is the window a Windows person recognises as
  their own; `-STA` because WinForms insists on a single-threaded apartment;
  and the OutputEncoding line because PowerShell would otherwise write the
  path in this console's code page -- GBK here -- and a directory whose name
  is not ASCII would come back as noise.

  A dialog that cannot be shown (no desktop session to draw into, most often)
  throws, and the command says which by EXITING 2: an empty output has to keep
  meaning one thing only, which is that the human pressed Cancel."
  (str "[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false); "
       "Add-Type -AssemblyName System.Windows.Forms | Out-Null; "
       "$d = [System.Windows.Forms.FolderBrowserDialog]::new(); "
       "$d.Description = '选择一个项目目录 -- select a project directory'; "
       "$d.ShowNewFolderButton = $true; "
       "try { if ($d.ShowDialog() -eq [System.Windows.Forms.DialogResult]::OK) "
       "{ [Console]::Out.Write($d.SelectedPath); [Console]::Out.Flush() } } "
       "catch { exit 2 }; "
       "exit 0"))

(defn- powershell-directory!
  "Windows' native folder dialog, as a CHOICE. `powershell.exe` first, `pwsh`
  second: either may be the one this machine has, and a missing executable is
  not an answer -- it is an absence, so the next name is tried. Only when
  neither is here does this say unavailable."
  []
  (let [attempt (fn [exe]
                  (try
                    (let [{:keys [out exit]} (*dialog-launcher*
                                              [exe "-NoProfile" "-STA" "-Command" powershell-script])
                          path               (chosen-path out)]
                      (if (zero? exit)
                        (if (str/blank? path)
                          {:status :cancelled}
                          {:status :picked :dir path})
                        {:status :unavailable :reason unavailable-reason}))
                    ;; Not here -- try the next name. An IOException from
                    ;; starting a process is "no such program", not "the
                    ;; dialog failed".
                    (catch java.io.IOException _ nil)
                    (catch Throwable _ {:status :unavailable :reason unavailable-reason})))]
    (or (attempt "powershell.exe")
        (attempt "pwsh.exe")
        {:status :unavailable :reason unavailable-reason})))

(defn- this-platform
  "Where this JVM is running, as one of three keywords. `os.name` is read
  rather than a constant because the answer is a fact about the machine, and
  three keywords is as fine as this ever needs to be: the question is only
  ever 'which dialog does this platform have'."
  []
  (let [os-name (str/lower-case (System/getProperty "os.name" ""))]
    (cond
      (str/includes? os-name "win") :windows
      (str/includes? os-name "mac") :macos
      :else                         :other)))

(defn- chooser-for
  "The dialog PLATFORM has, or nil where it has none. Nil is a real answer and
  not a fallback: a platform with no dialog says so out loud rather than
  quietly landing on some other platform's command and failing there."
  [platform]
  (case platform
    :windows powershell-directory!
    :macos   osascript-directory!
    nil))

(defn- platform-directory!
  "The default chooser: this platform's dialog, or an explicit 'none here'."
  []
  (if-some [choose (chooser-for (this-platform))]
    (choose)
    {:status :unavailable :reason unavailable-reason}))

(def ^:dynamic *directory-chooser*
  "The picker itself, as a seam. Tests BIND this to a stub: the real one opens
  a window and waits for a human, which no test run may do. Production leaves
  it at the real dialog -- a var holding the default, exactly like
  harness.infra.home/*root-override* is a var holding a test override."
  platform-directory!)

(defn- pick-choice
  "A chooser's answer, in the ONE shape the endpoint reads:

    {:status :picked :dir \"<path>\"}   -- a directory was chosen
    {:status :cancelled}                -- the human dismissed the window
    {:status :unavailable :reason \"..\"} -- there was no window to dismiss

  THREE, and not two, because collapsing the last into the middle is the bug
  this whole edge shipped with: a machine that could not open a dialog
  answered `nil`, which reads as a cancellation, and the human saw nothing at
  all happen. Unavailable is therefore never an alias for nil, and a bare
  value from a chooser (a stub's string, say) is read the obvious way -- a
  path is picked, blank or nothing is cancelled -- which is why the older
  stubs still mean what they always meant."
  [answer]
  (cond
    (and (map? answer) (#{:picked :cancelled :unavailable} (:status answer)))
    answer

    ;; A map we do not recognise is read the same way a bare value is: a path
    ;; is picked, anything else is a cancellation. Never `:unavailable` -- that
    ;; one has to be said, not inferred.
    (map? answer)
    (if (str/blank? (str (:dir answer)))
      {:status :cancelled}
      {:status :picked :dir (str (:dir answer))})

    (str/blank? (str answer))
    {:status :cancelled}

    :else
    {:status :picked :dir (str answer)}))

(defn- project-pick
  "POST /api/project/pick -- open the native folder dialog and answer the
  chosen absolute path, {:dir nil} when the human cancels, or a 501 carrying
  the reason when this machine has no dialog to open. A question asked with
  POST because the call has a side effect the human sees: a window opens,
  which is not something a cache or a prefetch may trigger.

  Nothing is bound here. The client takes the path, shows it, and binds it
  through the ordinary /api/project POST -- so there is exactly ONE route that
  mutates a binding, and picking a folder leaves no trace of its own."
  [_req]
  (let [{:keys [status dir reason]} (pick-choice (*directory-chooser*))]
    (case status
      :picked      (api-response 200 {:dir dir})
      :cancelled   (api-response 200 {:dir nil})
      :unavailable (api-response 501 {:error reason}))))

(defn- project-get
  "GET /api/project?threadId=.. -- the thread's bound project directory, or
  {:dir nil} for an unbound thread. Unbound is an answer, not an error."
  [req]
  (let [thread-id (get (query-params (:query-string req)) "threadId")]
    (if (str/blank? thread-id)
      (api-response 400 {:error "missing threadId query parameter"})
      (api-response 200 {:threadId thread-id
                         :dir      (project/binding-for thread-id)}))))

(defn- project-post
  "POST /api/project {threadId, dir} -- bind the thread to the directory,
  validate FIRST (a missing or non-directory path is a named 400 and leaves
  no trace), then land the project/bound audit line as before -> after, the
  provider/changed style: the previous binding (nil for a first bind) and the
  one now stored, so a session's directory timeline is readable off the log.
  runId is nil on that line because a binding happens OUTSIDE any run. The
  previous binding is read BEFORE binding: bind! overwrites, and the audit
  line is the only place the old value would survive.

  THE LOG TRAVELS WITH THE BINDING. Which workspace a session's log belongs in
  is decided by its project, so a rebind that did not carry the file would split
  one conversation across two directories -- and replay, rebuild and the eval
  reader each reconstruct a conversation from a single file. The before-
  directory is therefore read while the OLD binding is still in force; after a
  successful bind the file is moved. A move that cannot happen (the session
  already has a log where it is going) is refused by name, and the binding is NOT
  rolled back: the store and the tree would then disagree about where the session
  lives, and the human's task is the same in either case -- decide which log is
  the conversation. The 400 says so, and the response carries the directory the
  store actually holds so the client is not left guessing which of the two it
  ended up with.

  ORDER MATTERS, AND THE AUDIT LINE GOES LAST. The move happens before the line is
  written for two reasons: the line must land in the workspace the session has
  just moved to, so the whole timeline stays in one file; and a REFUSED move must
  leave no line at all -- writing one first would append it to the very file the
  refusal just declared ambiguous, corrupting a record the refusal exists to
  protect. So a refused move is traced by its 400 and by nothing on disk, which
  is the same 'no trace on failure' the validation above already promises."
  [req]
  (let [parsed (try {:ok (json/read-str (slurp (:body req) :encoding "UTF-8")
                                        :key-fn keyword)}
                     (catch Throwable _ {:bad true}))
        {:keys [ok bad]} parsed]
    (cond
      bad
      (api-response 400 {:error "request body is not valid JSON"})

      (str/blank? (str (:threadId ok)))
      (api-response 400 {:error "missing threadId"})

      (str/blank? (str (:dir ok)))
      (api-response 400 {:error "missing dir"})

      :else
      (let [thread-id (str (:threadId ok))
            before    (project/binding-for thread-id)
            from-dir  (log-dir-for thread-id)
            bound     (try {:ok (project/bind! thread-id (str (:dir ok)))}
                           (catch Throwable t {:error (ex-message t)}))]
        (if-some [error (:error bound)]
          (api-response 400 {:error error})
          (let [abs   (:ok bound)
                moved (try {:ok (move-log! thread-id from-dir (log-dir-for thread-id))}
                           (catch Throwable t {:error (ex-message t)}))]
            (if-some [move-error (:error moved)]
              (api-response 400 {:error move-error :threadId thread-id :dir abs})
              (do (log! thread-id nil "project/bound" {:before before :after abs :via "http"})
                  (api-response 200 {:threadId thread-id :dir abs})))))))))

(defn- threads-get
  "GET /api/threads -- the conversations the projects tree holds, newest first.
  The listing is a DIRECTORY SCAN of files, so it knows nothing about whether a
  conversation is complete; a truncated one is refused at rebuild time, not
  listed differently here.

  One row per FILE, and the id is that file's stem. Two files in DIFFERENT
  workspaces can share a stem, and the ordinary cause is a bind whose log move
  was REFUSED (the session returned to a project whose workspace still held its
  earlier log): the store then points at the new project while the file stayed
  behind, so the next run starts a second file. The listing therefore speaks
  filenames, and the rebuild route refuses a stem that resolves to more than one
  (see replay/locate)."
  [_req]
  (api-response 200
                (mapv (fn [t] {:threadId     (:thread-id t)
                               :lastActivity (:last-activity t)
                               :bytes        (:bytes t)})
                      (replay/threads (home/projects-dir)))))

(defn- session-row
  "One session as the SIDEBAR reads it: what the store owns (its id, its archive
  flag) plus what the tree says about its file.

  The store decides which sessions are listed -- not the filesystem -- and that
  is the whole point of the sidebar: a session belongs to a project because
  something asked for it to, and an unowned jsonl sitting in the tree is not a
  session this interface will show. It is also why a session with NO file is a
  row rather than a problem: a session that has never run has no log yet, and one
  just created on the sidebar has not run by definition. Its two disk facts are
  null, which is a fact about the disk and not a zero-byte file.

  The two facts are read FRESH from the file every time rather than kept in the
  store, because they are the two things the store deliberately does not hold: a
  size and an mtime are properties of a record, and the record lives in the file."
  [workspace {:keys [id archived?]}]
  (let [f (home/log-file workspace id)
        exists? (.exists f)]
    {:threadId     id
     :archived     (boolean archived?)
     :lastActivity (when exists? (.lastModified f))
     :bytes        (when exists? (.length f))}))

(defn- newest-first
  "The sidebar's order within a project: most recent activity first, and a session
  that has never run treated as the most recent of all -- it was created a moment
  ago, and 'no log' is the state every session begins in, so burying those at the
  bottom would hide the one a person just asked for.

  `sort` with a reversed comparator, and Clojure's sort is STABLE, so sessions
  that tie keep the store's order (oldest created first)."
  [rows]
  (let [key-of (fn [row] (or (:lastActivity row) Long/MAX_VALUE))]
    (vec (sort (fn [a b] (compare (key-of b) (key-of a))) rows))))

(defn- projects-get
  "GET /api/projects -- the sidebar's listing: every project this home knows, each
  with its sessions.

  TWO SOURCES, ONE ANSWER, AND THAT IS THE POINT OF THE ENDPOINT. The store says
  which projects and sessions exist, which session belongs where and which are
  archived; the tree says how big each log is and when it last changed. Neither
  question can be answered from the other side alone -- a directory of jsonl files
  cannot say which project a conversation belongs to (that was the whole reason
  not to migrate the old logs/), and the store must not mirror file sizes. So the
  two are joined here, on the reading side, and each field comes from its owner.

  Archived sessions are INCLUDED and flagged, not filtered: which group to draw
  them in is a decision for the screen, and a listing that quietly dropped them
  would make 'where did my session go' a question with no server-side answer.
  Unbound sessions are NOT included -- they belong to no project, so they have no
  row here; GET /api/threads is the raw tree view for anyone diagnosing.

  A SUBAGENT'S SESSION IS NOT LISTED, and this is the read that says so. Every row
  here is a conversation a person can open, continue and type into; a subagent's is
  none of those -- it belongs to the call that delegated to it, it ran once, and a
  row offering to open it would be inviting somebody to talk to something that
  cannot answer. It still HAS a store row and still appears in the tree, which is
  what lets rebuild, trajectory and the subagent panel find it by id."
  [_req]
  (let [by-project (group-by :project-id (remove :subagent (project/sessions)))]
    (api-response 200
                  (mapv (fn [{:keys [id canonical-path]}]
                          (let [ws (workspace-for canonical-path)]
                            {:projectId id
                             :path      canonical-path
                             :sessions  (newest-first
                                         (mapv #(session-row ws %)
                                               (get by-project id)))}))
                        (project/projects)))))

(defn- subagent-run-row
  "One delegation, as a panel reads it: which subagent ran, whose session
  delegated it, where its record went, when it started, and whether it is running
  NOW.

  `running` IS THE ONE FIELD THAT IS NOT THE STORE'S, and it is here rather than
  derived client-side because the client cannot see this process's live table --
  the same reason `GET /api/settings` resolves rather than handing over the files.
  It is FALSE for a delegation that finished and for one left by a previous
  process, and those read the same on screen because they ARE the same fact about
  now: nothing is running under that id.

  `delegatedAt` is the store row's creation time, which for a subagent's session is
  the moment its delegation opened it -- there is no earlier fact about it."
  [row]
  {:threadId    (:thread-id row)
   :parent      (:parent row)
   :subagent    (:subagent row)
   :project     (:project row)
   :delegatedAt (:delegated-at row)
   :running     (:running row)})

(defn- subagents-get
  "GET /api/subagents -- the subagent panel's whole answer, and the settings page's
  too: what this home's subagents ARE, and which delegations it has a record of.

    {:subagents [{:name .. :description .. :baseline .. :exclude [..] :builtin <bool>}]
     :problem   <string|nil>   ; the first thing the file said that could not be honoured
     :path      <the user-level harness.edn>
     :runs      [{:threadId .. :parent .. :subagent .. :project .. :delegatedAt .. :running}]}

  TWO SOURCES, ONE ANSWER, and the split is the one the whole feature is built on:
  the DEFINITIONS come from harness.edn (read fresh -- a save is in force on the
  next delegation with no restart), while the RUNS come from the store plus this
  process's live table. Neither can be derived from the other -- a subagent that
  has never run has no row, and a record whose definition was since deleted has no
  definition -- so the join happens here, on the reading side, exactly as the
  sidebar's listing joins the store to the log tree.

  THE `:problem` IS PART OF THE ANSWER, NOT A FAILURE. A harness.edn with a typo in
  its :subagents block leaves a harness that still runs (see the reader's own
  tolerance), so this route stays a 200 and hands the sentence over for the screen
  to show. A HOME whose file cannot be parsed at all is the other case and refuses
  by name, from the reader, with the path in it.

  READ-ONLY, and therefore no audit line -- the same rule every other GET here
  follows. Asking what a home's subagents are is not part of the record of a
  delegation."
  [_req]
  (let [defs (subagents/definitions)]
    (api-response 200 {:subagents (mapv subagents/wire-definition (:subagents defs))
                       :problem   (:problem defs)
                       :path      (:path defs)
                       :runs      (mapv subagent-run-row (subagents/runs))})))

(defn- subagents-post
  "POST /api/subagents {name, description, baseline, exclude, replace} -- create or
  replace ONE definition in the home's harness.edn, and answer it as the panel
  reads it.

  `replace` IS WHAT SEPARATES EDITING FROM ADDING, and it is the request's own
  statement about which screen sent it: the form's edit view says true (it is
  changing a row it is showing), the new-subagent view says false, and a false
  whose name is already taken is refused rather than quietly obeyed. Without it
  'add a subagent called explore' would silently rewrite the built-in -- a change
  nobody asked for, delivered by a screen that said it was adding something. It
  defaults to false, so a caller that has not thought about it gets the refusal
  rather than the overwrite.

  THE BASELINE ARRIVES AS A STRING (`\"all\"`, `\"read-only\"`), because that is what
  JSON has; harness.cap.subagents/entry-from-wire turns it into the keyword the file
  holds and leaves anything else for the validator to refuse BY NAME. Nothing here
  re-implements a definition check: every refusal a person reads is that
  namespace's sentence.

  NOTHING IS WRITTEN UNTIL EVERY CHECK HAS PASSED, so the refusal a form shows is
  also the proof that the home did not move -- which is what the screen promises
  when it keeps the form open."
  [req]
  (let [parsed (try {:ok (json/read-str (slurp (:body req) :encoding "UTF-8")
                                        :key-fn keyword)}
                    (catch Throwable _ {:bad true}))
        {:keys [ok bad]} parsed]
    (cond
      bad
      (api-response 400 {:error "request body is not valid JSON"})

      :else
      (let [allowed #{:name :description :baseline :exclude :replace}
            extras  (sort (map name (remove allowed (keys ok))))]
        (cond
          (seq extras)
          (api-response 400 {:error (str "does not understand " (pr-str (vec extras))
                                         "; it takes name, description, baseline, exclude"
                                         " and replace")})

          (not (contains? ok :name))
          (api-response 400 {:error "missing name"})

          (and (contains? ok :replace) (not (boolean? (:replace ok))))
          (api-response 400 {:error "replace must be true or false"})

          :else
          (let [answer (try {:ok (subagents/put-definition! (:name ok)
                                                             (dissoc ok :name :replace)
                                                             (true? (:replace ok)))}
                            (catch Throwable t {:error (ex-message t)}))]
            (if-some [error (:error answer)]
              (api-response 400 {:error error})
              (api-response 200 (subagents/wire-definition (:ok answer))))))))))

(defn- subagents-remove-post
  "POST /api/subagents/<name>/remove -- take ONE definition out of the home's
  harness.edn.

  THE NAME IS THE STEM, like every other verb-carrying route in this file, and it
  is decoded before it is used -- a subagent name is an ordinary word today, and a
  route that only worked for words somebody had ruled out would be a trap waiting
  for the first name with a space in it.

  BUILT-INS ARE REFUSED, by name and with the reason: they come from the code, so
  there is nothing in the file to remove, and a fresh home with no subagents would
  have no names to delegate to. The refusal points at the thing that CAN be done
  to one -- edit it, since an entry with its name replaces it.

  A 404 FOR A NAME THIS HOME DOES NOT HAVE, the same shape `remove-project-post`
  answers an unknown directory with: the panel's row is out of date, and guessing
  at which subagent it meant would delete the wrong one. A built-in is NOT a 404 --
  the name exists and the request was understood; what is refused is the act."
  [stem]
  (let [answer (try {:ok (subagents/remove-definition! stem)}
                    (catch Throwable t {:error (ex-message t) :data (ex-data t)}))]
    (if-some [error (:error answer)]
      (if (= :unknown-subagent (:reason (:data answer)))
        (api-response 404 {:error error :name stem})
        (api-response 400 {:error error :name stem}))
      (api-response 200 (subagents/wire-definition (:ok answer))))))

(def ^:private thread-verbs
  "The verbs this edge serves under /api/threads/<stem>/. A CLOSED SET, and that
  is load-bearing rather than tidiness: the handler dispatches on this shape
  BEFORE the run endpoint, so a path that merely looks like it -- and names a verb
  nobody serves -- has to fall through to the ordinary AG-UI handler rather than
  be answered 405 by a route that was never about it.

  ONE OF THE FOUR IS A GET: `stats` only reads the log, so it has no effect to
  report and nothing to add to it; `trajectory` is the second reader on the same
  terms. The set stays closed and the 405 stays here -- what changed is that the
  sentence 'every verb on this shape is a POST' is no longer true, not where the
  refusal happens."
  #{"rebuild" "archive" "stats" "trajectory"})

(def ^:private project-verbs
  "The verbs this edge serves under /api/projects/<stem>/. The other half of the
  pair above, and closed for the same reason -- these two namespaces are the two
  nouns the sidebar manages, and a path segment is not a good place to discover
  that."
  #{"remove"})

(def ^:private provider-verbs
  "The verbs this edge serves under /api/providers/<stem>/ -- the catalog's own
  noun, added because the settings form manages entries the same way the sidebar
  manages projects. Closed, like the other two: a path that names a verb nobody
  serves has to fall through to the run endpoint rather than be answered 405 by a
  route that was never about it.

  ONE VERB, and the other two actions are plain routes rather than verbs: creating
  and updating are the SAME act on one entry (the body carries the id), and it
  belongs on the collection."
  #{"remove"})

(def ^:private subagent-verbs
  "The verbs this edge serves under /api/subagents/<stem>/. The third collection of
  this shape, and closed for the same reason the other two are: a path segment is
  not a good place to discover that.

  ONE VERB, and the other two actions are plain routes rather than verbs -- the same
  arrangement the provider catalog uses, because a subagent is managed the same way
  an entry there is: creating and updating are one act on one entry (the body
  carries the name), so they belong on the collection, and only removal has a
  single row to point at."
  #{"remove"})

(defn- stem-verb-route
  "/api/<collection>/<stem>/<verb>, matched on: an exact segment count, the
  segment that names the collection, a verb in that collection's closed set, and
  a non-empty stem -- else nil.

  ONE MATCHER FOR BOTH COLLECTIONS, because the shape is the fiddly part and the
  collections differ in nothing else: a second copy would be a second chance to
  disagree about how many segments there are, where the stem sits, or whether
  decoding happens before or after the split. It happens AFTER -- an id containing
  an encoded slash is decoded into a path segment, never allowed to jump out of
  one.

  The stem is whatever the collection names its rows by: for a thread, the
  SESSION id, which is also its log's file stem and what /api/projects hands out;
  for a project, the DIRECTORY's canonical path, because that -- not the integer
  id -- is the project's identity everywhere else in this edge (see the listing's
  :path, and `workspace-for`, which is a function of it).

  A NON-EMPTY STEM is checked with `seq` rather than by comparing against the
  empty string: seq of an empty string is nil and so fails the `and`, which is the
  intended answer for a path like /api/threads//archive -- there is no session
  called nothing, and falling through to the run endpoint is how that spelling
  behaved before this route existed."
  [collection verbs uri]
  (let [parts (str/split (str uri) #"/")]
    (when (and (= 5 (count parts))
               (= "api" (nth parts 1))
               (= collection (nth parts 2))
               (contains? verbs (nth parts 4))
               (seq (nth parts 3)))
      {:verb (nth parts 4)
       :stem (java.net.URLDecoder/decode (nth parts 3) "UTF-8")})))

(defn- archive-post
  "POST /api/threads/<stem>/archive {archived: true|false} -- set one session's
  archive flag. Answers {:threadId .. :archived <bool>}.

  ONE ROUTE, BOTH DIRECTIONS, because archiving and unarchiving are one column
  write that differ in a boolean; two routes would be two chances for the two
  halves to drift apart. The path names the action, the body names the direction.

  NOTHING IS LOCATED AND NOTHING IS READ, which is the one place this route
  differs from its rebuild sibling in a way worth stating: a rebuild has to find
  the log because it reconstructs a conversation FROM it, while an archive is a
  rewrite of a row and never opens the file. The stem is the session's id, the
  store is the only authority on whether it exists, and a session whose log was
  moved or deleted by hand is still archiv-able -- deliberately, because the flag
  describes the conversation, not the file. That also means an archive works for
  a session that has never run and therefore has no file at all.

  NO AUDIT LINE, AND THAT IS THE POINT. Every other mutating route here writes one
  because it happens to a log; an archive must not, because it must leave the log
  BYTE-FOR-BYTE and mtime-for-mtime untouched, and the ticket's acceptance asserts
  exactly those two numbers. Writing a line would fail the assertion that proves
  archiving is not a deletion.

  An id this home has never seen is a NAMED 404: the sidebar needs a reason for
  the row the click landed on, and 'we archived it' about a session that does not
  exist here would be a lie the client cannot detect. The 400 is reserved for a
  body that is not valid JSON or that carries no boolean at all -- the difference
  between 'I cannot understand you' and 'that thing is not here'."
  [req stem]
  (let [parsed (try {:ok (json/read-str (slurp (:body req) :encoding "UTF-8")
                                        :key-fn keyword)}
                     (catch Throwable _ {:bad true}))
        {:keys [ok bad]} parsed
        archived (:archived ok)]
    (cond
      bad
      (api-response 400 {:error "request body is not valid JSON"})

      (not (boolean? archived))
      (api-response 400 {:error "archived must be true or false"})

      :else
      (let [written (try {:ok (project/archive! stem archived)}
                         (catch Throwable t {:error (ex-message t)}))]
        (if-some [error (:error written)]
          (api-response 404 {:error error :threadId stem})
          (api-response 200 {:threadId stem :archived (:ok written)}))))))

(defn- stats-get
  "GET /api/threads/<stem>/stats -- one session's numbers, folded from its RECORD
  (harness.edge.stats): turns, model calls, what those calls reported, how much of
  the prompt came from the vendor's cache, how fast the answers came out.

  IT READS THE LOG AND WRITES NOTHING, which is why it is the first GET on this
  shape: the other verbs here change something (a rebuild lands an audit line, an
  archive rewrites a row), and this one only answers. Asking it again is free and
  asking it mid-run is normal.

  THE STEM IS LOCATED THE SAME WAY THE REBUILD LOCATES IT -- `replay/locate`, the
  same naming rule and the same refusal -- so there is one addressing rule on this
  edge, not a second one invented for reading. 'Nothing found' is a 404 with the
  locator's own sentence; a log that cannot be read back is a 400, the same split
  the rebuild draws between 'not here' and 'here, and broken'.

  THE ANSWER IS THE WHOLE ANSWER: what the fold could not establish is ABSENT, not
  zero (see harness.edge.stats/records->stats). The client renders the gaps by
  leaving them out; it does not fill them in.

  AND IT CARRIES THE CONTEXT SECTION (harness.edge.context): how full the model's
  window is right now and what filled it. One question per fold, one read of the
  file -- the records are parsed once and both readers fold them -- so the strip
  under the composer and the ring beside the model cannot report two different
  moments of the same log. The composer's own contract (mount / session change /
  assistant message added / run over) is what asks, and asking once asks both."
  [stem]
  (let [located (try {:ok (replay/locate (home/projects-dir) stem)}
                     (catch Throwable t {:error (ex-message t)}))
        folded  (when (nil? (:error located))
                  (try (let [records (stats/read-records (:ok located))]
                         {:ok (assoc (stats/records->stats records)
                                     :context (context/records->context records))})
                       (catch Throwable t {:error (ex-message t)})))]
    (cond
      (some? (:error located))
      (api-response 404 {:error (:error located) :threadId stem})

      (some? (:error folded))
      (api-response 400 {:error (:error folded) :threadId stem})

      :else
      (api-response 200 (assoc (:ok folded) :threadId stem)))))

(defn- trajectory-get
  "GET /api/threads/<stem>/trajectory -- one session's turns as the MODEL saw them,
  folded from its RECORD (harness.edge.trajectory): the system message that was in
  force, the context spliced in beside it, every user message, and each tool call with
  its arguments and result.

  THE SECOND READ-ONLY VERB ON THIS SHAPE, for the same reason as `stats`: it answers
  and writes nothing, so asking it again is free and asking it mid-run is normal.

  IT READS A DIFFERENT HALF OF THE LOG THAN `stats` DOES, and that is why it exists
  rather than being a field on it: `stats` folds the audit lines into numbers and never
  looks at a message, while this folds the `message` lines and never adds anything up.
  Two questions, two readers, one file.

  LOCATION AND REFUSALS ARE THE SAME AS `stats`' -- `replay/locate`, 404 for 'not here'
  with the locator's own sentence, 400 for 'here, and unreadable'. A log whose last run
  has not finished is NEITHER: it is read, and the answer says so, because looking at a
  session while it runs is the ordinary case rather than an error."
  [stem]
  (let [located (try {:ok (replay/locate (home/projects-dir) stem)}
                     (catch Throwable t {:error (ex-message t)}))
        folded  (when (nil? (:error located))
                  (try {:ok (trajectory/log-trajectory (:ok located))}
                       (catch Throwable t {:error (ex-message t)})))]
    (cond
      (some? (:error located))
      (api-response 404 {:error (:error located) :threadId stem})

      (some? (:error folded))
      (api-response 400 {:error (:error folded) :threadId stem})

      :else
      (api-response 200 (assoc (:ok folded) :threadId stem)))))

(defn- close-off-open-run!
  "Close every run a log left open, so the conversation can be CONTINUED instead of
  being refused -- a vector of {:run-id .. :frames [type ..]}, one per closed run, or
  nil when the log already ends where a log should.

  THIS IS WHERE A TRUNCATED LOG STOPS BEING A DEAD END. A process killed between
  a run's last frame and its terminal one leaves a record that reads as half a
  conversation, and every reader of it -- the UI opening the thread, a future
  export, the eval reader -- was told to refuse it. The refusal is right about the
  FACTS and wrong about the OUTCOME: the conversation the user wants back is
  sitting right there, one terminal frame short of readable. So the run is closed
  at the moment somebody asks to continue it, and the record says who closed it
  and what was appended (`session/closed-off`). Until then nothing is touched --
  reading a truncated log still refuses, because a reader that silently folds
  half a run is the failure this whole contract exists to prevent.

  EVERY OPEN RUN, not just one. A thread can have several runs in flight at once
  (each POST is its own run, and the browser may have more than one), so a process
  killed mid-flight can leave more than one unclosed -- and a log is only readable
  once all of them have ended. Each closure is one run's frames, written under that
  run's id, with its own `session/closed-off` line.

  THE AUDIT LINE LANDS BEFORE THE FRAMES IT NAMES, which is also why an appended
  terminal is the last FRAME of its run: a reader that meets a RUN_ERROR no run
  emitted must have met the line that explains it first.

  BEST EFFORT ON PURPOSE. A corrupt log throws here and is left to `rebuild` to
  refuse by name -- repairing is what this does, and the reader that follows says
  precisely what is wrong with a log nobody can repair."
  [stem ^java.io.File path]
  (try
    (when-let [closures (seq (replay/closing-frames
                              (replay/lines->records (replay/read-lines path))))]
      (doseq [{:keys [run-id last-frame frames]} closures]
        (log! stem nil "session/closed-off" {:run-id     run-id
                                             :last-frame last-frame
                                             :frames     (mapv :type frames)})
        (doseq [frame frames]
          ;; RUN-ID IS THE CLOSED RUN'S: the frames belong to it, and that is how a
          ;; reader pairs a terminal frame with the run it ended.
          (log! stem run-id "event" frame)))
      (mapv (fn [{:keys [run-id frames]}] {:run-id run-id :frames (mapv :type frames)})
            closures))
    (catch Throwable t
      (log/warn! :session/close-off-failed {:thread-id stem :reason (ex-message t)})
      nil)))

(defn- rebuild-post
  "POST /api/threads/<stem>/rebuild -- hand the client its conversation back:
  the AG-UI message list (seed + every recorded frame, reasoning and tool
  calls included) plus the context it started with. The client takes both into
  its next ordinary run; the server holds no rebuilt state. A CORRUPT log is
  refused with the reason on the 400; a log that merely ends MID-RUN is closed
  off first (`close-off-open-run!`) and rebuilt, because that is what continuing
  a session means. The rebuild action lands a session/rebuilt audit line on the
  log it rebuilt -- runId nil, because a rebuild happens OUTSIDE any run.

  The stem is located BEFORE anything else happens, and that ordering is why a
  rebuild never writes half a trace: a stem that resolves to nothing, or to more
  than one file, is refused without landing its audit line anywhere."
  [req stem]
  (let [located (try {:ok (replay/locate (home/projects-dir) stem)}
                     (catch Throwable t {:error (ex-message t)}))
        _       (when (nil? (:error located))
                  (close-off-open-run! stem (:ok located)))
        result  (when (nil? (:error located))
                  (try {:ok (replay/rebuild (:ok located))}
                       (catch Throwable t {:error (ex-message t)})))]
    (cond
      (some? (:error located))
      (api-response 404 {:error (:error located)})

      (some? (:error result))
      (api-response 400 {:error (:error result)})

      :else
      (let [{:keys [messages context]} (:ok result)]
        (log! stem nil "session/rebuilt" {:messages (count messages) :via "http"})
        (api-response 200 {:threadId stem
                           :messages messages
                           :context  (or context [])})))))

(defn- add-project-post
  "POST /api/projects {dir} -- DIR becomes a project of this home, with no
  session in it yet. Answers {:projectId .. :path <canonical>}.

  THE SINGULAR/PLURAL PAIR IS THE WHOLE DISTINCTION, so it is worth stating
  plainly: POST /api/project (singular) binds a SESSION to a directory and is
  how an existing conversation moves; POST /api/projects (plural) makes the
  DIRECTORY a project and is how the list grows. A session's bind needs the
  directory to exist too, so the two share the validation and the same
  find-or-create inside harness.cap.project -- what differs is which fact the caller
  has in hand. The sidebar's 'add a project' has a directory and no conversation;
  'new task' has a conversation and a project already.

  FIND-OR-CREATE, so adding a directory that is already listed answers the SAME
  project rather than failing: re-adding something a person forgot was already
  there is not a mistake they can act on, and a 'duplicate' refusal would be a
  dead end. The caller (the sidebar) switches to whatever comes back.

  Validation failure is a NAMED 400 carrying the server's reason -- the path as
  typed -- and nothing is written; the route lands no audit line either, exactly
  as the singular route does not (a project is not a session and has no log to
  write to)."
  [req]
  (let [parsed (try {:ok (json/read-str (slurp (:body req) :encoding "UTF-8")
                                        :key-fn keyword)}
                     (catch Throwable _ {:bad true}))
        {:keys [ok bad]} parsed]
    (cond
      bad
      (api-response 400 {:error "request body is not valid JSON"})

      (str/blank? (str (:dir ok)))
      (api-response 400 {:error "missing dir"})

      :else
      (let [added (try {:ok (project/add-project! (str (:dir ok)))}
                       (catch Throwable t {:error (ex-message t)}))]
        (if-some [error (:error added)]
          (api-response 400 {:error error})
          (api-response 200 {:projectId (:project-id (:ok added))
                             :path      (:path (:ok added))
                             ;; Sessions that remembered this directory, coming
                             ;; back with it -- see `remove-project!`. On a
                             ;; fresh add it is 0, which is the truthful answer
                             ;; rather than a field nobody sets.
                             :adopted   (:adopted (:ok added))}))))))

(defn- remove-project-post
  "POST /api/projects/<canonical path>/remove -- take that directory out of this
  home's project list. Answers {:path .. :unbound <n>}.

  THE PATH IS THE PROJECT'S IDENTITY, not its integer id, and that is what the
  sidebar has in hand: every project row is drawn with `:path` on it (the listing
  hands out the canonical form), while `projectId` is a store detail no client
  needs. So the route is keyed the way the client knows the thing -- and the
  matcher decodes an encoded path into one segment, which is what makes a path
  with slashes in it addressable at all.

  A REMOVAL, NOT A DELETION, and the route's job is only to say so honestly. The
  verb deletes the `projects` ROW: the directory stops being one this home lists,
  its sessions are unbound by the schema's own trigger, and NOTHING under
  `projects/<workspace>/` is opened -- the jsonl files stay byte-for-byte and
  mtime-for-mtime where they were. Re-adding the same directory adopts the
  sessions back, archive flags included.

  NO AUDIT LINE, and here the reason is structural rather than a choice: an audit
  line is written to a SESSION's log, the workspace is a function of the project,
  and this verb is removing the project. There is no file this action owns, and
  inventing one -- the .unbound workspace, say -- would land a line where the
  session does not live. The acceptance pins every file under the workspace in
  any case, so a helpful line written anywhere would fail it.

  An unknown directory is a NAMED 404: the sidebar needs a sentence for the row
  the click landed on, and a 200 about a project that was not there would tell the
  caller their stale list is current."
  [stem]
  (let [removed (try {:ok (project/remove-project! stem)}
                     (catch Throwable t {:error (ex-message t)}))]
    (if-some [error (:error removed)]
      (api-response 404 {:error error :path stem})
      (api-response 200 {:path    (:path (:ok removed))
                         :unbound (:unbound (:ok removed))}))))

(defn- mcp-get
  "GET /api/mcp?threadId=.. -- this session's MCP ledger: which servers it
  declares, what each one is doing, and which tools each is providing.

  READ-ONLY, so no audit line -- the same rule the other GETs follow. Asking what
  a server is doing is not part of the record of what it did.

  An unbound (or unknown) thread is an ANSWER, not an error: the declarations come
  from the configuration home plus, when there is one, the bound project's -- every
  session has an MCP ledger, even the one that has declared nothing (it is empty).

  The ledger carries no server's `:env` at any depth. That is the api-key's rule,
  and it holds here for the same reason: a person needs to know a server IS
  configured, never with what."
  [req]
  (let [thread-id (get (query-params (:query-string req)) "threadId")]
    (api-response 200 {:threadId (or thread-id "")
                       :servers  (cap-mcp/status thread-id)})))

(defn- mcp-post
  "POST /api/mcp {threadId, server, enabled} -- switch one declared server on or
  off FOR THIS SESSION.

  NOT A CONFIG EDIT: mcp.edn is untouched, and the switch is gone on restart --
  the same standing as a session's tool overlay and its hook overlay. Closing a
  server closes its process, but its tools stay in the table with their calls
  refused (see harness.cap.mcp/session-disable-server!), because hiding them would
  make 'there is no such server' and 'that server is off' the same observation.

  A CHANGE WORTH A LINE, unlike the GET above: this one moves what the session can
  do, so it lands an `mcp/server` audit line with `disabled`, runId null (it happens
  outside any run). A server this session does not declare is a NAMED 404: the panel
  draws a switch per DECLARED server, so an unknown name means the screen is out of
  date, and guessing which server it meant would switch the wrong thing."
  [req]
  (let [parsed (try {:ok (json/read-str (slurp (:body req) :encoding "UTF-8")
                                        :key-fn keyword)}
                    (catch Throwable _ {:bad true}))
        {:keys [ok bad]} parsed
        thread-id (str (:threadId ok))
        server    (:server ok)
        enabled   (:enabled ok)
        declared  (set (keys (cap-mcp/config thread-id)))]
    (cond
      bad
      (api-response 400 {:error "request body is not valid JSON"})

      (str/blank? thread-id)
      (api-response 400 {:error "missing threadId"})

      (not (string? server))
      (api-response 400 {:error "missing server"})

      (not (boolean? enabled))
      (api-response 400 {:error "enabled must be true or false"})

      (not (contains? declared server))
      (api-response 404 {:error (str "this session does not declare an MCP server "
                                     (pr-str server)
                                     "; it declares " (pr-str (vec (sort declared))))})

      :else
      (do (if enabled
            (cap-mcp/session-enable-server! thread-id server)
            (cap-mcp/session-disable-server! thread-id server))
          (log! thread-id nil "mcp/server" {:server server :disabled (not enabled)
                                            :via "http"})
          (api-response 200 {:threadId thread-id :server server :enabled enabled})))))

(defn- elicitation-get
  "GET /api/elicitation?interruptId=.. -- the QUESTION behind a parked interrupt:
  which server asked, what it asked, and the JSON Schema it wants filled in.

  WHY THIS IS AN ENDPOINT AND NOT A FIELD ON THE INTERRUPT. AG-UI's interrupt
  object is a strict shape -- id, reason, message, toolCallId and a couple more, and
  a client's own validator refuses anything else -- so a form schema stuffed into it
  would be a protocol change this harness has no business making. The interrupt says
  'a server is asking a question' and carries the question's sentence; the SHAPE of
  the answer is fetched here, by the client that is about to draw it.

  Read-only, and therefore no audit line: it answers where a parked call already is,
  and asking about a decision must not become part of the record of it.

  A missing or unknown id is a NAMED 404 rather than an empty form: a client drawing
  a form for a question nobody asked would be collecting answers into nowhere."
  [req]
  (let [id (get (query-params (:query-string req)) "interruptId")]
    (cond
      (str/blank? id)
      (api-response 400 {:error "missing interruptId query parameter"})

      :else
      (let [rec (tools/parked id)]
        (if (and rec (= :elicitation (:reason rec)))
          (api-response 200 {:interruptId id
                             :server      (:server rec)
                             :prompt      (:prompt rec)
                             :schema      (:schema rec)
                             :expiresAt   (:expires-at rec)})
          (api-response 404 {:error (str "no elicitation is parked under " id)}))))))

(defn- model-get
  "GET /api/model?threadId=.. -- what this session is served by and what that
  model accepts, for a client deciding whether to offer an image picker:

    {:provider :openrouter :model \"anthropic/claude-sonnet-4.5\"
     :reasoning-effort \"high\"
     :protocol :openai-completions :base-url \"https://openrouter.ai/api/v1\"
     :input [\"image\" \"text\"] :output [\"text\"]
     :context-window 1000000 :max-output-tokens 64000}

  Answered from the LIVE resolution (providers/active-provider): a session override
  made a moment ago is already reflected, and nothing is cached between calls.
  The api-key is not in the answer at any depth -- active-provider names its
  fields one by one rather than passing the resolved map through.

  The two counts are REPORTED, not enforced: nothing here counts tokens, and
  nothing here is going to send :max-output-tokens as max_tokens. They exist so
  a client choosing a model -- or a human reading a log -- can see what it is
  buying before it asks for it.

  THE SHAPE IS THIS HARNESS'S, NOT AG-UI's. AG-UI describes capabilities as
  MultimodalCapabilities ({input.{image,audio,video,pdf,file}, ...}) for its
  connect handshake; this endpoint answers the harness's own vocabulary
  (:text/:image) and leaves that mapping to whoever wires the handshake up. It
  is also only the INPUT half of that story, but here input is all there is to
  report: output is always text, and a field that can only ever hold one value
  says nothing.

  READ-ONLY, and therefore leaves no trace: like GET /api/project, only a route
  that can CHANGE something writes an audit line.

  Absent is an answer, not an error. An unbound thread, a provider described
  inline that declared no modalities, a model nobody gave a reasoning effort --
  each comes back with the field missing rather than with a 400, because a
  client asking 'what is this session' deserves the truth about a sparse
  configuration rather than a failure it has to interpret."
  [req]
  (let [thread-id (get (query-params (:query-string req)) "threadId")]
    (api-response 200 (providers/wire (providers/active-provider thread-id)))))

(defn- settings-get
  "GET /api/settings?threadId=.. -- the read-only settings panel's answer: what
  configuration is in force for this session, where each choice came from, where
  the api-key WOULD be read from, and which files make up this home. See
  providers/settings for the shape and for why every field is read live.

  THE WHOLE ANSWER IS RENDERED THROUGH `wire` WITH ITS OWN KEYS, and that is the
  second half of the key's treatment rather than bookkeeping: `wire` refuses to
  render :api-key at any position it is asked about, so a resolution that
  somehow reached this body whole would lose its secret on the way out.

  The reason a failed resolution is a 400 with the server's own sentence: the
  panel shows that sentence. An unknown provider or an undeclared model is a
  configuration a person is in the middle of fixing, and 'could not resolve: no
  provider named :beta; the registry defines ...' is a far better thing to be
  looking at than an empty panel. A missing config.edn lands here too, with
  harness.infra.home's message carrying the path and the knob that moves it.

  READ-ONLY, and therefore leaves no trace: like GET /api/model, only a route
  that can CHANGE something writes an audit line. This one cannot even change the
  session it is asked about -- it resolves and returns."
  [req]
  (let [thread-id (get (query-params (:query-string req)) "threadId")
        answer    (try {:ok (providers/settings thread-id)}
                       (catch Throwable t {:error (ex-message t)}))]
    (if-some [error (:error answer)]
      (api-response 400 {:error error})
      (api-response 200 (providers/wire (:ok answer) (keys (:ok answer)))))))

(defn- model-post
  "POST /api/model {threadId, provider?, model?, reasoning-effort?, clear?} --
  change THIS session's selection, and answer the resolution that is now in force.

  THE HTTP TWIN OF THE `session-configure` TOOL, and deliberately its twin rather
  than a second implementation of the same idea: the three knobs are the same
  three, the unknown-key refusal is the same refusal, and the change is validated
  by RESOLVING IT before anything is written -- a change that cannot be served is
  not a change, and writing first would leave the session holding a configuration
  every later run fails on.

  WHAT IT DOES NOT SHARE IS THE ROAD TO THE LOG. The tool cannot write a log line,
  so it leaves the change in the provider outbox for the run that will drain it;
  this route IS the edge, so it writes its own line, at the moment of the change,
  exactly as POST /api/project does. Using the outbox here would be worse than
  redundant: nothing drains it outside a run, so a change made in the composer of
  an idle session would surface in the log attached to the NEXT run -- a timeline
  that says the model changed after it did.

  `clear: true` DROPS THE SESSION'S OWN TIER, putting the session back on
  config.edn and the catalog. It is a knob rather than an empty body because
  'change nothing' and 'stop choosing' are different requests, and only one of
  them has something to say.

  ONLY THE SESSION IS TOUCHED. config.edn and every other thread
  are read and left alone; the override lives in this process's memory keyed by
  thread id, which is what makes 'only the current session' true rather than
  merely intended -- and what makes it not survive a restart, which the panel is
  the place to read."
  [req]
  (let [parsed (try {:ok (json/read-str (slurp (:body req) :encoding "UTF-8")
                                        :key-fn keyword)}
                    (catch Throwable _ {:bad true}))
        {:keys [ok bad]} parsed]
    (cond
      bad
      (api-response 400 {:error "request body is not valid JSON"})

      (str/blank? (str (:threadId ok)))
      (api-response 400 {:error "missing threadId"})

      :else
      (let [thread-id (str (:threadId ok))
            allowed   #{:threadId :provider :model :reasoning-effort :clear}
            extras    (sort (map name (remove allowed (keys ok))))]
        (cond
          (seq extras)
          (api-response 400 {:error (str "does not understand " (pr-str (vec extras))
                                         "; it takes provider, model, reasoning-effort and clear")})

          (:clear ok)
          (let [{:keys [before]} (providers/swap-override! thread-id nil)]
            (log! thread-id nil "provider/session-changed"
                  {:before before :after nil :via "http"})
            (api-response 200 (providers/wire (providers/active-provider thread-id))))

          :else
          (let [change (cond-> {}
                         (some? (:provider ok))         (assoc :provider (:provider ok))
                         (some? (:model ok))            (assoc :model (:model ok))
                         (some? (:reasoning-effort ok)) (assoc :reasoning-effort (:reasoning-effort ok)))]
            (if (empty? change)
              (api-response 400 {:error "nothing to change: give at least one of provider, model, reasoning-effort"})
              ;; ONE ATOM OPERATION, and it answers the transition it made. Reading the
              ;; tier here and writing it back would lose a change the `session-configure`
              ;; tool made in between -- both write this tier, from different threads --
              ;; and this line would then record a before->after pair that never happened.
              (let [answer (try {:ok (providers/swap-override! thread-id change)}
                                (catch Throwable t {:error (ex-message t)}))]
                (if-some [error (:error answer)]
                  (api-response 400 {:error error})
                  (let [{:keys [before after resolved]} (:ok answer)]
                    (log! thread-id nil "provider/session-changed"
                          {:before before :after after :via "http"
                           :resolved resolved})
                    (api-response 200 (providers/wire (providers/active-provider thread-id)))))))))))))

(defn- choices-get
  "GET /api/choices?threadId=.. -- what the session's pickers may offer: the three
  knobs as they stand, the providers and models the catalog declares, and the
  reasoning efforts worth putting on a menu. See providers/choices for the shape
  and for why the answer is built field by field rather than passed through
  `wire`.

  READ-ONLY, and therefore leaves no trace: it resolves and returns. A thread id
  it does not recognise is not refused -- an unbound thread still has a
  configuration (the process-wide one), and the picker for it is the same picker."
  [req]
  (api-response 200 (providers/choices (get (query-params (:query-string req)) "threadId"))))

(defn- skills-get
  "GET /api/skills?threadId=.. -- the skill list a PERSON can pick from: this
  session's roots grouped, each skill with its name, its description, and -- when
  it cannot be used -- the reason. See harness.cap.skills/skill-list for the shape and
  for the two places it deliberately differs from the model's catalog.

  THE ANSWER IS THE SERVER'S, and that is the point rather than an implementation
  note: which roots this session reads, which of them is the machine's and which
  the project's, who won a name conflict, and why a broken skill is broken are
  questions `harness.cap.skills` already answers. A client that re-derived any of them
  would be a second answer, free to drift from the one the model's catalog is
  built from -- and the two WOULD drift, because they are read at different
  moments by different code.

  An unbound session is not an error: it has the machine's skills and no project
  ones, which is exactly what the list shows. A session with no skills at all
  answers {:groups []} rather than a 404 -- 'nothing to pick' is an ordinary
  state, and a caller should not have to read it as a failure (the same reason
  GET /api/git answers {:dir nil} for a session with no directory).

  READ-ONLY, and therefore leaves no trace: like GET /api/choices, only a route
  that can CHANGE something writes an audit line."
  [req]
  (api-response 200 (skills/skill-list
                     (project/skill-layers (get (query-params (:query-string req)) "threadId")))))

;; ------------------------------------------------- the provider catalog, as a form

(defn- provider-models-post
  "POST /api/providers/models {id?, base-url?, protocol?, api-key?} -- ask a vendor
  what it serves, for the form's model list.

  AN EXACT ROUTE RATHER THAN A VERB, and that is load-bearing rather than
  stylistic: `/api/providers/models` is ONE segment after the collection, so the
  /api/<collection>/<stem>/<verb> matcher can never see it -- it would fall through
  to the run endpoint and become a 500 from a body that was never there, which is
  the trap edge.md records for a GET on the verb shape. The exact match wins before
  the shape is tried.

  A PROVIDER MAY BE *NAMED* \"models\" WITHOUT COLLIDING: creating and updating go
  through /api/providers (the collection), removing through /api/providers/<id>/remove
  (the verb shape), so this path is only ever the probe. A reserved word would be a
  rule with no failure to prevent.

  THE KEY MAY COME FROM THE FORM -- somebody typing one into a field means to try
  that key before it is written anywhere -- and when it does not, it is resolved
  exactly as a run resolves it. The answer carries model ids and the endpoint that
  was asked; the key is in neither, and nothing is written: no file, no store, no
  log line. The vendor's refusal comes back in the vendor's own words (see
  providers/probe-models), which is what makes a 401 debuggable from the form."
  [req]
  (let [parsed (try {:ok (json/read-str (slurp (:body req) :encoding "UTF-8")
                                        :key-fn keyword)}
                    (catch Throwable _ {:bad true}))
        {:keys [ok bad]} parsed]
    (cond
      bad
      (api-response 400 {:error "request body is not valid JSON"})

      (not (map? ok))
      (api-response 400 {:error "request body must be a JSON object naming a vendor to ask"})

      :else
      (let [answer (try {:ok (providers/probe-models ok)}
                        (catch Throwable t {:error (ex-message t)}))]
        (if-some [error (:error answer)]
          (api-response 400 {:error error})
          (api-response 200 (:ok answer)))))))

(defn- defaults-post
  "POST /api/defaults {provider?, model?, reasoning-effort?} -- set the DEFAULT tier,
  the one config.edn's :default section holds, and answer the catalog as it now
  stands.

  THE TIER A NEW SESSION STARTS FROM, which is what makes this different from
  POST /api/model: that one changes THIS session (memory, gone on restart), and this
  one changes the file every session reads. The page that shows them side by side is
  the same page, which is why the distinction is drawn in one place -- the tier
  report `GET /api/settings` already answers with.

  ABSENT MEANS 'LEAVE THAT KNOB ALONE' AND AN EXPLICIT null MEANS 'REMOVE THE KEY'
  -- see providers/put-defaults! for why those are different requests. A body that
  names a provider REPLACES the tier (the one way out of an inline description);
  one that does not is a patch.

  VALIDATED BY RESOLVING, before anything is written: an unknown provider or a model
  the vendor does not declare is a 400 with the server's sentence, and config.edn
  does not move. NO AUDIT LINE, for the reason its provider-writing siblings give:
  the rule is 审计行跟着日志走, and this neither moves nor reads a log."
  [req]
  (let [parsed (try {:ok (json/read-str (slurp (:body req) :encoding "UTF-8")
                                        :key-fn keyword)}
                    (catch Throwable _ {:bad true}))
        {:keys [ok bad]} parsed]
    (cond
      bad
      (api-response 400 {:error "request body is not valid JSON"})

      (not (map? ok))
      (api-response 400 {:error "request body must be a JSON object naming the knobs to set"})

      :else
      (let [answer (try {:ok (providers/put-defaults! ok)}
                        (catch Throwable t {:error (ex-message t)}))]
        (if-some [error (:error answer)]
          (api-response 400 {:error error})
          (api-response 200 (providers/registry-report)))))))

(defn- providers-get
  "GET /api/providers -- the catalog as the settings form needs it: every vendor
  with where it came from, its endpoint, the models it declares, whether a key is
  configured and WHICH line supplies it. See harness.cap.providers/registry-report
  for the shape and for why every field in it is one that function chose.

  NO THREADID, unlike /api/model and /api/choices: the catalog is this HOME's, the
  same answer for every session, and a form editing it is not doing anything to a
  conversation. Asking for one would suggest a per-session answer that does not
  exist.

  READ-ONLY, and therefore leaves no trace: nothing here writes a file, touches the
  store, or registers anything -- the api-key's VALUE is absent at every depth,
  which is what makes this safe to call while a run is in flight."
  [_req]
  (api-response 200 (providers/registry-report)))

(defn- provider-post
  "POST /api/providers {id, …entry, api-key?} -- create or replace ONE entry in
  config.edn's :providers, and answer with the whole catalog as it now stands.

  THE WHOLE CATALOG, not the row, and the same shape GET answers with: the form
  refetches the list after every write anyway, and one shape for both means a client
  has one thing to parse. (It is also the honest answer to 'and what does the file
  say now'.)

  VALIDATION RUNS BEFORE ANYTHING IS WRITTEN -- see providers/put-provider! -- so a
  refused change leaves config.edn byte for byte as it was. That is the one thing a
  form editing a hand-written file owes the person using it, and it is why the
  refusals below are 400s with the server's own sentence rather than a 500: the
  sentence names the field, the value, and what to write instead, and the form shows
  it verbatim.

  THE API-KEY, when the body carries one, becomes its own line in the home's .env
  under the credential name the id derives -- written after the config write has
  landed, so a refused entry cannot leave a key behind for a provider that does not
  exist. It is never echoed back: the answer's key facts are presence, origin and
  name.

  NO AUDIT LINE, and the rule says why: 审计行跟着日志走，不跟着写入走. Writing
  config.edn neither moves nor reads a log, so this is in the same class as adding a
  project. Nothing is cached either -- the catalog is re-read per call, so the next
  run uses this entry with no restart."
  [req]
  (let [parsed (try {:ok (json/read-str (slurp (:body req) :encoding "UTF-8")
                                        :key-fn keyword)}
                    (catch Throwable _ {:bad true}))
        {:keys [ok bad]} parsed]
    (cond
      bad
      (api-response 400 {:error "request body is not valid JSON"})

      (not (map? ok))
      (api-response 400 {:error "request body must be a JSON object describing one provider"})

      (str/blank? (str (:id ok)))
      (api-response 400 {:error "missing id: a provider entry is identified by one, and its credential name is derived from it"})

      :else
      (let [id     (:id ok)
            key    (:api-key ok)
            entry  (dissoc ok :id :api-key)
            answer (try {:ok (providers/put-provider! id entry key)}
                        (catch Throwable t {:error (ex-message t)}))]
        (if-some [error (:error answer)]
          (api-response 400 {:error error})
          (api-response 200 (providers/registry-report)))))))

(defn- remove-provider-post
  "POST /api/providers/<id>/remove -- take one entry out of config.edn's :providers,
  and answer with the catalog as it now stands plus which entry went.

  WHAT CAN BE REMOVED IS WHAT THE FILE HOLDS, and the refusal says so rather than
  answering 'removed' while the vendor is still there: a built-in provider is the
  built-in table's, and what a person CAN take back is their patch of one.

  The .env is NOT touched: the key line may be one this route wrote or one somebody
  typed, and deleting a secret is not a side effect anybody asked for. A line for a
  provider that no longer exists is inert.

  NO AUDIT LINE, like its write sibling, and for the same reason."
  [id]
  (let [answer (try {:ok (providers/remove-provider! id)}
                    (catch Throwable t {:error (ex-message t)}))]
    (if-some [error (:error answer)]
      (api-response 400 {:error error})
      (api-response 200 (assoc (providers/registry-report)
                               :removed (:name (:ok answer)))))))

(defn- git-get
  "GET /api/git?threadId=.. -- the session's directory as a working tree: the
  branch it is on, the branches it could be on, and how many changes are in the
  way. `:dir` is the binding the answer is about, echoed so a strip can draw the
  directory and the branch from one call.

  A SESSION WITH NO DIRECTORY ANSWERS `{:dir nil :repo? false}`, not a 400: most
  sessions have no project, the strip simply shows nothing, and a caller drawing
  chrome should not have to treat 'nothing to show' as a failure."
  [req]
  (let [thread-id (get (query-params (:query-string req)) "threadId")
        dir       (project/binding-for thread-id)]
    (api-response 200 (assoc (git/state dir) :dir dir))))

(defn- git-post
  "POST /api/git {threadId, branch} -- move the session's directory onto BRANCH,
  and answer the state afterwards.

  THE ONE THING HERE THAT CHANGES A DIRECTORY RATHER THAN A ROW, and it is
  confined to what was asked for: `git checkout` with no --force, so a dirty tree
  or a branch held by another worktree is refused IN GIT'S OWN WORDS, and the
  refusal is a 400 carrying that sentence rather than a paraphrase of it. Nothing
  is written to the store or to any log on the way in; on the way out there is one
  audit line, because this changed something a person would want to find later.

  A REFUSED SWITCH WRITES NOTHING, which is why the line is written after the
  checkout succeeds -- the same 'no trace on failure' POST /api/project keeps for
  a refused move."
  [req]
  (let [parsed (try {:ok (json/read-str (slurp (:body req) :encoding "UTF-8")
                                        :key-fn keyword)}
                    (catch Throwable _ {:bad true}))
        {:keys [ok bad]} parsed]
    (cond
      bad
      (api-response 400 {:error "request body is not valid JSON"})

      (str/blank? (str (:threadId ok)))
      (api-response 400 {:error "missing threadId"})

      :else
      (let [thread-id (str (:threadId ok))
            dir       (project/binding-for thread-id)]
        (if (str/blank? (str dir))
          (api-response 400 {:error "this session has no project directory, so it has no branch to switch"})
          (let [before   (git/state dir)
                answer   (git/switch! dir (:branch ok))]
            (if-some [error (:error answer)]
              (api-response 400 {:error error})
              (do (log! thread-id nil "git/branch"
                        {:before (:branch before) :after (:branch (:ok answer))
                         :dir dir :via "http"})
                  (api-response 200 (assoc (:ok answer) :dir dir))))))))))

(defn- dispatch
  "The route table, with no safety net -- see `handler` for the one wrapped
  around it. Split out so the net is a single line of indentation around the
  whole thing rather than a `try` re-indenting every route."
  [req]
  (cond
    (= :options (:request-method req))
    ;; NO HEADERS BUILT HERE: what a preflight is answered with is decided in one
    ;; place for every route -- see `with-cors`.
    {:status 204}

    ;; THE AG-UI EDGE, and the only route here that answers SSE rather than JSON.
    ;; It IS a route, not the catch-all it used to be: the run endpoint sat at the
    ;; server root and everything unmatched fell to it, which meant a mistyped
    ;; management path became a run -- a request with no RunAgentInput in it, read
    ;; as one. It is under `/api` with the rest so that a front end has ONE prefix
    ;; to think about (and one thing for a dev proxy to forward).
    (= "/api/agent" (:uri req))
    (case (:request-method req)
      :post (handle-run req)
      (api-response 405 {:error "method not allowed"}))
    (= "/api/model" (:uri req))
    (case (:request-method req)
      :get  (model-get req)
      :post (model-post req)
      (api-response 405 {:error "method not allowed"}))

    (= "/api/choices" (:uri req))
    (case (:request-method req)
      :get  (choices-get req)
      (api-response 405 {:error "method not allowed"}))

    (= "/api/skills" (:uri req))
    (case (:request-method req)
      :get  (skills-get req)
      (api-response 405 {:error "method not allowed"}))

    (= "/api/git" (:uri req))
    (case (:request-method req)
      :get  (git-get req)
      :post (git-post req)
      (api-response 405 {:error "method not allowed"}))

    (= "/api/mcp" (:uri req))
    (case (:request-method req)
      :get  (mcp-get req)
      :post (mcp-post req)
      (api-response 405 {:error "method not allowed"}))

    (= "/api/elicitation" (:uri req))
    (case (:request-method req)
      :get  (elicitation-get req)
      (api-response 405 {:error "method not allowed"}))

    (= "/api/settings" (:uri req))
    (case (:request-method req)
      :get  (settings-get req)
      (api-response 405 {:error "method not allowed"}))

    (= "/api/subagents" (:uri req))
    (case (:request-method req)
      :get  (subagents-get req)
      :post (subagents-post req)
      (api-response 405 {:error "method not allowed"}))

    (= "/api/project/pick" (:uri req))
    (case (:request-method req)
      :post (project-pick req)
      (api-response 405 {:error "method not allowed"}))

    (= "/api/project" (:uri req))
    (case (:request-method req)
      :get  (project-get req)
      :post (project-post req)
      (api-response 405 {:error "method not allowed"}))

    (= "/api/threads" (:uri req))
    (case (:request-method req)
      :get  (threads-get req)
      (api-response 405 {:error "method not allowed"}))

    (= "/api/projects" (:uri req))
    (case (:request-method req)
      :get  (projects-get req)
      :post (add-project-post req)
      (api-response 405 {:error "method not allowed"}))

    (= "/api/providers" (:uri req))
    (case (:request-method req)
      :get  (providers-get req)
      :post (provider-post req)
      (api-response 405 {:error "method not allowed"}))

    (= "/api/providers/models" (:uri req))
    (case (:request-method req)
      :post (provider-models-post req)
      (api-response 405 {:error "method not allowed"}))

    (= "/api/defaults" (:uri req))
    (case (:request-method req)
      :post (defaults-post req)
      (api-response 405 {:error "method not allowed"}))

    :else
    (if-some [{:keys [verb stem]} (stem-verb-route "threads" thread-verbs (:uri req))]
      ;; The verb-carrying routes: one shape, three verbs. TWO OF THEM ARE POSTS
      ;; because they have an effect, and `stats` is a GET because it only reads --
      ;; so the rule is 'the method says whether there is an effect', not 'this
      ;; shape is POST-only'. A method this shape does not serve is still answered
      ;; 405 HERE rather than falling through to the run endpoint -- which is where
      ;; the pre-verb dispatch used to send it, and where it became a 500 from a
      ;; body that was never there.
      (case [(:request-method req) verb]
        [:post "rebuild"] (rebuild-post req stem)
        [:post "archive"] (archive-post req stem)
        [:get "stats"]    (stats-get stem)
        [:get "trajectory"] (trajectory-get stem)
        (api-response 405 {:error "method not allowed"}))
      (if-some [{:keys [verb stem]} (stem-verb-route "providers" provider-verbs (:uri req))]
        (case [(:request-method req) verb]
          [:post "remove"] (remove-provider-post stem)
          (api-response 405 {:error "method not allowed"}))
        (if-some [{:keys [verb stem]} (stem-verb-route "projects" project-verbs (:uri req))]
          (case [(:request-method req) verb]
            [:post "remove"] (remove-project-post stem)
            (api-response 405 {:error "method not allowed"}))
          (if-some [{:keys [verb stem]} (stem-verb-route "subagents" subagent-verbs (:uri req))]
            (case [(:request-method req) verb]
              [:post "remove"] (subagents-remove-post stem)
              (api-response 405 {:error "method not allowed"}))
            ;; NOTHING ELSE. This used to be `(handle-run req)` -- the run endpoint
            ;; was the fallback for every unmatched path -- so a typo in a management
            ;; route arrived at the kernel as a run with no RunAgentInput in it, and
            ;; was answered with whatever that produced. A path this table does not
            ;; know is now exactly that, and says so.
            (api-response 404 {:error (str "no such route: " (:uri req))})))))))

(defn- with-cors
  "The CORS headers this request's answer carries, merged onto whatever the route
  built.

  ONE EXIT IS THE POINT. Every JSON answer in this file is built by `api-response`
  and comes back through `handler`, and `api-response` is handed a status and a
  body -- it cannot see what page is asking, which is the only thing this decision
  turns on. Merging here also means a route cannot forget to: the OPTIONS route
  returns its 204 with no headers at all and still gets them.

  THE SSE ROUTE IS NOT COVERED BY THIS, and cannot be: its status and headers ride
  on the first frame rather than on the ring response (see `runner`, which is
  handed the same header)."
  [req resp]
  (if-some [headers (cors-headers (request-origin req))]
    (update resp :headers merge headers)
    resp))

(defn handler
  "Every request, with a net under it.

  WITHOUT THIS, AN UNHANDLED EXCEPTION IS INVISIBLE: it goes to http-kit, which
  answers the client a 500 and prints to a console nobody is reading, and the
  one fact worth having -- which route, which thread, what threw -- is gone. So
  the net reports through harness.infra.log, which puts the same sentence on the
  console and in ~/.clj-harness/harness.infra.log, and answers the client a 500 whose
  body is the server's own sentence rather than an empty one.

  THE ROUTES ARE NOT REWRITTEN TO THROW. Most of them already catch what they
  expect and answer a 400 with a reason; this is for what they did not expect,
  and it deliberately does not try to tell the two apart -- a route that
  answered a 400 never reaches here.

  IT IS ALSO WHERE THE CORS HEADERS GO ON, on both paths -- including the one that
  just failed, since a browser cannot read a 500 body it was not allowed to read."
  [req]
  (try
    (with-cors req (dispatch req))
    (catch Throwable t
      (log/error! :request-failed t {:method (:request-method req) :uri (:uri req)})
      (with-cors req (api-response 500 {:error (or (ex-message t) "the request failed")})))))

;; ---------------------------------------------------------------------- start

(def ^:private death-logged?
  "Whether this process has already arranged to record its own exit. One hook per
  process, not one per `start!`: the test suite starts a server per case, and a
  hook apiece would register a hundred of them."
  (atom false))

(defn- log-own-death!
  "On the way out, one line saying the process is going down.

  WHY THIS IS WORTH A HOOK. Everything else in the file is written by a process
  that is still running; a run that stops mid-flight because the JVM went away
  leaves its jsonl ending mid-sentence and NOTHING anywhere saying so -- which is
  a death indistinguishable from a hang, from a client that hung up, and from a
  record that was never flushed. This is the only code the JVM runs on the way
  out, so it is the only place that line can come from. `harness.cap.mcp` installs
  one for the same reason: cleanup has nowhere else to live.

  THE LINE IS EVIDENCE WHEN IT APPEARS AND NONE WHEN IT DOES NOT, because the JVM
  runs shutdown hooks only for an orderly death. A `SIGTERM` writes it -- three
  runs out of three on 2026-09-17, on the thread this names -- and a `SIGKILL` or a
  crash writes nothing at all (measured the same day: the file held its startup
  line and nothing else). So an absent `:shutdown` narrows nothing by itself; what
  it is for is the OTHER half, where the run's own record stops mid-flight: a
  `run/start` with no terminal and a `:shutdown` after it is a process somebody
  stopped, and the same without one is a run that is still going. Logback's own
  hook races this one in principle; it has not been observed to win."
  [root]
  (when (compare-and-set! death-logged? false true)
    (.addShutdownHook (Runtime/getRuntime)
                      (Thread. ^Runnable (fn [] (log/info! :shutdown {:root root}))
                               "harness-shutdown"))))

(defn start!
  "Start the server and return its stop fn. Default port is 8080.

  LOGGING COMES UP FIRST, BEFORE THE SOCKET. A server that cannot bind -- the
  port is taken, which on this machine is the ordinary case of a session already
  running -- failed to START, and that is precisely the kind of failure somebody
  wants in a file rather than in whatever console the process happened to have.
  Configuring after `run-server` meant the one error worth recording at startup
  was the one error that could not be: found by starting this on a busy port and
  watching a bare BindException go past with no log file."
  [& [opts]]
  (let [opts (merge {:port port :ui-origin ui-origin} opts)
        root (logging/configure!)
        ;; THE COMPOSITION ROOT INSTALLS THE APP'S CAPABILITIES, and this is the
        ;; one place in a real process where that happens. The kernel ships an
        ;; empty tool table (harness.kernel.tools knows what a tool is, not which
        ;; tools exist), so a process that never calls this serves a model with no
        ;; tools at all -- which is the honest answer, not a broken one.
        ;;
        ;; The teardown is returned as part of the STOP fn: stopping a server must
        ;; give the table back the way it was, or a test suite that starts a
        ;; hundred servers leaves a hundred layers stacked, each one's teardown
        ;; never claimed.
        ;; ...AND THE REST OF THE APP'S CAPABILITIES, through the same-shaped doors
        ;; on their own namespaces: which two files hold a user's hook declarations,
        ;; and the kernel's own three SystemPrompt rows. A capability installs itself
        ;; where it is understood and the composition root only decides WHICH ones
        ;; this process runs.
        teardowns [(cap-tools/install!)
                   (cap-hooks/install!)
                   (system-prompt/install!)
                   ;; MCP SERVERS, when a home declares any: the tools they provide
                   ;; are a question about the SESSION (which project's mcp.edn is
                   ;; in force, which server is up), so the seam asks this
                   ;; capability per assembly instead of being handed a map.
                   (cap-mcp/install!)
                   ;; SUBAGENTS: the `agent` tool a session delegates with, the range
                   ;; a subagent thread is held to, and the row that tells it who it
                   ;; is. LAST, so the editing mode's subtraction is asked first and
                   ;; a name both policies refuse is refused in the editing mode's
                   ;; words -- it is the more specific statement about the session.
                   ;; The runner is passed in because running a conversation is this
                   ;; namespace's business, not a capability's.
                   (subagents/install! {:run run-subagent!})]]
    (println (str "logging to " root "/logs/harness.infra.log (rotated by date and size)"))
    ;; THE ONE ORIGIN THIS PROCESS ANSWERS BY NAME, settled before the socket opens --
    ;; the same shape as the port below it and for the same reason: both are facts
    ;; about the process that the process itself did not choose. Pages on this
    ;; machine need none of it (they are answered by rule, whatever port -- see
    ;; `localhost-page?`); this is for a launcher that serves the UI somewhere else.
    ;; `start!` is the composition root, so such a launcher says so HERE; see
    ;; `named-origin` for why it cannot be a value baked into a header map at load
    ;; time.
    ;; `or` rather than the value itself: an explicit `:ui-origin nil` is the one
    ;; way a caller could turn the named origin off by accident, and `merge` would
    ;; let it through.
    (reset! named-origin (or (:ui-origin opts) ui-origin))
    ;; A HOME THAT HAS NEVER BEEN CONFIGURED GETS A config.edn HERE, at boot: a
    ;; process about to SERVE from a home is the one that should hand a person a file
    ;; to edit. The reader does not do this -- `home/config` reads a missing file as an
    ;; empty one and creates nothing -- so an offline tool or a test asking what a home
    ;; says still leaves the home exactly as it found it.
    (when (:migrated? (providers/migrate-config!))
      (println (str "config.edn in " root " was in the shape from before :default and"
                    " :providers -- moved it under :default (the old file is"
                    " config.edn.bak)")))
    (when (:created? (providers/ensure-config!))
      (println (str "no config.edn in " root " -- wrote an empty one; the settings panel"
                    " (or an editor) can fill it in")))
    (try
      (let [server (hk/run-server handler opts)
            ;; THE PORT THE SOCKET GOT, which is not always the one that was asked
            ;; for: `0` means 'whichever is free', and that is the form a launcher
            ;; wants when it cannot know in advance. Reporting `(:port opts)` here
            ;; printed `localhost:0` and logged `port=0`, so the one line a person
            ;; (or a script) reads to learn where the server is said nothing.
            bound  (:local-port (meta server))]
        (println (str "harness listening on http://localhost:" bound)
                 "-- POST an AG-UI RunAgentInput to /api/agent; stop with (stop!)")
        (log/started root bound)
        ;; ...AND ONE LINE FOR THE OTHER END OF THAT STORY. `:listening` marks
        ;; where the file's story begins; this marks where the process stopped
        ;; telling it, which is the fact a run that dies mid-flight leaves behind.
        (log-own-death! root)
        ;; http-kit's server IS the stop fn, and its meta carries :local-port --
        ;; which is how every test learns the port the OS handed out. The wrapper
        ;; keeps that meta, so `(meta stop)` still answers the same thing.
        (with-meta (fn stop! [] (server) (doseq [td teardowns] (td))) (meta server)))
      (catch Throwable t
        (doseq [td teardowns] (td))
        (log/error! :start-failed t {:port (:port opts) :root root})
        (throw t)))))

(defn -main
  "Run the server in the foreground until it is killed.

  `--port N` picks the port; `0` asks the OS for a free one, which is the form a
  launcher wants -- it cannot know in advance which ports are taken, and the one
  that matters here is only knowable after the bind. Whichever it is, the bound
  port is what gets printed and logged (see start!), so a script can start this
  on `0`, read the line, and point a browser at it. Without the option the default
  is unchanged (`port`, 8080).

  `--ui-origin URL` NAMES ONE MORE ORIGIN this edge answers. It is not needed for
  the ordinary dev loop and has not been since the rule changed: a page served
  from this machine is answered whatever port it is on (`localhost-page?`), so
  `scripts/dev.mjs` can give vite any port it likes without telling this process
  anything. What is left is the case a rule cannot cover -- a UI served from
  ANOTHER machine, whose origin nobody could guess -- and there it has to be said.
  It is an argument rather than an environment variable because the caller already
  knows the value, and a fact that is passed to one child should be passed to the
  other the same way."
  [& args]
  (let [option (fn [name] (some (fn [[k v]] (when (= k name) v)) (partition 2 1 args)))
        asked  (option "--port")
        origin (option "--ui-origin")]
    (start! (cond-> {}
              asked  (assoc :port (Integer/parseInt (str asked)))
              origin (assoc :ui-origin (str origin))))
    @(promise)))
