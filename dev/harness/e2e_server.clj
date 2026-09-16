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

(defn- script-in
  "The script file -> {:turns [..] :thinking bool}.

  :thinking makes the served provider a THINKING-MODE VENDOR on the way in as well
  as on the way out (see harness.fake): a request whose assistant messages do not
  carry `reasoning_content` is answered with a DeepSeek-compatible gateway's own 400.
  A suite sets it when the case is about a conversation continuing -- the defect that
  mode reproduces only shows up on the SECOND request."
  [file]
  (let [f (io/file file)]
    (if (.exists f)
      (let [parsed (json/read-str (slurp f :encoding "UTF-8") :key-fn keyword)]
        {:turns (vec (or (:turns parsed) [])) :thinking (boolean (:thinking parsed))})
      {:turns [] :thinking false})))

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
              (let [{:keys [turns thinking]} (script-in script-file)
                    ;; A thinking-mode vendor comes WITH a reasoning effort: that is
                    ;; the knob that puts the REQUEST in thinking mode, and both halves
                    ;; of the rule are conditioned on it (the refusal, and the padding
                    ;; that satisfies it). Without it a case would exercise neither.
                    provider (cond-> (fake/scripted turns {:thinking thinking})
                               thinking (assoc :reasoning-effort "high"))]
                (swap! per-thread assoc thread-id provider)
                provider))))))))

(defn- arg [args name]
  (some (fn [[k v]] (when (= name k) v)) (partition 2 1 args)))

(defn- isolate-os-home!
  "Point the host's convention directory at PATH -- or at a fresh temp directory
  of this process's own when PATH is nil, which is what a suite that has nothing
  to plant wants.

  THE PATH IS AN ARGUMENT BECAUSE A CALLER HAS TO PLANT THINGS THERE, and it has
  to know where 'there' is BEFORE the server starts: ~/AGENTS.md and
  ~/.agents/skills are read into every run's opening blocks, so a check that
  wants a system-level skill in the catalogue -- or a standing instruction --
  must write it first, into a directory it chose. A server that made up its own
  private temp home and never said where it was left exactly that check
  impossible, and 'the machine's skills are one of the two layers' was
  consequently unmeasurable from the UI side.

  SIBLING, NEVER NESTED is the caller's half of the bargain: the configuration
  root's own isolation is the spawner's (CLJ_HARNESS_HOME), and putting the user
  home inside it would place it within the fence's allowed set, quietly
  answering a question the fence tests ask."
  [path]
  (let [dir (if path
              (io/file path)
              (io/file (System/getProperty "java.io.tmpdir")
                       (str "clj-harness-e2e-home-" (System/nanoTime))))]
    (.mkdirs dir)
    (alter-var-root #'home/*user-home-override* (constantly (str dir)))
    (str dir)))

(defn -main [& args]
  (let [script-file (or (arg args "--script-file")
                        (throw (ex-info "missing --script-file" {})))]
    (isolate-os-home! (arg args "--user-home"))
    (install-pin! script-file)
    (let [stop (http/start! {:port (Integer/parseInt (str (or (arg args "--port") "0")))})]
      (println (str "PRINT-READY {:port " (:local-port (meta stop)) "}"))
      (flush)
      (.addShutdownHook (Runtime/getRuntime) (Thread. ^Runnable stop))
      @(promise))))
