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

  Above the chat sit the project panel (the session's project-directory binding)
  and the session panel (the conversations the harness log directory holds).
  The thread id comes off the agent itself -- CopilotKit assigns one per
  conversation and writes it onto the agent instance, so a new conversation
  after a stop reads as a fresh, unbound thread.

  Restoring a session (ticket 06) is deliberately AGENT-ONLY: the rebuilt thread
  id and message list are written onto the agent, and nothing else is touched.
  That works because AbstractAgent builds its RunAgentInput from its own state
  (prepareRunAgentInput: threadId + messages off the agent), so the next input
  continues the restored thread as an ordinary AG-UI run. The CopilotKit-level
  explicit-thread path (setActiveThreadId) is avoided on purpose -- it drags in
  connectAgent handshakes and message-clearing rules that a client talking
  straight to the harness has no use for."
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
                                 ;; OnMessagesChanged so a session restore (setMessages)
                                 ;; re-renders the panel and re-reads the thread binding
                                 :updates #js [(gobj/get UseAgentUpdate "OnRunStatusChanged")
                                               (gobj/get UseAgentUpdate "OnMessagesChanged")]})
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

;; ---------------------------------------------------------------- session panel

(def ^:private row-style
  {:display "flex"
   :gap 10
   :align-items "center"
   :font-size 12
   :flex-basis "100%"})

(def ^:private ghost-style
  {:border "1px solid #d9d9d9"
   :background "#fff"
   :color "#1677ff"
   :padding "2px 10px"
   :border-radius 6
   :cursor "pointer"
   :font-size 12})

(defn- fmt-bytes [n]
  (cond
    (< n 1024)    (str n " B")
    (< n 1048576) (str (.toFixed (/ n 1024) 1) " KB")
    :else         (str (.toFixed (/ n 1048576) 1) " MB")))

(defn- fetch-threads!
  "GET /api/threads -- on-ok receives the JS array of {threadId, lastActivity,
  bytes} (a JSON array at the top level, so there is no field to unwrap);
  on-error a message string."
  [on-ok on-error]
  (-> (js/fetch (str harness-url "/api/threads"))
      (.then (fn [^js resp]
               (-> (.json resp)
                   (.then (fn [^js data]
                            (if (.-ok resp)
                              (on-ok data)
                              (on-error (or (.-error data) "读取会话列表失败"))))))))
      (.catch (fn [^js e] (on-error (.-message e))))))

(defn- restore-thread!
  "POST /api/threads/<stem>/rebuild, then hand the conversation back to the
  CLIENT: the rebuilt thread id and message list both land on the AGENT, which
  is exactly what the next run reads. A 400 (truncated or corrupt log) carries
  the server's named reason to on-error -- the panel stays alive either way."
  [agent thread-id on-ok on-error]
  (-> (js/fetch (str harness-url "/api/threads/" (js/encodeURIComponent thread-id) "/rebuild")
                #js {:method "POST"})
      (.then (fn [^js resp]
               (-> (.json resp)
                   (.then (fn [^js data]
                            (if (.-ok resp)
                              (do (set! (.-threadId agent) (.-threadId data))
                                  (.setMessages agent (.-messages data))
                                  (on-ok (.-threadId data)))
                              (on-error (or (.-error data) "恢复失败"))))))))
      (.catch (fn [^js e] (on-error (.-message e))))))

(defnc session-panel
  "The conversations the log directory holds, and the way back into one.

  恢复 hands the rebuilt history to the agent (thread id + messages) -- the
  client re-owns the conversation, and the next input continues it as an
  ordinary AG-UI run that appends to the SAME log. A refused rebuild shows the
  named reason inline while every other row stays clickable. 新建会话 starts a
  fresh thread the same agent-owned way: a new id, empty messages.

  Subscribed to OnMessagesChanged as well as OnRunStatusChanged so the panel
  re-renders when a restore lands (setMessages is a message change, not a run
  status change)."
  []
  (let [^js ctx   (useAgent #js {:agentId "default"
                                 :updates #js [(gobj/get UseAgentUpdate "OnRunStatusChanged")
                                               (gobj/get UseAgentUpdate "OnMessagesChanged")]})
        ^js agent (.-agent ctx)
        [sessions set-sessions] (hooks/use-state nil)
        [error    set-error]    (hooks/use-state nil)
        [busy     set-busy]     (hooks/use-state nil)
        current   (.-threadId agent)

        refresh
        (fn []
          (fetch-threads! (fn [rows]
                            (set-sessions rows)
                            (set-error nil))
                          set-error))

        restore
        (fn [thread-id]
          (if (.-isRunning agent)
            (set-error "有正在进行的运行，等它结束再恢复。")
            (do (set-busy thread-id)
                (restore-thread! agent thread-id
                                 (fn [_]
                                   (set-busy nil)
                                   (set-error nil)
                                   ;; the rebuild just appended its audit line to the
                                   ;; log -- re-list so sizes and order stay honest
                                   (fetch-threads! #(set-sessions %) set-error))
                                 (fn [msg]
                                   (set-busy nil)
                                   (set-error msg))))))

        new-session
        (fn []
          (set! (.-threadId agent) (str (js/crypto.randomUUID)))
          (.setMessages agent #js [])
          (set-error nil))]

    (hooks/use-effect [] (refresh))

    ($ :div {:style panel-style}
       ($ :span {:style {:font-weight 600}} "会话")
       ($ :button {:style ghost-style :on-click (fn [_] (refresh))} "刷新")
       ($ :button {:style ghost-style :on-click (fn [_] (new-session))} "新建会话")
       ($ :span {:style muted-style} "恢复后历史归本页持有，续聊照常走 AG-UI")
       (when error
         ($ :span {:style error-style} error))
       (cond
         (nil? sessions) nil
         (empty? sessions) ($ :span {:style muted-style} "日志目录还没有会话")
         :else
         (into-array
          (map (fn [^js t]
                 (let [tid (.-threadId t)]
                   ($ :span {:key tid :style row-style}
                      ($ :span {:style (if (= tid current)
                                         {:color "#1677ff" :font-weight 600}
                                         muted-style)}
                         tid)
                      ($ :span {:style muted-style}
                         (str (-> (js/Date. (.-lastActivity t)) (.toLocaleString))
                              " · " (fmt-bytes (.-bytes t))))
                      ($ :button {:style ghost-style
                                  :disabled (= busy tid)
                                  :on-click (fn [_] (restore tid))}
                         (if (= busy tid) "恢复中…" "恢复")))))
               sessions))))))

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
        ($ session-panel)
        ($ :div {:style {:flex 1 :min-height 0}}
           ($ CopilotChat {:messageView message-view})))))
