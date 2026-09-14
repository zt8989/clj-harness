(ns harness.hooks-test
  "harness.hooks' external behavior: the point table, the two-level hooks.edn
  assembly, and the named failures that make a typo loud instead of silent.

  Nothing here spawns anything -- running a declaration is harness.hooks.dispatch's
  business and has its own ticket. What this namespace pins is the DATA the engine
  dispatches over: which points exist, what they may be declared with, and which
  declarations are in force for a thread."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [harness.home :as home]
            [harness.hooks :as hooks]
            [harness.hooks.dispatch :as dispatch]
            [harness.project :as project]
            [harness.tools :as tools]))

(def ^:private root (str (System/getProperty "java.io.tmpdir") "/harness-hooks-test"))

(io/delete-file root true)
(.mkdirs (io/file root))

(defn- tmp [name] (str root "/" name))

(defn- write-hooks! [file content]
  (.mkdirs (.getParentFile (io/file file)))
  (spit file content :encoding "UTF-8"))

(defn- user-hooks [content] (write-hooks! (str (home/root) "/hooks.edn") content))
(defn- project-hooks [content] (write-hooks! (str root "/.harness/hooks.edn") content))

;; Both levels are written by these tests, so both are wiped around every test:
;; a leftover would leak into the next namespace's assertions as a hook nobody
;; declared. Same discipline as project_test's harness.edn fixture.
(defn- wipe-hook-files [f]
  (io/delete-file (io/file (home/root) "hooks.edn") true)
  (io/delete-file (io/file root ".harness" "hooks.edn") true)
  (f)
  (io/delete-file (io/file (home/root) "hooks.edn") true)
  (io/delete-file (io/file root ".harness" "hooks.edn") true))

(use-fixtures :each wipe-hook-files)

;; ------------------------------------------------------------- the point table

(deftest the-point-table-is-data-and-holds-every-point
  (testing "26 points, each a row of facts rather than a code path"
    (is (= 26 (count hooks/points)))
    (is (every? (fn [p] (and (string? (:name p))
                             (string? (:when p))
                             (set? (:payload p))
                             (contains? p :gate?)
                             (contains? p :matches)
                             (contains? p :on-error)))
                hooks/points)))
  (testing "the failure policy says what a TIMEOUT or a bad spawn means, per point"
    (testing "a gate cannot decide, so it does not decide yes"
      (is (every? #(= :block (:on-error %))
                  (filter :gate? hooks/points))))
    (testing "an observer never changes the run"
      (is (every? #(= :proceed (:on-error %))
                  (remove :gate? hooks/points)))))
  (testing "the EDN key is derived from the payload name, both spellings tied in one place"
    (is (= "PreToolUse" (:name (hooks/point-for :pre-tool-use))))
    (is (= "SessionStart" (:name (hooks/point-for :session-start))))
    (is (= "PostToolUseFailure" (:name (hooks/point-for :post-tool-use-failure)))))
  (testing "a key that is not a point answers nil -- the caller names the failure"
    (is (nil? (hooks/point-for :pre-tool-use!)))
    (is (nil? (hooks/point-for "pre-tool-use"))))
  (testing "point-keys is what a failure message lists"
    (is (= 26 (count hooks/point-keys)))
    (is (= hooks/point-keys (vec (sort hooks/point-keys))))))

(deftest the-points-with-a-match-target-are-the-tool-and-file-ones
  (is (= #{:pre-tool-use :permission-request :permission-denied
           :post-tool-use :post-tool-use-failure :file-changed}
         (set (for [k hooks/point-keys
                    :when (:matches (hooks/point-for k))]
                k)))))

;; ---------------------------------------------------------- the two-level read

(deftest a-missing-hooks-edn-is-the-empty-configuration
  (testing "a fresh install has no hooks at all"
    (is (empty? (hooks/effective-hooks nil)))
    (is (empty? (hooks/declarations-at nil :stop))))
  (testing "and the same for a bound thread with no files"
    (project/bind! "h-none" root)
    (try (is (empty? (hooks/effective-hooks "h-none")))
         (finally (project/bind! "h-none" nil)))))

(deftest a-declaration-loads-with-an-id-a-session-can-name
  (user-hooks (pr-str {:stop [{:command "notify.sh" :timeout 2000}]}))
  (let [e (hooks/effective-hooks nil)
        d (first (vals e))]
    (testing "it reports the fields it was declared with, plus what it answers to"
      (is (= "notify.sh" (:command d)))
      (is (= 2000 (:timeout d)))
      (is (= :stop (:point d)))
      (is (= :config (:source d)) "it came from a file, not from this session")
      (is (false? (:disabled? d)) "and it is on")
      (is (= "stop#0" (:id d)) "the id is its point and its position in the file"))
    (testing "and declarations-at is the runnable view: the same row"
      (is (= [{:command "notify.sh" :timeout 2000
               :point :stop :source :config :disabled? false :id "stop#0"}]
             (hooks/declarations-at nil :stop))))))

