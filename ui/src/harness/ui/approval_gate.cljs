(ns harness.ui.approval-gate
  "The human end of pre-tool approval.

  A run that parks a call for approval ends with RUN_FINISHED carrying
  outcome.interrupts. `useInterrupt` receives that, and `resolve` / `cancel`
  send the answer back as a spec `resume` array -- the client re-runs the same
  thread and the server replays the decision. Nothing here talks to the server
  directly, and nothing here knows what an interrupt id means.

  renderInChat stays at its default, so the card is published into <CopilotChat>
  rather than hand-placed: there is no UI kit in this project to hang a dialog
  on, and the chat stream is where the parked call belongs anyway."
  (:require ["@copilotkit/react-core/v2" :refer [useAgent useInterrupt UseAgentUpdate]]
            [goog.object :as gobj]
            [helix.core :refer [$ defnc]]
            [helix.hooks :as hooks]))

;; The interrupt reason this gate owns; any other interrupt is left alone.
(def ^:private reason "tool-approval")

;; Styles are Clojure maps: helix's `dom-props` camel-cases the keys for us.
(def ^:private card-style
  {:border "1px solid #d9d9d9"
   :border-radius 8
   :padding "12px 14px"
   :margin "8px 0"
   :background "#fffdf5"
   :color "#1f1f1f"
   :font-size 14})

(def ^:private title-style {:font-weight 600 :margin-bottom 6})

(def ^:private code-style {:background "#f0f0f0" :padding "2px 6px" :border-radius 4})

(def ^:private args-style
  {:margin "6px 0 0"
   :white-space "pre-wrap"
   :word-break "break-all"
   :background "#f7f7f7"
   :padding 8
   :border-radius 4
   :font-size 12})

(def ^:private message-style {:color "#595959" :margin-bottom 10})

(def ^:private row-style {:display "flex" :gap 8})

(def ^:private approve-style
  {:border "1px solid #1677ff"
   :background "#1677ff"
   :color "#fff"
   :padding "6px 14px"
   :border-radius 6
   :cursor "pointer"})

(def ^:private veto-style
  {:border "1px solid #d9d9d9"
   :background "#fff"
   :color "#1f1f1f"
   :padding "6px 14px"
   :border-radius 6
   :cursor "pointer"})

(def ^:private note-style {:color "#8c8c8c" :margin-top 8 :font-size 12})

(defn- tool-call-for
  "The call an interrupt is about, read from the client's own message list: the
  tool-call frames already carry the name and the arguments, so the card can
  show the exact command without the server echoing it back."
  [messages tool-call-id]
  (when tool-call-id
    (some (fn [^js message]
            (some (fn [^js call]
                    (when (= (.-id call) tool-call-id) call))
                  (array-seq (or (.-toolCalls message) #js []))))
          (array-seq (or messages #js [])))))

(defn- approval-card
  "One parked call, drawn as an element. A plain function, not a component: it
  has no state and no reason to be re-mounted on its own."
  [^js interrupt ^js call approve! veto!]
  (let [^js call-fn (when call (.-function call))]
    ($ :div
       {:style card-style}
       ($ :div {:style title-style} "需要你批准这次工具调用")
       ($ :div
          {:style {:margin-bottom 6}}
          ($ :code {:style code-style} (or (some-> call-fn (.-name)) "tool"))
          (when-some [args (some-> call-fn (.-arguments))]
            ($ :pre {:style args-style} args)))
       (when-some [message (.-message interrupt)]
         ($ :div {:style message-style} message))
       ($ :div
          {:style row-style}
          ($ :button {:style approve-style :on-click (fn [_] (approve! #js {:decision "approved"}))} "批准")
          ($ :button {:style veto-style :on-click (fn [_] (veto!))} "否决"))
       ($ :div {:style note-style}
          "批准则照常执行；否决则工具不执行，模型会收到一条被人工否决的工具结果并继续。"))))

(defnc ApprovalGate []
  (let [^js ctx (useAgent #js {:agentId "default"
                               ;; `UseAgentUpdate` is a JS enum, not a map:
                               ;; the member has to come off the object.
                               :updates #js [(gobj/get UseAgentUpdate "OnRunStatusChanged")]})
        ;; Pulled out rather than destructured: the type hint is what keeps the interop
        ;; calls below from each drawing an infer-warning, and destructuring drops it.
        ^js agent (.-agent ctx)
        ;; A run waiting on a decision is a run holding a thread on the server. If this
        ;; gate unmounts mid-executing the interrupt renderer goes with it and nothing
        ;; would ever answer, so abort. The ref keeps the cleanup reading the latest
        ;; status without re-firing on every flip.
        running (hooks/use-ref false)]

    (hooks/use-effect [agent (.-isRunning agent)]
      (set! (.-current running) (.-isRunning agent)))

    (hooks/use-effect [agent]
      ;; Returning a function is the cleanup: `wrap-fx` passes it straight to React.
      (fn [] (when (.-current running) (.abortRun agent))))

    (useInterrupt
     #js {:agentId "default"
          ;; Another renderer may own other interrupts; this gate answers only
          ;; approvals.
          :enabled (fn [^js event]
                     (= reason (some-> event (.-value) (.-reason))))
          :render (fn [^js opts]
                    ;; `resolve` is shadowed by clojure.core/resolve, so the callbacks
                    ;; are handed on to a function that names what they do.
                    (approval-card (.-interrupt opts)
                                   (tool-call-for (.-messages agent)
                                                  (.-toolCallId (.-interrupt opts)))
                                   (.-resolve opts)
                                   (.-cancel opts)))})

    ;; It renders inside CopilotChat; there is nothing to place.
    nil))
