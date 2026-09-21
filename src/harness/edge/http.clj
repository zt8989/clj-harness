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

  All of it is a RECORD, never a source of truth. The conversation is the SERVER'S --
  it lives in `harness.edge.sessions`, is born from this log when a process has none,
  and is continued from memory after that (ADR 0002). The file is still not read
  DURING a run: what a run continues from is the session, not the bytes."
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
            [harness.cap.claims :as claims]
            [harness.cap.preamble :as preamble]
            [harness.cap.project :as project]
            [harness.edge.replay :as replay]
            [harness.edge.record :as record]
            [harness.edge.sessions :as sessions]
            [harness.edge.context :as context]
            [harness.edge.stats :as stats]
            [harness.edge.trajectory :as trajectory]
            ;; The built page, when this process has one: `ui/dist`, served at the
            ;; root. See harness.edge.ui for why the server carries it at all.
            [harness.edge.ui :as ui]
            ;; skill-picker 的 /api/skills 用它（那一票在 main 上，本分支没有）：
            [harness.cap.skills :as skills]
            [harness.cap.system-prompt :as system-prompt]
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

;; THE LOCK IS GONE (ticket 02). One line is one JSON object and a reader parses
;; the file line by line, so a half-written line is not a smaller record, it is a
;; broken file -- and `log!` is called from a hook's own thread as well as from a
;; run's consumer, so the appends had to be serialized. That is now the record
;; writer's single consumer thread (`harness.edge.record`), which is a better
;; answer than a lock: there is exactly one writer by construction, so there is
;; nothing to serialize and no lock to hold on the response path.

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

  IT ANSWERS WHETHER IT CHANGED F, because the writer measures a thread's record
  offset in that file: a carried segment (or an audit line about a refused one)
  moves every offset after it, so the writer re-bases when this answers true
  (`harness.edge.record/prepare-with!`). Nothing to do answers nil.

  CALLED BY THE RECORD WRITER'S CONSUMER BEFORE EVERY LINE (`harness.edge.record`),
  which is this process's only writer -- that is what replaced `log-lock`, and it is
  why this needs no lock of its own. THE CALL IS PER LINE, THE WORK IS AT MOST ONCE:
  the guard above is what makes it so, and the ordering it exists for is only that a
  carried segment precedes the line about to be written. Asking every time is not
  belt-and-braces -- the `when-not` cannot answer for a line that has not been
  written yet, and a segment that appears while this thread is running (a store
  rebuilt under it) is carried by the NEXT line, not by the one that has already
  gone. A thread with no leftover segment pays one `exists` on a file that is not
  there."
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
                  (do (carry-audit! f "log/carry-refused"
                                    {:reason "the leftover segment holds no timestamped lines, so the two ranges cannot be checked"
                                     :from from :to to})
                      true)

                  (and dest-range (not (<= (second dest-range) (first src-range))))
                  (do (carry-audit! f "log/carry-refused"
                                    {:reason (str "the two files' timestamps overlap, so appending would read as one conversation out of order"
                                                  " (the conversation's file ends at " (second dest-range)
                                                  ", the leftover segment begins at " (first src-range) ")")
                                     :from from :to to
                                     :destination-last (second dest-range)
                                     :segment-first (first src-range)})
                      true)

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
                                     :from from :to to}))
                    true)))))))
      (catch Throwable t
        (try
          (carry-audit! f "log/carry-refused"
                        {:reason (str "carrying the leftover unbound segment failed: " (ex-message t))
                         :from (.getAbsolutePath ^java.io.File (home/log-file (unbound-dir) thread-id))
                         :to   (.getAbsolutePath ^java.io.File f)})
          (catch Throwable _ nil))))))

(defn- log!
  "The line goes to the record writer, which appends it off this thread's own
  path (`harness.edge.record`). NOTHING HERE TOUCHES THE FILE: the File is
  resolved here -- where a session's record belongs is a fact about its project,
  and this is the namespace that joins the two -- and the bytes are the writer's.

  That the file is resolved on THIS thread is deliberate: `home`'s root can be
  moved by a test's binding, and a binding is per-thread. A consumer thread that
  resolved it itself would write to whatever root the process had, not the one
  the caller is running under."
  [thread-id run-id kind payload]
  (let [f    (log-file-for thread-id)
        line (str (json/write-str {:ts (System/currentTimeMillis)
                                   :runId run-id :kind kind :payload payload}) "\n")]
    (record/append! thread-id f line)))

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

(defonce ^:private live-runs
  (atom {}))
;; thread-id -> {:run-id ..}: the runs THIS PROCESS has started and not yet finished.
;;
;; PROCESS-LOCAL, AND THAT IS THE WHOLE POINT. A run is a go block on this JVM's
;; threads and nothing about it is on disk, so 'this conversation is being answered
;; right now' is a fact no reader of the log can state: a jsonl with an input and no
;; terminal frame is a run still going OR a process that was stopped, and the two are
;; one file (harness.edge.replay/open-runs can only count lines -- see
;; docs/architecture/home-and-storage.md). The file cannot tell them apart; this can,
;; and the readers that have to choose between them ask HERE rather than guessing.
;;
;; ONE KEY PER THREAD, holding the run id rather than a bare true: `unregister-run!`
;; compares ids instead of dissoc'ing blind, so a run that ends cannot erase a
;; registration that is not its own. TWO RUNS OF ONE THREAD ARE NOW REFUSED AT THE DOOR
;; (`refuse-second-run!`), which is why this comment no longer has to defend against
;; them: a second run cannot start while the first is registered, so this map cannot be
;; re-pointed out from under a live run.

