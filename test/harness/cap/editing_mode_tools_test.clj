(ns harness.cap.editing-mode-tools-test
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
            [harness.cap.editing :as editing]
            [harness.infra.home :as home]
            [harness.cap.project :as project]
            [harness.kernel.tools :as tools]
            [harness.test-support :as support]))

(use-fixtures :once support/with-builtins)

(def ^:private root (support/temp-dir "editing-mode-tools"))

;; TWO ROOTS, because a session is bound to a DIRECTORY and some of these cases are about
;; what a BOUND session is served. The mode itself is no longer per project: :editing lives
;; in this home's config.edn, so two threads read the same one
;; (.scratch/config-merge/spec.md -- the project level is gone).
(def ^:private other-root (support/temp-dir "editing-mode-tools-other"))



;; Same discipline as editing_test: the user level lives outside this namespace,
;; so a leftover would silently change the NEXT namespace's toolset.
(defn- wipe-harness-edn [f]
  (support/wipe-session!)
  (f)
  (support/wipe-session!))

(use-fixtures :each wipe-harness-edn)

(defn- set-mode!
  "Bind THREAD-ID to DIR and have this home's config.edn select MODE. Read fresh per ask, so
  a later call can move the mode -- there is one level now, so that write is the whole
  home's mode and not DIR's."
  [thread-id dir mode]
  (project/bind! thread-id dir)
  (support/write-session! (str "{:editing {:mode " mode "}}")))