(deftest the-project-level-wins-whole-key-by-whole-key
  (user-hooks (pr-str {:stop [{:command "user.sh"}]
                       :session-start [{:command "user-start.sh"}]}))
  (project-hooks (pr-str {:stop [{:command "project.sh"}]}))
  (project/bind! "h-two" root)
  (try
    (testing "the project's :stop REPLACES the user's -- it does not append"
      (is (= ["project.sh"] (map :command (hooks/declarations-at "h-two" :stop)))))
    (testing "a key the project says nothing about keeps the user's"
      (is (= ["user-start.sh"] (map :command (hooks/declarations-at "h-two" :session-start)))))
    (testing "and an unbound thread sees the user level alone"
      (is (= ["user.sh"] (map :command (hooks/declarations-at "h-unbound" :stop)))))
    (finally (project/bind! "h-two" nil))))

(deftest a-broken-file-fails-by-name-with-its-absolute-path
  (testing "not valid EDN"
    (user-hooks "{:stop [{:command ")
    (let [e (try (hooks/effective-hooks nil) nil (catch Exception e e))]
      (is (some? e))
      (is (str/includes? (ex-message e) "not valid EDN"))
      (is (str/includes? (ex-message e) (str (home/root) "/hooks.edn")))))
  (testing "valid EDN that is not a map"
    (user-hooks "[1 2 3]")
    (let [e (try (hooks/effective-hooks nil) nil (catch Exception e e))]
      (is (str/includes? (ex-message e) "must be an EDN map"))))
  (testing "an unknown hook point names the point and lists the real ones"
    (user-hooks (pr-str {:pre-tool-use! [{:command "x.sh"}]}))
    (let [e (try (hooks/effective-hooks nil) nil (catch Exception e e))]
      (is (str/includes? (ex-message e) ":pre-tool-use!"))
      (is (str/includes? (ex-message e) ":pre-tool-use"))
      (is (str/includes? (ex-message e) ":post-tool-use"))))
  (testing "a point whose value is not a vector of declarations"
    (user-hooks (pr-str {:stop {:command "x.sh"}}))
    (let [e (try (hooks/effective-hooks nil) nil (catch Exception e e))]
      (is (str/includes? (ex-message e) "must be a vector")))))

(deftest a-declaration-is-validated-field-by-field
  (testing "a misspelled field fails and lists the real ones"
    (user-hooks (pr-str {:stop [{:commnd "x.sh"}]}))
    (let [e (try (hooks/effective-hooks nil) nil (catch Exception e e))]
      (is (str/includes? (ex-message e) ":commnd"))
      (is (str/includes? (ex-message e) ":command"))))
  (testing ":command is required and must be a non-empty string"
    (user-hooks (pr-str {:stop [{:timeout 100}]}))
    (is (thrown-with-msg? Exception #"non-empty string :command"
                          (hooks/effective-hooks nil)))
    (user-hooks (pr-str {:stop [{:command ""}]}))
    (is (thrown-with-msg? Exception #"non-empty string :command"
                          (hooks/effective-hooks nil))))
  (testing ":timeout must be a positive whole number"
    (doseq [bad [0 -1 1.5 "1000"]]
      (user-hooks (pr-str {:stop [{:command "x.sh" :timeout bad}]}))
      (is (thrown-with-msg? Exception #":timeout must be a positive whole number"
                            (hooks/effective-hooks nil)))))
  (testing "a declaration that is not a map at all"
    (user-hooks (pr-str {:stop ["x.sh"]}))
    (is (thrown-with-msg? Exception #"must be a map"
                          (hooks/effective-hooks nil)))))

