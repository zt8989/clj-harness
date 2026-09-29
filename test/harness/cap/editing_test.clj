(ns harness.cap.editing-test
  "harness.cap.editing's external behavior: which editing implementation a session is
  served by, how the block composes over the defaults, and what a broken one says. The
  regression guarantee is the first test -- with nobody saying anything, a session edits
  by anchor, which is what this harness does by default.

  ONE LEVEL NOW (.scratch/config-merge). :editing used to be composed from two harness.edn
  levels, user then project, key by key; it lives in this home's config.edn, :session
  :editing, and there is one file -- so what the cases below check is the block, the
  defaults it composes over, and what a broken one refuses. Where a case used to pin the
  two-level behavior it was rewritten to pin what replaced it, and the ones whose whole
  premise was 'the project level wins' are gone with the level."
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

;; THE :session SECTION LIVES IN THE SHARED HOME, so a leftover would leak into the
;; NEXT namespace as a silently different editing mode -- the exact confusion this
;; namespace exists to refuse. Wipe it around every test; the rest of config.edn (the
;; provider fixtures) is left alone, which is what `wipe-session!` is for.
(defn- wipe-session [f]
  (support/wipe-session!)
  (f)
  (support/wipe-session!))

(use-fixtures :each wipe-session)

(defn- write-session! [edn] (support/write-session! edn))

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
    (write-session! "{:editing {:mode :str-replace}}")
    (is (= :str-replace (:mode (editing/editing-mode "ed-default"))))
    (is (= :on (:boundary-dedup (editing/editing-mode "ed-default")))
        "and the other keys keep their defaults -- the block composes by key")))

(deftest a-session-with-nothing-written-is-just-as-empty
  ;; A binding whose directory has no .harness/ at all, and a home whose config.edn says
  ;; nothing: both answer the defaults. (The `.harness` directory itself is still made
  ;; here: it is where hooks.edn lives, and a session bound to a project is the everyday
  ;; case -- what must be true is that NOTHING in it changes this answer.)
  (.mkdirs (io/file root ".harness"))
  (is (= editing/defaults (editing/editing-mode "ed-emptydir"))))

;; ------------------------------------------------------------------- writing

(deftest the-block-composes-over-the-defaults-key-by-key
  ;; What is left of the old 'two levels compose key by key' case. The composition that
  ;; matters is block-over-defaults: a person who writes ONE key must not have to restate
  ;; the other seven, and a key nobody named keeps its default.
  (project/bind! "ed-overlay" root)
  (write-session! "{:editing {:mode :hashline :diff-context-lines 4}}")
  (testing "the keys that were written are the ones answered"
    (is (= {:mode :hashline :diff-context-lines 4}
           (select-keys (editing/editing-mode "ed-overlay")
                        [:mode :diff-context-lines]))))
  (testing "and the keys nobody named are still the defaults"
    (is (= :on (:boundary-dedup (editing/editing-mode "ed-overlay")))
        "nobody named :boundary-dedup, so it is the default")
    (is (true? (:grep (editing/editing-mode "ed-overlay")))))
  (testing "writing the block again REPLACES it -- there is one level, not a merge of two"
    ;; THE THING THE PROJECT LEVEL USED TO BUY, said out loud as its absence: a second
    ;; write is the whole block, so a key the first write set and the second does not
    ;; mention goes back to its default. Two levels would have kept it; one level cannot.
    (write-session! "{:editing {:grep false}}")
    (is (false? (:grep (editing/editing-mode "ed-overlay"))))
    (is (= :hashline (:mode (editing/editing-mode "ed-overlay")))
        "the mode is the DEFAULT again -- the second write replaced the block")))

(deftest every-session-reads-the-same-one-file
  ;; The old case was 'an unbound session sees only the user level'. With one level there
  ;; is no second file to be seen by anyone, and the property worth pinning is the one
  ;; that replaced it: the write is the WHOLE home's mode, bound or not.
  (write-session! "{:editing {:mode :str-replace}}")
  (project/bind! "ed-bound" root)
  (is (= :str-replace (:mode (editing/editing-mode "ed-bound"))))
  (is (= :str-replace (:mode (editing/editing-mode "ed-unbound")))
      "a session with no binding reads the same file -- there is nowhere else to look"))

