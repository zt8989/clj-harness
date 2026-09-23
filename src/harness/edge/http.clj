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
            [harness.edge.pressure :as pressure]
            [harness.edge.compaction :as compaction]
            [harness.edge.stats :as stats]
            [harness.edge.trajectory :as trajectory]
            ;; The built page, when this process has one: `ui/dist`, served at the
            ;; root. See harness.edge.ui for why the server carries it at all.
            [harness.edge.ui :as ui]
            ;; skill-picker 的 /api/skills 用它（那一票在 main 上，本分支没有）：
            [harness.cap.skills :as skills]
            [harness.cap.system-prompt :as system-prompt]
            [harness.cap.subagents :as subagents]
            [harness.cap.frame-bus :as frame-bus]
            [harness.cap.frame-bus :as frame-bus]
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
  ;; IT DOES NOT SERIALIZE APPENDS ANY MORE. One line is one JSON object and a reader
  ;; parses the file line by line, so a half-written line is not a smaller record, it
  ;; is a broken file -- but that is the record writer's job now: one consumer thread
  ;; appends every line (`harness.edge.record`, ticket 02), so there is exactly one
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
  (spit f (str (json/write-str (merge {:ts (System/currentTimeMillis) :runId nil}
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
  (`harness.edge.record/prepare-with!`). Nothing to do answers nil.

  CALLED BY THE RECORD WRITER'S CONSUMER BEFORE EVERY LINE (`harness.edge.record`),
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

(defn- log!
  "The line goes to the record writer, which appends it off this thread's own
  path (`harness.edge.record`). NOTHING HERE TOUCHES THE FILE: the File is
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
  (`harness.edge.record/append!`), which is how a conversation's entries are numbered:
  the edge attaches it to the lines that CARRY entries -- each of an action's own
  `message` rows, and the terminal frame of its run -- and hands the number to
  `harness.edge.sessions/land!`."
  ([thread-id run-id kind payload]
   (log! thread-id run-id kind payload nil))
  ([thread-id run-id kind payload lands]
   (log! thread-id run-id kind payload lands nil))
  ([thread-id run-id kind payload lands extra]
   (let [line (str (json/write-str (merge {:ts (System/currentTimeMillis) :runId run-id}
                                          extra
                                          (row-of kind payload)))
                   "\n")]
     (locking log-lock
       ;; `mkdirs` and the carry-back are the writer's now: the consumer creates the
       ;; parent before every line, and runs the carry-back as its prepare step
       ;; (`record/prepare-with!`, installed in `start!`). Doing either here would be a
       ;; second place deciding when a file exists and what it already holds.
       (record/append! thread-id (log-file-for thread-id) line lands)))))

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
  (sessions/run-started! thread-id run-id))

(defn- unregister-run!
  "Forget THREAD-ID's run RUN-ID -- if that is the run this thread has registered.

  IDEMPOTENT ON PURPOSE: every ending calls it, and one run can reach two of them on
  the way out (a terminal frame, and then a channel that closes; a throw after the
  terminal was dispatched). The second call is a no-op, so 'exactly once' is a
  property of the shape rather than something each call site has to arrange."
  [thread-id run-id]
  (sessions/run-finished! thread-id run-id))

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

(defn- log-messages!
  "One \"message\" line per provider-shaped message, VERBATIM -- the row's payload is the
  message itself and its envelope carries the `:source` that says who put it in the array
  (`entry-source` / `returned-source`). The submitted side of a run (what the pre-LLM step
  derived for it) and the returned side of a run (what the kernel added) both come through
  here."
  [thread-id run-id msgs]
  (doseq [m msgs]
    (log! thread-id run-id "message" m nil {:source (returned-source m)})))

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
      ;; THE TERMINAL FRAME'S LINE IS WHERE THIS RUN'S ENTRIES LAND: `settle!` folds the
      ;; run's messages into the conversation at this same moment, and the record's
      ;; offset of this line is the number they are given (`sessions/land!`). The line is
      ;; logged before `settle!` runs, so the number is already on its way back when the
      ;; entries appear -- and `land!` is idempotent and by group, so either order works.
      (log! thread-id run-id "event" frame
            (when (contains? terminal (:type frame))
              (fn [offset] (sessions/land! thread-id run-id offset))))
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
          ;; next (harness.edge.sessions/running? -- the fact the sidebar row reads).
          (unregister-run! thread-id run-id)
          ;; AND THE CONVERSATION IS NOW WHAT IT SAYS. Before the frame is sent, so that
          ;; the client that reads this terminal and immediately sends its next action
          ;; finds the answer already in the history -- the window between 'the run
          ;; ended' and 'its words are in the conversation' is one a fast client would
          ;; otherwise be racing this process through.
          (sessions/settle! thread-id run-id (:frames @state))
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
;; BEFORE it derives the request (ticket 04).
(declare compact-if-pressured! recover-overflow!)
(defn- run-agent!
  "Drive ONE run: log its entries, set the conversation up, and stream what comes back.

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
        ;; AND ONE WRITER THAT DOES NOT SEND: the frames of a stop's cut-off answers are the
        ;; record's and the session's, never the client's (see `recorder`).
        record! (recorder thread-id run-id state)
        convert (ag/outbound thread-id run-id)]
    ;; THE BIRTH -- reading the session's opening, appending this action's own entries,
    ;; writing their rows and naming the session -- HAPPENS INSIDE THE GO BLOCK
    ;; BELOW, on purpose. Reading the instruction files can fail (an unreadable
    ;; AGENTS.md), and a failure on the way in has to leave the client a TERMINATED RUN
    ;; rather than a stream that never says anything: the go block's `try` is where that
    ;; is turned into a RUN_ERROR frame, so the birth is on its side of the parens.
    ;; `input`, `thread-id`, `run-id` and the two stateful closures above are all it
    ;; needs.
    (async/go
      ;; A GO BLOCK'S EXCEPTION GOES NOWHERE: core.async throws it into the block's
      ;; own channel, which nobody reads -- so a consumer that dies takes the run
      ;; down in silence. The kernel's producer blocks on its next put, the client
      ;; waits for frames that will never come, and the record stops mid-sentence
      ;; with nothing anywhere saying why. That is the one failure in this file
      ;; that cannot be allowed to be quiet, so the whole run body is wrapped.
      (try
        (binding [hook/*sink* (sink-for thread-id run-id)]
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
          (compact-if-pressured! thread-id)
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
            ;; ONE ROW PER ENTRY, AND THE LINE THAT CARRIES IT IS THE LINE THAT NUMBERS
            ;; IT (`.scratch/jsonl-two-kinds` 票 02): the action's entries are `message`
            ;; rows now -- each with ITS OWN identity (the envelope's `:id`, the name the
            ;; session and the fold dedupe by) and its own number (`land-at!` is handed the
            ;; offset of the very line it wrote, so a window's `beforeSeq` cuts where the
            ;; record does). The `input` row that used to carry them all is gone; what it
            ;; also carried (the whole inbound vector on every run) was the second copy
            ;; this ticket deletes.
            ;;
            ;; WHOSE ELEMENT OF THE ARRAY IT WAS IS THE ENVELOPE'S `:source` (owner,
            ;; 2026-09-21: a `message` row IS an element of the messages array the model
            ;; was handed, so the row has to say who put it there). The payload stays the
            ;; VERBATIM provider message -- `model-view` is asked for it here because the
            ;; record keeps what the MODEL read, and the card part an opening entry carries
            ;; is the screen's, not the provider's (`ag/provider-part` refuses it by name).
            ;;
            ;; THE ROWS ARE WRITTEN IN THE ORDER THE ENTRIES WENT IN, which is the order
            ;; the array was read in -- the same order `land-at!` matches an unnamed entry
            ;; by.
            ;; A ROW'S PAYLOAD IS THE MESSAGE THE PROVIDER READS, which is NOT the client's
            ;; own bytes when a part has to be translated (`ag/provider-messages`: the cards
            ;; go, an AG-UI `image` becomes the vendor's `image_url`) -- and the ENTRY'S NAME
            ;; rides the envelope rather than the payload, because a provider message has no
            ;; such field and the fold dedupes by the envelope's `:id` (票 02).
            ;;
            ;; AN ENTRY THAT TRANSLATES TO NOTHING WRITES NO ROW: a lone `reasoning` message
            ;; is folded into the assistant it precedes, and a row for it would claim the
            ;; model was handed something it never saw.
            (doseq [[i m] (map-indexed vector added)
                    :let [shown (first (ag/provider-messages (sessions/model-view [m])))]
                    :when (some? shown)]
              (log! thread-id run-id "message" shown
                    (fn [offset] (sessions/land-at! thread-id run-id (or (:id m) i) offset))
                    (cond-> {:source (entry-source m)}
                      (:id m) (assoc :id (:id m)))))
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
            (let [[provider messages decisions resolved injected]
                (try (let [;; THE PROVIDER IS THE SESSION'S, NOT THE REQUEST'S. It used to
                           ;; be layered with whatever `:provider` the run body carried,
                           ;; which made the selection a thing a CLIENT said per request --
                           ;; and that is the shape ticket 03 retired: choosing a model is
                           ;; an action (`POST /api/model`, which is where the session's
                           ;; override is written and where the change is recorded), and a
                           ;; run is served by whatever that action left in force. `input`
                           ;; is not consulted here at all, which is the point.
                           provider (providers/current-provider thread-id)]
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
                             assembled (ag/inbound (sessions/model-view (into history added))
                                                   (system-prompt/assemble thread-id)
                                                   nil)
                             applied   (project/before-llm assembled thread-id)
                             injected  (subvec applied (count assembled))]
                         [provider
                          (llm/thinking-mode-history applied provider)
                          (resume-decisions (:resume input))
                          ;; THE SESSION'S OWN TIER, with no request layered on it -- the
                          ;; same answer `provider` above resolved, and the map the
                          ;; provider/init and provider/changed lines are written from.
                          (providers/resolve-provider thread-id)
                          injected]))
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
              ;; THE SYSTEM MESSAGE IS A `message` ROW, because a `message` row IS an element
              ;; of the array the model read (owner, 2026-09-21: "所谓 message 就是送给大模型
              ;; 那些 message 数组的超集"). It goes first, which is where it sat in that array,
              ;; and the ENVELOPE says who put it there (`:source "system-prompt"`) plus the
              ;; SHA-256 of those bytes (`:hash`) -- so 'was this the same prompt as last run'
              ;; is answerable without diffing four kilobytes, which is what the provider's
              ;; prefill (prompt cache) rests on. The payload stays the verbatim provider
              ;; message, as every message row's does.
              ;;
              ;; WRITTEN PER RUN, NOT ONCE: the assembled text is recomputed every run (the
              ;; binding moves, a hook is switched), so each run's row is what THAT run was
              ;; handed -- a reader asks the row, not a carry-forward.
              (let [prompt (or (some #(when (= "system" (:role %)) (:content %)) messages) "")]
                (log! thread-id run-id "message" {:role "system" :content prompt} nil
                      {:source "system-prompt" :hash (system-prompt/digest prompt)}))
              ;; WHAT THIS RUN DERIVED FOR ITSELF, as message rows (票 02). These are the
              ;; injections folded in beside the conversation -- a body an earlier turn
              ;; loaded, a job that ended between two runs: ordinary user messages to the
              ;; provider, parts of the array this run was handed, and NOT entries of the
              ;; conversation (the client gets them as cards, and the next run re-derives
              ;; them rather than reading them back). The rest of the submitted array was
              ;; written by the runs that produced it -- which is the whole saving of this
              ;; ticket: a run logs what IT put in, never the conversation again.
              (log-messages! thread-id run-id injected)
              ;; HOW FULL THE REQUEST THAT IS ABOUT TO GO OUT IS, ON THE RECORD, BEFORE
              ;; it goes -- the reading a compaction trigger (harness.edge.pressure) starts
              ;; from. MESSAGES is handed in rather than read back because this run's own
              ;; lines are still with the writer; the anchor comes from the file, where the
              ;; previous call has long landed.
              (log! thread-id run-id "context/pressure"
                    (pressure/log-pressure (log-file-for thread-id) messages
                                          (:context-window provider)))
              ;; Drain run-chan and convert each kernel event to AG-UI frames. The
              ;; stream closes via :run/end's RUN_FINISHED (or RUN_ERROR), or via
              ;; :run/interrupt's RUN_FINISHED carrying outcome.interrupts; the
              ;; :run/done history itself is never converted -- it is the returned
              ;; side of the message record instead.
              (let [events (loop/run-chan provider messages {:thread-id thread-id
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
                                                                            (recover-overflow! thread-id provider history t))
                                                             :overflow-retries (compaction/overflow-retries thread-id)
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
                      (log-messages! thread-id run-id (:added ev))
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
                                                (cond-> (vec (map-indexed
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
                  (log/warn! :run/events-closed-without-terminal
                             {:thread-id thread-id :run-id run-id
                              :last      (:last @state)})))))))
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
        (sessions/settle! thread-id run-id (:frames @state))
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
    ;; -- everything it does, the birth included, happens on the go block's thread, so it
    ;; does not block the worker that :on-open runs on.
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
        frames   (atom [])]
    (binding [hook/*sink* (sink-for thread-id run-id)]
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
                assembled (ag/inbound (sessions/model-view (sessions/messages thread-id))
                                      (system-prompt/assemble thread-id)
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
                    {:source "system-prompt" :hash (system-prompt/digest prompt)}))
            (log-messages! thread-id run-id injected)
            (log! thread-id run-id "provider/init" (provider-line provider :inherited))
            (let [events (loop/run-chan provider messages {:thread-id  thread-id
                                                           :resume     []
                                                           :before-llm project/before-llm})]
              (loop []
                (if-let [ev (async/<!! events)]
                  (if (= :run/done (:type ev))
                    (do
                      ;; THE RETURNED SIDE, AS THE KERNEL NAMES IT (`:added`), exactly
                      ;; as the agent route reads it -- 'past the count of what we
                      ;; handed in' would file an entry of the conversation as this
                      ;; run's own. And the answer is the last thing the subagent
                      ;; actually SAID rather than the last frame on the wire.
                      (log-messages! thread-id run-id (:added ev))
                      {:answer (answer-of (:history ev))})
                    (do
                      ;; Tool-lifecycle and model-call events are audit lines rather
                      ;; than wire frames, keyed by toolCallId.
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
                          (log! thread-id run-id "event" f
                                (when (contains? terminal (:type frame))
                                  (fn [offset] (sessions/land! thread-id run-id offset))))
                          ;; AND THE SAME FRAME GOES ON THE BUS: the record is not where
                          ;; a panel watches from -- it is where a panel catches up.
                          (frame-bus/publish! thread-id f)
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
          (sessions/settle! thread-id run-id @frames))))))

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
                                  _     (when (not= from-dir to-dir) (record/flush! 5000))
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

  `:running` IS THE ONE FIELD NOT IN THE STORE, and it is not an oversight: whether a
  run is alive right now is a question about THIS PROCESS, and a file cannot answer it
  -- a log that stops without a terminal frame belongs equally to a run still going and
  to a process that was killed. It is asked of the run set the server keeps with the
  session (`harness.edge.sessions`' `running?`, ADR 0002's authority) rather than of
  disk, and read per request because the answer moves.

  A TASK AND A PROJECT'S SESSION ARE THE SAME ROW, which is why there is no
  `task-row` any more: the two differed only in where their disk facts were asked
  from (a project's workspace vs a walk of the whole tree), and with those gone the
  store answers both the same way. What differs between them is the project, and
  that is the caller's structure rather than the row's.

  nil `:lastSentAt` is a conversation nothing has been sent to -- registered and never
  used, or a log deleted by hand. The client draws that in words rather than
  inventing a time."
  [{:keys [id archived? title last-sent-at]}]
  {:threadId     id
   :archived     (boolean archived?)
   :running      (running? id)
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

(defn- projects-get
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
  [_req]
  (let [by-project (group-by :project-id (remove :subagent (project/sessions)))
        tasks      (project/tasks)]
    (api-response 200
                  {:projects (mapv (fn [{:keys [id canonical-path]}]
                                     {:projectId id
                                      :path      canonical-path
                                      :sessions  (newest-first
                                                  (mapv session-row (get by-project id)))})
                                   (project/projects))
                   :tasks    (newest-first (mapv session-row tasks))})))

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

  SEVEN OF THE TEN ARE GETS: `stats`, `trajectory` and `delegations` only READ the log (a folded
  view of a finished conversation, and the per-turn timeline), `sofar` reads the
  same file while it is still being written, and the window's two verbs (`feed`,
  `page`) read it in pieces. The set stays closed and the 405 stays here -- what
  changed is that the sentence 'every verb on this shape is a POST' is no longer
  true, not where the refusal happens.

  `sofar` NAMES AN INTENT AND NOT A RESOURCE, which is why it is not `history`:
  what it answers is not the conversation (a thing a client could take over) but
  how much of it there IS at this moment -- the same log may answer differently a
  second later, and the answer says so (`replay/sofar`'s `:state`). Rebuild remains
  the door for 'give me the conversation, I will own it'.

  AND TWO VERBS READS A CONVERSATION IN PAGES rather than whole (`feed` and `page`,
  ticket 05 of `.scratch/sessions-live-on-the-server`): the window ADR 0003
  describes, for a conversation too long to send. They are GETs and they are the
  first pair here that a page uses continuously rather than once -- `page` answers
  scrolling up, and `feed` stays open -- which is why they are the two routes on
  this edge that are not request/response (`feed` streams; see `stream-feed!`).

  AND `cancel` IS THE ONE THAT STOPS SOMETHING rather than reading it as it is: a POST
  aimed at one conversation, whose run -- if this process has one going -- is told to
  stop (`.scratch/session-after-refresh` tickets 07/08). It is the server's half of
  the composer's Stop, and the half the browser's own abort never had: pressing that
  one only closed the stream, while the run kept going and the record kept growing.
  A conversation with NO run going here is refused BY NAME rather than answered
  quietly -- 'it is already over' and 'it was stopped' are different things to know."
  #{"rebuild" "compact" "archive" "stats" "trajectory" "sofar" "feed" "page" "delegations" "follow" "cancel"})

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
  assistant message added / run over) is what asks, and asking once asks both.

  IT KEEPS READING THE RECORD, and that is ticket 05's decision rather than an
  oversight: these are facts ABOUT THE RECORD -- which model a call went to, what the
  vendor reported, where the gaps are -- and a session's memory holds messages, not
  accounting. What memory CAN say is how far behind the record is, and `:behind` is
  that: the number of lines the writer has not put on disk yet. ABSENT MEANS NOTHING
  IS PENDING (`record-health` draws the same line and for the same reason -- a
  `:behind 0` would be a field nobody reads). A count that is there is a warning that
  the numbers below it are that many record lines short of the conversation."
  [stem]
  (let [located (try {:ok (replay/locate (home/projects-dir) stem)}
                     (catch Throwable t {:error (ex-message t)}))
        folded  (when (nil? (:error located))
                  (try (let [records (stats/read-records (:ok located))]
                         {:ok (assoc (stats/records->stats records)
                                     :context  (context/records->context records)
                                     :pressure (pressure/records->pressure records))})
                       (catch Throwable t {:error (ex-message t)})))]
    (cond
      (some? (:error located))
      (api-response 404 {:error (:error located) :threadId stem})

      (some? (:error folded))
      (api-response 400 {:error (:error folded) :threadId stem})

      :else
      (let [behind (record/pending-count stem)]
        (api-response 200 (cond-> (assoc (:ok folded) :threadId stem)
                            (pos? behind) (assoc :behind behind)))))))

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
  session while it runs is the ordinary case rather than an error.

  AND IT CARRIES THE SAME `:behind` AS `stats`, for the same reason: this is the
  RECORD's trajectory, and the record can be behind the conversation being written to
  it. Absent means nothing is pending."
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
      (let [behind (record/pending-count stem)]
        (api-response 200 (cond-> (assoc (:ok folded) :threadId stem)
                            (pos? behind) (assoc :behind behind)))))))

(defn- follow-get
  "GET /api/threads/<stem>/follow -- an SSE, READ-ONLY channel: the frames of
  ONE thread as they land, for a panel that watches a delegation work. It is
  NOT a run and must never be mistaken for one: it is a GET, it answers no
  RunAgentInput, accepts no input at all -- there is nothing here to run --
  and POST /api/agent is untouched: the parent's stream is still only its own
  run's, because frames for a child mixed into that stream would be read as
  the parent's messages (app.tsx keeps one core per host precisely so streams
  cannot cross). Follow is the OTHER stream, read-only, and so father and
  child never share a wire.

  WHAT IT CAN FOLLOW IS A DELEGATION, which is not a narrowing of the route's
  name but the truth about the bus: a subagent's run is what publishes (see
  `run-subagent!`), because anybody else's frames already have a reader -- the
  client that started that run, on its own stream. A thread with no delegation
  in flight is answered from its record and ENDED rather than left open: a
  stream that never produces and never ends reads as 'still running' to a
  client, which is the one lie a follow channel must not tell.

  THE SHAPE OF A CONNECTION, in order:

    1. subscribe FIRST (frame-bus), THEN replay. The other order loses the
       frames that land between replay's read and the subscribe -- replay
       cannot see a frame that has not been written yet, and the bus drops
       what nobody has asked for. Subscribing first leaves the race exactly
       one shape: the boundary, where a frame arrives from BOTH sources;
    2. replay what the RECORD already holds -- the stats-style reader, the
       half-tolerant one, because a child that is running right now is the
       case this route exists for and its last row may be half-written;
       every wire frame becomes a frame on the channel, and the highest
       `:seq` among them is remembered;
    3. frames from the bus whose `:seq` is <= that number are dropped -- THE
       EXPLICIT ANSWER to the boundary, which is why the publisher stamps the
       number into the record as well as onto the bus (`run-subagent!`): the
       comparison only means anything if both sides count the same thing;
    4. a TERMINAL frame (RUN_FINISHED / RUN_ERROR) ends the stream, wherever
       it came from: a replayed one means the run is already over, a live one
       means it just ended. NOTHING FOLLOWS IT -- the terminal IS the ending
       the client's vocabulary has;
    5. THE STREAM OPENS WITH THE RUN, AND THE SNAPSHOT RIDES INSIDE IT. The
       first thing on the wire is a `RUN_STARTED` and the second is one AG-UI
       `MESSAGES_SNAPSHOT` -- WHAT THE FRAMES CANNOT REBUILD: the conversation
       of the record MINUS the messages the replayed frames carry under the
       same id. That is the task the subagent was handed, a `message` row no
       frame ever mentions (frames only carry what a run RETURNED), and it is
       the reason a panel needs no second read to show what was asked. The
       snapshot is the same frame the run edge sends for the entries a client
       never sent (`ag/conversation-snapshot`), and it is sent only when there
       is something in it.

       THAT ORDER IS NOT A PREFERENCE, and a browser walkthrough is what
       settled it: `@ag-ui/client` refuses a stream whose FIRST event is not
       `RUN_STARTED` (its verifier's own words: \"First event must be
       'RUN_STARTED'\"), and a snapshot sent ahead of the run failed the panel
       with that sentence while the wire looked perfectly reasonable. So the
       record's own opening frame goes out first -- a real record's first
       frame IS the child's `RUN_STARTED`, because `run-subagent!` writes it
       before anything else -- and the snapshot follows it. A record that does
       NOT open with one (a hand-written log, a repair) gets a synthesized
       `RUN_STARTED` carrying the child's thread and run, because a channel a
       runtime consumes has to be well-formed whatever the file holds;
    6. A THREAD WITH NOBODY RUNNING AND NO TERMINAL IN ITS RECORD says so in
       ONE SSE COMMENT and closes. A COMMENT, not a frame, and that is the
       one shape this channel has to get right: a frame the client's parser
       does not know is an ERROR there (`@ag-ui/client` validates every event
       and fails the run on an unknown type), so a channel consumed by a
       runtime can only ever carry frames that runtime's vocabulary has. The
       sentence is for whoever is reading the wire (a person with `curl`, a
       log), and the CLOSE is what the client acts on;
    7. the client going away unsubscribes (the bus's stop!), which is where
       the publisher-must-never-block guarantee is cashed out on this side.

  READ-ONLY, like stats: the record is read, the bus is read, nothing is
  written -- not the log, not the store, not ~/.clj-harness."
  [req stem]
  (let [located (try {:ok (replay/locate (home/projects-dir) stem)}
                     (catch Throwable t {:error (ex-message t)}))]
    (if (some? (:error located))
      ;; An unknown stem is the management edge's ordinary JSON 404 -- there is
      ;; nothing to follow, and a stream that opens just to say so would be a
      ;; stream pretending a thread exists.
      (api-response 404 {:error (:error located) :threadId stem})
      (let [;; THE TEARDOWN LIVES OUT HERE, and that is a fact about `as-channel`
            ;; rather than about the bus: `on-open` and `on-close` are two callbacks
            ;; with nothing in scope between them, and only the first one can TAKE the
            ;; subscription while only the second one must END it. `open?` makes the
            ;; ending idempotent, which both the terminal frame and the client's
            ;; departure reach.
            open?    (atom true)
            teardown (atom nil)
            end!     (fn end! []
                       (when (compare-and-set! open? true false)
                         (when-some [stop! @teardown] (stop!))))]
        (hk/as-channel req
         {:on-open
          (fn [ch]
            ;; 1. SUBSCRIBE FIRST, 2. REPLAY SECOND.
            (let [{sub :ch stop! :stop!} (frame-bus/subscribe! stem)
                  last-seq (atom nil)
                  ;; The record, read the TOLERANT way (`stats/read-records`): a child
                  ;; whose last row is being written right now is the case this route
                  ;; is FOR, and a half row is 'we read this far', not damage.
                  records  (try (stats/read-records (:ok located)) (catch Throwable _ []))
                  frames   (->> records (filter replay/frame?) (mapv replay/payload))
                  over?    (boolean (some #(contains? terminal (:type %)) frames))
                  live?    (some? (subagents/live-subagent stem))
                  ;; The HEAD rides the first send, like the runner's.
                  head     (fn [body]
                             {:status 200
                              :headers (merge (cors-headers (request-origin req))
                                              {"Content-Type" "text/event-stream"
                                               "Cache-Control" "no-cache"})
                              :body body})
                  bytes    (fn [frame] (str "data: " (json/write-str frame) "\n\n"))
                  ;; THE RUN'S OPENING, AND THEN WHAT THE FRAMES CANNOT REBUILD (5.): the
                  ;; record's conversation minus the messages the frames carry under the
                  ;; same id. See the docstring's point 5 for why the snapshot cannot go
                  ;; first -- `@ag-ui/client` refuses a stream that does not open with
                  ;; `RUN_STARTED`, which is what a real browser said.
                  ;; The comparison is by MESSAGE ID because the fold that builds these
                  ;; messages names each one after the frame that produced it
                  ;; (`kernel.frames/apply-frames`), which is the same id the wire uses
                  ;; -- so 'which messages would be drawn twice' is a question with an
                  ;; exact answer rather than a guess about ordering.
                  snapshot (let [streamed (into #{} (keep :messageId) frames)
                                 opening  (remove #(contains? streamed (:id %))
                                                  (try (replay/messages-so-far records)
                                                       (catch Throwable _ [])))]
                             (when (seq opening)
                               (ag/conversation-snapshot opening)))
                  ;; THE REPLAY IS WHAT THE RECORD ALREADY SAYS, and the `:seq` comes
                  ;; off it on the way out: the number is the bus's bookkeeping for the
                  ;; boundary, not a field of any AG-UI frame (票 02 writes it into the
                  ;; record so the two sides count the same thing, and this is the one
                  ;; place that must not pass it on).
                  ;;
                  ;; A REAL RECORD'S FIRST FRAME IS THE RUN'S `RUN_STARTED` -- written by
                  ;; `run-subagent!` before the child has said anything -- so the ordinary
                  ;; case is that frame going out verbatim. The synthesized one is for a
                  ;; record that does not open with it (a hand-written log, a repaired
                  ;; one): the child's own thread and run id are the truth about which run
                  ;; this is, and a stream a runtime consumes must not start in the middle.
                  opening  (when (= "RUN_STARTED" (:type (first frames))) (first frames))
                  started  (or opening
                               {:type "RUN_STARTED"
                                :threadId stem
                                :runId (or (some-> (first records) :runId)
                                           (str (java.util.UUID/randomUUID)))})
                  replay   (str (bytes (dissoc started :seq))
                                (when snapshot (bytes snapshot))
                                (apply str (map #(bytes (dissoc % :seq))
                                                (if opening (rest frames) frames))))
                  ;; 6. THE ENDING FOR A THREAD NOBODY IS RUNNING: the replay, one
                  ;; comment, and the close. A comment because the client's parser must
                  ;; never be handed a frame it does not know (see the docstring), and a
                  ;; SENTENCE because 'why is this stream over' is what a reader of the
                  ;; wire is about to ask. This is only ever reached BEFORE the head has
                  ;; gone out -- the live path opens its own send below.
                  ended!   (fn [why]
                             (end!)
                             (hk/send! ch (head (str "retry: 2000\n\n" replay
                                                     ": " why "\n\n"))
                                       true))]
              (reset! teardown stop!)
              (reset! last-seq (when-let [ss (seq (keep :seq frames))] (reduce max ss)))
              (if (or over? (not live?))
                ;; NOTHING IS RUNNING THIS, AND THE RECORD IS WHAT THERE IS: a run that
                ;; already reached its terminal frame (the replay carries the terminal,
                ;; which is the client's ending), or a thread no delegation is in
                ;; flight for. THE REASON IS SAID RATHER THAN IMPLIED -- a reader can
                ;; tell 'the run finished' from 'nothing here was ever live' without
                ;; guessing -- and the channel ENDS, because a stream that never
                ;; produces and never ends reads as 'still running' to a client, which
                ;; is the one lie this route must not tell.
                (if over?
                  ;; THE REPLAY CARRIES THE TERMINAL, and that is the client's ending:
                  ;; nothing is said behind it but the close.
                  (do (end!) (hk/send! ch (head (str "retry: 2000\n\n" replay)) true))
                  (ended! (str stem " is not running, and its record has no terminal frame")))
                (do
                  (hk/send! ch (head (str "retry: 2000\n\n" replay)) false)
                  ;; 3. THE BUS SIDE, on this thread's go loop: the channel is a
                  ;; sliding buffer, so reads never block the publisher.
                  (async/go
                    (loop []
                      (if-some [frame (async/<! sub)]
                        (cond
                          ;; THE END MARKER: the subscription was stopped from
                          ;; underneath us -- by the client's own on-close -- so this
                          ;; loop is over either way.
                          (= frame-bus/closed-marker frame)
                          (do (hk/close ch) nil)

                          ;; THE DEDUPE: anything the replay already delivered is
                          ;; dropped, silently and BY NUMBER rather than by comparing
                          ;; shapes -- a stream of text deltas has no identity to
                          ;; compare. This is the boundary the ticket demands an
                          ;; answer for, and the answer is the publisher's number.
                          (and (some? @last-seq)
                               (:seq frame)
                               (<= (:seq frame) @last-seq))
                          (recur)

                          :else
                          (do (when (:seq frame) (reset! last-seq (:seq frame)))
                              (hk/send! ch (bytes (dissoc frame :seq)) false)
                              ;; 4. THE TERMINAL ENDS IT: the client has its ending
                              ;; (RUN_FINISHED / RUN_ERROR is the whole of it), so the
                              ;; channel is unsubscribed and closed behind it.
                              (if (contains? terminal (:type frame))
                                (do (end!) (hk/close ch) nil)
                                (recur))))
                        (do (hk/close ch) nil))))))))
          :on-close
          (fn [_ch _status]
            ;; 6. THE CLIENT WENT; THE SUBSCRIPTION GOES TOO -- otherwise the bus
            ;; would go on filling a channel nobody reads (sliding, so the publisher
            ;; still would not block -- but the frames would be lost to nobody, which
            ;; is waste, not correctness).
            (end!))})))))


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
          st       (sessions/state stem)]
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
                                   :state    (name st)}
                            (seq (:interrupts live)) (assoc :interrupts (:interrupts live))
                            (some? health)           (assoc :record health)))))
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

(defn- feed-bytes
  "One feed frame, encoded the way the run edge encodes its frames: `data: <json>` and a
  blank line. The same spelling on purpose -- a client that reads runs already knows how
  to read this, and there is one SSE dialect on this edge rather than two."
  [frame]
  (.getBytes (str "data: " (json/write-str frame) "\n\n") StandardCharsets/UTF_8))

(defn- feed-head
  "A feed frame as the INITIAL RESPONSE of the stream: the status and headers ride on
  the first frame, which is http-kit's contract for a streaming answer (the same
  spelling `runner` uses for a run).

  AND `send!` MUST BE TOLD NOT TO CLOSE. Without the third argument http-kit treats a
  send as the WHOLE response -- it computes a `content-length`, finishes it, and every
  later frame is dropped -- which is what a feed did here until it was measured: the
  window arrived, the socket stayed open, and nothing was ever pushed again. A stream
  is the pair (head map, `false`), and `stream-feed!`'s later sends are `false` too,
  with `true` only on the frame that ends it (`runner` has the same three cases)."
  [origin bytes]
  {:status  200
   :headers (merge (cors-headers origin) {"Content-Type"  "text/event-stream"
                                          "Cache-Control" "no-cache"})
   :body    bytes})

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

(defn- read-entries
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

(defn- window-page
  "A LIVE conversation's window: {:entries [..] :baseSeq N :hasMore bool} for the tail page
  (`since` nil) or for what arrived after `since`.

  THE SAME CHOICE `read-entries` MAKES, AT THE STREAMING DOOR: the record while a run of it
  is in flight here, else the session's own entries (`sessions/tail` / `sessions/since`).
  Re-reading on every ring is the point -- the delta has to be computed against the file as
  it is NOW, which is what makes a reader's own cursor sufficient (ADR 0003 decision 7).

  A RECORD THAT CANNOT BE READ FALLS BACK TO MEMORY. This is a stream that has already
  begun, and refusing the whole window mid-flight would leave the reader holding half of it
  with nothing to say about which half; the PAGE route is where a broken record is reported
  BY NAME (`read-entries`)."
  [stem since]
  (let [from-record (when (running? stem) (:ok (record-entries stem true)))]
    (cond
      (nil? from-record)
      (if (nil? since)
        (sessions/tail stem)
        {:entries (sessions/since stem since) :baseSeq since :hasMore false})

      (nil? since) (sessions/tail-of from-record)

      :else {:entries (sessions/since-of from-record since) :baseSeq since :hasMore false})))

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

(defn- stream-feed!
  "Serve a feed that has already been let through: the opening frame, then a frame per
  landing, then one `end` frame when the window is over.

  THE DOORBELL IS A ONE-SLOT CHANNEL (`harness.edge.sessions/watch!`), which is what
  makes this loop free of both polling and lost wakeups: a ring that arrives while the
  answer is being written is still in the slot when the loop next waits, and a dozen
  rings that arrive while it is writing collapse into one wait -- because the loop does
  not count events, it re-reads the delta from its own cursor. That is the whole reason
  the watcher carries no cursor of its own (ADR 0003 decision 7).

  THE CURSOR ONLY EVER MOVES FORWARD, and it moves to the last entry's record offset.
  Entries still in the writer's queue carry no offset, so a frame can be followed by
  another containing the same entries -- by the time it is written their line has landed
  and the delta from the old cursor reaches them again. The reader drops the repeat by
  message id, which is the same identity the session dedupes an action by; the
  alternative, moving the cursor to a number the writer has not confirmed, is how a
  reader ends up asking for a delta from an offset nothing is numbered at."
  [req stem since generation]
  (let [origin (request-origin req)
        wake   (async/chan (async/sliding-buffer 1))
        why    (atom nil)
        f      (fn [_tid event]
                 (when (= :gone (:kind event)) (reset! why (:reason event)))
                 (async/offer! wake :ring))]
    (sessions/watch! stem f)
    (hk/as-channel req
                   {:on-open
                    (fn [ch]
                      (async/go
                        (try
                          ;; THE OPENING FRAME COMES FIRST, before any wait: a client
                          ;; that has news waiting for it must not wait for a doorbell
                          ;; that has already rung.
                          (let [page  (window-page stem since)
                                frame (window-frame stem
                                                    (if (nil? since) "window" "append")
                                                    (live-state stem)
                                                    page)]
                            (hk/send! ch (feed-head origin (feed-bytes frame)) false)
                            (loop [cursor (:cursor frame) state-0 (:state frame)]
                              (async/<! wake)
                              (if (nil? (sessions/live-entry stem))
                                ;; THE WINDOW IS OVER, AND IT SAYS SO: the session was put
                                ;; away, swept, or taken over. A stream that merely stopped
                                ;; would leave the reader believing it holds everything.
                                ;; `true`: this frame ends the response, which is the
                                ;; one thing a feed is ever allowed to do on its own.
                                (hk/send! ch (feed-bytes
                                              {:type       "end"
                                               :reason     (or @why "the session is gone")
                                               :generation generation})
                                          true)
                                (let [delta (:entries (window-page stem cursor))
                                      ;; THE STATE IS READ BEFORE THE FRAME IS BUILT, and
                                      ;; it is worth a frame of its own: a run that settled
                                      ;; without adding an entry (nothing was said) still
                                      ;; changes what the reader should draw, and a frame
                                      ;; sent only for entries would leave the turn on
                                      ;; screen looking unfinished forever.
                                      state (live-state stem)
                                      frame (window-frame stem "append" state
                                                          {:entries delta
                                                           :baseSeq cursor
                                                           :hasMore false})]
                                  ;; A FRAME HAS TO CARRY NEWS, AND `nil` STATE IS NOT NEWS.
                                  ;; Ending a run is two steps -- `run-finished!` unpins it,
                                  ;; `settle!` folds the frames and writes the state -- and
                                  ;; between them the conversation is neither running nor
                                  ;; anything else yet, so `live-state` answers nil. Telling a
                                  ;; reader "never run" for those milliseconds would be a lie,
                                  ;; and telling it nothing is exactly right: the fold rings on
                                  ;; its own, and the frame that follows says `unfinished` or
                                  ;; `settled`, which is the truth. (The suite caught this as a
                                  ;; flake: the state-only frame was the FIRST of the two, and
                                  ;; it said nothing.)
                                  (when (or (seq delta)
                                            (and (some? state) (not= state state-0)))
                                    (hk/send! ch (feed-bytes frame) false))
                                  (recur (or (:cursor frame) cursor) state)))))
                          (finally
                            (sessions/unwatch! stem f)))))
                    ;; ONE LINE PER FEED END, SAYING WHAT http-kit THINKS HAPPENED, for
                    ;; the same reason the run route says it: a stream that stops is
                    ;; otherwise indistinguishable from a stream nobody was sending on.
                    :on-close (fn [_ch status]
                                (log/info! :feed/stream-closed
                                           {:thread-id stem :status status
                                            :generation generation})
                                (sessions/unwatch! stem f))})))

(defn- feed-get
  "GET /api/threads/<stem>/feed[?since=N&generation=G] -- the conversation as a LIVE
  window: the tail page (or the delta after N), then every entry that lands afterwards,
  until the window is over.

  CONNECTING IS AN ACT ON THE SESSION, so this route births it (a page opening a
  conversation is the first ask, ADR 0002 decision 5) and rings the claim: pushing
  changes means holding them. A conversation another live process is serving is refused
  with the 409 that names it (`refuse-served-elsewhere!`) rather than half-served.

  TWO REFUSALS BEFORE ANY BYTE IS STREAMED, and both are the same mistake: the client is
  holding numbers from a window that no longer exists.

    a generation that is not this window's   the session was put away, taken over, or
                                             rebuilt, and every number the client has
                                             is about a conversation that is gone
                                             (ADR 0003 decision 6)
    a `since` older than the tail page       the client is further behind than a page,
                                             so the 'delta' would be the whole
                                             conversation -- which is the cost the
                                             window exists to refuse

  Both answer 409 with the CURRENT generation and baseSeq, so the client's move is the
  same in both cases and it is a move it can make: drop what it holds and open the tail.

  ONCE STREAMING, THE ONLY ENDING THAT IS NOT THE CLIENT'S is the window ending: the
  session put away, swept, or the claim changing hands. That sends an `end` frame --
  SAYING SO -- and closes. A stream that just stops leaves a replica believing it holds
  everything (`harness.edge.sessions/watch!`)."
  [req stem]
  (let [params  (query-params (:query-string req))
        held    (claims/holder stem)
        parse   (try {:since (number-param params "since")
                      :generation (get params "generation")}
                     (catch Throwable t {:error (ex-message t)}))]
    (cond
      (some? (:error parse))
      (api-response 400 {:error (:error parse) :threadId stem})

      (and (some? held) (not (claims/mine? held)))
      (refuse-served-elsewhere! stem held)

      :else
      (let [{:keys [since generation]} parse
            _      (sessions/touch! stem)
            mine   (sessions/generation stem)
            tail   (sessions/tail stem)
            stale? (or (and (some? generation) (not= (str generation) (str mine)))
                       (and (some? since) (some? (:baseSeq tail))
                            (< (long since) (long (:baseSeq tail)))))]
        (if stale?
          (api-response 409 {:error (str "this window is over: "
                                         (if (and (some? generation)
                                                  (not= (str generation) (str mine)))
                                           "the conversation is being served under a new generation"
                                           (str "the client is holding a cursor (since=" since
                                                ") older than the oldest entry this window still answers from"))
                                         ". Drop what you hold and open the tail again: GET"
                                         " /api/threads/" stem "/feed")
                             :threadId stem
                             :generation mine
                             :baseSeq (:baseSeq tail)})
          (stream-feed! req stem since mine))))))

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
  for its length, which ignores the budget and keeps only the newest indivisible unit."
  [stem provider records window ratios opts]
  (let [written   (atom [])
        put       (fn [kind payload]
                    (swap! written conj [kind payload])
                    (log! stem nil kind payload))
        summarize (fn [messages]
                    (let [specs []
                          p     (assoc provider :tools specs)]
                      (put "model/start" (dissoc (ev/model-start p specs) :type))
                      (try
                        (let [{:keys [message telemetry]}
                              (llm/stream! p
                                           (conj (vec messages)
                                                 {:role "user" :content compaction/summary-instruction})
                                           (fn [_]) stem)]
                          (put "model/end" telemetry)
                          (let [content (:content message)]
                            (if (string? content) content (str content))))
                        (catch Throwable t
                          (put "model/end" {})
                          (throw t)))))]
    ;; THE TWO HOOK POINTS THE TABLE ALREADY DECLARED (`harness.kernel.hooks`), fired around every
    ;; compaction -- automatic or manual. Observers (`:gate? false`), so neither can gate it; with
    ;; no sink bound (a manual compaction outside a run) they are simply quiet.
    (hook/emit :pre-compact {:thread-id stem})
    (let [result (compaction/perform! records
                                      {:window       window
                                       :retain-ratio (:retain-ratio ratios)
                                       :append       put
                                       :plan-fn      (when (:aggressive? opts) compaction/overflow-plan)
                                       :summarize    summarize})]
      (when (seq @written)
        (sessions/set-compactions!
         stem
         (replay/compaction-facts
          (into (vec records) (map (fn [[k p]] (row-of k p)) @written)))))
      ;; fired even when `perform!` had nothing to compact -- `:pre-compact` already fired, and a
      ;; half-open pair would be the worse trace.
      (hook/emit :post-compact {:thread-id stem})
      result)))

(defn- recover-overflow!
  "ONE AGGRESSIVE COMPACTION after the vendor refused the request for its LENGTH (ticket 05).

  Returns the SHORTER model view to retry with, or nil when nothing could be removed -- and the
  caller then hands the vendor's own refusal out UNTOUCHED. It never throws: a failure in the
  recovery must not replace the error that explains the run.

  NOTHING ABOUT CAPACITY IS READ. The vendor has already answered, so no window and no estimate
  is needed to justify the compaction -- `compaction/overflow-plan` ignores both on purpose.

  THE LOCK IS THE SAME ONE the manual route and the automatic trigger take, so a recovery
  cannot race either.

  THE RETRY KEEPS THE SYSTEM MESSAGE and takes the conversation from the RECORD's compacted
  view, so what goes out is a request built the one way this harness builds one. Anything this
  run produced that has not reached the record -- an earlier tool round of the SAME run -- and
  the derived injections are not re-sent: the run continues from the compacted conversation.
  THE VIEW IS MEASURED against what was actually sent, so a pass that removed nothing answers
  nil rather than retrying the same overflowing request."
  [stem provider history _t]
  (try
    (locking compaction-lock
      (when-some [f (replay/find-log (home/projects-dir) stem)]
        (let [records (replay/read-records f)
              ratios  (compaction/config stem)
              result  (run-compaction! stem provider records (:context-window provider) ratios
                                       {:aggressive? true})
              system  (vec (take-while #(= "system" (:role %)) history))
              retry   (into system (sessions/messages stem))]
          (when (and (some? result)
                     (< (pressure/estimate-messages retry) (pressure/estimate-messages history)))
            retry))))
    (catch Throwable _ nil)))

(defn- compact-if-pressured!
  "AUTO COMPACTION (ticket 04): at the start of a run, BEFORE it derives its request, measure
  the pressure against the window the record describes and compact when it is at or over the
  threshold. Below the threshold, nothing happens at all -- no rows, no model call.
  `harness.edge.pressure` owns the threshold and where the window comes from.

  FAILS SOFT: a report-only meter must never become a dead run, so anything wrong here is
  logged and the run carries on uncompacted."
  [stem]
  (try
    (locking compaction-lock
    (when-some [f (replay/find-log (home/projects-dir) stem)]
      (let [records (replay/read-records f)
            ratios  (compaction/config stem)
            answer  (pressure/records->pressure records (sessions/messages stem) ratios)]
        (when (and (:thresholdTokens answer)
                   (>= (:pressureTokens answer) (:thresholdTokens answer))
                   (not (compaction/lock-active? records)))
          (when-some [provider (providers/current-provider stem)]
            (run-compaction! stem provider records (:windowTokens answer) ratios nil))))))
    (catch Throwable t
      (log/warn! :compaction/auto-failed {:thread-id stem :reason (ex-message t)})))
  nil)

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
          (try
            (let [result (run-compaction! stem provider (:ok read)
                                            (:context-window provider) (compaction/config stem) nil)]
              (api-response 200 {:threadId  stem
                                 :compacted (some? result)
                                 :shadowed  (:shadowed result)}))
            (catch Throwable t
              (api-response 400 {:error (ex-message t) :threadId stem})))))))))

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
      ;; The verb-carrying routes: one shape, many verbs. THREE OF THEM ARE POSTS
      ;; because they have an effect, and `stats` is a GET because it only reads --
      ;; so the rule is 'the method says whether there is an effect', not 'this
      ;; shape is POST-only'. A method this shape does not serve is still answered
      ;; 405 HERE rather than falling through to the run endpoint -- which is where
      ;; the pre-verb dispatch used to send it, and where it became a 500 from a
      ;; body that was never there.
      (case [(:request-method req) verb]
        [:post "rebuild"] (rebuild-post req stem)
        [:post "compact"] (compact-post req stem)
        [:post "cancel"]  (cancel-post stem)
        [:post "archive"] (archive-post req stem)
        [:get "stats"]    (stats-get stem)
        [:get "trajectory"] (trajectory-get stem)
        [:get "sofar"]    (sofar-get req stem)
        [:get "feed"]     (feed-get req stem)
        [:get "page"]     (page-get req stem)
        [:get "delegations"] (delegations-get stem)
        [:get "follow"]      (follow-get req stem)
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
                   (subagents/install! {:run run-subagent!})]]
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
