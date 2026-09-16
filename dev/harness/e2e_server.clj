(ns harness.e2e-server
  "The harness a UI test suite starts, on a port it chooses, serving a SCRIPTED
  provider -- so an end-to-end check through the real @ag-ui/client needs no
  api-key, no model and no human, and replays identically every run.

  This is the backend half of ui/test/: those suites drive a real harness over
  real HTTP, and this is what they drive. `harness.fake` is the same scripted
  provider the offline suite pins, one layer up; it lives under test/ and is
  loaded here on purpose, this namespace being dev-only itself.

      clojure -M:dev -m harness.e2e-server --script-file PATH [--port N]

  Writes ONE line to stdout -- `PRINT-READY {:port <int>}` -- once the socket is
  open, then serves until SIGTERM. The port cannot be an argument: it is only
  knowable after bind, and the default `--port 0` asks the OS for a free one,
  which is what a suite wants (nothing to collide with, nothing to coordinate).

  THE SCRIPT COMES FROM A FILE, AND IS RE-READ PER CONVERSATION. That file is the
  whole control channel, and it is a file rather than an RPC on purpose: the
  caller is a JavaScript test, so writing a file is one call there, and it keeps
  every test-only route out of the production HTTP edge.

  Re-read on a NEW thread id, not on every request: one conversation costs
  several LLM calls (a tool round is two), and the scripted provider consumes its
  turns one call at a time -- so re-reading per request would replay turn one
  forever, and never re-reading would make a second conversation in the same
  process start mid-script. A new thread id is exactly the boundary the tests
  mean by \"a new conversation\".

  CLJ_HARNESS_HOME must be set by the SPAWNER: the config root is read live, but
  a JVM reads its environment once, at startup. A test run must never write into
  the developer's real ~/.clj-harness.

  THE OS HOME IS PINNED HERE TOO, to a SIBLING of that root. The host's
  convention files live there -- ~/AGENTS.md and the skills under
  ~/.agents/skills -- and both are folded into every run's opening messages. A
  suite that read the developer's real home would therefore depend on one
  person's dotfiles: their AGENTS.md would ride every request, and their skill
  list would appear in every catalog. There is no environment variable for it
  (it is the JVM's own user.home), so the override is set here, by the process
  that is already the test's backend."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [harness.fake :as fake]
            [harness.infra.home :as home]
            [harness.edge.http :as http]
            [harness.cap.providers :as providers]))

(defn- turns-in [file]
  (let [f (io/file file)]
    (if (.exists f)
      (let [parsed (json/read-str (slurp f :encoding "UTF-8") :key-fn keyword)]
        (vec (or (:turns parsed) [])))
      [])))

(defn- install-pin!
  "Serve EVERY thread from SCRIPT-FILE, whatever its threadId.

  A pin is normally per-thread, and that shape is right for the offline suite:
  each test knows the one thread id it posts to. A suite driving the real AG-UI
  client cannot know its thread ids in advance -- the client mints one per
  conversation -- so pinning by id would need the id before the run that needed
  it.

  So this patches `pinned-provider`, the seam `current-provider` consults first.
  Patching that one public fn covers every caller at once: the run path, and the
  init-line suppression in harness.edge.http, which then reads correctly -- a session
  served by a script has no provider tier to record.

  The provider instance is keyed by thread id, so a new thread id starts a fresh
  script while the several LLM calls of one conversation keep advancing the one
  they started with."
  [script-file]
  (let [per-thread (atom {})]
    (alter-var-root
     #'providers/pinned-provider
     (constantly
      (fn [thread-id]
        (when (seq (str thread-id))
          (or (get @per-thread thread-id)
              (let [provider (fake/scripted (turns-in script-file))]
                (swap! per-thread assoc thread-id provider)
                provider))))))))

(defn- arg [args name]
  (some (fn [[k v]] (when (= name k) v)) (partition 2 1 args)))

(defn- isolate-os-home!
  "Point the host's convention directory at a fresh temp dir for this process,
  so a run's opening blocks come from a home this test made rather than the
  developer's. Sibling, never nested: the configuration root's own isolation is
  the spawner's (CLJ_HARNESS_HOME), and putting the user home inside it would
  place it within the fence's allowed set, quietly answering a question the
  fence tests ask."
  []
  (let [dir (io/file (System/getProperty "java.io.tmpdir")
                     (str "clj-harness-e2e-home-" (System/nanoTime)))]
    (.mkdirs dir)
    (alter-var-root #'home/*user-home-override* (constantly (str dir)))
    (str dir)))

(defn -main [& args]
  (let [script-file (or (arg args "--script-file")
                        (throw (ex-info "missing --script-file" {})))]
    (isolate-os-home!)
    (install-pin! script-file)
    (let [stop (http/start! {:port (Integer/parseInt (str (or (arg args "--port") "0")))})]
      (println (str "PRINT-READY {:port " (:local-port (meta stop)) "}"))
      (flush)
      (.addShutdownHook (Runtime/getRuntime) (Thread. ^Runnable stop))
      @(promise))))
