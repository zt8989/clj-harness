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
  log-dir-for). THE FILE HAS EXACTLY TWO KINDS OF ROW (`.scratch/jsonl-two-kinds`,
  拍定 2026-09-21): `message` -- one line per element of the messages array the
  model was handed, in the provider's own shape, VERBATIM, its identity (`id`) and
  its `source` on the envelope -- and `event`, every other fact; the `input` row
  that used to carry the whole RunAgentInput went with 票 02. The facts:
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
    \"delegation\" -- ONE PER DELEGATION, written to the PARENT session's record
                   by the delegating tool thread, at the moment the subagent's
                   conversation is opened -- before the subagent's own first
                   row lands, so a card in the parent's conversation can be
                   opened while the subagent is still running (that is the
                   whole point of writing it first). It names the call that
                   made it (:toolCallId, the key the card pairs on), which
                   subagent ran (:subagent), and the child session's id
                   (:threadId, the stem every read route takes). A RECORD
                   ABOUT the parent's conversation, never a message or a frame
                   OF it: it is one of the CUSTOM frames the harness writes
                   about itself, so no reader that folds a conversation
                   (`harness.edge.replay/entries`) can see it -- asserted in
                   the delegation-line test through a real rebuild, not
                   assumed.

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
            [harness.infra.language :as language]
            [harness.infra.log :as log]
            [harness.infra.logging :as logging]
            [harness.cap.providers :as providers]
            [harness.kernel.llm :as llm]
            [harness.kernel.loop :as loop]
            [harness.cap.claims :as claims]
            [harness.cap.preamble :as preamble]
            [harness.cap.project :as project]
            [harness.edge.replay :as replay]
            [harness.infra.stream :as stream]
            [harness.edge.sessions :as sessions]
            [harness.edge.mux :as mux]
            [harness.edge.normalized :as normalized]
            [harness.edge.host :as host]
            [harness.edge.context :as context]
            [harness.edge.pressure :as pressure]
            [harness.edge.projection :as projection]
            [harness.edge.compaction :as compaction]
            [harness.edge.llm-timeout :as llm-timeout]
            [harness.edge.prune :as prune]
            [harness.edge.stats :as stats]
            [harness.edge.turn :as turn]
            [harness.edge.trajectory :as trajectory]
            ;; The built page, when this process has one: `ui/dist`, served at the
            ;; root. See harness.edge.ui for why the server carries it at all.
            [harness.edge.ui :as ui]
            ;; skill-picker 的 /api/skills 用它（那一票在 main 上，本分支没有）：
            [harness.cap.skills :as skills]
            [harness.cap.system-prompt :as system-prompt]
            [harness.cap.instruction-updates :as instructions]
            [harness.cap.jobs :as jobs]
            [harness.cap.subagents :as subagents]
            [harness.cap.todos :as todos]
            [harness.cap.frame-bus :as frame-bus]
            [harness.cap.frame-bus :as frame-bus]
            [harness.cap.hooks :as cap-hooks]
            [harness.cap.mcp :as cap-mcp]
            [harness.cap.spill :as spill]
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
  ;; IT DOES NOT SERIALIZE APPENDS ANY MORE. One line is one JSON object and a reader
  ;; parses the file line by line, so a half-written line is not a smaller record, it
  ;; is a broken file -- but that is the record writer's job now: one consumer thread
  ;; appends every line (`harness.infra.stream`, ticket 02), so there is exactly one
  ;; writer by construction and nothing to serialize.
  ;;
  ;; WHAT IT STILL GUARDS IS WHERE A LINE GOES. The writer serializes the bytes, not
  ;; the PLACEMENT: `log!` resolves a session's File from its binding on a caller's
  ;; thread, while `project-post` both changes that binding (`project/bind!`) and MOVES
  ;; the file it already has (`move-log!`). Those two must not interleave, or a line is
  ;; addressed to a workspace the conversation has just left and the move carries the
  ;; file out from under it -- one conversation in two files, which replay, rebuild and
  ;; the eval reader all refuse to reconstruct. That is the ordinary case now, not an
  ;; exotic one: the sidebar binds a session at its first send, i.e. while a run is
  ;; writing. Both critical sections take this lock (see `log!` and `project-post`), and
  ;; it is held around the resolution and the hand-over -- never across the write.
  ;;
  ;; THE ORDER IS lock -> store, never the reverse: `log-file-for` reads the store under
  ;; it, so nothing that holds a store transaction may take it, and no `log!` call in
  ;; this namespace runs inside a `with-transaction`. NOTHING HERE WAITS ON THE WRITER
  ;; either -- the consumer takes no lock of ours -- so a bind can never deadlock against
  ;; the very run whose record it is moving; the drain the bind takes before a moving
  ;; `move-log!` is what closes the one hole the lock cannot (a line handed over just
  ;; before it, still queued) and it is bounded for the same reason. What would break
  ;; without it: the bind-vs-writer case this lock exists for
  ;; (`a-bind-that-arrives-while-the-writer-is-mid-run-leaves-one-file`).
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

  THIS IS THE ONE EXPRESSION THAT TURNS A PROJECT INTO A DIRECTORY, and since the
  sidebar's listing stopped touching the tree (`projects-get`) the writer is its
  only caller -- which is worth knowing before a second one is added: the two must
  not drift. It reads from the CANONICAL path -- the project's identity, not the
  spelling a session was bound with -- so every session of one project lands in
  one workspace however that directory was spelled."
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
  edge names ids the server minted (and the sidebar's registry may not know one
  yet -- a task is registered when it is created, but the two stores are not the
  same table), and its log goes to the reserved workspace."
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

(defn- row-of
  "THE RECORD HAS TWO KINDS OF ROW (`.scratch/jsonl-two-kinds`, 拍定 2026-09-21): `message` is
  what a person said or an LLM returned, and `event` is every other fact. An AG-UI frame we
  put on the wire is an `event` whose payload IS the frame; everything the harness knows on
  its own -- a provider change, a tool's three moments, the plan an action was given -- is an
  `event` whose payload is a **CUSTOM frame** named after that fact. Two reasons for the
  wrapping rather than a third row type: a client already ignores a CUSTOM frame it does not
  know, and the record then holds exactly the vocabulary it can be rebuilt into.

  `ts` and `runId` sit at the TOP LEVEL, outside the payload, so the payload stays verbatim
  -- byte for byte what the vendor saw -- while the row still says when and for which run."
  [kind payload]
  (cond
    (= "message" kind) {:type "message" :payload payload}
    (= "event" kind)   {:type "event"   :payload payload}
    :else              {:type "event"
                        :payload {:type "CUSTOM" :name kind :value payload}}))

