(ns harness.cap.hashline.grep-test
  "`anchor_grep`: a search whose hits can be edited, and the refusals that keep it
  from being a way to hang a session.

  The value here is the DISTANCE between finding and changing: a plain grep gives a
  path and a line number, and a line number cannot be edited, so the model pays a
  read of every matching file before it can act. The cases below check that gap is
  gone -- search, then edit with what the search handed back."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [harness.infra.db :as db]
            [harness.cap.hashline.anchors :as anchors]
            [harness.cap.hashline.store :as store]
            [harness.infra.home :as home]
            [harness.cap.project :as project]
            [harness.kernel.tools :as tools]
            [harness.test-support :as support]))

(use-fixtures :once support/with-builtins)

(def ^:private root
  (support/temp-dir "hashline-grep"))

(.mkdirs (io/file root "src"))
(.mkdirs (io/file root ".git"))

(def ^:private user-file (io/file (home/root) "harness.edn"))
(def ^:private project-file (io/file root ".harness" "harness.edn"))

(defn- path [name] (store/canonical (str (io/file root name))))

(defn- put! [name content]
  (let [f (io/file root name)]
    (.mkdirs (.getParentFile f))
    (spit f content :encoding "UTF-8")))

(defn- rm-rf
  "Delete a directory tree.

  `clojure.java.io/delete-file` does NOT recurse -- it calls File.delete, which
  fails on a non-empty directory and, with `silently` true, says nothing. That is
  how the first version of this fixture left a previous case's two hundred files
  exactly where the next case would search them."
  [f]
  (let [f (io/file f)]
    (when (.isDirectory f)
      (doseq [c (.listFiles f)] (rm-rf c)))
    (io/delete-file f true)))

