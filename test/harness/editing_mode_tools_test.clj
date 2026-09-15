(ns harness.editing-mode-tools-test
  "The editing mode's subtraction: which tools a session's toolset is built from,
  and what a call to a tool it does not serve is told.

  Ticket 03's mechanism, tested on its own because it is a rule about NAMES
  rather than about any particular tool: the mode subtracts the other family and
  refuses calls to it by name. The anchor-mode tools themselves arrive in later
  tickets, so the family that has not shipped yet is exercised through
  session-registered stand-ins -- which is exactly the property worth having, the
  filter having no per-tool code and picking up a tool the moment it exists."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [harness.editing :as editing]
            [harness.home :as home]
            [harness.project :as project]
            [harness.tools :as tools]))

(def ^:private root
  (str (System/getProperty "java.io.tmpdir") "/harness-editing-mode-tools-test"))

;; A SECOND project directory, because the mode is a property of the DIRECTORY a
;; session is bound to -- two threads sharing one project share its mode, which is
;; the point of putting :editing in the project-level file at all.
(def ^:private other-root
  (str (System/getProperty "java.io.tmpdir") "/harness-editing-mode-tools-other"))

(io/delete-file root true)
(io/delete-file other-root true)
(.mkdirs (io/file root))
(.mkdirs (io/file other-root))

(def ^:private user-file (io/file (home/root) "harness.edn"))

(defn- project-file [dir] (io/file dir ".harness" "harness.edn"))

;; Same discipline as editing_test: the user level lives outside this namespace,
;; so a leftover would silently change the NEXT namespace's toolset.
(defn- wipe-harness-edn [f]
  (io/delete-file user-file true)
  (io/delete-file (project-file root) true)
  (io/delete-file (project-file other-root) true)
  (f)
  (io/delete-file user-file true)
  (io/delete-file (project-file root) true)
  (io/delete-file (project-file other-root) true))

(use-fixtures :each wipe-harness-edn)

(defn- set-mode!
  "Bind THREAD-ID to DIR and have DIR's harness.edn select MODE. Read fresh per
  ask, so a later call can move the same thread to the other mode -- which the
  isolation test relies on."
  [thread-id dir mode]
  (project/bind! thread-id dir)
  (let [f (project-file dir)]
    (.mkdirs (.getParentFile f))
    (spit f (str "{:editing {:mode " mode "}}") :encoding "UTF-8")))

