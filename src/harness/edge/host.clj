(ns harness.edge.host
  "THE HOST'S OWN DOORBELL: a change to a fact that is not about ONE conversation.

  The session window (`events.mux`) answers 'what is this conversation now'. A sidebar asks
  a different question -- which conversations and projects exist, what they are called,
  when they were last sent to, and which of them a run is going in THIS process -- and that
  answer is one listing the store plus the live-runs registry fold (`harness.edge.http`'s
  `projects-body`).

  NO SUBSCRIPTION, and that is the whole difference from `harness.edge.mux`: every
  `events.host` connection wants the same listing, so a ring writes to all of them. There
  is no per-connection set to keep and nothing to release but the watcher itself, which the
  close handler takes back.

  IT IS RUNG FROM THE MUTATORS, NOT FROM A TIMER: the handful of places that change one of
  these facts say so. A ring on an entry landing would push a listing per line of a run,
  and the listing does not change on an entry -- only on a run boundary, a row being
  written, and a project or archive change. Those are the call sites.")

(defonce ^:private watchers
  (atom #{}))

(defn watch!
  "Add a host watcher (a 0-arg fn) and answer it, so a caller can hand it back to
  `unwatch!`."
  [f]
  (swap! watchers conj f)
  f)

(defn unwatch!
  "Stop calling F. Idempotent: a connection whose close and whose replacement race is not a
  failure."
  [f]
  (swap! watchers disj f)
  nil)

(defn watching?
  "Whether anybody is listening -- a caller that would otherwise do work to build a frame
  may ask first."
  []
  (boolean (seq @watchers)))

(defn ring!
  "Say a host-level fact changed. A watcher that throws must not stop the others or reach
  the mutator: this runs on request threads and, for a run boundary, on the thread that
  just changed the session registry, so each call is under its own net. Nothing to ring is
  the ordinary case (no page has a sidebar open)."
  []
  (doseq [f @watchers]
    (try (f) (catch Throwable _ nil)))
  nil)