(defn running?
  "Is a run of THREAD-ID alive in this process right now?

  THE FACT THAT IS NOT IN THE RECORD, and the reason it is asked here: an input line
  whose run never terminated is either a run still going or a process that died
  mid-flight. Every reader that has to choose (the sidebar row, the composer's gate,
  the read side's 'may I close this off') asks this instead of inferring from the
  file.

  A THREAD ID IS COMPARED AS A STRING: ids arrive from JSON, from a path segment and
  from a map key, and this is a lookup rather than a validation. Nil and the empty
  string are not session ids anyone mints; they answer false."

  [thread-id]
  (contains? @live-runs (str thread-id)))

(defn- running-run-id
  "The run id THREAD-ID's live run was registered under, or nil. FOR A REFUSAL that has
  to say what is in the way: the client that got a 409 did not choose its run id (the
  server mints it), so the only useful name here is the one already going."
  [thread-id]
  (:run-id (get @live-runs (str thread-id))))

(defn- register-run!
  "Record that THREAD-ID's run RUN-ID is alive in this process, replacing any earlier
  registration for that thread.

  CALLED WHERE THE RUN ACTUALLY STARTS -- beside the `run/start` line, once the
  provider resolved -- and not at the top of `run-agent!`. A run that never started
  (the setup refusal above: no provider, an undeclared modality, a resume naming an
  interrupt this process never parked) reaches no terminal frame through the emitter
  and so has nothing that would ever remove a registration made early: it would leave
  its thread claiming to be running for the life of the process, and every reader of
  that answer -- the composer's gate, the read side's decision to close a record off
  -- would be wrong forever rather than briefly."

  [thread-id run-id]
  (swap! live-runs assoc (str thread-id) {:run-id (str run-id)}))

(defn- unregister-run!
  "Forget THREAD-ID's run RUN-ID -- if that is the run this thread has registered.

  PRESENCE, THEN DISSOCIATE, and no `update-in` with a default in sight: updating a
  removed key RESURRECTS it as a map of nils (.scratch/bash-lifetime paid for that
  lesson), and `running?` would then read a finished run as alive. Comparing the run
  id is the other half -- a bare `(dissoc m k)` would let the older of two runs on one
  thread take the newer one's registration down with it.

  IDEMPOTENT ON PURPOSE: every ending calls it, and one run can reach two of them on
  the way out (a terminal frame, and then a channel that closes; a throw after the
  terminal was dispatched). The second call is a no-op, so 'exactly once' is a
  property of the shape rather than something each call site has to arrange."

  [thread-id run-id]
  (let [k (str thread-id)]
    (swap! live-runs
           (fn [m]
             (if (= (str run-id) (:run-id (get m k)))
               (dissoc m k)
               m)))))

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
      ;; THE RUN'S OWN HALF OF THE CONVERSATION, kept for the moment it ends: the
      ;; session's history is what this run was handed, and these frames are what came
      ;; of it. Collected HERE because this is the one place that sees every frame
      ;; exactly once, and settled at the terminal -- a half-written answer is not a
      ;; turn, so the conversation changes when the run does.
      (swap! state update :frames conj frame)
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
          ;; THE RUN IS OVER THE MOMENT ITS TERMINAL FRAME EXISTS, and the emitter is
          ;; the only place that sees it: this is where the registry stops saying the
          ;; thread is running, rather than at whoever happens to drain the channel
          ;; next (harness.edge.http/live-runs -- the fact the sidebar row reads).
          (unregister-run! thread-id run-id)
          ;; AND THE CONVERSATION IS NOW WHAT IT SAYS. Before the frame is sent, so that
          ;; the client that reads this terminal and immediately sends its next action
          ;; finds the answer already in the history -- the window between 'the run
          ;; ended' and 'its words are in the conversation' is one a fast client would
          ;; otherwise be racing this process through.
          (sessions/settle! thread-id (:frames @state))
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

  IT TAKES THE ENTRIES THEMSELVES, not a run body: what is inspected is what THIS
  action adds, and since ticket 03 that is not a field of the request but the list the
  edge built for it (`:append` -> the entries that actually entered the conversation).

  Returns nil when the run may proceed, so the caller reads as a guard clause."
  [entries provider]
  (let [bad (ag/undeclared-input entries (:input provider))]
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

(defn- run-agent!
  "Drive ONE run: log its input, set the conversation up, and stream what comes back.

  RUN-ID IS AN ARGUMENT rather than a field of INPUT, and after ticket 03 that is the
  whole point: the id names a run in THIS process and is minted at the door
  (`handle-run`), so it is not something the body has and not something a client can
  say. It goes to the record as the log line's own `:runId`, not inside the payload."
  [ch state input run-id origin]
  (let [thread-id (str (:threadId input))
        run-id    (str run-id)
        ;; ONE emitter and ONE converter per run. The converter owns the open-message
        ;; state machine, so building it per event restarts every message id and
        ;; re-emits START frames -- which an AG-UI client treats as fatal.
        emit    (runner thread-id run-id ch state origin)
        convert (ag/outbound thread-id run-id)
        ;; WHAT THIS RUN CONTINUES FROM, read once, here, before anything starts. The
        ;; session holds the conversation (harness.edge.sessions); the client no longer
        ;; sends it. What the client DOES send is this action's own entries (`append`),
        ;; and they go into the session BEFORE the run is set up, so a run that then
        ;; fails (no provider, a refused modality) still leaves the question in the
        ;; conversation -- which is where the record's own input line puts it.
        ;;
        ;; AND THE OPENING CONTEXT IS ONLY EVER TAKEN ONCE, at the birth of the
        ;; conversation -- that is what makes it part of the history every later run
        ;; continues from, instead of a message re-appended (and re-paid for) on every
        ;; run. A session that already has messages has already been born.
        ;;
        ;; `:added` IS WHAT THIS ACTION MEANT, next to the payload the client typed: the
        ;; two differ when a retry sends a message the conversation already has (nothing
        ;; enters) and at birth (the context enters, and the client never sent it). The
        ;; record keeps both because the fold reads `:added` and a reader asking 'why is
        ;; my message not in here' needs to see what was sent.
        history  (sessions/messages thread-id)
        born?    (empty? history)
        entries  (cond-> (vec (:append input))
                   born? (into (when-some [e (ag/context-entry (:context input))] [e])))
        added    (sessions/append! thread-id entries)
        input    (assoc input :added added)]
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
          (let [[provider messages decisions resolved blocks injected]
                (try (let [;; THE PROVIDER IS THE SESSION'S, NOT THE REQUEST'S. It used to
                           ;; be layered with whatever `:provider` the run body carried,
                           ;; which made the selection a thing a CLIENT said per request --
                           ;; and that is the shape ticket 03 retired: choosing a model is
                           ;; an action (`POST /api/model`, which is where the session's
                           ;; override is written and where the change is recorded), and a
                           ;; run is served by whatever that action left in force. `input`
                           ;; is not consulted here at all, which is the point.
                           provider (providers/current-provider thread-id)
                           ;; THE SESSION'S OPENING BLOCKS, read fresh and RENDERED
                           ;; HERE (they fire InstructionsLoaded through the sink the
                           ;; binding above installed, which is why they are read
                           ;; inside this try). They are returned out of it as well as
                           ;; folded into the history, because the edge is what emits
                           ;; their frames -- the kernel never sees them as something it
                           ;; added.
                           blocks (opening-blocks! thread-id)]
                       ;; THE ACTION'S OWN ENTRIES ARE WHAT IS CHECKED, not the
                       ;; conversation: everything already in the history was accepted
                       ;; by the model that produced it, and blaming a model for an
                       ;; image from three turns ago would refuse a run that carries
                       ;; nothing (see ag/undeclared-input on why only user messages
                       ;; are inspected at all).
                       (guard-input-modalities! (:append input) provider)
                       (let [;; THE VENDOR'S THINKING-MODE REQUIREMENT IS MET HERE, on the
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
                             ;;
                             ;; THE DIFF IS TAKEN FOR THE SAME REASON THE KERNEL TAKES IT
                             ;; before every call: a body this session already carried (a
                             ;; skill it loaded in an earlier turn, a job that ended between
                             ;; two runs) is injected here and NOT by the kernel, so the
                             ;; cards for those messages can only come from this side -- and
                             ;; 'every injection is a card' is what the feature promises
                             ;; (see ag-ui/injected-frame).
                             ;; THE CONVERSATION IS THE SESSION'S, and it is exactly what
                             ;; the two bindings above say it is: what the session held
                             ;; when this run began, plus what this action put in it. It is
                             ;; handed over in the client's AG-UI shape, which is the shape
                             ;; this converter takes. The birth context is IN `:added` --
                             ;; so the `context` argument below is for a caller with a
                             ;; conversation that has no beginning yet, which after ticket
                             ;; 03 is nobody: the edge has already put it in.
                             assembled (ag/inbound (into history added)
                                                   (system-prompt/assemble thread-id)
                                                   blocks nil)
                             applied   (project/before-llm assembled thread-id)
                             injected  (subvec applied (count assembled))]
                         [provider
                          (llm/thinking-mode-history applied provider)
                          (resume-decisions (:resume input))
                          ;; THE SESSION'S OWN TIER, with no request layered on it -- the
                          ;; same answer `provider` above resolved, and the map the
                          ;; provider/init and provider/changed lines are written from.
                          (providers/resolve-provider thread-id)
                          blocks injected]))
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
              ;; THE REGISTRY IS TOLD FIRST, deliberately: the line below is then a
              ;; WITNESS for it. Anything that has seen `run/start` in the process log
              ;; (a test, a person reading along) may rely on `running?` answering true
              ;; for that thread -- which is the whole use of the fact, and an ordering
              ;; the other way round would make every such reader race the registry.
              (register-run! thread-id run-id)
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
                          (doseq [frame (into (vec (convert ev))
                                              (when (= :run/start (:type ev))
                                                ;; EVERY MESSAGE THIS RUN OPENS WITH RIDES
                                                ;; WITH ITS START: the instruction blocks and
                                                ;; the injections folded in beside them (a
                                                ;; body an earlier turn loaded, a job that
                                                ;; ended between two runs) are all in the
                                                ;; first model call's history, so a card for
                                                ;; each is due before anything the model says.
                                                ;; They are ordinary user messages to the
                                                ;; provider; this is the only place a person
                                                ;; gets to see them in the conversation column
                                                ;; (see ag-ui/injected-frame for why the client
                                                ;; never sends them back). THE ONE NUMBERING
                                                ;; IS THE HISTORY'S OWN, so the frames come out
                                                ;; in the order the model read them.
                                                (map-indexed
                                                 (fn [i message]
                                                   (ag/injected-frame (str run-id "-open" i) message))
                                                 (into (vec blocks) injected))))]
                            (emit frame))
                          (recur)))))
                ;; THE CHANNEL CLOSED, AND THIS IS WHERE A RUN SAYS WHETHER IT GOT
                ;; TO SAY GOODBYE. The kernel closes it after :run/done, so every
                ;; normal ending emits a terminal frame first and lands here quiet.
                ;; A silent arrival means the run stopped without one -- a crashed
                ;; consumer, a tool thread that died holding a result, a channel
                ;; closed from underneath -- and the record would otherwise end
                ;; mid-sentence with nothing anywhere saying so.
                (when-not (:terminal @state)
                  ;; A CHANNEL THAT CLOSED WITHOUT A TERMINAL IS STILL AN ENDING, and a
                  ;; registration left behind by it is exactly the 'forever running' this
                  ;; registry must not produce. The `when-not` is what keeps that from
                  ;; being a DOUBLE unregistration on the ordinary path (the terminal
                  ;; frame already removed it) -- though the call would be harmless
                  ;; there too: it is idempotent by its own design.
                  (unregister-run! thread-id run-id)
                  (log/warn! :run/events-closed-without-terminal
                             {:thread-id thread-id :run-id run-id
                              :last      (:last @state)}))))))
      (catch Throwable t
        ;; A CRASHED RUN IS NOT A RUNNING ONE, and this catch is the only place that
        ;; knows a run died outside the emitter: without this the thread would claim to
        ;; be running until the process ended. It is a no-op when the throw happened
        ;; before the run started (this catch also covers the setup above it) -- the
        ;; run-id it names is simply not the one registered.
        (unregister-run! thread-id run-id)
        ;; WHAT IT MANAGED TO SAY IS STILL PART OF THE CONVERSATION. A run that died
        ;; mid-answer leaves frames that were already served, and those frames are what
        ;; the model said -- dropping them would make the next run continue from a
        ;; conversation that never had the half-answer the client is looking at. The
        ;; fold is the emitter's own (`sessions/settle!`), so the session gets exactly
        ;; the messages its frames describe, and nothing is invented for the ending.
        (sessions/settle! thread-id (:frames @state))
        (log/error! :run/crashed t {:thread-id thread-id :run-id run-id
                                    :last      (:last @state)})
        ;; THE STREAM IS ENDED RATHER THAN LEFT OPEN: a client parked on a run
        ;; that will never send another frame has nothing to look at and nothing
        ;; to report, which is the state this whole wrapper exists to shorten.
        (try (hk/close ch) (catch Throwable _ nil)))))))