(defn- carry-audit!
  "One audit line about a leftover unbound segment, appended to F -- the
  conversation's own file. runId is nil: this happens on the way to a record, not
  inside a run. A caller that cannot afford this to throw swallows it."
  [^java.io.File f kind payload]
  (spit f (str (json/write-str (merge {:ts (System/currentTimeMillis) :runId nil
                                      ;; THE RECORD'S OWN FURNITURE TOO: it is a line about the
                                      ;; record (a leftover segment it carried), not a run's doing
                                      :producer :record}
                                      (row-of kind payload)))
               "\n")
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
  (`harness.infra.stream/prepare-with!`). Nothing to do answers nil.

  CALLED BY THE RECORD WRITER'S CONSUMER BEFORE EVERY LINE (`harness.infra.stream`),
  which is this process's only writer -- that single writer is why this needs no lock
  of its own. (The one thing it does not serialize is where a line is ADDRESSED, and
  that is decided before the queue, in `log!`; see `log-lock`.) THE CALL IS PER LINE,
  THE WORK IS AT MOST ONCE:
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


;; ------------------------------------------------------------ the file header

(def record-format
  "THE RECORD'S FORMAT VERSION (ticket 06 of `.scratch/event-persistence`). It describes the FILE's
  shape -- which row kinds exist and which envelope fields a reader must expect -- and it went to 2
  in the same commit that started writing a HEADER LINE, because a reader that has to meet a
  new-first-line is a reader that must be able to say so. It did NOT move for the text snapshot
  (ticket 03's `text/snapshot`): that is a new CUSTOM NAME, and a reader that has never heard of one
  ignores it by the vocabulary it already has (`kind`/`fact-frame?`), not by a version."
  2)

(defonce ^:private header-fn
  ;; (fn [thread-id] -> payload-map | nil) -- nil means no header line.
  (atom nil))

(defn set-header!
  "Say what a record's FIRST line carries. THE SEAM the same family always uses (`prepare-with!`,
  `set-sink!`, `set-forcer!`): the writer knows WHEN a file's first line is due -- before any other
  line of that file -- and the SHAPE of what it says belongs to the adapter that owns the row
  vocabulary (`harness.edge.http/row-of`), so the composition root installs it. F answers nil for
  'no header' (which turns the whole thing off, the way a test wants)."
  [f]
  (reset! header-fn (or f (constantly nil))))

(defn reset-header! []
  (reset! header-fn (constantly nil)))

(defonce ^:private headers-written
  ;; file path -> true. ONE LINE PER FILE, EVER: the header is the file's, not the
  ;; thread's -- a conversation that MOVED (move-log!) is a new file and gets a new one,
  ;; while two threads of one conversation (they exist: a run's and a hook's) share theirs.
  (atom #{}))

(defn reset-headers!
  "Forget which files have their header. FOR TESTS." []
  (reset! headers-written #{}))

(defn- header-line!
  "THE FIRST LINE OF F, if one is owed: the adapter's payload under the same envelope every row
  has. Answers the line or nil -- and NOTHING THROWS: a missing header is not a fact a reader
  stumbles over (it reads the row as the conversation's own first line, as it always has), so
  the worst case is the old world, not a broken one.

  THE FILE IS THE AUTHORITY AND THE ATOM IS ONLY THE LOCK (owner, 2026-09-28).
  `headers-written` is PROCESS MEMORY, so a session continued after a RESTART looked
  headerless to the new process and got a SECOND `record/header` appended in the middle of its
  own record (measured on a forked session: four of them, one per restart). A file that
  already has bytes is named, whoever wrote them; the atom still makes the check and the
  write ONE operation, so two threads of one conversation cannot both write one."
  [^java.io.File f]
  (try
    (let [p        (.getAbsolutePath f)
          on-disk? (and (.exists f) (pos? (.length f)))
          [before _] (swap-vals! headers-written #(if (contains? % p) % (conj % p)))]
      (when (and (not on-disk?) (not (contains? before p)))
        (when-some [payload (@header-fn (str/replace (.getName f) #"\.jsonl$" ""))]
          (str (json/write-str (merge {:ts (System/currentTimeMillis) :runId nil
                                      ;; THE RECORD'S OWN FURNITURE, not a run's doing
                                      :producer :record}
                                      (row-of "record/header" payload)))
               "\n"))))
    (catch Throwable _ nil)))
(def ^:dynamic *producer*
  "WHO IS WRITING, for the row's own `:producer` (`.scratch/record-stream` ticket 03).

  A ROW SAYS WHERE IT CAME FROM, and the readers need it: 'is this line part of what a call was
  HANDED, or something the run produced?' is the question `harness.edge.trajectory` used to answer
  by POSITION -- and position stopped being an answer the moment the record began to be written as
  the run happens (a resume answers before it submits). Bound by the two chokepoints that write a
  batch -- the request side (`:request`) and the kernel's messages (`:kernel-message`) -- and
  worked out from the KIND when nobody said (`producer-of`)."
  nil)

(def ^:dynamic *written-rows*
  "THE ROWS ONE RUN HAS WRITTEN, as `[line row]` pairs, while a caller that wants them binds this
  to an atom -- the agent route and a delegation each bind one for their own run, and `log!` fills
  it. NIL IS 'NOBODY IS COLLECTING', which is the ordinary state of a process that only writes.

  WHAT READS IT IS `harness.edge.replay/entries-of-rows` AT THE END OF THE RUN: an entry's number
  is decided by the rows (`message` rows close the frame group before them), so the writer that has
  its own rows can reproduce the READER's numbers exactly instead of handing the whole run one
  number (`.scratch/entry-numbering/` ticket 01). It holds the payloads of ONE run and dies with
  the run's own state -- the same lifetime `:frames` already has."
  nil)

(defn- entry-lines
  "THE ROWS A RUN WROTE -> `{entry-id line}`, folded by the READER's own fold (`replay/entries-of-rows`).
  The ids are the frames' (`<run-id>-mN`), which is what the session's entries carry; a row the fold
  answers without one is skipped, because this map is keyed by name and nothing else."
  [rows]
  (into {} (keep (fn [e] (when-some [id (get-in e [:message :id])] [id (:seq e)])))
        (replay/entries-of-rows rows)))

(defn- producer-of
  "The default producer of a row of KIND carrying PAYLOAD: an audit row derived from a kernel
  event, a CUSTOM frame (a fact the harness stated on its own), or a plain wire frame. A `message`
  row is the RUN's unless the caller says otherwise -- the request batch is the one that does."
  [kind payload]
  (case kind
    "message" :kernel-message
    "event"   (if (and (map? payload) (= "CUSTOM" (:type payload))) :fact :frame)
    :kernel-event))

(defn- log!
  "The line goes to the record writer, which appends it off this thread's own
  path (`harness.infra.stream`). NOTHING HERE TOUCHES THE FILE: the File is
  resolved here -- where a session's record belongs is a fact about its project,
  and this is the namespace that joins the two -- and the bytes are the writer's.

  That the file is resolved on THIS thread is deliberate: `home`'s root can be
  moved by a test's binding, and a binding is per-thread. A consumer thread that
  resolved it itself would write to whatever root the process had, not the one
  the caller is running under.

  AND THE RESOLUTION IS THE HALF THE SINGLE WRITER DOES NOT DO, which is why it
  happens under `log-lock`: the appends cannot interleave, but WHERE a line goes is
  decided here, on a caller's thread, while a bind (`/api/project`) rewrites the
  binding and MOVES the file (`move-log!`). Resolved outside the lock, a line could
  be addressed to a workspace the conversation has just left, and the move would
  then carry the file out from under it -- one conversation, two files. The lock is
  released as soon as the line is handed over; it is never held across the write.

  EXTRA IS ENVELOPE, NOT PAYLOAD: fields that belong to the ROW rather than to the thing it
  carries -- `:source`, which says who put a message into the array the model read, and the
  system prompt's `:hash`. They sit beside `ts`/`runId` because the payload must stay
  verbatim (owner, 2026-09-21: a `message` row is an element of the messages array the model
  was handed, and the envelope is where the record says whose element it was).

  LANDS, WHEN GIVEN, IS CALLED WITH THE RECORD OFFSET THE LINE GOT once it is on disk
  (`harness.infra.stream/push!`), which is how a conversation's entries are numbered:
  the edge attaches it to the lines that CARRY entries -- each of an action's own
  `message` rows, and the terminal frame of its run -- and hands the number to
  `harness.edge.sessions/land!`."
  ([thread-id run-id kind payload]
   (log! thread-id run-id kind payload nil))
  ([thread-id run-id kind payload lands]
   (log! thread-id run-id kind payload lands nil))
  ([thread-id run-id kind payload lands extra]
   (let [row  (merge {:ts (System/currentTimeMillis) :runId run-id}
                     extra
                     ;; WHO PRODUCED IT, ON THE ROW: the envelope may say, then whoever is bound
                     ;; (a batch says it about itself), then the kind's own default.
                     {:producer (or (:producer extra) *producer* (producer-of kind payload))}
                     (row-of kind payload))
         line (str (json/write-str row) "\n")
         ;; THE OFFSET COMES BACK FROM THE WRITE ITSELF (ADR 0007), and this is the caller that
         ;; hands it out: the fact families of ADR 0006 are stamped with it, and `lands` -- when
         ;; it was given -- has already been called with it from inside `append!` (after THAT
         ;; namespace's lock, never inside this one).
         ;; THE FILE'S OWN FIRST LINE COMES FIRST (ticket 06): one header per FILE, before any other
         ;; line of it -- decided inside the same lock that resolves the file, because 'which file'
         ;; and 'does that file have its header yet' are one question, and a move (move-log!) is a
         ;; NEW file, which gets a NEW header. THE APPEND ITSELF IS OUTSIDE THE LOCK, exactly like
         ;; the line below (ADR 0007) -- only the decision is made under it.
         offset (let [f (locking log-lock
                          (let [f (log-file-for thread-id)]
                            (when-some [h (header-line! f)]
                              (stream/push! thread-id f h))
                              f))]
                  (stream/push! thread-id f line lands {:producer (get row :producer) :row row}))]
     ;; WHERE THE LINE GOES IS STILL DECIDED UNDER `log-lock` -- a bind rewrites the binding and
     ;; MOVES the file (`move-log!`), and a line resolved outside the lock could be addressed to
     ;; a workspace the conversation has just left -- WHILE THE WRITE ITSELF HAPPENS OUTSIDE IT,
     ;; INSIDE `stream/push!` (ADR 0007). The split matters for one concrete reason: `lands`
     ;; takes the SESSION's lock, and a `lands` called while holding this one is a lock-order
     ;; inversion (`.scratch/event-persistence/spec.md`, '锁要往下搬一层').
     ;; THE OFFSET COMES BACK FROM THE WRITE ITSELF (ADR 0007), and this is the caller that
     ;; hands it out: the fact families of ADR 0006 are stamped with it, and `lands` -- when it
     ;; was given -- has already been called with it from inside `append!` (after THAT
     ;; namespace's lock, never inside this one).
     ;; THE READERS ARE NOT TOLD HERE ANY MORE (ticket 04 of `.scratch/record-stream`): they are
     ;; attached to the STREAM itself, in two shapes -- a live step's doorbell is the process-wide
     ;; listener `harness.kernel.session` installs at load, and a window's mark rides the same one
     ;; -- so the one write path WRITES and knows nobody. Adding a consumer is attaching a reader
     ;; (`harness.infra.stream/listen!` / `listen-every!`), never editing this function.
     ;; AND A RUN THAT IS COLLECTING ITS OWN ROWS GETS THIS ONE (`*written-rows*`): the ROW, not the
     ;; bytes, and the line it landed on -- the two things `harness.edge.replay/entries-of-rows`
     ;; folds to answer the same numbers a window gives (`.scratch/entry-numbering/` ticket 01).
     (when (some? *written-rows*) (swap! *written-rows* conj [offset row]))
     offset)))

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
        ;; LET GO OF EVERY HANDLE FIRST: the next thing this does is RENAME the file, and Windows
        ;; refuses to rename one that is open (ADR 0007 decision 3's price, paid on the one
        ;; occasion it costs anything -- a person re-binding a project).
        (stream/release-handles!)
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

;; A RUN'S LIVENESS IS THE SESSION TABLE'S NOW (ticket 05, merging ticket 01's correction
;; 3). This namespace used to keep `live-runs`, a second registry of 'this process is
;; answering thread X', beside the session table's own `:runs` pin -- two homes for one
;; fact, and two ways for it to be wrong. They were always the same question: a run is
;; this process's, it pins the session so the sweeper leaves it alone, and it is what the
;; sidebar row, the composer's gate, the door's second-run refusal and the read side's 'may
;; I close this off' all ask. `harness.edge.sessions` owns it; the answers below are the
;; edge's spelling of it, kept because every caller here reads better for them.

(defn running?
  "Is a run of THREAD-ID alive in this process right now?

  THE FACT THAT IS NOT IN THE RECORD, and the reason it is asked here: a run whose
  opening `message` row has no terminal frame is either a run still going or a process
  that died mid-flight. Every reader that has to choose (the sidebar row, the composer's gate,
  the read side's 'may I close this off') asks this instead of inferring from the
  file.

  A THREAD ID IS COMPARED AS A STRING: ids arrive from JSON, from a path segment and
  from a map key, and this is a lookup rather than a validation. Nil and the empty
  string are not session ids anyone mints; they answer false."
  [thread-id]
  (sessions/running? thread-id))

(defn- running-run-id
  "The run id THREAD-ID's live run was registered under, or nil. FOR A REFUSAL that has
  to say what is in the way: the client that got a 409 did not choose its run id (the
  server mints it), so the only useful name here is the one already going."
  [thread-id]
  (sessions/running-run-id thread-id))

(defn- register-run!
  "Record that THREAD-ID's run RUN-ID is alive in this process.

  CALLED WHERE THE RUN ACTUALLY STARTS -- beside the `run/start` line, once the
  provider resolved -- and not at the top of `run-agent!`. A run that never started
  (the setup refusal above: no provider, an undeclared modality, a resume naming an
  interrupt this process never parked) reaches no terminal frame through the emitter
  and so has nothing that would ever remove a registration made early: it would leave
  its thread claiming to be running for the life of the process, and every reader of
  that answer -- the composer's gate, the read side's decision to close a record off
  -- would be wrong forever rather than briefly."
  [thread-id run-id]
  (sessions/run-started! thread-id run-id)
  ;; AND THE HOST HEARS: a run starting is one of the facts a sidebar draws, and it
  ;; does not ride on any one conversation's window (that is what `events.host` is
  ;; for). The ring itself lives in `sessions/run-started!` now -- the verb that moves
  ;; both the registry and the column is the one that says the row changed -- so this
  ;; wrapper is one call, not three.
  nil)

(defn- unregister-run!
  "Forget THREAD-ID's run RUN-ID -- if that is the run this thread has registered.

  IDEMPOTENT ON PURPOSE: every ending calls it, and one run can reach two of them on
  the way out (a terminal frame, and then a channel that closes; a throw after the
  terminal was dispatched). The second call is a no-op, so 'exactly once' is a
  property of the shape rather than something each call site has to arrange."
  [thread-id run-id]
  (sessions/run-finished! thread-id run-id)
  ;; AND THE HOST HEARS for the same reason: the row's "running" dot -- rung by
  ;; `sessions/run-finished!` itself, with the column it now shares.
  nil)

(defn entry-source
  "WHO PUT THIS MESSAGE INTO THE ARRAY THE MODEL READ, for a message an ACTION put in --
  the value the row's envelope carries under `:source` (owner, 2026-09-21: a `message` row
  IS an element of that array, so the row says whose element it was; the table of names is
  `.scratch/jsonl-two-kinds` 票 04's).

  THE TWO THE EDGE WROTE ITSELF ARE NAMED BY THE IDS IT MINTED, which is the same reading
  `ag/injected?` takes: the conversation's opening blocks (`ag/opening-entry?`, ids
  `session-opening-<i>`) are the `opening`, and the session's own context entry
  (`ag/context-entry-id`) is the `injection`. EVERYTHING ELSE THAT ENTERED CAME FROM THE
  CLIENT -- the action's own `:append`, which is the only other thing `sessions/append!`
  is ever handed by this file."
  [message]
  (cond
    (ag/opening-entry? message)                  "opening"
    (= ag/context-entry-id (:id message))       "injection"
    :else                                       "client"))

;; THE FACT FAMILY'S WRITER AND THE NUMBERS ITS `model/end` CARRIES (ADR 0006): both are defined
;; with the downlink machinery, far below the emitter that calls them.
(declare family-send! live-numbers-slice numbers-snapshot task-send!)

(defn speaks-for-a-person?
  "Whether MESSAGE is something A PERSON said, as the record spells it: a `user` message whose
  envelope says the CLIENT put it in the array (`entry-source`).

  THIS IS THE TURN'S OPENING CONDITION (ADR 0006 decision 3). A run can bring entries and still
  not open a turn -- a resume brings none (`harness.edge.stats`: 'a resume brings none and opens
  none'), and the conversation's BIRTH brings entries that ride as user messages without anybody
  having typed them (`source` = `opening` / `injection`)."
  [message]
  (and (= "user" (:role message)) (= "client" (entry-source message))))

(defn returned-source
  "WHO PUT THIS MESSAGE INTO THE ARRAY, for a message a RUN added: what the model returned,
  what a tool answered, and what the pre-LLM step derived along the way.

  THE ROLE ANSWERS THE FIRST TWO (`model`, `tool`) and the tag answers the rest: a skill
  body and a job's ending are wrapped by the code that writes them (`harness.cap.skills`
  writes `<skill name=..>`, `harness.cap.jobs` writes `<job-ended ..>`), and the skills
  namespace reads the first of those tags back out of the conversation
  (`loaded-skill-names`), so this is the same reading rather than a new convention. Anything
  else a run injected is an `injection` and says so."
  [message]
  (let [content (str (:content message))]
    (case (:role message)
      "assistant" "model"
      "tool"      "tool"
      (cond
        (str/starts-with? content "<skill name=")   "skill"
        (str/starts-with? content "<job-ended ")    "job"
        ;; THE CONVERSATION'S OPENING IS READ AGAIN BY EVERY RUN (`.scratch/session-opening`):
        ;; the instruction files and the skills catalog were folded in at the birth, so a
        ;; later run's copy is the same kind of fact -- an `opening` -- and the tags are the
        ;; ones `harness.edge.ag-ui/opening-entries` writes.
        (str/starts-with? content "<instructions")  "opening"
        (str/starts-with? content "<skills")        "opening"
        :else                                       "injection"))))

(defn- log-message!
  "ONE \"message\" line, for one message a RUN put in the array -- the row's payload is the
  message itself and its envelope carries the `:source` that says who put it there
  (`returned-source`).

  ONE AT A TIME IS THE POINT (`.scratch/record-envelopes`): the kernel says which message it just
  added (`:message/added`) and the edge writes it THERE -- so the record grows with the run the way
  the wire does, and a reader watching a run sees each tool's ANSWER and each answer's row as it
  happens instead of in one lump after the run's terminal frame."
  [thread-id run-id message]
  ;; A MESSAGE ROW WRITTEN HERE IS THE KERNEL'S (`:message/added`, and the account's tail) -- so it
  ;; says so. Ticket 02 of `.scratch/record-stream` hands the pen itself over; until then the row
  ;; names whose message it is, which is what the readers ask.
  (binding [*producer* :kernel-message]
    (log! thread-id run-id "message" message nil {:source (returned-source message)})))

(defn- log-messages!
  "The same rows in a batch -- what the run's own ACCOUNT (`:added`) is reconciled against at
  `:run/done`, and what a subagent's returned side is written from. See `log-message!` for why the
  ordinary path is one at a time."
  [thread-id run-id msgs]
  (doseq [m msgs]
    (log-message! thread-id run-id m)))

;; ------------------------------------------------------------------- the edge

(def ^:private terminal #{"RUN_FINISHED" "RUN_ERROR"})

(def ^:private reasoning-frames
  "The five frame types ADR 0009 keeps off the record. SPELLED OUT rather than matched by prefix: a
  sixth `REASONING_*` frame would then be RECORDED and the case that pins the record against the wire
  (`the-record-keeps-every-wire-frame-except-the-reasoning-family`) would fail -- where a prefix match
  would drop it in silence."
  #{"REASONING_START" "REASONING_MESSAGE_START" "REASONING_MESSAGE_CONTENT"
    "REASONING_MESSAGE_END" "REASONING_END"})

(defn- reasoning-frame?
  "Is FRAME one of the per-token REASONING family? THE RECORD SKIPS THE WHOLE FAMILY, and the
  predicate is spelled ONCE because there are two frame sinks (the agent route and a subagent's) and
  a second spelling would be a second rule.

  THE WHOLE FAMILY AND NOT JUST ITS CONTENT FRAMES: `harness.kernel.frames/apply-frames` builds a
  reasoning message from the START frame ALONE (empty content), and a fold that saw that message
  would think the frames had carried the reasoning and skip the run's own `message` row -- measured:
  that is exactly why `the-log-the-server-writes-is-one-replay-can-read` answered nil while only the
  CONTENT frames were dropped. See ADR 0009 and `.scratch/reasoning-out-of-the-record/spec.md`.

  THE FRAME IS NOT DROPPED, ONLY ITS LINE: it is still broadcast (`frame-bus`, `events.mux`) and
  still collected into the session's memory; the same text comes back off the run's `message` row
  (`harness.edge.replay/reasoning-row?`)."
  [frame]
  (contains? reasoning-frames (:type frame)))

;; ------------------------------------------------------ the running text, snapshotted

(def text-snapshot-ms
  "HOW OFTEN A RUNNING ANSWER'S TEXT REACHES THE RECORD -- `.scratch/event-persistence` ticket 03's
  'a snapshot every 50-100ms'. 75 sits in the middle of that band.

  A `def` RATHER THAN A NUMBER AT THE CALL SITE because a case has to be able to move it to either
  end of the band: at 0 every text frame is its own line, and at a huge value EXACTLY ONE line per
  message is written -- the one the message's end forces. Those two ends are what makes the band
  assertable without putting a clock inside a test."
  75)

(defn- text-snapshot
  "The LINE a run's running text becomes: THE WHOLE TEXT SO FAR, under a name of its own.

  NOT `TEXT_MESSAGE_CONTENT` CARRYING A WHOLE-TEXT `:delta`, which is the cheap way and the wrong
  one: a `row-of` payload is VERBATIM what the vendor sent (`.scratch/jsonl-two-kinds`), and every
  reader alive today appends a `:delta` to what it has. So the record's text is a family of its own
  -- `CUSTOM text/snapshot`, the shape every fact the harness knows by itself already has
  (`model/start`, `tools/*`, `session/rebuilt`) -- and THE FOLD IS WHAT KNOWS IT
  (`harness.kernel.frames/apply-frames` replaces rather than appends).

  THE FRAME'S NUMBER COMES ALONG where frames carry one (a subagent's do: `mux`'s record/bus
  boundary is compared by it). A snapshot STANDS FOR the text frames up to this one, so it takes
  that one's number: a reader that already holds the record up to N still drops exactly what it has
  seen and no more."
  [frame text]
  (cond-> {:type "CUSTOM" :name "text/snapshot"
           :value {:messageId (:messageId frame) :content text}}
    (contains? frame :seq) (assoc :seq (:seq frame))))

(defn- text-lines
  "THE ONE PLACE A RUN'S STREAMING TEXT BECOMES RECORD LINES
  (ticket 03 of `.scratch/event-persistence`): STATE is the sink's own atom (the messages still open
  live in it under `:text`), and this answers the lines FRAME becomes, in order. Both frame sinks
  call it once per frame.

  THE WIRE IS UNTOUCHED, and that is the whole of why this change is small: `runner` and the
  subagent route still broadcast every frame and still collect every frame for `settle!`, so a
  client still receives one frame per token and the conversation a run leaves in memory does not
  move. What this decides is WHICH LINES EXIST -- a log's text is 2% of its bytes and the per-frame
  envelope the rest.

  A SNAPSHOT AND NOT A BATCH OF DELTAS: the fold treats a snapshot as THE CONTENT, so a reader
  holding only the last line still has the whole sentence -- which is exactly what a run cut off
  mid-answer needs. A batch would leave every earlier line load-bearing, and a crash would leave a
  prefix of a prefix.

  A MESSAGE'S FIRST WORDS GO DOWN AT ONCE, and only then does the period apply -- a period alone
  would leave a run cut off inside its first tick with nothing at all on the record, and 'a run cut
  off mid-answer keeps the half sentence it had managed' is the one property this family is not
  allowed to trade away.

  AND A MESSAGE'S END AND THE RUN'S TERMINAL BOTH FORCE A FLUSH, before the frame that closes them
  -- a record must read in the order the wire had. Nothing is written twice in a row: a snapshot
  equal to the one already down is not a new fact."
  [state frame]
  (let [t   (:type frame)
        id  (:messageId frame)
        now (System/currentTimeMillis)]
    (cond
      (= t "TEXT_MESSAGE_CONTENT")
      (let [{:keys [text at out]} (get-in @state [:text id])
            text' (str text (:delta frame))
            tick  (long (or at now))
            ;; THE FIRST WORDS OF A MESSAGE GO DOWN AT ONCE, and only then does the period apply. A
            ;; period alone would leave a run cut off INSIDE its first tick with nothing on the record
            ;; at all -- which is the one thing this ticket refuses to trade away, and which
            ;; `a-run-that-stops-mid-thought-keeps-its-words-and-not-its-thinking` measures: its
            ;; whole answer arrives in one tick and its run never reaches a terminal.
            due?  (or (nil? out)
                      (>= (- now tick) (long (or text-snapshot-ms 0))))]
        (swap! state assoc-in [:text id]
               {:text text' :out (if due? text' out) :at (if due? now tick) :seq (:seq frame)})
        (if due? [(text-snapshot frame text')] []))

      (= t "TEXT_MESSAGE_END")
      (let [{:keys [text out] :as open} (get-in @state [:text id])]
        (swap! state update :text dissoc id)
        (if (and (some? text) (not= text out))
          [(text-snapshot (cond-> {:messageId id}
                                    (some? (:seq open)) (assoc :seq (:seq open)))
                          text)
           frame]
          [frame]))

      (contains? terminal t)
      (let [open (:text @state)]
        (swap! state assoc :text {})
        (into (vec (keep (fn [[mid {:keys [text out seq]}]]
                           (when (and (some? text) (not= text out))
                             (text-snapshot (cond-> {:messageId mid}
                                                     (some? seq) (assoc :seq seq))
                                            text)))
                         (sort-by key open)))
              [frame]))

      :else [frame])))

;; ------------------------------------------------- the frames the record never keeps

(def ^:private wire-only-frames
  "The CUSTOM frames the WIRE carries and the RECORD does not. SPELLED OUT rather than
  matched by prefix, for the reason `reasoning-frames` above is: a name nobody meant to add
  here would otherwise be dropped from the record in silence."
  #{ag/timeout-part-name})

(defn- wire-only-frame?
  "Is FRAME one the wire carries and the record never keeps?

  THE IDLE GUARD'S FRAME IS THE WHOLE LIST TODAY, and it is a different animal from the
  reasoning family above: those leave no line because their text comes back on the run's
  own `message` row, while this one leaves no line because it is NOT PART OF THE
  CONVERSATION AT ALL -- nothing a later rebuild, window or replay reader should see.
  The frame still reaches the client: it is broadcast (`mux-broadcast!`) and kept for the
  session's fold, where `harness.kernel.frames/apply-frames` drops the name exactly as it
  drops every CUSTOM name it does not know.

  THE PREDICATE IS SPELLED ONCE because there are two frame sinks -- the agent route and a
  subagent's -- and a second spelling would be a second rule."
  [frame]
  (and (= "CUSTOM" (:type frame)) (contains? wire-only-frames (:name frame))))

;; BOTH EXCEPTIONS ARE NOW THE SAME KIND OF THING (`reasoning-frame?` and
;; `wire-only-frame?`): one says which LINES a frame becomes, the other says whether the
;; harness's own frame about a call in flight may become a line at all. `runner` below and
;; the subagent route ask BOTH, in that order, over whatever `text-lines` answered.

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

    :step/start
    ["step/start" {}]

    ;; AND THE STEP'S OWN END SAYS WHICH CALLS IT MADE, and nothing else: what became of each
    ;; of them is on its own `tools/*` row, keyed by the same id (ADR 0011 -- one place).
    :step/end
    ["step/end" {:tools (:tools ev)}]

    nil))

;; THE RUN'S FRAMES ALSO GO OUT ON THE DOWNLINK (`events.mux`, ADR 0004). Declared here
;; because the emitter is built near the top of this file and the mux section is far below
;; it -- the Var is what a runtime call resolves either way.
(declare mux-broadcast!)
(defn- runner
  "Build the frame sink for one run: log every frame EXCEPT the per-token reasoning family (ADR 0009 --
  its text is on the run's own `message` row, and those frames were 80% of a log's bytes), keep every
  frame for the moment the run ends, and BROADCAST them all to the downlink (`events.mux`, ADR 0004)
  -- which is the carrier a run has now. WHAT IS DROPPED IS A LINE, NOT A FRAME: `reasoning-frame?`
  is the one place that decides, and the session's memory and the wire take every frame as before.
  ;;
  ;; AND THE RUNNING TEXT IS WRITTEN AS SNAPSHOTS (ticket 03), by the same rule and in the same place:
  ;; `text-lines` is the one thing that decides which LINES a run's text becomes, and the subagent
  ;; route calls the same helper. The frames still flow; only the lines changed.

  WHERE THE RUN ENDS IS HERE and nowhere else: a terminal frame is the only fact that says so,
  and this is the one place that sees every frame exactly once. So the registry stops claiming
  the thread is running and the conversation is folded (`settle!`) BEFORE the terminal goes out
  -- the window between 'the run ended' and 'its words are in the conversation' is one a fast
  client would otherwise be racing this process through.

  IT IS HANDS-OFF ABOUT WHO IS LISTENING. The broadcast writes to whatever connections declared
  this conversation; one that declared it and then went away costs nothing (the write is caught
  in `mux-send!`).

  AND ONE FAMILY IS NEITHER RECORDED NOR FOLDED INTO THE CONVERSATION AT EITHER SINK: the
  idle guard's CUSTOM frame (`wire-only-frame?`). A vendor that went quiet for too long is
  a fact about a call IN FLIGHT -- the client is told, and the record is not, because a
  reload that rebuilt a conversation out of it would be carrying a card about something
  that is not in the conversation at all."
  [thread-id run-id state]
  (fn [frame]
    ;; THE TERMINAL FRAME'S LINE IS WHERE THIS RUN'S ENTRIES LAND: `settle!` folds the run's
    ;; messages into the conversation at this same moment, and this line's record offset is the
    ;; number they are given (`sessions/land!`). THE NUMBER IS HELD, NOT APPLIED, and the order is
    ;; the whole reason: the write is synchronous (ADR 0007), so the offset comes back HERE --
    ;; before `settle!` has put those entries in the table -- and `land!` numbers only entries
    ;; that are already there. Landing it here numbered NOTHING AT ALL, which is how every entry a
    ;; run produced came to keep `:seq nil` (measured 2026-09-30: 7 of a session's 563 in memory,
    ;; 563 of 563 in the file), and `since` then answered the whole conversation to a reader
    ;; asking what it had missed. `settle!` takes it as its fourth argument and applies it before
    ;; the doorbell; the note there says why that is the only moment that works. (The comment
    ;; here used to claim 'either order works' -- it does not.)
    ;; THE PER-TOKEN REASONING DELTAS ARE NOT RECORDED (ADR 0009): they were **63% of the LINES of one
    ;; real log** (8,640 of 13,631) and **80% of the BYTES of another** (41,896,984 of 52,248,775 --
    ;; measured, `.scratch/reasoning-out-of-the-record/evidence/read_routes.txt`), and the same text is
    ;; on the run's OWN `message` row -- which the fold reads back (`harness.edge.replay/reasoning-row?`).
    ;; VERIFIED ON REAL LOGS: drop every reasoning frame from one and the fold answers the same
    ;; conversation, message for message (same judge the tests run).
    ;; THE FRAME IS NOT DROPPED, ONLY ITS LINE: it still goes to the bus and into the session's
    ;; memory, and the run's own `message` row carries the same text back (`reasoning-frame?` says
    ;; which family, and why the WHOLE family and not just its CONTENT frames).
    ;; BOTH EXCEPTIONS APPLY TO WHAT `text-lines` ANSWERED, not to FRAME: the text family
    ;; is one of the lines a frame becomes (`text-lines`), and a frame the record never
    ;; keeps is a whole frame rather than a line -- so the wire-only test reads ROW.
    (doseq [row (text-lines state frame)]
      (when-not (or (reasoning-frame? row) (wire-only-frame? row))
        (let [offset (log! thread-id run-id "event" row
                           (when (contains? terminal (:type row))
                             (fn [offset] (swap! state assoc :terminal-line offset))))]
          ;; AND THE LAST LINE THIS RUN WROTE, which is all a run that DIED has to be numbered by:
          ;; the crash path below settles the frames it managed to write, and the terminal it never
          ;; reached is exactly the number the ordinary path hands `settle!`.
          (when (some? offset) (swap! state assoc :last-line offset)))))
    ;; THE RUN'S OWN HALF OF THE CONVERSATION, kept for the moment it ends: the session's
    ;; history is what this run was handed, and these frames are what came of it. Collected HERE
    ;; because this is the one place that sees every frame exactly once, and settled at the
    ;; terminal -- a half-written answer is not a turn, so the conversation changes when the run
    ;; does.
    (swap! state update :frames conj frame)
    (when (contains? terminal (:type frame))
      ;; THE RUN IS OVER THE MOMENT ITS TERMINAL FRAME EXISTS: this is where the registry stops
      ;; saying the thread is running (harness.edge.sessions/running? -- the fact the sidebar row
      ;; reads), and where the conversation becomes what it says.
      (unregister-run! thread-id run-id)
      (sessions/settle! thread-id run-id (:frames @state) (:terminal-line @state)
                        (some-> *written-rows* deref entry-lines))
      (swap! state assoc :terminal (:type frame)))
    ;; THE FRAME ITSELF, NOT A DECORATED COPY: this map is what the socket carries, and for every
    ;; family but the reasoning one (ADR 0009) it is also the map the record logs -- `:threadId`, the
    ;; routing tag the downlink adds, is not part of the AG-UI frame and is stripped by the reader).
    (mux-broadcast! thread-id frame)
    (when (contains? terminal (:type frame))
      ;; AFTER the broadcast: a log write is a synchronous file write, and putting it in front
      ;; would delay the frame that ends the run.
      (log/info! :run/terminal {:thread-id thread-id :run-id run-id
                                :event     (:type frame)
                                :reason    (:message frame)}))))

;; Keep a frame for the RECORD AND THE SESSION, and send it to NOBODY.
;;
;; THE ONE CALLER IS A STOP'S CUT-OFF ANSWERS (`harness.kernel.loop`'s stop branch,
;; `:run/cut-off-result`). They are the mirror of the wire's normal rule -- every frame goes to
;; the record and the client -- and the two halves part here on purpose: the record needs the
;; answer (an open tool call is a shape the vendors refuse, and the session folds these frames
;; so the next run continues from a complete conversation), while the client must NOT be told a
;; call returned when it did not. A client that received one would draw the call `Done`; the
;; truthful thing on screen is the cancellation it just asked for.
;;
;; NOT THE EMITTER WITH A FLAG: that function also owns the terminal -- unregistering the run,
;; settling the conversation, closing the stream -- and none of that can apply to a frame no
;; stream will ever see. What it owes the session is `:frames`, which is the same list
;; `settle!` folds at the terminal, so it goes in there and nowhere else.
(defn- recorder
  "Keep a frame for the record and the session, and send it to nobody -- see above."
  [thread-id run-id state]
  (fn [frame]
    (log! thread-id run-id "event" frame)
    (swap! state update :frames conj frame)))

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
  "The messages a conversation OPENS WITH, other than the frozen system prompt: the
  session's instruction files, read fresh, and (from the skills ticket) the catalog.
  Runs INSIDE the edge's hook-sink binding, because folding an instruction file is a
  hook point and this is where it happens -- see `sink-for` and `run-agent!` for why
  the binding wraps the call.

  Every file that was folded fires InstructionsLoaded with its path, and the
  verdict is DISCARDED: the point is an observer (:gate? false, :on-error
  :proceed), and there is nothing sensible for a loader to do about a hook that
  refused -- the file is already read. A file that was skipped does NOT fire:
  nothing was folded, and a hook announced for something that did not happen is
  worse than no hook.

  IT IS READ ONCE PER CONVERSATION, not once per run (`.scratch/session-opening`).
  The opening is part of what a conversation was born with: the run that births one
  writes these into it (`ag/opening-entries`) and every later run continues from them
  as history, so an edited AGENTS.md takes effect at the NEXT opening -- a new session,
  or a compaction that rebuilds one -- rather than mid-conversation. That is the price
  of the opening being an event rather than a per-run splice, and it is the trade this
  function's call site makes deliberately."
  [thread-id]
  (let [gathered (preamble/gather
                  {:files (project/preamble-files thread-id)
                   :roots (project/skill-roots thread-id)})]
    (doseq [{:keys [path]} (:instructions gathered)]
      (hook/emit :instructions-loaded {:path path}))
    (preamble/messages gathered)))

(defn- sink-for
  "The hook sink ONE run records through: every hook this run fires is audited under
  its run id, in the record, next to the rest of that run.

  A FUNCTION RATHER THAN A LITERAL IN TWO PLACES because there are two moments in a run
  that fire hooks and they are on different sides of the `async/go`: the opening is read
  before the run body starts (it belongs to the conversation, not to the run) and the
  system prompt is assembled inside it. Both are the same run, so both must audit under
  the same name."
  [thread-id run-id]
  {:thread-id thread-id
   :run-id    run-id
   :audit     (fn [payload]
                (log! thread-id run-id
                      (str "hook/" (:point payload))
                      (dissoc payload :point)))})

;; Defined below with the compaction route; `run-agent!` calls it at the start of every run,
;; BEFORE it derives the request (ticket 04), and `relieve-pressure!` is the same question asked
;; again before EVERY model call (ticket 04 of `.scratch/compaction-shape`).
(declare compact-if-pressured! recover-overflow! relieve-pressure!)
(declare compact-if-pressured! recover-overflow!)
(defn- run-agent!
  "Drive ONE run: log its entries, set the conversation up, and stream what comes back.

  RUN-ID IS AN ARGUMENT rather than a field of INPUT, and after ticket 03 that is the
  whole point: the id names a run in THIS process and is minted at the door
  (`handle-run`), so it is not something the body has and not something a client can
  say. It goes to the record as the log line's own `:runId`, not inside the payload."
  [state input run-id]
  (let [thread-id (str (:threadId input))
        run-id    (str run-id)
        ;; ONE emitter and ONE converter per run. The converter owns the open-message
        ;; state machine, so building it per event restarts every message id and
        ;; re-emits START frames -- which an AG-UI client treats as fatal.
        emit    (runner thread-id run-id state)
        ;; AND ONE WRITER THAT DOES NOT SEND: the frames of a stop's cut-off answers are the
        ;; record's and the session's, never the client's (see `recorder`).
        record! (recorder thread-id run-id state)
        convert (ag/outbound thread-id run-id)
        ;; THE REQUEST SIDE OF A RUN'S FIRST CALL, HELD UNTIL ITS ENVELOPE IS OPEN (ticket 04 of
        ;; `.scratch/record-envelopes`): the rows this run wrote FOR the call it is about to make --
        ;; the system prompt, the array's own entries, the messages the pre-LLM step derived and the
        ;; pressure reading -- belong INSIDE `[model/start, model/end]`, so that a reader who meets a
        ;; call's pair knows what that call had in hand without assembling it from the rows above.
        ;;
        ;; THEY ARE QUEUED, NOT WRITTEN, and `flush-request!` writes them the moment the kernel
        ;; says the call is starting (`:model/start` IS that moment: the envelope opens on it).
        ;; A THUNK RATHER THAN A ROW because the number a line gets IS the write's own -- `log!`
        ;; answers it and `land-at!` takes it -- so holding the rows means holding the writes, and
        ;; the numbering stays honest about where the lines really are.
        ;;
        ;; NOTHING IS LOST WHEN NO CALL HAPPENS: the queue is flushed when this run ends too, so a
        ;; person's question is on the record even when the run it was sent for never started.
        ;;
        ;; THE COST, SAID ONCE: the request is no longer on disk the instant it is assembled -- it
        ;; lands when the call it was assembled for begins.
        queued (atom [])
        hold!  (fn [thunk] (swap! queued conj thunk) nil)
        ;; THE REQUEST SIDE SAYS SO ABOUT ITSELF (ticket 03): these are the rows this run wrote FOR
        ;; a call, and the binding is CAPTURED here because the thunk runs later, at the flush.
        request-log! (fn [& args]
                       (let [p (or *producer* :request)]
                         (hold! (fn [] (binding [*producer* p] (apply log! args))))))
        request-messages! (fn [& args]
                            (let [p (or *producer* :request)]
                              (hold! (fn [] (binding [*producer* p]
                                             (apply log-messages! args))))))
        flush-request! (fn []
                         (let [writes (first (reset-vals! queued []))]
                           (doseq [write writes] (write))))
        ;; AND THE OPEN TEXT GOES DOWN BEFORE A ROW THAT WOULD SPLIT ITS GROUP: `replay/fold-frames`
        ;; folds a run's frames as ONE GROUP, so a `message` row written while the answer's
        ;; `TEXT_MESSAGE_*` pair is still open would cut that group in half and the text would come
        ;; back wrong. The row that triggers this is the kernel's `:message/added` for the ANSWER --
        ;; by then the call is over, so the snapshot is written a beat earlier than the converter's
        ;; own `TEXT_MESSAGE_END` (at `:model/end`) would have written it.
        flush-open-text! (fn []
                          (doseq [[mid _] (:text @state)
                                  row (text-lines state {:type "TEXT_MESSAGE_END" :messageId mid})
                                  :when (not= "TEXT_MESSAGE_END" (:type row))]
                            (log! thread-id run-id "event" row nil)))
        ;; THE DOOR THE KERNEL WRITES ITS OWN MESSAGES THROUGH (ticket 02 of `.scratch/record-stream`):
        ;; the kernel has the messages first, so it is the one that writes them -- this is where the
        ;; file is resolved and the row's envelope is shaped, and where the two things the EDGE owes a
        ;; row it did not write happen: the open text is closed onto the record first (a row may not
        ;; split a run's frames), and the account is told, so `:run/done` can tell 'written' from
        ;; 'forgotten'.
        write! (fn [message]
                 (when (= "assistant" (:role message)) (flush-open-text!))
                 (log-message! thread-id run-id message)
                 (swap! state update :reported (fnil inc 0)))]
    ;; THE BIRTH -- reading the session's opening, appending this action's own entries,
    ;; writing their rows and naming the session -- HAPPENS INSIDE THE GO BLOCK
    ;; BELOW, on purpose. Reading the instruction files can fail (an unreadable
    ;; AGENTS.md), and a failure on the way in has to leave the client a TERMINATED RUN
    ;; rather than a stream that never says anything: the go block's `try` is where that
    ;; is turned into a RUN_ERROR frame, so the birth is on its side of the parens.
    ;; `input`, `thread-id`, `run-id` and the two stateful closures above are all it
    ;; needs.
    ;; A THREAD, NOT A `go` BLOCK (ADR 0007): everything below -- including every `log!` -- now
    ;; does its file I/O ON THIS THREAD, and a blocked `go` block PARKS A core.async DISPATCH
    ;; THREAD for every other block in the process. Measured on the first attempt: a run held at
    ;; a tool seam stopped reaching it at all, because this body logs its own frames.
    (async/thread
      ;; A GO BLOCK'S EXCEPTION GOES NOWHERE: core.async throws it into the block's
      ;; own channel, which nobody reads -- so a consumer that dies takes the run
      ;; down in silence. The kernel's producer blocks on its next put, the client
      ;; waits for frames that will never come, and the record stops mid-sentence
      ;; with nothing anywhere saying why. That is the one failure in this file
      ;; that cannot be allowed to be quiet, so the whole run body is wrapped.
      (try
        (binding [hook/*sink* (sink-for thread-id run-id)
                  ;; THIS RUN'S OWN ROWS, collected for the one moment they become the numbers of the
                  ;; conversation (`entry-lines` below, at `settle!`): the record is what decides them
                  ;; and this is the writer that has it in hand.
                  *written-rows* (atom [])]
          ;; ------------------------------------------------------------------ the birth
          ;; WHAT THIS RUN CONTINUES FROM, read once, before anything starts. The session
          ;; holds the conversation (harness.edge.sessions); the client no longer sends
          ;; it. What the client DOES send is this action's own entries (`append`), and
          ;; they go into the session BEFORE the run is set up, so a run that then fails
          ;; (no provider, a refused modality) still leaves the question in the
          ;; conversation -- which is where the record's own input line puts it.
          ;;
          ;; AND THE WHOLE OPENING IS ONLY EVER TAKEN ONCE, at the birth of the
          ;; conversation -- the opening context AND the opening blocks (the session's
          ;; instruction files and skills catalog, `.scratch/session-opening`). That is
          ;; what makes them part of the history every later run continues from, instead
          ;; of messages re-appended (and re-paid for, and read after the answer) on
          ;; every run. A session that already has messages has already been born.
          ;;
          ;; IT IS READ INSIDE THIS BINDING because folding an instruction file is a hook
          ;; point and this is where it happens -- and INSIDE THIS TRY, because a failure
          ;; to read it (an unreadable AGENTS.md) is a run that never starts, and the
          ;; client has to get a terminated run rather than a broken stream.
          ;;
          ;; ITS FAILURE IS RAISED A FEW LINES LOWER, AFTER THE APPEND, and that ordering
          ;; is the point: the person's own message must enter the conversation even when
          ;; the run it was sent for never starts, or a reload would show a question that
          ;; the session has no record of.
          ;;
          ;; `:added` IS WHAT THIS ACTION MEANT, next to the payload the client typed:
          ;; the two differ when a retry sends a message the conversation already has
          ;; (nothing enters) and at birth (the opening enters, and the client never sent
          ;; it). The record keeps both because the fold reads `:added` and a reader
          ;; asking 'why is my message not in here' needs to see what was sent.
          ;; AUTO COMPACTION (ticket 04): before this run derives its request, is the model's
          ;; window about to run out? At or over the threshold, compact NOW -- so `history`
          ;; below reads the compacted conversation. Below it, nothing happens.
          ;;
          ;; WHAT IT DID IS KEPT BESIDE THE RUN (`:compacted`), and that is not bookkeeping: this
          ;; compaction changed what the model will read on this very run, and a person watching has
          ;; no other way to be told. It cannot be said HERE either -- a frame before RUN_STARTED is
          ;; a frame the client has no run to hang it on -- so it rides the run's first frames
          ;; (the `:run/start` branch below, beside the injections).
          (swap! state assoc :compacted (compact-if-pressured! thread-id))
          (let [history  (sessions/messages thread-id)
                born?    (empty? history)
                [opening opening-failure]
                (if born?
                  (try [(ag/opening-entries (opening-blocks! thread-id)) nil]
                       (catch Throwable t [nil t]))
                  [nil nil])
                ;; THE QUESTION COMES FIRST, THE OPENING BEHIND IT. What a model reads
                ;; on the turn that births a conversation is `system, what the person
                ;; asked, and the material for it` -- the order `.scratch/context-frames`
                ;; decision 7 and `CONTEXT.md`'s 注入 entry both state, and the one this
                ;; ticket restores. Ticket 01 of `.scratch/session-opening` had put the
                ;; opening in FRONT of the question while making it happen once; the
                ;; 'once' is the part that mattered, and it is kept: the opening is
                ;; still written here and nowhere else.
                ;;
                ;; THE BIRTH CONTEXT KEEPS ITS PLACE AHEAD OF THE OPENING, because it is
                ;; what the conversation IS (the project this session is bound to) and
                ;; the opening is what it must read -- the same reading the retired
                ;; `tail-blocks` gave the two, and the one `CONTEXT.md` calls 'the
                ;; birth context is already in the conversation'.
                entries  (into (cond-> (vec (:append input))
                                 born? (into (when-some [e (ag/context-entry (:context input))] [e])))
                               opening)
                added    (sessions/append! thread-id run-id entries)
                ;; THE CONVERSATION ITSELF, for the one run that is the only wire it
                ;; has: the entries this action wrote on the client's behalf (the birth
                ;; context and the opening blocks) are in the session and in no client's
                ;; hands, and a page that MINTED the session holds no window and follows
                ;; no feed. `ag/conversation-snapshot` says why a message list and not a
                ;; card frame -- the short of it is that a message survives the trip and
                ;; a frame does not. NIL ON EVERY OTHER RUN: a run whose entries the
                ;; client sent is continuing a conversation that client already holds.
                snapshot (when (seq (ag/client-never-sent added (:append input)))
                           ;; THE ENTRIES, NOT THE TABLE'S COPY OF THEM: this is the
                           ;; conversation as it stands after this action, and at birth
                           ;; that is exactly `entries` -- the client's own messages
                           ;; (already in the wire's shape) plus what the birth wrote.
                           (ag/conversation-snapshot entries))]
            ;; AND THE SESSION ACQUIRES ITS NAME FROM THE SAME ARRIVAL, in the same place
            ;; and for the same reason the input frame is written here: this is the one
            ;; moment the server holds 'the person pressed send'. It writes once per
            ;; session and is a no-op for every later run
            ;; (`cap.project/remember-send!`'s `COALESCE(title, ?)`), and it is called
            ;; with the SESSION's first user turn rather than by reading the log -- the
            ;; log is where the same words already are, and a listing that had to derive
            ;; a title from 50 logs would pay for it on every sidebar refresh.
            ;;
            ;; WHY `history` AND NOT THE ACTION: main retired `:messages` in the run body
            ;; (ADR 0002 decision 9), so the client's words for THIS action are all an
            ;; action ever carries -- and on the fortieth send that is the newest
            ;; message, not the first. The session is the authority, so the first user
            ;; turn of `history` is the honest name (a session that ran before this
            ;; column existed is named correctly the next time it runs); this action's
            ;; own entries cover the birth, when `history` is empty. Reading the log
            ;; instead would be the second truth ADR 0002 refuses.
            (project/remember-send! thread-id (ag/first-user-text (into history (:append input))))
            (host/ring!)
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
            (let [[provider messages decisions resolved injected sys plan]
                (try (let [;; THE PROVIDER IS THE SESSION'S, NOT THE REQUEST'S. It used to
                           ;; be layered with whatever `:provider` the run body carried,
                           ;; which made the selection a thing a CLIENT said per request --
                           ;; and that is the shape ticket 03 retired: choosing a model is
                           ;; an action (`POST /api/model`, which is where the session's
                           ;; override is written and where the change is recorded), and a
                           ;; run is served by whatever that action left in force. `input`
                           ;; is not consulted here at all, which is the point.
                           provider (providers/current-provider thread-id)
                           ;; THE INSTRUCTION PLAN, ASKED FOR ONCE, and ONLY THIS RUN'S: what
                           ;; message[0] is, whether the instructions moved since the last send,
                           ;; and the chain of updates to resend. `plan` runs the SystemPrompt
                           ;; hooks ONLY when something moved, so a run that changed nothing reuses
                           ;; the text it sent last time -- which is the whole saving of
                           ;; `.scratch/instruction-updates`. It sits inside the hook sink binding
                           ;; above because assembly is what fires the point.
                           plan (instructions/plan thread-id (:instruction-updates provider))]
                       ;; THE OPENING THAT COULD NOT BE READ, raised here -- now that the
                       ;; conversation holds what the person sent -- so it lands in this
                       ;; try's own catch, beside every other could-not-start failure, and
                       ;; the client gets the file's name in a RUN_ERROR frame instead of
                       ;; a stream that stops mid-sentence.
                       (when opening-failure
                         (throw opening-failure))
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
                             ;; NOTHING IS SPLICED IN HERE ANY MORE FOR THE OPENING
                             ;; (`.scratch/session-opening`): the instruction files and the
                             ;; skills catalog entered the conversation at its birth, so
                             ;; they are part of the list below and stand in front of the
                             ;; question. What this run still derives for itself is
                             ;; `before-llm` on the next line -- the half that changes while
                             ;; a conversation lives.
                             ;;
                             ;; THE MODEL VIEW, ASKED FOR ONCE FOR THE WHOLE LIST. `history`
                             ;; is already the session's model view; `added` is NOT --
                             ;; `append!` answers with the entries as the RECORD keeps them,
                             ;; which for the opening means the card part too (the record is
                             ;; what the page draws from). Handing that to a provider is the
                             ;; one thing `ag/provider-part` refuses by name, so the two
                             ;; halves go through `sessions/model-view` together.
                             client    (sessions/model-view (into history added))
                             base      (ag/inbound client (:content (:system plan)) nil)
                             ;; THE UPDATES STAND BEFORE THE QUESTION. `place-updates` answers
                             ;; nil when the last message is not a user turn (a pathological
                             ;; history); that is a fact to act on, not to paper over, so the run
                             ;; falls back to :replace and says why (decision 3).
                             placed    (when (and (= :in-place (:mode plan))
                                                  (seq (:updates plan)))
                                         (ag/place-updates base (:updates plan)))
                             unplaced? (and (= :in-place (:mode plan))
                                            (seq (:updates plan))
                                            (nil? placed))
                             plan      (if unplaced?
                                         (do (log/warn!
                                              :instruction/update-unplaced
                                              {:thread-id thread-id
                                               :reason (str "the last message this run"
                                                            " carries is not a user turn, so an"
                                                            " instruction update has nowhere to"
                                                            " stand before the question; the"
                                                            " change went out by replacing"
                                                            " message[0] instead")})
                                             (instructions/fallback plan))
                                         plan)
                             assembled (if unplaced?
                                         (ag/inbound client (:content (:system plan)) nil)
                                         (or placed base))
                             applied   (project/before-llm assembled thread-id)
                             injected  (subvec applied (count assembled))]
                         [provider
                          (llm/thinking-mode-history applied provider)
                          (resume-decisions (:resume input))
                          ;; THE SESSION'S OWN TIER, with no request layered on it -- the
                          ;; same answer `provider` above resolved, and the map the
                          ;; provider/init and provider/changed lines are written from.
                          (providers/resolve-provider thread-id)
                          injected
                          (:system plan)
                          plan]))
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
            ;; The provider timeline, part 1: ONE init line per session, on its first run.
            ;; IT COMES BEFORE THE PROMPT, and that is a decision: the init is an EVENT row
            ;; (the timeline), the prompt is the array's first MESSAGE row -- so putting the
            ;; init first costs nothing and keeps "a reader meets what served the
            ;; conversation before it meets the conversation" true even though the prompt is
            ;; now the record's first message row. Later runs of the same thread do not
            ;; repeat it -- the timeline is init plus changes, not a snapshot per run.
            (when (and (some? provider)
                       (nil? (providers/pinned-provider thread-id))
                       (claim-once! init-logged thread-id))
              (log! thread-id run-id "provider/init"
                    (provider-line provider (:source resolved))))
            ;; ------------------------------------------------------- the prompt comes first
            ;; THE SYSTEM ROW IS WRITTEN FIRST, BEFORE THE ACTION'S OWN ENTRIES, because a
            ;; `message` row IS an element of the array the model read and the prompt is that
            ;; array's FIRST element: written this way the record's `message` rows come out
            ;; in the array's own order -- `role=system`, then what the person said, then
            ;; what the run returned. That is also what lets `harness.edge.trajectory`
            ;; open every run at its prompt rather than at whichever entry landed first.
            ;;
            ;; WRITTEN WHENEVER THE ASSEMBLY SUCCEEDED, PROVIDER OR NOT: the content is the
            ;; assembled text (prompt.md's frozen opening with each SystemPrompt hook's text
            ;; behind it), and the ENVELOPE says who put it there (`:source`) plus the
            ;; bytes' SHA-256 (`:hash`) -- so 'was this the same prompt as last run' is
            ;; answerable without diffing four kilobytes, which is what the provider's
            ;; prefill rests on. A run whose ASSEMBLY failed writes no row (there is no
            ;; prompt to describe) and this `when` is what says so; its entries' rows below
            ;; are still written.
            ;;
            ;; THE TABLE RIDES THE ENVELOPE (`:tools`), NOT THE CONTENT: a `<tools>` block in
            ;; the message would be read by the model a second time, and paid for -- while the
            ;; envelope is exactly where `log!` keeps a field that belongs to the ROW
            ;; (`:source`, `:hash`) and `harness.edge.replay/payload` keeps it out of the
            ;; message. So the record carries the whole table and the model never sees it.
            ;; See ADR 0004.
            ;; WHAT THE MODEL READ AS ITS SYSTEM MESSAGE, which under :in-place is the
            ;; FROZEN text we sent before (the update rides the tail), not this run's
            ;; assembly. The row says what was SENT, so a reader comparing signatures
            ;; sees the prefix the model is actually still reading.
            (when (some? sys)
              (request-log! thread-id run-id "message"
                    {:role "system" :content (:content sys)} nil
                    {:source "system-prompt" :hash (:hash sys)
                     :hooks-names-hash (:hooks-names-hash sys)
                     ;; WHICH DELIVERY THIS RUN WAS SERVED BY, so the meter can tell a
                     ;; hook change that broke the prefix (:replace) from one that rode the
                     ;; tail (:in-place) -- the two are indistinguishable from the hooks
                     ;; hash alone (`.scratch/instruction-updates` ticket 06).
                     :instruction-updates (:mode plan)
                     :tools (tools/specs thread-id)}))
            ;; ONE ROW PER ENTRY, AND THE LINE THAT CARRIES IT IS THE LINE THAT NUMBERS IT
            ;; (`.scratch/jsonl-two-kinds` 票 02): the action's entries are `message` rows
            ;; now -- each with ITS OWN identity (the envelope's `:id`, the name the session
            ;; and the fold dedupe by) and its own number (`land-at!` is handed the offset of
            ;; the very line it wrote, so a window's `beforeSeq` cuts where the record does).
            ;;
            ;; WRITTEN EVEN WHEN THE RUN DOES NOT START, which is why this sits OUTSIDE
            ;; `(when provider ...)`: the person's own message must enter the CONVERSATION
            ;; even when the run it was sent for never starts, or a reload (which reads the
            ;; LOG) would show a question the session has no record of.
            ;;
            ;; WHOSE ELEMENT OF THE ARRAY IT WAS IS THE ENVELOPE'S `:source` (owner,
            ;; 2026-09-21: a `message` row IS an element of the messages array the model was
            ;; handed, so the row has to say who put it there). The payload stays the
            ;; VERBATIM provider message -- `model-view` is asked for it here because the
            ;; record keeps what the MODEL read. THE ROWS ARE WRITTEN IN THE ORDER THE
            ;; ENTRIES WENT IN, which is the order the array was read in -- the same order
            ;; `land-at!` matches an unnamed entry by. AN ENTRY THAT TRANSLATES TO NOTHING
            ;; WRITES NO ROW: a lone `reasoning` is folded into the assistant it precedes, and
            ;; a row for it would claim the model was handed something it never saw.
            ;; THE INSTRUCTION UPDATES ARE SUBMITTED MESSAGES TOO, and they stand where the
            ;; ARRAY put them: immediately before the question. They are written by this run
            ;; (the client never holds them) and carry `:source "instruction-update"` so no
            ;; reader mistakes them for a person's turn -- they are not conversation entries,
            ;; and `replay/entries` and `trajectory/entry-row?` both leave them out.
            (let [updates    (:updates plan)
                  ;; the question is the LAST added entry; when the run brought none, the
                  ;; conversation's own last message is the question and the updates stand at
                  ;; the head of this run's rows (right behind it on the record).
                  updates-at (when (and (seq updates) (seq added)) (dec (count added)))]
              (when (and (seq updates) (empty? added))
                (doseq [u updates]
                  (request-log! thread-id run-id "message" {:role "developer" :content u} nil
                        {:source "instruction-update"})))
              ;; A TURN OPENS WITH A PERSON'S OWN WORDS, AND ONLY WITH THEM (ADR 0006 decision 3):
              ;; a resume brings none and opens none, and the conversation's birth rides as user
              ;; messages nobody typed (`speaks-for-a-person?`).
              ;;
              ;; ITS OPENING LINE IS THE NEXT ONE THE RECORD WILL TAKE: this sits before the rows
              ;; below, and the write is synchronous (ADR 0007), so `flushed-seq` is that line's
              ;; number rather than a prediction about a queue. The counts start from zero here
              ;; and the write stream takes them from there.
              (when-some [from (when (some speaks-for-a-person? added)
                                 (stream/flushed-seq thread-id))]
                (sessions/set-fold-value! thread-id :turn (turn/state-init))
                (swap! state assoc :turn/from from)
                (family-send! thread-id {:type "turn/start" :seq from}))
              (doseq [[i m] (map-indexed vector added)
                      :let [shown (first (ag/provider-messages (sessions/model-view [m])))]]
                (when (= i updates-at)
                  (doseq [u updates]
                    (request-log! thread-id run-id "message" {:role "developer" :content u} nil
                          {:source "instruction-update"})))
                (when (some? shown)
                  (request-log! thread-id run-id "message" shown
                        (fn [offset] (sessions/land-at! thread-id run-id (or (:id m) i) offset))
                        (cond-> {:source (entry-source m)}
                          (:id m) (assoc :id (:id m)))))))
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
              ;; REMEMBERED ONLY NOW: the run let a request go out under this plan, so the
              ;; next one can tell whether its instructions still hold. A run that never got
              ;; here -- no provider, a hook that refused -- leaves the memory as it was,
              ;; because what never went out is not 'what we said last'.
              (instructions/commit! thread-id plan)
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
              ;; The decision record: what the human answered, next to the input that
              ;; carried it. The same verdict also lands on the resumed call's
              ;; tools/pre-execute line, keyed by toolCallId -- this row is the one
              ;; that carries the interrupt id and the client's payload.
              (doseq [d decisions]
                (log! thread-id run-id "approval/decided" d))
              ;; The provider timeline, part 2: any change a TOOL made during this
              ;; run, drained from the outbox. NOTHING FEEDS IT TODAY -- the tool
              ;; that did (`session-configure`) has been removed, and POST /api/model
              ;; writes its own `provider/session-changed` line when it is pressed --
              ;; but the drain stays wired for the next tool that moves the
              ;; selection. It lands after approval/decided because such a change is
              ;; only written once the human's approval has been consumed, so the two
              ;; lines read together as "approved, and here is what it changed".
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
              ;; WHAT THIS RUN DERIVED FOR ITSELF, as message rows (票 02). These are the
              ;; injections folded in beside the conversation -- a body an earlier turn
              ;; loaded, a job that ended between two runs: ordinary user messages to the
              ;; provider, parts of the array this run was handed, and NOT entries of the
              ;; conversation (the client gets the card, and the next run reads its bytes
              ;; back through `sessions/model-view` rather than deriving them again). The rest of the submitted array was
              ;; written by the runs that produced it -- which is the whole saving of this
              ;; ticket: a run logs what IT put in, never the conversation again.
              (request-messages! thread-id run-id injected)
              ;; HOW FULL THE REQUEST THAT IS ABOUT TO GO OUT IS, ON THE RECORD, BEFORE
              ;; it goes -- the reading a compaction trigger (harness.edge.pressure) starts
              ;; from. MESSAGES is handed in rather than read back because this run's own
              ;; lines are still with the writer; the anchor comes from the session's band,
              ;; which the writer kept current row by row and which the build folded from
              ;; the record -- so this reading opens no file.
              (request-log! thread-id run-id "context/pressure"
                    (pressure/log-pressure thread-id messages
                                          (:context-window provider)))
              ;; Drain run-chan and convert each kernel event to AG-UI frames. The
              ;; stream closes via :run/end's RUN_FINISHED (or RUN_ERROR), or via
              ;; :run/interrupt's RUN_FINISHED carrying outcome.interrupts; the
              ;; :run/done history itself is never converted -- it is the returned
              ;; side of the message record instead.
              (let [events (loop/run-chan provider messages {:thread-id thread-id
                                                             ;; THE KERNEL WRITES ITS OWN MESSAGES, so it is
                                                             ;; handed the door (`write!`, above).
                                                             :write!  write!
                                                             ;; THE RUN'S OWN STOP SWITCH, minted by
                                                             ;; `register-run!` above. The route that
                                                             ;; rings it (`cancel-post`) and the loop
                                                             ;; that listens both read THIS row, so a stop
                                                             ;; cannot be aimed at a run that is over.
                                                             :cancel (sessions/run-switch thread-id)
                                                             :resume decisions
                                                             ;; The session's skill bodies
                                                             ;; go back in before every
                                                             ;; LLM call. Both edges pass
                                                             ;; the SAME function, so a
                                                             ;; rebuilt conversation carries
                                                             ;; what a live one did.
                                                             ;; A REFUSAL FOR LENGTH RECOVERS IN THE SAME TURN: the
                                                             ;; loop hands the history to `recover-overflow!`, which
                                                             ;; compacts aggressively and answers a SHORTER view, or
                                                             ;; nil (the vendor's refusal then stands).
                                                             :on-overflow (fn [history t]
                                                                            (recover-overflow! thread-id provider history t emit))
                                                             ;; THE SAME QUESTION, ASKED BEFORE EVERY CALL INSTEAD OF
                                                             ;; AFTER THE REFUSAL: is this the request to send? A run
                                                             ;; that grows between calls (one tool result can be
                                                             ;; enormous) used to fly blind from its start reading to
                                                             ;; the vendor's 400 -- measured on a real session: 62% at
                                                             ;; the run's start, 97% thirteen seconds later, over 100%
                                                             ;; twelve minutes in (`.scratch/compaction-shape` 04).
                                                             :on-pressure (fn [history]
                                                                            (relieve-pressure! thread-id provider history emit))
                                                             :overflow-retries (compaction/overflow-retries thread-id)
                                                             ;; THE IDLE GUARD'S TWO KNOBS, read from the same harness.edn
                                                             ;; and on the same terms: how long a model call may sit silent,
                                                             ;; and how many times a call that went silent is tried again.
                                                             ;; `harness.edge.llm-timeout` is the reader and the kernel is
                                                             ;; handed the answer -- it reads no configuration of its own.
                                                             :idle-timeout-ms (llm-timeout/idle-timeout-ms thread-id)
                                                             :idle-timeout-retries (llm-timeout/retries thread-id)
                                                             ;; A JUST-PRODUCED TOOL RESULT THAT IS
                                                             ;; HUGE IS MOVED OUT OF THE CONVERSATION
                                                             ;; (`harness.cap.spill`): the model reads
                                                             ;; one pickup slip instead of a giant answer,
                                                             ;; so the pressure never goes up for it. The
                                                             ;; `read` tool IS the retrieval path, so its
                                                             ;; own answers are never spilled again -- that
                                                             ;; would bury the thing being retrieved.
                                                             :on-tool-result (fn [name content]
                                                                               (if (= "read" name)
                                                                                 content
                                                                                 (spill/slip thread-id content)))
                                                             :before-llm project/before-llm
                                                             ;; THE EDGE'S HALF OF THE model/start
                                                             ;; SIGNATURE: the byte measure the
                                                             ;; kernel does not own (see
                                                             ;; `harness.edge.context/tool-signature`).
                                                             :tool-signature context/tool-signature})]
                (loop []
                  (when-let [ev (async/<!! events)]
                    ;; WHETHER THIS RUN'S END LEAVES THE TURN OWING ANYTHING, AND WHERE ITS RANGE
                    ;; ENDS (ADR 0006 decision 3). The terminal EVENT arrives before the frame it
                    ;; becomes, so the next line written for this thread IS that frame's line --
                    ;; `stream/flushed-seq` counts what is written, and the write is synchronous
                    ;; (ADR 0007), so this asks the record rather than guessing.
                    ;;
                    ;; `:run/interrupt` IS NOT AN ENDING: the calls are a human's to decide, and the
                    ;; run that carries the answer closes the SAME turn.
                    (when (contains? #{:run/end :run/error :run/stopped} (:type ev))
                      (swap! state assoc :turn/closes? true)
                      (swap! state assoc :turn/to (stream/flushed-seq thread-id)))
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
                      ;; added -- assistant replies VERBATIM (the history holds the
                      ;; provider message unrebuilt, reasoning and tool calls intact)
                      ;; and each tool result as the tool message submitted on the next
                      ;; call, plus the injections the pre-LLM step derived along the
                      ;; way. THE KERNEL SAYS WHICH ONES (`:added` on :run/done) and
                      ;; this line does not work it out: a resumed run puts a replayed
                      ;; call's answer BEHIND the call it answers
                      ;; (`.scratch/session-opening` ticket 02), so 'past the count of
                      ;; what we handed in' would file one of the CLIENT's messages as
                      ;; this run's own and drop the one that really was. :run/done
                      ;; follows RUN_ERROR too, so any run the kernel started leaves its
                      ;; full returned side on disk -- but it lands one beat AFTER the
                      ;; terminal frame, so a reader racing the consumer may not see it
                      ;; yet.
                      ;; AND A RUN WHOSE CALL NEVER OPENED AN ENVELOPE STILL LEFT A REQUEST: flush what
                      ;; is still held (a no-op when `:model/start` already wrote it) -- the person's
                      ;; question has to be on the record the moment the run is over, call or no call.
                      (flush-request!)
                      ;; THE RUN'S OWN ACCOUNT, CHECKED RATHER THAN COPIED: every message it added was
                      ;; written as it arrived (`:message/added`), so this writes only what did NOT
                      ;; come through -- a code path that forgot to say so. It is written late AND
                      ;; NAMED: the record keeps the message (a missing one is a lie a rebuild would
                      ;; repeat) and the log says which.
                      (let [account (vec (:added ev))
                            written (get @state :reported 0)
                            missing (subvec account (min written (count account)))]
                        (when (seq missing)
                          (log/warn! :run/messages-unreported
                                     {:thread-id thread-id :run-id run-id
                                      :reported written :added (count account)
                                      :missing (count missing)})
                          (log-messages! thread-id run-id missing)))
                      ;; A TURN CLOSES HERE, AND ONLY WHEN ITS RUN LEFT NOTHING OWED (ADR 0006
                      ;; decision 3): AFTER THE RETURNED TAIL HAS LANDED, because the counts it
                      ;; carries (`harness.edge.turn/answer`) include the assistant messages this
                      ;; run just wrote.
                      (when (:turn/closes? @state)
                        (let [counts (turn/answer (sessions/fold-value thread-id :turn))
                              from   (:turn/from @state)
                              to     (:turn/to @state)]
                          (family-send! thread-id
                                        (cond-> {:type "turn/end" :seq to
                                                 :numbers (merge {:seqFrom from :seqTo to} counts)}
                                          ;; THE TURN'S NAME IS DERIVED, NOT MINTED: the record line
                                          ;; it opened on. Nothing has to be kept for it to be
                                          ;; stable, the same reason a call's id is `<run>-m<n>`.
                                          (some? from) (assoc :turnId (str thread-id "-t" from)))))
                        ;; THE NEXT TURN COUNTS FROM ZERO: 'this turn', not 'since this session
                        ;; began'. `set-fold-value!` is the door an on-demand consumer comes
                        ;; through, and the write stream takes it from here.
                        (sessions/set-fold-value! thread-id :turn (turn/state-init)))
                      ;; AND THE NUMBERS ARE WRITTEN ONE LAST TIME FOR THIS RUN, HERE AND NOT AT
                      ;; THE TERMINAL FRAME (ticket 01 of `.scratch/session-numbers-in-the-store`):
                      ;; the returned tail has just landed (`log-messages!` above), and the context
                      ;; split is counted from the tail -- so a snapshot taken at the terminal
                      ;; frame would be the one the old `reload`-after-the-run existed to correct.
                      ;; A session this process does not hold writes nothing (`numbers-snapshot`
                      ;; answers nil).
                      (when-some [snap (numbers-snapshot thread-id)]
                        (project/remember-numbers! thread-id snap))
                      ;; A REPLAYED ANSWER THAT HAD NOWHERE TO GO gets a line of its own:
                      ;; the message went to the end of the history instead of behind
                      ;; its call, which is the shape the vendor refuses on the next
                      ;; request. It is not thrown -- the run was answered as well as
                      ;; this history allows -- but somebody reading the log later needs
                      ;; to see it.
                      (when (seq (:unplaced ev))
                        (log/warn! :run/replay-unplaced
                                   {:thread-id thread-id :run-id run-id
                                    :tool-call-ids (:unplaced ev)})))
                      (do ;; Tool-lifecycle events are audit lines, not wire frames:
                          ;; each lands as its own jsonl line, keyed by toolCallId.
                          ;; THE RETURNED SIDE, ONE MESSAGE AT A TIME, WRITTEN WHERE IT HAPPENED: the
                          ;; kernel says what it just added (`:message/added`) and the edge writes that
                          ;; row NOW -- so the record is as current as the frames the same run is putting
                          ;; on the wire, and the trajectory can show a tool's RESULT while the run is
                          ;; still going.
                          ;; THE KERNEL ASKS WHETHER THE DRAIN HAS CAUGHT UP, and this is where the
                          ;; answer comes from: everything put on the channel BEFORE this event has
                          ;; been dealt with -- this loop is the one that deals with them, in order --
                          ;; so the promise the kernel is waiting on is settled here
                          ;; (`harness.kernel.loop/answer-drain!`, which is the one spelling of the
                          ;; consumer's half of that barrier).
                          (loop/answer-drain! ev)
                          (when-let [[kind payload] (lifecycle-record ev)]
                            ;; THE MODEL FAMILY GOES OUT HERE (ADR 0006 decision 4), stamped with the
                            ;; line's own number -- which `log!` now ANSWERS, because the write is
                            ;; synchronous (ADR 0007). Nothing is read off disk for the number.
                            (let [offset (log! thread-id run-id kind payload)]
                              ;; A RECORDED KIND THAT IS ALSO A FACT GOES OUT HERE. The list is the
                              ;; family's own (`harness.edge.mux/fact-types`), not a second spelling: a
                              ;; name added to the writer and not to the ring (or the reverse) is a fact
                              ;; nobody can ask for again (see that Var).
                              ;; AND THE REQUEST THIS CALL IS ABOUT TO SEND LANDS HERE, right behind
                              ;; `model/start`'s own row (ticket 04 of `.scratch/record-envelopes`):
                              ;; the envelope is open from this line on, so the rows this run wrote for
                              ;; the call are read INSIDE it. Only a run's FIRST call has such a
                              ;; batch, and the queue empties itself -- a later call writes nothing.
                              (when (= "model/start" kind) (flush-request!))
                              (when (contains? mux/fact-types kind)
                                (family-send!
                                 thread-id
                                 (cond-> {:type kind :seq offset}
                                   ;; THE START CARRIES THEM TOO, and that is the whole point of
                                   ;; the `:start` phase: the request has just gone out, so the
                                   ;; strip can be initialized (counts, zeroes, and the estimate
                                   ;; of what was sent) instead of drawing nothing until the
                                   ;; vendor answers. See `initial-numbers`.
                                   (= "model/start" kind) (assoc :payload payload
                                                                :numbers (live-numbers-slice
                                                                          thread-id :start))
                                   ;; THE NUMBERS RIDE ON THE END, and they are the session's own
                                   ;; folds at this moment -- ADR 0006 decision 8: the fold has to
                                   ;; be in memory for this to have anything to say.
                                   (= "model/end" kind) (assoc :payload payload
                                                              :numbers (live-numbers-slice thread-id))))
                                ;; AND THE STORE IS TOLD THE SAME THING, AFTER THE PUSH (ticket 01
                                ;; of `.scratch/session-numbers-in-the-store`): the snapshot the
                                ;; strip's FIRST read answers from is written at the one moment a
                                ;; fold has just moved -- no timer, nothing folded twice -- and it
                                ;; is written after the frame so that a store that refuses cannot
                                ;; swallow a frame the client is waiting for. Both readings come
                                ;; from the same folds, so they cannot disagree.
                                (when (= "model/end" kind)
                                  (when-some [snap (numbers-snapshot thread-id)]
                                    (project/remember-numbers! thread-id snap)))))
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
                                                ;; EVERY MESSAGE THIS RUN DERIVED FOR ITSELF RIDES
                                                ;; WITH ITS START: the injections folded in beside
                                                ;; the conversation (a body an earlier turn loaded,
                                                ;; a job that ended between two runs) are all in
                                                ;; the first model call's history, so a card for
                                                ;; each is due before anything the model says.
                                                ;; They are ordinary user messages to the
                                                ;; provider; this is the only place a person
                                                ;; gets to see them in the conversation column
                                                ;; (see ag-ui/injected-frame for why the client
                                                ;; never sends them back). THE ONE NUMBERING
                                                ;; IS THE HISTORY'S OWN, so the frames come out
                                                ;; in the order the model read them -- and the id
                                                ;; is the run's own name plus that place.
                                                ;;
                                                ;; `-pre<i>`, AND NOT THE `-ctx<n>` THE KERNEL'S
                                                ;; OWN SPLICES CARRY. Both are 'context injected
                                                ;; into this run', and both count from zero, so one
                                                ;; spelling for the two made a collision -- and a
                                                ;; frame's id is what the record folds a card
                                                ;; under (`harness.kernel.frames/apply-frames`,
                                                ;; then a first-wins dedupe in `replay/append-new`
                                                ;; and `sessions/append!`), so the collision would
                                                ;; have DRAWN ONE CARD FOR TWO: the run's start
                                                ;; (only the edge reaches this) and a mid-run
                                                ;; splice (only the kernel does) would fold into
                                                ;; one message, and one of the two injections
                                                ;; would be missing from a rebuilt conversation.
                                                ;; `pre` = folded in BEFORE the first call; the
                                                ;; kernel's keep `ctx`, which is the name of the
                                                ;; event they answer (`:context/injected`).
                                                (cond-> (into (if-some [compacted (:compacted @state)]
                                                                ;; THE COMPACTION THIS RUN WOKE UP TO ALREADY RIDES FIRST,
                                                                ;; because it happened FIRST: the trigger at the run's head
                                                                ;; (`compact-if-pressured!`, whose answer this key holds)
                                                                ;; measured the record BEFORE the injections below were derived,
                                                                ;; so the summary the model is now reading stands in front of
                                                                ;; material derived after it. A run the trigger left alone has
                                                                ;; no such key and this is the empty vector it takes instead.
                                                                [(ag/compacted-frame compacted)]
                                                                [])
                                                              (map-indexed
                                                               (fn [i message]
                                                                 (ag/injected-frame (str run-id "-pre" i) message))
                                                               injected))
                                                  ;; AND THE CONVERSATION THIS RUN WROTE
                                                  ;; PART OF rides with it, for the same
                                                  ;; reason and on the same run: the page
                                                  ;; that minted this session holds no
                                                  ;; window, so the messages the birth
                                                  ;; put in the conversation can only
                                                  ;; reach it here
                                                  ;; (`ag/conversation-snapshot`).
                                                  snapshot (into [snapshot]))))]
                            ;; THE RECORD'S FRAME OR THE CLIENT'S, and a stop's cut-off answers are
                            ;; the record's alone: a client told the call returned would draw it `Done`
                            ;; instead of the cancellation it asked for (see `recorder` and
                            ;; `harness.kernel.event/cut-off-result`).
                            (if (= :run/cut-off-result (:type ev))
                              (record! frame)
                              (emit frame)))
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
                  ;; AND THE READER IS TOLD, because a PUSH HAS NO CLOSE TO CARRY THE NEWS: the
                  ;; run's frames go down `events.mux` now, so a client handed no terminal waits
                  ;; forever (the SSE response used to end for it). The RECORD is left exactly as
                  ;; it is -- it really did stop mid-sentence, and `rebuild` is the door that
                  ;; closes such a log off -- while whoever is looking gets the one ending the
                  ;; vocabulary has.
                  (try (mux-broadcast! thread-id
                                       (convert (ev/run-error
                                                 "the run's event channel closed without a terminal frame")))
                       (catch Throwable _ nil))
                  (log/warn! :run/events-closed-without-terminal
                             {:thread-id thread-id :run-id run-id
                              :last      (:last @state)})))))))
      ;; WHATEVER IS STILL HELD GOES DOWN NOW (ticket 04 of `.scratch/record-envelopes`): a run
      ;; that never reached a model call -- no provider, a refusal on the way in -- still leaves
      ;; the request it was assembled for on the record. A no-op when the envelope already
      ;; opened and wrote it.
      (flush-request!)
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
        (sessions/settle! thread-id run-id (:frames @state) (or (:terminal-line @state) (:last-line @state))
                          (some-> *written-rows* deref entry-lines))
        (log/error! :run/crashed t {:thread-id thread-id :run-id run-id
                                    :last      (:last @state)})
        ;; AND THE READER IS TOLD: a crashed run emits no terminal, and a reader on the downlink
        ;; would otherwise wait forever. GUARDED, because the converter is one of the things
        ;; that can BE what threw.
        (try (mux-broadcast! thread-id (convert (ev/run-error (ex-message t))))
             (catch Throwable _ nil))
        nil)))))


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

(defn- rung
  "Answer RESP after saying a HOST-LEVEL fact changed -- a row in the sidebar's listing, a
  project, an archive flag. The mutation has already happened by the time a route builds
  its answer, and the host stream (`events.host`) is how every OTHER page hears about it;
  a route that answers a host-level write wraps its answer in this."
  [resp]
  (host/ring!)
  resp)

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

(defn- record-shape
  "THREAD-ID's record's ENVELOPE VIOLATIONS, or nil when it has none (or cannot be read).

  A RUN THIS PROCESS IS ANSWERING IS NOT TORN, which is why the registry is asked first:
  the file cannot tell a run still going from one whose process died, and the registry can
  (`running?`). Reading the log is best-effort -- a record that is corrupt or missing is
  left to the reader that refuses it by name, not turned into a second refusal here.

  TWO QUESTIONS, ONE READ: `:normalized` (`harness.edge.normalized` -- may these bytes be written
  to any further?) and `:violations` (did a RUN fall out of its envelope?). Both are asked of the
  same rows, and this record can be hundreds of megabytes of them."
  [thread-id]
  (when-not (running? thread-id)
    (try
      (when-some [f (replay/find-log (home/projects-dir) thread-id)]
        (let [rows (replay/read-records f)]
          {:normalized (normalized/normalized? rows)
           :violations (seq (replay/envelope-violations rows))}))
      (catch Throwable _ nil))))

(defn- refuse-torn-record!
  "The answer a client gets when a run aims at a record whose messages fell OUT of their
  envelopes. 409: the conversation exists, and continuing it would fold half a pair.

  THE REFUSAL NAMES THE DOOR OUT (fork), because there is one: the fork copies the record
  up to a boundary and closes what a cut left open, so the work is not lost -- it is
  continued from a record that reads.
  TEMPORARY (owner, 2026-09-27): this gate exists to keep OLD JSONL records honest until the
  format settles; it is meant to be deleted then."
  [thread-id torn]
  (api-response
   409
   {:error    (str "session " (pr-str thread-id) " has " (count torn)
                   " run(s) that never reached a terminal frame, so a run cannot continue"
                   " it: a record is folded from its event pairs, and one missing half a"
                   " pair would hand the model half a conversation. Rebuild it"
                   " (POST /api/threads/<stem>/rebuild) to close those runs off, or fork it"
                   " from before a compaction (POST /api/threads/<stem>/fork).")
    :threadId   thread-id
    :reason     "torn-record"
    :violations (vec torn)}))

(defn- refuse-unnormalized!
  "The answer a client gets when a run (or a compaction) aims at a record that is NOT 已重整化.
  409, not 400: the conversation exists and is perfectly readable -- what it cannot be is WRITTEN
  to, because this record's rows and frames do not say the same thing yet, and every further run
  would append to a history whose reading is a guess.
  
  THE DOOR OUT IS THE FORK, and the sentence names it and says what to do with it, because a
  refusal that only says no is a wall. `verdict`'s reasons say WHAT is missing -- one sentence
  each, for the client to draw beside the button (they are the same sentences `sofar` answers).
  
  TEMPORARY (owner, 2026-09-28): 'every message must be wrapped by its start/end envelope' is the
  rule this gate makes real, and forking is how a record that breaks it is normalized."
  [thread-id verdict]
  (api-response
   409
   {:error    (str "this conversation's record is not normalized ("
                   (str/join "; " (:reasons verdict))
                   "), so continuing it would write to a history no reader can fold back to what"
                   " happened. Fork it to normalize a copy -- POST /api/threads/" thread-id "/fork"
                   " -- and continue in the new session.")
    :threadId thread-id
    :reason   "unnormalized"
    :normalizationReasons (:reasons verdict)}))

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

(defn- cancel-post
  "POST /api/threads/<stem>/cancel -- stop the run THIS PROCESS has going for STEM.

  THE SERVER'S HALF OF THE COMPOSER'S STOP (ticket 07 of `.scratch/session-after-refresh`).
  It RINGS the switch of the run registered for this conversation, and the loop that
  drives that run does the rest: the calls still in flight are stopped (a command's
  process tree with them), every unanswered call is given `frames/cut-off-result` so the
  record keeps no open call, and the run reaches a terminal that says a person stopped
  it. None of that happens HERE -- see the next paragraph.

  IT ANSWERS WITHOUT WAITING FOR ANY OF IT, and that is a decision rather than
  convenience. A stop is a SIGNAL, not a join: this route cannot know how long a command
  takes to die, and the fact a client needs next -- is a run still going here -- is
  already readable (`sofar`'s `:state`, and the state frame the window's feed sends when
  it changes). Waiting would also make the stop's own answer depend on the thing it is
  stopping, which is the shape that made the browser's abort useless: it closed a stream
  and reported success while the run went on.

  A CONVERSATION WITH NO RUN GOING IS REFUSED BY NAME (409), and both halves of that
  boundary land on this one answer: an id this home has never been asked to keep, and one
  whose run has already reached its terminal. The sentence says what this process can
  actually see -- nothing of OURS is running -- which is the honest half: a run in
  ANOTHER process is not ours to stop, and nothing here is entitled to guess that it is
  (`harness.edge.sessions/live-entry` is about THIS process, deliberately)."
  [stem]
  (let [thread-id (str stem)]
    (if-some [run-id (sessions/cancel! thread-id)]
      (api-response 200 {:threadId thread-id :runId run-id :state "stopping"})
      (api-response
       409
       {:error    (str "nothing to cancel for " (pr-str thread-id) ": this process has no run"
                       " of that conversation going, so there is no stop switch to ring --"
                       " the conversation has never run here, or the run it had already"
                       " reached its terminal frame, or another process is serving it. Send a"
                       " message and stop the run that comes back, or read the conversation"
                       " and continue it.")
        :threadId thread-id}))))
(defn- start-run
  "Start a run the door let through and answer an ACK instead of a stream.

  THE FRAMES GO OUT ON THE DOWNLINK (`events.mux`, ADR 0004) to every page that declared
  this conversation -- the sender included, which is why it subscribes BEFORE it asks here.
  THE SSE RESPONSE IS GONE (ticket 05): a run has ONE carrier now, the downlink, so this is
  the only door. The record, the state and `settle!` are the emitter's, unchanged."
  [input run-id]
  (let [state (atom {:terminal nil :last nil :frames [] :reported 0})]
    (run-agent! state input run-id)
    (api-response 200 {:threadId (str (:threadId input)) :runId (str run-id)})))

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
  session table, the jsonl writer and the hook sink are this namespace's, and a second
  implementation of them inside the capability is how the two would drift. It is
  `run-agent!`'s body MINUS everything a client owns: no SSE emitter, no run registry,
  no interrupts, no claim at the door.

  THE CONVERSATION IS THE SESSION'S, exactly as a person's is, and that is what makes
  a delegation readable afterwards: the task is the subagent's own first entry
  (`sessions/append!`, source `client` -- the delegating model is this session's
  client and there is no other), and the ANSWER enters the same way a person's run's
  does, by folding the run's own frames at the end (`sessions/settle!`). A record that
  held only message rows would rebuild as a conversation with no answer in it.

  THE SUBAGENT IS NOT REGISTERED AS A RUNNING SESSION, deliberately. `running?` is the
  answer a conversation a PERSON can act on gives -- the composer's gate and the run
  door's second-run refusal both read it -- and a subagent accepts neither. Its
  liveness is `harness.cap.subagents/live`, keyed the same way, which is what the
  sidebar and the panel ask.

  NO FRAMES ARE SENT, AND THE FRAMES ARE STILL WRITTEN DOWN. Nothing is watching this
  run: its entire output is one string, which is the result of the tool call that
  asked for it, so there is no emitter, no client and nobody to fail to reach. What IS
  built is the same AG-UI conversion a client's run gets, and every frame it produces
  lands in the subagent's jsonl as an `event` line -- because the record's SHAPE is
  what makes it readable, and both readers of a conversation (rebuild, and
  replay/history) fold those lines and nothing else.

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

  THE PARENT'S RECORD LEARNS THE CHILD'S NAME FIRST, via the `delegation` row
  below (see the header's fact list): the child's own file says what it was asked,
  but the parent's record is what the card in the PARENT's conversation reads --
  and it must say which of the parent's calls opened which session, because that
  is a fact the parent holds and the child cannot state for it. THE KEY IS
  tools/*tool-call-id*, which the seam binds around every tool body: read HERE,
  on the tool thread itself, rather than guessed from position -- two delegations
  in one turn are two tool threads whose completion order has nothing to do with
  their start order, and a positional guess would pair the wrong card to the
  wrong session quietly. The row is written by `log!` on the parent's file, whose
  global lock is what makes two concurrent delegations' rows land whole; one row,
  one write, no torn records.

  THE PROVIDER IS THE PARENT'S, RESOLVED NOW. A subagent has no session of its own to
  resolve from, and re-resolving from the default tier would quietly move it to a
  different model than the conversation that delegated to it is holding.

  A RUN THAT DIED STILL LEAVES WHAT IT SAID. The frames collected so far are folded in
  the `finally`, which is the agent route's own rule for the same reason, and the
  answer is then the honest nothing `answer-of` words for an empty history -- the
  delegation is a tool call, and a tool call that threw would take the parent's run
  down with it."
  [{:keys [parent-thread-id thread-id definition task]}]
  (let [run-id    (str (java.util.UUID/randomUUID))
        frame-seq (atom 0)
        provider (providers/current-provider parent-thread-id)
        ;; ONE converter per run, like the agent route's: it owns the open-message
        ;; state machine, so building it per event would restart every message id.
        convert  (ag/outbound thread-id run-id)
        ;; THIS RUN'S FRAMES, collected for the one moment they become the
        ;; conversation. The agent route keeps the same atom for the same reason: it is
        ;; the one place that sees every frame exactly once.
        frames   (atom [])
        ;; AND THE LINE THE RUN'S TERMINAL FRAME IS WRITTEN ON, held for the `settle!` in the
        ;; `finally` below for the same reason the agent route holds it (see `runner`): the entries
        ;; it numbers are not in the table until the frames become the conversation.
        terminal-line (atom nil)
        ;; AND THE LAST LINE THIS DELEGATION WROTE, for the `finally` when it died without a
        ;; terminal -- the same fact the agent route keeps (see `runner`).
        last-line (atom nil)
        ;; AND THE ONE PLACE THE AGENT ROUTE ALSO USES for its text (`text-lines`): the messages
        ;; still open live here, and what reaches the record is the snapshot, not the token.
        text     (atom {})]
    (binding [hook/*sink* (sink-for thread-id run-id)
              ;; THE DELEGATION'S OWN ROWS, for the same reason and the same reader as the agent
              ;; route's (`entry-lines`).
              *written-rows* (atom [])]
        ;; THE PARENT LEARNS THE CHILD'S NAME FIRST, before the child writes a row of
        ;; its own: a card in the PARENT's conversation can then be clicked while the
        ;; subagent is still working, which is the entire point of the timing.
        ;; THE KEY IS tools/*tool-call-id*, read HERE on the tool thread the seam bound
        ;; it around -- NOT guessed from position. Two delegations in one turn are two
        ;; tool threads whose completion order has nothing to do with their start order,
        ;; and a positional guess would pair the wrong card to the wrong session
        ;; quietly. Written even when the id is somehow absent, as a nil: the card then
        ;; simply never becomes clickable, and a missing id is the honest spelling of a
        ;; pairing nobody can make.
        (log! parent-thread-id run-id "delegation"
              {:toolCallId tools/*tool-call-id*
               :subagent   (:name definition)
               :threadId   thread-id})
      (try
        ;; THE TASK IS THIS CONVERSATION'S FIRST ENTRY, and it is NAMED by the run
        ;; (`-task`) the way the converter names the messages it mints: an entry with
        ;; no id cannot be deduped, by `append!`, by the fold or by a repeat.
        (let [added (sessions/append! thread-id run-id
                                      [{:id (str run-id "-task") :role "user" :content task}])]
          ;; ONE ROW PER ENTRY, and the envelope says whose element of the array it
          ;; was. `client` is the honest word here: this session has exactly one
          ;; client -- the delegating model -- and `replay/conversation-sources` reads
          ;; that source as a SPEAKING part of the conversation, which is what puts
          ;; the task in front of a reader of the panel or of a rebuild.
          (doseq [[i m] (map-indexed vector added)
                  :let  [shown (first (ag/provider-messages (sessions/model-view [m])))]
                  :when (some? shown)]
            (log! thread-id run-id "message" shown
                  (fn [offset] (sessions/land-at! thread-id run-id (or (:id m) i) offset))
                  (cond-> {:source "client"} (:id m) (assoc :id (:id m)))))
          (let [;; THE CONVERSATION AS THE SESSION HOLDS IT, which by now includes the
                ;; task above. The opening blocks are NOT spliced in here: they enter a
                ;; conversation at its birth (`.scratch/session-opening`), and this
                ;; conversation has exactly one birth -- the entry just written.
                ;; THE SAME ASSEMBLY + SIGNATURE THE AGENT ROUTE ASKS FOR: the
                ;; subagent's `<subagent>` block comes from this capability's own
                ;; SystemPrompt row, and the hash is what the row below keeps.
                sys       (system-prompt/assemble* thread-id)
                assembled (ag/inbound (sessions/model-view (sessions/messages thread-id))
                                      (:text sys)
                                      nil)
                ;; WHAT THE PRE-LLM STEP DERIVED FOR THIS RUN (a skill body, a job's
                ;; ending) rides beside the conversation. The agent route's lines are
                ;; these, and the boundary between them matters for the same reason
                ;; there: what `before-llm` added is THIS run's own, and the rest of the
                ;; array belongs to the runs that produced it.
                applied   (project/before-llm assembled thread-id)
                injected  (subvec applied (count assembled))
                messages  (llm/thinking-mode-history applied provider)]
            ;; THE SYSTEM MESSAGE IS A `message` ROW, like the agent route's, and for
            ;; the same reason: a `message` row IS an element of the array the model
            ;; read. THE `<subagent>` BLOCK IS IN IT -- that is what the capability's
            ;; own SystemPrompt row contributes to `assemble`, and it is how the model
            ;; is told which subagent it is and what it cannot do.
            (let [prompt (or (some #(when (= "system" (:role %)) (:content %)) messages) "")]
              (log! thread-id run-id "message" {:role "system" :content prompt} nil
                    {:source "system-prompt" :hash (system-prompt/digest prompt)
                     :hooks-names-hash (:hooks-names-hash sys)
                     ;; the same envelope `:tools` the agent route writes -- see there.
                     :tools (tools/specs thread-id)}))
            (log-messages! thread-id run-id injected)
            (log! thread-id run-id "provider/init" (provider-line provider :inherited))
            (let [;; THE KERNEL WRITES ITS OWN MESSAGES, on this route too (ticket 02 of
                  ;; `.scratch/record-stream`): the door is the same shape as the agent route's, and
                  ;; the account is what keeps `:run/done` from writing the same row twice.
                  reported (atom 0)
                  write!   (fn [message]
                             (log-message! thread-id run-id message)
                             (swap! reported inc))
                  events (loop/run-chan provider messages {:thread-id  thread-id
                                                         :write!     write!
                                                           :resume     []
                                                           :before-llm project/before-llm
                                                           :tool-signature context/tool-signature
                                                           ;; A DELEGATION INHERITS THE PARENT'S TIER, so it inherits
                                                           ;; the parent's idle guard with it -- read on the parent's
                                                           ;; thread, which is the session harness.edn was composed for.
                                                           :idle-timeout-ms (llm-timeout/idle-timeout-ms parent-thread-id)
                                                           :idle-timeout-retries (llm-timeout/retries parent-thread-id)})]
              (loop []
                (if-let [ev (async/<!! events)]
                  (if (= :run/done (:type ev))
                    (do
                      ;; THE RETURNED SIDE, AS THE KERNEL NAMES IT (`:added`), exactly
                      ;; as the agent route reads it -- 'past the count of what we
                      ;; handed in' would file an entry of the conversation as this
                      ;; run's own. And the answer is the last thing the subagent
                      ;; actually SAID rather than the last frame on the wire.
                      ;; WHAT THE KERNEL WROTE IS ALREADY ON DISK: this writes only the tail it never
                      ;; got to -- which is nothing, unless the write itself failed (the row is then
                      ;; still the account's, and the account is how the reconciliation knows).
                      (log-messages! thread-id run-id (drop @reported (:added ev)))
                      {:answer (answer-of (:history ev))})
                    (do
                      ;; Tool-lifecycle and model-call events are audit lines rather
                      ;; than wire frames, keyed by toolCallId.
                      ;; THE KERNEL'S QUESTION IS ANSWERED ON THIS ROUTE TOO: everything put on this
                      ;; channel before it has been dealt with, and the promise is how it learns that
                      ;; (`harness.kernel.loop/answer-drain!`).
                      (loop/answer-drain! ev)
                      (when-let [[kind payload] (lifecycle-record ev)]
                        (log! thread-id run-id kind payload))
                      ;; AND THE WIRE FRAMES GO ON THE RECORD, in the subagent's own
                      ;; file. The terminal frame's line is the one this run's entries
                      ;; land on (`sessions/land!`), which is how a rebuild reproduces
                      ;; the same numbering a live session hands out.
                      (doseq [frame (convert ev)]
                        ;; THE NUMBER IS STAMPED BEFORE THE FRAME IS WRITTEN, because
                        ;; the RECORD and the BUS must carry THE SAME one: a follow
                        ;; channel replays what the record holds and then takes what the
                        ;; bus hands it, and the only way it can drop a frame it was
                        ;; handed twice -- once by each source -- is to compare numbers
                        ;; that mean the same thing on both sides. One counter per
                        ;; thread, kept by the only writer of this thread's frames (a
                        ;; subagent thread runs exactly ONE delegation, so the run is
                        ;; the thread here). See `follow-get`.
                        (let [f (assoc frame :seq (swap! frame-seq inc))]
                          ;; THE SAME TWO EXCEPTIONS AS THE AGENT ROUTE (`reasoning-frame?`,
                          ;; `wire-only-frame?`), over the same helper: a subagent's reasoning
                          ;; frames are broadcast and kept in memory and NOT recorded -- the
                          ;; delegation's own `message` rows carry the text back -- and the idle
                          ;; guard's frame is the wire's alone in a delegation too.
                          (doseq [row (text-lines text f)]
                            (when-not (or (reasoning-frame? row) (wire-only-frame? row))
                              (let [offset (log! thread-id run-id "event" row
                                                (when (contains? terminal (:type row))
                                                  (fn [offset] (reset! terminal-line offset))))]
                                (when (some? offset) (reset! last-line offset)))))
                          ;; AND THE SAME FRAME GOES ON THE BUS: the record is not where
                          ;; a panel watches from -- it is where a panel catches up.
                          (frame-bus/publish! thread-id f)
                          ;; AND THE SAME NUMBERED FRAME GOES DOWN THE DOWNLINK (`events.mux`),
                          ;; so a panel can read the child's live frames from the one page-wide
                          ;; socket (ticket 04) instead of holding a channel of its own. The `:seq`
                          ;; is the same counter the record carries, which is what makes the
                          ;; boundary between the replay and the live tail exact.
                          (mux-broadcast! thread-id f)
                          (swap! frames conj f)))
                      (recur)))
                  ;; THE CHANNEL CLOSED. The kernel closes it after :run/done, so an
                  ;; ordinary ending came through the branch above; arriving here means
                  ;; the run stopped without saying goodbye, and the honest answer is
                  ;; the one `answer-of` words for an empty history.
                  {:answer (answer-of [])})))))
        (catch Throwable t
          ;; A DELEGATION THAT DIED IS NOT A DELEGATION THAT ANSWERED, and the parent
          ;; hears the difference in the answer's own words. The reason goes to the
          ;; process log: the whole conversation is on the record for whoever reads it
          ;; next, and a throw out of here would take the parent's run down with it.
          (log/error! :run/subagent-crashed t {:thread-id thread-id :run-id run-id})
          {:answer (answer-of [])})
        (finally
          ;; THE FRAMES BECOME THE CONVERSATION NOW, once, at the end -- a half-written
          ;; answer is not a turn. `settle!` also carries the state the terminal frame
          ;; says (settled / unfinished), so the next reader of this session gets the
          ;; same answer the record would give.
          (sessions/settle! thread-id run-id @frames (or @terminal-line @last-line)
                            (some-> *written-rows* deref entry-lines)))))))

;; The door repairs a run that never closed before it reads the record (see the 4b decision
;; below); the repair is defined with the read side further down, so it is named here.
(declare close-off-open-run!)
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

      ;; 4b. THE RECORD'S OWN ROWS AND ENVELOPES, asked of ONE read after the repair.
      ;; Two refusals live here and they are different questions about the same bytes:
      ;;
      ;;   * NOT NORMALIZED (`harness.edge.normalized`): this record's rows and frames do not say
      ;;     the same thing (a tool call answered only by a frame, a `START` with no `END`, an
      ;;     old-contract line). Writing another run into it would append to a history whose
      ;;     reading is a guess, so the run is refused and the sentence names the FORK -- which
      ;;     copies the conversation into a record that reads. TEMPORARY (owner, 2026-09-28).
      ;;   * TORN: a run that never reached a terminal frame. This is the 2026-09-27 gate, kept
      ;;     for the same reason and pointing at the same door.
      ;;
      ;; A RUN THAT NEVER CLOSED IS REPAIRED FIRST, NOT REFUSED: closing it off is what `rebuild`
      ;; has always done, and doing it here means a process that died mid-flight does not cost the
      ;; conversation its next run. The repair WRITES (a terminal frame and the row for every call
      ;; it answers), so the judgment is made on the record as it stands after it.
      :else
      (let [_     (close-off-open-run! thread-id (log-file-for thread-id))
            shape (record-shape thread-id)]
        (cond
          (and shape (:violations shape))
          (refuse-torn-record! thread-id (:violations shape))

          (and shape (not (:normalized? (:normalized shape))))
          (refuse-unnormalized! thread-id (:normalized shape))

          :else
          (start-run input run-id))))))

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
  is the same 'no trace on failure' the validation above already promises.

  ALL OF IT HAPPENS UNDER log-lock, and that is a requirement rather than a
  detail: a run writing its records resolves where its next line goes from the very
  binding this changes, and the bind MOVES the file those records are going into.
  The writer holds the same lock while it resolves a line's File (see `log!`), so
  serializing the two is what makes the outcome independent of who got there first,
  and a BIND AT FIRST SEND makes that a routine race rather than a rare one (the
  sidebar binds when the session's first message arrives, i.e. while the run is
  writing). It also has to be the WHOLE section: from-dir is where the log is NOW,
  so it must be read where nothing can move the file out from under it, and the
  binding must not be visible to the writer until the file has followed it. THE
  WRITE ITSELF IS NOT UNDER THIS LOCK -- the bytes belong to the record writer's
  consumer -- so what is serialized here is the placement decision, not the append."
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
            bound     (locking log-lock
                        (let [before   (project/binding-for thread-id)
                              from-dir (log-dir-for thread-id)
                              b        (try {:ok (project/bind! thread-id (str (:dir ok)))}
                                            (catch Throwable t {:error (ex-message t)}))]
                          (cond
                            (contains? b :error)
                            {:error (:error b)}

                            :else
                            (let [abs   (:ok b)
                                  to-dir (log-dir-for thread-id)
                                  ;; READ YOUR OWN WRITE, the same step `rebuild-post`
                                  ;; already takes (see its comment). The lines the writer
                                  ;; is still holding for this session were ADDRESSED to
                                  ;; the file `move-log!` is about to rename -- the lock
                                  ;; above stops new ones being addressed there, but a line
                                  ;; handed over before it is already queued, and the
                                  ;; consumer would write it to the old path afterwards and
                                  ;; recreate it: one conversation, two files, and the next
                                  ;; bind then refuses by name. Draining is what makes the
                                  ;; move and the queue one act.
                                  ;;
                                  ;; INSIDE THE CRITICAL SECTION, and it cannot deadlock:
                                  ;; the consumer takes no lock of ours (its prepare step is
                                  ;; `carry-back!`, which appends and renames files, and the
                                  ;; landing callback is a store write). BOUNDED, because a
                                  ;; DEGRADED thread -- a full disk -- must not hang a bind
                                  ;; forever: the wait times out and the move proceeds with
                                  ;; the residual risk the lock cannot close.
                                  _     (when (not= from-dir to-dir) (stream/flush! 5000))
                                  moved (try {:ok (move-log! thread-id from-dir to-dir)}
                                             (catch Throwable t {:error (ex-message t)}))]
                              (if-some [move-error (:error moved)]
                                {:move-error move-error :abs abs :before before}
                                {:abs abs :before before})))))]
        (cond
          (contains? bound :error)
          (api-response 400 {:error (:error bound)})

          (contains? bound :move-error)
          (api-response 400 {:error (:move-error bound) :threadId thread-id :dir (:abs bound)})

          :else
          (do (log! thread-id nil "project/bound" {:before (:before bound) :after (:abs bound) :via "http"})
              (host/ring!)
              (api-response 200 {:threadId thread-id :dir (:abs bound)})))))))

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
      (rung (api-response 200 {:threadId (project/register-session! thread-id)})))))

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
  "ONE CONVERSATION AS THE SIDEBAR READS IT -- and every field is a STORE fact plus
  the one fact a store cannot hold.

  WHAT LEFT THIS FUNCTION, and why it is worth saying out loud because it was the
  shape of the endpoint for its whole life: the two DISK facts. `:lastActivity` was
  the log's mtime and `:bytes` was its size, both read per row, per request. The
  owner's rule is that everything the left panel shows comes from the store except
  whether a run is in flight, so the size went away outright (nobody read it) and the
  time became `sessions.last_sent_at` -- written on every send, and backfilled for
  rows that predate the column (`harness.infra.db/sessions-remember-their-last-send`).
  A refresh of the sidebar is now one SELECT and one registry lookup instead of forty
  stat calls, which is what the panel's own frame rate was paying for.

  `:running` IS A STORE FACT NOW, and the rule it rode in on is the owner's own
  sentence, completed: everything the left panel shows comes from the store -- full
  stop. `sessions.run_state` is the LAST KNOWN run state, written by the same two
  verbs that move the in-process registry (`harness.edge.sessions/run-started!` /
  `run-finished!`), so inside this process the column and the registry move
  together; across a restart the column is what SURVIVES, and the registry is gone.
  The startup cleanup (`harness.cap.project/clear-startup-run-state!`) turns a
  `running` the previous process died holding back into `idle`, so the honest
  reading of a fresh process's listing is exactly what happened here -- nothing is
  running in it yet. Within one process the two sources stay consistent because
  they are written at the same two moments; there is deliberately no third thing
  that ORs a live registry against a stale column (see ticket 01 of
  `.scratch/sidebar-ws-and-run-state` for why a column that only ever says 'last
  known' beats two answers that can disagree).

  A TASK AND A PROJECT'S SESSION ARE THE SAME ROW, which is why there is no
  `task-row` any more: the two differed only in where their disk facts were asked
  from (a project's workspace vs a walk of the whole tree), and with those gone the
  store answers both the same way. What differs between them is the project, and
  that is the caller's structure rather than the row's.

  nil `:lastSentAt` is a conversation nothing has been sent to -- registered and never
  used, or a log deleted by hand. The client draws that in words rather than
  inventing a time."
  [{:keys [id archived? title last-sent-at run-state]}]
  {:threadId     id
   :archived     (boolean archived?)
   :running      (= "running" run-state)
   :lastSentAt   last-sent-at
   ;; THE NAME, from the store, nil for a session that has not been named yet (it
   ;; never ran, or it ran before the column existed and has not run since).
   :firstUserText title})

(defn- newest-first
  "The sidebar's order, within a project and in the task block alike: LAST SENT
  FIRST, and a conversation nothing has been sent to sorts LAST.

  THE KEY IS THE STORE'S CLOCK, NOT THE FILE'S. It used to be the log's mtime with
  'no log' treated as the most recent of all -- because a row with no log was a row
  somebody had just asked for, and burying it would hide the thing they were about
  to type into. That reasoning is gone with the lazy `new-task` button: a session is
  now created BY its first send, so a row with no time is not a new conversation but
  an ABANDONED one (registered by an older client, or a log deleted by hand), and
  the bottom of the list is where it belongs.

  NULL LAST IS SPELLED OUT rather than left to a sentinel, because the two things
  that could stand in for 'never' -- 0 and 'now' -- are both wrong in a way nobody
  would notice until a row jumped to the top.

  `sort` with a comparator, and Clojure's sort is STABLE, so sessions that tie keep
  the store's order (oldest created first)."
  [rows]
  (vec (sort (fn [a b]
               (let [ka (:lastSentAt a) kb (:lastSentAt b)]
                 (cond
                   (and (nil? ka) (nil? kb)) 0
                   (nil? ka)                 1
                   (nil? kb)                 -1
                   :else                     (compare kb ka))))
             rows)))

(defn- projects-body
  "GET /api/projects -- the sidebar's listing: every project this home knows, each
  with its sessions, PLUS every task this home knows.

  ONE SOURCE, ONE ANSWER: EVERY FIELD IN A ROW IS THE STORE'S. The store says which
  projects and sessions exist, which session belongs where, which are archived, what
  each is named and when it was last sent to -- and this endpoint stats no log, reads
  no size and walks no tree. It used to do all three: a log's mtime and length were
  read per row, per request, and the two sources were joined here on the reading side.
  The owner's rule retired that -- everything the left panel shows comes from the store
  except whether a run is in flight -- and the one field left outside it (`:running`)
  is a fact about THIS PROCESS, read from the session's run set (`session-row` says
  why). The tree was never able to answer the interesting question anyway: a directory
  of jsonl files cannot say which project a conversation belongs to, which is the whole
  reason the old logs/ were not migrated.

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
  is the raw tree view for anyone diagnosing).

  A SUBAGENT'S SESSION IS NOT LISTED, and this is the read that says so. Every row
  here is a conversation a person can open, continue and type into; a subagent's is
  none of those -- it belongs to the call that delegated to it, it ran once, and a
  row offering to open it would be inviting somebody to talk to something that
  cannot answer. It still HAS a store row and still appears in the tree, which is
  what lets rebuild, trajectory and the subagent panel find it by id."
  [] (let [by-project (group-by :project-id (remove :subagent (project/sessions)))
        tasks      (project/tasks)]
    {:projects (mapv (fn [{:keys [id canonical-path]}]
                       {:projectId id
                        :path      canonical-path
                        :sessions  (newest-first
                                    (mapv session-row (get by-project id)))})
                     (project/projects))
     :tasks    (newest-first (mapv session-row tasks))}))

(defn- projects-get
  "GET /api/projects -- `projects-body` as one JSON answer."
  [_req]
  (api-response 200 (projects-body)))

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

  EIGHT OF THE FOURTEEN ARE GETS, AND ONE VERB HAS BOTH METHODS: `stats`, `trajectory` and
  `delegations` only READ the log (a folded
  view of a finished conversation, and the per-turn timeline), `sofar` reads the
  same file while it is still being written, the window's two verbs (`feed` and
  `page`) read it in pieces, `jobs` reads the process's own JOB REGISTRY rather than
  any file, `todos` reads the STORE's task-list row, and the POST of `jobs` STOPS one
  of those
  of `.scratch/right-pane-tasks`: a person's stop, which claims no telling). The set
  stays closed and the 405 stays here -- what
  changed is that the sentence 'every verb on this shape is a POST' is no longer
  true, not where the refusal happens.

  `sofar` NAMES AN INTENT AND NOT A RESOURCE, which is why it is not `history`:
  what it answers is not the conversation (a thing a client could take over) but
  how much of it there IS at this moment -- the same log may answer differently a
  second later, and the answer says so (`replay/sofar`'s `:state`). Rebuild remains
  the door for 'give me the conversation, I will own it'.

  AND `page` READS A CONVERSATION IN PAGES rather than whole (ticket 05 of
  `.scratch/sessions-live-on-the-server`): the window ADR 0003 describes, for a conversation
  too long to send, and the route a reader scrolling up uses. It is a GET, and it is the one
  verb here that answers a piece of a conversation rather than all of it.

  AND `cancel` IS THE ONE THAT STOPS A RUN rather than reading it as it is: a POST
  aimed at one conversation, whose run -- if this process has one going -- is told to
  stop (`.scratch/session-after-refresh` tickets 07/08). It is the server's half of
  the composer's Stop, and the half the browser's own abort never had: pressing that
  one only closed the stream, while the run kept going and the record kept growing.
  A conversation with NO run going here is refused BY NAME rather than answered
  quietly -- 'it is already over' and 'it was stopped' are different things to know."
  #{"rebuild" "compact" "fork" "fork-points" "archive" "stats" "trajectory" "sofar" "page" "delegations" "frames" "cancel" "jobs" "todos"})

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
          (rung (api-response 200 {:threadId stem :archived (:ok written)})))))))

;; `live-numbers` is defined just below its one caller, and a `defn-` has to be known before it is
;; read: a plain `declare` rather than moving it up, because the live answer reads like the
;; fallback it guards -- the record read comes second, only when there is no live answer.
(declare live-numbers)
(defn- fold-requested?
  "Does this stats request ask for the RECORD's own fold rather than the stored snapshot?
  `?fold=1` is that question. IT IS THE REPAIR DOOR: the stored numbers are a last-known
  snapshot of a fold, so a record that disagrees with it -- edited by hand, or rebuilt --
  can be asked about directly, and a test can compare the two. The answer is NOT written
  back: a GET writes nothing, here as everywhere else on this edge."
  [req]
  (= "1" (get (query-params (:query-string req)) "fold")))

(defn- numbers-snapshot
  "THREAD-ID's numbers as the store would keep them: the folds this process holds, minus the
  two keys that are facts about a READ rather than numbers, plus WHEN the snapshot was taken.

  WHAT IS LEFT OUT, and why it is not a detail:

    - `:pressure` is the edge's pre-flight estimate of the NEXT request
      (`harness.edge.pressure`) -- a fact about a request nobody has made yet, so a stored
      one would be a lie the moment it was written. Nothing in the client reads it (it is
      not on `StatsPayload`).
    - `:incomplete` is 'the record's last frame is not terminal' AS OF A READ
      (`harness.edge.stats`). A snapshot cannot know it: the run it describes may have
      finished since, and the next reader would be told about a moment that is over. It is
      the one key a stored answer deliberately lacks, and `stats-get`'s docstring says so.

  NIL when this process does not hold the conversation, which is what makes both writers
  no-ops for a session another process is serving."
  [thread-id]
  (when-some [live (live-numbers thread-id)]
    (assoc (dissoc live :pressure :incomplete) :numbersAt (System/currentTimeMillis))))

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
  leaving them out; it does not fill them in. That is also why the stored answer has no
  `:incomplete`: the record's last frame is not something a snapshot can know, and the store
  does not pretend to (a reader that needs it asks `?fold=1`).

  AND IT CARRIES THE CONTEXT SECTION (harness.edge.context): how full the model's
  window is right now and what filled it. One question per fold, one read of the
  file -- the records are parsed once and both readers fold them -- so the strip
  under the composer and the ring beside the model cannot report two different
  moments of the same log. The composer's own contract (mount / session change /
  assistant message added / run over) is what asks, and asking once asks both.

  THREE SOURCES, IN THIS ORDER (ticket 01 of `.scratch/session-numbers-in-the-store`), and
  the order is what the route's cost is: THE LIVE FOLDS when this process holds the
  conversation (no file at all), then THE STORE ROW (`sessions.numbers`, one SELECT, written
  at every `model/end` and at every `:run/done` by this same edge), and finally THE RECORD --
  which is now the REPAIR path rather than the ordinary one: it answers when there is no row
  (a session nobody has watched, or a store that predates the column) and when the caller
  asks for it with `?fold=1`. A stored answer carries `:numbersAt`, the moment it was taken,
  because it is a LAST KNOWN value and not a live one; the live and folded answers carry no
  such key, since they are read at the moment they are sent.

  IT ASKS THE SESSION FOR THE RECORD (ticket 05): the READ half of a session's two streams
  locates the log, walks the tree and drops a torn tail, so the folding path never opens the
  file itself. What it then folds are facts ABOUT THE RECORD -- which model a call went to,
  what the vendor reported, where the gaps are -- and the rows are the session's to give.
  `:behind` is the other half, and it is the session's too: the number of lines the writer
  has not put on disk yet. ABSENT MEANS NOTHING IS PENDING (`record-health` draws the same
  line and for the same reason -- a `:behind 0` would be a field nobody reads). A count that
  is there is a warning that the numbers below it are that many record lines short of the
  conversation."
  [req stem]
  ;; THE LIVE ANSWER FIRST, and it is the whole of ticket 01: a session this process holds has
  ;; both folds on it (installed at birth above), so answering reads no file -- no locate, no
  ;; walk, no torn tail.
  (if-some [live (live-numbers stem)]
    (api-response 200 (cond-> (assoc live :threadId stem)
                        (pos? (stream/pending-count stem))
                        (assoc :behind (stream/pending-count stem))))
    ;; ...THEN THE STORE (`sessions-remember-their-numbers`), which is what makes the FIRST
    ;; read of a session nobody here holds ONE SELECT instead of a walk of the whole record.
    ;; The stored value is a LAST KNOWN snapshot and says when it was taken (`:numbersAt`), so
    ;; a reader can judge it; `?fold=1` asks for the record's own word instead (the repair
    ;; door, and the door a test compares through). A GET STILL WRITES NOTHING, whichever
    ;; door was used -- that rule is older than this column (`docs/rules/panel-data.md`).
    (if-some [stored (when-not (fold-requested? req) (project/numbers-for stem))]
      (api-response 200 (assoc stored :threadId stem))
    (let [read (sessions/read-records stem)]
    (cond
      (some? (:missing read))
      (api-response 404 {:error (:missing read) :threadId stem})

      (some? (:error read))
      (api-response 400 {:error (:error read) :threadId stem})

      :else
      (let [records (:ok read)
            folded  (try (let [records (vec records)]
                           {:ok (assoc (stats/records->stats records)
                                       :context  (context/records->context records)
                                       :pressure (pressure/records->pressure records))})
                         (catch Throwable t {:error (ex-message t)}))]
        (if (some? (:error folded))
          (api-response 400 {:error (:error folded) :threadId stem})
          (let [behind (stream/pending-count stem)]
            (api-response 200 (cond-> (assoc (:ok folded) :threadId stem)
                                (pos? behind) (assoc :behind behind)))))))))))
(defn- live-numbers
  "THREAD-ID's numbers as THIS PROCESS holds them -- the stats payload, the context section and
  the meter's band, all from the folds the session installed at birth -- or NIL, which is 'this
  process does not hold that conversation' and sends the caller to the record.

  BOTH FOLDS OR NEITHER. `stats` and `context` are registered together (`start!`), and half an
  answer from memory beside half from a file would be two moments of one log pretending to be
  one -- which is the failure `stats-get` has always refused."
  [stem]
  (when-some [st (sessions/fold-value stem :stats)]
    (when-some [ctx (sessions/fold-value stem :context)]
      (assoc (stats/stats-answer st)
             :context (context/state->context ctx)
             ;; THE BAND IS THE THIRD FOLD, and the surface it is asked about is the REQUEST the
             ;; next call would carry -- the conversation PLUS the system message in force
             ;; (`harness.edge.pressure/live-surface`; `sessions/messages` alone is the smaller
             ;; array, and the meter subtracts one surface from the other). Memory for a session
             ;; held here, and the same shape the compaction trigger measures mid-run.
             :pressure (pressure/band-pressure stem (pressure/live-surface stem)
                                               (compaction/config stem))))))

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

  LOCATION AND REFUSALS ARE THE SAME AS `stats`' -- the SESSION locates the log (ticket 06),
  404 for 'not here' with the locator's own sentence, 400 for 'here, and unreadable'. A log
  whose last run has not finished is NEITHER: it is read, and the answer says so, because
  looking at a session while it runs is the ordinary case rather than an error.

  AND IT CARRIES THE SAME `:behind` AS `stats`, for the same reason: this is the
  RECORD's trajectory, and the record can be behind the conversation being written to
  it. Absent means nothing is pending.

  IT IS ANSWERED FROM THE SESSION'S HELD VIEW WHEN THERE IS ONE (ticket 13): the fold happens
  once and is kept on the session, so a later ask reads a value rather than opening the file.
  Otherwise it is ONE STREAMING WALK (`sessions/fold-record`, ticket 12) whose rows go
  straight into the fold -- a record vector is never built (`sessions/read-records` is not
  called here any more)."
  [req stem]
  (let [;; THE SESSION'S HELD VIEW, built once on the first ask (ticket 13): `view-value` runs
        ;; ONE streaming walk and keeps the STATE on the session, and the write stream's step
        ;; advances it after that. nil means this process does not hold STEM at all.
        held   (trajectory/view-value stem)
        ;; ELSE ONE STREAMING WALK (ticket 12), with no session to keep it on.
        folded (when (nil? held)
                 (sessions/fold-record stem (trajectory/trajectory-init) trajectory/trajectory-step))]
    (cond
      (some? (:missing folded))
      (api-response 404 {:error (:missing folded) :threadId stem})

      (some? (:error folded))
      (api-response 400 {:error (:error folded) :threadId stem})

      :else
      (let [initial (if (some? held) (trajectory/trajectory-answer held)
                                  (trajectory/trajectory-answer (:ok folded)))
            behind  (stream/pending-count stem)
            header  (cond-> {:threadId stem :incomplete (:incomplete initial)}
                      (pos? behind) (assoc :behind behind))
            headers (merge {"Content-Type" "application/x-ndjson; charset=utf-8"}
                           (cors-headers (request-origin req)))
            watching (atom nil)]
        (hk/as-channel
         req
         {:on-open  (fn [ch]
                      ;; ON-OPEN RUNS ON http-kit'S OWN THREAD, after this ring map was
                      ;; returned -- so a throw here is a silently dropped connection
                      ;; rather than a 500. The stream is netted and simply ends.
                      (try
                        ;; THE HEADER FIRST: a reader knows what it is reading before turn one.
                        (hk/send! ch {:headers headers
                                      :body    (str (json/write-str header) "\n")}
                                  false)
                        (let [sent  (atom 0)
                              ;; THE TURNS THIS VIEW HAS NOW, then whatever finalizes later.
                              ;; Reading the view costs no file: it is a value on the session.
                              push! (fn []
                                      (let [payload (if (some? held)
                                                      (some-> (sessions/fold-value stem :trajectory)
                                                              trajectory/trajectory-answer)
                                                      initial)]
                                        (when (some? payload)
                                          (let [turns (:turns payload)
                                                ;; THE NEW FINALIZED TURNS, then THE OPEN ONE
                                                ;; again: a turn still growing is re-sent, and the
                                                ;; client replaces it by `:index` -- the same
                                                ;; in-place rule the window's frames use.
                                                finalized (max 0 (dec (count turns)))
                                                from      (min @sent finalized)]
                                            (doseq [turn (subvec turns from finalized)]
                                              (hk/send! ch (str (json/write-str turn) "\n") false))
                                            (reset! sent finalized)
                                            (when (pos? (count turns))
                                              (hk/send! ch (str (json/write-str (peek turns)) "\n") false))))))]
                          (push!)
                          (if (and (some? held) (mux/watching? stem))
                            ;; THE SESSION IS HELD *AND* SOMEBODY IS SUBSCRIBED TO IT, so every
                            ;; change to it can be a push -- and the session's own doorbell is
                            ;; the notification.
                            ;;
                            ;; AND THAT SUBSCRIPTION IS THE DOORBELL'S OWNER, which is the fix
                            ;; this route needed (`.scratch/memory-hygiene/` 票 02): THIS
                            ;; RESPONSE'S OWN CLIENT CANNOT BE OBSERVED AT ALL. http-kit reports
                            ;; no close for a plain streaming channel and its `open?` stays true
                            ;; after the reader is gone (measured), so a doorbell tied to this
                            ;; socket outlives every tab that ever opened one -- holding this
                            ;; closure (the channel, the folded payload) and pinning the session
                            ;; in memory, because `watched?` counts it as a reader. The page's own
                            ;; downlink IS observable, so that is what owns it.
                            (let [watcher (fn [_thread-id event]
                                            (case (:kind event)
                                              :entries (try (push!) (catch Throwable _ nil))
                                              :gone    (hk/close ch)
                                              nil))]
                              (reset! watching watcher)
                              (sessions/watch! stem watcher (fn [] (mux/watching? stem))))
                            ;; NOT PUSHABLE: this process does not hold the session (no view to
                            ;; push from), or NOBODY IS SUBSCRIBED to it -- and a stream whose
                            ;; reader is a bare HTTP client (a curl, a test) has no owner that
                            ;; could ever end its doorbell. Either way the reader gets the fold
                            ;; it asked for and the stream ENDS, which is the other half of 'do
                            ;; not hold what you cannot watch'.
                            (hk/close ch)))
                        (catch Throwable t
                          (log/error! :trajectory/stream-failed t {:threadId stem}))))
          :on-close (fn [_ch _status]
                      (when-some [watcher @watching] (sessions/unwatch! stem watcher)))})))))


(defn- frames-get
  "GET /api/threads/<stem>/frames -- the REPLAY half of the `follow` channel as JSON: this
  conversation's frames as the record holds them, ordered the way a runtime must read them
  (a `RUN_STARTED` first, then the conversation snapshot the frames cannot rebuild, then the
  frames), and whether this process is still running it.

  WHY THIS IS APART FROM `follow`: the LIVE tail moves to the page-wide downlink
  (`events.mux`, ADR 0004, ticket 04) -- one socket for every panel instead of a channel
  each -- and the record is still where a panel CATCHES UP. The two are joined by the
  frame's own `:seq` (the bus's counter, which the record carries too): a frame the live
  tail hands over that is not past the replay's last number is one the replay already gave.

  READ-ONLY, like `stats` and `follow`: nothing here writes."
  [stem]
  (let [located (try {:ok (replay/locate (home/projects-dir) stem)}
                     (catch Throwable t {:error (ex-message t)}))]
    (if (some? (:error located))
      (api-response 404 {:error (:error located) :threadId stem})
      (let [records  (try (stats/read-records (:ok located)) (catch Throwable _ []))
            frames   (->> records (filter replay/frame?) (mapv replay/payload))
            streamed (into #{} (keep :messageId) frames)
            opening  (remove #(contains? streamed (:id %))
                             (try (replay/messages-so-far records) (catch Throwable _ [])))
            snapshot (when (seq opening) (ag/conversation-snapshot opening))
            first-frame (first frames)
            started? (= "RUN_STARTED" (:type first-frame))
            started  (if started?
                       first-frame
                       {:type "RUN_STARTED"
                        :threadId stem
                        :runId (or (some-> (first records) :runId)
                                   (str (java.util.UUID/randomUUID)))})
            tail     (if started? (rest frames) frames)]
        (api-response 200
                      {:threadId stem
                       :running  (some? (subagents/live-subagent stem))
                       :frames   (vec (concat [(dissoc started :seq)]
                                              (when snapshot [snapshot])
                                              tail))})))))


(defn- delegations-get
  "GET /api/threads/<stem>/delegations -- the delegation rows a PARENT session's
  record holds: one {:toolCallId .. :subagent .. :threadId .. :at ..} per
  `delegation` line, in file order. This is the read side of the line
  `run-subagent!` writes when it opens a child (see the header's kind list),
  and what ticket 04's card pairs its click against: the parent's toolCallId
  names the card, :threadId names the conversation the panel opens.

  READ-ONLY, like stats and trajectory: it reads the log and writes nothing,
  and it takes the stats-style record reader rather than replay's strict one,
  because a parent whose child is RUNNING RIGHT NOW is the case the card
  exists for -- its last line may be half-written, and a half line is 'we read
  this far', not damage.

  LOCATION AND REFUSALS ARE STATS' -- `replay/locate`, 404 with the locator's
  own sentence for a stem that is nowhere under the tree. A parent that simply
  never delegated answers an empty list, which is an ordinary answer and not
  an error: the rows are how the panel learns there is nothing to open.

  THE ROW IS ASKED FOR THE WAY EVERY READER ASKS (`replay/kind`/`replay/payload`),
  which is what keeps this route honest across the two-row record: a `delegation` line
  is an `event` row whose payload is the CUSTOM frame NAMED `delegation`, so a reader
  that compared `:type` to `delegation` would find nothing at all -- the name is
  derived in one place, and this is a caller of that place."
  [stem]
  (let [located (try {:ok (replay/locate (home/projects-dir) stem)}
                     (catch Throwable t {:error (ex-message t)}))
        folded  (when (nil? (:error located))
                  (try
                    {:ok (->> (stats/read-records (:ok located))
                              (filter #(= "delegation" (replay/kind %)))
                              (mapv (fn [row] (assoc (replay/payload row) :at (:ts row)))))}
                    (catch Throwable t {:error (ex-message t)})))]
    (cond
      (some? (:error located))
      (api-response 404 {:error (:error located) :threadId stem})

      (some? (:error folded))
      (api-response 400 {:error (:error folded) :threadId stem})

      :else
      (api-response 200 {:threadId stem :delegations (:ok folded)}))))

(defn- todos-get
  "GET /api/threads/<stem>/todos -- the task list a model last wrote for one session, as
  the store's row holds it: one map of `content` and `status` per item, in the order it
  was written, straight from harness.cap.todos/items-for.

  IT READS THE STORE'S ROW, NOT THE LOG, and that is the whole of its point: the
  `todo_write` call that produced the list is folded away by a compaction, while the row
  outlives the run that wrote it -- so 'what is left to do' is a question a screen can
  ask of a conversation it scrolled away from.

  NO LOCATE AND NO 404: the list is a row keyed by the thread id, not a line under the
  home's log tree. A stem this home has never heard of answers [] exactly as a session
  that never wrote one does -- `items-for` already collapses those two facts, and this
  route does not take them apart again.

  READ-ONLY, so no audit line: asking again is the ordinary use (the composer's strip
  re-asks on every model call), and a route that wrote a line per ask would fill the
  logs with 'somebody looked'."
  [stem]
  (api-response 200 {:threadId stem :todos (todos/items-for stem)}))

(defn- jobs-get
  "GET /api/threads/<stem>/jobs -- the background commands THIS PROCESS is running for
  one session, as rows a task pane can draw: id, the command, how it is going, when it
  started, and where its record is.

  IT READS THE PROCESS'S REGISTRY, NOT A LOG, and that is the whole of its difference
  from its siblings above: `stats`, `trajectory` and `delegations` fold a file and 404
  when the stem is not under the tree, while a job IS the process's own memory of a
  command (`harness.cap.jobs`). So there is nothing to locate and nothing to refuse:
  'this process has no jobs for this session' is `:jobs []`, an ordinary answer to a
  question about WHAT IS THERE. A stem nobody has run anything for and a stem whose
  jobs went with an earlier process both answer `[]` -- the records outlive the
  process, but the JOBS do not, and this route is about the jobs.

  READ-ONLY, so no audit line: every GET on this edge only answers. Asking again a
  second later is the ordinary use (`[]` polls the pane does), and a route that wrote
  a line per poll would fill the logs with 'somebody looked'.

  THE METHOD SAYS WHETHER THERE IS AN EFFECT, so this verb has two (ticket 04 of
  `.scratch/right-pane-tasks`): a GET answers, and the POST below stops one of the jobs
  this one listed. A pair this shape does not serve is still a 405, from the dispatch's
  `case`."
  [stem]
  (api-response 200 {:threadId stem :jobs (jobs/listing stem)}))

(defn- jobs-post
  "POST /api/threads/<stem>/jobs {job: \"j1\"} -- STOP one of this session's background
  jobs, as a PERSON. Answers {:id .. :stopped? .. :ending ..}: which job, whether THIS
  call is what stopped it, and its record's last line -- the same three facts
  `job_kill`'s answer carries, from the same place (`cap.jobs/stop!`).

  IT IS THE SECOND INITIATOR, NOT A SECOND STOP. `stop!` takes WHO stopped it, and the
  whole of what that changes is the telling: the model's `job_kill` passes nothing (the
  default) and its answer claims `:told?`, while this route passes `{:by :user}`, which
  claims nothing and marks the entry instead -- `take-notices!` then puts a `by=\"user\"`
  block in front of the model at the next call (`cap.jobs/stop!` argues the split).

  NO APPROVAL, AND FOR THE REASON `cancel-post` GIVES: a person already pressed the
  button, and asking 'are you sure you want to stop it' about a stop somebody just asked
  for turns a settled thing into a question. The pane's own second thought, if any, is
  the pane's business; the server is not the place to second-guess a press.

  AN ID THIS SESSION DOES NOT HAVE IS THE REFUSAL `cap.jobs` ALREADY HAS -- `unknown-job`,
  which names the ids that DO exist -- raised here and answered 404 rather than written a
  second time in this namespace. The tool face turns the same exception into a tool
  result, so the two readers of one refusal say the same words.

  AN ALREADY-ENDED JOB IS NOT AN ERROR: `stopped? false` with its own `[exit N]` is the
  honest answer to a press that raced the command's own end -- and nothing at all was
  changed in the registry (see `cap.jobs/stop!`).

  A BODY THAT IS NOT JSON, OR THAT NAMES NO JOB, IS A 400: the difference between 'I
  cannot understand you' and 'that job is not here' is the one `archive-post` draws too."
  [req stem]
  (let [parsed (try {:ok (json/read-str (slurp (:body req) :encoding "UTF-8")
                                        :key-fn keyword)}
                    (catch Throwable _ {:bad true}))
        job-id (:job (:ok parsed))]
    (cond
      (:bad parsed)
      (api-response 400 {:error "request body is not valid JSON"})

      (not (string? job-id))
      (api-response 400 {:error "job must be the id of a background job"})

      :else
      (let [stopped (try {:ok (jobs/stop! stem job-id {:by :user})}
                         (catch Throwable t {:error (ex-message t) :data (ex-data t)}))]
        (if-some [error (:error stopped)]
          (if (= :unknown-job (:reason (:data stopped)))
            (api-response 404 {:error error :job job-id})
            (api-response 400 {:error error :job job-id}))
          (api-response 200 (select-keys (:ok stopped) [:id :stopped? :ending])))))))

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
        (doseq [{:keys [run-id last-frame frames messages]} closures]
          (log! stem nil "session/closed-off" {:run-id     run-id
                                               :last-frame last-frame
                                               :frames     (mapv :type frames)})
          (doseq [frame frames]
            ;; RUN-ID IS THE CLOSED RUN'S: the frames belong to it, and that is how a
            ;; reader pairs a terminal frame with the run it ended.
            (log! stem run-id "event" frame))
          ;; AND THE ROWS THE CUT-OFF ANSWERS ARE (`closing-frames`' own `:messages`): the frames say
          ;; what happened and the rows are what every reader folds into the conversation, and a
          ;; record missing one is not normalized -- so the repair that exists to make a killed
          ;; session continuable would leave it read-only instead (`.scratch/record-normalization`).
          (log-messages! stem run-id messages))
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
  learn nothing; `harness.infra.stream` is where the failure lives."
  [thread-id]
  (let [h (stream/health thread-id)
        d (stream/degraded thread-id)]
    ;; ABSENCE IS THE CLIENT'S 'fine', so a healthy record carries no field at all -- and the WORD
    ;; stays the one the page already draws ("degraded") because a finer fact is not a new
    ;; contract: `:level` and `:says` are what ticket 04 added (L1 behind / L2 stuck / L3 lost), and
    ;; a surface that has learned to read them says the writer's own sentence instead of guessing
    ;; one.
    (when (pos? (long (:level h)))
      {:state   "degraded"
       :level   (:level h)
       :says    (:says h)
       :reason  (:reason d)
       :pending (:pending h)
       :at      (:at d)})))

(defn- ambiguous-stem
  "The locator's refusal when a stem names MORE THAN ONE log, or nil when it does not.

  A SESSION'S RECORD CAN BE SPLIT IN TWO -- a rebind that moved the conversation and a
  file left behind, a restored backup, a hand-edited tree -- and every reader here has to
  refuse rather than pick a side: memory holds one conversation and the listing shows
  two rows, so a rebuild that answered from memory would silently hand back the half
  that happens to be loaded. The other locator failures are NOT this: 'nothing found' is
  an ordinary state for a session that has never run (`find-log` answers nil for that,
  and this answers nil with it)."
  [stem]
  (try
    (replay/locate (home/projects-dir) stem)
    nil
    (catch Throwable t
      (when (seq (:paths (ex-data t))) (ex-message t)))))

;; `live-state` is defined below with the window (its other caller), and a `defn-` has to
;; be known before it is read: a plain `declare` rather than moving it up, because the
;; window section is where its argument lives and nothing here reads it before this point.
(declare live-state)
(defn- fork-session!
  "Make a NEW session out of SOURCE-THREAD's record AT A CUT, and answer where it landed.

  THE CUT IS THE RECORD'S (`harness.edge.replay/fork-cut`), not this function's: the lines
  before it are what the new file holds, byte for byte, so the fork is the conversation as it
  stood there rather than a re-rendering of it. THE ONE EXCEPTION IS A RECORD IN THE OLD CONTRACT
  (owner, 2026-09-28): its rows are TRANSLATED on the way in (the reader did it, `replay/legacy-
  rows`) and written out in the current format, because a fork is the only door that may rewrite
  a record -- so it is also the door that turns an old one into one this build can continue.
  CUT is {:step-seq n} -- a `step/end` line from
  `GET /api/threads/<stem>/fork-points`, which the fork KEEPS -- or nil for the last step there
  is. A line that is not a `step/end` is refused rather than rounded to the nearest one.

  THE NEW FILE MUST READ, WHICH IS THE ONE THING A CUT CAN BREAK. A cut usually sits INSIDE a
  run (`relieve-pressure!` runs before a model call, and a step's end is not a run's end): the
  lines before it end with a run that never reached a terminal frame. Those runs are closed
  here (`replay/closing-frames`) exactly as a rebuild closes them, each under its own run id,
  so the new record is a log every reader accepts.

  A NAME THE RECORD DOES NOT HAVE IS A REFUSAL, not a rounding: a line that is not a
  `step/end`, and a record with no step behind it at all, are each named back to the caller."
  [thread-id cut]
  (let [source (replay/find-log (home/projects-dir) thread-id)]
    (when-not source
      (throw (ex-info (str "no record for " thread-id " in this home, so there is nothing to"
                           " fork: a fork copies a conversation's log")
                      {:reason :no-record :thread-id thread-id})))
    (let [records (vec (replay/read-records source))
          where   (replay/fork-cut records cut)]
      (when-not where
        (throw (ex-info (if (some? (:step-seq cut))
                           (str "line " (:step-seq cut) " of " thread-id " is not a `step/end`,"
                                " so there is no step to fork after")
                           (str "no `step/end` in " thread-id
                                ", so there is no line to fork after"))
                        {:reason :no-fork-point :thread-id thread-id :cut cut})))
      (let [
            ;; THE LINES THE FORK COPIES ARE THE FILE'S OWN, BYTE FOR BYTE -- with ONE exception:
            ;; a record written in the OLD CONTRACT (`.scratch/record-normalization`, owner
            ;; 2026-09-28). The reader TRANSLATED those lines already (`replay/legacy-rows`, and each
            ;; translated row carries `:old-contract`), so what is copied is the translation: the new
            ;; file is in the current format from its first line, which is what makes the door out of an
            ;; old record a fork at all. The two cases cannot share one `subvec` either: for such a
            ;; record a row and a line are not one-to-one (an old `input` line becomes several rows),
            ;; so `:cut` -- a RECORD index -- is what both branches slice by.
            ;; WHETHER THE FILE OPENS WITH A HEADER -- asked ONCE, and about the RECORD (rows, not
            ;; lines: a header is one of each, so one answer serves both slices).
            ;;
            ;; `header?` ANSWERS A BOOLEAN, and `some?` AROUND IT IS TRUE FOR EVERY ROW THERE IS: that
            ;; wrapper is what used to drop the first line of a record whose first row is an
            ;; ordinary one -- every record written before the header existed, which is exactly the
            ;; old-contract records this branch was added for.
            skip   (if (replay/header? (first records)) 1 0)
            kept   (subvec records skip (:cut where))
            legacy? (boolean (some :old-contract kept))
            lines  (when-not legacy?
                     (subvec (vec (replay/read-lines source))
                             skip
                             (:cut where)))
            folded (subvec records 0 (:cut where))
            new-id (str (java.util.UUID/randomUUID))
            dir    (project/binding-for thread-id)
            dest   (log-file-for new-id)]
        (if (some? dir)
          (project/bind! new-id dir)
          (project/register-session! new-id))
        ;; THE HEADER IS THE FILE'S: `header-line!` writes it for THIS path and remembers
        ;; that it did, so the new conversation opens with its own first line while every
        ;; line after it is the parent's.
        (when-some [h (header-line! dest)] (stream/push! new-id dest h))
        (if legacy?
          ;; THE TRANSLATION, WRITTEN OUT: `json/write-str` of the row the reader made of each old
          ;; line (the marker itself is this process's bookkeeping and is not written -- the new file
          ;; says what it IS, in the format that is current now).
          (doseq [row kept]
            (stream/push! new-id dest (str (json/write-str (dissoc row :old-contract)) "\n")))
          (doseq [line lines]
            (stream/push! new-id dest (str line "\n"))))
        (log! new-id nil "session/forked"
             {:from thread-id
              :seq  (:at where)})
        (when-let [closures (seq (replay/closing-frames folded))]
          (doseq [{:keys [run-id last-frame frames]} closures]
            (log! new-id nil "session/closed-off" {:run-id run-id :last-frame last-frame
                  :frames (mapv :type frames) :via "fork"}))
          (doseq [{:keys [run-id frames messages]} closures
                  frame frames]
            (log! new-id run-id "event" frame))
          ;; AND THE ROWS THOSE ANSWERS ARE (`closing-frames`' own `:messages`): a fork's product
          ;; must BE 已重整化 -- a cut-off call answered by a frame alone would leave it exactly as
          ;; un-normalized as the record it was forked from.
          (doseq [{:keys [run-id messages]} closures]
            (log-messages! new-id run-id messages)))
        ;; AND THE ROWS A CALL'S ANSWER NEVER LANDED AS (ticket 03 of `.scratch/record-normalization`):
        ;; a run that answered a call with a `TOOL_CALL_RESULT` frame and no `message` row is a record
        ;; nobody may write to (判据 (3) of ticket 01, and the bug 票 05 of `.scratch/record-stream`
        ;; fixed at the writer), so the fork writes that row -- the frame's own content, through the
        ;; same `frames/tool-message` the run loop writes its results with.
        ;;
        ;; NOTHING IS ADDED WHEN THERE IS NOTHING MISSING, which is what makes the fork IDEMPOTENT:
        ;; forking a record that already has every row copies it and stops.
        ;; GROUPED BY RUN because a row is written under the run it belongs to: one `log-messages!`
        ;; per run, in the record's order.
        (doseq [[run-id entries] (group-by :run-id (replay/missing-tool-rows folded))]
          (log-messages! new-id run-id (mapv :message entries)))
        (project/set-title! new-id
                            (str/trim (str "[fork] " (or (project/title thread-id) ""))))
        (host/ring!)
        {:threadId new-id
         :from     thread-id
         :seq      (:at where)}))))

(defn- fork-post
  "POST /api/threads/<stem>/fork {stepSeq?} -- make a new session from STEM's record AT A CUT,
  and answer where it landed. The parent is untouched: its file, row and run state stay
  exactly as they were.

  THE CUT IS A LINE THE CALLER NAMES -- a `step/end` line number from
  `GET /api/threads/<stem>/fork-points`, which the fork keeps. With NO line named, the LAST
  step is the cut, so every session with a step behind it can be forked.

  A SESSION WITH A RUN IN FLIGHT IS REFUSED (409). The cut has to land on a boundary, and a
  run still writing has not reached one yet -- the same reason the message menu's Fork is
  offered only after a turn ends.

  A SESSION THIS HOME HAS NEVER HEARD OF is refused the way every run on it is
  (`refuse-unknown-session!`); a line that is not a fork point is a 400 that names it."
  [req stem]
  (cond
    (not (project/session-exists? stem))
    (refuse-unknown-session! stem)

    (running? stem)
    (api-response 409 {:error (str "session " (pr-str stem) " has a run in flight here, so"
                                   " it cannot be forked right now: a fork cuts the record"
                                   " at a boundary, and a run still writing has none yet.")
                       :threadId stem
                       :reason   "running"})

    :else
    (let [body     (try (json/read-str (slurp (:body req) :encoding "UTF-8") :key-fn keyword)
                         (catch Throwable _ nil))
          step-seq (when (map? body) (:stepSeq body))
          cut      (when (integer? step-seq) {:step-seq step-seq})
          bad?     (and (some? step-seq) (not (integer? step-seq)))]
      (if bad?
        (api-response 400 {:error (str "stepSeq must be a whole line number from"
                                       " GET /api/threads/<stem>/fork-points (a `step/end`"
                                       " line); this body's stepSeq is not one")
                           :threadId stem
                           :reason   "bad-cut"})
        (try
          (rung (api-response 200 (fork-session! stem cut)))
          (catch clojure.lang.ExceptionInfo e
            (let [d (ex-data e)]
              (api-response (if (= :no-record (:reason d)) 404 400)
                            {:error    (ex-message e)
                             :threadId stem
                             :reason   (name (or (:reason d) :fork-refused))}))))))))

(defn- fork-points-get
  "GET /api/threads/<stem>/fork-points -- the LINES this session may be forked at, oldest
  first: every `step/end` (a fork keeps that line) and every compaction that produced a
  `context/compacted` (a fork cuts just before it).

  IT EXISTS BECAUSE THE CUT IS A LINE (owner, 2026-09-28). The message menu's Fork takes the
  default; a caller that wants to go back to a PARTICULAR moment has to see which moments
  there are, and `:seq` here is exactly what `/fork` takes back as `stepSeq`.

  THE TAIL IS CAPPED (200 points) so a long conversation does not answer with a novel; the
  numbers stay absolute (`:seq` is the record line), so a truncated list is still usable."
  [stem]
  (try
    (if-some [f (replay/find-log (home/projects-dir) stem)]
      (let [points (vec (replay/fork-points (vec (replay/read-records f))))]
        (api-response 200 {:threadId stem
                           :total    (count points)
                           :points   (vec (take-last 200 points))}))
      (api-response 404 {:error (str "no record for " (pr-str stem) " in this home, so there"
                                     " are no fork points to list")
                         :threadId stem}))
    (catch Throwable t
      (api-response 400 {:error (ex-message t) :threadId stem}))))

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
  than one file, is refused without landing its audit line anywhere.

  A LIVE SESSION IS ANSWERED FROM MEMORY (ticket 05, judgement 6), and the difference is
  visible in both what it answers and what it does NOT do: there is no repair, because a
  conversation this process is holding does not need one (`close-off-open-run!` is for a
  log whose run is over -- a live session's run is either running or already settled in
  memory), and the messages come back with the cards the page draws. A conversation
  nobody here holds is read from the record exactly as it was, repair and all: the
  process that holds it, if any, is the one that may close its record off.

  A LIVE SESSION'S ANSWER CARRIES ITS STATE (`:state`), because the door a client came
  through decides whether it FOLLOWS the run: this door hands over a SNAPSHOT with no
  feed, so a page that opened a RUNNING session from the sidebar had no server word to
  close the composer's gate with, and the only reply to its Send was the run edge's 409
  (`refuse-second-run!`). A client that sees `running` here looks through the window
  instead -- see `harness.edge.http/window-frame` for the state a window carries."
  [req stem]
  (if-some [split (when (some? (sessions/live-entry stem)) (ambiguous-stem stem))]
    (api-response 404 {:error split :threadId stem})
   (if-some [_live (sessions/live-entry stem)]
    (let [messages (sessions/drawn stem)
          health   (record-health stem)]
      (log! stem nil "session/rebuilt" {:messages (count messages) :via "http"
                                        :source "memory"})
      ;; NO RING NEEDED HERE: a rebuild changes no fact the listing carries (its row
      ;; exists already or the id is unknown; a run's start/end rings from the
      ;; run-state writes themselves). Same for the record-backed rebuild below --
      ;; a log read back is not a listing fact.
      (api-response 200 (cond-> {:threadId stem
                                 :messages messages
                                 :context  (or (sessions/context stem) [])
                                 :state    (live-state stem)}
                          (some? health) (assoc :record health))))
   (let [located (try {:ok (replay/locate (home/projects-dir) stem)}
                     (catch Throwable t {:error (ex-message t)}))
        _       (when (nil? (:error located))
                  (close-off-open-run! stem (:ok located))
                  ;; READ YOUR OWN WRITE. The closing frames are the thing that
                  ;; makes the file whole, and the rebuild below reads the file --
                  ;; through the queue they would not be there yet, and this route
                  ;; would refuse a log it had just repaired. Draining is what
                  ;; makes the repair and the read one act (ticket 02).
                  (stream/flush! 5000))
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
        (log! stem nil "session/rebuilt" {:messages (count messages) :via "http"
                                          :source "record"})
        (api-response 200 (cond-> {:threadId stem
                                   :messages messages
                                   :context  (or context [])}
                            (some? health) (assoc :record health)))))))))

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

  AND ONE FIELD ABOUT THE BYTES: `:normalized` -- whether this record may be WRITTEN to any
  further (`harness.edge.normalized`, ticket 01 of `.scratch/record-normalization`). The client
  disables its composer on `false`, shows `:normalizationReasons` and offers the fork. It rides
  disables its composer on `false`, shows `:normalizationReasons` and offers the fork.
  
  TWO SOURCES, ONE QUESTION, and neither re-reads the bytes on a poll: the RECORD path folds them
  on the walk that already folded the conversation (so the verdict and the messages cannot come from
  two states of the file), and the LIVE branch reads the session's OWN FOLD -- the criterion is
  registered on both of a session's seams (`start!`), seed from the record at birth and advanced by
  every row this process writes. A live session answered `true` unconditionally before that, which
  was a claim about bytes nothing had judged: opening a session in the running process (a feed rings
  the claim) was enough to hide a record that no reader may write to.

  THE TWO HALVES OF THE LIVENESS QUESTION ARE ANSWERED BY THEIR OWNERS: the file
  says whether a run has a terminal frame (`replay/sofar`), and the process says
  whether that run is still being answered (`running?`, the session table's run set).
  Neither is inferred from the other -- which is the whole point of having both.

  AND A LIVE SESSION IS ANSWERED FROM MEMORY, for the same reason `rebuild` is: what
  this process is holding is the conversation, and the record may be behind it. The two
  halves of the liveness question stay two: the STATE is the session's own
  (`harness.edge.sessions/state`, written by `settle!` from the frame that ended the
  run), and whether a run is going is `running?` -- neither inferred from the other."
  [req stem]
  ;; `when-not` RATHER THAN `and`: a running session must fall through to the record
  ;; path below, and `(and false ...)` answers FALSE -- which `if-some` reads as a
  ;; value, not as an absence. The record path is the right one while a run is going:
  ;; what that run has produced is being WRITTEN, and `settle!` is the moment it
  ;; becomes this table's (see `messages`); reading memory mid-run would show a client
  ;; a conversation missing the answer it is watching arrive.
  (if-some [live (when-not (running? stem) (sessions/live-entry stem))]
    (let [messages (sessions/drawn stem)
          context  (or (sessions/context stem) [])
          health   (record-health stem)
          st       (sessions/state stem)
          ;; AND WHETHER THESE BYTES MAY BE WRITTEN TO, from the session's OWN FOLD -- the criterion
          ;; registered on both of its seams (see `start!`), so a session born from a record starts
          ;; with that record's verdict and every row this process writes advances it. No re-read of
          ;; the bytes on a poll, and no claim about a record this process never judged.
          verdict  (normalized/finish (sessions/fold-value stem :normalized))]
      (cond
        ;; THE SAME REFUSAL THE RECORD PATH MAKES, and for the same reason: the log ends
        ;; mid-run, nobody here is running it, and closing it off is a decision made by
        ;; whoever asks to CONTINUE the conversation.
        (= :unfinished st)
        (api-response 400 {:error (str "this conversation's log ends mid-run and this"
                                       " process is not running it: the run was cut off."
                                       " POST /api/threads/" stem "/rebuild to close it off"
                                       " and read it back, or start a new session.")})

        :else
        (api-response 200 (cond-> {:threadId stem :messages messages :context context
                                   :state    (name st)
                                   ;; THE RECORD'S OWN VERDICT, about the SESSION (the fold above):
                                   ;; the composer's gate and the notice that offers the fork read it.
                                   :normalized (boolean (:normalized? verdict))
                                   :normalizationReasons (:reasons verdict)}
                            (seq (:interrupts live)) (assoc :interrupts (:interrupts live))
                            (some? health)           (assoc :record health)))))
   (let [located (try {:ok (replay/locate (home/projects-dir) stem)}
                     (catch Throwable t {:error (ex-message t)}))
        read    (when (nil? (:error located))
                  (try {:ok (replay/fold-sofar (:ok located) {:normalized normalized/fold})}
                       (catch Throwable t {:error (ex-message t)})))]
    (cond
      (some? (:error located))
      (api-response 404 {:error (:error located)})

      (some? (:error read))
      (api-response 400 {:error (:error read)})

      :else
      (let [{:keys [messages context state open-runs interrupts]} (:ok read)
            health (record-health stem)
            ;; THIS ANSWER IS ABOUT THE BYTES, and it rides the SAME walk that folded the
            ;; conversation (`replay/fold-sofar` fed `normalized/fold`): the verdict and the
            ;; messages cannot be read from two different states of the file.
            verdict (normalized/finish (get-in read [:ok :folds :normalized]))]
        (cond
          (= :unfinished state)
          (if (running? stem)
            ;; NO VERDICT ON THIS ONE: a record a run IN THIS PROCESS is still writing is
            ;; incomplete by nature -- a call whose answer has not been written yet is not damage
            ;; -- and continuing it is refused by the one-run-at-a-time rule regardless. Whether
            ;; these bytes may be written to is judged when the run settles, above the record
            ;; read below.
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
                                     :state    (name state)
                                     :normalized (boolean (:normalized? verdict))
                                     ;; WHY NOT: the sentences above, for a client that shows the
                                     ;; reason next to the fork button. Empty when the record is fine.
                                     :normalizationReasons (:reasons verdict)}
                              (seq interrupts) (assoc :interrupts interrupts)
                              (some? health)   (assoc :record health)))))))))

;; -------------------------------------------------------------------- the window
;;
;; THE WINDOW IS HOW A CLIENT HOLDS A CONVERSATION TOO LONG TO SEND (ADR 0003). It
;; gets a TAIL page when it opens the session, an APPEND stream while it is connected,
;; and a PREPEND page when somebody scrolls up -- and every entry in all three carries
;; the record offset it arrived at, so the client can tell what it has without asking
;; the server to remember (`harness.edge.sessions`' arrivals, and ticket 05).
;;
;; THE TWO ROUTES ARE ONE WINDOW READ TWICE:
;;
;;   GET /api/threads/<stem>/page[?beforeSeq=N]   the TAIL page, or the page in front
;;                                                of N -- plain JSON, ONE answer
;;   GET /api/threads/<stem>/feed[?since=N&generation=G]
;;                                                the same window as a STREAM: the
;;                                                tail (or the delta after N), then
;;                                                every later entry as it lands
;;
;; AND THE PAGE ROUTE READS THE RECORD WHEN THIS PROCESS DOES NOT HOLD THE SESSION.
;; That is the difference between the two, and it is deliberate: scrolling up is a READ,
;; and a read does not need to be the process that serves the conversation (ADR 0002
;; decision 7 is about who may WRITE). The feed cannot be that generous -- pushing
;; changes means holding them -- so connecting is an act on the session and it rings the
;; claim the same way an action does.


(defn- live-state
  "The state of a conversation THIS PROCESS HOLDS, in the same words `sofar` answers
  with (`harness.edge.http/sofar-get`): `running` when a run of it is alive here, else
  what the session's own memory last said (`harness.edge.sessions/state`, written by
  `settle!` from the frame that ended the run).

  WHY A WINDOW CARRIES IT AT ALL (ticket 06 of `.scratch/sessions-live-on-the-server`):
  a replica that opened a window and then watched somebody ELSE's run needs to know when
  that run settles -- the difference is whether the turn on screen is still being written
  -- and the window is the only connection it has. Polling `sofar` for the same fact is
  what the window exists to replace. `nil` for a session that has never run, which reads
  as 'not running' on the other side."
  [stem]
  (if (running? stem) "running" (some-> (sessions/state stem) name)))

(defn- window-frame
  "A page as a feed frame: the entries, where the page starts in the record, whether
  there is more in front of it, where the reader's cursor now is, WHICH WINDOW this is --
  the session's generation (`harness.edge.sessions/generation`) -- and how far along the
  conversation is (`state`, see `live-state`).

  THE CURSOR IS THE LAST ENTRY'S `:seq`, and nil when the page's entries have not landed
  yet: a reader that kept a number the writer has not confirmed would be inventing one,
  and the next read would then ask for a delta from a number nothing is numbered at.
  `baseSeq` is the same kind of number for the front of the page.

  THE FIVE TYPES ARE NAMED HERE, ONCE, and they say what the reader is holding rather
  than which route produced it -- the page route and the feed answer the same window:

    `window`  the feed's opening frame: the tail page, for a client with nothing
    `append`  entries after the reader's cursor, on the feed or as its opening frame
              when it connected with `since`
    `page`    the page in front of the reader's oldest entry (`?beforeSeq`)
    `tail`    the newest page, for a reader with no cursor at all (`GET .../page`)
    `end`     the window is over: the session was put away, swept or taken over

  Three of them come off the feed and two off the page route, and a client switches on
  the type the same way either way.

  THE RECORD'S HEALTH RIDES ALONG, absent when there is nothing to say -- ADR 0002
  decision 6 asks that a write failure reach whoever is looking, and a window is now one
  of the reads somebody looks through (`harness.edge.http/record-health`, the same fact
  `rebuild` and `sofar` carry)."
  [thread-id type state {:keys [entries baseSeq hasMore]}]
  (let [entries (vec entries)
        health  (record-health thread-id)]
    (cond-> {:type       type
             :entries    entries
             :baseSeq    baseSeq
             :hasMore    (boolean hasMore)
             :cursor     (or (:seq (peek entries)) baseSeq)
             :generation (sessions/generation thread-id)
             :state      state}
      (some? health) (assoc :record health))))

(defn- record-entries
  "The entries the RECORD holds for STEM, read right now: `{:ok [..] :state \"..\"}`, or
  `{:error <sentence> :status 404|400}` -- an unknown stem and a log that cannot be read,
  told apart the way `rebuild` tells them apart.

  TWO READERS, AND WHICH ONE IS A DECISION ABOUT WHO IS WRITING:

    LENIENT when this process is writing it (`lenient?`, i.e. a run of this session is in
    flight here): the newest line may be half-flushed, and dropping it is the honest answer
    to 'what has arrived' (`replay/read-records`). Every other line is still strict.

    STRICT when nobody here is writing it: a torn line is then a log that was CUT OFF, and
    refusing it by name is what sends the client to the `rebuild` door that closes it off
    (`replay/lines->records`, and `:state` from `record-state` so a page can say which)."
  [stem lenient?]
  (let [located (try {:ok (replay/locate (home/projects-dir) stem)}
                     (catch Throwable t {:error (ex-message t)}))]
    (if-some [err (:error located)]
      {:error err :status 404}
      (try (let [records (if lenient?
                           (replay/read-records (:ok located))
                           (vec (replay/lines->records (replay/read-lines (:ok located)))))]
             {:ok    (vec (replay/entries records))
              :state (name (:state (replay/record-state records)))})
           (catch Throwable t {:error (ex-message t) :status 400})))))

(defn- read-entries-raw
  "The entries a WINDOW route answers with: the live session's when this process holds
  it -- EXCEPT while a run of it is in flight, when the record is further along -- else
  the record's. Read-only, and never a birth.

  WHILE A RUN IS GOING, THE RECORD IS THE TRUTH AND MEMORY IS BEHIND. `settle!` folds a
  run's frames at its END, deliberately ('half an answer is not a turn'), so a page that
  arrives mid-run and read the table would show the question and none of the work -- the
  bookkeeping of `.scratch/session-opening` ticket 04, and the reason a refresh during a
  long turn looked like an empty turn. The frames are on the record the moment they are
  written (`log!` is a per-frame append), so the record answers 'what has arrived' exactly,
  and the run's own group is already in `replay/entries` (it flushes the unfinished group).

  `:live` STAYS TRUE while it reads the record: this process IS serving the session, and the
  client uses that flag for 'somebody here can be asked', not for 'this answer came out of
  memory'. The STATE comes from the same owner as `sofar`'s (`live-state`), so the two doors
  name the conversation's state with one voice.

  {:ok [..]} or {:error <sentence>}: an unknown stem and a log that cannot be read are
  the two failures, and they are told apart the way `rebuild` tells them apart (404 for
  'not here', 400 for 'here and broken').

  IT ANSWERS THE STATE AS WELL, because a page that draws a conversation has to know
  whether its last turn is still being written: memory for a session this process holds
  (`live-state`), and the RECORD's own reading for one it does not (`replay/record-state`
  -- `:unfinished` is the honest word for a log that ends mid-run, and it is also the flag
  that sends a client to the `rebuild` door that closes it off)."
  [stem]
  (if-some [e (sessions/live-entry stem)]
    (if-some [split (ambiguous-stem stem)]
      {:error split :status 404}
      (do (sessions/touch! stem)
          (if (running? stem)
            (let [r (record-entries stem true)]
              (cond
                ;; NOTHING ON DISK YET (a session whose first line has not been written, or a
                ;; table fed by a caller rather than by a run): memory is all there is, and it
                ;; is the fuller one. A session that HAS a record and is running always reads
                ;; the record -- that is the point of this branch.
                (= 404 (:status r)) {:ok (:entries e) :live true :state (live-state stem)}
                (some? (:error r))   {:error (:error r) :status (or (:status r) 400)}
                :else                {:ok (:ok r) :live true :state (live-state stem)}))
            {:ok (:entries e) :live true :state (live-state stem)})))
    (record-entries stem false)))

(defn- reconcile-numbers!
  "GIVE A CONVERSATION THIS PROCESS ALREADY HOLDS THE NUMBERS ITS RECORD SAYS -- the repair for a
  session whose runs ended in a process that PREDATES the numbering (`.scratch/window-self-heal/`):
  its entries carry `:seq nil`, and nothing in memory can say what they should be, because the rows
  that decided them died with the run that wrote them. The record is the only place they still exist,
  so it is read ONCE -- a STREAMING fold (`replay/fold-entries`, the same reader that gives a window
  its numbers) -- and the names it answers are handed to the session (`number-entries!`).

  WHEN IT DOES NOT RUN, and each of these is a reason on its own:
    * nobody here holds the conversation, or it has no log -- nothing to number, nothing to read;
    * a run of it is IN FLIGHT: its newest entries have no number YET, on purpose, and the run that
      is writing them is the door they will come through (`harness.edge.http/runner`);
    * `:numbered-from-record` on the session's fold table says it has been looked at. A session
      BORN from the record is numbered by that walk and never needs this; one that has been looked at
      once must not be looked at again on every read (the flag, not the scan, is what keeps a
      well-formed conversation from paying for this at all).

  A READ STILL WRITES NOTHING: no record byte, no claim, no birth -- only numbers in the table. The
  same shape `revive-parks!` has beside it, and for the same reason: a reader handed a conversation
  it can neither cut a page from nor ask a delta of is a reader misled."
  [stem]
  (let [session (sessions/live-entry stem)]
    (when (and (some? session)
               (not (sessions/fold-value stem :numbered-from-record))
               (not (sessions/running? stem))
               (some #(nil? (:seq %)) (:entries session)))
      (when-some [f (replay/find-log (home/projects-dir) stem)]
        (sessions/number-entries!
         stem nil
         (into {} (keep (fn [e] (when-some [id (get-in e [:message :id])] [id (:seq e)])))
               (replay/fold-entries f))))
      ;; AND THE SESSION IS MARKED LOOKED AT, log or no log: a conversation with no record has
      ;; nothing to be numbered BY, and asking that again on every read would be a file lookup (and
      ;; a scan) per read for an answer that cannot change.
      (sessions/set-fold-value! stem :numbered-from-record true))))

(defn- read-entries
  "`read-entries-raw` (above, which says everything about WHERE an answer comes from), with
  one step in front of the answer: the parks this conversation is waiting on are rebuilt
  (`harness.edge.sessions/revive-parks!`).

  THE REPAIR IS PART OF HANDING A CONVERSATION OUT, not a second reading of it. What a
  reader is about to draw are the cards the conversation names -- and after a restart those
  names are the only thing left of the parks behind them: the card's question would 404 and
  its answers would go nowhere, which is the state a person sees as 'the page is stuck' (the
  2026-09-29 session 9fbc5c8c). `revive-parks!` rebuilds them out of the conversation's own
  words, under the same ids -- so the card goes on being answerable, and a READ still writes
  nothing but that: no record byte, no claim, no birth, and never twice for one park."
  [stem]
  (reconcile-numbers! stem)
  (let [read (read-entries-raw stem)]
    (if-some [es (:ok read)]
      (assoc read :ok (sessions/revive-parks! stem es))
      read)))

;; ----------------------------------------------------- the record, read one pass at a time

(defonce ^:private record-folds
  ;; thread-id -> {:path .. :bytes .. :lines .. :rows .. :fold ..}: HOW FAR THIS PROCESS HAS ALREADY
  ;; FOLDED a conversation's record. See `record-entries-resumed` for what keeps it and what drops it.
  (atom {}))

(defn- record-entries-resumed
  "STEM's entries AS THE RECORD HAS THEM, folded over what has been APPENDED since the last pass --
  the same answer `record-entries` gives, without reading again the bytes it has already read.

  WHY IT EXISTS: a run in flight writes its answer a line at a time, and the window's doorbell asks
  again on every tick of the sweeper's clock (`growth-interval-ms`, ten times a second) so that a
  reader watches the answer grow. Re-reading the record each time cost A PARSE OF THE WHOLE
  CONVERSATION PER TICK -- measured on this machine, 2026-09-30: a 23.7 MB record of 53,732 rows
  took 254-321 ms a pass against a 100 ms interval, so the thread never got to sleep, and a
  conversation that only grows makes it worse. The bytes that changed are the whole answer; the
  fold is resumed over those, and `replay/entries-fold` says why the two readings agree entry for
  entry.

  A COLD CURSOR READS THE WHOLE RECORD ONCE: no cursor for this thread-id, a record at another path,
  or a SHORTER file -- a record replaced or truncated is not the continuation of a stream of bytes,
  so it is read again from the beginning. A record that cannot be read at all THROWS, and the caller
  answers from memory, which is what the window did before this existed.

  IT ANSWERS ENTRIES, not the `{:ok ..}` / `{:error ..}` shape `record-entries` answers: this is one
  half of the door `window-page` uses, and the other half of that door is memory."
  [stem]
  (let [log   (replay/locate (home/projects-dir) stem)
        path  (.getAbsolutePath log)
        at    (get @record-folds stem)
        ;; THE SAME FILE, STILL GOING FORWARD -- a file that is shorter than what we read of it is a
        ;; different stream of bytes, and so is one that moved.
        same? (and (some? at) (= path (:path at)) (<= (long (:bytes at)) (.length log)))
        from  (if same? at {:path path :bytes 0 :lines 0 :rows 0 :fold (replay/entries-fold)})
        [pairs bytes lines] (replay/rows-after log (long (:bytes from)) (long (:rows from))
                                             (long (:lines from)))
        fold  (if (seq pairs) (replay/entries-fold (:fold from) pairs) (:fold from))]
    (swap! record-folds assoc stem
           {:path  path
            :bytes (long bytes)
            :lines (long lines)
            :rows  (+ (long (:rows from)) (count pairs))
            :fold  fold})
    (replay/entries-of-fold fold)))
(defn- window-page
  "A LIVE conversation's window: {:entries [..] :baseSeq N :hasMore bool} for the tail page
  (`since` nil) or for what arrived after `since`.

  THE SAME CHOICE `read-entries` MAKES, AT THE STREAMING DOOR: the record while a run of it
  is in flight here, else the session's own entries (`sessions/tail` / `sessions/since`).
  READING THE RECORD IS THE POINT; RE-READING IT IS NOT. The delta has to be computed against
  the file as it is NOW, which is what makes a reader's own cursor sufficient (ADR 0003
  decision 7) -- but a pass now pays only for the bytes APPENDED since the last one
  (`record-entries-resumed`). Before that it re-read and re-parsed the whole record on every
  100 ms tick, whatever had grown.

  A RECORD THAT CANNOT BE READ FALLS BACK TO MEMORY. This is a stream that has already
  begun, and refusing the whole window mid-flight would leave the reader holding half of it
  with nothing to say about which half; the PAGE route is where a broken record is reported
  BY NAME (`read-entries`)."
  [stem since]
  (reconcile-numbers! stem)
  (let [running-here? (running? stem)]
    ;; A CURSOR BELONGS TO THE RUN THAT MADE IT NEEDED: the record is what the window reads only
    ;; while a run of this conversation is in flight HERE (`read-entries-raw` says why), so a cursor
    ;; its run has outlived is dropped -- otherwise this map would hold a fold for every
    ;; conversation the process ever watched.
    (when-not running-here? (swap! record-folds dissoc stem))
    (let [from-record (when running-here?
                        (try (record-entries-resumed stem) (catch Throwable _ nil)))
        page (cond
               (nil? from-record)
               (if (nil? since)
                 (sessions/tail stem)
                 {:entries (sessions/since stem since) :baseSeq since :hasMore false})

               (nil? since) (sessions/tail-of from-record)

               :else {:entries (sessions/since-of from-record since) :baseSeq since :hasMore false})]
    ;; THE SAME REPAIR THE PAGE ROUTE MAKES, at the streaming door (`read-entries`): a window is
    ;; a conversation a client draws cards from, and the tail window is where the card of a run
    ;; that just stopped comes from. A session this process does not hold answers nil from
    ;; `sessions/tail`, and a nil window has nothing to repair.
    (if-some [es (:entries page)]
      (assoc page :entries (sessions/revive-parks! stem es))
      page))))

(defn- number-param
  "A query parameter that is meant to be a record offset, or nil when it is absent.
  A value that is not a non-negative integer is refused BY NAME rather than coerced:
  `since=abc` quietly read as 0 would hand the client a window it did not ask for."
  [params name]
  (when-some [v (get params name)]
    (if (re-matches #"[0-9]+" (str v))
      (Long/parseLong (str v))
      (throw (ex-info (str name " must be a non-negative record offset, got " (pr-str v))
                      {:reason :bad-offset :param name :value v})))))

(defn- page-get
  "GET /api/threads/<stem>/page[?beforeSeq=N] -- the tail page of a conversation, or the
  page of entries in front of offset N (the client scrolling up).

  ONE ANSWER, NO STREAM, and that is what makes it usable when the conversation is not
  being held here: it reads memory if this process serves the session and the record if
  it does not. NOTHING IS WRITTEN and no claim is taken -- a reader paging through a
  conversation another process is serving is exactly the case the record is still for.
  (WHAT IS REPAIRED HERE, and it is not the record: `read-entries` rebuilds the in-memory
  parks the conversation names, so the cards it is about to be drawn from can be answered.)

  `beforeSeq` IS THE OLDEST OFFSET THE CLIENT HOLDS, not the newest: the entries it is
  missing are all in front of that one, and the page ends where the client's own window
  begins (`harness.edge.sessions/before` explains why that cannot overlap or skip)."
  [req stem]
  (let [params (query-params (:query-string req))
        before (try {:ok (number-param params "beforeSeq")}
                    (catch Throwable t {:error (ex-message t)}))]
    (if-some [err (:error before)]
      (api-response 400 {:error err :threadId stem})
      (let [read (read-entries stem)]
        (if-some [err (:error read)]
          (api-response (or (:status read) 400) {:error err :threadId stem})
          (let [es  (:ok read)
                page (if-some [b (:ok before)]
                       (sessions/before-of es b)
                       (sessions/tail-of es))]
            (api-response 200 (assoc (window-frame stem
                                                   (if (:ok before) "page" "tail")
                                                   (:state read)
                                                   page)
                                     :live (boolean (:live read))))))))))


;; ---------------------------------------------------------------- the downlink
;;
;; ONE SOCKET FOR EVERY CONVERSATION A PAGE HOLDS (`events.mux`), because the SSE feed
;; it replaces took a connection slot PER CONVERSATION: a page that had opened six
;; sessions held six sockets, the browser's per-origin pool is six, and the next request
;; -- a run, a read, an asset -- waited behind them (ADR 0004).
;;
;; THE SOCKET IS DOWNLINK-ONLY. A frame goes out; nothing comes in. The subscription is
;; therefore an HTTP fact, in two halves:
;;
;;   the handshake URL     `GET /api/events.mux?subscriber=<token>&sessions=<json>`
;;                         names the conversations this connection holds and the cursor
;;                         each starts from -- the same `since`/`generation` the SSE feed
;;                         carried, so a reconnect re-declares what the client holds
;;                         rather than the server remembering it.
;;   POST .../subscribe    the set changing while the socket is up.
;;
;; AND THE SERVER FILTERS: only a conversation this connection subscribed to has a watch,
;; so a ring for any other one reaches nobody here. The cost of N conversations running at
;; once no longer lands on every page -- the DSH broadcast bug this shape avoids.

(defn- mux-frame
  "One frame on the downlink, tagged with the conversation it is about: every `window`
  frame's fields, plus `:threadId`. The tag is what lets one socket carry many windows."
  [thread-id frame]
  (assoc frame :threadId (str thread-id)))

(defn- mux-send!
  "Write one JSON frame to a downlink. A channel that closed under us is not worth
  raising: the close handler is already releasing the subscription."
  [ch frame]
  (try (hk/send! ch (json/write-str frame)) (catch Throwable _ nil)))

(defn- initial-numbers
  "THE NUMBERS A CONVERSATION STARTS WITH, for the moment its first request has just gone out
  and nothing has reported anything yet: the fold's counts as they stand, `0` for the vendor's
  cache share and for the rate, and an ESTIMATE of the tokens that request carries -- which is
  what `harness.edge.pressure`'s band already measures (it prices the request an edge has
  assembled, and at a call's start that request is the one on its way to the vendor). The
  payload is marked `:estimated`, so a client draws the estimate as one (see `statsCells`).

  THE OWNER ASKED FOR THIS (2026-09-27), and it is worth writing down as a DELIBERATE
  EXCEPTION to this repo's oldest rule about numbers -- 'not reported is not zero' -- because a
  strip that draws nothing until the first vendor reply is a strip that looks broken for as
  long as the first call takes. What makes the exception honest rather than a lie is that the
  estimate is MARKED as one: the zeroes are the truth about the vendor (it has reported
  nothing), and the token figures say what was SENT, which is a fact about this moment and not
  a guess about the vendor.

  BASE IS `owned-numbers` OF THE FOLD'S ANSWER -- the keys a push owns and nothing else, so
  this cannot leak a read-fact (`:incomplete`) onto the wire by passing a bigger map in. BAND is
  the pressure band at this moment."
  [base band]
  (let [tokens (:pressureTokens band)]
    (cond-> (assoc base :cacheHitPercent 0 :outputTokensPerSecond 0 :estimated true)
      (number? tokens)
      (assoc :usage {:totalTokens tokens}
             :context (cond-> {:usedTokens tokens}
                        (number? (:windowTokens band))
                        (assoc :windowTokens (:windowTokens band)
                               :percent (:percent band)))))))

(defn- owned-numbers
  "THE KEYS A PUSH OWNS, out of the fold's whole answer: the numbers a fold can move, and none
  of the facts about a READ (`:incomplete`, `:pressure`). One place, so the two phases of a
  call cannot disagree about what a push is allowed to say -- see `live-numbers-slice`."
  [n]
  (cond-> {}
    ;; THE FLAG IS ALWAYS ON THE WIRE, both ways: an end-phase push says `false` out loud so that
    ;; merging it over a `:start` push REPLACES the estimate rather than leaving a stale mark on
    ;; a measured number (`withPushedNumbers` is a spread, and a key nobody sends is a key that
    ;; stays).
    true (assoc :estimated false)
    (contains? n :turns) (assoc :turns (:turns n))
    (contains? n :steps) (assoc :steps (:steps n))
    (contains? n :usage) (assoc :usage (:usage n))
    (contains? n :cacheHitPercent) (assoc :cacheHitPercent (:cacheHitPercent n))
    (contains? n :outputTokensPerSecond)
    (assoc :outputTokensPerSecond (:outputTokensPerSecond n))
    (seq (:context n)) (assoc :context (:context n))))

(defn- live-numbers-slice
  "The slice of `live-numbers` that the `model/*` facts put on the wire (ADR 0006 decision 4):
  the session's numbers AT THIS MOMENT -- how many turns, how many calls so far, what they
  reported, how fast the answers came, and the context ring's four fields.

  PHASE IS WHICH END OF A CALL THIS IS, and it decides ONE thing: at `:start` with nothing yet
  reported by the vendor, the payload is `initial-numbers` -- the counts, zeroes for cache and
  rate, and the ESTIMATE of the request that just went out (see that function, and the owner's
  decision recorded there). Anywhere else the fold's own answer is sent as it stands, absent
  keys included.

  WHY A SLICE RATHER THAN THE WHOLE PAYLOAD: the wire's fact is about a MODEL CALL, while the
  pressure band (the third fold `live-numbers` carries) is the edge's own pre-flight estimate --
  a different question, answered by a different reader. Narrowing here keeps 'one key does not
  mean two things' true across the two families.

  `:turns` RIDES ALONG TOO, and it is here because of WHEN the first push of a session goes
  out: the strip under the composer has no snapshot yet at the first `model/end` of a
  conversation somebody has just started, and a payload with no turn count used to draw the
  RAW KEY `stats.turns` instead of a number (a plural lookup with NO count is not a plural
  lookup at all, so the catalog answered with the key itself -- measured 2026-09-27). It is the same fold's
  answer as the snapshot's, sent a moment earlier, so this is not a second clock; it is the
  existing one arriving in time to be drawn. ('A turn is counted where a run is opened' is
  still true -- by this moment it HAS opened, which is why the fold has the count to give.)

  NIL WHEN THIS PROCESS HOLDS NOTHING, and the caller then sends NO numbers rather than zeroes:
  'not reported' is not zero, which `harness.edge.stats`'s oldest rule.

  IT READS `live-numbers`' OWN SHAPE, and that sentence is here because the first version did
  not: it looked under `(:stats n)` for numbers that `live-numbers` returns FLAT (`stats-answer`
  assoc'd with `:context` and `:pressure`). Every key was therefore missing from a payload that
  was present, the slice was `{}`, and `model/end` had been pushing AN EMPTY MAP since the day
  it was written -- invisible while a snapshot was in hand (merging `{}` changes nothing), and
  the reason a page that had only ever been PUSHED drew a raw catalog key instead of a number
  (measured 2026-09-27). One shape, read as itself."
  ([stem] (live-numbers-slice stem :end))
  ([stem phase]
   (when-some [n (live-numbers stem)]
     (let [base (owned-numbers n)]
       (if (and (= :start phase) (zero? (long (or (:stepsWithUsage n) 0))))
         ;; NOTHING REPORTED YET: this is the conversation's opening request (or one whose
         ;; vendor has told us nothing), and the strip is initialized rather than left blank.
         (initial-numbers base (:pressure n))
         base)))))

(defn- task-body
  "THE TASK PANE'S WHOLE ANSWER for THREAD-ID, as one payload: this session's background jobs
  and the delegations it made -- exactly the two routes the pane reads when it opens
  (`GET /api/threads/<stem>/jobs` and `GET /api/subagents`, narrowed to this parent).

  ONE PAYLOAD FOR TWO SECTIONS, for the reason the pane itself gives: the two sections answer
  one question ('what has this session got going on') and are read at one moment, so a push
  that carried half of it would be a second clock for the other half."
  [thread-id]
  {:jobs        (vec (jobs/listing thread-id))
   :delegations (vec (filter #(= (str thread-id) (str (:parent %))) (subagents/runs)))})

(defn- task-send!
  "PUSH the pane's answer to every connection watching THREAD-ID (ticket 01 of
  `.scratch/task-pane-push`).

  THE INCREMENTAL HALF OF THE PANE, and it is a fourth kind of frame on the session's socket
  -- `{:type task ..}` -- beside a window's frames, a run's AG-UI events and the fact family
  (ADR 0006). It is NOT a `fact`: a fact carries the record's own line number and can be
  replayed by cursor, while a job's ending lives in the JOB's record and a delegation's end is
  a memory of this process -- there is no line to number and nothing to replay, so the frame
  is a whole payload per change, exactly like `events.host`'s.

  A NO-OP WHEN NOBODY IS WATCHING THE SESSION, which is the ordinary case for a background
  job: `mux/channels-for` answers nothing and the building of the body never happens."
  [thread-id]
  (let [channels (mux/channels-for thread-id)]
    (when (seq channels)
      (let [payload (assoc (task-body thread-id) :type "task")]
        (doseq [ch channels]
          (mux-send! ch (mux-frame thread-id payload)))))
    nil))

(defn- family-send!
  "Send ONE fact of the turn / model-call families down the session's downlink (ADR 0006).

  IT DOES NOT GO THROUGH THE RUN RING, unlike `mux-broadcast!`: a run frame is NUMBERED by that
  ring and replayed from it when a socket reconnects (`runSince`), while a fact carries THE
  RECORD'S `seq` (a line number, ADR 0003 decision 1/9) -- the number that can be aligned with
  the window half of the same socket. There is no cursor for this family yet (ticket 05); until
  there is, a page that missed one repairs itself with the snapshot it asks for when it opens
  the conversation, which is decision 5's 'no history'."
  [thread-id fact]
  ;; REMEMBERED BEFORE IT IS SENT (ticket 05): a fact is a PUSH, and one that nobody heard is
  ;; gone unless the sender kept it. A reader that reconnects declares how far it got
  ;; (`factSince`, a RECORD line number) and is handed the rest by `mux-replay-facts!`.
  (mux/record-fact! thread-id fact)
  (let [payload (mux-frame thread-id fact)]
    (doseq [ch (mux/channels-for thread-id)]
      (mux-send! ch payload))))

(defn- mux-broadcast!
  "Number ONE run frame for THREAD-ID, REMEMBER it (so a reader that reconnects can be
  handed the gap -- `harness.edge.mux/record-run!` keeps a bounded ring, and a frame that
  already carries a number, a subagent's, keeps it), and send it to every downlink watching
  the conversation. A page that is not watching hears nothing, which is the subscription
  doing its job."
  [thread-id frame]
  (let [numbered (mux/record-run! thread-id frame)
        payload  (mux-frame thread-id numbered)]
    (doseq [ch (mux/channels-for thread-id)]
      (mux-send! ch payload))))

(defn- mux-replay-run!
  "Hand TOKEN's downlink the run frames of THREAD-ID it is missing: everything remembered
  after RUN-SINCE, oldest first.
  
  A NIL CURSOR REPLAYS NOTHING, and that is deliberate rather than a shortcut: `nil` is 'I
  hold nothing of this run', which is what a connection about to START one declares -- and the
  buffer may still hold the PREVIOUS run's frames, terminal and all, which would finish the
  new run before it began (measured: a suite resuming a parked run was handed the parked run's
  RUN_FINISHED and stopped reading). A RECONNECTING reader has a cursor -- it saw frames -- and
  gets exactly the gap."
  [token thread-id run-since]
  (when (some? run-since)
    (when-some [ch (mux/channel token)]
      (doseq [frame (mux/run-frames-after thread-id run-since)]
        (mux-send! ch (mux-frame thread-id frame))))))

(defn- mux-end!
  "Tell one conversation's reader on this downlink that its window is over, naming why.
  THE SAME ENDING THE FEED SENDS, for the same reason: a stream that just stops leaves a
  replica believing it holds everything. The client's repair is its own -- reopen the tail
  page, then re-subscribe with the new cursor."
  [ch thread-id reason]
  (mux-send! ch (mux-frame thread-id
                           {:type       "end"
                            :reason     reason
                            :entries    []
                            :baseSeq    (:baseSeq (sessions/tail thread-id))
                            :hasMore    false
                            :cursor     nil
                            :generation (sessions/generation thread-id)
                            :state      (live-state thread-id)})))

(defn- mux-sessions
  "The `sessions` query parameter (a JSON array) as the subscriptions it names:
  [{:threadId .. :since .. :generation ..} ..]. A parameter this server cannot read is NO
  subscriptions rather than a failed handshake -- the client's move is a `page` read and a
  re-declare either way."
  [raw]
  (try
    (let [parsed (json/read-str (or raw "[]") :key-fn keyword)]
      (if (vector? parsed)
        (vec (keep (fn [s]
                     (when (map? s)
                       (when-some [tid (:threadId s)]
                         {:threadId   (str tid)
                          :since      (when (number? (:since s)) (long (:since s)))
                          :generation (:generation s)
                          :runSince   (when (number? (:runSince s)) (long (:runSince s)))
                          ;; TICKET 05: the FACT family's own cursor -- a RECORD line number,
                          ;; and a separate number from the run's, because the two count
                          ;; different things (a run frame is numbered by the sender, a fact by
                          ;; the record line it was written for).
                          :factSince  (when (number? (:factSince s)) (long (:factSince s)))})))
                   parsed))
        []))
    (catch Throwable _ [])))

(defn- mux-watch!
  "Subscribe TOKEN's downlink to ONE conversation, starting from SINCE, and send the
  opening frame. The doorbell re-reads the delta from the cursor this closure holds and
  sends what is new -- nothing at all when the frame would carry no news, so a page parked
  on a quiet conversation costs one idle socket. The FIRST frame is always sent, even
  empty, so a reader never wonders whether the socket is working."
  [token thread-id since]
  (let [state (atom {:cursor since :state nil :sent false})]
    (letfn [(push []
              (when-some [ch (mux/channel token)]
                (let [cursor (:cursor @state)
                      page   (window-page thread-id cursor)
                      frame  (window-frame thread-id
                                           (if (nil? cursor) "window" "append")
                                           (live-state thread-id)
                                           page)]
                  (when (or (not (:sent @state))
                            (seq (:entries frame))
                            (not= (:state frame) (:state @state)))
                    (reset! state {:cursor (:cursor frame) :state (:state frame) :sent true})
                    (mux-send! ch (mux-frame thread-id frame))))))]
      (mux/subscribe! token thread-id push)
      (push))))

(defn- mux-add!
  "Subscribe TOKEN's downlink to THREAD-ID under the window rules the feed already
  enforces: a conversation another live process is serving, or a `generation`/`since` that
  names a window that is over, is TOLD so with an `end` frame rather than half served
  (ADR 0003 decision 6)."
  [token thread-id since generation run-since fact-since]
  (let [held (claims/holder thread-id)]
    (cond
      (and (some? held) (not (claims/mine? held)))
      (when-some [ch (mux/channel token)]
        (mux-end! ch thread-id (str "conversation " (pr-str (str thread-id))
                                    " is being served by another harness process (pid "
                                    (:pid held) ")")))

      :else
      (do
        (sessions/touch! thread-id)
        (let [mine   (sessions/generation thread-id)
              tail   (sessions/tail thread-id)
              stale? (or (and (some? generation) (not= (str generation) (str mine)))
                         (and (some? since) (some? (:baseSeq tail))
                              (< (long since) (long (:baseSeq tail)))))]
          (if stale?
            (when-some [ch (mux/channel token)]
              (mux-end! ch thread-id
                        (if (and (some? generation) (not= (str generation) (str mine)))
                          "this conversation is being served under a new generation"
                          (str "the cursor (since=" since ") is older than the oldest"
                               " entry this window still answers from"))))
            (do (mux-watch! token thread-id since)
                ;; AND THE RUN FRAMES THIS CONNECTION IS MISSING. A window read is a PULL, so
                ;; a cursor re-reads it; a run is a PUSH, so the frames that happened while a
                ;; socket was down are only here because `mux-broadcast!` remembered them.
                (mux-replay-run! token thread-id run-since)
                ;; AND THE FACTS THIS CONNECTION IS MISSING (ticket 05, ADR 0006): the same
                ;; push and the same curse -- remembered by `family-send!`'s `record-fact!` and
                ;; replayed here from the RECORD LINE NUMBER the reader last held. A reader that
                ;; never held one declares `factSince` nil and is handed what is kept.
                (when (some? fact-since)
                  (when-some [ch (mux/channel token)]
                    (doseq [fact (mux/facts-after thread-id fact-since)]
                      (mux-send! ch (mux-frame thread-id fact))))))))))))

(defn- mux-attend!
  "A downlink just connected: remember it, and subscribe it to everything the handshake
  URL declared."
  [token ch wanted]
  (mux/attach! token ch)
  (doseq [{:keys [threadId since generation runSince factSince]} wanted]
    (mux-add! token threadId since generation runSince factSince)))

(defn- mux-get
  "GET /api/events.mux?subscriber=<token>&sessions=<json> -- the downlink. A WebSocket, or
  a 400 when there is no subscriber token to address the set with: the socket carries no
  subscription message, so a connection that names no token could never change its set."
  [req]
  (let [params (query-params (:query-string req))
        token  (get params "subscriber")
        wanted (mux-sessions (get params "sessions"))]
    (if (str/blank? token)
      (api-response 400 {:error "events.mux needs a subscriber token: ?subscriber=<token>"
                         :hint  (str "the socket is downlink-only, so the set is updated"
                                     " by POST /api/events.mux/subscribe")})
      (hk/as-channel req
                     {:on-open  (fn [ch]
                                  ;; ON-OPEN RUNS AFTER THIS RING MAP IS RETURNED, on http-kit's
                                  ;; thread -- so `handler`'s net is not around it, and a throw
                                  ;; here would be a silently dropped connection.
                                  (try (mux-attend! token ch wanted)
                                       (catch Throwable t
                                         (log/error! :mux/attend-failed t {:subscriber token}))))
                      :on-close (fn [ch _status]
                                  (try (mux/detach! (mux/token-of ch))
                                       (catch Throwable _ nil)))}))))

(defn- mux-subscribe-post
  "POST /api/events.mux/subscribe {subscriber, subscribe: [..], unsubscribe: [..]} --
  UPDATE THE SET of a live downlink. This is the half of the subscription the socket
  itself cannot carry; the handshake URL is the other half."
  [req]
  (let [parsed (try (json/read-str (slurp (:body req) :encoding "UTF-8") :key-fn keyword)
                    (catch Throwable _ nil))]
    (cond
      (not (map? parsed))
      (api-response 400 {:error "request body is not valid JSON"})

      (str/blank? (:subscriber parsed))
      (api-response 400 {:error "missing subscriber"})

      (nil? (mux/channel (:subscriber parsed)))
      (api-response 404 {:error (str "no such downlink connection "
                                     (pr-str (str (:subscriber parsed)))
                                     ": it has closed or was never opened")
                         :subscriber (:subscriber parsed)})

      :else
      (let [token (str (:subscriber parsed))]
        (doseq [thread-id (:unsubscribe parsed)]
          (mux/unsubscribe! token thread-id))
        (doseq [sub (:subscribe parsed)]
          (mux-add! token (:threadId sub) (:since sub) (:generation sub) (:runSince sub)
                   ;; A SUBSCRIBE THAT DOES NOT DECLARE A FACT CURSOR IS HANDED WHAT IS KEPT:
                   ;; nil means 'I hold nothing' (`harness.edge.mux/facts-after`).
                   (:factSince sub)))
        (api-response 200 {:subscriber token
                           :threads    (vec (mux/subscriptions token))})))))

;; ------------------------------------------------------------- the host stream
;;
;; THE OTHER DOWNLINK CATEGORY (ADR 0004): facts about the HOME rather than one
;; conversation -- which sessions and projects exist, what they are called and when last
;; sent to, which are archived, and which have a run going in this process. A sidebar draws
;; all of it from `GET /api/projects`' one payload, and this is that same payload PUSHED
;; whenever one of those facts changes, so two windows agree without a refresh button.
;;
;; NO SUBSCRIPTION HERE, unlike `events.mux`: every connection wants the same listing, so
;; a ring writes to all of them (`harness.edge.host`).

(defn- host-frame
  "The host-level facts as one downlink frame: the SAME payload `GET /api/projects` answers
  with, plus the type tag that tells this category from a window frame."
  [] (assoc (projects-body) :type "projects"))

(defn host-frame-for-test
  "The frame a host watcher's push hands its connection, as data -- a test seam, and
  nothing else reads it (see the host-stream tests and ticket 02's push case)."
  [] (host-frame))

(defn- host-get
  "GET /api/events.host -- the host-level downlink. A WebSocket that is handed the listing
  at once and then once per host-level change; nothing is sent over it and there is no set
  to subscribe (see `host-frame`)."
  [req]
  (let [registered (atom nil)]
    (hk/as-channel req
                   {:on-open  (fn [ch]
                                (try
                                  (let [push (fn [] (mux-send! ch (host-frame)))]
                                    (reset! registered push)
                                    (host/watch! push)
                                    ;; THE FIRST LISTING AT ONCE: a page that opens this
                                    ;; stream must not wait for the next change to draw a
                                    ;; sidebar.
                                    (push))
                                  (catch Throwable t
                                    (log/error! :host/watch-failed t))))
                    :on-close (fn [_ch _status]
                                (when-some [push @registered]
                                  (host/unwatch! push)))})))

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
          (rung (api-response 200 {:projectId (:project-id (:ok added))
                                      :path      (:path (:ok added))
                                      ;; Sessions that remembered this directory, coming
                                      ;; back with it -- see `remove-project!`. On a
                                      ;; fresh add it is 0, which is the truthful answer
                                      ;; rather than a field nobody sets.
                                      :adopted   (:adopted (:ok added))})))))))

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
      (rung (api-response 200 {:path    (:path (:ok removed))
                                  :unbound (:unbound (:ok removed))})))))

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
  WHO asked, what they asked, and the JSON Schema it wants filled in.

  WHY THIS IS AN ENDPOINT AND NOT A FIELD ON THE INTERRUPT. AG-UI's interrupt
  object is a strict shape -- id, reason, message, toolCallId and a couple more, and
  a client's own validator refuses anything else -- so a form schema stuffed into it
  would be a protocol change this harness has no business making. The interrupt says
  'something is asking a question' and carries the question's sentence; the SHAPE of
  the answer is fetched here, by the client that is about to draw it.

  WHO ASKED IS TWO FACTS, NOT ONE, and both are answered because a card has to say
  it. `server` names the outside program that asked, for the questions harness.cap.mcp
  brings in; `askedBy` is for the questions a tool of this harness asks on its own
  (`ask`), where there is no server and where 'a server is asking you' would be
  false. A question with neither is answered as such -- ABSENT KEYS, not nulls --
  because the client tells the two apart by presence, and `null` would read as a
  server whose name is null.

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
          (api-response 200 (cond-> {:interruptId id
                                     :prompt      (:prompt rec)
                                     :schema      (:schema rec)}
                              (:server rec)     (assoc :server (:server rec))
                              (:asked-by rec)   (assoc :askedBy (name (:asked-by rec)))
                              (:expires-at rec) (assoc :expiresAt (:expires-at rec))))
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

(defn- language-get
  "GET /api/language -- the language this home speaks, resolved by
  harness.infra.language (config.edn's :ui :language, then the OS's, then the
  terminal's, then English). It always answers one of the two, so a client that has
  nowhere else to look still has an answer.

  IT CARRIES NO THREAD and needs none: a language is a fact about the HOME, not a
  session, which is why this is a route of its own rather than a field on
  /api/settings -- that one is asked with a threadId and answers per-session state."
  [_req]
  (api-response 200 {:language (name (language/resolved))}))

(defn- language-post
  "POST /api/language {language: \"zh\" | \"en\"} -- write the choice into config.edn's
  :ui :language and answer the language now resolved.

  A REFUSED VALUE IS A 400 WITH THE SERVER'S SENTENCE and nothing is written, the same
  rule POST /api/model keeps. The answer is the RESOLVED language rather than the value
  that was sent, so a caller never has to guess what its own value meant."
  [req]
  (let [body   (json/read-str (slurp (:body req) :encoding "UTF-8") :key-fn keyword)
        answer (try {:ok (providers/set-language! (:language body))}
                    (catch Throwable t {:error (ex-message t)}))]
    (if-some [error (:error answer)]
      (api-response 400 {:error error})
      (api-response 200 {:language (name (language/resolved))}))))

(defn- security-wire
  "providers/sensitive-paths-config -> the shape that may leave this process: the list as
  WRITTEN under `sensitive-paths`, where it came from under `source`, and the built-in list
  under `defaults`.

  THE WRITTEN FORM, NOT THE EXPANDED ONE, and that is the point of this route: a client
  edits what the file would hold, so `~/.ssh/` is what it has to be shown. What the park
  rule compares against (providers/sensitive-paths, `~` expanded) is a machine fact, and a
  panel showing it would be showing paths nobody typed."
  []
  (let [{:keys [paths source defaults]} (providers/sensitive-paths-config)]
    {:sensitive-paths paths :source (name source) :defaults defaults}))

(defn- security-get
  "GET /api/security -- the paths THIS HOME declares sensitive, as the park rule reads
  them: the list, where it came from (:config when this home wrote one, :default when it
  wrote none), and the built-in list beside it so a client can offer 'restore the default
  list' without a second request.

  A ROUTE OF ITS OWN, for the reason /api/language is one: this is a fact about the HOME
  rather than about a session, so it is asked with no threadId at all and has no per-session
  answer. What it MEANS for a call -- which tools park on which path -- is
  harness.cap.project's business, not this route's.

  READ-ONLY, and therefore leaves no trace: like GET /api/model and GET /api/settings, only
  a route that can CHANGE something writes an audit line."
  [_req]
  (api-response 200 (security-wire)))

(defn- security-post
  "POST /api/security {sensitive-paths: [\"~/.ssh/\", ..]} -- write the list into
  config.edn's :security and answer the list now in force, in the shape GET answers.

  THE WHOLE LIST IS REPLACED, because that is what the request says: a list is edited by
  adding and removing entries, and a client that sends the list it wants has said everything
  there is to say. AN EMPTY ARRAY IS A DECISION -- 'this home guards nothing' -- while a
  body that names no list at all is refused by name, so the two cannot look the same.

  A REFUSED VALUE IS A 400 WITH THE SERVER'S SENTENCE and nothing is written, the rule
  POST /api/language and POST /api/model keep. The answer is the list READ BACK rather than
  the list that was sent, so a caller never has to guess what its own value meant."
  [req]
  (let [parsed (try {:ok (json/read-str (slurp (:body req) :encoding "UTF-8") :key-fn keyword)}
                    (catch Throwable _ {:bad true}))
        {:keys [ok bad]} parsed]
    (cond
      bad
      (api-response 400 {:error "request body is not valid JSON"})

      (not (and (map? ok) (contains? ok :sensitive-paths)))
      (api-response 400 {:error (str "missing sensitive-paths; send the whole list, [] to"
                                      " guard nothing")})

      :else
      (let [answer (try {:ok (providers/set-sensitive-paths! (:sensitive-paths ok))}
                        (catch Throwable t {:error (ex-message t)}))]
        (if-some [error (:error answer)]
          (api-response 400 {:error error})
          (api-response 200 (security-wire)))))))

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

  THE ONLY WAY A SESSION'S SELECTION MOVES, now that the `session-configure`
  tool is gone: the composer's picker presses this, and nothing else writes the
  session tier. The three knobs are exactly provider, model and reasoning-effort,
  an unknown key is refused by name, and the change is validated by RESOLVING IT
  before anything is written -- a change that cannot be served is not a change,
  and writing first would leave the session holding a configuration every later
  run fails on.

  WHY IT WRITES ITS OWN LOG LINE INSTEAD OF USING THE PROVIDER OUTBOX. This route
  IS the edge, so it writes at the moment of the change, exactly as
  POST /api/project does; the outbox is for code that is NOT the edge and cannot
  write a line at all. Using it here would be worse than redundant: nothing drains
  it outside a run, so a change made in the composer of an idle session would
  surface in the log attached to the NEXT run -- a timeline that says the model
  changed after it did. The outbox has no producer at all today; see
  harness.cap.providers for why it stays anyway.

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
              ;; tier here and writing it back would lose a change another press made in
              ;; between -- this route runs on an http-kit thread, so two presses of the
              ;; picker can overlap -- and this line would then record a before->after
              ;; pair that never happened.
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
  exactly as a run resolves it. The answer carries model ROWS -- the id, and the
  delivery mode the catalog suggests for it when a prefix table speaks for that family
  (`providers/suggested-instruction-updates`, ticket 04's prefill) -- and the endpoint
  that was asked; the key is in neither, and nothing is written: no file, no store, no
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

  `?dir=..` ASKS THE SAME QUESTION ABOUT A DIRECTORY, and it exists for the one session
  that has no row: the id this page minted and has not sent to (the composer's picker and
  the sidebar's 'New session' both remember a directory and write nothing). `?threadId=`
  answers `{:dir nil}` for it -- correctly, for an id this home has never heard of -- so the
  strip could never draw the branch of the repository in front of it. A directory this home
  does not list is answered exactly like a session with none; the gate and its reason are
  `project/listed-dir`'s.

  A SESSION WITH NO DIRECTORY ANSWERS `{:dir nil :repo? false}`, not a 400: most
  sessions have no project, the strip simply shows nothing, and a caller drawing
  chrome should not have to treat 'nothing to show' as a failure."
  [req]
  (let [params (query-params (:query-string req))
        asked  (get params "dir")
        dir    (if (str/blank? (str asked))
                 (project/binding-for (get params "threadId"))
                 (project/listed-dir asked))]
    (api-response 200 (assoc (git/state dir) :dir dir))))

(defn- git-post
  "POST /api/git {threadId, branch} -- move the session's directory onto BRANCH,
  and answer the state afterwards.

  {dir, branch} IS THE SAME VERB FOR A DIRECTORY, and it is what the composer's branch
  picker uses before the session exists (`project/listed-dir` is the gate, and the reason is
  the GET's). IT WRITES NO AUDIT LINE, and that is not an omission: the line belongs in a
  session's log, and the session this is for is one this page minted and has not sent to --
  writing a log for it is exactly the row `POST /api/project` stopped creating (点击新增不立刻
  会话，发送才新建). The checkout itself is not hidden: it is a real change in a real working
  tree, and `git reflog` in that directory is where git keeps it.

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
        {:keys [ok bad]} parsed
        thread-id (str (:threadId ok))
        asked     (str (:dir ok))]
    (cond
      bad
      (api-response 400 {:error "request body is not valid JSON"})

      (and (str/blank? thread-id) (str/blank? asked))
      (api-response 400 {:error "missing threadId or dir"})

      :else
      (let [by-dir? (not (str/blank? asked))
            dir     (if by-dir? (project/listed-dir asked) (project/binding-for thread-id))]
        (if (str/blank? (str dir))
          (api-response 400 {:error (if by-dir?
                                      "this home does not list that directory as a project, so it has no branch to switch"
                                      "this session has no project directory, so it has no branch to switch")})
          (let [before   (git/state dir)
                answer   (git/switch! dir (:branch ok))]
            (if-some [error (:error answer)]
              (api-response 400 {:error error})
              (do (when-not by-dir?
                    (log! thread-id nil "git/branch"
                          {:before (:branch before) :after (:branch (:ok answer))
                           :dir dir :via "http"}))
                  (api-response 200 (assoc (:ok answer) :dir dir))))))))))

(defonce ^:private compaction-lock
  ;; ONE LOCK FOR ALL COMPACTIONS IN THIS PROCESS. The DECISION (read the record, is a
  ;; compaction already open, what is the range) and the WRITE must be one critical section:
  ;; two requests that each read a lock-free record would both compact. Compactions are rare
  ;; (a person's `/compact`, or a run crossing the threshold), so one lock is the right size.
  (Object.))

(defn- run-compaction!
  "One compaction, against RECORDS with PROVIDER, measuring against WINDOW: summarize (ONE
  model call, bracketed like any other), write the rows, and tell the live session. Returns
  the result map, or nil when there was nothing to compact. Shared by the manual route, the
  automatic trigger and the overflow recovery so the three cannot drift.

  OPTS' `:aggressive?` picks the plan: the ordinary budget-keeping one, or
  `compaction/overflow-plan` -- the one used after the vendor has ALREADY refused the request
  for its length, which ignores the budget and keeps only the newest indivisible unit.

  IT ANSWERS WHAT IT DID, AND A CALLER THAT HAS A RUN SAYS IT OUT LOUD. The map is
  `harness.edge.compaction/perform!`'s own, so `:compactionId` names the pair of rows just written
  and `:tokens` is the size of the range they replace. Nothing HERE builds a frame, because the
  manual route has no run to speak into; the callers that do build one pass this map to
  `harness.edge.ag_ui/compacted-frame` -- including the trigger at a run's head, whose answer
  `run-agent!` holds until the run's first frames go out."
  [stem provider records window ratios opts]
  (let [written   (atom [])
        put       (fn [kind payload]
                    (swap! written conj [kind payload])
                    (log! stem nil kind payload))
        ;; FIRED BEFORE THE SUMMARY REQUEST IS BUILT, so that what a declaration prints can
        ;; ride on it (`.scratch`: the place a project says which worktree this is). The
        ;; post one below is still just an observer.
        pre       (hook/emit :pre-compact {:thread-id stem})
        summarize (fn [messages instruction]
                    (let [specs []
                          ;; AND THIS SUMMARY IS A MODEL CALL TOO, so it carries the same
                          ;; idle guard the run path carries -- read off the session whose
                          ;; conversation is being summarized.
                          p     (assoc provider :tools specs
                                       :idle-timeout-ms (llm-timeout/idle-timeout-ms stem))]
                      (put "model/start" (dissoc (ev/model-start p specs) :type))
                      (try
                        (let [{:keys [message telemetry]}
                              ;; THE SUMMARY CALL IS A PROVIDER CALL, so the array it is handed is
                              ;; put in the PROVIDER's shape first, by the SAME fold the run path uses
                              ;; (`ag/provider-messages`) rather than a second copy of it.
                              ;;
                              ;; THE PLAN'S MESSAGES ARE THE MODEL VIEW -- AG-UI, where a thought is a message
                              ;; of its own with role "reasoning" -- and a vendor refuses that role
                              ;; outright (`messages[N].role: unknown variant \`reasoning\``, measured
                              ;; on a real log: 15 compactions of one session, 15 refusals, every one
                              ;; the same sentence, and the record never got a `context/compacted`).
                              ;; Folding carries the thought across as the `reasoning_content` the
                              ;; vendor bills instead of dropping it.
                              (llm/stream! p
                                           ;; WHAT THE SUMMARIZER IS HANDED IS ONE EXPRESSION
                                           ;; (`compaction/summary-messages`): the provider's
                                           ;; shape, every recorded tool answer moved behind its
                                           ;; call, and the instruction last -- the same fold and
                                           ;; the same repair the run path uses.
                                           (compaction/summary-messages messages instruction)
                                           (fn [_]) stem)]
                          (put "model/end" telemetry)
                          (let [content (:content message)]
                            (if (string? content) content (str content))))
                        (catch Throwable t
                          (put "model/end" {})
                          (throw t)))))]
    ;; THE TWO HOOK POINTS THE TABLE DECLARED (`harness.kernel.hooks`) BRACKET every compaction,
    ;; automatic or manual. THE PRE ONE HAS ALREADY FIRED -- above, before the summary request
    ;; was built, so that what it printed could ride on it. Neither can gate: they are
    ;; observers (`:gate? false`), and with no sink bound (a manual compaction outside a run)
    ;; both are simply quiet.
    (let [result (compaction/perform! records
                                      {:window       window
                                       :retain-ratio (:retain-ratio ratios)
                                       :append       put
                                       :plan-fn      (when (:aggressive? opts) compaction/overflow-plan)
                                       :summarize    summarize
                                       ;; THE TWO PIECES OF THE PROMPT THE EDGE OWNS: where the
                                       ;; work is happening (git, read on the session's own
                                       ;; directory) and what a `:pre-compact` hook printed.
                                       ;; `compaction/start` records the assembled prompt.
                                       :environment  (compaction/environment-block
                                                      (compaction/environment
                                                       (project/binding-for stem)))
                                       :blocks       (:blocks pre)})]
      (when (seq @written)
        (sessions/set-compactions!
         stem
         (replay/compaction-facts
          (into (vec records) (map (fn [[k p]] (row-of k p)) @written)))))
      ;; fired even when `perform!` had nothing to compact -- `:pre-compact` already fired, and a
      ;; half-open pair would be the worse trace.
      (hook/emit :post-compact {:thread-id stem})
      result)))

(defn- prune-results!
  "Elide the oversized TOOL RESULTS in RECORDS (ticket 06): a local, deterministic cut that
  spends NOTHING -- no model call, no window, no estimate. One `context/pruned` receipt per
  replaced result names the event it shadowed and the code points either side of the cut; the
  live session is told so `messages` folds the elided text at once.

  Answers `{:records <records + the receipts> :reduction {:pruned n :removed n}}`, or nil when no
  result was long enough to cut. THE LOCK IS THE CALLER'S: this is one step of the same critical
  section as a compaction, so it cannot race one."
  [stem records]
  (let [done (into #{} (map :toolCallId) (replay/prune-facts (vec records)))
        plan (prune/prune-plan (replay/entries (vec records)) done)]
    (when (seq plan)
      ;; THE LIVE SESSION IS TOLD, so `messages` folds the elided text at once rather than
      ;; waiting for a rebuild. Holding it first is what makes `set-prunes!` land.
      (sessions/touch! stem)
      (let [written (atom [])]
        (doseq [p plan]
          (let [payload (prune/receipt p)]
            (swap! written conj ["context/pruned" payload])
            (log! stem nil "context/pruned" payload)))
        (let [all (into (vec records) (map (fn [[k p]] (row-of k p)) @written))]
          (sessions/set-prunes! stem (replay/prune-facts all))
          {:records   all
           :reduction (prune/reduction plan)})))))

(defn- recover-overflow!
  "ONE AGGRESSIVE COMPACTION after the vendor refused the request for its LENGTH (ticket 05).

  Returns the SHORTER model view to retry with, or nil when nothing could be removed -- and the
  caller then hands the vendor's own refusal out UNTOUCHED. It never throws: a failure in the
  recovery must not replace the error that explains the run.

  NOTHING ABOUT CAPACITY IS READ. The vendor has already answered, so no window and no estimate
  is needed to justify the compaction -- `compaction/overflow-plan` ignores both on purpose.

  THE LOCK IS THE SAME ONE the manual route and the automatic trigger take, so a recovery
  cannot race either.

  THE RETRY KEEPS THE SYSTEM MESSAGE and takes the conversation from the RECORD's view -- pruned
  and, when it could be, compacted -- so what goes out is a request built the one way this
  harness builds one. THE FREE STEP GOES FIRST (ticket 06): an oversized tool result is elided
  without any model call, and that alone can be the progress the retry rests on -- so the
  aggressive summary is attempted NEXT, and if IT fails the pruned view is still a shorter model
  view, which is what the retry needs. Anything this run produced that has not reached the record
  and the derived injections are not re-sent: the run continues from the recorded conversation.

  THE VIEW IS MEASURED BEFORE AND AFTER, over the CONVERSATION alone -- not over what was
  actually sent, whose derived injections would make any view look shorter. A pass that removed
  nothing answers nil rather than retrying the same overflowing request.

  WHAT IT FOLDED AWAY IS SAID OUT LOUD (`emit`): the vendor refused this request for its length,
  so the run has just taken the front of the conversation off the model's view, and that is
  precisely the moment a person watching needs to be told about. A PASS THAT ONLY PRUNED EMITS
  NOTHING -- pruning is a different fact with its own name (`context/pruned`), and a `compacted`
  card over it would be a card about work that did not happen."
  [stem provider history _t emit]
  (try
    (locking compaction-lock
      (when-some [f (replay/find-log (home/projects-dir) stem)]
        (let [records (replay/read-records f)
              before  (sessions/messages stem)
              ;; 1. THE FREE STEP: elide giant tool results, no model call.
              pruned  (prune-results! stem records)
              records (or (:records pruned) records)
              ratios  (compaction/config stem)
              ;; 2. THE AGGRESSIVE SUMMARY. Its failure is not fatal while pruning made progress.
              ;; 2. THE AGGRESSIVE SUMMARY. Its failure is not fatal while pruning made progress,
              ;;    and its success is SAID OUT LOUD before the retry goes out: the run has just
              ;;    folded the front of this conversation away, and that card belongs on screen
              ;;    whether or not the shorter view survives the check below.
              _       (when-some [compacted (try
                                            (run-compaction! stem provider records
                                                             (:context-window provider) ratios
                                                             {:aggressive? true})
                                            (catch Throwable _ nil))]
                        (emit (ag/compacted-frame compacted)))
              system  (vec (take-while #(= "system" (:role %)) history))
              after   (sessions/messages stem)]
          (when (< (pressure/estimate-messages after) (pressure/estimate-messages before))
            (into system (ag/provider-messages after))))))
    (catch Throwable _ nil)))

(defn- relieve-pressure!
  "MID-RUN AUTO COMPACTION (ticket 04 of `.scratch/compaction-shape`): the run-start trigger's
  question -- is the window about to run out? -- asked before EVERY model call instead of once
  per run, and answered with a SHORTER model view when it is.

  HISTORY IS THE ARRAY ABOUT TO GO OUT, which is the point: the meter is handed a REQUEST
  rather than a record it hopes agrees with one (`log-pressure`'s own contract), and this run's
  own last call is what anchors it -- the freshest number there is.

  NIL IS ALMOST EVERY CALL AND COSTS NO FILE READ: the meter's band is kept in memory and its
  window is the provider's. The record is read, and the compaction lock taken, only once the
  threshold is actually crossed -- and nothing is written when it is not (the one
  `context/pressure` row a run leaves is the line `run-agent!` writes before its first call).

  THE VIEW IS REBUILT FROM THE SESSION rather than spliced into the array it was handed: a
  provider message carries no id (`harness.edge.ag-ui/absorbed` takes the envelope off), so the
  messages a compaction shadowed cannot be found in that array. Same shape
  `recover-overflow!` answers with -- and the loop re-applies its own per-call step to it,
  because what comes back is the CONVERSATION and not this run's decorations.

  IT NEVER THROWS AND NEVER SHORTENS NOTHING: a failure answers nil, and so does a view the
  estimator says is not shorter. The run then carries on with the array it had.

  ITS SUCCESS IS SAID OUT LOUD (`emit`), AND NOT CONDITIONALLY ON THE VIEW IT ANSWERS: the rows are
  written and the session's own model view has already moved, so the conversation IS compacted even
  when the shorter array is one this call declines to take (the loop refuses a view that would
  leave a tool call unanswered). A card withheld in that case would be the harness hiding
  something it had already done."
  [stem provider history emit]
  (try
    (let [ratios (compaction/config stem)
          window (:context-window provider)
          answer (pressure/log-pressure stem history window)]
      (when (and (:thresholdTokens answer)
                 (>= (:pressureTokens answer) (:thresholdTokens answer)))
        (locking compaction-lock
          (when-some [f (replay/find-log (home/projects-dir) stem)]
            (let [records (vec (replay/read-records f))
                  before  (pressure/estimate-messages history)]
              (when-some [compacted (run-compaction! stem provider records window ratios nil)]
                ;; AND THE CARD GOES OUT THE MOMENT IT IS TRUE -- not when the view below survives.
                ;; The rows are written and the session's own model view has already moved, so the
                ;; conversation IS compacted; the comparison below only decides whether THIS call
                ;; takes the shorter array.
                (emit (ag/compacted-frame compacted))
                (let [system (vec (take-while #(= "system" (:role %)) history))
                      view   (into system (ag/provider-messages (sessions/messages stem)))]
                  (when (< (pressure/estimate-messages view) before)
                    view))))))))
    (catch Throwable _ nil)))

(defn- compact-if-pressured!
  "AUTO COMPACTION (ticket 04): at the start of a run, BEFORE it derives its request, measure
  the pressure against the window the record describes and compact when it is at or over the
  threshold. Below the threshold, nothing happens at all -- no rows, no model call.
  `harness.edge.pressure` owns the threshold and where the window comes from.

  FAILS SOFT: a report-only meter must never become a dead run, so anything wrong here is
  logged and the run carries on uncompacted.

  PRUNING GOES FIRST AND MAY BE ENOUGH (ticket 06): the oversized TOOL RESULTS are elided for
  free, the pressure is MEASURED AGAIN over the pruned surface, and a view that came back under
  the threshold skips the summary entirely -- one model call that does not happen.

  IT ANSWERS WHAT IT COMPACTED, or nil, where it used to answer nothing at all: this compaction
  happens BEFORE the run has emitted anything, so its card cannot go out from here -- a frame
  before RUN_STARTED has no run to hang on -- and `run-agent!` holds this answer until the run's
  first frames (see the `:run/start` branch there). Failing soft still answers nil, which draws no
  card."
  [stem]
  (try
    (locking compaction-lock
      (when-some [f (replay/find-log (home/projects-dir) stem)]
        (let [ratios (compaction/config stem)
              ;; 1. THE CHEAP CHECK (ticket 03): the meter band answers 'how full is the next
              ;;    request' WITHOUT reading the record, so a session nowhere near the
              ;;    threshold never touches the file -- which is almost every run. THE SURFACE
              ;;    IS THE REQUEST the next call would carry, system message and all
              ;;    (`harness.edge.pressure/live-surface`), not the conversation.
              quick  (pressure/band-pressure stem (pressure/live-surface stem) ratios)]
          (when (and (:thresholdTokens quick)
                     (>= (:pressureTokens quick) (:thresholdTokens quick)))
            ;; 2. AT OR OVER THE THRESHOLD, and only now is the record worth reading: the
            ;;    free pruning step and the lock check both need it, and pruning can bring a
            ;;    view back under the threshold on its own.
            (let [records (replay/read-records f)
                  pruned  (prune-results! stem records)
                  records (or (:records pruned) records)
                  ;; 3. RE-MEASURE over what the model would now be handed.
                  answer  (pressure/records->pressure records (pressure/live-surface stem) ratios)]
              (when (and (:thresholdTokens answer)
                         (>= (:pressureTokens answer) (:thresholdTokens answer))
                         (not (compaction/lock-active? records)))
                (when-some [provider (providers/current-provider stem)]
                  (run-compaction! stem provider records (:windowTokens answer) ratios nil))))))))
    (catch Throwable t
      (log/warn! :compaction/auto-failed {:thread-id stem :reason (ex-message t)})
      nil)))

(defn- compact-post
  "POST /api/threads/<stem>/compact -- one compaction, run by hand (ticket 03).

  IT DECIDES, SUMMARIZES WITH ONE MODEL CALL, AND WRITES THE ROWS; then it tells the live
  session what changed, so the model view the NEXT request is built from is the compacted
  one. The lock, the range and the no-op rule live in `harness.edge.compaction` and are
  tested without any of this.

  NOTHING TO DO IS NOT AN ERROR: a session with no provider, no declared window, or nothing
  past the retained tail is answered plainly, and a compaction writes no rows at all."
  [req stem]
  (let [located  (try {:ok (replay/locate (home/projects-dir) stem)}
                      (catch Throwable t {:error (ex-message t)}))
        provider (try (providers/current-provider stem) (catch Throwable _ nil))]
    (cond
      (some? (:error located))
      (api-response 404 {:error (:error located) :threadId stem})

      (nil? provider)
      (api-response 400 {:error "this session has no provider to summarize with"})

      (nil? (:context-window provider))
      (api-response 400 {:error "this model declares no context window, so there is nothing to measure against"})

      (running? stem)
      (api-response 409 {:error "this session has a run in flight; compact between turns"})
      :else
      (locking compaction-lock
      (let [read (try {:ok (replay/read-records (:ok located))}
                      (catch Throwable t {:error (ex-message t)}))]
        (if (some? (:error read))
          (api-response 400 {:error (:error read) :threadId stem})
          ;; A COMPACTION WRITES ROWS TO THIS RECORD (`context/compacted`, the summary's rows),
          ;; so it is one of the doors that may not write to a record that is not normalized --
          ;; the same refusal a run gets, naming the same door out (the fork).
          (let [verdict (normalized/normalized? (:ok read))]
            (if-not (:normalized? verdict)
              (refuse-unnormalized! stem verdict)
              (try
            (let [result (run-compaction! stem provider (:ok read)
                                            (:context-window provider) (compaction/config stem) nil)]
              (api-response 200 {:threadId  stem
                                 :compacted (some? result)
                                 :shadowed  (:shadowed result)}))
            (catch Throwable t
              (api-response 400 {:error (ex-message t) :threadId stem})))))))))))

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

    ;; THE DOWNLINK (ADR 0004): one WebSocket per page carrying every conversation it
    ;; holds, and the HTTP route that updates which ones those are.
    (= "/api/events.mux" (:uri req))
    (case (:request-method req)
      :get  (mux-get req)
      (api-response 405 {:error "method not allowed"}))

    (= "/api/events.mux/subscribe" (:uri req))
    (case (:request-method req)
      :post (mux-subscribe-post req)
      (api-response 405 {:error "method not allowed"}))

    ;; THE HOST DOWNLINK: the sidebar's listing, pushed. One per page, no subscription.
    (= "/api/events.host" (:uri req))
    (case (:request-method req)
      :get  (host-get req)
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

    (= "/api/language" (:uri req))
    (case (:request-method req)
      :get  (language-get req)
      :post (language-post req)
      (api-response 405 {:error "method not allowed"}))

    (= "/api/security" (:uri req))
    (case (:request-method req)
      :get  (security-get req)
      :post (security-post req)
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
      ;; The verb-carrying routes: one shape, many verbs. The POST-only verbs have an
      ;; effect and the GET-only verbs only read --
      ;; and `jobs` HAS BOTH, because a GET lists the process's jobs and a POST
      ;; stops one of them -- so the rule is 'the method says whether there is an
      ;; effect', not 'this shape is POST-only'. A method this shape does not serve
      ;; 405 HERE rather than falling through to the run endpoint -- which is where
      ;; the pre-verb dispatch used to send it, and where it became a 500 from a
      ;; body that was never there.
      (case [(:request-method req) verb]
        [:post "rebuild"] (rebuild-post req stem)
        [:post "fork"]    (fork-post req stem)
        [:get "fork-points"] (fork-points-get stem)
        [:post "compact"] (compact-post req stem)
        [:post "cancel"]  (cancel-post stem)
        [:post "archive"] (archive-post req stem)
        [:get "stats"]    (stats-get req stem)
        [:get "jobs"]    (jobs-get stem)
        [:post "jobs"]   (jobs-post req stem)
        [:get "trajectory"] (trajectory-get req stem)
        [:get "sofar"]    (sofar-get req stem)
        [:get "page"]     (page-get req stem)
        [:get "delegations"] (delegations-get stem)
        [:get "frames"]    (frames-get stem)
        [:get "todos"]    (todos-get stem)
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
          ;;
          ;; THE BUILT PAGE SITS HERE, in front of the 404 and behind every route
          ;; above it. It answers only GET/HEAD outside `/api` (harness.edge.ui
          ;; refuses the rest), so nothing on the management edge can be shadowed
          ;; by a file, and a request the table does not know is still a 404 --
          ;; one that names the missing build when the page itself is what was
          ;; asked for.
          (or (ui/answer req)
              (ui/absent req)
              (api-response 404 {:error (str "no such route: " (:uri req))}))))))))

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
                   (cap-mcp/install!)
                   ;; SUBAGENTS: the `agent` tool a session delegates with, the range
                   ;; a subagent thread is held to, and the row that tells it who it
                   ;; is. LAST, so the editing mode's subtraction is asked first and
                   ;; a name both policies refuse is refused in the editing mode's
                   ;; words -- it is the more specific statement about the session.
                   ;; The runner is passed in because running a conversation is this
                   ;; namespace's business, not a capability's.
                   (subagents/install! {:run run-subagent!})
                   ;; THE CONTENT PROJECTION (ADR 0008): a background pass that copies each session's
                   ;; NEW BYTES into the store, OFF THE WRITE PATH, and it is BACK ON (2026-09-29) after
                   ;; a day of being paused -- see `.scratch/memory-hygiene/` tickets 01 (why it was
                   ;; turned off) and 04 (what made it worth turning back on). What it cost then: a
                   ;; round walked EVERY session, and every conversation's every lookup opened its OWN
                   ;; sqlite connection (plus a migration pass) -- two per conversation per tick,
                   ;; 3,914 ms against a 2,000 ms interval, 46% of this process's CPU. What it costs
                   ;; now: ONE connection for the whole round (`db/with-connection`, the listing and the
                   ;; offsets in one query) and one `stat` per conversation -- a session whose file is
                   ;; the same one at the same length produces no query, no connection and no write at
                   ;; all. THAT IS THE DISCIPLINE DECISION 4 LEFT UNSTATED: the projection holds a
                   ;; connection for the length of a round and never a handle across ticks (`fsync!`
                   ;; and every write still go through `harness.infra.db`'s own doors).
                   ;;
                   ;; IT STILL HAS A TEARDOWN, unlike the writer: a process that stops serving stops
                   ;; copying, and the next one resumes at the offset it left (`projection_offsets`).
                   (projection/start!)]]
    ;; THE RECORD WRITER COMES UP WITH THE CAPABILITIES, because it is one: every
    ;; line this process produces goes through it (`harness.infra.stream`), and the
    ;; carry-back that must precede a session's first line is ITS step -- so the
    ;; edge hands its own carry-back over here, where a capability is told which
    ;; implementation it runs with. It has no teardown: one writer serves every
    ;; server this process starts, and a suite that starts a hundred must not
    ;; leave a hundred writer threads behind (or stop the one it has).
    (stream/prepare-with! carry-back!)
    ;; AND THE RECORD'S FIRST LINE (ticket 06): what a file's header says is the EDGE's to say --
    ;; the row vocabulary is this namespace's (`row-of`) and the conversation's identity is the
    ;; HOME's. The writer only knows WHEN one is due.
    (set-header!
     (fn [thread-id]
       {:format  record-format
        :thread  (str thread-id)
        :created (System/currentTimeMillis)}))
    (stream/start!)
    ;; THE SESSION TABLE IS LIVE FROM HERE, and it needs both of its outside facts.
    ;; THE PIN FIRST: a session whose bytes are not all on disk may not be put away, or
    ;; 'put away' would mean rebuilding from a record that is behind it -- silently,
    ;; which is the failure ADR 0002 decision 5 exists to refuse. The answer belongs to
    ;; the writer, so it is handed over rather than guessed (`stream/pending?`).
    (sessions/watch-unflushed! stream/pending?)
    ;; AND THE SWEEPER, because the table holds conversations now: without it, every
    ;; session this process has ever been asked about would be held until it exits.
    ;; AND THE SWEEPER, because the table holds conversations now: without it, every
    ;; session this process has ever been asked about would be held until it exits.
    (sessions/start!)
    ;; AND THE RUN STATE'S STARTUP CLEANUP, once, after the sweeper and before the
    ;; socket opens: no run of any session is alive in a process that has not started
    ;; one yet, so every `running` the column carries was left by a process that is
    ;; gone. The kernel's registry starts empty; the store now agrees with it.
    (sessions/clear-startup-run-state!)
    ;; THE SESSION'S OUTSIDE FACTS ARE INSTALLED HERE, not at some namespace's load: what a
    ;; and the meter's band is a consumer's fold and step (`harness.edge.pressure`). A process
    ;; that never starts a server registers neither.
    (sessions/install!)
    ;; ...AND THE TASK PANE'S DOORBELL, through the two seams the capabilities expose: a job
    ;; appearing or ending, and a delegation starting or ending, are the four moments the
    ;; right-hand pane's rows change -- and the pane polls for none of them any more (ticket
    ;; 01 of `.scratch/task-pane-push`, and `docs/rules/panel-data.md`).
    (jobs/set-change-hook! task-send!)
    (subagents/set-change-hook! task-send!)
    (pressure/install!)
    ;; AND THE CURRENT TURN'S TWO COUNTS (`harness.edge.turn`), which is what `turn/end` carries
    ;; (ADR 0006 decision 1). Read at the turn's end, reset at the start of the next one.
    (turn/install!)
    ;; AND THE TWO FOLDS THE COMPOSER'S STRIP READS (ticket 01 of `.scratch/turn-and-model-events`):
    ;; the numbers (`stats`) and the context ring (`context`), each registered on both of a
    ;; session's seams. With them on the session, `stats-get` answers a conversation this process
    ;; HOLDS without opening its record at all -- which is what the next `model/end` will need to
    ;; say 'this much so far' (that ticket's decision 4).
    (stats/install!)
    (context/install!)
    ;; AND THE TRAJECTORY'S LIVE STEP, the same shape as the meter's: the view is built ON
    ;; DEMAND (`harness.edge.trajectory/view-value`) and this step advances it (ticket 13).
    (trajectory/install!)
    ;; AND THE RECORD'S OWN VERDICT (`harness.edge.normalized`), on the same two seams: a session
    ;; born from a record gets the verdict of THOSE bytes (its birth walk feeds this fold), and every
    ;; row this process writes advances it -- so `sofar`'s live branch can answer whether the record
    ;; may be written to WITHOUT re-reading it on every poll (ticket 01/04 of
    ;; `.scratch/record-normalization`).
    (sessions/register-fold! :normalized normalized/fold)
    (sessions/register-step! :normalized (:step normalized/fold))
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
    ;; THE TWO FILES THAT WERE FILES ONCE. harness.edn and mcp.edn became sections of
    ;; config.edn (.scratch/config-merge), so a home that still has one is a home that has
    ;; not opened its configuration since: what it says is merged in (config.edn wins key
    ;; by key) and the file itself is moved to `<name>.bak`. Neither step throws -- a home
    ;; with an odd file still has to start -- so what happened is PRINTED here.
    (let [{:keys [migrated? skipped problems files]} (providers/migrate-legacy-config!)]
      (when (seq migrated?)
        (println (str "this home's " (str/join " + " migrated?) " moved into config.edn"
                      " (the old files are beside them as " (str/join ", " files) ")"
                      (when (seq skipped)
                        (str "; config.edn already had " (pr-str skipped)
                             ", so those keys stand as config.edn has them")))))
      (doseq [problem problems]
        (println (str "a legacy configuration file could not be migrated: " problem))))
    (when-let [moved (seq (project/migrate-legacy-project-config!))]
      (println (str (count moved) " project-level harness.edn/mcp.edn file(s) moved aside:"
                    " the project level is gone, so nothing reads them (see"
                    " .scratch/config-merge); what they said is in the .bak files: "
                    (str/join ", " moved))))
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
