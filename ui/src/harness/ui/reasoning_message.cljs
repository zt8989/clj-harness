(ns harness.ui.reasoning-message
  "The reasoning slot of CopilotChat, replaced for one behavior: the default
  component collapses the content to \"Thought for Xs\" the moment streaming
  ends, and the content is only visible again by clicking. Here the content
  STAYS EXPANDED after the run finishes; manual collapse still works, and a
  new thinking phase re-opens it exactly like the default does.

  Everything else mirrors the default component: same static Header / Toggle /
  Content children from CopilotChatReasoningMessage, same \"Thinking…\" label
  while streaming, same elapsed-time format once it stops.

  READ PROPS WITH KEYWORD DESTRUCTURING. helix wraps the React props in a
  cljs-bean before the body sees them, so `.-field` on the props bag reads
  the bean's internals and silently yields nil. The prop VALUES (message,
  messages) are plain JS objects -- `.-field` / `aget` on those is correct."
  (:require ["@copilotkit/react-core/v2" :refer [CopilotChatReasoningMessage]]
            [helix.core :refer [$ defnc]]
            [helix.hooks :as hooks]))

(defn- format-duration
  "Same shape as the default's formatter: \"a few seconds\" below one
  second, whole seconds below a minute, then minutes-and-seconds."
  [seconds]
  (let [total (js/Math.round seconds)]
    (cond
      (< total 1) "a few seconds"
      (< total 60) (str total " seconds")
      :else (let [mins (quot total 60)
                  rem  (mod total 60)]
              (if (zero? rem)
                (str mins (if (= mins 1) " minute" " minutes"))
                (str mins "m " rem "s"))))))

(defnc reasoning-message
  "Props: {message, messages, isRunning} as handed down by CopilotChatMessageView."
  [{:keys [message messages isRunning]}]
  (let [^js message message
        ^js messages messages
        latest? (and messages
                     (= (.-id message)
                        (.-id (aget messages (dec (.-length messages))))))
        streaming? (boolean (and isRunning latest?))
        content (.-content message)
        has-content? (boolean (and (string? content) (pos? (.-length content))))
        [open? set-open!] (hooks/use-state true)
        start (hooks/use-ref nil)
        [elapsed set-elapsed!] (hooks/use-state 0)]

    ;; A new thinking phase opens up, like the default. The END of one does not
    ;; close it -- that is the whole point of this component: the close path
    ;; here is manual only (the Header's onClick).
    (hooks/use-effect [streaming?]
      (when streaming?
        (set-open! true)))

    ;; Elapsed timer: starts with streaming, freezes when it stops.
    (hooks/use-effect [streaming?]
      (cond
        streaming? (do (when (nil? (.-current start))
                         (set! (.-current start) (js/Date.now)))
                       (let [timer (js/setInterval
                                    #(set-elapsed! (/ (- (js/Date.now) (.-current start)) 1000))
                                    1000)]
                         (fn [] (js/clearInterval timer))))
        (some? (.-current start))
        (do (set-elapsed! (/ (- (js/Date.now) (.-current start)) 1000))
            nil)))

    ($ :div {:className "cpk:my-1" :data-message-id (.-id message)}
       ($ (.-Header CopilotChatReasoningMessage)
          {:isOpen open?
           :label (if streaming?
                    "Thinking…"
                    (str "Thought for " (format-duration elapsed)))
           :hasContent has-content?
           :isStreaming streaming?
           :onClick (when has-content?
                      (fn []
                        (set-open! not)))})
       ($ (.-Toggle CopilotChatReasoningMessage)
          {:isOpen open?}
          ($ (.-Content CopilotChatReasoningMessage)
             {:isStreaming streaming? :hasContent has-content?}
             content)))))
