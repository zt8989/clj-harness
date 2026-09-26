(ns harness.cap.instruction-updates
  "WHAT THIS PROCESS LAST SAID AS A SESSION'S SYSTEM MESSAGE, and whether it still
  holds -- the memory, the comparison and the delivery plan behind
  `.scratch/instruction-updates`.

  THE PROBLEM IT ANSWERS. The system message is assembled fresh every run
  (`harness.cap.system-prompt/assemble*`), and until this namespace existed nothing
  could say 'this run's instructions are the ones we sent last run'. So a change --
  a hook switched, a tool added -- silently replaced `message[0]` and the whole
  conversation was prefilled from token 0. The fact was never wrong; it was just
  never NAMED, so nobody could tell 'moved' from 'same' and nobody could choose how
  to deliver it. This namespace gives the change a name and a memory.

  THE NAME IS TWO SETS, NOT A TEXT (`decision 1`). The instruction signature is
  the hooks in force at the SystemPrompt point and the names of the tools the
  session offers, each hashed. Comparing the ASSEMBLED TEXT would require running
  every hook to produce it -- and a hook can be a shell command -- which is the very
  cost a run that changed nothing must not pay. Comparing the two name sets costs a
  registry lookup and answers the question that matters: did the SET of things that
  assemble the message move. Re-describing a tool does not move it; adding or
  removing one does. The signature also carries the two facts whose CONTENT cannot
  be a name set but whose change has an explicit door: the project binding (whose
  block states a fence, so a bind! that left it out would make the message lie) and
  `harness.kernel.llm/prompt-epoch` (prompt.md is frozen in-process; `reset-prompt!`
  is the door). This is wider than `decision 6` claimed -- it said the binding could
  not move mid-session, but `POST /api/project` does exactly that -- and the two name
  hashes it named are unchanged.

  TWO CALLERS, ONE FOLD. `harness.edge.http` calls `plan` on the run path (INSIDE
  the hook sink binding, because assembly fires the point) and `commit!` after the
  send; `harness.edge.ag-ui/place-updates` splices the updates into the array.
  NOTHING IS WRITTEN DOWN: the memory is a `defonce` atom, per thread-id, beside
  the edge's other process facts, and a restart simply reassembles once -- a cold
  prefix, then back to normal. See decision 2."
  (:require [harness.cap.project :as project]
            [harness.cap.system-prompt :as system-prompt]
            [harness.kernel.llm :as llm]
            [harness.kernel.tools :as tools]))

;; ------------------------------------------------------------------ the signature

(defn signature
  "THREAD-ID's instruction signature: the facts that decide whether the system
  message must be assembled again this run.

  THE TWO NAME HASHES ARE THE OWNER'S SIGNATURE. `:hooks-names-hash` is computed
  WITHOUT RUNNING ANY HOOK (`harness.cap.system-prompt/hooks-names-hash`), and
  `:tools-names-hash` comes off the same resolved table the request will carry
  (`harness.kernel.tools/names-hash`, which sorts the names and never looks at a
  description). `:project-dir` and `:prompt-epoch` are the two content facts with
  an explicit door -- see this namespace's docstring for why they belong here.

  IT IS CHEAP ON PURPOSE. Nothing here reads a record, runs a hook or measures
  bytes: a run that changed nothing must be able to say so before assembly."
  [thread-id]
  {:hooks-names-hash (system-prompt/hooks-names-hash thread-id)
   :tools-names-hash  (some-> (tools/specs thread-id) tools/default-signature
                              :tools-names-hash)
   :project-dir       (project/binding-for thread-id)
   :prompt-epoch      (llm/prompt-epoch)})

;; --------------------------------------------------------------------- the memory

(defonce ^:private sent
  (atom {}))
;; thread-id -> {:sig {..} :text ".." :hooks-names-hash ".."
;;               :system {:content ".." :hash ".." :hooks-names-hash ".."}
;;               :updates [".."] :mode :in-place|:replace}
;;
;; WHAT THE MODEL READ AS ITS SYSTEM MESSAGE LAST RUN and what that message was
;; assembled from. Per thread-id, because the memory is about one conversation's
;; prefix; in-process only, because it is 'what I just said', not a fact about the
;; world (decision 2). `:system` describes message[0] AS SENT -- under :in-place it
;; is the frozen text the model is still reading, which is why a hook change does
;; not make it a different message. `:text`/`:hooks-names-hash` are the NEWEST
;; assembly, which is what the next signature is compared against.

(defn remembered
  "THREAD-ID's memory, or nil -- the exact map `plan` reads and `commit!` writes.
  Exposed for a caller that wants to ask what this process believes it said."
  [thread-id]
  (get @sent thread-id))

