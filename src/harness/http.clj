(ns harness.http
  "The AG-UI edge. One POST endpoint, SSE out, CORS so a browser app on :5173 can call
  it directly (there is no proxy in front of us). Alongside it, a small management
  edge of plain JSON endpoints -- /api/project, the session's project-directory
  binding, plus /api/project/pick, the OS folder dialog that feeds it -- sharing
  the same CORS and logging.

  Also append-only JSONL logging: one file per thread, under the session's
  project's workspace in the home's projects tree -- the path is the one place
  where 'where things are' and 'who this session is' are joined (see
  log-dir-for). These line kinds.

    \"input\"   -- the client's RunAgentInput as received.
    \"event\"   -- every AG-UI frame we emitted.
    \"message\" -- one line per provider-shaped message the LLM saw or produced,
                   VERBATIM: the frozen system prompt, each inbound message
                   (per-run context rides as a trailing user message), and every
                   assistant reply / tool result the kernel appended.
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
                   READS the log; this line is its one trace.

  All of it is a RECORD, never a source of truth -- the client owns the conversation,
  and the server never reads the file back."
  (:require [clojure.core.async :as async]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [harness.ag-ui :as ag]
            [harness.event :as ev]
            [harness.hooks.dispatch :as hook]
            [harness.home :as home]
            [harness.llm :as llm]
            [harness.providers :as providers]
            [harness.loop :as loop]
            [harness.project :as project]
            [harness.replay :as replay]
            [harness.tools :as tools]
            [org.httpkit.server :as hk])
  (:import [java.nio.charset StandardCharsets]))

(def port 8080)
(def ui-origin "http://localhost:5173")

(def ^:private cors
  {"Access-Control-Allow-Origin"  ui-origin
   "Access-Control-Allow-Methods" "GET, POST, OPTIONS"
   "Access-Control-Allow-Headers" "Content-Type"})

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

  The join between 'where things are' (harness.home) and 'who this session is'
  (harness.project) is made here, on the writing side: the project's identity
  comes from the store and the directory comes from the rule above. Keeping the
  join here is what leaves harness.home knowing only the root and the naming rule,
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