(deftest the-block-is-read-fresh-on-every-ask
  ;; The config.edn discipline: editing the file moves the mode without a restart. A
  ;; cached answer would make the file a thing you have to know about rather than a thing
  ;; that works.
  (write-session! "{:editing {:mode :str-replace}}")
  (is (= :str-replace (:mode (editing/editing-mode "ed-fresh"))))
  (write-session! "{:editing {:mode :hashline}}")
  (is (= :hashline (:mode (editing/editing-mode "ed-fresh")))))

(deftest the-project-level-is-gone
  ;; DECISION 2 OF .scratch/config-merge, pinned where it is cheapest to see: a
  ;; `.harness/harness.edn` in the bound project is a file NOTHING reads. It is not an
  ;; error and it is not a second level -- it simply has no say.
  (project/bind! "ed-project-level" root)
  (write-session! "{:editing {:mode :hashline} :approval {:allow [\"shared\"]}}")
  (let [f (io/file root ".harness" "harness.edn")]
    (.mkdirs (.getParentFile f))
    (spit f "{:editing {:mode :str-replace} :approval {:strict true}}" :encoding "UTF-8")
    (try
      (testing "what the session reads is the file it actually reads"
        (is (= :hashline (:mode (editing/editing-mode "ed-project-level"))))
        (is (= {:editing {:mode :hashline} :approval {:allow ["shared"]}}
               (project/harness-config "ed-project-level"))
            "harness-config answers the one level, with the project file unread")
        (is (= (.getAbsolutePath (home/config-file)) (project/harness-config-path))
            "and it names the file a failure would have to send somebody to"))
      (finally
        (io/delete-file (io/file root ".harness") true)))))

;; ------------------------------------------------------- broken is a failure