(defn- wipe [f]
  (io/delete-file user-file true)
  (io/delete-file project-file true)
  ;; The SEARCH ROOT is cleared too, not just the store: this suite's subject is what
  ;; a search FINDS, and a file written by an earlier case is a file this one would
  ;; find.
  (rm-rf root)
  (.mkdirs (io/file root "src"))
  ;; ...and the `.git` DIRECTORY IS PUT BACK, because `rg` only honours .gitignore
  ;; inside a repository (ripgrep 14's `require-git` default). Without it the
  ;; gitignore case would quietly search everything and 'pass' for the wrong reason
  ;; -- which is how the first version of this fixture read, one deletion after the
  ;; ns-level mkdirs that had created it.
  (.mkdirs (io/file root ".git"))
  (f)
  (io/delete-file user-file true)
  (io/delete-file project-file true))


(defn- clean-tables [f]
  (let [wipe-tables (fn []
                      (when (.exists (home/db-file))
                        (db/with-transaction
                          (fn [c]
                            (doseq [t ["hashline_snapshots" "hashline_ownership"
                                       "hashline_sessions" "hashline_undo"]]
                              (db/execute! c (str "DELETE FROM " t)))))))]
    (wipe-tables) (f) (wipe-tables)))

(use-fixtures :each wipe clean-tables
  (fn [f] (tools/forget-turn!) (f) (tools/forget-turn!)))

(def ^:private tid "gt")

(defn- use-mode!
  ([] (use-mode! {}))
  ([extra]
   (.mkdirs (.getParentFile project-file))
   (spit project-file (str "{:editing " (pr-str (merge {:mode :hashline} extra)) "}")
         :encoding "UTF-8")
   (project/bind! tid root)))

(defn- call [name args]
  (tools/run! {:id "c" :type "function"
               :function {:name name :arguments (json/write-str args)}} tid))

(defn- grep! [args] (call "anchor_grep" args))

(defn- rows
  "A grep answer's anchored rows as [file line-number anchor content], the headers
  and any budget note dropped.

  What a row LOOKS like is `%6d │ anchor│content`, so the split is on the `│` after
  the number and then on the one inside the row."
  [out]
  (let [lines (str/split-lines out)]
    (loop [ls lines, file nil, acc []]
      (if-not (seq ls)
        acc
        (let [l (first ls)]
          (cond
            (str/starts-with? l "===") (recur (rest ls) (str/trim (subs l 3 (- (count l) 3))) acc)
            (str/starts-with? l "[") (recur (rest ls) file acc)
            :else
            (if-let [i (str/index-of l " │ ")]
              (let [n   (str/trim (subs l 0 i))
                    row (subs l (+ i 3))
                    j   (str/index-of row "│")]
                (recur (rest ls) file
                       (conj acc [file (parse-long n) (subs row 0 j) (subs row (inc j))])))
              (recur (rest ls) file acc))))))))

(defn- anchor-for [out content]
  (some (fn [[_ _ a c]] (when (= c content) a)) (rows out)))

;; ------------------------------------------------- does the mark hold the lock?
;;
;; `served` has to stay a subset of `:anchors`: an edit PRUNES it in `advance-on!`
;; (intersecting with the surviving anchors) while a marking UNIONS, so the read a
;; marking is derived from and the marking itself may not be separated by an edit.
;; The helper below asks the question DETERMINISTICALLY -- no gate, no race -- by
;; swapping `store/mark-served!` for a stub that records whether the CALLING thread
;; held the session lock at the instant of the call.

(defn- lock-witness
  "Call F with `store/mark-served!` wrapped: every call first records, under the
  THREAD-ID it names, whether the calling thread held that session's lock, then
  delegates to the original. Answers the witness {thread-id [held? ..]}.

  DETERMINISTIC, NOT A RACE: the stub asks its own thread the question at the moment
  of the call, so the answer is the same on every run. `alter-var-root`, not
  `with-redefs`, because the tool seam is free to run the body on another thread.

  The lock object comes out of the store's own table, keyed exactly as
  `with-session-lock` keys it; it exists by the time the marking runs because the
  `serve/sync!` that rendered each block made one. A session with no lock at all is
  recorded as NOT held."
  [f]
  (let [original @#'store/mark-served!
        seen     (atom {})
        wrapped  (fn [thread-id path anchors]
                   (let [^java.util.concurrent.ConcurrentHashMap locks
                         @#'store/session-locks
                         l (.get locks (str thread-id))]
                     (swap! seen update (str thread-id) (fnil conj [])
                            (boolean (and l (.isHeldByCurrentThread
                                             ^java.util.concurrent.locks.ReentrantLock l)))))
                   (original thread-id path anchors))]
    (alter-var-root #'store/mark-served! (constantly wrapped))
    (try (f) (finally (alter-var-root #'store/mark-served! (constantly original))))
    @seen))

;; ------------------------------------------------------------- the basic use

(deftest a-hit-comes-back-as-an-anchored-row
  (use-mode!)
  (put! "src/a.clj" "(defn thing []\n  (inc 1))\n")
  (put! "src/b.clj" "(defn other []\n  (dec 1))\n")
  (let [{:keys [content error]} (grep! {:pattern "defn"})]
    (is (false? error) content)
    (testing "one block per file, with a header naming it"
      (is (str/includes? content (str "=== " (path "src/a.clj") " ===")) content)
      (is (str/includes? content (str "=== " (path "src/b.clj") " ==="))))
    (testing "and each hit as <line number> │ <anchor>│<content>"
      (let [rs (rows content)]
        (is (= 2 (count rs)))
        (is (every? (fn [[_ n a _]] (and (= 1 n) (anchors/anchor? a))) rs))
        (is (= #{"(defn thing []" "(defn other []"} (into #{} (map (fn [[_ _ _ c]] c)) rs)))))))

(deftest a-hit-can-be-edited-with-what-the-search-handed-back
  ;; THE POINT. Search, then change what was found, with no read in between.
  (use-mode!)
  (put! "src/a.clj" "(defn thing []\n  (inc 1))\n")
  (let [{:keys [content]} (grep! {:pattern "inc"})
        a     (anchor-for content "  (inc 1))")
        edit  (call "replace" {:remove_from a :replacement_lines ["  (inc 2))"]})]
    (is (some? a) content)
    (is (false? (:error edit)) (:content edit))
    (is (= "(defn thing []\n  (inc 2))\n" (slurp (io/file root "src/a.clj") :encoding "UTF-8")))))

(deftest context-rows-carry-anchors-too
  (use-mode!)
  (put! "src/a.clj" "line1\nline2\nMARKER\nline4\nline5\n")
  (let [out (:content (grep! {:pattern "MARKER" :context 1}))
        rs  (rows out)]
    (is (= [2 3 4] (mapv (fn [[_ n _ _]] n) rs)) "the match and one line either side")
    (is (every? (fn [[_ _ a _]] (anchors/anchor? a)) rs))
    (testing "and a context row is editable"
      (let [a (anchor-for out "line4")
            edit (call "replace" {:remove_from a :replacement_lines ["LINE4"]})]
        (is (false? (:error edit)) (:content edit))
        (is (= "line1\nline2\nMARKER\nLINE4\nline5\n"
               (slurp (io/file root "src/a.clj") :encoding "UTF-8")))))))

(deftest only-the-lines-that-were-printed-count-as-shown
  ;; `served` is the rule everywhere in this feature: an anchor is usable because it
  ;; was DISPLAYED, and nothing else. A search shows a handful of lines of a file the
  ;; session may never have read -- so those lines are shown, and the rest of that
  ;; file is not.
  (use-mode!)
  (put! "src/a.clj" "one\ntwo\nMARKER\nfour\nfive\n")
  (let [out (grep! {:pattern "MARKER"})
        _   (is (false? (:error out)))
        st  (store/state tid (path "src/a.clj"))]
    (is (some? st) "the search taught the session about this file")
    (is (contains? (:served st) (anchor-for (:content out) "MARKER")))
    (testing "a line of the file the search did not print is not shown"
      (let [line-of (fn [content]
                      (some (fn [[i a]] (when (= content
                                                 (nth (str/split-lines
                                                       (slurp (io/file root "src/a.clj")
                                                              :encoding "UTF-8")) i))
                                          a))
                            (map-indexed vector (:anchors st))))
            unshown (line-of "five")]
        (is (some? unshown))
        (is (not (contains? (:served st) unshown)))
        (is (str/includes?
             (:content (call "replace" {:remove_from unshown
                                        :replacement_lines ["FIVE"]}))
             "never shown"))))))

;; ------------------------------------------------------------ the arguments

(deftest the-pattern-is-required
  (use-mode!)
  (let [out (grep! {})]
    (is (true? (:error out)))
    (is (str/includes? (:content out) "pattern"))))

(deftest bad-options-are-named
  (use-mode!)
  (put! "src/a.clj" "one\n")
  (doseq [[args fragment] {{:pattern "one" :context -1}  "0 or more"
                           {:pattern "one" :context 1.5} "0 or more"
                           {:pattern "one" :limit 0}    "positive integer"
                           {:pattern "one" :limit -3}   "positive integer"}
          :let [{:keys [content error]} (grep! args)]]
    (is (true? error) (pr-str args))
    (is (str/includes? content fragment) (str args " -> " content))))

(deftest a-regex-that-could-hang-is-refused-with-a-way-out
  ;; Not a general regex-engine problem to solve: the model wrote `(a+)+b` by
  ;; accident, and the session would sit inside ripgrep until the timeout. So the
  ;; shape is refused up front, and `literal: true` is named -- which is right for
  ;; the common case anyway, since a search for code is usually a search for text.
  (use-mode!)
  (put! "src/a.clj" "aaaaaaaaaaaaaaab\n")
  (doseq [p ["(a+)+b" "(a|aa)+c" "(x+)*y" "a{1000}" "(x)\\1"]]
    (let [{:keys [content error]} (grep! {:pattern p})]
      (is (true? error) p)
      (is (str/includes? content "literal: true") (str p " -> " content))
      (testing (str p " runs as literal text when asked to")
        (let [out (grep! {:pattern "aaaa" :literal true})]
          (is (false? (:error out)) (:content out)))))))

(deftest a-pattern-that-does-not-hang-is-run
  ;; The three shapes below are near misses of the refused ones -- a quantifier on a
  ;; single character, a bounded repeat, an alternation without a quantifier -- and
  ;; each also actually MATCHES the file, so a pass means the search ran rather than
  ;; that a refusal was mistaken for a hit.
  (use-mode!)
  (put! "src/a.clj" "alpha\nbeta\n")
  (doseq [p ["al.ha" "a{1}lpha" "(alpha|beta)"]]
    (let [{:keys [content error]} (grep! {:pattern p})]
      (is (false? error) (str p " -> " content))
      (is (str/includes? content "│") (str p " matched something")))))

;; ------------------------------------------------------------- the search

(deftest the-search-respects-gitignore-and-skips-binaries
  ;; Both are `rg`'s own behaviour, and both are the reason this uses `rg` rather
  ;; than walking the tree: a search that reads what the rest of the session ignores
  ;; is a search that answers a different question.
  (use-mode!)
  (put! ".gitignore" "ignored/\n")
  (put! "ignored/hidden.clj" "MARKER in an ignored file\n")
  (put! "src/kept.clj" "MARKER in a kept file\n")
  (with-open [o (io/output-stream (io/file root "bin.dat"))]
    (.write o (.getBytes "MARKER\u0000binary" "UTF-8")))
  (let [out (:content (grep! {:pattern "MARKER"}))]
    (is (str/includes? out "kept.clj") out)
    (is (not (str/includes? out "ignored")) "an ignored file was not searched")
    (is (not (str/includes? out "bin.dat")) "a binary file was skipped")
    (is (not (str/includes? out "Error")) "and skipping is not an error")))

(deftest a-glob-narrows-the-search
  (use-mode!)
  (put! "src/a.clj" "MARKER\n")
  (put! "src/a.txt" "MARKER\n")
  (let [out (:content (grep! {:pattern "MARKER" :glob "*.txt"}))]
    (is (str/includes? out "a.txt"))
    (is (not (str/includes? out "a.clj")))))

(deftest the-path-argument-narrows-the-search-and-re-roots
  (use-mode!)
  (put! "src/a.clj" "MARKER\n")
  (put! "other/b.clj" "MARKER\n")
  (let [out (:content (grep! {:pattern "MARKER" :path "src"}))]
    (is (str/includes? out "a.clj"))
    (is (not (str/includes? out "b.clj")))))

(deftest case-insensitivity-is-optional
  (use-mode!)
  (put! "src/a.clj" "Marker\n")
  (is (true? (:error (grep! {:pattern "marker"}))))
  (is (false? (:error (grep! {:pattern "marker" :ignore-case true})))))

(deftest search-does-not-follow-git
  ;; The one directory whose contents are never what a search means.
  (use-mode!)
  (put! ".git/objects/thing" "MARKER inside git\n")
  (put! "src/a.clj" "MARKER outside git\n")
  (let [out (:content (grep! {:pattern "MARKER"}))]
    (is (str/includes? out "a.clj"))
    (is (not (str/includes? out "objects")))))

(deftest no-matches-is-an-error-that-says-so
  ;; Distinct from a search that FAILED: 'nothing matched' is an answer the model can
  ;; act on (change the pattern), and it says which pattern found nothing.
  (use-mode!)
  (put! "src/a.clj" "alpha\n")
  (let [{:keys [content error]} (grep! {:pattern "nothing-matches-this"})]
    (is (true? error))
    (is (str/includes? content "no matches"))
    (is (str/includes? content "nothing-matches-this"))))

;; --------------------------------------------------------------- the limits

(deftest a-limit-caps-the-hits-per-file-and-says-there-are-more
  (use-mode!)
  (put! "src/a.clj" (str (str/join "\n" (repeat 30 "MARKER")) "\n"))
  (let [out (:content (grep! {:pattern "MARKER" :limit 5}))]
    (is (= 5 (count (rows out))) "five hits, as asked")
    (is (str/includes? out "narrow the search") "and the rest are acknowledged")))

(deftest an-enormous-line-keeps-its-anchor
  ;; Same treatment as `read`: the anchor comes out, the content is replaced by a
  ;; note, and the line is still editable by that anchor -- which is what makes a
  ;; long line a thing you can fix rather than a thing you have to work around.
  (use-mode!)
  (put! "src/big.clj" (str "MARKER " (apply str (repeat 60000 "x")) "\n"))
  (let [out (:content (grep! {:pattern "MARKER"}))
        [_ n a content] (first (rows out))]
    (is (= 1 n))
    (is (anchors/anchor? a))
    (is (str/includes? content "not shown") "the content is a note, not 60k of x")
    (is (false? (:error (call "replace" {:remove_from a
                                         :replacement_lines ["now short"]}))))
    (is (= "now short\n" (slurp (io/file root "src/big.clj") :encoding "UTF-8")))))

(deftest a-wide-result-set-is-cut-off-with-an-explanation
  (use-mode!)
  ;; Enough files that the answer cannot hold them all: each one's block is a few
  ;; hundred bytes and the budget is 50 KB.
  (doseq [i (range 200)]
    (put! (format "src/f%03d.clj" i)
          (str (str/join "\n" (repeat 5 (str "MARKER " (apply str (repeat 60 "y")) i)))
               "\n")))
  (let [out (:content (grep! {:pattern "MARKER" :limit 5}))]
    (is (str/includes? out "output budget reached") "the cut is stated, not silent")
    (is (str/includes? out "Narrow the search") "and the way to see the rest is given")
    (is (< (count out) 90000) "the answer really is bounded")))

;; --------------------------------------------------------- the tool's face

(deftest the-tool-is-switched-off-by-its-own-key
  ;; `:anchor-grep false` is the session saying 'I want anchor editing without the
  ;; search tool', and nothing takes its place -- a session with no grep tool uses
  ;; `bash`, which is a tool it already has.
  (use-mode! {:anchor-grep false})
  (let [names (map (fn [s] (get-in s [:function :name])) (tools/specs tid))]
    (is (not (contains? (set names) "anchor_grep")))
    (is (contains? (set names) "replace") "the rest of the anchor toolset is untouched"))
  (testing "and calling it anyway says which key turned it off"
    (let [{:keys [content error]} (grep! {:pattern "x"})]
      (is (true? error))
      (is (str/includes? content "anchor-grep"))
      (is (not (str/includes? content "unknown tool"))))))

(deftest the-description-says-how-to-read-a-row
  (use-mode!)
  (let [spec (first (filter #(= "anchor_grep" (get-in % [:function :name]))
                            (tools/specs tid)))
        desc (:description (:function spec))]
    (is (some? spec))
    (is (str/includes? desc "ANCHORED"))
    (is (str/includes? desc "never to edit by") "the line number's role is stated")
    (is (str/includes? desc "literal: true") "and the way out of a refused pattern")))

;; ------------------------------------------ the marking shares the read's lock

(deftest the-search-marks-what-it-printed-under-the-session-lock
  ;; THE BUG THIS PINS: `render` reads each file's anchors through `serve/sync!`
  ;; under the session lock, and the `mark-served!` that records what each block
  ;; printed used to happen after it was released. A concurrent edit in that gap
  ;; prunes the freed anchors (`advance-on!`) and the marking unions them back in --
  ;; so `served` names an anchor no longer in `:anchors`. The stub turns 'was the
  ;; lock held when the marking ran?' into a value, with no race to hope for.
  (use-mode!)
  (put! "src/a.clj" "one\nMARKER\n")
  (let [seen (lock-witness #(grep! {:pattern "MARKER"}))
        held (get seen tid)]
    (is (seq held) "the search did mark what it printed")
    (is (every? true? held)
        (str "every mark-served! call must hold " tid "'s session lock; got "
             (pr-str held)))))
