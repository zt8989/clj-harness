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

;; THE `metadata.custom.<ns>.interrupts` KEY A REBUILT PARKED RUN IS READ BACK FROM.
;;
;; Its value is pinned by `@assistant-ui/react-ag-ui` (`AG_UI_METADATA_NAMESPACE = "agui"`),
;; which reads `metadata.custom.agui.interrupts` when it converts a rebuilt conversation and
;; refuses an interrupt whose `id` or `reason` is not a string. THE LIVE PATH NEVER WRITES
;; THIS FROM THIS SIDE: the client's own aggregator stores what
;; `RUN_FINISHED.outcome.interrupts` carried. A REBUILT conversation has no aggregator, so
;; this fold has to produce the same shape or the card cannot come back
;; (`.scratch/session-after-refresh` ticket 06) -- a second spelling here would be a card
;; that draws nowhere.
(def park-namespace "The metadata namespace the client reads a parked run back from -- see above." "agui")

;; Attach INTERRUPTS to the LAST assistant message, the way the client's reader finds them
;; (`findRequiresActionAssistant("interrupt")` is about the last assistant). A parked run's
;; parked call belongs to that message -- it is the one the run was building when it stopped
;; -- so this is placement rather than a guess.
(defn- park-on-last-assistant
  "Attach INTERRUPTS to the last assistant message -- see above."
  [msgs interrupts]
  (let [last-assistant (last (keep-indexed (fn [i m] (when (= "assistant" (:role m)) i)) msgs))]
    (if (nil? last-assistant)
      msgs
      (assoc-in msgs [last-assistant :metadata :custom park-namespace :interrupts] interrupts))))

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

         ;; A SNAPSHOT IS THE CONTENT, NOT A PIECE OF IT (ticket 03 of `.scratch/event-persistence`): a
         ;; running answer reaches the record as whole-text snapshots every ~75ms rather than one line
         ;; per token, so this REPLACES what it has instead of appending to it.
         ;;
         ;; AND IT MAY HAVE NOTHING, which is the other half of why a snapshot is worth having: a
         ;; reader whose window opens ON a snapshot line still gets the sentence. A delta has to be
         ;; preceded by every delta before it or it is a fragment.
         (and (= t "CUSTOM") (= (:name f) "text/snapshot"))
         (let [id   (get-in f [:value :messageId])
               text (str (get-in f [:value :content]))]
           (if (some #(= id (:id %)) msgs)
             (patch-by-id msgs id #(assoc % :content text))
             (conj msgs {:id id :role "assistant" :content text})))

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

         ;; A PARKED RUN'S ENDING IS PART OF THE CONVERSATION, not just a terminal to stop
         ;; at: the frame names the calls a human must decide, and the client draws its card
         ;; from the LAST assistant message's `metadata.custom.agui.interrupts` (ticket 06
         ;; of `.scratch/session-after-refresh`). The live path gets that metadata from the
         ;; client's own aggregator; a rebuilt one has only this fold.
         (and (= t "RUN_FINISHED") (= "interrupt" (get-in f [:outcome :type])))
         (let [interrupts (vec (get-in f [:outcome :interrupts]))]
           (if (seq interrupts)
             (park-on-last-assistant msgs interrupts)
             msgs))

         :else msgs)))
   []
   frames))
