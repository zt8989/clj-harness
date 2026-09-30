(ns harness.edge.sessions
  "THE ADAPTER around `harness.kernel.session`: it installs the half of a session's mechanism
  that needs to know what a RECORD is, and re-exports the mechanism under the names the edge
  already calls.

  THE MECHANISM IS THE KERNEL'S (tickets 08-10 of `.scratch/session-as-kernel`): per-thread
  state, the two subscription seams (a read fold and a live step), the window arithmetic, the
  run and stop pins. This namespace adds no state of its own -- every `defn` below IS the
  kernel's function -- and what it installs is the ADAPTER half:

    :build           the ONE read of a record, taken when a session is born. It is
                     `harness.edge.replay/fold-sofar`: the conversation, the runs and the
                     registered folds, all from one streaming walk.
    :model-messages  what a provider may be handed (`replay/compacted-messages` of
                     `replay/model-view`): the cards off, the compactions and prunes on.
    :read / :fold    the record's rows -- located (`replay/locate`), parsed
                     (`replay/read-records`) and, for a fold, streamed (`replay/fold-records`).
    :claim           `harness.cap.claims`, which is why the session's lifetime is a claim on
                     the conversation and an idle one does not hold it forever.
    :stop-jobs!      `harness.cap.jobs/stop-session!`: the PROCESSES a session started, stopped
                     when the session is put away (idle `sweep!` or an explicit `drop!`).
                     The records stay where they are -- an ending outlives the id that named
                     it -- while the commands go, because a session this process no longer
                     serves is one whose background commands nothing here could reach again.
    :put-away!        `harness.infra.stream`: the other half of the same moment -- the PROCESSES
                      stop, and the BYTES this process was the only one holding get their promise
                      (ticket 04 of `.scratch/event-persistence`). A record is not a command: it
                      stays, and it should stay on the platter. AND THE SAME MOMENT TAKES THE
                      CONVERSATION'S IN-MEMORY RING ROW (`stream/forget-kept!`, ticket 03 of
                      `.scratch/memory-hygiene`): what this process keeps is a cache of JUST NOW,
                      and history is what the file is for.

  THE IRON LAW RIDES ON `:build`: the mechanism never reads a record during a run, and the one
  read -- the walk a session is BORN by -- is the fold installed here."
  (:require [harness.cap.claims :as claims]
            [harness.cap.jobs :as jobs]
            [harness.cap.project :as project]
            [harness.edge.host :as host]
            [harness.infra.stream :as stream]
            [harness.edge.replay :as replay]
            [harness.infra.home :as home]
            [harness.kernel.frames :as frames]
            [harness.kernel.tools :as tools]
            [harness.kernel.session :as session]))

;; ------------------------------------------- the parks a conversation is stranded with
;;
;; A PARK LIVES IN THE PROCESS THAT MADE IT (`harness.kernel.tools/park-approval!` says
;; why), so a conversation read after a restart names interrupts nothing here holds: the
;; card a person is looking at is a form whose question cannot be fetched
;; (`GET /api/elicitation` answers 404) and whose answers go nowhere (`resume` refuses the
;; id). The CALL is still in that same conversation, with everything the question was made
;; of -- so the reader builds the one thing a restart cannot keep, the in-memory park, back
;; out of what the conversation itself says.

