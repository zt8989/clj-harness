(ns harness.cap.subagents-test
  "harness.cap.subagents' external behaviour: the two built-ins every home has, the
  range a definition derives from the session's own table, and the refusals that
  keep `eval` and `agent` unreachable by any configuration.

  THE DERIVATION IS COMPARED AGAINST THE SEAM, not against a list of names written
  down here. `table-for`'s whole claim is that a subagent's range is the SAME KIND
  of statement as the session's table -- so the assertion is `general` = the
  session's own names minus two, and a range that drifted from the table would fail
  here without this file having to be updated."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [harness.cap.hooks :as cap-hooks]
            [harness.cap.subagents :as subagents]
            [harness.cap.system-prompt :as system-prompt]
            [harness.cap.tools :as cap-tools]
            [harness.infra.home :as home]
            [harness.kernel.tools :as tools]
            [harness.test-support :as support]))

;; --------------------------------------------------------------- the fixtures
;;
;; The app's capabilities AND this one, which is what the composition root
;; installs. A namespace that asked table-for without the tool table in place
;; would be deriving a range over an empty map and agreeing with itself.

(defn- with-capabilities [f]
  (let [teardowns [(cap-tools/install!)
                   (cap-hooks/install!)
                   (system-prompt/install!)
                   (subagents/install! {})]]
    (try (f) (finally (doseq [td teardowns] (td))))))

(defn- each-test-gets-its-own-home [f]
  ;; A HOME OF THE TEST'S OWN, per AGENTS.md, because the definitions reader opens a
  ;; USER-level harness.edn -- a file that belongs to whichever root is in force, and
  ;; the run-wide root belongs to every namespace in this JVM. Writing the cases'
  ;; declarations there and wiping them afterwards is a delete against a directory
  ;; somebody else is also reading, and it puts the wipe's correctness in the way of
  ;; an exception thrown mid-case. A root this namespace owns makes the whole question
  ;; go away: born empty, gone on the way out, and `with-temp-env` also seeds the
  ;; config.edn a run needs to resolve a provider.
  (support/with-temp-env [_root _home] (f)))

(use-fixtures :once with-capabilities)
(use-fixtures :each each-test-gets-its-own-home)

(defn- definitions [] (subagents/definitions nil))

(defn- definition [name] (subagents/definition-for (definitions) name))

(defn- range-of [name]
  (set (keys (subagents/table-for "sa-parent" (definition name)))))

(defn- write-user-harness-edn!
  "A user-level harness.edn, as the settings form would leave it."
  [edn]
  (spit (io/file (home/root) "harness.edn") (pr-str edn) :encoding "UTF-8"))

;; ------------------------------------------------------------------ the floor

(deftest a-fresh-home-has-the-two-built-ins-in-order
  (let [defs (definitions)]
    (is (nil? (:problem defs)) "nothing to report about a home that declares nothing")
    (is (= ["general" "explore"] (subagents/known-names defs)))
    (is (= [:all :read-only] (mapv :baseline (:subagents defs)))
        "the two baselines, in the order the list offers them")
    (doseq [d (:subagents defs)]
      (is (not (str/blank? (:description d)))
          "a subagent with no description is one the model cannot choose between"))))

(deftest general-serves-the-session-minus-the-two-forbidden-names
  ;; "WHAT THE MAIN AGENT SERVES" IS THE FILTERED LIST, not every name in the table:
  ;; a name the session itself cannot call -- the other editing mode's tools -- is
  ;; not something a delegation from it could inherit, so it is not in the range
  ;; either. The narrowing composes as an intersection, and this is that claim
  ;; measured against the table rather than restated as a list of names.
  (let [session (set (filter #(tools/served? "sa-parent" %)
                             (keys (tools/effective-tools "sa-parent"))))
        table   (range-of "general")]
    (is (contains? session "eval") "the session itself has it -- that is what makes removing it worth asserting")
    (is (contains? session "agent") "and the delegating tool is in the main table by design")
    (is (contains? (set (keys (tools/effective-tools "sa-parent"))) "edit")
        "the two editing families are both IN the table, which is what makes the filter worth having")
    (is (not= (contains? session "edit") (contains? session "replace"))
        "and exactly one of them is served -- whichever mode this home is in")
    (is (= (disj (disj session "eval") "agent") table)
        "everything else, name for name")))

