(ns harness.hashline-insert-test
  "`insert`: adding lines beside a line without disturbing it, and the things it
  deliberately does differently from `replace` (no dedup; the anchored line keeps
  its name)."
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
  (str (io/file (System/getProperty "java.io.tmpdir") "harness-hashline-insert-test")))

(io/delete-file root true)
(.mkdirs (io/file root))

(def ^:private user-file (io/file (home/root) "harness.edn"))
(def ^:private project-file (io/file root ".harness" "harness.edn"))
(def ^:private file (io/file root "f.txt"))

(defn- path [] (store/canonical (str file)))

(defn- wipe [f]
  (io/delete-file user-file true)
  (io/delete-file project-file true)
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

(def ^:private tid "it")

(defn- use-mode!
  ([] (use-mode! {}))
  ([extra]
   (.mkdirs (.getParentFile project-file))
   (spit project-file (str "{:editing " (pr-str (merge {:mode :hashline} extra)) "}")
         :encoding "UTF-8")
   (project/bind! tid root)))

(defn- call-map
  "A tool call in the provider's shape, NOT run."
  [id name args]
  {:id id :type "function"
   :function {:name name :arguments (json/write-str args)}})

(defn- call
  "Run one tool call and return the seam's result."
  ([name args] (call "c" name args))
  ([id name args] (tools/run! (call-map id name args) tid)))

(defn- insert!
  ([args] (call "insert" args))
  ([anchor direction lines]
   (insert! {:anchor anchor :direction direction :lines lines})))

(defn- put! [content] (spit file content :encoding "UTF-8"))
(defn- contents [] (slurp file :encoding "UTF-8"))

(defn- read-rows
  "The read answer's rows as [anchor content]."
  []
  (keep (fn [r]
          (let [i (str/index-of r "│")]
            (when (and i (>= i 4)) [(subs r (- i 4) i) (subs r (inc i))])))
        (str/split-lines (:content (call "read" {:path "f.txt"})))))

(defn- read!
  "The anchors of f.txt in line order."
  []
  (mapv first (read-rows)))

(defn- anchor-of
  "The anchor naming the line whose content is LINE, from a FRESH read.

  Address by CONTENT, not by position. Every one of these cases writes the file and
  then reads it again, and an index into the old answer silently means a different
  line afterwards -- which turns 'insert before gamma' into 'insert before whatever
  is now third', i.e. a test of something else that still passes."
  [line]
  (some (fn [[a l]] (when (= line l) a)) (read-rows)))

(defn- diff-rows
  "The answer's rows as [prefix anchor line], the prose dropped."
  [out]
  (->> (str/split-lines out)
       (remove #(str/starts-with? % "Edited"))
       (remove #(str/starts-with? % "Inserted"))
       (remove #(str/starts-with? % "The anchors"))
       (remove #(str/starts-with? % "Note:"))
       (remove str/blank?)
       (keep (fn [row]
               (when (>= (count row) 6)
                 [(subs row 0 1) (let [a (subs row 1 5)]
                                   (when-not (str/blank? a) a))
                  (subs row 6)])))))

(defn- row-for [out line]
  (some (fn [[_ a l]] (when (= line l) a)) (diff-rows out)))

;; -------------------------------------------------------------- the two ways

(deftest insert-after-and-insert-before
  (use-mode!)
  (put! "alpha\nbeta\ngamma\n")
  (testing "after puts the lines below the anchored one"
    (is (false? (:error (insert! (anchor-of "beta") "after" ["AFTER-1" "AFTER-2"]))))
    (is (= "alpha\nbeta\nAFTER-1\nAFTER-2\ngamma\n" (contents))))
  (testing "before puts them above it"
    (is (false? (:error (insert! (anchor-of "gamma") "before" ["BEFORE"]))))
    (is (= "alpha\nbeta\nAFTER-1\nAFTER-2\nBEFORE\ngamma\n" (contents)))))

(deftest the-anchored-line-keeps-its-anchor
  ;; THE PROPERTY THIS TOOL EXISTS FOR. If the anchored line's name changed, a model
  ;; that inserts a line would have to re-read before it could touch the line it was
  ;; already looking at -- which is the round trip anchors are supposed to remove.
  (use-mode!)
  (put! "alpha\nbeta\ngamma\n")
  (let [[a b c] (read!)]
    (insert! b "after" ["NEW"])
    (testing "the anchored line is still called what it was called"
      (is (= b (anchor-of "beta"))))
    (testing "and so are the lines after it: the insertion is an EMPTY span, so
              nothing was rewritten and no position changed meaning"
      (is (= a (anchor-of "alpha")))
      (is (= c (anchor-of "gamma"))))
    (testing "so the ORIGINAL anchor is still usable for an edit"
      (let [out (call "replace" {:remove_from b :replacement_lines ["BETA"]})]
        (is (false? (:error out)) (:content out))
        (is (= "alpha\nBETA\nNEW\ngamma\n" (contents)))))))

(deftest the-new-lines-get-anchors-the-answer-hands-over
  (use-mode!)
  (put! "alpha\nbeta\ngamma\n")
  (let [out (:content (insert! (anchor-of "beta") "after" ["NEW-A" "NEW-B"]))]
    (testing "each inserted line appears as a `+` row with an anchor"
      (doseq [line ["NEW-A" "NEW-B"]]
        (let [a (row-for out line)]
          (is (some? a) (str "no anchor for " line " in:\n" out))
          (is (anchors/anchor? a)))))
    (testing "and those anchors are immediately editable"
      (let [again (call "replace" {:remove_from (row-for out "NEW-B")
                                   :replacement_lines ["NEW-B2"]})]
        (is (false? (:error again)) (:content again))
        (is (= "alpha\nbeta\nNEW-A\nNEW-B2\ngamma\n" (contents)))))))

(deftest an-inserted-line-identical-to-its-neighbour-is-inserted
  ;; NO BOUNDARY DEDUP HERE, and this is the contrast with `replace`: there, a
  ;; replacement that re-includes the line at the edge of its range is a slip worth
  ;; stripping (or refusing); here, asking for a line that reads like its neighbour
  ;; is the request itself. The two cases sit side by side on purpose, on the same
  ;; file and the same shape of request.
  (use-mode!)
  (put! "one\ntwo\nthree\n")
  (testing "insert keeps the repeat, even of the line it is anchored to"
    (is (false? (:error (insert! (anchor-of "one") "after" ["one"]))))
    (is (= "one\none\ntwo\nthree\n" (contents))
        "the insert did exactly what it said"))
  (testing "and replace, asked the same shape, strips it"
    (put! "one\ntwo\nthree\n")
    ;; The range is two..three and the replacement starts by writing back `one`,
    ;; the line BEFORE the range -- the artefact `:boundary-dedup` exists for.
    (call "replace" {:remove_from (anchor-of "two") :remove_to (anchor-of "three")
                     :replacement_lines ["one" "NEW"]})
    (is (= "one\nNEW\n" (contents)) "the leading `one` was stripped")))

(deftest a-no-op-insert-writes-nothing-and-keeps-the-undo-history
  (use-mode!)
  (put! "one\ntwo\nthree\n")
  (let [[_ b _] (read!)]
    (call "replace" {:remove_from b :replacement_lines ["TWO"]})
    (let [out (:content (insert! (anchor-of "TWO") "after" []))]
      (is (str/includes? out "No change") out)
      (is (= "one\nTWO\nthree\n" (contents)))
      (testing "the undo record from the earlier edit is still there"
        (is (= "one\ntwo\nthree\n" (:prior-text (store/undo-for (path)))))))))

;; ------------------------------------------------------------- the payload

(deftest a-blank-line-and-awkward-whitespace-survive
  (use-mode!)
  (put! "alpha\nomega\n")
  (insert! (anchor-of "alpha") "after" ["" "   indented  " "\tTAB"])
  (is (= "alpha\n\n   indented  \n\tTAB\nomega\n" (contents))
      "a blank line is a blank line, and trailing spaces are the model's text"))

(deftest malformed-payloads-are-named-one-by-one
  ;; Where the ARGUMENT CHECK catches it first (a missing `anchor`, `direction` or
  ;; `lines` is a required argument, refused by the seam before the tool body runs)
  ;; the message is the seam's; everything else is the tool's own. The fragments
  ;; below are chosen to match whichever speaks, so the test is about the fact that
  ;; something names the problem -- not about which layer noticed.
  (use-mode!)
  (put! "one\ntwo\n")
  (let [a     (anchor-of "one")
        check (fn [args fragment]
                (let [{:keys [content error]} (insert! args)]
                  (is (true? error) (pr-str args))
                  (is (str/includes? content fragment) (str args " -> " content))))]
    (check {:direction "after" :lines ["X"]} "anchor")
    (check {:anchor a :lines ["X"]} "direction")
    (check {:anchor a :direction "sideways" :lines ["X"]} "before\" or \"after")
    (check {:anchor a :direction "after"} "lines")
    (check {:anchor a :direction "after" :lines "X"} "array of strings")
    (check {:anchor a :direction "after" :lines [1]} "must be a string")
    (check {:anchor "toolong" :direction "after" :lines []} "4-character anchor")
    (check {:anchor a :direction "after" :lines [] :nope 1} "does not take")
    (testing "and the direction is read case-insensitively, because JSON is the
              model's to spell"
      (is (false? (:error (insert! {:anchor a :direction "AFTER" :lines ["X"]})))))))

(deftest a-nul-byte-is-refused-in-the-lines-too
  (use-mode!)
  (put! "one\ntwo\n")
  (let [out (:content (insert! (anchor-of "one") "after" ["x\u0000y"]))]
    (is (str/includes? out "NUL"))
    (is (str/includes? out "`lines`") "and names THIS tool's argument, not replace's")
    (is (= "one\ntwo\n" (contents)))))

(deftest strict-input-refuses-the-slips-here-too
  ;; The slips are the same slips (a pasted `anchor│row`, a whole JSON array in one
  ;; element), and they are treated the same way in both tools -- one vocabulary.
  (use-mode! {:strict-input true})
  (put! "one\ntwo\n")
  (let [a (anchor-of "one")
        out (:content (insert! {:anchor (str a "│one") :direction "after"
                                :lines ["X"]}))]
    (is (str/includes? out "strict-input"))
    (is (str/includes? out "Stripped") "and lists what it declined to fix")
    (is (= "one\ntwo\n" (contents)))))

(deftest a-pasted-anchor-row-is-fixed-and-reported
  (use-mode!)
  (put! "one\ntwo\n")
  (let [a (anchor-of "one")
        out (:content (insert! {:anchor (str a "│one") :direction "after"
                                :lines ["X"]}))]
    (is (str/includes? out "Note:") out)
    (is (str/includes? out "Stripped") out)
    (is (= "one\nX\ntwo\n" (contents)))))

;; ------------------------------------------------------------- the edges

(deftest the-first-and-last-lines-of-a-file
  (use-mode!)
  (put! "first\nmiddle\nlast\n")
  (testing "after the last line appends"
    (is (false? (:error (insert! (anchor-of "last") "after" ["TAIL"]))))
    (is (= "first\nmiddle\nlast\nTAIL\n" (contents))))
  (testing "and before the first line prepends"
    (is (false? (:error (insert! (anchor-of "first") "before" ["HEAD"]))))
    (is (= "HEAD\nfirst\nmiddle\nlast\nTAIL\n" (contents)))))

(deftest a-single-line-file-takes-both-directions
  (use-mode!)
  (put! "only\n")
  (insert! (anchor-of "only") "before" ["ABOVE"])
  (is (= "ABOVE\nonly\n" (contents)))
  (insert! (anchor-of "only") "after" ["BELOW"])
  (is (= "ABOVE\nonly\nBELOW\n" (contents))))

(deftest an-empty-file-can-be-filled
  ;; An empty file reads as ONE empty anchored row (ticket 04), and `insert after`
  ;; it is how a model puts the first content in without going through `write`.
  (use-mode!)
  (put! "")
  (let [[a line] (first (read-rows))]
    (is (some? a))
    (is (= "" line) "the empty file's one row is an empty line, and it has a name")
    (is (false? (:error (insert! a "after" ["first line" "second line"]))))
    (is (= "first line\nsecond line\n" (contents)))
    (testing "and the file can be edited normally afterwards"
      (is (false? (:error (call "replace" {:remove_from (anchor-of "first line")
                                           :replacement_lines ["FIRST"]}))))
      (is (= "FIRST\nsecond line\n" (contents))))))

(deftest the-file-s-own-ending-and-encoding-survive-an-insert
  (use-mode!)
  (spit file "\uFEFFone\r\ntwo\r\n" :encoding "UTF-8")
  (let [[_ b] (read!)]
    (is (false? (:error (insert! b "after" ["X"]))))
    (let [raw (slurp file :encoding "UTF-8")]
      (is (= \uFEFF (.charAt raw 0)))
      (is (str/includes? raw "X\r\n") "the new line got the file's own ending"))))

;; ------------------------------------------------------ the shared refusals

(deftest insert-shares-the-refusal-paths-with-replace
  ;; No second vocabulary for the same failures: an anchor nobody holds, a file
  ;; nobody read, and a file that moved all say what replace says.
  (use-mode!)
  (put! "one\ntwo\nthree\n")
  (let [b (anchor-of "two")]
    (testing "a name that is not an anchor"
      (is (str/includes? (:content (insert! "Hasu" "after" ["X"])) "not an anchor")))
    (testing "an anchor this session does not hold"
      (let [other (first (map anchors/anchor-at (range 200000 200010)))]
        (is (str/includes? (:content (insert! other "after" ["X"])) "Call read"))))
    (testing "a file that moved underneath the session, healed (ticket 06)"
      (put! "one\nSOMETHING-ELSE\nthree\n")
      (let [out (:content (insert! b "after" ["X"]))]
        (is (str/includes? out "changed after it was read") out)
        (is (str/includes? out "SOMETHING-ELSE") "and the current rows came back")
        (is (= "one\nSOMETHING-ELSE\nthree\n" (contents)) "nothing was written")))))

(deftest editing-an-unread-file-says-to-read-it
  (use-mode!)
  (put! "one\ntwo\n")
  (let [out (:content (insert! "Hasu" "after" ["X"]))]
    (is (str/includes? out "not an anchor")
        "a name that is not one is told apart from a real anchor nobody holds")))

;; ------------------------------------------------------------- with the rest

(deftest require-path-is-honoured-and-checked
  (use-mode! {:require-path true})
  (put! "one\ntwo\n")
  (let [a (anchor-of "one")]
    (testing "without it the call is refused"
      (is (str/includes? (:content (insert! a "after" ["X"])) "required")))
    (testing "with the right one it goes through"
      (is (false? (:error (insert! {:path "f.txt" :anchor a :direction "after"
                                    :lines ["X"]})))))
    (testing "and with the wrong one it is refused"
      (spit (io/file root "g.txt") "other\n" :encoding "UTF-8")
      (is (str/includes? (:content (insert! {:path "g.txt" :anchor (anchor-of "one")
                                             :direction "after" :lines ["Y"]}))
                         "names a line in")))))

(deftest an-insert-and-a-replace-in-one-message-are-one-commit
  ;; The batch rule (ticket 09) does not care which of the two tools a call came
  ;; from: they address the same file the same way, so they are spliced together.
  (use-mode!)
  (put! "alpha\nbeta\ngamma\ndelta\n")
  (let [calls [(call-map "c1" "insert" {:anchor (anchor-of "beta") :direction "after"
                                        :lines ["NEW"]})
               (call-map "c2" "replace" {:remove_from (anchor-of "delta")
                                         :replacement_lines ["DELTA"]})]]
    (tools/register-turn! tid calls)
    (try
      (let [m1 (tools/run! (first calls) tid)
            m2 (tools/run! (second calls) tid)]
        (is (str/includes? (:content m1) "ONE commit") (:content m1))
        (is (false? (:error m2)) (:content m2))
        (is (= "alpha\nbeta\nNEW\ngamma\nDELTA\n" (contents))))
      (finally (tools/forget-turn!))))
  (testing "and one undo takes back both"
    (is (false? (:error (call "undo_last_replace" {:path "f.txt"}))))
    (is (= "alpha\nbeta\ngamma\ndelta\n" (contents)))))

(deftest an-insert-is-undoable
  (use-mode!)
  (put! "one\ntwo\n")
  (insert! (anchor-of "one") "after" ["INSERTED"])
  (is (= "one\nINSERTED\ntwo\n" (contents)))
  (let [out (call "undo_last_replace" {:path "f.txt"})]
    (is (false? (:error out)) (:content out))
    (is (= "one\ntwo\n" (contents))))
  (testing "and the undo's own rows put the anchors back in the model's hands"
    (let [out (call "replace" {:remove_from (anchor-of "two")
                               :replacement_lines ["TWO"]})]
      (is (false? (:error out)) (:content out))
      (is (= "one\nTWO\n" (contents))))))

;; ---------------------------------------------------------- the tool's face

(deftest the-description-says-what-it-will-not-do
  ;; Three sentences the model has to have before it reaches for this: which line is
  ;; left alone, that the anchors stay valid, and that a repeat is not deduplicated
  ;; (the opposite of what replace does with one).
  (use-mode!)
  (let [spec (first (filter #(= "insert" (get-in % [:function :name]))
                            (tools/specs tid)))
        desc (:description (:function spec))
        props (get-in spec [:function :parameters :properties])]
    (is (some? spec) "insert is served in anchor mode")
    (is (= #{"anchor" "direction" "lines"} (set (keys props))))
    (is (str/includes? desc "KEEPS ITS ANCHOR"))
    (is (str/includes? desc "never deduplicates"))
    (is (str/includes? desc "no `│`"))
    (is (= ["before" "after"] (get-in props ["direction" :enum]))
        "the schema names the legal directions, not only the prose")))
