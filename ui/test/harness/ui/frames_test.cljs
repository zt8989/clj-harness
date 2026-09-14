(ns harness.ui.frames-test
  "Every frame the server emits, parsed by the SHIPPED AG-UI schema.

  From ui/check-frames.mjs, which did this by hand: post one run, split the SSE
  body, feed each frame to @ag-ui/core's EventSchemas. Worth keeping because the
  schema is not a summary of the protocol -- it is the client's own validation,
  and this project already found three real violations through it that reading
  the prose did not. EventSchemas is zod, and zod validates BEFORE the client's
  applier sees a frame, so an invalid frame is a hard client failure no matter
  how harmless it looks.

  The script is one turn carrying a tool call, because that is where the most
  frame types appear: the reasoning before it, the call, and its result."
  (:require ["@ag-ui/core" :as core]
            [cljs.test :refer [deftest is]]
            [harness.ui.e2e :as e2e])
  (:require-macros [harness.ui.test-runner :refer [deftest-index]]))

(defn- valid? [frame]
  (.-success (.safeParse (.-EventSchemas core) frame)))

(defn- types [frames] (mapv #(.-type %) frames))

(deftest every-frame-passes-the-ag-ui-schema
  (e2e/async!
   (fn [_]
     (e2e/script! [#js {:reasoning "\u5148\u770b\u4e00\u773c\u3002"
                        :content ""
                        :tool-calls #js [#js {:id "c1" :name "read"
                                              :arguments #js {:path "deps.edn"}}]}
                   #js {:content "\u8fd9\u662f\u4e00\u4e2a Clojure \u9879\u76ee\u3002"}])
     (e2e/fetch-frames! (e2e/thread-id "frames") "r1" #js []))
   (fn [frames]
     (is (pos? (.-length frames)) "the run produced frames at all")
     (let [bad (remove valid? frames)]
       ;; Name the offending TYPE, not just a count: a count says a frame is
       ;; wrong, the type says which one.
       (is (empty? bad) (str "every frame passes EventSchemas; invalid: " (pr-str (types bad))))
       ;; The run must have ENDED, or "0 invalid" could just mean the stream
       ;; stopped early -- the cheapest way to pass a linter.
       (is (= "RUN_FINISHED" (last (types frames))) "the run reached RUN_FINISHED")))))

(deftest reasoning-frames-are-shape-legal
  ;; The three violations this project actually shipped, pinned so they cannot
  ;; come back: REASONING_START/END need a messageId, and the reasoning message's
  ;; role must be the literal "reasoning".
  (e2e/async!
   (fn [_]
     (e2e/script! [#js {:reasoning "\u5148\u770b\u4e00\u4e0b\u3002" :content "ok"}])
     (e2e/fetch-frames! (e2e/thread-id "reasoning") "r1" #js []))
   (fn [frames]
     (let [starts (filter #(= "REASONING_MESSAGE_START" (.-type %)) frames)]
       (is (seq starts) "the run emitted reasoning frames")
       (is (every? #(some? (.-messageId ^js %)) starts)
           "every REASONING_MESSAGE_START carries a messageId")
       (is (every? #(= "reasoning" (.-role ^js %)) starts)
           "and its role is the literal \"reasoning\"")))))

(deftest tool-frames-name-their-call
  ;; A tool call with no id -- or a result that names none -- is how a tool card
  ;; silently fails to render: the client matches them up by id alone.
  (e2e/async!
   (fn [_]
     (e2e/script! [#js {:content ""
                        :tool-calls #js [#js {:id "c9" :name "read"
                                              :arguments #js {:path "deps.edn"}}]}
                   #js {:content "done"}])
     (e2e/fetch-frames! (e2e/thread-id "toolframes") "r1" #js []))
   (fn [frames]
     (let [id-of (fn [^js f] (or (.-toolCallId f) (.-id f)))
           starts (filter #(= "TOOL_CALL_START" (.-type %)) frames)
           ends   (filter #(= "TOOL_CALL_END" (.-type %)) frames)]
       (is (= 1 (count starts)) "one tool call started")
       (is (= 1 (count ends)) "and one ended")
       (is (every? some? (map id-of starts)) "every start names a call")
       (is (= (set (map id-of starts)) (set (map id-of ends)))
           "the ends name the same calls the starts did")))))

(deftest no-chunk-frames-reach-the-client
  ;; The client materialises complete messages; a CHUNK frame is the old
  ;; streaming shape and would arrive as an unknown type.
  (e2e/async!
   (fn [_]
     (e2e/script! [#js {:reasoning "r" :content "hello"}])
     (e2e/fetch-frames! (e2e/thread-id "chunks") "r1" #js []))
   (fn [frames]
     (is (not-any? #(>= (.indexOf (.-type %) "CHUNK") 0) frames)
         "no frame type carries CHUNK"))))

(deftest an-error-run-is-well-formed
  ;; A run that fails still owes the client a legal terminal pair. The failure
  ;; has to happen INSIDE the run -- a body the server cannot even parse is
  ;; rejected before any frame exists, so it proves nothing about the error path.
  ;; A resume naming an interrupt this process never parked does happen inside
  ;; it, and is exactly the sort of client mistake the edge has to survive.
  (e2e/async!
   (fn [_]
     (e2e/script! [#js {:content "unused"}])
     (-> (e2e/post-run (e2e/thread-id "errframes") "r1" #js []
                       #js {:resume #js [#js {:interruptId "never-parked"
                                              :status "resolved"}]})
         (.then (fn [resp] (.text resp)))
         (.then e2e/frames-from-sse)))
   (fn [frames]
     (is (pos? (.-length frames)) "a failed run still gets frames")
     (is (every? valid? frames) "and every one of them is schema-legal")
     (is (= "RUN_ERROR" (last (types frames)))
         "and the run is terminally an error, not a broken stream")
     (is (.includes (.-message (last frames)) "unknown interrupt")
         "the error names what was wrong"))))

(deftest-index)