(defn- api-response
  "One JSON answer. NO CORS HEADERS HERE: which origin an answer may name is a
  fact about the REQUEST, and this function is handed a status and a body -- it
  has ninety-odd call sites and none of them knows what page is asking. `handler`
  merges them at the one exit instead, which is also where a reader should look.

  IT SITS ABOVE THE RUN EDGE rather than beside the management routes it mostly serves,
  because the ONE refusal that is not a management route needs it: a run asked for while
  the session is already running is answered as JSON too (`refuse-second-run!`), and the
  alternative -- its own three lines of encoding -- would be a second copy of this shape."
  [status body]
  {:status  status
   :headers {"Content-Type" "application/json; charset=utf-8"}
   :body    (.getBytes (json/write-str body) StandardCharsets/UTF_8)})

(defn- refuse-unknown-session!
  "The answer a client gets when it aims a run at an id this home has never been asked
  to keep. 404, and a sentence naming the id and the way to make one.

  THIS REPLACES A SILENT CREATE, and the silence was the problem rather than the
  creation: the edge used to register whatever thread id arrived (`register-session!`),
  so a typo, a stale bookmark or a hand-written curl produced a NEW conversation that
  looked exactly like the one that was meant. A run is an action on a conversation, and
  an action on something that does not exist is an error -- the one that says which.

  NOT A 409: nothing is in conflict. The client is not early or late, it is aimed at
  something this home does not have."
  [thread-id]
  (api-response
   404
   {:error    (str "no session " (pr-str thread-id) " exists in this home, so this run"
                   " was not started: a run continues a conversation, and this one has"
                   " no beginning here. Create it first -- POST /api/sessions answers a"
                   " fresh threadId when asked with no id -- and send the run again.")
    :threadId thread-id}))

