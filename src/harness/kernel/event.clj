(ns harness.kernel.event
  "The kernel's whole vocabulary: thirteen event kinds. Everything AG-UI-shaped
  is derived from these by harness.edge.ag-ui, never produced here; five kinds
  carry no wire frame at all -- the three tool-lifecycle ones (:tool/pre-execute,
  :tool/execute, :tool/post-execute) and the two model-call boundaries
  (:model/start, :model/end) -- and the http edge turns those into jsonl audit
  lines, never into AG-UI frames.

  :run/interrupt is the second terminal event: a run that parks calls for a
  human decision ends with it instead of :run/end, so exactly one of the two
  closes any run.")

(defn run-start [] {:type :run/start})
(defn text-delta [text] {:type :text/delta :text text})
(defn reasoning-delta [text] {:type :reasoning/delta :text text})

(defn tool-call
  "ARGS is the fully accumulated argument text, not a fragment."
  [id name args] {:type :tool/call :id id :name name :args args})

(defn tool-result [id content error?]
  {:type :tool/result :id id :content content :error error?})

(defn tool-pre-execute
  "One tool call entered the execution seam. OUTCOME is :pass, :unknown-tool,
  :unserved, :disabled, :missing-args, :hook-blocked, :needs-approval, :approved,
  or :vetoed. :disabled is a session's switch: the tool exists and is on offer,
  but this session turned its availability off, so the call is refused outright
  -- never parked, never executed. :unserved is the editing mode's subtraction:
  the tool exists and is registered, but this session's editing mode serves the
  OTHER editing toolset, so the call is refused by name with the substitute and
  the config key that switches back (see harness.cap.editing). :hook-blocked is the
  user's own rulebook saying no: a PreToolUse hook exited 2, the tool does NOT
  run, and the hook's stderr is what the model reads. :needs-approval parks the
  call for a human decision: it does not execute, and its :tool/post-execute
  still closes this transit of the seam immediately. A decided call crosses the
  seam a SECOND time -- :approved executes it, :vetoed answers it without
  executing -- so one toolCallId can carry two pre-execute lines; read them in
  time order. MISSING names the absent required arguments on :missing-args."
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

(defn model-start
  "One model call BEGINS. PROVIDER is the resolved provider map, and what is kept
  from it is the call's IDENTITY -- who this call went to -- never its body: the
  messages are already `message` lines, and a second copy of them would be the
  same fact written twice.

  The three knobs are recorded AS THE VENDOR SPELLS THEM (:model, :base-url,
  :reasoning-effort), and A KEY IS WRITTEN ONLY WHEN THE PROVIDER HAS IT -- for
  :reasoning-effort that is 'this request is not in thinking mode', and for the
  other two it is 'this provider named none', which is not the same statement as
  a null. A base-url is not a secret -- `provider/init` records it already.

  IT PAIRS WITH :model/end BY ORDER: the nth :model/start of a run is that run's
  nth call. A counter in the record would be the same fact written a second
  time, and two copies drift."
  [provider]
  (cond-> {:type :model/start}
    (:model provider)            (assoc :model (:model provider))
    (:base-url provider)         (assoc :base-url (:base-url provider))
    (:reasoning-effort provider) (assoc :reasoning-effort (:reasoning-effort provider))))

(defn model-end
  "One model call is OVER, and TELEMETRY is what the vendor reported back --
  {:usage …, :finish-reason …, :model …} -- kept VERBATIM: the vendor's own key
  names inside :usage are not renamed on the way into the record. What those
  numbers mean is the record reader's business, not the kernel's.

  AN EMPTY TELEMETRY IS STILL AN EVENT. A call that died mid-stream reports
  nothing, and this line is what says so -- without it a stalled call cannot be
  told from one that never ended."
  [telemetry]
  (merge {:type :model/end} telemetry))

(defn run-end [] {:type :run/end})

(defn run-interrupt
  "The run stops with calls parked pending a human decision. INTS carries the
  parked calls in call order, facts only -- {:id <interrupt-id> :tool-call-id ..
  :name .. :args <json-string>}. The id is the correlation key the client hands
  back on resume. Terminal: a run emits this or :run/end, never both."
  [ints] {:type :run/interrupt :interrupts (vec ints)})

(defn run-error [message] {:type :run/error :message message})
