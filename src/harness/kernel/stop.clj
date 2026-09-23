(ns harness.kernel.stop
  "THE STOP SWITCH OF ONE RUN: the two facts a stop has to be, in one value.

  A RUN SOMEBODY WANTS TO STOP IS USUALLY PARKED, not spinning: it is waiting on a
  command, on a vendor, on a tool. So stopping it needs two different things, and a
  design with only one of them is a stop that does not work in the case it exists for:

    :flag  THE FACT. Sticky, cheap, askable at any boundary -- a loop that comes back
           from a call and checks its switch must not be able to miss a stop that
           arrived while it was away.
    :wake  THE DOORBELL. A channel, and the ONLY event it ever carries is its own
           close: a loop parked on `alts!!` over a slow call's result is woken by it
           instead of waiting for a call that is no longer wanted.

  WHY NOT ONE OF THEM ALONE. A channel cannot be ASKED whether it is closed without
  consuming the answer -- a closed channel and an open one with nothing on it both
  read nil -- so a flag-less design would make every boundary check a guess. And a
  flag cannot wake anybody, which would leave the slow-command case -- the one case a
  person is looking at when they press stop -- waiting for a result nobody wants.

  THE SHAPE IS THE KERNEL'S, THE INSTANCE IS THE EDGE'S: a run is started, addressed
  and stopped through `harness.edge.sessions` (which mints one of these per run and
  rings it by thread-id), and it is CONSUMED here, by the loop and by the execution
  seam -- both of which only ever ask `rung?` or listen on `:wake`. Nothing in this
  namespace knows what a session is."
  (:require [clojure.core.async :as async]))

(defn handle
  "One run's stop switch, unrung."
  []
  {:flag (atom false)
   :wake (async/chan 1)})

(defn rung?
  "Has HANDLE been rung? The sticky half -- safe to ask at any moment, and it stays
  true for the rest of the run's life."
  [handle]
  (boolean (some-> handle :flag deref)))

(defn ring!
  "Stop the run HANDLE belongs to. IDEMPOTENT, because a person can press stop twice
  (and the registry's own caller can race a run that is ending): the flag is already
  true and the channel is already closed the second time, and neither is an error.

  THE FLAG IS SET BEFORE THE DOORBELL IS RUNG, and that ordering is what makes the
  waiting side simple: a loop woken by `:wake` -- or a boundary check that runs after
  one -- reads a flag that is already true."
  [handle]
  (when (some? handle)
    (reset! (:flag handle) true)
    (async/close! (:wake handle))
    true))