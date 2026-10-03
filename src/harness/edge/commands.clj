(ns harness.edge.commands
  "THE SESSION'S COMMAND QUEUE: things the HARNESS is asked to do to a conversation,
  as opposed to things the MODEL is asked (those ride `append`).

  WHY ONE QUEUE RATHER THAN A ROUTE PER ACTION (`.scratch/run-commands`): every
  management action used to get its own route -- `POST .../cancel`, `POST .../compact`
  -- and none of them had any relation to the others, so 'stop this run' and 'compact
  this conversation' had no common order and no common place. A per-session queue gives
  them one: a command arrives, waits its turn, and is taken at ONE boundary by whoever
  is serving the session. THE PRIORITY IS DATA, not a `cond` in the run loop: see
  `types`, where each command declares its rank and what happens when no run is in
  flight.

  THE QUEUE IS PROCESS MEMORY, like a pending approval or a job registry: a command is
  'do this to the conversation THIS process is serving right now', and a restart loses
  it. That is the honest lifetime -- the words a person typed are in the CLIENT's hands,
  and a client that comes back can send them again.

  WHAT IS HERE IS THE SLICE `.scratch/goal` NEEDS (ticket 03): the queue, the priority
  table, and the drain. The other two commands the design names -- `steer` and `queue`,
  which put TEXT into a running run -- are declared in the table and refused BY NAME
  until their own ticket lands, so a client that sends one is told rather than
  silently ignored.

  A COMMAND IS AN EVENT, NOT A MESSAGE (spec decision 7): none of these arrives in the
  conversation as a user message. What a command DOES is written down where it belongs
  (`goal/change`, `context/compacted`), and a second row saying 'somebody asked' would
  be a second copy of one fact."
  (:require [clojure.string :as str]))

;; ------------------------------------------------------------------- the table

