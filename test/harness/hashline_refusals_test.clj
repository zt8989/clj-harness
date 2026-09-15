(ns harness.hashline-refusals-test
  "The refused edits, and what comes back with them.

  A refusal is a delivery. `old_string` answers 'not found' and the model re-reads
  the file; here the refusal carries the lines it was talking about WITH their
  current anchors, and those rows count as shown -- so the next call succeeds
  without a read. That property is what this namespace exists to pin, and each case
  below ends by making the retry that the refusal invited."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [harness.db :as db]
            [harness.hashline.anchors :as anchors]
            [harness.hashline.store :as store]
            [harness.home :as home]
            [harness.project :as project]
            [harness.tools :as tools]))

(def ^:private root
  (str (io/file (System/getProperty "java.io.tmpdir") "harness-hashline-refusals-test")))

(io/delete-file root true)
(.mkdirs (io/file root))

(def ^:private user-file (io/file (home/root) "harness.edn"))
(def ^:private project-file (io/file root ".harness" "harness.edn"))
(def ^:private file (io/file root "f.txt"))

(defn- path
  "The path the store books f.txt under -- canonical, which on macOS is not the
  string `(str file)` produces (the temp directory is reached through a symlink)."
  []
  (store/canonical (str file)))

(defn- wipe [f]
  (io/delete-file user-file true)
  (io/delete-file project-file true)
  (f)
  (io/delete-file user-file true)
  (io/delete-file project-file true))

(use-fixtures :each wipe)

(defn- clean-tables [f]
  (let [wipe-tables (fn []
                      (when (.exists (home/db-file))
                        (db/with-transaction
                          (fn [c]
                            (doseq [t ["hashline_snapshots" "hashline_ownership"
                                       "hashline_sessions" "hashline_undo"]]
                              (db/execute! c (str "DELETE FROM " t)))))))]
    (wipe-tables) (f) (wipe-tables)))

(use-fixtures :each clean-tables)

(def ^:private tid "rf")

(defn- use-mode! []
  (.mkdirs (.getParentFile project-file))
  (spit project-file "{:editing {:mode :hashline}}" :encoding "UTF-8")
  (project/bind! tid root))

(defn- call [name args]
  (tools/run! {:function {:name name :arguments (json/write-str args)}} tid))

(defn- write! [content] (spit file content :encoding "UTF-8"))

(defn- text
  "A file body: the lines joined, WITH the trailing newline a real file has."
  [lines]
  (str (str/join "\n" lines) "\n"))

(defn- read!
  "The anchors of f.txt in line order, as a read hands them out."
  []
  (let [out (:content (call "read" {:path "f.txt"}))]
    (mapv (fn [row] (subs row 0 (str/index-of row "│"))) (str/split-lines out))))

(defn- replace! [args] (call "replace" args))

(defn- content-of [out] (:content out))

(defn- row-for
  "The row in OUT whose content is LINE, as [anchor line] -- the anchor being the
  four characters to the left of the `│`.

  Deliberately indifferent to what comes before those four: a read row is
  `anchor│text` and a diff row is `+anchor│text`, and this helper is used on both.
  Pinning it to one shape is how a test ends up asserting the row's prefix in a case
  that has nothing to do with prefixes."
  [out line]
  (some (fn [r]
          (let [i (str/index-of r "│")]
            (when (and i (>= i 4) (= line (subs r (inc i))))
              [(subs r (- i 4) i) line])))
        (str/split-lines out)))

(defn- forget-ownership!
  "Delete this session's ownership rows, leaving the views alone: the half-lost
  anchor table the content fallback exists for."
  []
  (db/with-transaction
    (fn [c] (db/execute! c "DELETE FROM hashline_ownership WHERE thread_id = ?" tid))))

