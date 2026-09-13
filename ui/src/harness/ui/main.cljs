(ns harness.ui.main
  "Browser entry: owns the React root only. The page itself is harness.ui.app, kept in
  its own namespace so a hot reload can swap it without tearing the root down and
  starting over.

  Mounting on load *is* the entry, and that is not a style choice. shadow-cljs's :esm
  target publishes `^:export` vars onto the global object rather than onto the module,
  so the compiled build has no importable handle to call -- whoever imports this
  namespace can only import it for effect."
  (:require ["react" :as react]
            ["react-dom/client" :as react-dom]
            [harness.ui.app :refer [App]]
            [helix.core :refer [$]]))

(defonce ^:private root (atom nil))

(defn- mount []
  (when-not @root
    (reset! root (react-dom/createRoot (js/document.getElementById "root"))))
  (.render ^js @root ($ react/StrictMode nil ($ App))))

;; A hot reload of *any* namespace lands here, app.cljs included -- and app.cljs is
;; where the components live, so re-rendering on every reload is the point. The
;; defonce root is what survives it.
(mount)

(defn ^:dev/after-load reload []
  (mount))
