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
    wrapper is the one seam where the fine-grained slots are reachable."
  (:require ["@ag-ui/client" :refer [HttpAgent]]
            ["@copilotkit/react-core/v2" :refer [CopilotChat CopilotChatMessageView CopilotKit WildcardToolCallRender]]
            [harness.ui.approval-gate :refer [ApprovalGate]]
            [harness.ui.reasoning-message :refer [reasoning-message]]
            [helix.core :refer [$ defnc]]))

(defonce agent (HttpAgent. #js {:url "http://localhost:8080/"}))

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
     ($ :div {:style {:height "100vh"}}
        ;; Answers the harness's pre-tool approval interrupts; renders inside
        ;; CopilotChat, so it has no place in the layout.
        ($ ApprovalGate)
        ($ CopilotChat {:messageView message-view}))))