(defn- refuse-retired-messages!
  "The answer a client gets for a run body that still carries the accumulated `messages`.

  THE FIELD RETIRED IN TICKET 03 (ADR 0002 decision 9). It used to be how a client said
  what the conversation was; the session is that now, and what a run carries is the
  entries THIS ACTION adds, under `append`. Refusing rather than ignoring is the whole
  point of retiring it: a body that still sends the history is a client written against
  the old contract, and treating its `messages` as an append would silently double the
  conversation -- the failure mode this feature exists to end, dressed as compatibility."
  []
  (api-response
   400
   {:error (str "this run carries \"messages\", which is not part of the input face any"
                " more: the server holds the conversation, so a run carries only what"
                " THIS ACTION adds -- send those as \"append\".")
    :field "messages"}))

(defn- refuse-served-elsewhere!
  "The answer a client gets when the conversation it is aiming an action at is being
  served by ANOTHER PROCESS of this home. 409, and a sentence naming that process.

  THE CROSS-PROCESS HALF OF 'ONE AUTHORITY PER CONVERSATION' (ADR 0002 decision 7).
  `refuse-second-run!` below is the same rule inside this process; this is the case the
  in-process registry cannot see at all -- another JVM, its own memory table, its own
  record writer, appending to the same file. Nothing in a log line says which process
  put it there, so two writers do not produce a detectable conflict: they produce a
  record that reads as a conversation that happened.

  READ-ONLY IS THE REST OF IT, and this refusal is deliberately only about ACTIONS: the
  listing, the conversation (`rebuild`), `sofar`, the statistics and the trajectory all
  read files, and a second process reading them is exactly what those routes are for.
  What it may not do is WRITE to the conversation -- which is the run, and the one other
  action that moves a session's log file.

  IT NAMES THE PID AND WHEN THE CLAIM WAS TAKEN, because a refusal a person cannot act
  on is not much better than silence: the move is to stop that process (or wait for it
  to exit) and send again. The instance is in the body for whoever is matching this
  against a log line."
  [thread-id held]
  (api-response
   409
   {:error    (str "conversation " (pr-str (str thread-id)) " is being served by another"
                   " harness process (pid " (:pid held) ", which has held it since "
                   (str (java.time.Instant/ofEpochMilli (:since held))) "), so this action"
                   " was refused: two processes serving one conversation write two"
                   " conversations into one record, and nothing in the record says which"
                   " is which. This process can still READ it -- the sidebar, the"
                   " conversation, the statistics -- but not act on it. Stop that process"
                   " (or wait for it to exit) and send again.")
    :threadId thread-id
    :holder   {:pid      (:pid held)
               :since    (:since held)
               :instance (:instance held)}}))

