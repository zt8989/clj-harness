(ns harness.cap.system-prompt
  "The system message a run sends, ASSEMBLED: prompt.md's frozen opening plus the
  text that every hook at the SystemPrompt point appends, in order.

  THE OPENING IS FROZEN, THE REST IS NOT, and that split is the whole point. What
  prompt.md holds is the part of the message that is true of every session --
  identity, the secrets discipline, 'the rest you can read for yourself'. What the
  hooks append is the part that is true of THIS session: which tools are in force,
  which directory it is bound to, which model is serving it. A fact written into
  the frozen file is a fact that starts going stale the moment it is written down;
  a fact derived at assembly time cannot.

  WHY THIS IS NOT PART OF harness.cap.preamble, WHICH OWNS THE OTHER HALF. A run opens
  in two halves -- this one (role system) and harness.cap.preamble's (the instruction
  files and the skills catalog, role user) -- and the halves cannot interleave,
  because they are different message roles. So 'the order has exactly one decider'
  holds inside each half, and each half has its own owner. What decides the owner
  is REQUIRE: the hooks below need the live tool table (harness.kernel.tools), the
  session's binding (harness.cap.project) and the provider (harness.cap.providers), while
  harness.cap.project already requires harness.cap.preamble for instruction-files -- so
  folding this half in there would be preamble -> tools -> project -> preamble, and
  triggering the point adds hooks.dispatch to the same loop (hooks -> project ->
  preamble). The other evidence sits on the preamble side: instruction-files takes
  (cfg, project-dir) instead of looking up the binding itself, precisely to dodge
  this cycle.

  WHY IT IS ASSEMBLED PER RUN RATHER THAN FROZEN PER SESSION. Every fact a hook
  appends can move mid-session -- tools/session-disable!, project/bind!,
  session-configure -- so a copy taken once would eventually be a sentence that is
  no longer true. This is the same discipline as config.edn, harness.edn and
  AGENTS.md, all of which are read fresh on every run. The price
  of a fact that moved is ONE cold prefix; the price of freezing it is a system
  message that lies.

  AND WHEN NOTHING MOVED, NOTHING MOVES. The assembly is a pure function of those
  facts, so two runs over the same facts produce byte-identical text and the
  provider's prefix cache keeps hitting. That is asserted, not assumed.

  IT ALSO REGISTERS THE KERNEL'S OWN HOOKS. Building one needs the three
  namespaces above, which is exactly why they are registered from here rather than
  declared in harness.kernel.hooks -- that namespace owns the registry as a seam, and this
  is its only writer. They are rows in the same table as any declared hook: visible
  in effective-hooks, switchable with session-disable!, audited like everything
  else. Killing a hook is not the same as killing the rule, and the division is
  clean: what the hooks append can be switched off, what prompt.md states cannot --
  prompt.md is not in a hook's hands."
  (:require [clojure.string :as str]
            [harness.kernel.hooks :as hooks]
            [harness.kernel.hooks.dispatch :as hook]
            [harness.kernel.llm :as llm]
            [harness.cap.project :as project]
            [harness.cap.providers :as providers]
            [harness.kernel.tools :as tools]))

;; ------------------------------------------------------------------ assembly

