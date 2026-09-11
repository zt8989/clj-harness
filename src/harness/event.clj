(ns harness.event
  "The kernel's whole vocabulary: seven event kinds. Everything AG-UI-shaped
  is derived from these by harness.ag-ui, never produced here.")

(defn run-start [] {:type :run/start})
(defn text-delta [text] {:type :text/delta :text text})
(defn reasoning-delta [text] {:type :reasoning/delta :text text})

(defn tool-call
  "ARGS is the fully accumulated argument text, not a fragment."
  [id name args] {:type :tool/call :id id :name name :args args})

(defn tool-result [id content error?]
  {:type :tool/result :id id :content content :error error?})

(defn run-end [] {:type :run/end})
(defn run-error [message] {:type :run/error :message message})
