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
            [harness.infra.env :as env]
            [harness.infra.home :as home]
            [harness.infra.shell :as shell]
            [harness.cap.project :as project]
            [harness.cap.system-prompt :as system-prompt]
            [harness.test-support :as support]))

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
  "The BLOCK out of a whole system message -- <project> or <env> -- for
  assertions about that block rather than about its place in the message. Nil
  when the block is not there at all, which is itself a case worth asserting."
  [text tag]
  (let [open  (str "<" tag ">")
        close (str "</" tag ">")
        ;; LAST, not first: the frozen opening can NAME a tag it tells the model to
        ;; read (prompt.md points at `<env>` for the language), and only the appended
        ;; blocks are real. The block is always behind the opening.
        start (str/last-index-of text open)
        end   (str/last-index-of text close)]
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
  (let [marker (str (io/file (support/temp-dir "sp-late") "ran.txt"))]
    (support/write-hooks! {:system-prompt [{:command "echo no >&2; exit 2"}
                                           ;; The marker goes into a shell command,
                                           ;; so it is spelled for the shell -- see
                                           ;; harness.test-support/shell-path.
                                           {:command (str "echo ran > "
                                                          (support/shell-path marker) "; exit 0")}]})
    (is (some? (:error (assemble-run "sp-both"))))
    (is (.exists (io/file marker))
        "a later declaration is not skipped because an earlier one refused")))

;; -------------------------------------------------------- the kernel's own rows

