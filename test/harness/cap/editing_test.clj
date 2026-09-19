(ns harness.cap.editing-test
  "harness.cap.editing's external behavior: which editing implementation a session is
  served by, how the two harness.edn levels compose, and what a broken block
  says. The regression guarantee is the first test -- with nobody saying
  anything, a session is str-replace, which is what this harness already was."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [harness.cap.editing :as editing]
            [harness.infra.home :as home]
            [harness.test-support :as support]
            [harness.cap.project :as project]))

;; One scratch project directory for the whole namespace. mkdtemp makes it, so there
;; is nothing to clear at load time and nothing for a second run to collide with
;; (the tools-test precedent).
(def ^:private root (support/temp-dir "editing"))

(def ^:private user-file    (io/file (home/root) "harness.edn"))
(def ^:private project-file (io/file root ".harness" "harness.edn"))

;; Both levels live OUTSIDE this namespace's scratch directory: the user level in
;; the (shared, test-runner-owned) config home, the project level under the
;; shared root. Wipe them around every test -- a leftover :editing would leak
;; into the next namespace as a silently different editing mode, which is the
;; exact confusion this namespace exists to refuse.
(defn- wipe-harness-edn [f]
  (io/delete-file user-file true)
  (io/delete-file project-file true)
  (f)
  (io/delete-file user-file true)
  (io/delete-file project-file true))

(use-fixtures :each wipe-harness-edn)

(defn- write-user! [edn]
  (spit user-file edn :encoding "UTF-8"))

(defn- write-project! [edn]
  (.mkdirs (.getParentFile project-file))
  (spit project-file edn :encoding "UTF-8"))

(defn- ex-data-of [f]
  (try (f) nil (catch Exception e (ex-data e))))

(defn- msg-of [f]
  (try (f) nil (catch Exception e (ex-message e))))

;; ------------------------------------------------------------------ defaults

(deftest the-default-is-anchor-editing
  ;; Ticket 12 flipped this from :str-replace. The assertion is worth keeping
  ;; pointed at the CURRENT default rather than at the value it happened to have
  ;; when ticket 01 landed: what it is for is catching a change nobody meant, and
  ;; the flip was a change somebody did mean.
  (is (= :hashline (:mode (editing/editing-mode))))
  (is (= :hashline (:mode (editing/editing-mode "ed-default"))))
  (testing "and the whole block is answered, every key defaulted"
    (is (= editing/defaults (editing/editing-mode "ed-default"))))
  (testing "and a session that wants the exact-string editor says so, one key"
    (project/bind! "ed-default" root)
    (write-project! "{:editing {:mode :str-replace}}")
    (is (= :str-replace (:mode (editing/editing-mode "ed-default"))))
    (is (= :on (:boundary-dedup (editing/editing-mode "ed-default")))
        "and the other keys keep their defaults -- the block composes by key")))

(deftest a-local-dir-without-the-file-is-just-as-empty
  (.mkdirs (io/file root ".harness"))
  (is (= editing/defaults (editing/editing-mode "ed-emptydir"))))

;; ------------------------------------------------------------------- overlay

(deftest the-two-levels-compose-key-by-key
  ;; This is the ONE departure from harness.edn's shallow merge, and the whole
  ;; reason this namespace composes its own block: a project that wants to turn
  ;; one knob off must not have to restate the block and re-decide every default
  ;; the user chose.
  (project/bind! "ed-overlay" root)
  (write-user! "{:editing {:mode :hashline :diff-context-lines 4}}")
  (testing "the user level alone answers the user level"
    (is (= {:mode :hashline :diff-context-lines 4}
           (select-keys (editing/editing-mode "ed-overlay")
                        [:mode :diff-context-lines]))))
  (testing "the project level adds to it without erasing it"
    (write-project! "{:editing {:auto-read false}}")
    (is (= :hashline (:mode (editing/editing-mode "ed-overlay")))
        "the user's mode survived a project that never mentioned :mode")
    (is (= 4 (:diff-context-lines (editing/editing-mode "ed-overlay"))))
    (is (false? (:auto-read (editing/editing-mode "ed-overlay")))))
  (testing "and a key BOTH levels name is the project's"
    (write-project! "{:editing {:mode :str-replace}}")
    (is (= :str-replace (:mode (editing/editing-mode "ed-overlay")))))
  (testing "the keys nobody named are still the defaults"
    (write-project! "{:editing {:auto-read false}}")
    (is (= :on (:boundary-dedup (editing/editing-mode "ed-overlay")))
        "neither level named :boundary-dedup, so it is the default")
    (is (= 4 (:diff-context-lines (editing/editing-mode "ed-overlay")))
        "and the user's :diff-context-lines is still standing, not reset")))