(defn- join-blocks
  "OPENING with BLOCKS appended, exactly one blank line between each. The engine
  wraps nothing: a block's tag is part of the text the block brought, and a
  wrapping rule would be a second mechanism for 'who is speaking here'.

  The opening's own trailing newlines are normalised away before the separator is
  added, so 'exactly one blank line' means one blank line whatever the file happens
  to end with. Nothing else about it is touched -- and a run with no blocks does
  not come through here at all (see below), so the file's bytes are what the
  provider sees whenever nothing is appended.

  An EMPTY BLOCKS returns OPENING ITSELF, not an equal string: that is the path
  every caller without a bound sink takes -- and the path every run took before
  this feature existed -- so byte-identity there is the regression guarantee the
  whole thing rests on."
  [opening blocks]
  (if (empty? blocks)
    opening
    (str (str/replace opening #"\n+$" "") "\n\n" (str/join "\n\n" blocks))))

(defn assemble
  "The text of THREAD-ID's system message on this run: prompt.md's frozen opening,
  then each SystemPrompt hook's text, in order, one blank line apart. Every block
  has been trimmed already, and one that trimmed to nothing is simply not here.

  THE EDGE'S HOOK SINK MUST BE BOUND AROUND THIS CALL. `hook/emit` fires nothing
  when no sink is bound, deliberately -- an offline tool, replay and a test driving
  the kernel directly all behave exactly as they did before hooks existed -- and
  the consequence here is that this returns prompt.md's bytes verbatim in those
  callers. A caller that wants the appended text has to be the edge, or has to bind
  a sink itself.

  A declaration at this point that exits 2, times out, or cannot be run means THE
  RUN DOES NOT START: this throws with the hook's own words as the message, and
  harness.edge.http's set-up catch turns that into the RUN_ERROR a client sees. A
  hard failure rather than fail-open, because what these hooks write is what the
  system message is supposed to have said -- the same family as an AGENTS.md that
  exists but cannot be read, and for the same reason: an instruction that was
  meant to constrain the run must not be dropped in silence."
  [thread-id]
  (let [{:keys [verdict reason blocks]} (hook/emit :system-prompt {})]
    (when (= :block verdict)
      ;; A BLOCK WITH NOTHING ON IT still says something. `verdict-of` gives a
      ;; block the declaration's own stderr, so a blank reason means the
      ;; declaration exited 2 and wrote nothing anywhere -- rare, and exactly the
      ;; case where a silent refusal would leave someone staring at an empty
      ;; RUN_ERROR wondering whether the harness or their hook was broken.
      (throw (ex-info (if (str/blank? (str reason))
                        "a SystemPrompt hook refused this run and said nothing"
                        (str reason))
                      {:reason :system-prompt-blocked :thread-id thread-id})))
    (join-blocks (llm/prompt) blocks)))

;; -------------------------------------------------------- the kernel's own rows
;;
;; Three rows at the SystemPrompt point, registered from here because building
;; one needs the live tool table, the session's binding and the provider -- see
;; this namespace's docstring for the require cycle that decides it. harness.kernel.hooks
;; owns the registry as a SEAM; this is its only writer.
;;
;; THEY ARE ROWS, NOT A MECHANISM. Source :built-in, visible in effective-hooks,
;; switchable with session-disable!, audited like everything else, first in the
;; order because they are the kernel's own. And they are deliberately switchable
;; while prompt.md's opening is not: what these append is this session's FACTS,
;; and a session may stop handing its model a fact. What the opening states are
;; COMMITMENTS, and those are not in a hook's hands.

(defn- tools-block
  "The <tools> block: which tools this session can call, which it has switched
  off, and which come from an outside program.

  THREE FACTS, AND THE SECOND TWO ARE WHY THE FIRST ALONE WOULD LIE. The set on
  its own would already be far better than a hard-coded list, but it would be
  wrong in two ways:

    - what this session switched OFF. Off is not hidden -- the tool is still in
      the table and the model can switch it back on -- so a list that omitted it
      would describe a session that does not exist. It appears in both lines when
      it is both switched off and external, because the two facts are independent.
    - what comes from somewhere else. `mcp__<server>__<tool>` is not this kernel's
      implementation: it can be slow, it can be down, and it fails in ways a
      built-in never does. A model should know which of its hands are borrowed.

  The set is read from the WIRE's array (harness.kernel.tools/specs) rather than from the
  table, and sorted. The array is what the model actually has -- the editing mode
  subtracts one mode's tools from it -- and the sort is what makes this block a
  function of the SET: if the order the table iterates in could move the text, two
  runs over identical facts would differ and the prefix cache would miss.

  NO DESCRIPTIONS. Every one of these tools already carries its description in the
  request's :tools array; repeating them here would spend the context window twice
  on the same words. This block is a roll call."
  [payload]
  (let [thread-id (get payload "thread_id")
        names     (vec (sort (map #(get-in % [:function :name]) (tools/specs thread-id))))
        off       (filterv #(tools/session-disabled? thread-id %) names)
        external  (filterv #(str/starts-with? % "mcp__") names)]
    {:exit 0 :err ""
     :out (str "<tools>\n"
               (str/join "\n"
                         (remove nil?
                                 [(str "available: " (str/join ", " names))
                                  (when (seq off)
                                    (str "switched off in this session: " (str/join ", " off)))
                                  (when (seq external)
                                    (str "external (an MCP server provides these, not this kernel): "
                                         (str/join ", " external)))]))
               "\n</tools>")}))

(defn- project-block
  "The <project> block: which directory this session is bound to, and what that
  means for the paths it hands the file tools.

  THIS REPLACES A PARAGRAPH THAT TOLD THE MODEL TO GO ASK. 'Your project: ask
  (harness.cap.project/binding-for ...)' was a true sentence about how to find out,
  but it spent a round trip on a fact the server already had, and it left the
  model's first action uncertain. The binding can move mid-session
  (project/bind!), so this is derived on every run -- which is the only way the
  block can never state a binding that has stopped being true.

  THE FENCE IS DERIVED, NOT DESCRIBED, and that is not decoration: the free paths
  below ARE harness.cap.project/fence -- the same list harness.cap.project/out-of-bounds?
  tests a tool call against, including the two cases a hand-written sentence kept
  getting wrong. `:approval {:strict true}` takes the PROJECT directory out of the
  free set, and a skill root is free even though it sits outside both the project
  and the configuration home -- so a fixed sentence would be a rule that does not
  hold, which is the one thing a block whose job is to state the rules must not do.
  Reading the gate's own list rather than re-deriving it is what keeps the two from
  drifting apart.

  UNBOUND SAYS SO PLAINLY and mentions no fence at all: a session with no binding
  has no fence, and describing one would be describing a rule that is not in
  force."
  [payload]
  (let [thread-id (get payload "thread_id")
        {:keys [dir strict? free]} (project/fence thread-id)]
    {:exit 0 :err ""
     :out (if (nil? dir)
            (str "<project>\n"
                 "not bound to any project directory. Relative paths in the file tools"
                 " resolve against the process's working directory, and bash runs there."
                 " Absolute paths are never redirected.\n"
                 "</project>")
            (str "<project>\n"
                 "bound to: " dir "\n"
                 "Relative paths in the file tools resolve against it, and bash runs with it"
                 " as its working directory. Absolute paths are never redirected.\n"
                 "A read/write/edit path that resolves outside every free path below parks"
                 " for human approval before it runs:\n"
                 (str/join "\n" (map (fn [[p why]] (str "  - " p " -- " why)) free)) "\n"
                 (when strict?
                   (str "This project sets :approval {:strict true}, so the project directory"
                        " is NOT in that set: paths inside it park too.\n"))
                 "</project>"))}))

(defn- provider-block
  "The <provider> block: which vendor, which model, which reasoning effort -- the
  three knobs a session chooses, as the catalog resolved them for THIS thread.

  ONE SOURCE, NOT A NEW ONE. It reads harness.cap.providers/active-provider, which is
  the same live answer the /api/model endpoint and the session's own `eval` get;
  re-deriving the tiers here would be a second implementation of the answer, and
  the two would eventually disagree about what the session is served from.

  NEVER THE KEY, and the guarantee is structural rather than careful: the fields
  named below are selected one by one out of a map that already refuses to carry
  :api-key (harness.cap.providers/active-provider), so there is no depth at which a
  secret could ride along. A test searches the assembled text for the real key
  anyway, because 'no path exists' is worth checking rather than believing.

  A THREAD THAT CANNOT ANSWER PRODUCES NO BLOCK -- empty output, which the engine
  drops. Not an error and not an empty <provider></provider>: a session with
  nothing to say about its model should say nothing about its model."
  [payload]
  (let [p (providers/active-provider (get payload "thread_id"))
        lines (cond-> []
                (:provider p)         (conj (str "vendor: " (name (:provider p))))
                (:model p)            (conj (str "model: " (:model p)))
                (:reasoning-effort p) (conj (str "reasoning effort: " (:reasoning-effort p))))]
    {:exit 0 :err ""
     :out (if (seq lines)
            (str "<provider>\n" (str/join "\n" lines) "\n</provider>")
            "")}))

(defn install!
  "Put the kernel's own three rows at the SystemPrompt point, and answer the
  teardown that takes them away again.

  THEY ARE INSTALLED, NOT REGISTERED AT LOAD. The rows are a capability like any
  other, so they arrive through harness.kernel.hooks/install! and leave with its
  teardown -- a namespace that quietly registers rows as a side effect of being
  loaded is exactly what the install door exists to stop, and these three were the
  last ones doing it.

  The rows' IDs are `builtin:tools` and friends, derived from the names below: a
  built-in has a name because the kernel wrote it and can say what it is, so it
  reads as itself in a table, in a disable call and in an audit line."
  []
  (hooks/install! {:name "system-prompt rows"
                   :builtins {:system-prompt [["tools"    {:run tools-block}]
                                              ["project"  {:run project-block}]
                                              ["provider" {:run provider-block}]]}}))