(def types
  "EVERY COMMAND THIS BUILD KNOWS, by its wire name -- the ONE place a command is
  declared. A map rather than a `case`, because two readers have to agree about a
  command before anybody runs it: the DRAIN, which orders them, and the ROUTE, which
  has to decide what to do when no run is in flight.

    :priority  lower goes first. THE ORDER IS WRITTEN HERE rather than left to arrival
               time, and it is the design's own: `interrupt > steer > compact > goal = todo >
               queue`. `goal` and `todo` share a rank -- both are session-management writes
               that need no model call, and nothing orders one before the other. `interrupt` is
               not merely first in this list -- it also rings the
               run's stop switch the moment it arrives (see `harness.edge.http`), because
               a queue entry can wait and a person pressing stop cannot.
    :no-run    what the route does with it when NO run of that session is in flight:

                 :refuse     the command needs the thing it would act on (there is no
                             stop switch, and `steer` is about a step in progress)
                 :execute    the route runs it itself, in-process (see the note below)
                 :wait       it stays in the queue for the next run

  THE DEVIATION, SAID PLAINLY. `.scratch/run-commands` decision 4 says a command with
  no run in flight STARTS one (`:start-run`) so that every command is executed inside a
  run. That design is not implemented on this branch: starting a run means a model call,
  and a person pressing 'create this goal' has not asked for one. So `goal` and
  `compact` are `:execute` here -- the route drains the queue itself, which is the SAME
  `drain!` and the SAME queue, just a different boundary. When run-commands lands, these
  two become `:start-run` and this row is the only thing that changes."
  {"interrupt" {:priority 0 :no-run :refuse}
   "steer"     {:priority 1 :no-run :refuse :unimplemented true}
   "compact"   {:priority 2 :no-run :execute :unimplemented true}
   "goal"      {:priority 3 :no-run :execute}
   ;; THE TASK LIST'S REMINDER (`harness.cap.todos`): `remind` injects a reminder into the run
   ;; in flight, or opens one when nothing is running; `auto` is the switch the driver obeys.
   ;; Executed in-process like `goal`, and for the same reason -- no model call is needed to
   ;; flip a switch or to start the round that carries the reminder.
   "todo"      {:priority 3 :no-run :execute}
   "queue"     {:priority 4 :no-run :wait  :unimplemented true}})

(def ^:private unknown-priority 99)

(defn priority [type] (get-in types [type :priority] unknown-priority))

(defn known? [type] (contains? types type))

;; ------------------------------------------------------------------- the queue

(defonce ^:private queues
  ;; thread-id -> the commands queued for it, in ARRIVAL order. One queue per
  ;; conversation rather than one for the process: two conversations' commands have
  ;; nothing to say to each other, and a command is about the session it names.
  (atom {}))

(defn queued
  "The commands waiting for THREAD-ID, in arrival order. FOR A READER: the drain takes
  them out."
  [thread-id]
  (get @queues (str thread-id) []))

(defn enqueue!
  "Put COMMANDS (a sequence of maps, each with a `:type`) at the end of THREAD-ID's queue,
  and answer how many this call added. An empty or nil sequence is a no-op rather than a
  row of nothing."
  [thread-id commands]
  (let [cmd (vec (remove nil? commands))]
    (when (seq cmd)
      (swap! queues update (str thread-id) (fnil into []) cmd))
    (count cmd)))

(defn drain!
  "Take EVERYTHING waiting for THREAD-ID and run it through EXECUTE, in `priority` order,
  and answer what each one did.

  IT TAKES THE WHOLE QUEUE IN ONE GO, which is the design rather than a convenience:
  'interrupt beats steer beats compact beats goal beats queue' is a rule about ONE
  draining, and a drain that left the losers behind would let a later-arriving winner
  overtake them. Within one priority the ARRIVAL order stands (`sort-by` is stable).

  RUNNABLE? IS WHAT A BOUNDARY CAN DO, not what a command means: the route runs what it can
  execute itself and leaves the rest queued (`:wait` rows), while a run takes everything.
  A command held back KEEPS ITS PLACE AT THE FRONT of the queue, in arrival order -- it was
  there before whatever this drain ran, and a queue that moved it behind them would be
  reordering somebody else's commands.

  EXECUTE is handed one command and answers nil when it did the thing, or a map saying why it
  did not -- this namespace knows the ORDER, never what a command means. It runs on the
  caller's thread: the route's, or a run's pre-LLM seam."
  ([thread-id execute] (drain! thread-id execute (constantly true)))
  ([thread-id execute runnable?]
   (let [taken    (first (swap-vals! queues (fn [q] (assoc q (str thread-id) []))))
         all      (get taken (str thread-id) [])
         [go stay] ((juxt filter remove) #(runnable? (:type %)) all)]
     (when (seq stay)
       (swap! queues assoc (str thread-id) (vec stay)))
     (mapv (fn [command]
             (let [type (:type command)
                   outcome (try (execute command) (catch Throwable t (ex-message t)))]
               (if (map? outcome) (assoc outcome :type type) {:type type :ok true})))
           (sort-by (comp priority :type) go)))))

(defn clear!
  "Forget everything queued for THREAD-ID -- what a conversation that has LEFT this
  process takes with it (its queue is about serving it HERE, and there is nothing left
  to serve). For a test fixture too."
  [thread-id]
  (swap! queues dissoc (str thread-id))
  nil)

(defn reset-queues! [] (reset! queues {}))

;; ------------------------------------------------------------------- the route's half

(defn cannot-run-alone
  "The commands in COMMANDS that need a run of their own conversation and cannot have one
  right now -- `:refuse` rows, and (for now) the ones nothing executes. Answers a vector
  of sentences, one per command, so a route can refuse by name rather than in silence."
  [thread-id commands]
  (into []
        (keep (fn [{:keys [type]}]
                (let [row (get types type)]
                  (cond
                    (nil? row)
                    (str "this harness does not know a command of type " (pr-str type)
                         " -- the commands it reads are " (str/join ", " (sort (keys types))))


                    (= :refuse (:no-run row))
                    (str "a " (pr-str type) " command needs a run of " (pr-str (str thread-id))
                         " in flight, and this process has none: there is nothing for it to act on")

                    ;; A `:wait` ROW IS NEVER A REFUSAL, even when nothing executes it yet: what
                    ;; the route owes it is a place in the queue, not an answer. (`queue` is
                    ;; declared and unimplemented -- it waits, and a run that takes it says so.)
                    (= :wait (:no-run row))
                    nil

                    (:unimplemented row)
                    (str "the " (pr-str type) " command is not implemented in this build -- it is"
                         " declared (so it has a name and a rank) and refused rather than ignored")

                    :else nil))))
        commands))

(defn executable?
  "Does this command need a run in flight, or may the route run it itself? `:wait` rows
  answer false: they stay in the queue for the next run."
  [type]
  (= :execute (:no-run (get types type))))

(defn wait?
  "Does this command stay in the queue for the next run? The `queue` command's own
  semantics, and the reason a queue can outlive a request at all."
  [type]
  (= :wait (:no-run (get types type))))