(defn forget!
  "Drop the memory for THREAD-ID, or for every thread with no argument.

  THE EXPLICIT DOOR, and there is exactly one: a test that wants a cold start, a
  teardown, and -- through the prompt epoch -- a person who re-read prompt.md. There
  is no background expiry, because 'I forgot' is not a fact about the conversation."
  ([] (reset! sent {}))
  ([thread-id] (swap! sent dissoc thread-id) nil))

;; ---------------------------------------------------------------------- the plan

(defn plan
  "THREAD-ID + the DELIVERY its endpoint supports -> what this run should send and
  remember:

    {:mode        :in-place | :replace
     :system      {:content .. :hash .. :hooks-names-hash ..}   ; message[0]
     :updates     [\"..\" ..]    ; developer texts (in-place only)
     :changed?    true|false    ; the signature MOVED since the last send
     :sig         {..}          ; compared next run
     :text        \"..\"         ; the newest assembly, in force
     :hooks-names-hash \"..\"
     :fresh-system {..}         ; what a :replace send would use (the fallback)
     :reused?     true|false}   ; true = assembly was skipped

  ASSEMBLY (AND THEREFORE THE HOOKS) RUNS ONLY WHEN THE SIGNATURE MOVED OR NOTHING IS
  REMEMBERED. A run that changed nothing reuses the text it handed over last time --
  hooks are not re-run, which is the whole point of comparing name sets.

  THE FIRST RUN IS NOT A CHANGE (decision 4). With no memory there is nothing to
  reuse, so this assembles, but `:changed?` is false: the model has never been told
  anything, so there is nothing to UPDATE, and reporting the first assembly as a
  move would put a developer message on every session's first turn.

  A THROW FROM `assemble*` (a hook that refuses the run) LEAVES THE MEMORY UNTOUCHED
  (decision 5): `commit!` is the only writer, and it is called after the send. What
  never went out is not 'what we said last'.

  IT MUST RUN INSIDE THE EDGE'S HOOK SINK BINDING -- `assemble*` fires the
  SystemPrompt point, and an unbound sink assembles nothing.

  ONE THREAD, ONE RUN (decision 7): `harness.edge.sessions` serializes a thread's
  runs, so the read-then-commit pair needs no lock. Two threads are two keys."
  [thread-id delivery]
  (let [memory  (remembered thread-id)
        sig     (signature thread-id)
        first?  (nil? memory)
        moved?  (boolean (and memory (not= sig (:sig memory))))
        reuse?  (boolean (and memory (not moved?)))
        fresh   (when-not reuse? (system-prompt/assemble* thread-id))
        text    (if reuse? (:text memory) (:text fresh))
        hooks   (if reuse? (:hooks-names-hash memory) (:hooks-names-hash fresh))
        fresh-system {:content          text
                      :hash             (system-prompt/digest text)
                      :hooks-names-hash hooks}]
    (if (= :in-place delivery)
      ;; IN-PLACE: message[0] is what the model already read -- the frozen text --
      ;; and every move rides the tail as a developer message carrying the FULL new
      ;; text (the vendor's instruction slot is an overwrite, and a delta would make
      ;; the reader merge two sources; decision 3).
      {:mode             :in-place
       :system           (or (:system memory) fresh-system)
       :updates          (cond
                           first?  []
                           moved?  (conj (vec (:updates memory)) text)
                           :else   (vec (:updates memory)))
       :changed?         moved?
       :sig              sig
       :text             text
       :hooks-names-hash hooks
       :fresh-system     fresh-system
       :reused?          reuse?}
      ;; REPLACE (the default): the newest text IS message[0] and no update rides
      ;; the tail -- today's behaviour, written down rather than left implicit.
      {:mode             :replace
       :system           fresh-system
       :updates          []
       :changed?         moved?
       :sig              sig
       :text             text
       :hooks-names-hash hooks
       :fresh-system     fresh-system
       :reused?          reuse?})))

(defn fallback
  "PLAN -> the :replace plan for a run whose history could not take a developer
  message before its last user message (a pathological history -- the last message
  is not a user turn). The fresh text becomes message[0] and the chain is dropped,
  so the model reads the current instructions even though the delivery could not be
  in-place; the caller logs the fallback (ticket 02, decision 3: do not guess)."
  [plan]
  (assoc plan :mode :replace :system (:fresh-system plan) :updates []))

(defn commit!
  "Remember what this run ACTUALLY sent -- call it after the send succeeded.

  The :system map is message[0] AS IT WENT OUT (frozen under :in-place, the fresh
  text under :replace); :updates is the chain that rode the tail; :text and
  :hooks-names-hash are the newest assembly, so the NEXT run's signature is compared
  against what this run knew, not against what it happened to send."
  [thread-id {:keys [sig text hooks-names-hash system updates mode]}]
  (swap! sent assoc thread-id
         {:sig              sig
          :text             text
          :hooks-names-hash hooks-names-hash
          :system           system
          :updates          (vec updates)
          :mode             mode})
  nil)
