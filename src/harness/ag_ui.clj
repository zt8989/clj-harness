(ns harness.ag-ui
  "Conversion between the kernel's flat event stream and AG-UI wire events.

  OUTBOUND (outbound) is a stateful fold, because AG-UI constrains message and tool-call
  ids to be opened and closed in order while the kernel's events carry no such
  structure. That state lives here and nowhere else -- it never leaks into the kernel.

  The shape it produces, per assistant turn:
    reasoning group (optional)  ->  text message  ->  tool calls parented to it
  A turn with no text still opens an empty TEXT_MESSAGE, so every tool call has a
  parentMessageId and every reasoning message has an assistant message to fold onto.

  INVARIANT: never emit a *_CHUNK event. The client's applier throws on an unexpanded
  one, and relying on its stream transform to expand them first is a needless risk.")

(defn- open-text [s]
  (if (:text s)
    s
    (let [id (str (:run-id s) "-m" (:n s))]
      (-> s (assoc :text id) (update :n inc)
            (update :frames conj {:type "TEXT_MESSAGE_START" :messageId id :role "assistant"})))))

(defn- close-text [s]
  (if-let [id (:text s)]
    (-> s (assoc :text nil) (update :frames conj {:type "TEXT_MESSAGE_END" :messageId id}))
    s))

(defn- close-reasoning [s]
  (if-let [id (:reasoning s)]
    (-> s (assoc :reasoning nil)
          (update :frames conj {:type "REASONING_MESSAGE_END" :messageId id}
                              {:type "REASONING_END"}))
    s))

(defn- open-reasoning [s]
  (if (:reasoning s)
    s
    (let [id (str (:run-id s) "-r" (:n s))]
      (-> s (assoc :reasoning id) (update :n inc)
            (update :frames conj {:type "REASONING_START"}
                                {:type "REASONING_MESSAGE_START" :messageId id})))))

(defn- step [s ev]
  (case (:type ev)
    :run/start
    (update s :frames conj {:type "RUN_STARTED"
                            :threadId (:thread-id s) :runId (:run-id s)})

    :reasoning/delta
    ;; The id must be read AFTER opening: inside a -> the map literal still sees the
    ;; outer binding, so (-> s open-reasoning (update .. conj {.. (:reasoning s)})) would
    ;; capture the pre-open value. Hence the explicit let.
    (let [s (open-reasoning s)]
      (update s :frames conj {:type "REASONING_MESSAGE_CONTENT"
                              :messageId (:reasoning s) :delta (:text ev)}))

    :text/delta
    (let [s (-> s close-reasoning open-text)]
      (update s :frames conj {:type "TEXT_MESSAGE_CONTENT"
                              :messageId (:text s) :delta (:text ev)}))

    :tool/call
    ;; The parent must be named, then closed, before the call is announced -- that is
    ;; the order the protocol's own example uses.
    (let [s      (-> s close-reasoning open-text)
          parent (:text s)]
      (-> s close-text
            (update :frames conj {:type "TOOL_CALL_START" :toolCallId (:id ev)
                                  :toolCallName (:name ev) :parentMessageId parent}
                                {:type "TOOL_CALL_ARGS" :toolCallId (:id ev) :delta (:args ev)}
                                {:type "TOOL_CALL_END" :toolCallId (:id ev)})))

    :tool/result
    (let [id (str (:run-id s) "-t" (:n s))
          s  (-> s (update :n inc) close-text)]
      (update s :frames conj {:type "TOOL_CALL_RESULT" :messageId id :toolCallId (:id ev)
                              :content (:content ev) :role "tool"}))

    :run/end
    (-> s close-reasoning close-text
          (update :frames conj {:type "RUN_FINISHED"
                                :threadId (:thread-id s) :runId (:run-id s)}))

    :run/error
    (-> s close-reasoning close-text
          (update :frames conj {:type "RUN_ERROR" :message (:message ev)}))))

(defn outbound
  "Stateful converter. Returns (fn [kernel-event] -> vector of AG-UI wire frames)."
  [thread-id run-id]
  (let [s (atom {:thread-id thread-id :run-id run-id :n 0
                 :reasoning nil :text nil :frames []})]
    (fn [ev]
      (let [next (step (assoc @s :frames []) ev)]
        (reset! s (assoc next :frames []))
        (:frames next)))))
