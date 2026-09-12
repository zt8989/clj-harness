(ns harness.event
  "The kernel's whole vocabulary: ten event kinds. Everything AG-UI-shaped
  is derived from these by harness.ag-ui, never produced here; the three
  tool-lifecycle kinds (:tool/pre-execute, :tool/execute, :tool/post-execute)
  carry no wire frame at all -- the http edge turns them into jsonl audit
  lines, never into AG-UI frames.")

(defn run-start [] {:type :run/start})
(defn text-delta [text] {:type :text/delta :text text})
(defn reasoning-delta [text] {:type :reasoning/delta :text text})

(defn tool-call
  "ARGS is the fully accumulated argument text, not a fragment."
  [id name args] {:type :tool/call :id id :name name :args args})

(defn tool-result [id content error?]
  {:type :tool/result :id id :content content :error error?})

(defn tool-pre-execute
  "One tool call entered the execution seam. OUTCOME is :pass, :unknown-tool or
  :missing-args; MISSING names the absent required arguments on the latter."
  [id name outcome missing]
  {:type :tool/pre-execute :id id :name name :outcome outcome :missing missing})

(defn tool-executed
  "One tool call left execution. ERROR is the exception's message, or nil."
  [id name error]
  {:type :tool/execute :id id :name name :error error})

(defn tool-post-execute
  "One tool call is done with the seam entirely -- it always closes the call's
  lifecycle, whatever the earlier phases decided."
  [id name] {:type :tool/post-execute :id id :name name})

(defn run-end [] {:type :run/end})
(defn run-error [message] {:type :run/error :message message})