(defn- lines [n] (mapv #(str "line" %) (range 1 (inc n))))

;; --------------------------------------------------------------- drifted files

(deftest a-drifted-file-comes-back-with-the-anchors-to-retry-with
  ;; THE CASE THIS TICKET IS ABOUT. The file moved; the edit is refused; the lines
  ;; the model addressed come back named as they are NOW; the retry needs no read.
  (use-mode!)
  (write! (text (lines 6)))
  (let [[_ _ c _ _ _] (read!)]
    ;; somebody else's editor save: line 3 is now different
    (write! (text ["line1" "line2" "SOMEBODY-ELSE" "line4" "line5" "line6"]))
    (let [out (replace! {:remove_from c :replacement_lines ["LINE3"]})]
      (is (true? (:error out)) "the edit was refused")
      (testing "and it says what the file is now, in the model's terms"
        (is (str/includes? (content-of out) "changed after it was read"))
        (is (str/includes? (content-of out) "line 3") "the line that moved"))
      (testing "with the range's lines and their CURRENT anchors"
        (let [[anchor line] (row-for (content-of out) "SOMEBODY-ELSE")]
          (is (some? anchor) (str "no row for the changed line in:\n" (content-of out)))
          (is (anchors/anchor? anchor))
          (is (not= c anchor) "and it is NOT the anchor that just stopped working")
          (testing "so the retry is a retry, not a second read"
            (let [again (replace! {:remove_from anchor :replacement_lines ["LINE3"]})]
              (is (false? (:error again)) (content-of again))
              (is (= (text ["line1" "line2" "LINE3" "line4" "line5" "line6"])
                     (slurp file :encoding "UTF-8"))))))))))

(deftest an-edit-that-only-moved-a-line-elsewhere-heals-too
  ;; The other drift: nothing the model was looking at changed, but the file has
  ;; more or fewer lines than it did, so a line's NEIGHBOURS moved even though its
  ;; own content did not. The range's anchors still name the same lines, so the
  ;; healing answer says what the file is now and the retry goes through.
  (use-mode!)
  (write! (text ["alpha" "beta" "gamma"]))
  (let [[_ b _] (read!)]
    (write! (text ["INSERTED-ELSEWHERE" "alpha" "beta" "gamma"]))
    (let [out (replace! {:remove_from b :replacement_lines ["BETA"]})]
      (is (true? (:error out)) (content-of out))
      (is (str/includes? (content-of out) "it now has 4 lines, it had 3"))
      (let [[anchor _] (row-for (content-of out) "beta")]
        (is (some? anchor) (content-of out))
        (is (false? (:error (replace! {:remove_from anchor
                                       :replacement_lines ["BETA"]}))))))))

(deftest the-healed-rows-are-registered-as-shown
  ;; A printed row is an addressable line, and the store is the only place that
  ;; knows which rows went out. If the refusal printed anchors without recording
  ;; them, the retry would be refused for addressing a line nobody was shown --
  ;; the refusal would heal nothing.
  (use-mode!)
  (write! (text (lines 6)))
  (let [[_ _ c _ _ _] (read!)]
    (write! (text ["line1" "line2" "SOMEBODY-ELSE" "line4" "line5" "line6"]))
    (let [out (replace! {:remove_from c :replacement_lines ["X"]})
          shown (:served (store/state tid (path)))
          [anchor _] (row-for (content-of out) "SOMEBODY-ELSE")]
      (is (some? anchor) (content-of out))
      (is (contains? shown anchor)
          "the anchor the refusal printed is recorded as shown"))))

(deftest a-drift-names-the-lines-and-says-how-many
  ;; Each case writes a different file, so each one reads first: a healed answer
  ;; re-aligns the session's view, and the anchors from before it are gone by
  ;; design -- that is what the healing IS.
  (use-mode!)
  (testing "one line out of step is one line, named"
    (write! (text ["line1" "line2" "line3" "line4"]))
    (let [[_ _ c _] (read!)]
      (write! (text ["line1" "line2" "CHANGED" "line4"]))
      (let [out (content-of (replace! {:remove_from c :replacement_lines ["X"]}))]
        (is (str/includes? out "line 3 is not what it was")))))
  (testing "several are a span, with a count"
    (write! (text (lines 6)))
    (let [[_ _ c _ _ _] (read!)]
      (write! (text ["line1" "line2" "A" "B" "C" "line6"]))
      (let [out (content-of (replace! {:remove_from c :replacement_lines ["X"]}))]
        (is (str/includes? out "lines 3-5 are not what they were"))
        (is (str/includes? out "3 lines differ")))))
  (testing "and a file that changed SIZE says so, because that is another fact"
    (write! (text (lines 8)))
    (let [[_ _ c _ _ _ _ _] (read!)]
      (write! (text (lines 6)))
      (let [out (content-of (replace! {:remove_from c :replacement_lines ["X"]}))]
        (is (str/includes? out "it now has 6 lines, it had 8"))))))

(deftest a-long-range-cannot-flood-the-answer
  ;; The refusal has to be actionable, not a wall. A range longer than a screenful
  ;; is NAMED rather than printed, and the model is handed the read that shows it --
  ;; a range that long could not be retried in one edit anyway.
  (use-mode!)
  (write! (text (lines 80)))
  (let [as (read!)
        a  (first as)
        z  (last as)]
    (write! (text (conj (mapv #(str % "!") (lines 80)) "extra")))
    (let [res (replace! {:remove_from a :remove_to z :replacement_lines ["X"]})
          out (content-of res)]
      (is (true? (:error res)) (str "the edit did not go through: " out))
      (is (< (count (str/split-lines out)) 60) "the answer is not 80 rows long")
      (is (str/includes? out "the range runs to line 80"))
      (is (str/includes? out "offset=") "and says how to see the rest"))))

(deftest a-refusal-names-no-internals
  ;; A model can act on a path, a line number and an anchor. A checksum, a memory
  ;; address or a database column is noise it has to work out how to ignore.
  (use-mode!)
  (write! (str/join "\n" (lines 4)))
  (let [[_ _ c] (read!)]
    (write! (str/join "\n" ["line1" "CHANGED" "line3" "line4"]))
    (let [out (content-of (replace! {:remove_from c :replacement_lines ["X"]}))]
      (is (not (re-find #"[0-9a-f]{16}" out)) "no truncated digest")
      (is (not (str/includes? out "0x")) "no pointer")
      (is (not (re-find #"@[0-9a-f]{6,}" out)) "no java object identity")
      (is (not (str/includes? out "hashline_")) "no table names")
      (is (not (str/includes? out "Exception")) "no exception class names"))))

;; --------------------------------------------------------- files never read

(deftest editing-a-file-that-was-never-read-names-the-file
  (use-mode!)
  (write! "one\ntwo\n")
  (let [out (replace! {:path "f.txt" :remove_from "Hasu"
                       :replacement_lines ["X"]})]
    (is (true? (:error out)))
    (is (str/includes? (content-of out) (path)) "the file, by path")
    (is (str/includes? (content-of out) "Call read") "and the way in")))

(deftest a-name-that-is-not-an-anchor-says-so-rather-than-say-read
  ;; Two refusals that look alike and are not: no reading turns `Hasu` into a name,
  ;; so 'call read' here would be an instruction to repeat the mistake.
  (use-mode!)
  (write! "one\ntwo\n")
  (read!)
  (let [out (content-of (replace! {:remove_from "Hasu" :replacement_lines ["X"]}))]
    (is (str/includes? out "not an anchor"))
    (is (str/includes? out "Copy the anchor"))))

;; -------------------------------------------------------- the content fallback

(deftest an-anchor-the-ownership-table-lost-is-rescued-by-content
  ;; The half-lost anchor table: the view still names the line, ownership does not,
  ;; and the caller said which file. The line's content is unique, so there is
  ;; exactly one line the anchor can mean -- and the edit goes ahead, saying so.
  (use-mode!)
  (write! (text ["alpha" "beta" "gamma"]))
  (let [[_ b _] (read!)]
    (forget-ownership!)
    (let [out (replace! {:path "f.txt" :remove_from b :replacement_lines ["BETA"]})]
      (is (false? (:error out)) (content-of out))
      (is (str/includes? (content-of out) "ownership table")
          "and says it was a fallback, not the ordinary path")
      (is (= (text ["alpha" "BETA" "gamma"]) (slurp file :encoding "UTF-8")))
      (testing "and the anchors are owned again, so the next edit is an ordinary one"
        (let [owned (store/ownership tid)]
          (is (every? #(contains? owned %) (:anchors (store/state tid (path))))
              "every anchor the rescue accepted is recorded as this session's"))
        (let [[anchor _] (row-for (content-of out) "BETA")
              again (replace! {:remove_from anchor :replacement_lines ["BETA2"]})]
          (is (false? (:error again)) (content-of again))
          (is (not (str/includes? (content-of again) "ownership table"))
              "the second edit did not need the fallback"))))))

(deftest the-rescue-does-not-fire-when-two-lines-look-alike
  ;; `range-from-view` refuses to choose between them, because choosing is the
  ;; guess the whole scheme exists to prevent -- and the model is told to read the
  ;; file, which is the only thing that can tell the two lines apart.
  (use-mode!)
  (write! (text ["one" "two" "two"]))
  (let [[_ b _] (read!)]
    (forget-ownership!)
    (let [out (replace! {:path "f.txt" :remove_from b :replacement_lines ["X"]})]
      (is (true? (:error out)) (content-of out))
      (is (str/includes? (content-of out) "Call read"))
      (is (= (text ["one" "two" "two"]) (slurp file :encoding "UTF-8"))
          "nothing was written"))))

(deftest the-rescue-needs-a-view-to-read-the-line-out-of
  ;; No view means no line and no checksum -- there is nothing to confirm the anchor
  ;; against, so the answer is the ordinary 'read it first'.
  (use-mode!)
  (write! (text ["one" "two"]))
  (let [out (replace! {:path "f.txt" :remove_from "AAAB" :replacement_lines ["X"]})]
    (is (true? (:error out)))
    (is (str/includes? (content-of out) "Call read"))))

;; ---------------------------------------------------------------- the shape

(deftest every-refusal-is-information-and-keeps-the-run-going
  (use-mode!)
  (write! (text (lines 4)))
  (let [[a b _ d] (read!)]
    (write! (text ["line1" "CHANGED" "line3" "line4"]))
    (doseq [[what args] {"a name that is not an anchor" {:remove_from "Hasu"
                                                         :replacement_lines ["X"]}
                         "an anchor nobody holds"       {:remove_from "ZZZZ"
                                                         :replacement_lines ["X"]}
                         "a drifted range"              {:remove_from b
                                                         :replacement_lines ["X"]}
                         "a NUL byte"                   {:remove_from a
                                                         :replacement_lines ["\u0000"]}
                         "an empty file"                {:remove_from a :remove_to d
                                                         :replacement_lines []}
                         "a missing field"              {:remove_from a}
                         "an unknown argument"          {:remove_from a
                                                         :replacement_lines []
                                                         :nope 1}}]
      (let [{:keys [content error]} (replace! args)]
        (is (true? error) what)
        (is (string? content) what)
        (is (pos? (count (str/trim content))) (str what " -> " content)))))
  (testing "and the session is still usable afterwards"
    (let [out (replace! {:remove_from (first (read!)) :replacement_lines ["ONE"]})]
      (is (false? (:error out)) (content-of out)))))