(defn- spec-names
  ([thread-id] (mapv #(get-in % [:function :name]) (tools/specs thread-id))))

(defn- call [thread-id name args]
  (tools/run! {:function {:name name :arguments (json/write-str args)}} thread-id))

(defn- call-with-phases [thread-id name args]
  (let [seen (atom [])
        result (tools/run! {:function {:name name :arguments (json/write-str args)}}
                           thread-id #(swap! seen conj %))]
    {:result result :phases (mapv :type @seen) :outcomes (keep :outcome @seen)}))

(defn- stub
  "A stand-in for a tool a later ticket will register for real. Its body records
  that it RAN -- the assertion in most of these tests is that it did not."
  [ran]
  {:description "anchor-mode stand-in"
   :parameters  {:type "object" :properties {} :required []}
   :required    []
   :run         (fn [_] (swap! ran conj :ran) "ran")})

;; ------------------------------------------------ the default is unmoved

(deftest the-default-toolset-is-what-it-always-was
  ;; The regression guarantee this whole opt-in plan rests on. Ticket 03 has to
  ;; land without moving a single existing assertion, and this is the one that
  ;; would move first if the filter were wired wrong.
  (is (= ["bash" "edit" "eval" "read" "session-configure" "write"] (spec-names nil))
      "an unconfigured process is served the base, `edit` and all")
  (testing "and a thread with no project file is served the same"
    (is (= (spec-names nil) (spec-names "emt-default")))))

;; ------------------------------------------------- hashline subtracts edit

(deftest hashline-mode-does-not-serve-edit
  (set-mode! "emt-anchor" root ":hashline")
  (let [names (spec-names "emt-anchor")]
    (is (not (contains? (set names) "edit")))
    (testing "and everything that is not an editing tool is untouched"
      (is (= ["bash" "eval" "read" "session-configure" "write"]
             (remove #{"edit" "replace" "insert" "anchor_grep" "undo_last_replace"} names))))))

(deftest str-replace-mode-does-not-serve-the-anchor-tools
  ;; Exercised through stand-ins: the anchor tools land in 04-07, and this test
  ;; is about the FILTER, not about those tools' bodies. Registering them by name
  ;; for one session is the honest way to prove the rule without shipping a tool
  ;; that cannot yet do its job -- which would be the worse lie.
  (let [ran (atom [])]
    (doseq [n ["replace" "insert" "anchor_grep" "undo_last_replace"]]
      (tools/session-register! "emt-strrep" n (stub ran)))
    (testing "in the default mode none of them reach the model"
      (is (not-any? #(contains? (set (spec-names "emt-strrep")) %)
                    ["replace" "insert" "anchor_grep" "undo_last_replace"])))
    (testing "in hashline mode the same registrations are served"
      (set-mode! "emt-strrep" root ":hashline")
      (is (every? #(contains? (set (spec-names "emt-strrep")) %)
                  ["replace" "insert" "anchor_grep" "undo_last_replace"]))
      (is (not (contains? (set (spec-names "emt-strrep")) "edit"))))))

(deftest the-mode-sees-every-registered-tool-even-the-ones-it-does-not-serve
  ;; 'Not in the toolset' and 'not in the registry' are different claims: the
  ;; first is policy, the second is a fact. An agent asking what it HAS must not
  ;; be told the anchor tools do not exist just because this session edits by
  ;; string.
  (set-mode! "emt-introspect" root ":str-replace")
  (let [have (keys (tools/effective-tools "emt-introspect"))]
    (is (contains? (set have) "edit"))
    (testing "a name registered for this session is in the map whether or not
              the mode serves it"
      (tools/session-register! "emt-introspect" "replace" (stub (atom [])))
      (is (contains? (set (keys (tools/effective-tools "emt-introspect"))) "replace"))
      (is (not (contains? (set (spec-names "emt-introspect")) "replace"))
          "present in the registry, absent from the served toolset"))))

;; ------------------------------------------------------- the refusal speaks

(deftest an-unserved-call-is-refused-by-name-with-the-way-out
  (set-mode! "emt-refuse" root ":hashline")
  (let [{:keys [content error]} (call "emt-refuse" "edit" {:path "x" :old_string "a" :new_string "b"})]
    (is (true? error) "information for the model, not a run failure")
    (testing "it names the tool it refused"
      (is (str/includes? content "edit")))
    (testing "it says what this session edits by instead, and what to reach for"
      (is (str/includes? content "anchor"))
      (is (str/includes? content "replace")))
    (testing "it says which config key switches back -- the capability is one
              line away, and the message says which line"
      (is (str/includes? content ":editing"))
      (is (str/includes? content ":mode"))
      (is (str/includes? content ":str-replace")))
    (testing "and it is never 'unknown tool' -- the tool exists"
      (is (not (str/includes? content "unknown tool"))))))

(deftest the-refusal-goes-the-other-way-too
  (set-mode! "emt-refuse-anchor" root ":str-replace")
  (tools/session-register! "emt-refuse-anchor" "replace" (stub (atom [])))
  (let [{:keys [content]} (call "emt-refuse-anchor" "replace" {:remove_from "Hasu"})]
    (is (str/includes? content "replace"))
    (is (str/includes? content "edit") "the substitute for an unserved anchor tool")
    (is (str/includes? content ":hashline") "and the mode that would serve it")))

(deftest the-refusal-comes-before-the-other-checks
  (set-mode! "emt-order" root ":hashline")
  (let [{:keys [outcomes phases]} (call-with-phases "emt-order" "edit" {})]
    (testing "no required argument is read: the call is refused on the mode alone"
      (is (= [:unserved] outcomes))
      (is (not-any? #{:missing-args} outcomes)))
    (testing "the lifecycle still closes -- execute is skipped, post always arrives"
      (is (= [:tool/pre-execute :tool/post-execute] phases))))
  (testing "and it does not park: a call that cannot run has no business
            interrupting a person, however out-of-bounds its path looks"
    (is (nil? (:parked (call "emt-order" "edit" {:path "/etc/passwd"
                                                 :old_string "a" :new_string "b"}))))
    (is (= [:unserved] (:outcomes (call-with-phases "emt-order" "edit"
                                                    {:path "/etc/passwd"}))))))

;; ---------------------------------------------------- session isolation

(deftest two-sessions-with-different-modes-do-not-collide
  ;; The mode resolves per thread, so the subtraction is per thread too. A
  ;; process-wide mode would make one project's choice another project's -- and
  ;; two projects disagreeing is the everyday case, not an exotic one.
  (set-mode! "emt-a" root ":hashline")
  (set-mode! "emt-b" other-root ":str-replace")
  (.mkdirs (io/file root))
  (.mkdirs (io/file other-root))
  ;; The same relative path in each project: only the str-replace session will be
  ;; allowed to touch its copy.
  (spit (str root "/iso.txt") "x" :encoding "UTF-8")
  (spit (str other-root "/iso.txt") "x" :encoding "UTF-8")
  (testing "the hashline session has no edit; the str-replace session does"
    (is (not (contains? (set (spec-names "emt-a")) "edit")))
    (is (contains? (set (spec-names "emt-b")) "edit")))
  (testing "so the SAME call through each thread is answered differently"
    (let [{:keys [content error]} (call "emt-a" "edit" {:path "iso.txt"
                                                        :old_string "x" :new_string "y"})]
      (is (true? error))
      (is (str/includes? content "not served")))
    (let [{:keys [error]} (call "emt-b" "edit" {:path "iso.txt"
                                                :old_string "x" :new_string "y"})]
      (is (false? error) "the str-replace session ran the real edit path")
      (is (= "y" (slurp (str other-root "/iso.txt") :encoding "UTF-8")))
      (is (= "x" (slurp (str root "/iso.txt") :encoding "UTF-8"))
          "and the refused session's file was never opened"))))

(deftest the-mode-moves-with-the-config-without-a-restart
  (set-mode! "emt-move" root ":str-replace")
  (is (contains? (set (spec-names "emt-move")) "edit"))
  (set-mode! "emt-move" root ":hashline")
  (is (not (contains? (set (spec-names "emt-move")) "edit")))
  (set-mode! "emt-move" root ":str-replace")
  (is (contains? (set (spec-names "emt-move")) "edit")
      "and back -- nothing was cached on the way through"))

;; ----------------------------------------------- disable outranks the mode

(deftest a-session-switch-outranks-the-mode-and-says-so
  (set-mode! "emt-both" root ":hashline")
  (tools/session-register! "emt-both" "replace" (stub (atom [])))
  (testing "disable wins: the answer names the thing the caller themselves did"
    (tools/session-disable! "emt-both" "replace")
    (let [{:keys [content]} (call "emt-both" "replace" {})]
      (is (str/includes? content "disabled"))
      (is (str/includes? content "session-enable!"))))
  (testing "re-enabling brings the tool straight back"
    (tools/session-enable! "emt-both" "replace")
    (is (false? (:error (call "emt-both" "replace" {}))))))

(deftest a-call-that-is-disabled-and-unserved-says-both
  ;; Both facts are true, so both are stated. An answer that said only
  ;; 'disabled' would send the model off to re-enable a tool that would STILL not
  ;; run, and the next call would say the same thing again.
  (set-mode! "emt-doubly" root ":hashline")
  (tools/session-disable! "emt-doubly" "edit")
  (let [{:keys [content]} (call "emt-doubly" "edit" {})]
    (is (str/includes? content "disabled"))
    (is (str/includes? content "replace") "what this session edits with instead")
    (is (str/includes? content ":str-replace") "and the mode that would serve edit")
    (is (not (str/includes? content "session-enable!"))
        "re-enabling is NOT offered, because it would not make the call run")))

;; --------------------------------------------------------- introspection

(deftest the-agent-can-ask-which-mode-it-is-in
  ;; The form prompt.md and the tool bodies use: *thread-id* is bound around a
  ;; tool body, which is the only place this question has a session to answer for.
  (set-mode! "emt-ask" root ":hashline")
  (is (= :hashline (:mode (editing/editing-mode "emt-ask"))))
  (is (= :hashline (:mode (binding [tools/*thread-id* "emt-ask"]
                            (editing/editing-mode tools/*thread-id*)))))
  (testing "unbound, the same call answers for no session at all -- the default"
    (is (= :str-replace (:mode (editing/editing-mode tools/*thread-id*)))))
  (testing "and unbinding the thread drops the project level"
    (project/bind! "emt-ask" nil)
    (is (= :str-replace (:mode (editing/editing-mode "emt-ask"))))))

(deftest a-broken-harness-edn-fails-where-it-is-read-not-silently-the-other-way
  ;; The mode is read while the provider request is BUILT, so a broken block now
  ;; fails a run rather than one tool call. That is deliberate and it is this
  ;; repo's discipline: a config that says something unreadable must not be
  ;; silently equivalent to a config that says nothing -- here that would mean a
  ;; project that asked for anchors quietly getting string replacement.
  (project/bind! "emt-broken" root)
  (let [f (project-file root)]
    (.mkdirs (.getParentFile f))
    (spit f "{:editing {:mode :nonsense}}" :encoding "UTF-8")
    (is (thrown-with-msg? Exception #":hashline or :str-replace"
                          (tools/specs "emt-broken")))
    (testing "and the failure names the file, so it is fixable in place"
      (let [m (try (tools/specs "emt-broken") nil (catch Exception e (ex-message e)))]
        (is (str/includes? m (.getAbsolutePath f)))))))