(deftest the-kernel-registers-its-own-rows-like-any-other-hook
  (let [rows (hooks/declarations-at "sp-builtin" :system-prompt)]
    (testing "two rows, all at the SystemPrompt point, all from the kernel"
      (is (= 2 (count rows)))
      (is (= #{"builtin:project" "builtin:env"}
             (set (map :id rows))))
      (is (every? #(= :built-in (:source %)) rows)))
    (testing "each runs the only way a built-in may: a function, never a command"
      (is (every? #(ifn? (:run %)) rows))
      (is (every? #(nil? (:command %)) rows)))
    (testing "the table is where a session sees them, with the name it can switch off"
      (let [e (hooks/effective-hooks "sp-builtin")]
        (is (= "project" (:name (get e "builtin:project"))))
        (is (= :system-prompt (:point (get e "builtin:env"))))
        (is (false? (:disabled? (get e "builtin:project"))))))))

(deftest a-builtin-row-is-switchable-exactly-like-a-declared-one
  (hooks/session-disable! "sp-builtin-off" "builtin:project")
  (try
    (let [e (hooks/effective-hooks "sp-builtin-off")]
      (is (some? (get e "builtin:project")) "still in the table: off is not hidden")
      (is (true? (:disabled? (get e "builtin:project"))))
      (is (= 1 (count (hooks/declarations-at "sp-builtin-off" :system-prompt)))))
    (finally (hooks/session-enable! "sp-builtin-off" "builtin:project")))
  (testing "and switching it off stops that block while the opening stays put"
    (hooks/session-disable! "sp-builtin-text" "builtin:project")
    (try
      (let [{:keys [text audits]} (assemble-run "sp-builtin-text")]
        (is (str/starts-with? text (str/trimr (opening))) "the opening is untouched")
        (is (nil? (block text "project")))
        (is (some? (block text "env")) "the other rows are unaffected")
        (is (= 1 (:matched (first audits)))))
      (finally (hooks/session-enable! "sp-builtin-text" "builtin:project")))))

(deftest the-kernel-rows-run-first-then-the-file-then-the-session
  ;; The precedence rule, observed where it is visible: the order the text comes
  ;; out in. Built-in, then the file's in the file's order, then the session's.
  (support/write-hooks! {:system-prompt [{:command "printf 'from-the-file\n'"}]})
  (hooks/session-add! "sp-order-all" :system-prompt (says "from-the-session"))
  (let [text (:text (assemble-run "sp-order-all"))
        at   (fn [s] (str/last-index-of text s))]
    (is (every? some? [(at "<project>") (at "<env>")
                       (at "\n\nfrom-the-file\n") (at "\n\nfrom-the-session")]))
    (is (< (at "<project>") (at "<env>")
           (at "\n\nfrom-the-file\n") (at "\n\nfrom-the-session"))
        "the kernel's rows lead, then the file's, then what the session added")))

(deftest reloading-the-registration-replaces-in-place-rather-than-appending
  ;; An id is a name and a name means one row, so re-registering -- which is what
  ;; requiring the namespace again does -- must not grow the table.
  (let [before (mapv :id (hooks/declarations-at "sp-reload" :system-prompt))]
    (is (= 2 (count before)))
    (require 'harness.cap.system-prompt :reload)
    (is (= before (mapv :id (hooks/declarations-at "sp-reload" :system-prompt)))
        "two rows before and two rows after, in the same order")))

(deftest the-text-is-a-function-of-the-facts-and-nothing-else
  ;; The prefix cache is the reason: two runs over the same facts must produce the
  ;; same bytes, and a fact that moved must move them. Asserted on the whole system
  ;; message, not just the block, because that is what the provider keys on.
  (testing "nothing moved, nothing moved"
    (is (= (:text (assemble-run "sp-stable")) (:text (assemble-run "sp-stable")))))
  (testing "and when a fact does move, the text does"
    (let [before (:text (assemble-run "sp-stable"))]
      (support/with-machine {:command "/bin/bash" :kind :bash :posix? true
                             :argv-prefix ["-lc"]}
                  #{"rg"}
        (fn []
          (let [after (:text (assemble-run "sp-stable"))]
            (is (not= before after))
            (is (str/includes? after "available: rg"))
            (testing "while two runs ON the new facts agree with each other"
              (is (= after (:text (assemble-run "sp-stable")))))))))))

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
      (is (str/includes? proj (str (first (env/temp-dirs)) " -- "))
          "the machine's temp directory, stated with its reason like every other free path")
      (is (str/includes? proj "scratch that is meant to be thrown away"))
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

;; --------------------------------------------------------------- the env block

(def ^:private posix-shell
  {:command "/bin/bash" :kind :bash :posix? true :argv-prefix ["-lc"]})

(deftest the-env-block-states-this-machine-and-reads-the-shell-it-was-given
  ;; The whole block is three facts about the machine, so it is asserted with the
  ;; machine's two answers handed in: the chain's and the probe's. What is being
  ;; checked here is that the block READS them -- the shell line especially, because
  ;; re-deciding 'which shell is this' in a second place is how the two places come
  ;; to disagree.
  (support/with-machine {:command "/usr/local/bin/pwsh" :kind :pwsh :posix? false
               :argv-prefix ["-NoProfile" "-Command"]}
              #{"rg" "git"}
    (fn []
      (let [text (:text (assemble-run "sp-env"))
            e    (block text "env")]
        (testing "the block is there, and behind <project>"
          (is (some? e))
          (is (< (str/last-index-of text "<project>") (str/last-index-of text "<env>"))
              "the <env> block is behind <project>, not the mention in the opening"))
        (testing "the shell is harness.infra.shell's answer, reported and not re-decided"
          (is (str/includes? e "pwsh"))
          (is (str/includes? e "/usr/local/bin/pwsh"))
          (is (str/includes? e "-NoProfile -Command")))
        (testing "and the platform says which machine this is"
          (is (str/includes? e "platform: "))
          (is (str/includes? e (env/platform))))
        (testing "BOTH halves of the tool list are stated: what is here and what is not"
          (is (str/includes? e "available: rg, git"))
          (is (str/includes? e "not found: fd, jq"))
          (is (not (str/includes? e "(nothing from the list)"))))))))

(deftest a-list-with-nothing-and-everything-in-it-says-so-plainly
  (support/with-machine posix-shell #{}
    (fn []
      (let [e (block (:text (assemble-run "sp-env-none")) "env")]
        (is (str/includes? e "available: (none from the list)"))
        (is (str/includes? e (str "not found: " (str/join ", " env/enhancers)))))))
  (support/with-machine posix-shell (set env/enhancers)
    (fn []
      (let [e (block (:text (assemble-run "sp-env-every")) "env")]
        (is (str/includes? e (str "available: " (str/join ", " env/enhancers))))
        (is (str/includes? e "not found: (nothing from the list)"))))))

(deftest a-machine-whose-shell-would-not-answer-still-gets-a-block
  ;; A machine that cannot be asked is a fact like any other, and the one thing the
  ;; block must not do is take the run down with it: a session that started anyway
  ;; with an honest 'I do not know' is better than a session that did not start.
  (support/with-machine posix-shell :unknown
    (fn []
      (let [{:keys [text error]} (assemble-run "sp-env-unknown")
            e (block text "env")]
        (is (nil? error) "the assembly still happens")
        (is (some? e) "and the block is still there")
        (is (str/includes? e "available: unknown"))
        (is (not (str/includes? e "not found:"))
            "nothing is claimed to be missing -- it was never asked")))))

(deftest the-probe-asks-the-shell-rather-than-reading-the-jvm-s-environment
  ;; The property the enhancer half rests on: the JVM's PATH is not the shell's --
  ;; the shell is started as a login shell and re-sources the profile -- so the
  ;; question has to go to the shell. Asserted by watching the seam the probe uses
  ;; (harness.infra.shell/run, the same one every tool call goes through) rather than
  ;; by reading an answer back and agreeing with it.
  (try
    (let [seen (atom nil)]
      (with-redefs [shell/run (fn [req] (reset! seen req) {:exit 0 :out "rg\n" :err ""})]
        (is (= #{"rg"} (env/probe*))))
      (is (some? @seen) "it asked through the shell, and asked once")
      (is (str/includes? (:command @seen) "rg")
          "and the question names the tools it is about"))
    (finally (env/reset-probe!))))

(deftest the-same-machine-produces-the-same-text
  ;; The prefix cache, asserted where the block could break it: two assemblies of the
  ;; same thread on the same machine are byte-identical, because every machine fact in
  ;; here was resolved once. No stubs -- this one is about the real answer being CACHED
  ;; rather than re-asked (and re-worded) on every run.
  (is (= (:text (assemble-run "sp-env-stable"))
         (:text (assemble-run "sp-env-stable")))))

(deftest the-env-block-states-which-language-this-harness-speaks
  ;; The line is this HOME's setting, resolved from a chain whose tail is the OS and the
  ;; terminal -- facts about the machine the suite happens to run on -- so the assertion
  ;; is that the line EXISTS and names a language this harness speaks, not WHICH one
  ;; this machine answers. The chain itself is asserted source by source, with every
  ;; link stubbed, in harness.infra.language-test.
  (let [e (block (:text (assemble-run "sp-env-lang")) "env")]
    (is (some? e))
    (is (re-find #"(?m)^language: (?:English \(en\)|Chinese \(zh\))$" e)
        "the language line is always there, naming the value and what to call it")))

(deftest the-env-row-is-an-ordinary-row
  ;; Visible in the table, switchable by name, off means gone, on means back -- the
  ;; same shape the project row is asserted to have, because a row is a row.
  (is (false? (:disabled? (get (hooks/effective-hooks "sp-env-row") "builtin:env"))))
  (hooks/session-disable! "sp-env-row" "builtin:env")
  (try
    (is (true? (:disabled? (get (hooks/effective-hooks "sp-env-row") "builtin:env"))))
    (is (nil? (block (:text (assemble-run "sp-env-row")) "env")))
    (finally (hooks/session-enable! "sp-env-row" "builtin:env")))
  (is (some? (block (:text (assemble-run "sp-env-row")) "env"))))

;; ------------------------------------------- the whole message, searched for a key

(deftest the-assembled-text-never-carries-the-api-key
  ;; A SEARCH, not a proof by construction: the rows are built from facts that
  ;; refuse to carry a key, so no path for a leak should exist -- but a test that only
  ;; reasoned about the paths would not notice the day one appears, and this is the one
  ;; thing here that must never be wrong. The key is written where the resolver really
  ;; reads it, so the session under test is one whose provider HAS a key.
  ;;
  ;; THE FROZEN OPENING NAMES `:api-key` ITSELF -- that is the prohibition, and it
  ;; stays. So the search for the FIELD is made against what the hooks appended, which
  ;; is the part a leak could come from; the search for the SECRET is made against the
  ;; whole message, opening included.
  (let [secret "sk-live-DO-NOT-LEAK-4f2a9c"
        f      (home/dotenv-file)]
    (.mkdirs (.getParentFile f))
    (spit f (str "HARNESS_API_KEY=" secret "\n") :encoding "UTF-8")
    (try
      (is (str/includes? (slurp f :encoding "UTF-8") secret)
          "the key really is where the resolver reads it, so this is a search of something")
      (let [text     (:text (assemble-run "sp-key"))
            appended (subs text (count (str/trimr (opening))))]
        (is (str/includes? text "<env>") "and the message really was assembled")
        (is (not (str/includes? text secret)))
        (is (not (str/includes? text "HARNESS_API_KEY")))
        (testing "and what the hooks appended never even names the field"
          (is (not (str/includes? appended "api-key"))))
        (testing "while the search itself is one that could have found a key"
          ;; Without this half the assertion above would pass on an empty string too.
          (is (str/includes? (str text " " secret) secret))))
      (finally (io/delete-file f true)))))