(deftest an-unbound-session-sees-only-the-user-level
  (write-user! "{:editing {:mode :hashline}}")
  (write-project! "{:editing {:mode :str-replace}}")
  (project/bind! "ed-bound" root)
  (is (= :str-replace (:mode (editing/editing-mode "ed-bound"))))
  (is (= :hashline (:mode (editing/editing-mode "ed-unbound")))
      "an unbound thread has no project file to read"))

(deftest the-block-is-read-fresh-on-every-ask
  ;; The config.edn discipline: editing harness.edn moves the mode without a
  ;; restart. A cached answer would make the file a thing you have to know about
  ;; rather than a thing that works.
  (write-user! "{:editing {:mode :str-replace}}")
  (is (= :str-replace (:mode (editing/editing-mode "ed-fresh"))))
  (write-user! "{:editing {:mode :hashline}}")
  (is (= :hashline (:mode (editing/editing-mode "ed-fresh")))))

;; ------------------------------------------------------- broken is a failure

(deftest a-broken-block-is-a-named-failure-not-a-quiet-default
  (project/bind! "ed-broken" root)
  (testing ":editing that is not a map names the file and the value"
    (write-user! "{:editing :hashline}")
    (let [e (ex-data-of #(editing/editing-mode "ed-broken"))]
      (is (= :editing-not-a-map (:reason e)))
      (is (= "user" (name (:level e))))
      (is (= (.getAbsolutePath user-file) (:path e)))
      (is (str/includes? (msg-of #(editing/editing-mode "ed-broken")) ":hashline"))))
  (testing "the same at the project level names the PROJECT file"
    (write-user! "{}")
    (write-project! "{:editing 42}")
    (let [e (ex-data-of #(editing/editing-mode "ed-broken"))]
      (is (= :editing-not-a-map (:reason e)))
      (is (= "project" (name (:level e))))
      (is (= (.getAbsolutePath project-file) (:path e)))))
  (testing "a file that is not EDN at all fails too, and is not this namespace's
            error to spell -- harness.cap.project owns that message"
    (write-project! "{:editing ")
    (let [e (ex-data-of #(editing/editing-mode "ed-broken"))]
      (is (= :invalid-edn (:reason e))))
    (io/delete-file project-file true))
  (testing "and a silent fallback to the defaults is exactly what must NOT happen"
    (write-user! "{:editing :hashline}")
    (is (= ::threw (try (editing/editing-mode "ed-broken")
                        ::answered
                        (catch Exception _ ::threw))))))

;; ------------------------------------------------------------- key checking

(deftest an-unknown-key-names-the-file-that-wrote-it
  (project/bind! "ed-unknown" root)
  (write-user! "{:editing {:mode :hashline :modes :hashline}}")
  (let [e (ex-data-of #(editing/editing-mode "ed-unknown"))]
    (is (= :unknown-editing-key (:reason e)))
    (is (= :modes (:key e)))
    (is (= "user" (name (:level e))))
    (is (= (.getAbsolutePath user-file) (:path e))))
  (testing "the message lists what IS legal, so the reader can fix it in place"
    (let [m (msg-of #(editing/editing-mode "ed-unknown"))]
      (is (str/includes? m ":mode"))
      (is (str/includes? m ":diff-context-lines")))))

(deftest an-unknown-key-in-either-file-fails-even-when-shadowed
  ;; A typo is not a value that loses a merge: it is a request neither level was
  ;; ever going to honour, so looking at what survived the merge would hide it.
  (project/bind! "ed-shadow-unknown" root)
  (write-user! "{:editing {:modes :hashline}}")
  (write-project! "{:editing {:mode :hashline}}")
  (let [e (ex-data-of #(editing/editing-mode "ed-shadow-unknown"))]
    (is (= :unknown-editing-key (:reason e)))
    (is (= "user" (name (:level e)))
        "the shadowed half is still reported, against the level that wrote it")))

;; ----------------------------------------------------------- value checking

(deftest an-illegal-value-names-the-file-the-key-and-what-is-legal
  (project/bind! "ed-values" root)
  (let [check (fn [edn kw fragment]
                (write-user! edn)
                (let [e (ex-data-of #(editing/editing-mode "ed-values"))
                      m (msg-of #(editing/editing-mode "ed-values"))]
                  (is (= :bad-editing-value (:reason e)) (str "for " edn))
                  (is (= kw (:key e)) (str "for " edn))
                  (is (= "user" (name (:level e))))
                  (is (= (.getAbsolutePath user-file) (:path e)))
                  (is (str/includes? m fragment) (str "message for " edn))
                  (is (str/includes? m (str kw)) "the message names the key")))]
    (testing "an unimplemented mode is refused, and a legal one is offered"
      (check "{:editing {:mode :fuzzy}}" :mode ":hashline or :str-replace"))
    (testing "a legal mode spelled as a string is refused -- the value is a keyword"
      (check "{:editing {:mode \"hashline\"}}" :mode ":hashline or :str-replace"))
    (testing "the booleans take booleans"
      (check "{:editing {:auto-read \"yes\"}}" :auto-read "true or false")
      (check "{:editing {:anchor-grep 1}}" :anchor-grep "true or false")
      (check "{:editing {:require-path nil}}" :require-path "true or false")
      (check "{:editing {:strict-input :on}}" :strict-input "true or false"))
    (testing ":boundary-dedup has its own three values"
      (check "{:editing {:boundary-dedup true}}" :boundary-dedup ":on, :strict or :off"))
    (testing ":diff-context-lines is a bounded integer, and the bound is named"
      (check "{:editing {:diff-context-lines \"2\"}}" :diff-context-lines "an integer 0-10")
      (check "{:editing {:diff-context-lines -1}}" :diff-context-lines "an integer 0-10")
      (check "{:editing {:diff-context-lines 11}}" :diff-context-lines "an integer 0-10")
      (check "{:editing {:diff-context-lines 1.5}}" :diff-context-lines "an integer 0-10"))))

(deftest every-legal-value-is-accepted
  ;; The other half of the message contract: what the failure TELLS you to write
  ;; has to be exactly what passes. A legal set and a predicate that disagree
  ;; would send the reader into a second failure.
  (project/bind! "ed-legal" root)
  (doseq [mode [:hashline :str-replace]
          dedup [:on :strict :off]
          n [0 1 10]]
    (write-user! (str "{:editing {:mode " mode " :boundary-dedup " dedup
                      " :diff-context-lines " n " :auto-read true"
                      " :anchor-grep false :require-path true :strict-input false}}"))
    (let [m (editing/editing-mode "ed-legal")]
      (is (= mode (:mode m)))
      (is (= dedup (:boundary-dedup m)))
      (is (= n (:diff-context-lines m)))
      (is (false? (:anchor-grep m))))))

(deftest the-edges-of-every-legal-range-are-accepted
  ;; The other end of the message contract: the range a failure names has to be
  ;; the range that actually passes, edges included. A message saying "0-10"
  ;; beside a predicate that excludes 0 would send the reader into a second
  ;; failure while doing exactly what they were told.
  (project/bind! "ed-edges" root)
  (doseq [n [0 10]]
    (write-user! (str "{:editing {:diff-context-lines " n "}}"))
    (is (= n (:diff-context-lines (editing/editing-mode "ed-edges")))
        (str "diff-context-lines " n)))
  (doseq [d [:on :strict :off]]
    (write-user! (str "{:editing {:boundary-dedup " d "}}"))
    (is (= d (:boundary-dedup (editing/editing-mode "ed-edges")))
        (str "boundary-dedup " d))))

(deftest values-are-checked-as-effective-not-as-written
  ;; A project overriding a broken user value has to be able to FIX it -- which
  ;; it cannot do if the user's half is validated on its own way through.
  (project/bind! "ed-fix" root)
  (write-user! "{:editing {:mode :nonsense}}")
  (write-project! "{:editing {:mode :hashline}}")
  (is (= :hashline (:mode (editing/editing-mode "ed-fix"))))
  (testing "but the user's half still answers for itself when nothing overrides it"
    (io/delete-file project-file true)
    (is (= :bad-editing-value (:reason (ex-data-of #(editing/editing-mode "ed-fix")))))))

;; ------------------------------------------- harness.edn's own merge is intact

(deftest harness-configs-shallow-merge-is-untouched
  ;; harness-edn-levels was extracted from harness-config so this namespace could
  ;; compose :editing itself. The extraction must not have moved the fence's own
  ;; behavior: a project still REPLACES a top-level key whole.
  (project/bind! "ed-fence" root)
  (write-user! "{:approval {:allow [\"shared\"]} :other 1}")
  (write-project! "{:approval {:strict true}}")
  (is (= {:approval {:strict true} :other 1}
         (project/harness-config "ed-fence")))
  (testing "and the unmerged pair is available for a finer overlay"
    (let [{:keys [user project files]} (project/harness-edn-levels "ed-fence")]
      (is (= {:approval {:allow ["shared"]} :other 1} user))
      (is (= {:approval {:strict true}} project))
      (is (= (.getAbsolutePath user-file) (:user files)))
      (is (= (.getAbsolutePath project-file) (:project files)))))
  (testing "an unbound thread reports no project file, rather than a path to one"
    (let [{:keys [files]} (project/harness-edn-levels "ed-nobody")]
      (is (.endsWith ^String (:user files) "harness.edn"))
      (is (nil? (:project files))))))
