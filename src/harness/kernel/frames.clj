(ns harness.kernel.frames
  "The AG-UI frame applier: fold a stream of AG-UI frames into the message list
  they describe. This is the inverse of harness.edge.ag-ui/outbound, and it is the
  read half of the log contract -- the writer records frames, this folds them
  back into a conversation.

  Promoted here from the dev-side wire model (2026-09-13, ticket 05): rebuilding
  a conversation is now a product capability (the /api/threads/<id>/rebuild
  endpoint), and this is its engine. The iron law is untouched -- the kernel
  never reads its own log DURING a run; folding recorded frames is an explicit
  management action, outside any run.

  terminal? is here too, because \"the last frame was terminal\" is the
  completeness check every reader of a recorded frame stream needs before it
  trusts the fold.")

(defn terminal?
  "A run is over when one of these arrives. Nothing may follow it."
  [frame]
  (contains? #{"RUN_FINISHED" "RUN_ERROR"} (:type frame)))

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

  The output is the AG-UI message shape a client holds -- which is exactly what the
  rebuild endpoint hands back, so a client can re-own the conversation and continue it
  with an ordinary run."
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