(deftest a-matcher-is-only-for-points-that-match-something
  (testing "a tool point takes one, and it must compile as a regex"
    (user-hooks (pr-str {:pre-tool-use [{:command "gate.sh" :matcher "bash|write"}]}))
    (is (= [{:command "gate.sh" :matcher "bash|write"}]
           (map #(select-keys % [:command :matcher]) (hooks/declarations-at nil :pre-tool-use)))))
  (testing "a broken regex fails at LOAD, not at trigger time"
    (user-hooks (pr-str {:pre-tool-use [{:command "gate.sh" :matcher "bash|("}]}))
    (is (thrown-with-msg? Exception #"not a valid regex"
                          (hooks/effective-hooks nil))))
  (testing "a point with no match target refuses a matcher and says which points take one"
    (user-hooks (pr-str {:stop [{:command "notify.sh" :matcher "x"}]}))
    (let [e (try (hooks/effective-hooks nil) nil (catch Exception e e))]
      (is (str/includes? (ex-message e) "Stop has no match target"))
      (is (str/includes? (ex-message e) ":pre-tool-use"))
      (is (str/includes? (ex-message e) ":file-changed"))))
  (testing "an empty matcher string is refused too"
    (user-hooks (pr-str {:pre-tool-use [{:command "gate.sh" :matcher ""}]}))
    (is (thrown-with-msg? Exception #":matcher must be a non-empty string"
                          (hooks/effective-hooks nil)))))

;; ------------------------------------------- session hooks (eval's new face)

(deftest a-session-grows-a-hook-and-it-answers-to-an-id
  (testing "the add returns the id, and the table shows it as the session's own"
    (let [id (hooks/session-add! "hs-add" :stop {:command "notify.sh"})]
      (is (= "stop@1" id))
      (let [d (get (hooks/effective-hooks "hs-add") id)]
        (is (= :session (:source d)))
        (is (= :stop (:point d)))
        (is (false? (:disabled? d))))))
  (testing "a second add at the same point is a second declaration, not a replacement"
    (let [id1 (hooks/session-add! "hs-two" :stop {:command "a.sh"})
          id2 (hooks/session-add! "hs-two" :stop {:command "b.sh"})]
      (is (not= id1 id2))
      (is (= ["a.sh" "b.sh"] (map :command (hooks/declarations-at "hs-two" :stop))))))
  (testing "session hooks append AFTER the file's, so a file's gate still decides first"
    (user-hooks (pr-str {:stop [{:command "from-file.sh"}]}))
    (hooks/session-add! "hs-order" :stop {:command "from-session.sh"})
    (is (= ["from-file.sh" "from-session.sh"]
           (map :command (hooks/declarations-at "hs-order" :stop))))))

(deftest a-session-hook-is-validated-like-a-file-declaration
  (testing "a point that does not exist is refused by name"
    (let [e (try (hooks/session-add! "hs-bad" :not-a-point {:command "x.sh"})
                 nil (catch Exception e e))]
      (is (str/includes? (ex-message e) ":not-a-point"))
      (is (str/includes? (ex-message e) ":pre-tool-use"))))
  (testing "a declaration that could not run is refused where it was written"
    (is (thrown-with-msg? Exception #"non-empty string :command"
                          (hooks/session-add! "hs-bad" :stop {:command ""})))
    (is (thrown-with-msg? Exception #":timeout must be a positive whole number"
                          (hooks/session-add! "hs-bad" :stop {:command "x.sh" :timeout 0})))
    (is (thrown-with-msg? Exception #"unknown key"
                          (hooks/session-add! "hs-bad" :stop {:command "x.sh" :commnd "y"})))))

(deftest removing-is-for-what-this-session-added-and-nothing-else
  (testing "removing a session hook takes it out of the table"
    (let [id (hooks/session-add! "hs-rm" :stop {:command "x.sh"})]
      (is (some? (get (hooks/effective-hooks "hs-rm") id)))
      (hooks/session-remove! "hs-rm" id)
      (is (nil? (get (hooks/effective-hooks "hs-rm") id)))
      (is (empty? (hooks/declarations-at "hs-rm" :stop)))))
  (testing "removing an on-disk declaration is a no-op: it can only be switched off"
    (user-hooks (pr-str {:stop [{:command "from-file.sh"}]}))
    (hooks/session-remove! "hs-rm2" "stop#0")
    (is (= ["from-file.sh"] (map :command (hooks/declarations-at "hs-rm2" :stop)))))
  (testing "removing an id that was never added is a no-op"
    (hooks/session-remove! "hs-rm3" "stop@99")
    (is (nil? (get (hooks/effective-hooks "hs-rm3") "stop@99")))))

(deftest disabling-switches-a-hook-off-without-hiding-it
  (user-hooks (pr-str {:stop [{:command "from-file.sh"}]}))
  (testing "an on-disk declaration can be switched off, and STAYS readable"
    (hooks/session-disable! "hs-off" "stop#0")
    (testing "the table still shows it -- with the off mark"
      (let [d (get (hooks/effective-hooks "hs-off") "stop#0")]
        (is (some? d) "hiding it would make 'switched off' and 'does not exist' the same lie")
        (is (true? (:disabled? d)))))
    (testing "but nothing at that point will run"
      (is (empty? (hooks/declarations-at "hs-off" :stop))))
    (testing "and enabling brings it straight back"
      (hooks/session-enable! "hs-off" "stop#0")
      (is (= ["from-file.sh"] (map :command (hooks/declarations-at "hs-off" :stop))))))
  (testing "switching off twice, and switching on something never off, are both no-ops"
    (hooks/session-disable! "hs-idem" "stop#0")
    (hooks/session-disable! "hs-idem" "stop#0")
    (is (true? (hooks/session-hook-disabled? "hs-idem" "stop#0")))
    (hooks/session-enable! "hs-idem" "stop#never")
    (is (false? (hooks/session-hook-disabled? "hs-idem" "stop#never"))))
  (testing "switching off an id this session cannot see does not invent it"
    (hooks/session-disable! "hs-ghost" "stop@42")
    (is (nil? (get (hooks/effective-hooks "hs-ghost") "stop@42")))))

(deftest a-removed-hook-does-not-carry-its-off-mark-back
  (let [id (hooks/session-add! "hs-zombie" :stop {:command "x.sh"})]
    (hooks/session-disable! "hs-zombie" id)
    (is (true? (hooks/session-hook-disabled? "hs-zombie" id)))
    (hooks/session-remove! "hs-zombie" id)
    (let [again (hooks/session-add! "hs-zombie" :stop {:command "y.sh"})]
      (is (false? (hooks/session-hook-disabled? "hs-zombie" again))
          "a fresh declaration must not inherit a stale off state"))))

(deftest session-hooks-never-reach-another-thread
  (hooks/session-add! "hs-mine" :stop {:command "mine.sh"})
  (hooks/session-add! "hs-mine" :pre-tool-use {:command "gate.sh"})
  (hooks/session-disable! "hs-mine" "stop#0")
  (testing "another thread sees none of it"
    (is (empty? (hooks/effective-hooks "hs-other")))
    (is (empty? (hooks/declarations-at "hs-other" :pre-tool-use)))
    (is (false? (hooks/session-hook-disabled? "hs-other" "stop#0")))))

(deftest a-disabled-hook-does-not-fire-and-turning-it-back-on-resumes-it
  (let [marker (str root "/fired.txt")
        _      (io/delete-file (io/file marker) true)
        id     (hooks/session-add! "hs-fire" :stop
                                   {:command (str "echo fired > " marker "; exit 0")})]
    (hooks/session-disable! "hs-fire" id)
    (let [audits (atom [])
          r (dispatch/fire {:point :stop :thread-id "hs-fire" :fact {}
                            :audit #(swap! audits conj %) :run-id "r"})]
      (testing "a switched-off hook does not spawn, and is not even an event"
        (is (= :allow (:verdict r)))
        (is (zero? (:matched r)))
        (is (empty? @audits))
        (is (not (.exists (io/file marker))) "the command really did not run"))
      (testing "switching it back on resumes it"
        (hooks/session-enable! "hs-fire" id)
        (let [r (dispatch/fire {:point :stop :thread-id "hs-fire" :fact {}
                                :audit identity :run-id "r"})]
          (is (= 1 (:matched r)))
          (is (.exists (io/file marker))))))))

;; --------------------------------------------- the whole path, through eval

(deftest the-agent-grows-a-hook-through-eval-and-switches-it-off
  ;; End to end through the real eval tool call: the model writes Clojure, the
  ;; session gains a hook, the hook fires, and the model switches it off and back
  ;; on. This is the capability the whole feature moved eval's reason-for-being
  ;; onto, so it is asserted down the real path rather than by calling the fns.
  (let [tid    "t-grow"
        marker (str root "/grew.txt")
        _      (io/delete-file (io/file marker) true)
        call   (fn [code]
                 (tools/run! {:id "c1" :type "function"
                              :function {:name "eval"
                                         :arguments (json/write-str {:code code})}}
                             tid))
        fire   (fn [] (dispatch/fire {:point :stop :thread-id tid :fact {} :run-id "r"}))]
    (testing "adding one is a tool call, and the id comes back readable"
      (let [r (call (str "(harness.hooks/session-add! \"" tid
                         "\" :stop {:command \"echo grew > " marker "; exit 0\"})"))]
        (is (false? (:error r)))
        (let [id (read-string (:content r))]
          (is (= "stop@1" id))
          (testing "and it fires"
            (is (= 1 (:matched (fire))))
            (is (.exists (io/file marker))))
          (testing "switching it off is another call, and it stops firing"
            (io/delete-file (io/file marker) true)
            (is (false? (:error (call (str "(harness.hooks/session-disable! \"" tid "\" \"" id "\")")))))
            (is (= 0 (:matched (fire))))
            (is (not (.exists (io/file marker))) "the disabled hook really did not run")
            (testing "another session cannot see or touch it"
              (is (empty? (hooks/effective-hooks "t-other-session")))
              (is (empty? (hooks/declarations-at "t-other-session" :stop))))
            (testing "switching it back on resumes it"
              (is (false? (:error (call (str "(harness.hooks/session-enable! \"" tid "\" \"" id "\")")))))
              (is (= 1 (:matched (fire))))
              (is (.exists (io/file marker)))))
          (testing "and the session can read its own table back"
            (let [r (call (str "(keys (harness.hooks/effective-hooks \"" tid "\"))"))]
              (is (false? (:error r)))
              (is (str/includes? (:content r) "stop@1")))))))))