(deftest a-name-the-parent-does-not-serve-is-not-in-a-range-either
  ;; The half of the intersection the editing mode owns. Whichever mode is in force
  ;; one family is refused, and a general subagent must not be handed a name its own
  ;; delegating session cannot call.
  (let [session (set (keys (tools/effective-tools "sa-parent")))
        refusd  (set (remove #(tools/served? "sa-parent" %) session))
        table   (range-of "general")]
    (is (seq refusd) "this home has an editing mode in force, so something is refused")
    (is (empty? (filter table refusd)) "and none of it is in a general subagent's range")))

(deftest explore-serves-only-what-can-prove-it-changes-nothing
  (let [table (range-of "explore")]
    (testing "the five rows that declare themselves read-only are in"
      (doseq [n ["read" "anchor_grep" "glob" "web_fetch" "web_search"]]
        (is (contains? table n) (str n " reads and says so"))))
    (testing "and everything that can write is out"
      (doseq [n ["write" "edit" "replace" "insert" "undo_last_replace"
                 "bash" "job" "job_kill" "eval" "agent" "todo_write"]]
        (is (not (contains? table n)) (str n " is not something an exploring subagent may do"))))))

(deftest a-session-tool-that-cannot-prove-itself-is-out-of-an-exploring-range
  (let [opaque    {:description "a tool whose provenance is this session"
                   :parameters {:type "object" :properties {} :required []}
                   :run (fn [_] "ok")
                   :read-only true}
        from-a-server (assoc opaque :source :mcp)
        ;; WEARING THE BUILT-INS' OWN PAPERS, which is the case the rule is ABOUT
        ;; rather than an exotic one: `session-register!` keeps the map it was handed,
        ;; tags and all, so a name that arrived from a session can say `:source
        ;; :builtin` and `:read-only true` and be neither. A reader that believed the
        ;; map would hand a writing tool to an exploring subagent and call it proof.
        wearing   (assoc opaque :source :builtin)]
    (try
      (testing "a tool this session added is inherited by a general subagent"
        (tools/session-register! "sa-session" "opaque" opaque)
        (is (contains? (set (keys (subagents/table-for "sa-session" (definition "general"))))
                       "opaque")))
      (testing "but not by an exploring one, EVEN WHEN IT SAYS IT IS READ-ONLY"
        ;; The declaration is believed only from a provenance this namespace can
        ;; check; a tool that registered itself is a name that can say anything at
        ;; all. Fewer tools is the failure to prefer.
        (is (not (contains? (set (keys (subagents/table-for "sa-session" (definition "explore"))))
                            "opaque"))))
      (testing "nor one that arrived from this session wearing the built-ins' tags"
        (tools/session-register! "sa-session" "wearing" wearing)
        (is (contains? (set (keys (subagents/table-for "sa-session" (definition "general"))))
                       "wearing") "a general subagent still gets it, tags and all")
        (is (not (contains? (set (keys (subagents/table-for "sa-session" (definition "explore"))))
                            "wearing"))
            "the range asks WHO PUT IT THERE, not what the definition says about itself"))
      (testing "and neither is one an external server contributed"
        ;; The same rule for the other unprovable provenance: a server's roster is a
        ;; declaration about a process this harness does not own.
        (tools/session-register! "sa-session" "from-a-server" from-a-server)
        (is (contains? (set (keys (subagents/table-for "sa-session" (definition "general"))))
                       "from-a-server") "a general subagent still gets it")
        (is (not (contains? (set (keys (subagents/table-for "sa-session" (definition "explore"))))
                            "from-a-server"))))
      (finally
        (tools/session-unregister! "sa-session" "opaque")
        (tools/session-unregister! "sa-session" "wearing")
        (tools/session-unregister! "sa-session" "from-a-server")))))

(deftest an-exclusions-list-takes-names-out-of-whatever-the-baseline-serves
  (let [narrowed (assoc (definition "general") :exclude ["bash" "web_search"])
        table    (set (keys (subagents/table-for "sa-parent" narrowed)))]
    (is (not (contains? table "bash")))
    (is (not (contains? table "web_search")))
    (is (contains? table "read") "everything not named is untouched")))

;; --------------------------------------------------------- the seam's refusals

(deftest a-name-outside-the-range-is-refused-by-name
  ;; Running AS the subagent is what makes the policy speak: the live table is the
  ;; one fact that turns a thread into a subagent's, so the test opens one rather
  ;; than asserting against the message builder alone. The call goes through the
  ;; ordinary seam, which is where a real subagent's calls go.
  (let [thread-id "sa-refusing"
        defn      (definition "explore")
        end!      (subagents/begin! thread-id {:parent "sa-parent"
                                               :definition defn
                                               :table (subagents/table-for "sa-parent" defn)})
        answer    (try (tools/run! {:id "c1" :function {:name "write"
                                                        :arguments "{\"path\":\"x\"}"}}
                                   thread-id)
                       (finally (end!)))]
    (is (:error answer) "a refusal is an error result, not a silent success")
    (is (str/includes? (:content answer) "write") "it names the tool that was asked for")
    (is (str/includes? (:content answer) "explore") "and whose range this is")
    (is (str/includes? (:content answer) "only the tools that cannot change anything")
        "and what the range IS, so the model can work inside it")))

(deftest a-subagent-cannot-delegate-again
  (let [thread-id "sa-nested"
        defn      (definition "general")
        end!      (subagents/begin! thread-id {:parent "sa-parent"
                                               :definition defn
                                               :table (subagents/table-for "sa-parent" defn)})
        answer    (try (tools/run! {:id "c1" :function {:name "agent"
                                                        :arguments "{\"name\":\"explore\",\"prompt\":\"hi\"}"}}
                                   thread-id)
                       (finally (end!)))]
    (is (:error answer))
    (is (str/includes? (:content answer) "cannot delegate")
        "the refusal says why, rather than leaving a model to look for a way round it")))

(deftest the-range-is-what-the-model-is-handed-not-just-what-runs
  ;; Absence from the list is the half a model actually reads: a tool it can see and
  ;; cannot call is a restriction it will hunt a workaround for.
  (let [thread-id "sa-specs"
        defn      (definition "explore")
        end!      (subagents/begin! thread-id {:parent "sa-parent"
                                               :definition defn
                                               :table (subagents/table-for "sa-parent" defn)})
        names     (try (set (map #(get-in % [:function :name]) (tools/specs thread-id)))
                       (finally (end!)))]
    (is (contains? names "read"))
    (is (not (contains? names "write")))
    (is (not (contains? names "agent")))
    (is (contains? (set (map #(get-in % [:function :name]) (tools/specs "sa-parent")))
                   "write")
        "while the delegating session still has everything")))

(deftest every-thread-that-is-not-a-subagents-serves-everything
  (is (not (contains? (set (keys (subagents/table-for "sa-parent" (definition "explore"))))
                      "write"))
      "the range is narrow")
  (is (tools/served? "sa-ordinary" "write")
      "and a thread with no live entry is not narrowed by it at all"))

;; ------------------------------------------------------- the definitions check

(deftest a-definition-is-refused-by-name-and-for-its-own-reason
  (testing "a blank name"
    (is (thrown-with-msg? Exception #"needs a name" (subagents/check-entry! "  " {:baseline :all}))))
  (testing "an entry that is not a map"
    (is (thrown-with-msg? Exception #"must be an EDN map" (subagents/check-entry! "x" [:all]))))
  (testing "a key the shape does not have"
    ;; This is the one that could look like a way to put a tool BACK into a range.
    (is (thrown-with-msg? Exception #"does not understand"
                          (subagents/check-entry! "x" {:baseline :all :allow ["eval"]}))))
  (testing "a baseline that is neither of the two"
    (is (thrown-with-msg? Exception #"baseline of \"x\" must be"
                          (subagents/check-entry! "x" {:baseline :read-write}))))
  (testing "an exclusion naming a tool that does not exist"
    (is (thrown-with-msg? Exception #"is not a tool this harness knows"
                          (subagents/check-entry! "x" {:baseline :all :exclude ["nope"]}))))
  (testing "and an exclusion naming one of the two forbidden tools"
    ;; Those are never in ANY range, so excluding one asks for something the entry
    ;; cannot be honoured for -- and a request that cannot be honoured is refused
    ;; rather than dropped.
    (doseq [n ["eval" "agent"]]
      (is (thrown-with-msg? Exception #"never served to any subagent"
                            (subagents/check-entry! "x" {:baseline :all :exclude [n]})))))
  (testing "a legal one comes back whole"
    (is (= {:name "x" :description "" :baseline :read-only :exclude ["read"]}
           (subagents/check-entry! "x" {:baseline :read-only :exclude ["read"]})))))

;; -------------------------------------------------------- harness.edn, the file

(deftest a-user-entry-replaces-a-built-in-in-place
  (write-user-harness-edn! {:subagents {"explore" {:baseline :read-only
                                                   :exclude ["read"]
                                                   :description "a narrower explorer"}}})
  (let [defs (definitions)]
    (is (nil? (:problem defs)))
    (is (= ["general" "explore"] (subagents/known-names defs))
        "an entry for a built-in's name keeps its place at the top")
    (is (= "a narrower explorer" (:description (definition "explore"))))
    (is (= ["read"] (:exclude (definition "explore"))))
    (is (= :all (:baseline (definition "general"))) "and the other built-in is untouched")))

(deftest a-custom-entry-is-appended-to-the-built-ins
  (write-user-harness-edn! {:subagents {"auditor" {:baseline :read-only
                                                   :exclude ["web_fetch"]
                                                   :description "reads only files"}}})
  (let [defs (definitions)]
    (is (nil? (:problem defs)))
    (is (= ["general" "explore" "auditor"] (subagents/known-names defs)))
    (is (= [:all :read-only :read-only] (mapv :baseline (:subagents defs))))))

(deftest a-broken-entry-is-reported-and-the-built-ins-stay
  ;; TOLERANT ON PURPOSE: reading is asked on the way to every request, so a
  ;; reader that threw would turn one typo in an optional block into a harness
  ;; that cannot talk to a model at all. The first thing it could not honour is
  ;; reported instead, and the delegation is what refuses.
  (write-user-harness-edn! {:subagents {"broken" {:baseline :read-write}}})
  (let [defs (definitions)]
    (is (str/includes? (:problem defs) "baseline")
        "the problem says what was wrong")
    (is (= ["general" "explore"] (subagents/known-names defs))
        "and the built-ins are exactly as they were")))

(deftest a-subagents-block-that-is-not-a-map-is-reported-not-obeyed
  (write-user-harness-edn! {:subagents ["general"]})
  (let [defs (definitions)]
    (is (str/includes? (:problem defs) "must be a map"))
    (is (= ["general" "explore"] (subagents/known-names defs)))))

(deftest the-report-names-the-file-a-person-has-to-open
  (write-user-harness-edn! {:subagents {"broken" {:baseline :nope}}})
  (is (= (.getAbsolutePath (io/file (home/root) "harness.edn"))
         (:path (definitions)))
      "a broken block is only actionable once the reader knows which file to open"))

;; ------------------------------------------------ what a panel reads, and wrote

(defn- file-of [n] (io/file (home/root) n))

(defn- text-of
  "The named file's bytes, or nil when it is not there -- nil and \"\" are different
  answers, and 'a refusal created the file it refused to write' is only visible if
  the test can tell them apart."
  [n]
  (let [f (file-of n)] (when (.exists f) (slurp f :encoding "UTF-8"))))

(defn- home-snapshot
  "Every file in the configuration home with its size -- what 'the home did not move'
  means, measured over the whole home rather than asserted about the one file a
  write happens to name. A save that created a `.bak`, or a `harness.edn` in a home
  that had none, shows up here."
  []
  (->> (file-seq (io/file (home/root)))
       (filter #(.isFile %))
       (map (fn [f] [(.getName f) (.length f)]))
       sort
       vec))

(defn- put!
  "A save, in the shape the settings form sends: the baseline as the string JSON
  has it, the exclusions as an array."
  ([name row] (subagents/put-definition! name row false))
  ([name row replace?] (subagents/put-definition! name row replace?)))

(deftest a-panel-can-tell-a-built-in-from-somebodys-own
  ;; The screens' whole button logic reads this one field -- a built-in gets Edit
  ;; and no Remove -- so it is asserted here rather than left to a client to
  ;; re-derive from a hard-coded list of two names.
  (is (true? (:builtin (subagents/wire-definition (definition "general")))))
  (put! "auditor" {:baseline "read-only" :description "reads only"})
  (is (false? (:builtin (subagents/wire-definition (definition "auditor")))))
  (is (vector? (:exclude (subagents/wire-definition (definition "general"))))
      "and the row carries the shape the screen draws: baseline plus exclusions"))

(deftest a-save-writes-the-block-it-owns-and-leaves-every-other-key-alone
  (write-user-harness-edn! {:editing {:mode :hashline}
                            :skills {:roots ["~/skills"]}
                            :subagents {"explore" {:baseline :read-only
                                                   :exclude ["read"]
                                                   :description "was here first"}}})
  (let [before (text-of "harness.edn")]
    (put! "auditor" {:baseline "read-only" :description "reads and reports"
                     :exclude ["web_search"]})
    (let [raw (edn/read-string (text-of "harness.edn"))]
      (is (= {:mode :hashline} (:editing raw)) "the keys this namespace does not own come through")
      (is (= {:roots ["~/skills"]} (:skills raw)))
      (is (= {:baseline :read-only :exclude ["read"] :description "was here first"}
             (get-in raw [:subagents "explore"]))
          "and so does the entry the save did not touch")
      (is (= {:baseline :read-only :exclude ["web_search"] :description "reads and reports"}
             (get-in raw [:subagents "auditor"])))
      (is (= "was here first" (:description (definition "explore")))
          "the reader sees the same thing the file says")
      (is (= ["general" "explore" "auditor"] (subagents/known-names (definitions)))
          "a custom name is appended after the built-ins"))
    (is (= before (text-of "harness.edn.bak"))
        "and the version it replaced is beside it, whole")))

(deftest a-second-save-takes-the-spring-backup-rather-than-the-first
  ;; One generation, stated as a claim: the backup is the file as it stood BEFORE
  ;; this write, not the file as it stood before some earlier one.
  (put! "auditor" {:baseline "read-only" :description "first"})
  (let [first-write (text-of "harness.edn")]
    (put! "auditor" {:baseline "read-only" :description "second"} true)
    (is (= first-write (text-of "harness.edn.bak")))
    (is (= "second" (:description (definition "auditor"))))))

(deftest a-new-name-is-refused-when-it-is-already-taken
  (testing "without the caller saying it means to replace"
    (let [before (home-snapshot)]
      (is (thrown-with-msg? Exception #"already a subagent called \"explore\""
                            (put! "explore" {:baseline "read-only" :description "mine now"})))
      (is (= before (home-snapshot)) "and nothing was written at all -- not even a backup")
      (is (not= "mine now" (:description (definition "explore"))))))
  (testing "and the same name with replace? takes it over"
    (put! "explore" {:baseline "read-only" :description "mine now"} true)
    (is (= "mine now" (:description (definition "explore"))))
    (is (true? (:builtin (subagents/wire-definition (definition "explore"))))
        "a built-in somebody rewrote is still a built-in: the name is what the code supplies")))

(deftest every-refusal-a-form-can-show-leaves-the-home-byte-for-byte
  ;; THE PROMISE THE FORM MAKES WHEN IT KEEPS THE FORM OPEN: the sentence is the
  ;; whole of what happened. Measured over the home rather than over harness.edn,
  ;; because the failure worth catching is a REFUSAL THAT CREATED SOMETHING -- a
  ;; backup, or a file in a home that had none.
  (testing "in a home that has no harness.edn yet"
    (let [before (home-snapshot)]
      (doseq [[what thunk] [["a blank name"      #(put! "  " {:baseline "all"})]
                            ["a baseline nobody knows" #(put! "x" {:baseline "read/write"})]
                            ["an exclusion naming no tool" #(put! "x" {:baseline "all"
                                                                       :exclude ["nope"]})]
                            ["an exclusion naming eval" #(put! "x" {:baseline "all"
                                                                    :exclude ["eval"]})]
                            ["an unknown key" #(put! "x" {:baseline "all" :allow ["eval"]})]]]
        (is (thrown? Exception (thunk)) what)
        (is (nil? (text-of "harness.edn")) (str what " -- and no file appeared"))
        (is (nil? (text-of "harness.edn.bak")) (str what " -- and no backup either")))
      (is (= before (home-snapshot)))))
  (testing "and in one that has a file"
    (write-user-harness-edn! {:editing {:mode :hashline}})
    (let [before (home-snapshot)]
      (doseq [thunk [#(put! "x" {:baseline "read/write"})
                     #(put! "x" {:baseline "all" :exclude ["eval"]})
                     #(subagents/remove-definition! "general")]]
        (is (thrown? Exception (thunk))))
      (is (= before (home-snapshot)) "the file, its bytes and its backups are all where they were"))))

(deftest a-save-is-refused-while-the-file-holds-an-entry-nobody-can-honour
  ;; The reader is tolerant and the WRITER is not, and this is the case that tells
  ;; them apart: a harness.edn somebody hand-edited into a broken entry has to leave
  ;; a harness that still runs, so reading reports it and carries on. A save is a
  ;; person asking for a change, and rewriting a block the next read would refuse is
  ;; the one outcome worse than refusing the save.
  (write-user-harness-edn! {:subagents {"broken" {:baseline :nope}}})
  (let [before (home-snapshot)]
    (is (thrown-with-msg? Exception #"baseline of \"broken\" must be"
                          (put! "auditor" {:baseline "read-only"})))
    (is (= before (home-snapshot)) "and the file it could not read is exactly as the person left it")))

(deftest only-a-definition-somebody-wrote-can-be-removed
  (testing "a custom one goes, and the built-ins stay"
    (put! "auditor" {:baseline "read-only" :description "reads only"})
    (is (= ["general" "explore" "auditor"] (subagents/known-names (definitions))))
    (is (= "auditor" (:name (subagents/remove-definition! "auditor"))))
    (is (= ["general" "explore"] (subagents/known-names (definitions))))
    (is (contains? (edn/read-string (text-of "harness.edn")) :subagents)
        "the block stays, empty of custom entries -- removing one is not removing the key"))
  (testing "a built-in cannot, and the refusal says what to do instead"
    (let [before (home-snapshot)]
      (is (thrown-with-msg? Exception #"is built in.*Edit it instead"
                            (subagents/remove-definition! "explore")))
      (is (= before (home-snapshot)))))
  (testing "and a name the file does not hold is refused by name"
    (is (thrown-with-msg? Exception #"no subagent called \"nobody\"" 
                          (subagents/remove-definition! "nobody")))))

(deftest a-definition-replaced-keeps-its-place-in-the-list
  ;; The order is a fact the model reads: the delegating tool's description lists
  ;; the subagents, and a built-in that drifted to the bottom because somebody
  ;; edited it would be a list whose order depends on when things were touched.
  (put! "auditor" {:baseline "read-only" :description "one"})
  (put! "general" {:baseline "all" :description "mine"} true)
  (put! "auditor" {:baseline "all" :description "two"} true)
  (is (= ["general" "explore" "auditor"] (subagents/known-names (definitions)))))