(deftest a-broken-block-is-a-named-failure-not-a-quiet-default
  (project/bind! "ed-broken" root)
  (testing ":editing that is not a map names the file and the value"
    (write-session! "{:editing :hashline}")
    (let [e (ex-data-of #(editing/editing-mode "ed-broken"))]
      (is (= :editing-not-a-map (:reason e)))
      (is (= "config" (name (:level e))) "one level, and it says which one")
      (is (= (.getAbsolutePath (home/config-file)) (:path e)))
      (is (str/includes? (msg-of #(editing/editing-mode "ed-broken")) ":hashline"))))
  (testing "a file that is not EDN at all fails too, and is not this namespace's
            error to spell -- harness.cap.providers owns that message"
    (let [f (home/config-file)
          old (when (.exists f) (slurp f :encoding "UTF-8"))]
      (try
        (spit f "{:editing " :encoding "UTF-8")
        (is (= ::threw (try (editing/editing-mode "ed-broken")
                            ::answered
                            (catch Exception _ ::threw))))
        (finally
          (if old
            (spit f old :encoding "UTF-8")
            (io/delete-file f true))))))
  (testing "and a silent fallback to the defaults is exactly what must NOT happen"
    (write-session! "{:editing :hashline}")
    (is (= ::threw (try (editing/editing-mode "ed-broken")
                        ::answered
                        (catch Exception _ ::threw))))))

;; ------------------------------------------------------------- key checking

(deftest an-unknown-key-names-the-file-it-came-from
  (project/bind! "ed-unknown" root)
  (write-session! "{:editing {:mode :hashline :modes :hashline}}")
  (let [e (ex-data-of #(editing/editing-mode "ed-unknown"))]
    (is (= :unknown-editing-key (:reason e)))
    (is (= :modes (:key e)))
    (is (= "config" (name (:level e))))
    (is (= (.getAbsolutePath (home/config-file)) (:path e))))
  (testing "the message lists what IS legal, so the reader can fix it in place"
    (let [m (msg-of #(editing/editing-mode "ed-unknown"))]
      (is (str/includes? m ":mode"))
      (is (str/includes? m ":diff-context-lines")))))

(deftest a-key-that-was-removed-is-an-unknown-key
  ;; `:auto-read` used to be legal: a successful write handed the head of the file
  ;; back with fresh anchors. It is gone -- writing content is not knowing its line
  ;; numbers -- and a config.edn that still names it gets the same NAMED failure
  ;; any typo gets: the key, the file, and what IS legal, so the fix is deleting one
  ;; line rather than wondering why nothing happened.
  (project/bind! "ed-auto-read" root)
  (write-session! "{:editing {:auto-read true}}")
  (let [e (ex-data-of #(editing/editing-mode "ed-auto-read"))]
    (is (= :unknown-editing-key (:reason e)))
    (is (= :auto-read (:key e)))
    (is (= "config" (name (:level e))))
    (is (= (.getAbsolutePath (home/config-file)) (:path e))))
  (testing "and the legal list does not offer the removed key back"
    (is (not (contains? editing/defaults :auto-read))
        "the key is not a default any more")
    (is (str/includes? (msg-of #(editing/editing-mode "ed-auto-read")) ":grep")
        "while the keys that ARE legal are listed")))

(deftest an-unknown-key-fails-even-beside-a-legal-one
  ;; A typo is not a value that loses a merge: it is a request nobody was ever going to
  ;; honour, so looking at what survived would hide it. (This used to be the case that
  ;; wrote the typo at one level and a legal :mode at the other -- the shadowing half is
  ;; gone, the property is not.)
  (project/bind! "ed-shadow-unknown" root)
  (write-session! "{:editing {:modes :hashline :mode :hashline}}")
  (let [e (ex-data-of #(editing/editing-mode "ed-shadow-unknown"))]
    (is (= :unknown-editing-key (:reason e)))
    (is (= :modes (:key e))
        "the unknown key is reported, beside a legal :mode that says nothing about it")))

;; ----------------------------------------------------------- value checking

(deftest an-illegal-value-names-the-file-the-key-and-what-is-legal
  (project/bind! "ed-values" root)
  (let [check (fn [edn kw fragment]
                (write-session! edn)
                (let [e (ex-data-of #(editing/editing-mode "ed-values"))
                      m (msg-of #(editing/editing-mode "ed-values"))]
                  (is (= :bad-editing-value (:reason e)) (str "for " edn))
                  (is (= kw (:key e)) (str "for " edn))
                  (is (= "config" (name (:level e))))
                  (is (= (.getAbsolutePath (home/config-file)) (:path e)))
                  (is (str/includes? m fragment) (str "message for " edn))
                  (is (str/includes? m (str kw)) "the message names the key")))]
    (testing "an unimplemented mode is refused, and a legal one is offered"
      (check "{:editing {:mode :fuzzy}}" :mode ":hashline or :str-replace"))
    (testing "a legal mode spelled as a string is refused -- the value is a keyword"
      (check "{:editing {:mode \"hashline\"}}" :mode ":hashline or :str-replace"))
    (testing "the booleans take booleans"
      (check "{:editing {:grep 1}}" :grep "true or false")
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
    (write-session! (str "{:editing {:mode " mode " :boundary-dedup " dedup
                         " :diff-context-lines " n
                         " :grep false :require-path true :strict-input false}}"))
    (let [m (editing/editing-mode "ed-legal")]
      (is (= mode (:mode m)))
      (is (= dedup (:boundary-dedup m)))
      (is (= n (:diff-context-lines m)))
      (is (false? (:grep m))))))

(deftest the-edges-of-every-legal-range-are-accepted
  ;; The other end of the message contract: the range a failure names has to be
  ;; the range that actually passes, edges included. A message saying "0-10"
  ;; beside a predicate that excludes 0 would send the reader into a second
  ;; failure while doing exactly what they were told.
  (project/bind! "ed-edges" root)
  (doseq [n [0 10]]
    (write-session! (str "{:editing {:diff-context-lines " n "}}"))
    (is (= n (:diff-context-lines (editing/editing-mode "ed-edges")))
        (str "diff-context-lines " n)))
  (doseq [d [:on :strict :off]]
    (write-session! (str "{:editing {:boundary-dedup " d "}}"))
    (is (= d (:boundary-dedup (editing/editing-mode "ed-edges")))
        (str "boundary-dedup " d))))

(deftest a-broken-value-is-refused-even-though-the-defaults-would-cover-it
  ;; Values are judged as WRITTEN, not as they survive the fold over the defaults. A
  ;; broken :mode whose default is perfectly legal must be a refusal: reading it as
  ;; 'the default wins' would be the silent fallback this namespace exists to prevent.
  ;; (The old case was 'a project overriding a broken user value has to be able to FIX
  ;; it' -- with one level there is nothing to override, so the property left is the
  ;; refusal itself.)
  (project/bind! "ed-fix" root)
  (write-session! "{:editing {:mode :nonsense}}")
  (let [e (ex-data-of #(editing/editing-mode "ed-fix"))]
    (is (= :bad-editing-value (:reason e)))
    (is (= :mode (:key e)))
    (is (= :nonsense (:value e))))
  (testing "and the default it would have fallen back to is never handed back instead"
    (is (not= :hashline
              (try (:mode (editing/editing-mode "ed-fix")) (catch Exception _ ::refused))))))
