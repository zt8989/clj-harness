(ns harness.ui.app
  "The page: one AG-UI agent wired straight to the harness, presented by CopilotKit.

  The browser talks to the harness directly -- there is no runtime in between, which is
  why the server carries CORS. One agent, registered as \"default\" so CopilotChat picks
  it up with no agentId.

  Two rendering slots are wired beyond CopilotKit's defaults:

  - `renderToolCalls` gets the built-in wildcard renderer so every tool call shows up
    as a card (name, arguments, status, result). Without a registered renderer
    CopilotChat renders NOTHING for tool calls -- the details ride the wire just fine,
    the default renderer registry is simply empty.
  - `messageView` wraps CopilotChatMessageView solely to pass it our reasoning
    component. CopilotChat does NOT forward a reasoningMessage prop down to the
    message view (the only slot it forwards is messageView itself), so a bare
    reasoningMessage prop here ends up spread onto a div and React rejects it. The
    wrapper is the one seam where the fine-grained slots are reachable.

  Above the chat sits the project panel: the session's project-directory binding,
  read from and written through the management edge (/api/project). The thread id
  comes off the agent itself -- CopilotKit assigns one per conversation and writes
  it onto the agent instance, so a new conversation after a stop reads as a fresh,
  unbound thread."
  (:require ["@ag-ui/client" :refer [HttpAgent]]
            ["@copilotkit/react-core/v2" :refer [CopilotChat CopilotChatMessageView
                                                 CopilotKit WildcardToolCallRender
                                                 useAgent UseAgentUpdate]]
            [goog.object :as gobj]
            [harness.ui.approval-gate :refer [ApprovalGate]]
            [harness.ui.reasoning-message :refer [reasoning-message]]
            [helix.core :refer [$ defnc]]
            [helix.hooks :as hooks]))

