(ns harness.ui.e2e
  "Shared plumbing for the suites that drive a live harness: the URL, the script
  hand-off, the wire, and the async-test shape.

  The harness is started by the vitest driver (not here) and hands this side two
  facts through globals: HARNESS_URL, and HARNESS_SCRIPT -- the file the server
  re-reads whenever a NEW thread id arrives. Writing that file is how a test says
  what the model will reply, which is the whole reason these suites need no
  api-key: the provider is `harness.fake`, scripted, and the script is bytes on
  disk both processes can see.

  Every suite uses its own thread ids, so two of them cannot collide even when
  run back to back in one process, and a conversation that goes wrong cannot
  poison the next."
  (:require ["crypto" :as node-crypto]
            ["fs" :as fs]
            ["os" :as os]
            ["path" :as path]
            [cljs.test :as t]))

(defn url []
  (or (.-HARNESS_URL js/globalThis) "http://localhost:8080/"))

(defn thread-id
  "A fresh id per conversation. CopilotKit mints ids the same way; the point is
  that the harness holds NO session state, so an id is just a log filename.

  `node-crypto/randomUUID` is a CALL, not a method on a string -- `(.randomUUID
  node-crypto)` invokes randomUUID on randomUUID, which is what the first
  version of this did."
  [prefix]
  (str prefix "-" (node-crypto/randomUUID)))

(defn script!
  "Install TURNS -- a JS array of `#js {:reasoning .. :content .. :tool-calls ..}`
  -- as what the server serves from for the next NEW thread id.

  A turn declares a tool call like:
    #js {:id \"c1\" :name \"read\" :arguments #js {:path \"deps.edn\"}}
  `tool-calls` and `arguments` are spelled the way harness.fake reads them; the
  server's JSON reader keywordizes them on arrival.

  `clj->js` around the whole payload, not just `#js` on the outer literal:
  `#js` converts only the map it is written on, so a Clojure vector inside it
  survives as a Clojure vector -- and JSON.stringify of one emits its internal
  {meta, cnt, shift, root, tail} fields instead of the turns. The server then
  reads a script with no turns in it and answers every run with an empty one."
  [turns]
  (fs/writeFileSync (.-HARNESS_SCRIPT js/globalThis)
                    (.stringify js/JSON (clj->js {:turns turns}))))

;; -------------------------------------------------------------- the wire

(defn content
  "A message's text, or \"\" -- a message may carry only reasoning, or only tool
  calls, and `.-content` is then undefined rather than absent."
  [m]
  (if (string? (.-content m)) (.-content m) ""))

(defn post-run
  "POST an AG-UI RunAgentInput, answered with the raw Response. EXTRA is merged
  over the four required keys, which is how a resume is sent.

  `js/Object.assign`, not clojure.core/merge: `merge` treats its arguments as
  Clojure maps and `conj`s into them, which on a plain JS object throws
  \"No protocol method ICollection.-conj defined for type object\". A `#js`
  literal is an ordinary JS object, not a map."
  [tid rid messages extra]
  (js/fetch (url)
            #js {:method "POST"
                 :headers #js {"Content-Type" "application/json"
                               "Accept" "text/event-stream"}
                 :body (.stringify js/JSON
                                   (js/Object.assign
                                    #js {}
                                    #js {:threadId tid :runId rid
                                         :messages messages
                                         :tools #js [] :context #js []}
                                    (or extra #js {})))}))

(defn frames-from-sse
  "An SSE body -> a JS array of parsed data frames. Deliberately hand-rolled
  rather than borrowed from the client: this is the raw wire, and a test of the
  wire should not ask the client to interpret it first.

  `to-array` at the end, because callers index and `.length` it as JS -- a
  Clojure vector happens to support both through shadow's shims, but the shape
  here should be the shape the name promises."
  [body]
  (to-array
   (->> (.split (str body) "\n")
        (filter #(and (>= (count %) 5) (= "data:" (subs % 0 5))))
        (mapv #(js/JSON.parse (.trim (subs % 5)))))))

(defn fetch-frames! [tid rid messages]
  (-> (post-run tid rid messages nil)
      (.then (fn [resp] (.text resp)))
      (.then (fn [body] (frames-from-sse body)))))

;; ------------------------------------------------------------- async tests

(defn async!
  "The body of a `deftest`, as STEPS run in order. Each step is a fn of the
  previous step's value and may return a Promise; the next step waits for it.

  Flat on purpose. These tests are a linear sequence of wire operations --
  run, assert, run again, assert -- and written as nested `.then` callbacks they
  end up eight levels deep, where a missing paren is a real hazard and the order
  of operations is unreadable. The step form reads top to bottom.

  cljs.test's own `async` macro is not used for a different reason: it hands the
  test a `done` to call, and that call has to happen on the ERROR path too. Miss
  it and the test does not fail, it HANGS -- reported as a timeout with no
  reason, which is the least useful way to learn that an assertion threw. Here
  the error path is written once: a thrown step is REPORTED, and `done` is called
  once either way."
  [& steps]
  (reify
    t/IAsyncTest
    cljs.core/IFn
    (-invoke [_ done]
      (-> (reduce (fn [p step] (.then p (fn [v] (step v))))
                  (js/Promise.resolve nil)
                  steps)
          (.catch (fn [e]
                    (t/do-report {:type :error
                                  :message "the test body threw"
                                  :expected nil
                                  :actual e})))
          (.then (fn [_] (done)))))))

;; -------------------------------------------------------------- the filesystem

(defn tmp-path [^string name] (path/join (os/tmpdir) name))

(defn rm! [^string p] (fs/rmSync p #js {:force true :recursive true}))
(defn file-exists? [^string p] (fs/existsSync p))