(defn- log! [thread-id run-id kind payload]
  (let [f (log-file-for thread-id)
        line (str (json/write-str {:ts (System/currentTimeMillis)
                                   :runId run-id :kind kind :payload payload}) "\n")]
    (.mkdirs (.getParentFile f))
    (locking log-lock
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

(defn- init-logged? [thread-id] (contains? @init-logged thread-id))

(defn- mark-init-logged! [thread-id] (swap! init-logged conj thread-id))

(defonce ^:private session-started
  (atom #{}))
;; thread-ids whose SessionStart has already fired. A SECOND writer-side fact,
;; kept apart from init-logged? on purpose: the provider init LINE is not written
;; when a scripted pin serves the session (there is no resolution to record), but
;; the session still started, and a hook bound to SessionStart must fire once for
;; it either way. One atom per fact, each named for the fact it holds.

(defn- session-started? [thread-id] (contains? @session-started thread-id))

(defn- mark-session-started! [thread-id] (swap! session-started conj thread-id))

(defn- log-messages!
  "One \"message\" line per provider-shaped message, VERBATIM. The submitted and
  the returned side of the message record both come through here."
  [thread-id run-id msgs]
  (doseq [m msgs]
    (log! thread-id run-id "message" m)))

;; ------------------------------------------------------------------- the edge

(def ^:private terminal #{"RUN_FINISHED" "RUN_ERROR"})

(defn- lifecycle-record
  "A tool-lifecycle kernel event -> the [kind payload] jsonl line it becomes,
  keyed by toolCallId like applepi's ADR-0021 audit lines. Nil for every other
  event kind. The audit line is additive: the event itself carries no wire
  frame, so the AG-UI conversion upstream of this is untouched."
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

    nil))

(defn- runner
  "Build the frame emitter for one run. Two http-kit rules have to hold at once:

    - the status and headers ride on the FIRST send!, not on the ring response, so a
      separate header-only send is not an option;
    - the LAST frame carries close-after-send?. Closing down a separate code path
      loses the response: http-kit buffers small writes, and a lone close discards
      whatever was never flushed. Watched a complete, correctly logged run deliver
      zero frames that way.

  Bodies are UTF-8 BYTES: this machine's JVM default charset is GBK, so handing
  http-kit a String would be a coin flip on any non-ASCII."
  [thread-id run-id ch]
  (let [first? (atom true)]
    (fn [frame]
      (log! thread-id run-id "event" frame)
      (let [body (.getBytes (str "data: " (json/write-str frame) "\n\n")
                            StandardCharsets/UTF_8)
            head (when @first?
                   {:status  200
                    :headers (merge cors {"Content-Type" "text/event-stream"
                                          "Cache-Control" "no-cache"})
                    :body    body})]
        (reset! first? false)
        (hk/send! ch (or head body) (contains? terminal (:type frame)))))))

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

  Sets render as sorted string vectors (harness.providers/wire): two otherwise
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

(defn- run-agent! [ch input]
  (let [thread-id (str (:threadId input))
        run-id    (str (:runId input))
        ;; ONE emitter and ONE converter per run. The converter owns the open-message
        ;; state machine, so building it per event restarts every message id and
        ;; re-emits START frames -- which an AG-UI client treats as fatal.
        emit    (runner thread-id run-id ch)
        convert (ag/outbound thread-id run-id)]
    (log! thread-id run-id "input" input)
    (async/go
      ;; A malformed input, an unreadable prompt, a bad config, an image aimed at a
      ;; text-only model -- or a resume naming an interrupt this process never
      ;; parked -- blows up before the run starts. Catch it here and push a
      ;; well-formed RUN_STARTED..RUN_ERROR pair so the client sees a terminated
      ;; run rather than a broken stream.
      (let [[provider messages decisions resolved]
            (try (let [provider (providers/current-provider thread-id (:provider input))]
                   (guard-input-modalities! input provider)
                   [provider
                    (ag/inbound (:messages input) (llm/prompt) (:context input))
                    (resume-decisions (:resume input))
                    (providers/resolve-provider thread-id (:provider input))])
                 (catch Throwable t
                   (doseq [frame (into (vec (convert (ev/run-start)))
                                       (convert (ev/run-error (ex-message t))))]
                     (emit frame))
                   nil))]
        ;; THE RUN-SCOPED HOOK SINK. This is the edge, so it is the only place
        ;; that knows both the thread and where an audit line goes; binding it
        ;; around the whole run is what lets hooks fire at all -- and every caller
        ;; BELOW the edge (an offline tool, replay, a scripted test driving the
        ;; kernel directly) leaves it nil, so nothing fires there.
        (when provider
          (binding [hook/*sink* {:thread-id thread-id
                                 :audit     (fn [payload]
                                              (log! thread-id run-id
                                                    (str "hook/" (:point payload))
                                                    (dissoc payload :point)))
                                 :run-id    run-id}]
            ;; SessionStart fires on a session's FIRST run -- beside the provider
            ;; init line, because both answer "what is this conversation, as it
            ;; begins". It is an observer: its verdict is discarded. Every start is
            ;; a "new" one today; the rebuild path (:source "resume") is a later
            ;; ticket's.
            (when-not (session-started? thread-id)
              (hook/emit :session-start {:source "new"})
              (mark-session-started! thread-id))
            ;; The provider timeline, part 1: ONE init line per session, on its
            ;; first run. It lands after the input line and before the first
            ;; message line, so a reader meets "here is what this conversation is
            ;; served by" before it meets the conversation. Later runs of the same
            ;; thread do not repeat it -- the timeline is init plus changes, not a
            ;; snapshot per run.
            (when (and (nil? (providers/pinned-provider thread-id))
                       (not (init-logged? thread-id)))
              (log! thread-id run-id "provider/init"
                    (provider-line provider (:source resolved)))
              (mark-init-logged! thread-id))
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
            ;; to see. The FROZEN system prompt plus every inbound message in the
            ;; provider's shape, one line each, VERBATIM. Context rides as a
            ;; trailing user message -- it must never touch the system prompt, or
            ;; the provider's prefill (prompt cache) would miss every call.
            (log-messages! thread-id run-id messages)
            ;; Drain run-chan and convert each kernel event to AG-UI frames. The
            ;; stream closes via :run/end's RUN_FINISHED (or RUN_ERROR), or via
            ;; :run/interrupt's RUN_FINISHED carrying outcome.interrupts; the
            ;; :run/done history itself is never converted -- it is the returned
            ;; side of the message record instead.
            (let [events (loop/run-chan provider messages {:thread-id thread-id
                                                           :resume decisions})]
              (loop []
                (when-let [ev (async/<! events)]
                  (if (= :run/done (:type ev))
                    ;; Returned side of the message record: every message the kernel
                    ;; appended after the initial vector -- assistant replies
                    ;; VERBATIM (the history holds the provider message unrebuilt,
                    ;; reasoning and tool calls intact) and each tool result as the
                    ;; tool message submitted on the next call. :run/done follows
                    ;; RUN_ERROR too, so any run the kernel started leaves its full
                    ;; message tail on disk -- but it lands one beat AFTER the
                    ;; terminal frame, so a reader racing the consumer may not see
                    ;; it yet.
                    (log-messages! thread-id run-id
                                   (subvec (:history ev) (count messages)))
                    (do ;; Tool-lifecycle events are audit lines, not wire frames:
                        ;; each lands as its own jsonl line, keyed by toolCallId.
                        (when-let [[kind payload] (lifecycle-record ev)]
                          (log! thread-id run-id kind payload))
                        (doseq [frame (convert ev)] (emit frame))
                        (recur))))))))))))


(defn- handle-run [req]
  (let [input (json/read-str (slurp (:body req) :encoding "UTF-8") :key-fn keyword)]
    ;; as-channel wants no status or headers of its own. run-agent! returns immediately
    ;; -- the run is driven by a go loop draining the core.async channel -- so it does
    ;; not block the worker that :on-open runs on.
    (hk/as-channel req {:on-open (fn [ch] (run-agent! ch input))})))

;; ----------------------------------------------------- the management edge
;;
;; Plain request/response JSON, alongside the streaming AG-UI edge. Small on
;; purpose: each endpoint is a thin wrapper over one harness namespace call.
;; Responses are UTF-8 BYTES, like every other body this server writes -- the
;; JVM default charset is GBK here.

(defn- api-response [status body]
  {:status  status
   :headers (merge cors {"Content-Type" "application/json; charset=utf-8"})
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

(defn- osascript-directory!
  "macOS's native folder dialog, as a string path or nil when the human
  cancels. The browser cannot hand us an absolute path -- a web file input
  gives a File object with no real location -- so the dialog runs where the
  process actually lives. It is drawn by the app that owns the window, not by
  the browser: osascript owns its own window and does not need to borrow the
  user's focus from whatever they are typing in.

  Output is decoded UTF-8 EXPLICITLY rather than through `slurp`: Java 17 on
  this machine defaults to GBK, so an implicit byte->String here would mangle
  any directory whose name is not ASCII. The same reason `api-response` writes
  bytes, one direction over.

  Any failure -- osascript missing, the script refused, a dialog we cannot
  answer -- comes back as nil, which the endpoint reports as a cancellation.
  A picker that cannot open is not worth failing a request over."
  []
  (try
    (let [proc (-> (ProcessBuilder. ["osascript" "-e"
                                     "POSIX path of (choose folder with prompt \"选择一个项目目录 -- select a project directory\")"])
                   (.redirectErrorStream true)
                   (.start))
          out  (String. (.readAllBytes (.getInputStream proc)) StandardCharsets/UTF_8)
          code (.waitFor proc)]
      (when (and (zero? code) (seq (str/trim out)))
        (str/trim out)))
    (catch Throwable _ nil)))

(def ^:dynamic *directory-chooser*
  "The picker itself, as a seam. Tests BIND this to a stub: the real one opens
  a window and waits for a human, which no test run may do. Production leaves
  it at the real dialog -- a var holding the default, exactly like
  harness.home/*root-override* is a var holding a test override."
  osascript-directory!)

(defn- project-pick
  "POST /api/project/pick -- open the native folder dialog and answer the
  chosen absolute path, or {:dir nil} when the human cancels. A question asked
  with POST because the call has a side effect the human sees: a window opens,
  which is not something a cache or a prefetch may trigger.

  Nothing is bound here. The client takes the path, shows it, and binds it
  through the ordinary /api/project POST -- so there is exactly ONE route that
  mutates a binding, and picking a folder leaves no trace of its own."
  [_req]
  (let [dir (*directory-chooser*)]
    (if (str/blank? dir)
      (api-response 200 {:dir nil})
      (api-response 200 {:dir dir}))))

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
  row here; GET /api/threads is the raw tree view for anyone diagnosing."
  [_req]
  (let [by-project (group-by :project-id (project/sessions))]
    (api-response 200
                  (mapv (fn [{:keys [id canonical-path]}]
                          (let [ws (workspace-for canonical-path)]
                            {:projectId id
                             :path      canonical-path
                             :sessions  (newest-first
                                         (mapv #(session-row ws %)
                                               (get by-project id)))}))
                        (project/projects)))))

(defn- thread-rebuild-stem
  "/api/threads/<stem>/rebuild -> <stem>, else nil. The stem is the sanitized
  FILE stem -- the same id /api/threads lists -- so a client can copy it
  straight from the listing. Percent-decoding happens after the path split, so
  an id that contains an encoded slash cannot jump out of its path segment."
  [uri]
  (let [parts (str/split (str uri) #"/")]
    (when (and (= 5 (count parts))
               (= "api" (nth parts 1))
               (= "threads" (nth parts 2))
               (= "rebuild" (nth parts 4))
               (seq (nth parts 3)))
      (java.net.URLDecoder/decode (nth parts 3) "UTF-8"))))

(defn- rebuild-post
  "POST /api/threads/<stem>/rebuild -- hand the client its conversation back:
  the AG-UI message list (seed + every recorded frame, reasoning and tool
  calls included) plus the context it started with. The client takes both into
  its next ordinary run; the server holds no rebuilt state. A truncated or
  corrupt log is refused with the reason on the 400. The rebuild action lands
  a session/rebuilt audit line on the log it rebuilt -- runId nil, because a
  rebuild happens OUTSIDE any run.

  The stem is located BEFORE anything else happens, and that ordering is why a
  rebuild never writes half a trace: a stem that resolves to nothing, or to more
  than one file, is refused without landing its audit line anywhere."
  [req stem]
  (let [located (try {:ok (replay/locate (home/projects-dir) stem)}
                     (catch Throwable t {:error (ex-message t)}))
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

(defn handler [req]
  (cond
    (= :options (:request-method req))
    {:status 204 :headers cors}

    (= "/api/model" (:uri req))
    (case (:request-method req)
      :get  (model-get req)
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
      (api-response 405 {:error "method not allowed"}))

    (and (= :post (:request-method req)) (thread-rebuild-stem (:uri req)))
    (rebuild-post req (thread-rebuild-stem (:uri req)))

    :else
    (handle-run req)))

;; ---------------------------------------------------------------------- start

(defn start!
  "Start the server and return its stop fn. Default port is 8080."
  [& [opts]]
  (let [opts   (merge {:port port} opts)
        server (hk/run-server handler opts)]
    (println (str "harness listening on http://localhost:" (:port opts))
             "-- POST an AG-UI RunAgentInput here; stop with (stop!)")
    server))

(defn -main [& _]
  (start!)
  @(promise))