(defn- refuse-second-run!
  "The answer a client gets when it asks for a run of a session this process is already
  running. 409, and a sentence naming the session and the run that is in the way.

  A REFUSAL, NOT A QUEUE, and that is a decision rather than an omission: a queue is a
  mechanism this repo does not have, and inventing one here would mean also inventing
  what happens to a queued run whose client went away. The client is told what is in the
  way; it can ask again when that run ends (which is a fact it can watch -- the session's
  own row says `running`, and the stream's terminal frame is the end of it).

  IT NAMES THE RUN THAT IS GOING rather than the one that was refused, because since
  ticket 03 the client does not choose a run id -- the server mints it -- so a run id
  read out of the refused body would be a name for nothing at all. What a client can act
  on is 'this reply came from a request that was not run, while THAT one is'.

  IT IS ALSO WHAT KEEPS ONE SESSION'S RECORD READABLE. Two runs of one thread interleave
  their frames into one append-only file, and every reader downstream is written for one
  run at a time: `replay/ensure-complete!` counts inputs against terminals, `open-run`
  takes the LAST of each, and the fold reads input lines in file order. Two runs produce
  a record that reads like a conversation that never happened -- not a crash, which is
  worse, because there is nothing to notice."
  [thread-id]
  (api-response
   409
   {:error    (str "this session already has a run in this process, so this run was not"
                   " started: the harness answers one run per session at a time (threadId "
                   (pr-str thread-id) ", the run going is "
                   (pr-str (running-run-id thread-id)) "). Wait for the current run's"
                   " terminal frame, or for this session's row to stop saying it is"
                   " running, and send again.")
    :threadId thread-id
    :running  (running-run-id thread-id)}))

(defn- stream-run
  "Answer a run that the door let through: register it, and stream its frames.

  THE PARSE HAS ALREADY HAPPENED, and so have the door's decisions (`handle-run`): the
  thread id, the RUN ID and the id of the session are all settled before this is called.
  The run id comes from the door rather than the body because it is the SERVER'S now --
  it names a run in this process and it goes into the record, so a client that repeated
  one would collide two runs' frames in a rebuilt conversation (see
  `harness.edge.replay/fold-frames`)."
  [req input run-id]
  (let [thread-id (str (:threadId input))
        ;; WHAT PAGE IS ASKING, read HERE because this is the one route whose
        ;; headers do not come from the ring response: they ride on the first
        ;; frame. `handler` reads the same header for every other route.
        origin    (request-origin req)
        ;; THE RUN'S LIVE STATE, and the only thing the emitter and the close
        ;; handler share. `:on-open` and `:on-close` are two callbacks on
        ;; different threads with nothing else in common, so a fact one of them
        ;; knows and the other must report lives here.
        ;;
        ;; `:frames` IS THIS RUN'S CONVERSATIONAL OUTPUT, collected as it is served so
        ;; that the session can fold it into the conversation the moment the run ends
        ;; (`sessions/settle!`). It is held HERE -- per run, in the one place that sees
        ;; every frame exactly once -- rather than in the session, because a run's frames
        ;; are not the conversation until the run is over.
        state     (atom {:terminal nil :last nil :frames []})]
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
                   {:on-open  (fn [ch] (run-agent! ch state input run-id origin))
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

(defn- handle-run
  "The door to the run edge: read the request, decide whether this is a run this home
  answers, and hand the rest over.

  FIVE DECISIONS, IN THIS ORDER, and the order is the point -- each one is cheaper and
  more basic than the next, and none of them may leave a trace:

    1. THE BODY MUST NOT CARRY `messages`. That field retired (ADR 0002 decision 9): the
       session holds the conversation, and a run carries only what the action ADDS
       (`append`). Refused by name rather than ignored -- see `refuse-retired-messages!`.
    2. THE SESSION MUST EXIST. A run continues a conversation; if this home has no row
       for the id, the run is refused by name and NOTHING is created (ticket 03 removed
       the silent create -- see `refuse-unknown-session!`).
    3. NO OTHER PROCESS MAY BE SERVING IT. This is the cross-process half of 'one
       authority per conversation' (ADR 0002 decision 7), asked of the STORE -- the one
       place two processes of a home agree (`harness.cap.claims`), because the registry
       the next decision reads cannot see another JVM at all. A stale claim -- a process
       that is gone -- is nobody's, and the birth below takes it over.
    4. ONE RUN PER SESSION AT A TIME IN THIS PROCESS. The check is `running?` -- the
       registry the run registers itself in when it actually starts -- and a refused run
       must leave NO trace: the file would take a second `input` line that the reader's
       arithmetic is not written for (see `refuse-second-run!`).
    5. THE RUN ID IS MINTED HERE, not taken from the body. It names a run in this process
       and it goes into the record, and two runs sharing one would interleave into a
       conversation that reads as if it happened once.

  ASKING THE STORE ON EVERY RUN IS THE PRICE OF THE THIRD DECISION, and it is small: one
  row of a local file, against a run that costs a model call.

  WHAT THE FOURTH DECISION CANNOT SEE, said plainly rather than papered over: `running?`
  is the fact that a run STARTED, and the registration is made where the run actually
  begins -- after the provider resolves, deliberately, so that a run which never starts
  cannot leave a thread looking alive forever (see `register-run!`). Two requests
  arriving inside that setup window therefore both get through. Closing it would mean
  taking an admission before the setup and releasing it on every way the setup can fail
  -- one missed release and the session can never run again until the process restarts,
  which is a worse failure than the millisecond it buys.

  A BODY THAT IS NOT JSON IS NOT DECIDED HERE: the reader below throws, and the handler
  turns it into a 400 like it always has (see `handler`)."
  [req]
  (let [input     (json/read-str (slurp (:body req) :encoding "UTF-8") :key-fn keyword)
        thread-id (str (:threadId input))
        run-id    (str (java.util.UUID/randomUUID))
        ;; A LIVE CLAIM ON THIS CONVERSATION, read once for the third decision. A stale
        ;; row -- left by a process that is gone -- answers nil here
        ;; (`harness.cap.claims/holder`); the session's birth takes that over.
        held      (claims/holder thread-id)]
    (cond
      (contains? input :messages)
      (refuse-retired-messages!)

      (not (project/session-exists? thread-id))
      (refuse-unknown-session! thread-id)

      (and (some? held) (not (claims/mine? held)))
      (refuse-served-elsewhere! thread-id held)

      (running? thread-id)
      (refuse-second-run! thread-id)

      :else
      (stream-run req input run-id))))

;; ----------------------------------------------------- the management edge
;;
;; Plain request/response JSON, alongside the streaming AG-UI edge. Small on
;; purpose: each endpoint is a thin wrapper over one harness namespace call.
;; Responses are UTF-8 BYTES, like every other body this server writes -- the
;; JVM default charset is GBK here.


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
  "POST /api/project {threadId?, dir} -- bind the thread to the directory,
  validate FIRST (a missing or non-directory path is a named 400 and leaves
  no trace), then land the project/bound audit line as before -> after, the
  provider/changed style: the previous binding (nil for a first bind) and the
  one now stored, so a session's directory timeline is readable off the log.
  runId is nil on that line because a binding happens OUTSIDE any run. The
  previous binding is read BEFORE binding: bind! overwrites, and the audit
  line is the only place the old value would survive.

  THE ID IS THE SERVER'S TO MINT when the body names none, the same rule
  /api/sessions follows. 'Start a conversation in this directory' is ONE action
  here rather than 'mint a task, then bind it', because the two-call version
  leaves an UNBOUND conversation behind every time the bind fails -- and an empty
  session is the one thing a client may no longer make (ADR 0002 decision 9).
  The answer carries the id it minted, and that is the id to use from here on.
  A body that DOES name one is the rebind direction, unchanged: an id this home
  already knows moves, and the answer says which one it was -- UNLESS another process of
  this home is serving that conversation, in which case this is refused by name: the
  rebind MOVES THE LOG FILE out from under that process's writer (see the cond below).

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
        {:keys [ok bad]} parsed
        ;; WHO IS SERVING THE NAMED CONVERSATION, if anybody: read once, for the third
        ;; decision below.
        named (str (:threadId ok))
        held  (when-not (str/blank? named) (claims/holder named))]
    (cond
      bad
      (api-response 400 {:error "request body is not valid JSON"})

      (str/blank? (str (:dir ok)))
      (api-response 400 {:error "missing dir"})

      ;; AN ACTION ON A CONVERSATION ANOTHER PROCESS IS SERVING, and this one MOVES THE
      ;; LOG FILE (`move-log!` below): a writer in that other process holds the path it
      ;; opened, so a move underneath it splits one conversation across two files. The
      ;; same read-only rule the run edge answers with (`refuse-served-elsewhere!`),
      ;; asked here because binding is the other thing that acts on a log. A body that
      ;; names nobody -- the ordinary 'start a conversation in this directory' -- has
      ;; nothing to be serving, so this cannot refuse it.
      (and (some? held) (not (claims/mine? held)))
      (refuse-served-elsewhere! named held)

      :else
      (let [thread-id (if (str/blank? (str (:threadId ok)))
                        (str (java.util.UUID/randomUUID))
                        (str (:threadId ok)))
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

(defn- sessions-post
  "POST /api/sessions {threadId?} -- make a conversation a session of this home,
  belonging to no project. Answers {:threadId ..}.

  THE ID IS THE SERVER'S TO MINT when the body names none, and that is ticket 03's
  change: an id is the name a conversation is known by HERE, so the process that keeps
  the conversation is the one that names it. A client that made up an id and then asked
  the server to accept it had to be right about a namespace it does not own -- and the
  old arrangement also meant the page could hold an id the store had never heard of,
  which is exactly the state the run edge now refuses (`refuse-unknown-session!`).

  A CLIENT MAY STILL NAME ONE. The body's `threadId`, when it is there, is used as-is:
  the route is idempotent ('make sure this conversation exists'), and a caller that
  already holds an id -- a restored page, a script, a test -- must be able to say it
  without being handed a different conversation. What it may not do is assume the answer
  is the id it sent; the ANSWER is the id to use from here on.

  THE VERB IS 'EXIST', NOT 'BE A TASK'. An id this home has never heard of becomes a
  row with no project, no path and no remembered project -- a task, which is what the
  sidebar draws it as. An id this home ALREADY knows is left exactly as it is, and
  that is not a no-op to apologise for: 'make sure this conversation exists' is true
  the moment it does, and a caller must be able to say it about a session that belongs
  to a project without unbinding it (see `project/register-session!`). The rest of the
  row is the LISTING's business, and the sidebar reads it in the same snapshot it
  reads everything else.

  NO AUDIT LINE: this writes one row in the store and opens no file. Same rule as
  adding a project -- a line is for what happened to a LOG, and nothing here touched
  one. The 400 is for a body that is not JSON; nothing is written on that path either."
  [req]
  (let [parsed (try {:ok (json/read-str (slurp (:body req) :encoding "UTF-8")
                                     :key-fn keyword)}
                     (catch Throwable _ {:bad true}))
        {:keys [ok bad]} parsed
        named  (when ok (:threadId ok))
        thread-id (if (str/blank? (str named))
                    (str (java.util.UUID/randomUUID))
                    (str named))]
    (cond
      bad
      (api-response 400 {:error "request body is not valid JSON"})

      :else
      (api-response 200 {:threadId (project/register-session! thread-id)}))))

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

  The two DISK facts are read FRESH from the file every time rather than kept in
  the store, because they are the two things the store deliberately does not hold: a
  size and an mtime are properties of a record, and the record lives in the file.

  THE THIRD FACT IS NOT ON DISK AT ALL. `:running` is whether the run this session
  is in the middle of is alive in THIS PROCESS -- the one question a file cannot
  answer, since a log that stops without a terminal frame belongs equally to a run
  still going and to a process that was killed (see `live-runs` below). It is read
  from the registry, per request, for the same reason the other two are read per
  request: the answer moves."
  [workspace {:keys [id archived?]}]
  (let [f (home/log-file workspace id)
        exists? (.exists f)]
    {:threadId     id
     :archived     (boolean archived?)
     ;; WHETHER THIS PROCESS IS ANSWERING IT RIGHT NOW -- the third fact, and the one
     ;; the other two cannot give: a file's size and mtime say nothing about whether
     ;; the run that is growing it is alive or its process was killed. It comes from
     ;; the live-runs registry (`running?`) rather than from disk, which is why it is
     ;; HERE and not on /api/threads/<stem>/stats -- that endpoint folds the RECORD,
     ;; and this fact is not in the record. The sidebar and the client that restores a
     ;; session read this one payload, so both learn it in the same load.
     :running      (running? id)
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

(defn- task-row
  "One TASK as the sidebar reads it: the store's id and archive flag, plus what the
  TREE says about this stem -- anywhere under it.

  THE DISK FACTS OF A TASK ARE ASKED OF THE TREE, NOT OF A WORKSPACE, and that is
  the one place this differs from `session-row`. A session with a project has a log
  directory that is a function of that project; a task has no project to derive one
  from. The `.unbound` workspace is where a task's log USUALLY is, and the exception
  is ordinary enough to matter: a session released by `bind! id nil` keeps the file
  it already wrote, in the workspace of the project it used to belong to. Asking the
  tree by stem is also asking exactly what `rebuild` asks when somebody clicks the
  row, so a row can never disagree with the conversation clicking it shows.

  NO FILE, OR MORE THAN ONE, both answer with two nulls. No log yet is a fact (it is
  where every session starts, and it is not the same as a zero-byte file); a stem
  with two logs is a conversation the server refuses to guess about (`replay/locate`
  says why), so the row says nothing rather than picking a half.

  `:running` is not on disk at all -- see `session-row` for the whole argument.
  Both readers ask the same registry, so a task and a project session answer the
  same question the same way."
  [by-stem {:keys [id archived?]}]
  (let [found  (get by-stem id)
        single (when (= 1 (count found)) (first found))]
    {:threadId     id
     :archived     (boolean archived?)
     :running      (running? id)
     :lastActivity (when single (:last-activity single))
     :bytes        (when single (:bytes single))}))

(defn- projects-get
  "GET /api/projects -- the sidebar's listing: every project this home knows, each
  with its sessions, PLUS every task this home knows.

  TWO SOURCES, ONE ANSWER, AND THAT IS THE POINT OF THE ENDPOINT. The store says
  which projects and sessions exist, which session belongs where and which are
  archived; the tree says how big each log is and when it last changed. Neither
  question can be answered from the other side alone -- a directory of jsonl files
  cannot say which project a conversation belongs to (that was the whole reason
  not to migrate the old logs/), and the store must not mirror file sizes. So the
  two are joined here, on the reading side, and each field comes from its owner.

  BOTH HALVES IN ONE ANSWER, because the sidebar is one screen and one snapshot:
  two requests would be two lists that can disagree with each other about which
  conversation exists. The client renders both from this one payload -- including
  the restore that asks whether the session it remembers is still a session, which
  is a question about exactly this listing.

  Archived sessions are INCLUDED and flagged, not filtered: which group to draw
  them in is a decision for the screen, and a listing that quietly dropped them
  would make 'where did my session go' a question with no server-side answer.

  A TASK IS A SESSION WITH NO PROJECT AND NO REMEMBERED PROJECT (`project/tasks`):
  a conversation that never had a home. A session released by removing its project
  is NOT one of them, because it remembers where it was and is waiting for that
  directory to come back -- and an unknown id that has a jsonl in the tree is not a
  session either: the store decides which conversations are listed (GET /api/threads
  is the raw tree view for anyone diagnosing)."
  [_req]
  (let [by-project (group-by :project-id (project/sessions))
        tasks      (project/tasks)
        ;; The tree is walked ONCE, and only when there is a task to ask it about:
        ;; a home with no tasks pays nothing for a listing it does not need.
        by-stem    (if (seq tasks)
                     (group-by :thread-id (replay/threads (home/projects-dir)))
                     {})]
    (api-response 200
                  {:projects (mapv (fn [{:keys [id canonical-path]}]
                                     (let [ws (workspace-for canonical-path)]
                                       {:projectId id
                                        :path      canonical-path
                                        :sessions  (newest-first
                                                    (mapv #(session-row ws %)
                                                          (get by-project id)))}))
                                   (project/projects))
                   :tasks    (newest-first (mapv #(task-row by-stem %) tasks))})))

(def ^:private thread-verbs
  "The verbs this edge serves under /api/threads/<stem>/. A CLOSED SET, and that
  is load-bearing rather than tidiness: the handler dispatches on this shape
  BEFORE the run endpoint, so a path that merely looks like it -- and names a verb
  nobody serves -- has to fall through to the ordinary AG-UI handler rather than
  be answered 405 by a route that was never about it.

  THREE OF THE FIVE ARE GETS: `stats` and `trajectory` only READ the log (a folded
  view of a finished conversation, and the per-turn timeline) and `sofar` reads the
  same file while it is still being written, to hand a client that just landed on a
  session what has arrived. The set stays closed and the 405 stays here -- what
  changed is that the sentence 'every verb on this shape is a POST' is no longer
  true, not where the refusal happens.

  `sofar` NAMES AN INTENT AND NOT A RESOURCE, which is why it is not `history`:
  what it answers is not the conversation (a thing a client could take over) but
  how much of it there IS at this moment -- the same log may answer differently a
  second later, and the answer says so (`replay/sofar`'s `:state`). Rebuild remains
  the door for 'give me the conversation, I will own it'."
  #{"rebuild" "archive" "stats" "trajectory" "sofar"})

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

  AND IT ASKS THE REGISTRY FIRST (2026-09-20). 'No terminal frame' is not evidence
  that a run is dead -- it is equally what a run still being answered looks like --
  and this used to act on the file alone: a client that refreshed into a running
  session (a new runtime, so `isRunning` is false) and opened that thread got a
  TOOL_CALL_RESULT and a RUN_ERROR appended to a run that was still going, which
  then wrote its own RUN_FINISHED later. TWO TERMINALS IN ONE RUN, and frames in
  between: a lie in a record whose only asset is that every line of it is true.
  So a run this process is answering is left ALONE (`harness.edge.http/running?`),
  and the caller of this function -- a rebuild, today -- goes on to refuse the log by
  name, which is the honest answer for a conversation that has not stopped yet.

  BEST EFFORT ON PURPOSE. A corrupt log throws here and is left to `rebuild` to
  refuse by name -- repairing is what this does, and the reader that follows says
  precisely what is wrong with a log nobody can repair."
  [stem ^java.io.File path]
  (when-not (running? stem)
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
        nil))))

(defn- record-health
  "What the record writer says about THREAD-ID, or nil when there is nothing to say.

  NIL IS THE ORDINARY ANSWER, and absence is the client's 'fine': a session whose
  bytes have all reached the record has no story, and a `:record {:state \"ok\"}` on
  every answer would be a field nobody acts on. What the field exists for is the
  one case ADR 0002 decision 6 refuses to leave silent -- the writer could not put
  the bytes on disk, and the browser has to say so (ticket 02).

  IT IS READ FROM MEMORY, not from the file. The whole point of the fact is that
  the file is BEHIND (or, here, unwritable), so a reader that asked the file could
  learn nothing; `harness.edge.record` is where the failure lives."
  [thread-id]
  (when-some [d (record/degraded thread-id)]
    {:state   "degraded"
     :reason  (:reason d)
     :pending (:pending d)
     :at      (:at d)}))

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
                  (close-off-open-run! stem (:ok located))
                  ;; READ YOUR OWN WRITE. The closing frames are the thing that
                  ;; makes the file whole, and the rebuild below reads the file --
                  ;; through the queue they would not be there yet, and this route
                  ;; would refuse a log it had just repaired. Draining is what
                  ;; makes the repair and the read one act (ticket 02).
                  (record/flush! 5000))
        result  (when (nil? (:error located))
                  (try {:ok (replay/rebuild (:ok located))}
                       (catch Throwable t {:error (ex-message t)})))]
    (cond
      (some? (:error located))
      (api-response 404 {:error (:error located)})

      (some? (:error result))
      (api-response 400 {:error (:error result)})

      :else
      (let [{:keys [messages context]} (:ok result)
            health (record-health stem)]
        (log! stem nil "session/rebuilt" {:messages (count messages) :via "http"})
        (api-response 200 (cond-> {:threadId stem
                                   :messages messages
                                   :context  (or context [])}
                            (some? health) (assoc :record health)))))))

(defn- sofar-get
  "GET /api/threads/<stem>/sofar -- what has been recorded of this conversation so
  far, plus the one thing the record cannot say about itself (whether this process is
  still answering it).

  THE READ A CLIENT POLLS, WHICH IS WHY IT WRITES NOTHING. `rebuild` is the other
  door on the same file and it is a different act: it hands the conversation over
  (and, for a log that was cut off, it CLOSES THE RUN OFF first, which is a write).
  A reader that wanted to look at a session while it is being answered cannot use
  that door -- it would be repairing the file it is reading, on every poll, and it
  would be refused anyway (a live run reads as a truncated log to `ensure-complete!`).
  So this one folds what is there and says how much of it there is.

  THREE ANSWERS, and the third is a refusal:

    run in flight here  200, state :running, with the open runs named -- the client
                        shows what has arrived and knows not to send
    parked              200, state :parked, with the interrupts the newest run
                        ended on (the client's card, ticket 06)
    settled             200, state :settled, the same message list rebuild gives
    cut off             400 BY NAME, pointing at rebuild: nothing is folded and
                        nothing is written -- a run that ended without a terminal and
                        is not running here needs a human decision (rebuild closes it
                        off), and this route is not where that decision is taken

  THE TWO HALVES OF THE LIVENESS QUESTION ARE ANSWERED BY THEIR OWNERS: the file
  says whether a run has a terminal frame (`replay/sofar`), and the process says
  whether that run is still being answered (`running?`, the live-runs registry).
  Neither is inferred from the other -- which is the whole point of having both."
  [req stem]
  (let [located (try {:ok (replay/locate (home/projects-dir) stem)}
                     (catch Throwable t {:error (ex-message t)}))
        read    (when (nil? (:error located))
                  (try {:ok (replay/sofar (:ok located))}
                       (catch Throwable t {:error (ex-message t)})))]
    (cond
      (some? (:error located))
      (api-response 404 {:error (:error located)})

      (some? (:error read))
      (api-response 400 {:error (:error read)})

      :else
      (let [{:keys [messages context state open-runs interrupts]} (:ok read)
            health (record-health stem)]
        (cond
          (= :unfinished state)
          (if (running? stem)
            (api-response 200 (cond-> {:threadId stem
                                       :messages messages
                                       :context  (or context [])
                                       :state    "running"
                                       :openRuns open-runs}
                                (some? health) (assoc :record health)))
            ;; NOTHING IS WRITTEN HERE, not even the repair: closing a record off is
            ;; `close-off-open-run!`'s job, it belongs to whoever asks to CONTINUE the
            ;; conversation, and a poll must never be the thing that changes the file.
            (api-response 400 {:error (str "this conversation's log ends mid-run ("
                                        (str/join ", " open-runs) ") and this process is"
                                        " not running it: the run was cut off. POST"
                                        " /api/threads/" stem "/rebuild to close it off and read"
                                        " it back, or start a new session.")}))

          :else
          (api-response 200 (cond-> {:threadId stem
                                     :messages messages
                                     :context  (or context [])
                                     :state    (name state)}
                              (seq interrupts) (assoc :interrupts interrupts)
                              (some? health)   (assoc :record health))))))))

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

    ;; ONE CONVERSATION BECOMES A SESSION OF THIS HOME, under the collection that
    ;; names the thing: the `sessions` table's own noun. It sits next to
    ;; `/api/project(s)` deliberately -- those two move a conversation to a DIRECTORY,
    ;; and this one is the other statement a caller can make about a conversation:
    ;; that it exists at all.
    (= "/api/sessions" (:uri req))
    (case (:request-method req)
      :post (sessions-post req)
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
        [:get "sofar"]    (sofar-get req stem)
        (api-response 405 {:error "method not allowed"}))
      (if-some [{:keys [verb stem]} (stem-verb-route "providers" provider-verbs (:uri req))]
        (case [(:request-method req) verb]
          [:post "remove"] (remove-provider-post stem)
          (api-response 405 {:error "method not allowed"}))
        (if-some [{:keys [verb stem]} (stem-verb-route "projects" project-verbs (:uri req))]
          (case [(:request-method req) verb]
            [:post "remove"] (remove-project-post stem)
            (api-response 405 {:error "method not allowed"}))
          ;; NOTHING ELSE. This used to be `(handle-run req)` -- the run endpoint
          ;; was the fallback for every unmatched path -- so a typo in a management
          ;; route arrived at the kernel as a run with no RunAgentInput in it, and
          ;; was answered with whatever that produced. A path this table does not
          ;; know is now exactly that, and says so.
          ;;
          ;; THE BUILT PAGE SITS HERE, in front of the 404 and behind every route
          ;; above it. It answers only GET/HEAD outside `/api` (harness.edge.ui
          ;; refuses the rest), so nothing on the management edge can be shadowed
          ;; by a file, and a request the table does not know is still a 404 --
          ;; one that names the missing build when the page itself is what was
          ;; asked for.
          (or (ui/answer req)
              (ui/absent req)
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

  TWO OPTIONS, and both are facts about the process the process did not choose
  (`:ui-origin` names the one page answered by name, `:ui-dist` names the built
  page this server puts in front of a browser; see `named-origin` and
  `harness.edge.ui/serve-from!`). Either may be absent, which is the default:
  `ui/dist` under the working directory, and `http://localhost:5173`.

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
                   (cap-mcp/install!)]]
    ;; THE RECORD WRITER COMES UP WITH THE CAPABILITIES, because it is one: every
    ;; line this process produces goes through it (`harness.edge.record`), and the
    ;; carry-back that must precede a session's first line is ITS step -- so the
    ;; edge hands its own carry-back over here, where a capability is told which
    ;; implementation it runs with. It has no teardown: one writer serves every
    ;; server this process starts, and a suite that starts a hundred must not
    ;; leave a hundred writer threads behind (or stop the one it has).
    (record/prepare-with! carry-back!)
    (record/start!)
    ;; THE SESSION TABLE IS LIVE FROM HERE, and it needs both of its outside facts.
    ;; THE PIN FIRST: a session whose bytes are not all on disk may not be put away, or
    ;; 'put away' would mean rebuilding from a record that is behind it -- silently,
    ;; which is the failure ADR 0002 decision 5 exists to refuse. The answer belongs to
    ;; the writer, so it is handed over rather than guessed (`record/pending?`).
    (sessions/watch-unflushed! record/pending?)
    ;; AND THE SWEEPER, because the table holds conversations now: without it, every
    ;; session this process has ever been asked about would be held until it exits.
    (sessions/start!)
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
    ;; ...AND THE PAGE THIS PROCESS SERVES, settled in the same breath and for the
    ;; same reason: it is a fact about the process, chosen by whoever started it,
    ;; and it has to be in place before the socket opens. `contains?` rather than
    ;; `or`, so an explicit `:ui-dist nil` really is 'serve no page' instead of
    ;; falling back to the default -- the same trap `:ui-origin` has above.
    (ui/serve-from! (if (contains? opts :ui-dist) (:ui-dist opts) (ui/default-dir)))
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
        ;; WHAT IS AT `/`, which the line above does not say and a person starting
        ;; this process wants to know before opening a browser: the built page and
        ;; the directory it came from, or the fact that there is none.
        (println (ui/banner))
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
  other the same way.

  `--ui-dist DIR` SAYS WHERE THE BUILT PAGE IS, and without it the page is
  `ui/dist` under the working directory -- which is where `npm run build` puts it
  and where `clojure -M:run` (run from the repo root, the only directory whose
  `deps.edn` gives that command its classpath) will look. A directory with no
  `index.html` in it is 'no page', not an error: this process then serves `/api`
  alone, exactly as it did before it learned to serve a page at all."
  [& args]
  (let [option (fn [name] (some (fn [[k v]] (when (= k name) v)) (partition 2 1 args)))
        asked  (option "--port")
        origin (option "--ui-origin")
        dist   (option "--ui-dist")]
    (start! (cond-> {}
              asked  (assoc :port (Integer/parseInt (str asked)))
              origin (assoc :ui-origin (str origin))
              dist   (assoc :ui-dist (str dist))))
    @(promise)))
