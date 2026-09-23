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

(defn cut-off-result
  "THE RESULT A CALL IS GIVEN WHEN ITS RUN WAS CUT OFF BEFORE IT RETURNED.

  A CALL THAT NEVER CAME BACK STILL NEEDS AN ANSWER, and the reason is not tidiness:
  an assistant message whose `toolCalls` has no answering tool message is a shape the
  vendors refuse, so leaving one open trades a hole in the record for a 400 on the
  next call. The sentence is the TRUE one -- the call was cut off and nothing was
  recorded -- rather than an invented result.

  ONE SENTENCE, TWO WRITERS, and that is why it lives here rather than beside either
  of them: `harness.edge.replay/closing-frames` writes it when a log ends mid-run and
  is repaired, and the run loop writes it for the calls still in flight when somebody
  presses stop (`.scratch/session-after-refresh` tickets 07/08). Two copies of it
  would be two ways for the record to describe one thing."
  [] "the run was cut off before this call returned; no result was recorded")

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

         ;; AN INJECTED CONTEXT FRAME COMES BACK AS A CARD MESSAGE. The client draws
         ;; it (a `data` part, see the UI's context-card) and never sends it back --
         ;; `toAgUiMessages` has no case for a data part -- so a rebuilt conversation
         ;; carries what was on screen without putting anything into what the model is
         ;; asked next. The id is the frame's own, so the same card survives every
         ;; rebuild under the same name.
         ;;
         ;; ANY OTHER CUSTOM FRAME IS DROPPED, which is the honest default: a frame
         ;; this fold has never heard of is one the conversation does not contain.
         (and (= t "CUSTOM") (= (:name f) "injected-context"))
         (conj msgs {:id (or (:messageId f) (str "injected-" (count msgs)))
                     :role "assistant"
                     :content [{:type "data" :name (:name f)
                                :data (get-in f [:value])}]})

         (= t "TOOL_CALL_RESULT")
         (conj msgs {:id (:messageId f) :role "tool"
                     :toolCallId (:toolCallId f) :content (:content f)})

         :else msgs)))
   []
   frames))
