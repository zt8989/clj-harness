(ns harness.wire
  "This project's own minimal model of an AG-UI client, plus the structural rules that
  client enforces. Lives under dev/ rather than test/ because two callers need it and
  neither should own it: the replay tool (which rebuilds conversations from a log) and
  the tests (which assert the same contract).

  It is deliberately NOT in src/. The kernel emits events; it does not consume them."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]))

(defn frames-from-sse
  "The AG-UI events carried by a raw SSE body."
  [body]
  (->> (str/split-lines body)
       (keep (fn [line]
               (when (str/starts-with? line "data:")
                 (json/read-str (str/trim (subs line 5)) :key-fn keyword))))
       vec))

(defn terminal?
  "A run is over when one of these arrives. Nothing may follow it."
  [frame]
  (contains? #{"RUN_FINISHED" "RUN_ERROR"} (:type frame)))

(defn violations
  "Every structural rule an AG-UI client enforces, checked across a whole stream.
  Returns a vector of human-readable problems; empty means the stream is valid."
  [frames]
  (let [open (atom {})
        bad  (atom [])]
    (doseq [f frames]
      (let [t  (:type f)
            id (or (:messageId f) (:toolCallId f))]
        (cond
          (str/ends-with? (str t) "_CHUNK")
          (swap! bad conj (str "chunk event emitted: " t))

          (contains? #{"TEXT_MESSAGE_START" "REASONING_MESSAGE_START"} t)
          (if (contains? @open id)
            (swap! bad conj (str "double START for " id))
            (swap! open assoc id :message))

          (contains? #{"TEXT_MESSAGE_CONTENT" "REASONING_MESSAGE_CONTENT"} t)
          (when-not (contains? @open (:messageId f))
            (swap! bad conj (str "CONTENT for unopened " (:messageId f))))

          (contains? #{"TEXT_MESSAGE_END" "REASONING_MESSAGE_END"} t)
          (if (contains? @open (:messageId f))
            (swap! open dissoc (:messageId f))
            (swap! bad conj (str "END for unopened " (:messageId f))))

          (= t "TOOL_CALL_START") (swap! open assoc id :tool)

          (= t "TOOL_CALL_ARGS")
          (when-not (contains? @open id) (swap! bad conj (str "ARGS for unopened " id)))

          (= t "TOOL_CALL_END") (swap! open dissoc id)

          (terminal? f)
          (do (when (seq @open) (swap! bad conj (str "left open at " t ": " (keys @open))))
              (when-not (= f (last frames))
                (swap! bad conj (str "frames after " t)))))))
    @bad))

;; ------------------------------------------------------------------ the applier

(defn- patch-by-id [messages id f]
  (mapv (fn [m] (if (= id (:id m)) (f m) m)) messages))

(defn- patch-tool-call [messages id f]
  (mapv (fn [m]
          (if (some #(= id (:id %)) (:toolCalls m))
            (update m :toolCalls #(mapv (fn [tc] (if (= id (:id tc)) (f tc) tc)) %))
            m))
        messages))

(defn apply-frames
  "The bare minimum of what @ag-ui/client's applier does: accumulate text and reasoning
  into separate messages, attach tool calls to the open assistant message, and turn
  results into tool messages.

  This is the inverse of harness.ag-ui/outbound, and it is load-bearing for more than
  testing: it is how a conversation is rebuilt from its recorded frames, which is what
  makes the log readable at all."
  [frames]
  (reduce
   (fn [msgs f]
     (let [t (:type f)]
       (cond
         (= t "TEXT_MESSAGE_START")
         (conj msgs {:id (:messageId f) :role "assistant" :content ""})

         (= t "TEXT_MESSAGE_CONTENT")
         (patch-by-id msgs (:messageId f) #(update % :content str (:delta f)))

         (= t "REASONING_MESSAGE_START")
         (conj msgs {:id (:messageId f) :role "reasoning" :content ""})

         (= t "REASONING_MESSAGE_CONTENT")
         (patch-by-id msgs (:messageId f) #(update % :content str (:delta f)))

         (= t "TOOL_CALL_START")
         (patch-by-id msgs (:parentMessageId f)
                      #(update % :toolCalls (fnil conj [])
                               {:id (:toolCallId f) :type "function"
                                :function {:name (:toolCallName f) :arguments ""}}))

         (= t "TOOL_CALL_ARGS")
         (patch-tool-call msgs (:toolCallId f)
                          #(update-in % [:function :arguments] str (:delta f)))

         (= t "TOOL_CALL_RESULT")
         (conj msgs {:id (:messageId f) :role "tool"
                     :toolCallId (:toolCallId f) :content (:content f)})

         :else msgs)))
   []
   frames))
