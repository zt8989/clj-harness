(ns harness.cap.system-prompt-test
  "harness.cap.system-prompt's external behavior: what a run's system message is, what
  a declaration at the SystemPrompt point can do to it, and what the kernel's own
  three rows say.

  THE POINT'S CONTRACT IS THE REVERSE OF EVERY OTHER GATE. At PreToolUse the
  earliest block wins and the rest of the answer is a reason; here every matched
  declaration runs and every one APPENDS ITS TEXT, in source-then-order, because a
  hook that writes a paragraph must not be able to eat another's. And where the
  other points' stdout is read only for a JSON answer, stdout at this point IS the
  result -- the `:stdout :content` cell on its row, and the one thing that makes
  `fire` collect `:blocks`.

  THREE ENDS ARE PINNED SEPARATELY because they fail differently: the no-sink case
  is about staying byte-identical to a run before this existed, the bound case is
  about the text the model actually reads, and the kernel's own rows are about the
  facts that text states being LIVE -- a stale tool list, or a binding that has
  moved, would be a system message telling the model something untrue."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [harness.kernel.hooks :as hooks]
            [harness.kernel.hooks.dispatch :as dispatch]
            [harness.infra.home :as home]
            [harness.cap.project :as project]
            [harness.cap.providers :as providers]
            [harness.cap.system-prompt :as system-prompt]
            [harness.test-support :as support]
            [harness.kernel.tools :as tools]))

(use-fixtures :once support/with-builtins)

;; A hooks.edn written here declares for EVERY thread in this process, so it is
;; wiped around every test -- the same discipline harness.kernel.hooks-test applies.
(use-fixtures :each (fn [f] (support/wipe-hooks!) (f) (support/wipe-hooks!)))

