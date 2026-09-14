(ns harness.hooks-test
  "harness.hooks' external behavior: the point table, the two-level hooks.edn
  assembly, and the named failures that make a typo loud instead of silent.

  Nothing here spawns anything -- running a declaration is harness.hooks.dispatch's
  business and has its own ticket. What this namespace pins is the DATA the engine
  dispatches over: which points exist, what they may be declared with, and which
  declarations are in force for a thread."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [harness.home :as home]
            [harness.hooks :as hooks]
            [harness.project :as project]))

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
  (testing "a fresh install has no hooks, and every point is wired with nothing listening"
    (let [e (hooks/effective-hooks nil)]
      (is (= 26 (count e)))
      (is (every? empty? (vals e)))))
  (testing "the empty case is the same for a bound thread with no files"
    (project/bind! "h-none" root)
    (try (is (every? empty? (vals (hooks/effective-hooks "h-none"))))
         (finally (project/bind! "h-none" nil)))))

(deftest a-declaration-loads-and-reports-itself
  (user-hooks (pr-str {:stop [{:command "notify.sh" :timeout 2000}]}))
  (let [e (hooks/effective-hooks nil)]
    (is (= [{:command "notify.sh" :timeout 2000}] (:stop e)))
    (testing "every other point is still present and empty"
      (is (= 26 (count e)))
      (is (empty? (:pre-tool-use e))))))

(deftest the-project-level-wins-whole-key-by-whole-key
  (user-hooks (pr-str {:stop [{:command "user.sh"}]
                       :session-start [{:command "user-start.sh"}]}))
  (project-hooks (pr-str {:stop [{:command "project.sh"}]}))
  (project/bind! "h-two" root)
  (try
    (let [e (hooks/effective-hooks "h-two")]
      (testing "the project's :stop REPLACES the user's -- it does not append"
        (is (= [{:command "project.sh"}] (:stop e))))
      (testing "a key the project says nothing about keeps the user's"
        (is (= [{:command "user-start.sh"}] (:session-start e)))))
    (testing "and an unbound thread sees the user level alone"
      (let [e (hooks/effective-hooks "h-unbound")]
        (is (= [{:command "user.sh"}] (:stop e)))))
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
           (:pre-tool-use (hooks/effective-hooks nil)))))
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
