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
    :put-away!        `harness.infra.stream/fsync!`: the other half of the same moment -- the PROCESSES
                      stop, and the BYTES this process was the only one holding get their promise
                      (ticket 04 of `.scratch/event-persistence`). A record is not a command: it
                      stays, and it should stay on the platter.

  THE IRON LAW RIDES ON `:build`: the mechanism never reads a record during a run, and the one
  read -- the walk a session is BORN by -- is the fold installed here."
  (:require [harness.cap.claims :as claims]
            [harness.cap.jobs :as jobs]
            [harness.cap.project :as project]
            [harness.edge.host :as host]
            [harness.infra.stream :as stream]
            [harness.edge.replay :as replay]
            [harness.infra.home :as home]
            [harness.kernel.session :as session]))

;; ------------------------------------------------------------------- the seams it installs

(def ^:private seams
 {:build
  (fn [thread-id registered]
    (if-some [f (replay/find-log (home/projects-dir) thread-id)]
      (let [{:keys [entries context state compactions prunes]
             folds :folds} (replay/fold-sofar f registered)]
        {:entries     (vec entries)
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
  :put-away!  stream/fsync!})

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
(def watch-unflushed! session/watch-unflushed!)
(def record-grew! session/record-grew!)
(def ring-growth! session/ring-growth!)