(defonce agent (HttpAgent. #js {:url "http://localhost:8080/"}))

;; The management edge lives on the same origin as the AG-UI endpoint.
(def ^:private harness-url "http://localhost:8080")

;; `renderToolCalls` must be a stable array (CopilotKit warns on per-render
;; churn), so it is built once here, not inside the component.
(defonce tool-renderers #js [WildcardToolCallRender])

(defnc message-view
  "CopilotChat's messageView slot. CopilotKit hands down exactly three props --
  pass them through untouched and add our reasoning slot. Keys are written
  camelCase ON PURPOSE: helix camel-cases keys only for DOM elements,
  custom-component props pass through verbatim.

  READ PROPS WITH KEYWORD DESTRUCTURING. helix runs the React props through
  `extract_cljs_props`, which wraps the JS object in a cljs-bean; `.-field`
  on the bean reads the bean's own internals and silently yields nil (this
  once blanked the whole message list). The prop VALUES (messages array,
  indicator object) are plain JS and pass through untouched."
  [{:keys [messages isRunning intelligenceIndicator]}]
  ($ CopilotChatMessageView
     {:messages messages
      :isRunning isRunning
      :intelligenceIndicator intelligenceIndicator
      :reasoningMessage reasoning-message}))

;; ---------------------------------------------------------------- project panel

;; Styles are Clojure maps: helix's `dom-props` camel-cases the keys for us.
(def ^:private panel-style
  {:border-bottom "1px solid #e8e8e8"
   :padding "10px 16px"
   :display "flex"
   :gap 8
   :flex-wrap "wrap"
   :align-items "center"
   :font-size 13
   :color "#1f1f1f"
   :background "#fafafa"})

(def ^:private muted-style {:color "#8c8c8c"})

(def ^:private input-style
  {:flex 1
   :min-width 240
   :border "1px solid #d9d9d9"
   :border-radius 6
   :padding "5px 8px"
   :font-size 13})

(def ^:private bind-style
  {:border "1px solid #1677ff"
   :background "#1677ff"
   :color "#fff"
   :padding "5px 12px"
   :border-radius 6
   :cursor "pointer"})

(def ^:private error-style {:color "#cf1322" :flex-basis "100%"})

(defn- fetch-binding!
  "GET /api/project for THREAD-ID. on-ok receives the dir string (nil = the
  unbound answer, not an error); on-error receives a message string."
  [thread-id on-ok on-error]
  (-> (js/fetch (str harness-url "/api/project?threadId=" (js/encodeURIComponent thread-id)))
      (.then (fn [^js resp]
               (-> (.json resp)
                   (.then (fn [^js data]
                            (if (.-ok resp)
                              (on-ok (.-dir data))
                              (on-error (or (.-error data) "读取绑定失败"))))))))
      (.catch (fn [^js e] (on-error (.-message e))))))

(defn- bind-dir!
  "POST /api/project {threadId, dir}. on-ok receives the ABSOLUTE path the
  server stored; on-error the validation message (no such directory, ...)."
  [thread-id dir on-ok on-error]
  (-> (js/fetch (str harness-url "/api/project")
                #js {:method "POST"
                     :headers #js {"Content-Type" "application/json"}
                     :body (js/JSON.stringify #js {:threadId thread-id :dir dir})})
      (.then (fn [^js resp]
               (-> (.json resp)
                   (.then (fn [^js data]
                            (if (.-ok resp)
                              (on-ok (.-dir data))
                              (on-error (or (.-error data) "绑定失败"))))))))
      (.catch (fn [^js e] (on-error (.-message e))))))

(defnc project-panel
  "One row above the chat: the thread's bound project directory, and an input
  to bind one. The thread id is read off the AGENT (`.-threadId`) -- the agent
  is a native AG-UI object, so `.-field` interop is correct here; the
  keyword-destructuring rule in message-view applies to COMPONENT PROPS, which
  this component has none of. No thread yet (before the first message) means
  nothing to bind and the panel says so."
  []
  (let [^js ctx   (useAgent #js {:agentId "default"
                                 :updates #js [(gobj/get UseAgentUpdate "OnRunStatusChanged")]})
        ^js agent (.-agent ctx)
        thread-id (.-threadId agent)
        [bound set-bound] (hooks/use-state nil)
        [path set-path]   (hooks/use-state "")
        [error set-error] (hooks/use-state nil)]

    ;; A new threadId (first run, or a fresh conversation after a stop) is a
    ;; different session with its own binding -- re-read, never guess.
    (hooks/use-effect [thread-id]
      (set-error nil)
      (if (seq thread-id)
        (fetch-binding! thread-id set-bound set-error)
        (set-bound nil)))

    ($ :div {:style panel-style}
       ($ :span {:style {:font-weight 600}} "项目目录")
       (cond
         (not (seq thread-id))
         ($ :span {:style muted-style} "会话开始后可绑定项目目录")

         :else
         ($ :span (or bound "未绑定（相对路径按进程工作目录解析）")))
       (when (seq thread-id)
         ($ :input {:style input-style
                    :placeholder "输入项目目录的绝对路径，例如 C:\\Users\\me\\my-project"
                    :value path
                    :on-change (fn [^js e] (set-path (.. e -target -value)))})
         ($ :button {:style bind-style
                     :on-click (fn [_]
                                 (when (seq path)
                                   (bind-dir! thread-id path
                                              (fn [_]
                                                (set-error nil)
                                                (set-path ""))
                                              set-error)))}
            "绑定"))
       (when error
         ($ :span {:style error-style} error)))))

;; --------------------------------------------------------------------- the page

(defnc App []
  ;; Two levels, two different rules, and getting either wrong unmounts the page.
  ;;
  ;; The outer props must be a Clojure map: helix's `$` decides what is props with
  ;; `map?`, and a `#js` literal reads as `false`, so it falls through as a *child* and
  ;; the component sees no props at all.
  ;;
  ;; The value must be a JS object: helix's `-props` only converts keys, not values, so
  ;; a nested Clojure map would arrive as a PersistentArrayMap. CopilotKit finds its
  ;; agents with `Object.keys({...agents})`, and spreading a Clojure map yields none of
  ;; its entries -- so it reads the registry as empty and throws a ConfigurationError.
  ($ CopilotKit {:agents__unsafe_dev_only #js {:default agent}
                 :renderToolCalls tool-renderers}
     ;; Column layout: the project panel takes its natural height, the chat
     ;; takes the rest. ApprovalGate renders nothing itself -- it MOUNTS here
     ;; so its useInterrupt registers inside the CopilotKit context, and the
     ;; parked-call card is published into CopilotChat from there.
     ($ :div {:style {:height "100vh" :display "flex" :flex-direction "column"}}
        ($ ApprovalGate)
        ($ project-panel)
        ($ :div {:style {:flex 1 :min-height 0}}
           ($ CopilotChat {:messageView message-view})))))