(defn- named-parks
  "The parked calls a rebuilt conversation says it is WAITING on: [{:interrupt-id ..
  :tool-call-id ..} ..], deduped by call, in the order the conversation names them.

  THE SAME KEY THE CLIENT READS ITS CARDS FROM (`metadata.custom.agui.interrupts`, folded
  out of `RUN_FINISHED.outcome.interrupts` by `harness.kernel.frames/apply-frames`): the
  id the card holds and the call it is about, written together on one message. A pair
  missing either half names nothing this can rebuild, so it is left out rather than
  completed by guessing."
  [entries]
  (let [pairs (for [e entries
                    i (get-in e [:message :metadata :custom frames/park-namespace :interrupts])
                    :let [{:keys [id toolCallId]} i]
                    :when (and (string? id) (string? toolCallId))]
                {:interrupt-id id :tool-call-id toolCallId})]
    (reduce (fn [acc p]
              (if (some #(= (:tool-call-id p) (:tool-call-id %)) acc) acc (conj acc p)))
            [] pairs)))

(defn- calls-by-id
  "ENTRIES' tool calls as id -> the call, for the ids in WANTED (a set). The shape is the
  OpenAI one the fold carries (`{:id .. :function {:name .. :arguments ..}}`), which is
  what `harness.kernel.tools/repark!` reads."
  [entries wanted]
  (into {} (for [e entries
                 c (:toolCalls (:message e))
                 :when (contains? wanted (:id c))]
             [(:id c) c])))

(defn revive-parks!
  "ENTRIES -> THE SAME ENTRIES, with one repair behind them: the calls this conversation is
  still waiting on are PARKED AGAIN, under the very interrupt ids it names.

  WHY A READER DOES THIS. A restart strands a question in two places, and the run path
  (`harness.kernel.loop`) repairs only one of them -- the run it hands the unanswered call
  to. The other is the page. The conversation a client reads names the interrupt its card
  was drawn from, that id and its call written side by side, and on a fresh process every
  door that id goes through answers 'nothing here': the card's `GET /api/elicitation` is a
  404 with no fields to draw, and the resume is refused as an unknown interrupt. The person
  is looking at a question they cannot answer.

  SO THE READER REBUILDS THE PARK FROM THE CONVERSATION, and nothing else: the id is the
  one the conversation named, the call is the one it carries, and what the park holds is
  derived from that call exactly as the run path derives it -- `harness.kernel.tools/
  repark!` is what decides whether a call can be rebuilt at all (a server's elicitation, a
  tool this session no longer serves, and a call whose question is not a function of its
  arguments cannot).

  IT WRITES NOTHING BUT THAT: the record is not touched, no claim is taken, no session is
  born, and an id ALREADY parked here is skipped -- so a page passing over a conversation
  cannot re-park a question somebody is halfway through answering, nor overwrite a decision
  that has already been taken. A second read finds every park present and writes nothing.

  Returns ENTRIES unchanged: a caller threads it through the answer it is building (`let`
  rather than a bare call), which is what keeps the repair from becoming a second reading
  of the conversation."
  [thread-id entries]
  (let [pairs   (named-parks entries)
        missing (remove #(tools/parked (:interrupt-id %)) pairs)]
    (when (seq missing)
      (let [by-id (calls-by-id entries (set (map :tool-call-id missing)))]
        (tools/repark! thread-id
                       (into []
                             (keep (fn [{:keys [interrupt-id tool-call-id]}]
                                     (when-some [call (by-id tool-call-id)]
                                       (assoc call :interrupt-id interrupt-id))))
                             missing))))
    entries))

;; ------------------------------------------------------------------- the seams it installs

(def ^:private seams
 {:build
  (fn [thread-id registered]
    (if-some [f (replay/find-log (home/projects-dir) thread-id)]
      (let [{:keys [entries context state compactions prunes]
             folds :folds} (replay/fold-sofar f registered)]
        {:entries     (revive-parks! thread-id (vec entries))
         :compactions (vec compactions)
         :prunes      (vec prunes)
         :context     (vec context)
         :state       state
         :folds       folds})
      ;; NO LOG YET: the first message of a brand-new conversation. THE FOLDS ARE STILL SEEDED --
      ;; from their own `:init`, which is the truth for an empty record -- because the session's
      ;; write stream advances them from the very first row it writes (`harness.kernel.session/
      ;; row-written!`). Answering with an empty fold table instead left every consumer fold
      ;; starting from nil at that first row: not 'an empty conversation' but 'a fold that never
      ;; began', and for a fold that folds INTO its value, an exception.
      {:entries [] :compactions [] :prunes [] :context [] :state nil
       :folds   (replay/folds-init registered)}))

  :model-messages
  (fn [entries compactions prunes]
    (replay/model-view (replay/compacted-messages entries compactions prunes)))

  :read
  (fn [thread-id]
    (try
      (let [f (replay/locate (home/projects-dir) thread-id)]
        (try {:ok (replay/read-records f)} (catch Throwable t {:error (ex-message t)})))
      (catch Throwable t {:missing (ex-message t)})))

  :fold
  (fn [thread-id init rf]
    (try
      (let [f (replay/locate (home/projects-dir) thread-id)]
        (try {:ok (replay/fold-records f init rf)} (catch Throwable t {:error (ex-message t)})))
      (catch Throwable t {:missing (ex-message t)})))

  :claim
  {:take!      claims/take!
   :release!   claims/release!
   :hand-over! claims/hand-over!}

  :stop-jobs! jobs/stop-session!
  :put-away!  (fn [thread-id]
                ;; TWO FACTS, ONE MOMENT, and the second one is why this is a function rather than
                ;; `stream/fsync!` itself: THE BYTES GET THEIR PROMISE, and the IN-MEMORY RING ROW
                ;; for this conversation goes with it (`forget-kept!` -- a reader with a cursor pulls
                ;; the file, so nothing askable is lost). `.scratch/memory-hygiene/` ticket 03.
                (stream/fsync! thread-id)
                (stream/forget-kept! thread-id)
                nil)})

(defn install!
  "Install the adapter's half of the session mechanism (tickets 08-10): the map above,
  merged over the kernel's defaults. THE COMPOSITION ROOT CALLS THIS
  (`harness.edge.http/start!`) -- nothing here happens because a namespace was loaded, so
  'why does this session answer with a real conversation' is answered by the line that
  installed it rather than by what somebody else required.

  Idempotent; returns the teardown that puts the kernel's defaults back."
  [ ] ; no arguments
  (session/install! seams)
  session/reset-seams!)

;; ---------------------------------------- the mechanism, re-exported under its known names
;;
;; ONE NAME PER DOOR, and the value IS the kernel's -- not a second implementation. A caller
;; that requires `harness.edge.sessions` (http, record, pressure, the tests) is calling the
;; kernel's session; this file is what makes the seams real for it.
(def idle-ttl-ms session/idle-ttl-ms)
(def max-running session/max-running)
(def page-size session/page-size)
(def watchers session/watchers)
(def model-view replay/model-view)

(defn model-nodes
  "THREAD-ID's model-facing surface AS NODES `{:id :message}`, or nil when this process does not
  hold the session.

  THE LIVE READING OF `replay/model-nodes`, and it exists for ONE caller: a compaction's plan
  (`harness.edge.compaction/plan`). The trigger measures the array the model is ACTUALLY handed
  (`harness.edge.pressure/live-surface`), and the record's own fold is not that array -- it drops
  every run's injections except the last one -- so a plan over the record fold can answer a head
  that is only the previous summary and relieve nothing (`.scratch/compaction-shape` ticket 05).
  Handing the plan THIS array makes the two halves of a compaction measure one thing.

  IT IS THE SAME FOLD THE SESSION ITSELF HANDS A RUN: `(replay/model-nodes (display ..) the
  session's own compactions and prunes)` -- which is exactly what `messages` is, plus the ids
  `:shadowed` is made of. The IDs are the record line numbers the entries arrived in, so a head
  planned over this array names nodes the RECORD's fold also has."
  [thread-id]
  (when-some [e (session/live-entry thread-id)]
    (replay/model-nodes (session/display thread-id)
                        (:compactions e)
                        (:prunes e))))

(def touch! session/touch!)
(def live-entry session/live-entry)
(def fold-value session/fold-value)
(def set-fold-value! session/set-fold-value!)
(def read-records session/read-records)
(def fold-record session/fold-record)
(def register-fold! session/register-fold!)
(def unregister-fold! session/unregister-fold!)
(def registered-folds session/registered-folds)
(def register-step! session/register-step!)
(def unregister-step! session/unregister-step!)
(def row-written! session/row-written!)
(def generation session/generation)
(def running? session/running?)
(def running-run-id session/running-run-id)
(def cancel! session/cancel!)
(def run-switch session/run-switch)
(def messages session/messages)
(def raw-messages session/raw-messages)
(def set-compactions! session/set-compactions!)
(def set-prunes! session/set-prunes!)
(def display session/display)
(def drawn session/drawn)
(def context session/context)
(def state session/state)
(def append! session/append!)
(def settle! session/settle!)
(def land-at! session/land-at!)
(def number-entries! session/number-entries!)
(def land! session/land!)
;; THE RUN STATE'S ADAPTER HALF. The kernel owns the REGISTRY (the pins, the refusal,
;; the stop switch -- it cannot see the store and must not), and the store's column
;; (`sessions.run_state`) is written HERE, at the two verbs every run start and end
;; already goes through. One seam, two facts moving together: the registry's `:runs`
;; set and the column are written at the same two moments, so neither can drift more
;; than one crash from the other -- and the startup cleanup below closes even that
;; gap.
(defn run-started!
  "Pin THREAD-ID's run (the kernel's own verb) AND record the column: the store's
  last-known state becomes `running` at the same moment the registry says so."
  [thread-id run-id]
  (session/run-started! thread-id run-id)
  (project/set-run-state! thread-id "running")
  ;; AND THE HOST HEARS: a run starting is a fact the sidebar's row draws (`running`),
  ;; so every page's listing is pushed the new word -- no other page has to poll or
  ;; press the button to see the spinner (ticket 02 of
  ;; `.scratch/sidebar-ws-and-run-state`).
  (host/ring!))

(defn run-finished!
  "Unpin the run AND record the column: the store's last-known state becomes
  `idle` when the registry's last pin goes."
  [thread-id run-id]
  (session/run-finished! thread-id run-id)
  (project/set-run-state! thread-id "idle")
  ;; AND THE HOST HEARS, for the same reason the start does: the row's spinner goes
  ;; off on every page, not only the one that ran it.
  (host/ring!))

(def clear-startup-run-state! project/clear-startup-run-state!)
(def tail session/tail)
(def tail-of session/tail-of)
(def since session/since)
(def since-of session/since-of)
(def before session/before)
(def before-of session/before-of)
(def drop! session/drop!)
(def sweep! session/sweep!)
(def live session/live)
(def running-count session/running-count)
(def start! session/start!)
(def watch! session/watch!)
(def unwatch! session/unwatch!)
(def prune-watches! session/prune-watches!)
(def watch-unflushed! session/watch-unflushed!)
(def record-grew! session/record-grew!)
(def ring-growth! session/ring-growth!)