(defn- spec-names
  ([thread-id] (mapv #(get-in % [:function :name]) (tools/specs thread-id))))

(def ^:private anchor-tools
  #{"replace" "insert" "grep" "undo_last_replace"})

(def ^:private str-replace-tools
  #{"edit"})

(defn- non-editing-names
  "The names in a session's toolset that belong to NO editing implementation --
  the ones BOTH modes serve, so a mode assertion can be stated as 'everything
  else is untouched' without listing the editing tools at all."
  [thread-id]
  (remove (into anchor-tools str-replace-tools) (spec-names thread-id)))

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

;; ------------------------------------------------------- which mode is default

(deftest the-default-toolset-is-the-anchor-one
  ;; THE FLIP, pinned. Ticket 12 moved the default from the exact-string editor to
  ;; anchor editing, and this is the assertion that would move first if somebody
  ;; changed it back by accident -- or changed it to something that is neither.
  (is (= ["ask" "bash" "eval" "glob" "grep" "insert" "job" "job_kill" "job_list"
          "job_output" "read" "replace" "skill" "todo_read" "todo_write"
          "undo_last_replace" "web_fetch" "web_search" "write"]
         (spec-names nil))
      "an unconfigured process is served the anchor toolset")
  (testing "and a thread with no project file is served the same"
    (is (= (spec-names nil) (spec-names "emt-default")))))

(deftest both-modes-are-complete-and-differ-by-exactly-the-editing-tools
  ;; The meta-assertion the flip needs: each mode's toolset is WELL-FORMED (no
  ;; duplicates, every entry named and described), and the two differ by the
  ;; editing tools and nothing else. Stated as a comparison rather than as two
  ;; hard-coded lists, so a tool added to neither family shows up as belonging to
  ;; both -- which is what it should.
  (set-mode! "emt-meta-anchor" root ":hashline")
  (let [anchor-names (spec-names "emt-meta-anchor")
        specs        (tools/specs "emt-meta-anchor")]
    (testing "no duplicate names, and every entry has a name and a description"
      (is (= (count anchor-names) (count (set anchor-names))))
      (is (every? #(seq (get-in % [:function :description])) specs)))
    (set-mode! "emt-meta-strrep" other-root ":str-replace")
    (let [strrep-names (spec-names "emt-meta-strrep")]
      (is (= (count strrep-names) (count (set strrep-names))))
      (testing "the difference is exactly the editing tools, both directions"
        (is (= (set (remove (into anchor-tools str-replace-tools) anchor-names))
               (set (remove (into anchor-tools str-replace-tools) strrep-names)))
            "the non-editing tools are the same set in both modes")
        (is (= anchor-tools (set (remove (set strrep-names) anchor-names))))
        (is (= str-replace-tools (set (remove (set anchor-names) strrep-names))))))))

(deftest each-mode-runs-its-own-whole-path
  ;; The spec's end-to-end mainline, run once per mode through the SAME seam the
  ;; model uses: a real file, real answers, nothing else in the session. The
  ;; per-tool suites already cover each step in depth; what this adds is that a
  ;; session in either mode can get from a read to a change and back, and that the
  ;; mode it is NOT in is refused rather than half-working.
  (let [p (io/file root "mode-path.txt")]
    (testing "anchor mode: read -> replace -> undo_last_replace"
      (set-mode! "emt-path" root ":hashline")
      (spit p "alpha\nbeta\ngamma\n" :encoding "UTF-8")
      (let [out     (:content (call "emt-path" "read" {:path (str p)}))
            rows    (str/split-lines out)
            anchors (mapv #(subs % 0 (str/index-of % "│")) rows)]
        (is (= 3 (count rows)))
        (is (every? #(re-matches #"[A-Za-z0-9]{4}│.*" %) rows)
            "the read came back as anchor rows")
        (is (false? (:error (call "emt-path" "replace" {:remove_from (second anchors)
                                                        :replacement_lines ["BETA"]}))))
        (is (= "alpha\nBETA\ngamma\n" (slurp p :encoding "UTF-8")))
        (is (false? (:error (call "emt-path" "undo_last_replace" {:path (str p)}))))
        (is (= "alpha\nbeta\ngamma\n" (slurp p :encoding "UTF-8"))
            "and the file is back where it started")))
    (testing "str-replace mode: read -> edit, and the anchor tools are not served"
      (set-mode! "emt-path" root ":str-replace")
      (is (= "alpha\nbeta\ngamma\n" (:content (call "emt-path" "read" {:path (str p)})))
          "plain text, no anchor column")
      (is (false? (:error (call "emt-path" "edit" {:path (str p)
                                                   :old_string "beta"
                                                   :new_string "BETA"}))))
      (is (= "alpha\nBETA\ngamma\n" (slurp p :encoding "UTF-8")))
      (testing "and the step this mode does not have is named as such"
        (let [{:keys [content error]} (call "emt-path" "undo_last_replace" {:path (str p)})]
          (is (true? error))
          (is (str/includes? content "not served"))
          (is (str/includes? content "old_string")
              "saying what THIS session edits by")
          (is (str/includes? content ":editing") "and which key switches the mode")
          (is (= "alpha\nBETA\ngamma\n" (slurp p :encoding "UTF-8"))
              "and nothing was undone behind the refusal"))))))

;; ------------------------------------------------- hashline subtracts edit

(deftest hashline-mode-does-not-serve-edit
  (set-mode! "emt-anchor" root ":hashline")
  (let [names (spec-names "emt-anchor")]
    (is (not (contains? (set names) "edit")))
    (testing "and everything that is not an editing tool is untouched"
      (is (= ["ask" "bash" "eval" "glob" "job" "job_kill" "job_list" "job_output" "read"
              "skill" "todo_read" "todo_write" "web_fetch" "web_search" "write"]
             (non-editing-names "emt-anchor"))))))

(deftest str-replace-mode-does-not-serve-the-anchor-tools
  ;; The other direction, and it is not symmetry for its own sake: `edit` is the
  ;; only editor available in this mode, so a session that chose it must not be
  ;; served a second, incompatible one alongside.
  (set-mode! "emt-strrep" root ":str-replace")
  (let [names (spec-names "emt-strrep")]
    (testing "none of the anchor tools reach the model"
      (is (not-any? #(contains? (set names) %) anchor-tools)))
    (testing "and `edit` does"
      (is (contains? (set names) "edit")))
    (testing "with everything else untouched"
      (is (= ["ask" "bash" "eval" "glob" "job" "job_kill" "job_list" "job_output" "read"
              "skill" "todo_read" "todo_write" "web_fetch" "web_search" "write"]
             (non-editing-names "emt-strrep"))))))

(deftest a-session-added-tool-is-served-by-the-filter-not-by-the-mode
  ;; The stand-in half of this file's original shape, kept because it proves a
  ;; property the real tools cannot: the filter works on NAMES. A session that
  ;; registers its own tool under an anchor-mode name gets it served in anchor
  ;; mode and withheld in string mode, with no code anywhere that knows about it.
  (let [ran (atom [])]
    (set-mode! "emt-standin" root ":hashline")
    (tools/session-register! "emt-standin" "grep" (stub ran))
    (is (contains? (set (spec-names "emt-standin")) "grep"))
    (set-mode! "emt-standin" root ":str-replace")
    (is (not (contains? (set (spec-names "emt-standin")) "grep"))
        "the mode subtracts it by name, having no idea what it is")))

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

;; `two-sessions-with-different-modes-do-not-collide` USED TO LIVE HERE, and it is deleted
;; rather than rewritten: its whole premise was that the mode resolves per thread, which is
;; what the project level bought. With ONE level -- this home's config.edn -- two sessions
;; cannot hold two modes, so there is no property left to pin. What replaces it is the
;; assertion above that a write to config.edn moves the mode for the session that reads it.
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
    (is (= :hashline (:mode (editing/editing-mode tools/*thread-id*)))))
  (testing "and unbinding the thread drops the project level"
    (project/bind! "emt-ask" nil)
    (is (= :hashline (:mode (editing/editing-mode "emt-ask"))))))

(deftest a-broken-harness-edn-fails-where-it-is-read-not-silently-the-other-way
  ;; The mode is read while the provider request is BUILT, so a broken block now
  ;; fails a run rather than one tool call. That is deliberate and it is this
  ;; repo's discipline: a config that says something unreadable must not be
  ;; silently equivalent to a config that says nothing -- here that would mean a
  ;; project that asked for anchors quietly getting string replacement.
  (project/bind! "emt-broken" root)
  (support/write-session! {:editing {:mode :nonsense}})
  (is (thrown-with-msg? Exception #":hashline or :str-replace"
                        (tools/specs "emt-broken")))
  (testing "and the failure names the file, so it is fixable in place"
    (let [m (try (tools/specs "emt-broken") nil (catch Exception e (ex-message e)))]
      (is (str/includes? m (.getAbsolutePath (home/config-file)))))))