(defn- assemble-run
  "One assembly of THREAD-ID's system message with a sink bound -- what the edge
  does. Returns {:text .. :audits [..]}, or {:error e :audits [..]} for a run a
  declaration refused to start: 'what the model reads' and 'what the record says
  about it' are two separate claims, and both are wanted from the same run."
  [thread-id]
  (let [audits (atom [])]
    (-> (binding [dispatch/*sink* {:thread-id thread-id
                                   :run-id "r-system-prompt-test"
                                   :audit #(swap! audits conj %)}]
          (try {:text (system-prompt/assemble thread-id)}
               (catch Exception e {:error e})))
        (assoc :audits @audits))))

(defn- opening [] (slurp "prompt.md" :encoding "UTF-8"))

(defn- says [text] {:run (fn [_] {:exit 0 :out text :err ""})})

(defn- block
  "The BLOCK out of a whole system message -- <tools>, <project> or <provider> --
  for assertions about that block rather than about its place in the message. Nil
  when the block is not there at all, which is itself a case worth asserting."
  [text tag]
  (let [open  (str "<" tag ">")
        close (str "</" tag ">")
        start (str/index-of text open)
        end   (str/index-of text close)]
    (when (and start end) (subs text start (+ end (count close))))))

;; ------------------------------------------------- the no-sink half, byte for byte

(deftest without-a-sink-nothing-fires-and-the-text-is-prompt-dot-md
  ;; The regression the whole feature rests on: an offline tool, replay, or a test
  ;; driving the kernel directly has no audit writer, and a hook whose verdict
  ;; nobody records would change a run silently. So no sink means the point does
  ;; not dispatch -- even with declarations in the file, and even with the kernel's
  ;; own rows registered.
  (support/write-hooks! {:system-prompt [{:command "echo this must not be appended"}]})
  (is (= (opening) (system-prompt/assemble "sp-nosink"))
      "byte for byte: the frozen opening and nothing else")
  (is (= (opening) (system-prompt/assemble nil))))

(deftest a-thread-with-every-row-switched-off-writes-no-audit-line
  ;; 'Nothing declared, nothing happens' is the engine's property and it still
  ;; holds -- but since the kernel registers rows of its own, the state is reached
  ;; by switching those off rather than by having no declarations at all.
  (support/without-builtins! "sp-none")
  (let [{:keys [text audits]} (assemble-run "sp-none")]
    (is (= (opening) text))
    (is (empty? audits))))

;; ------------------------------------------------------------- the appended text

(deftest every-matched-declaration-appends-in-source-and-written-order
  ;; The point's defining difference: not first-block-wins. One hook must not be
  ;; able to swallow another one's text.
  (support/without-builtins! "sp-order")
  (support/write-hooks! {:system-prompt [{:command "printf 'from the file\n'"}
                                 {:command "printf 'and a second file row\n'"}]})
  (hooks/session-add! "sp-order" :system-prompt (says "from the session"))
  (let [{:keys [text audits]} (assemble-run "sp-order")]
    (testing "the frozen opening leads, byte for byte"
      (is (str/starts-with? text (str/trimr (opening)))))
    (testing "the blocks follow, one blank line apart -- and that is the whole text"
      (is (= (str (str/trimr (opening))
                  "\n\nfrom the file\n\nand a second file row\n\nfrom the session")
             text))
      (is (not (str/includes? text "\n\n\n"))
          "exactly one blank line between parts, never two"))
    (testing "and one audit line says how much of it there was"
      (is (= 1 (count audits)))
      (is (= "SystemPrompt" (:point (first audits))))
      (is (= 3 (:matched (first audits)))))))

(deftest a-block-is-trimmed-and-an-empty-one-says-nothing
  (support/without-builtins! "sp-blank")
  (hooks/session-add! "sp-blank" :system-prompt (says "  \n  padded  \n "))
  (hooks/session-add! "sp-blank" :system-prompt (says "   \n  "))
  (hooks/session-add! "sp-blank" :system-prompt (says "second"))
  (let [text (:text (assemble-run "sp-blank"))]
    (is (str/ends-with? text "\n\npadded\n\nsecond")
        "each block trimmed, and the one that trimmed to nothing dropped entirely")
    (is (not (str/includes? text "\n\n\n")) "no block leaves a blank line of its own")))

(deftest changing-hooks-edn-takes-effect-at-the-next-assembly
  ;; No restart, no reset: the same discipline config.edn and harness.edn are read
  ;; with, because the file is read on every trigger.
  (support/without-builtins! "sp-live")
  (testing "with nothing declared only the opening is sent"
    (is (= (opening) (:text (assemble-run "sp-live")))))
  (testing "declaring one puts its text in the very next assembly"
    (support/write-hooks! {:system-prompt [{:command "printf 'declared midway\n'"}]})
    (is (str/ends-with? (:text (assemble-run "sp-live")) "\n\ndeclared midway")))
  (testing "and removing it takes the text back out"
    (support/wipe-hooks!)
    (is (= (opening) (:text (assemble-run "sp-live"))))))

(deftest a-switched-off-declaration-does-not-append-and-stays-readable
  (support/without-builtins! "sp-off")
  (let [id (hooks/session-add! "sp-off" :system-prompt (says "should not appear"))]
    (hooks/session-disable! "sp-off" id)
    (testing "it appends nothing"
      (is (= (opening) (:text (assemble-run "sp-off")))))
    (testing "but it is still in the table, with the off mark -- off is not hidden"
      (let [d (get (hooks/effective-hooks "sp-off") id)]
        (is (some? d))
        (is (true? (:disabled? d)))))
    (testing "and switching it back on brings the text back"
      (hooks/session-enable! "sp-off" id)
      (is (str/ends-with? (:text (assemble-run "sp-off")) "\n\nshould not appear")))))

;; ------------------------------------------------------- saying no stops the run

(deftest a-declaration-that-says-no-stops-the-run-with-its-own-words
  (support/without-builtins! "sp-block")
  (support/write-hooks!
   {:system-prompt [{:command "echo 'the run may not start like this' >&2; exit 2"}]})
  (let [{:keys [audits error]} (assemble-run "sp-block")]
    (testing "assembly throws, and the message IS the hook's stderr -- verbatim"
      (is (some? error))
      (is (= "the run may not start like this" (ex-message error))))
    (testing "harness.edge.http's set-up catch turns that message into the client's RUN_ERROR"
      (is (= :system-prompt-blocked (:reason (ex-data error)))))
    (testing "and the trigger left its audit line, so the log says what refused"
      (is (= 1 (count audits)))
      (is (= "SystemPrompt" (:point (first audits))))
      (is (= :block (:verdict (first audits))))
      (is (= "the run may not start like this" (:reason (first audits)))))))

(deftest a-hook-that-could-not-answer-blocks-rather-than-being-skipped
  ;; :on-error :block, and the reason says WHICH failure it was: a hook that writes
  ;; what the system message is supposed to say cannot be silently dropped -- an
  ;; instruction that was meant to constrain the run must not vanish.
  (support/without-builtins! "sp-fail")
  (testing "a command that fails as soon as the shell reaches it"
    (support/write-hooks! {:system-prompt [{:command "/nonexistent/never-a-hook.sh"}]})
    (let [{:keys [error]} (assemble-run "sp-fail")]
      (is (some? error))
      (is (str/includes? (ex-message error) "hook exited 127"))))
  (testing "an in-process hook that throws could not be run at all"
    ;; The broken command above declares for every thread, so it goes first --
    ;; otherwise the earliest block would be its reason and not this one's.
    (support/wipe-hooks!)
    (hooks/session-add! "sp-fail" :system-prompt
                        {:run (fn [_] (throw (ex-info "the block generator is broken" {})))})
    (let [{:keys [error]} (assemble-run "sp-fail")]
      (is (some? error))
      (is (str/includes? (ex-message error) "could not be run"))
      (is (str/includes? (ex-message error) "the block generator is broken")
          "and the reason carries the function's own message, not just 'it failed'"))))

(deftest a-hook-that-refuses-still-lets-the-others-run
  ;; "Every matched declaration runs" is about RUNNING, and it holds even when the
  ;; first one refuses: the second one's side effect is a fact about the trigger,
  ;; while the run stopping is a separate fact about the assembly.
  (support/without-builtins! "sp-both")
  (let [marker (str (System/getProperty "java.io.tmpdir") "/sp-late-ran.txt")]
    (io/delete-file (io/file marker) true)
    (support/write-hooks! {:system-prompt [{:command "echo no >&2; exit 2"}
                                   {:command (str "echo ran > " marker "; exit 0")}]})
    (is (some? (:error (assemble-run "sp-both"))))
    (is (.exists (io/file marker))
        "a later declaration is not skipped because an earlier one refused")
    (io/delete-file (io/file marker) true)))

;; -------------------------------------------------------- the kernel's own rows

(deftest the-kernel-registers-its-own-rows-like-any-other-hook
  (let [rows (hooks/declarations-at "sp-builtin" :system-prompt)]
    (testing "three rows, all at the SystemPrompt point, all from the kernel"
      (is (= 3 (count rows)))
      (is (= #{"builtin:tools" "builtin:project" "builtin:provider"}
             (set (map :id rows))))
      (is (every? #(= :built-in (:source %)) rows)))
    (testing "each runs the only way a built-in may: a function, never a command"
      (is (every? #(ifn? (:run %)) rows))
      (is (every? #(nil? (:command %)) rows)))
    (testing "the table is where a session sees them, with the name it can switch off"
      (let [e (hooks/effective-hooks "sp-builtin")]
        (is (= "tools" (:name (get e "builtin:tools"))))
        (is (= :system-prompt (:point (get e "builtin:provider"))))
        (is (false? (:disabled? (get e "builtin:tools"))))))))

(deftest a-builtin-row-is-switchable-exactly-like-a-declared-one
  (hooks/session-disable! "sp-builtin-off" "builtin:tools")
  (try
    (let [e (hooks/effective-hooks "sp-builtin-off")]
      (is (some? (get e "builtin:tools")) "still in the table: off is not hidden")
      (is (true? (:disabled? (get e "builtin:tools"))))
      (is (= 2 (count (hooks/declarations-at "sp-builtin-off" :system-prompt)))))
    (finally (hooks/session-enable! "sp-builtin-off" "builtin:tools")))
  (testing "and switching it off stops that block while the opening stays put"
    (hooks/session-disable! "sp-builtin-text" "builtin:tools")
    (try
      (let [{:keys [text audits]} (assemble-run "sp-builtin-text")]
        (is (str/starts-with? text (str/trimr (opening))) "the opening is untouched")
        (is (nil? (block text "tools")))
        (is (some? (block text "project")) "the other rows are unaffected")
        (is (= 2 (:matched (first audits)))))
      (finally (hooks/session-enable! "sp-builtin-text" "builtin:tools")))))

(deftest the-kernel-rows-run-first-then-the-file-then-the-session
  ;; The precedence rule, observed where it is visible: the order the text comes
  ;; out in. Built-in, then the file's in the file's order, then the session's.
  (support/write-hooks! {:system-prompt [{:command "printf 'from-the-file\n'"}]})
  (hooks/session-add! "sp-order-all" :system-prompt (says "from-the-session"))
  (let [text (:text (assemble-run "sp-order-all"))
        at   (fn [s] (str/index-of text s))]
    (is (every? some? [(at "<tools>") (at "<project>") (at "<provider>")
                       (at "\n\nfrom-the-file\n") (at "\n\nfrom-the-session")]))
    (is (< (at "<tools>") (at "<project>") (at "<provider>")
           (at "\n\nfrom-the-file\n") (at "\n\nfrom-the-session"))
        "the kernel's rows lead, then the file's, then what the session added")))

(deftest reloading-the-registration-replaces-in-place-rather-than-appending
  ;; An id is a name and a name means one row, so re-registering -- which is what
  ;; requiring the namespace again does -- must not grow the table.
  (let [before (mapv :id (hooks/declarations-at "sp-reload" :system-prompt))]
    (is (= 3 (count before)))
    (require 'harness.cap.system-prompt :reload)
    (is (= before (mapv :id (hooks/declarations-at "sp-reload" :system-prompt)))
        "three rows before and three rows after, in the same order")))

;; ------------------------------------------------------------- the tools block

(deftest the-tools-block-reports-the-set-that-is-actually-served
  (let [text   (:text (assemble-run "sp-tools"))
        tools' (block text "tools")
        served (sort (map #(get-in % [:function :name]) (tools/specs "sp-tools")))]
    (testing "available is the array the model is handed, not the whole table"
      ;; Read from specs and sorted, so a tool the editing mode subtracts cannot
      ;; appear -- naming a tool the model cannot call would be exactly the
      ;; staleness this block exists to kill.
      (is (str/includes? tools' (str "available: " (str/join ", " served))))
      (is (not (str/includes? tools' "edit"))
          "the default mode is by-anchor, so the other mode's tool is not on offer"))
    (testing "and no descriptions: the :tools array already carries every one"
      (is (not (str/includes? tools' "description")))
      (is (not (str/includes? tools' "old_string"))))))

(deftest a-tool-this-session-registers-appears-and-an-outside-one-is-named
  (tools/session-register! "sp-tools-live" "mcp__fs__read_file"
                           {:description "read a file over MCP"
                            :parameters {:type "object" :properties {} :required []}
                            :required []
                            :run (fn [_] "")})
  (try
    (let [tools' (block (:text (assemble-run "sp-tools-live")) "tools")]
      (testing "a tool registered a moment ago is on the list, because it is"
        (is (str/includes? tools' "mcp__fs__read_file")))
      (testing "and it is named as somebody else's hand, not this kernel's"
        (is (str/includes? tools' (str "external (an MCP server provides these, not this kernel): "
                                       "mcp__fs__read_file")))))
    (finally (tools/session-unregister! "sp-tools-live" "mcp__fs__read_file"))))

(deftest a-tool-this-session-switches-off-stays-visible-and-is-named-separately
  ;; Off is not hidden: the tool is still in the table and the model can switch it
  ;; back on, so a roll call that omitted it would describe a session that does not
  ;; exist.
  (tools/session-disable! "sp-tools-off" "write")
  (try
    (let [tools' (block (:text (assemble-run "sp-tools-off")) "tools")]
      (is (str/includes? tools' "write") "still on the roll call")
      (is (str/includes? tools' "switched off in this session: write")))
    (finally (tools/session-enable! "sp-tools-off" "write"))))

(deftest the-text-is-a-function-of-the-facts-and-nothing-else
  ;; The prefix cache is the reason: two runs over the same facts must produce the
  ;; same bytes, and a fact that moved must move them. Asserted on the whole system
  ;; message, not just the block, because that is what the provider keys on.
  (testing "nothing moved, nothing moved"
    (is (= (:text (assemble-run "sp-stable")) (:text (assemble-run "sp-stable")))))
  (testing "and when a fact does move, the text does"
    (let [before (:text (assemble-run "sp-stable"))]
      (tools/session-register! "sp-stable" "mcp__x__y"
                               {:description "x" :parameters {:type "object" :properties {}}
                                :required [] :run (fn [_] "")})
      (let [after (:text (assemble-run "sp-stable"))]
        (is (not= before after))
        (is (str/includes? after "mcp__x__y"))
        (testing "while two runs ON the new facts agree with each other"
          (is (= after (:text (assemble-run "sp-stable")))))))))

;; ----------------------------------------------------------- the project block

(defn- project-dir
  "A directory under the configuration home, and THREAD-ID bound to it."
  [thread-id label]
  (let [d (io/file (home/root) "sp-projects" label)]
    (.mkdirs d)
    (project/bind! thread-id (str d))
    (str d)))

(deftest the-project-block-states-the-binding-and-what-it-means
  (let [dir (project-dir "sp-proj" "plain")
        proj (block (:text (assemble-run "sp-proj")) "project")]
    (testing "the binding is stated, absolutely -- no eval call needed to learn it"
      (is (str/includes? proj (str "bound to: " dir))))
    (testing "and so are the three path rules that follow from it"
      (is (str/includes? proj "Relative paths in the file tools resolve against it"))
      (is (str/includes? proj "bash runs with it as its working directory"))
      (is (str/includes? proj "Absolute paths are never redirected")))
    (testing "the fence names its free paths, derived from where the fence reads them"
      (is (str/includes? proj "parks for human approval"))
      (is (str/includes? proj (str dir " -- this project")))
      (is (str/includes? proj (str (home/root) " -- this harness's configuration home")))
      (is (str/includes? proj "reading your own configuration there is allowed")))
    (testing "and no strict sentence, because this project is not strict"
      (is (not (str/includes? proj ":strict true"))))))

(deftest a-strict-project-takes-its-own-directory-out-of-the-free-set
  ;; The acceptance criterion that keeps this block from stating a rule that does
  ;; not hold: under :approval {:strict true} a path inside the project parks too,
  ;; so listing the project directory as free would be the lie.
  (let [dir (io/file (home/root) "sp-projects/strict")]
    (.mkdirs (io/file dir ".harness"))
    (spit (io/file dir ".harness/harness.edn") (pr-str {:approval {:strict true}})
          :encoding "UTF-8")
    (project/bind! "sp-proj-strict" (str dir))
    (let [proj (block (:text (assemble-run "sp-proj-strict")) "project")]
      (is (str/includes? proj (str "bound to: " dir)))
      (is (str/includes? proj ":approval {:strict true}"))
      (is (str/includes? proj "paths inside it park too"))
      (is (not (str/includes? proj (str dir " -- this project")))
          "the project directory is NOT in the free list -- that would be the lie"))))

(deftest the-project-block-follows-a-rebind-and-an-unbind
  (testing "unbound: it says so, and says where relative paths land instead"
    (let [proj (block (:text (assemble-run "sp-proj-moves")) "project")]
      (is (str/includes? proj "not bound to any project directory"))
      (is (str/includes? proj "resolve against the process's working directory"))
      (is (not (str/includes? proj "park")) "no fence talk: there is no fence")))
  (testing "bound: the directory is named"
    (let [one (project-dir "sp-proj-moves" "one")]
      (is (str/includes? (block (:text (assemble-run "sp-proj-moves")) "project") one))))
  (testing "rebound: the NEXT assembly names the new directory and not the old one"
    (let [one (str (io/file (home/root) "sp-projects/one"))
          two (project-dir "sp-proj-moves" "two")
          proj (block (:text (assemble-run "sp-proj-moves")) "project")]
      (is (str/includes? proj two))
      (is (not (str/includes? proj one)))))
  (testing "and once it settles, the text is byte-identical run to run"
    (is (= (:text (assemble-run "sp-proj-moves")) (:text (assemble-run "sp-proj-moves"))))))

;; ---------------------------------------------------------- the provider block

(deftest the-provider-block-reports-the-effective-selection
  ;; Read from the existing resolution rather than re-derived here; a second
  ;; implementation would eventually disagree with what the session is served from.
  (providers/set-override! "sp-provider" {:provider "openrouter"
                                          :model "openai/gpt-4o-mini"
                                          :reasoning-effort "high"})
  (try
    (let [prov (block (:text (assemble-run "sp-provider")) "provider")]
      (is (some? prov))
      (is (str/includes? prov "vendor: openrouter"))
      (is (str/includes? prov "model: openai/gpt-4o-mini"))
      (is (str/includes? prov "reasoning effort: high")))
    (finally (providers/set-override! "sp-provider" nil))))

(deftest the-provider-block-follows-a-mid-session-change
  ;; session-configure moves the session's own tier, and the next run's system
  ;; message moves with it. One cold prefix is the price, and that is the point:
  ;; the alternative is a message naming a model that stopped serving the session.
  (let [before (:text (assemble-run "sp-provider-moves"))]
    (providers/set-override! "sp-provider-moves" {:provider "deepseek"
                                                  :model "deepseek-flash"})
    (try
      (let [after (:text (assemble-run "sp-provider-moves"))]
        (is (not= before after))
        (is (str/includes? after "vendor: deepseek"))
        (is (str/includes? after "model: deepseek-flash")))
      (finally (providers/set-override! "sp-provider-moves" nil)))))

(deftest a-thread-that-cannot-answer-produces-no-provider-block
  ;; Not an error and not an empty <provider></provider>: a session with nothing to
  ;; say about its model says nothing about its model.
  (with-redefs [providers/active-provider (constantly {})]
    (let [text (:text (assemble-run "sp-provider-none"))]
      (is (nil? (block text "provider")))
      (is (some? (block text "tools")) "and the other rows are unaffected")
      (is (not (str/includes? text "<provider>")))))
  (testing "a fact the resolution does not carry is left out rather than printed as nil"
    ;; The seeded provider is an inline description: it names an endpoint and a
    ;; model without naming a vendor. The block says what it knows and stays quiet
    ;; about the rest.
    (let [prov (block (:text (assemble-run "sp-provider-inline")) "provider")]
      (is (some? prov))
      (is (str/includes? prov "model: seeded"))
      (is (not (str/includes? prov "vendor: "))))))

(deftest the-provider-block-never-carries-the-api-key
  ;; A SEARCH, not a proof by construction. The block selects three named fields
  ;; out of a map that already refuses to carry a key -- so no path for a leak
  ;; should exist -- but a test that only reasoned about the paths would not notice
  ;; the day one appears, and this is the one thing here that must never be wrong.
  ;; The key is written where the resolver really reads it, so the run under test
  ;; is one whose provider HAS a key.
  (let [secret "sk-live-DO-NOT-LEAK-4f2a9c"
        f      (home/dotenv-file)]
    (.mkdirs (.getParentFile f))
    (spit f (str "HARNESS_API_KEY=" secret "\n") :encoding "UTF-8")
    (try
      (let [text (:text (assemble-run "sp-key"))
            prov (block text "provider")]
        (is (some? prov)
            "the block is there, so the search is a search of something")
        (is (not (str/includes? text secret)))
        (is (not (str/includes? text "HARNESS_API_KEY")))
        (testing "and the block itself never even names the field"
          ;; The frozen opening names :api-key in its prohibition -- that is the
          ;; rule, and it stays. What must not appear is the provider block
          ;; carrying it, which is what this half asks.
          (is (not (str/includes? prov "api-key")))))
      (finally (io/delete-file f true)))))
