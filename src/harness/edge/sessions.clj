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

  THE IRON LAW RIDES ON `:build`: the mechanism never reads a record during a run, and the one
  read -- the walk a session is BORN by -- is the fold installed here."
  (:require [harness.cap.claims :as claims]
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
      {:entries [] :compactions [] :prunes [] :context [] :state nil :folds {}}))

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
   :hand-over! claims/hand-over!}})

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
(def run-started! session/run-started!)
(def run-finished! session/run-finished!)
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
