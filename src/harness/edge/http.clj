(ns harness.edge.http
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
                   READS the log; this line is its one trace.

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
            ;; skill-picker 的 /api/skills 用它（那一票在 main 上，本分支没有）：
            [harness.cap.skills :as skills]
            [harness.cap.system-prompt :as system-prompt]
            [harness.cap.hooks :as cap-hooks]
            [harness.cap.tools :as cap-tools]
            [harness.kernel.tools :as tools]
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
      ;; THE RUN-SCOPED HOOK SINK, bound around the whole run. This is the edge,
      ;; so it is the only place that knows both the thread and where an audit
      ;; line goes; binding it here is what lets hooks fire at all -- and every
      ;; caller BELOW the edge (an offline tool, replay, a scripted test driving
      ;; the kernel directly) leaves it nil, so nothing fires there.
      ;;
      ;; It wraps the set-up as well as the run, because folding the session's
      ;; instruction files is itself a hook point (InstructionsLoaded) and that
      ;; folding happens before the first message is built. Binding it after the
      ;; set-up would have left that point declared and permanently silent.
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
                      (llm/thinking-mode-history
                       (ag/inbound (:messages input) (system-prompt/assemble thread-id)
                                   (opening-blocks! thread-id)
                                   (:context input))
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
            ;; to see. The ASSEMBLED system message -- prompt.md's frozen opening
            ;; with each SystemPrompt hook's text behind it -- plus every inbound
            ;; message in the provider's shape, one line each, VERBATIM. Context
            ;; rides as a trailing user message -- it must never touch the system
            ;; prompt, or the provider's prefill (prompt cache) would miss every
            ;; call. The appended text has no other trace: it is server-side, it
            ;; never becomes a frame, and this line is where its weight is on the
            ;; record. (The hook/SystemPrompt line records the same run of it.)
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
  harness.infra.home/*root-override* is a var holding a test override."
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

(def ^:private thread-verbs
  "The verbs this edge serves under /api/threads/<stem>/. A CLOSED SET, and that
  is load-bearing rather than tidiness: the handler dispatches on this shape
  BEFORE the run endpoint, so a path that merely looks like it -- and names a verb
  nobody serves -- has to fall through to the ordinary AG-UI handler rather than
  be answered 405 by a route that was never about it."
  #{"rebuild" "archive"})

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
          (let [before (providers/override-for thread-id)]
            (providers/set-override! thread-id nil)
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
              (let [before (providers/override-for thread-id)
                    answer (try {:ok (providers/resolve-override (merge before change))}
                                (catch Throwable t {:error (ex-message t)}))]
                (if-some [error (:error answer)]
                  (api-response 400 {:error error})
                  (let [after (providers/set-override! thread-id (merge before change))]
                    (log! thread-id nil "provider/session-changed"
                          {:before before :after after :via "http"
                           :resolved (:ok answer)})
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
    {:status 204 :headers cors}

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
      ;; The verb-carrying routes: one shape, two verbs, and every one of them is
      ;; a POST because every one of them has an effect. A GET on this shape is
      ;; answered 405 HERE rather than falling through to the run endpoint --
      ;; which is where the pre-verb dispatch used to send it, and where it became
      ;; a 500 from a body that was never there.
      (case [(:request-method req) verb]
        [:post "rebuild"] (rebuild-post req stem)
        [:post "archive"] (archive-post req stem)
        (api-response 405 {:error "method not allowed"}))
      (if-some [{:keys [verb stem]} (stem-verb-route "providers" provider-verbs (:uri req))]
        (case [(:request-method req) verb]
          [:post "remove"] (remove-provider-post stem)
          (api-response 405 {:error "method not allowed"}))
        (if-some [{:keys [verb stem]} (stem-verb-route "projects" project-verbs (:uri req))]
          (case [(:request-method req) verb]
            [:post "remove"] (remove-project-post stem)
            (api-response 405 {:error "method not allowed"}))
          (handle-run req))))))

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
  answered a 400 never reaches here."
  [req]
  (try
    (dispatch req)
    (catch Throwable t
      (log/error! :request-failed t {:method (:request-method req) :uri (:uri req)})
      (api-response 500 {:error (or (ex-message t) "the request failed")}))))

;; ---------------------------------------------------------------------- start

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
  (let [opts (merge {:port port} opts)
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
                   (system-prompt/install!)]]
    (println (str "logging to " root "/logs/harness.infra.log (rotated by date and size)"))
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
      (let [server (hk/run-server handler opts)]
        (println (str "harness listening on http://localhost:" (:port opts))
                 "-- POST an AG-UI RunAgentInput here; stop with (stop!)")
        (log/started root (:port opts))
        ;; http-kit's server IS the stop fn, and its meta carries :local-port --
        ;; which is how every test learns the port the OS handed out. The wrapper
        ;; keeps that meta, so `(meta stop)` still answers the same thing.
        (with-meta (fn stop! [] (server) (doseq [td teardowns] (td))) (meta server)))
      (catch Throwable t
        (doseq [td teardowns] (td))
        (log/error! :start-failed t {:port (:port opts) :root root})
        (throw t)))))

(defn -main [& _]
  (start!)
  @(promise))
