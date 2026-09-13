(ns harness.ui.app
  "The page: one AG-UI agent wired straight to the harness, presented by CopilotKit.

  The browser talks to the harness directly -- there is no runtime in between, which is
  why the server carries CORS. One agent, registered as \"default\" so CopilotChat picks
  it up with no agentId."
  (:require ["@ag-ui/client" :refer [HttpAgent]]
            ["@copilotkit/react-core/v2" :refer [CopilotChat CopilotKit]]
            [harness.ui.approval-gate :refer [ApprovalGate]]
            [helix.core :refer [$ defnc]]))

(defonce agent (HttpAgent. #js {:url "http://localhost:8080/"}))

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
  ($ CopilotKit {:agents__unsafe_dev_only #js {:default agent}}
     ($ :div {:style {:height "100vh"}}
        ;; Answers the harness's pre-tool approval interrupts; renders inside
        ;; CopilotChat, so it has no place in the layout.
        ($ ApprovalGate)
        ($ CopilotChat))))
