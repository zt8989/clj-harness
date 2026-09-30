(ns harness.cap.hashline.replace-test
  "The anchor-addressed edit: the payload grammar, the slips that are fixed rather
  than refused, literal application, and the answer that hands the model its next
  edit without a read."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [harness.infra.db :as db]
            [harness.cap.hashline.anchors :as anchors]
            [harness.cap.hashline.edit :as edit]
            [harness.cap.hashline.store :as store]
            [harness.cap.hashline.files :as files]
            [harness.infra.home :as home]
            [harness.cap.project :as project]
            [harness.kernel.tools :as tools]
            [harness.test-support :as support]))

(use-fixtures :once support/with-builtins)

(def ^:private root
  (support/temp-dir "hashline-replace"))

(def ^:private file (io/file root "f.txt"))

(defn- path
  "The path the store books f.txt under: the canonical one, which on macOS is not
  the string `(str file)` produces (the temp directory is reached through a
  symlink). Asking the store with the other spelling returns nil and reads as 'no
  row', which is the kind of near-miss a test should not be able to make."
  []
  (store/canonical (str file)))

(defn- config
  "Write the project's harness.edn, so the session's resolved :editing map is what
  the test says it is. Every edit reads it fresh, so this takes effect at once."
  [edn]
  (support/write-session! {:editing edn}))

(defn- wipe [f]
  (support/wipe-session!)
  (f)
  (support/wipe-session!))


(defn- clean-tables [f]
  (let [wipe-tables (fn []
                      (when (.exists (home/db-file))
                        (db/with-transaction
                          (fn [c]
                            (doseq [t ["hashline_snapshots" "hashline_ownership"
                                       "hashline_sessions" "hashline_undo"]]
                              (db/execute! c (str "DELETE FROM " t)))))))]
    (wipe-tables) (f) (wipe-tables)))

(use-fixtures :each wipe clean-tables)

(def ^:private tid "rt")

(defn- use-mode!
  ([] (use-mode! :hashline {}))
  ([mode] (use-mode! mode {}))
  ([mode extra]
   (config (merge {:mode mode} extra))
   (project/bind! tid root)))

(defn- call [name args]
  (tools/run! {:function {:name name :arguments (json/write-str args)}} tid))

(defn- write! [content] (spit file content :encoding "UTF-8"))

(defn- read!
  "Read the file and return its anchors in line order."
  []
  (let [out (:content (call "read" {:path "f.txt"}))]
    (mapv (fn [row] (subs row 0 (str/index-of row "│"))) (str/split-lines out))))

(defn- anchor-of
  "The anchor of the row whose CONTENT is LINE, from the CURRENT stored view --
  which is what a test wants when it needs to address a line by what it says."
  [line]
  (let [st (store/state tid (path))]
    (some (fn [[i a]] (when (= line (nth (mapv #(str/replace % "\r" "")
                                              (anchors/split-lines
                                               (slurp file :encoding "UTF-8")))
                                        i))
                        a))
          (map-indexed vector (:anchors st)))))

(defn- replace! [args] (call "replace" args))

(defn- diff-rows
  "The answer's rows as [prefix anchor line], the trailing prose dropped."
  [out]
  (->> (str/split-lines out)
       (remove #(str/starts-with? % "Edited"))
       (remove #(str/starts-with? % "The anchors"))
       (remove str/blank?)
       (mapv (fn [row]
               (let [prefix (subs row 0 1)
                     anchor (subs row 1 5)
                     line   (subs row 6)]
                 [prefix (if (str/blank? anchor) nil anchor) line])))))

;; ------------------------------------------------------------- the happy path

(deftest a-range-is-replaced
  (use-mode!)
  (write! "alpha\nbeta\ngamma\n")
  (let [[_ b _] (read!)]
    (let [out (:content (replace! {:remove_from b :remove_to b
                                   :replacement_lines ["BETA"]}))]
      (is (str/includes? out "Edited"))
      (is (= "alpha\nBETA\ngamma\n" (slurp file :encoding "UTF-8"))))))

(deftest omitting-remove-to-changes-one-line
  ;; The commonest edit there is, and making the model spell the same anchor twice
  ;; for it would be asking it to say the same thing twice.
  (use-mode!)
  (write! "one\ntwo\nthree\n")
  (let [[_ b _] (read!)]
    (is (str/includes? (:content (replace! {:remove_from b :replacement_lines ["TWO"]}))
                       "Edited"))
    (is (= "one\nTWO\nthree\n" (slurp file :encoding "UTF-8")))))

(deftest an-empty-array-deletes-the-range
  (use-mode!)
  (write! "one\ntwo\nthree\nfour\n")
  (let [[_ b c _] (read!)]
    (replace! {:remove_from b :remove_to c :replacement_lines []})
    (is (= "one\nfour\n" (slurp file :encoding "UTF-8")))))

(deftest one-empty-string-is-one-blank-line
  (use-mode!)
  (write! "one\ntwo\n")
  (let [[_ b] (read!)]
    (replace! {:remove_from b :replacement_lines [""]})
    (is (= "one\n\n" (slurp file :encoding "UTF-8"))
        "a blank line, not a deleted one")))

(deftest inserting-before-and-after-a-line
  (use-mode!)
  (write! "a\nb\nc\n")
  (let [[_ b _] (read!)]
    (testing "an empty range at the top of a line inserts before it"
      ;; The range is b..b and the replacement is the new lines PLUS b written
      ;; back -- which is what "insert before" means when everything is addressed
      ;; as a range.
      (replace! {:remove_from b :remove_to b :replacement_lines ["NEW" "b"]})
      (is (= "a\nNEW\nb\nc\n" (slurp file :encoding "UTF-8"))))
    (testing "and the line is still addressable afterwards"
      (is (some? (anchor-of "b"))))))

(deftest an-edit-at-the-first-line-and-at-the-last-both-work
  (use-mode!)
  (write! "first\nmiddle\nlast\n")
  (let [[a _ c] (read!)]
    (replace! {:remove_from a :replacement_lines ["FIRST"]})
    (let [[_ _ c2] (read!)]
      (replace! {:remove_from c2 :replacement_lines ["LAST"]})))
  (is (= "FIRST\nmiddle\nLAST\n" (slurp file :encoding "UTF-8"))))

(deftest a-file-that-never-ended-in-a-newline-does-not-acquire-one
  (use-mode!)
  (write! "one\ntwo")
  (let [[_ b] (read!)]
    (replace! {:remove_from b :replacement_lines ["TWO"]})
    (is (= "one\nTWO" (slurp file :encoding "UTF-8"))
        "the trailing-newline fact is preserved in both directions")))

;; ------------------------------------------------------- the answer's shape

(deftest the-answer-carries-the-changed-region-and-its-anchors
  (use-mode!)
  (write! "alpha\nbeta\ngamma\ndelta\n")
  (let [[a b _ _] (read!)
        out (:content (replace! {:remove_from b :replacement_lines ["BETA"]}))
        rows (diff-rows out)]
    (testing "a removed row and an added row, and the added one carries an anchor"
      (is (some (fn [[p _ _]] (= "-" p)) rows))
      (let [[_ anchor line] (first (filter (fn [[p _ _]] (= "+" p)) rows))]
        (is (some? anchor))
        (is (anchors/anchor? anchor))
        (is (= "BETA" line))))
    (testing "context rows carry the anchors that are still current"
      (let [ctx (filter (fn [[p _ _]] (= " " p)) rows)]
        (is (seq ctx))
        (is (every? (fn [[_ an _]] (and an (anchors/anchor? an))) ctx))))
    (testing "the removed row's anchor column is BLANK, so nobody copies a dead one"
      (is (every? (fn [[p an _]] (if (= "-" p) (nil? an) true)) rows)))))

(deftest the-anchors-in-the-answer-are-immediately-usable
  ;; THE POINT OF THE WHOLE FEATURE. No re-read: the model takes the anchor off a
  ;; `+` row and edits again.
  (use-mode!)
  (write! "alpha\nbeta\ngamma\n")
  (let [[_ b _] (read!)
        out     (:content (replace! {:remove_from b :replacement_lines ["BETA"]}))
        plus    (first (filter (fn [[p _ _]] (= "+" p)) (diff-rows out)))
        anchor  (second plus)]
    (let [again (:content (replace! {:remove_from anchor :replacement_lines ["BETA2"]}))]
      (is (str/includes? again "Edited"))
      (is (= "alpha\nBETA2\ngamma\n" (slurp file :encoding "UTF-8"))))))

(deftest a-context-row-is-usable-too
  (use-mode!)
  (write! "alpha\nbeta\ngamma\n")
  (let [[_ b _] (read!)
        out     (:content (replace! {:remove_from b :replacement_lines ["BETA"]}))
        ;; The line AFTER the change, named by its content rather than by its
        ;; position in the answer: which of the two context rows comes first is not
        ;; what this case is about, and picking it by position would have been a
        ;; test of the row order wearing a different name.
        ctx     (first (filter (fn [[p _ line]] (and (= " " p) (= "gamma" line)))
                               (diff-rows out)))
        anchor  (second ctx)]
    (is (some? anchor) "the trailing context row carries an anchor")
    (is (str/includes? (:content (replace! {:remove_from anchor
                                            :replacement_lines ["GAMMA2"]}))
                       "Edited"))
    (is (= "alpha\nBETA\nGAMMA2\n" (slurp file :encoding "UTF-8")))))

(deftest the-context-window-follows-the-config
  (use-mode! :hashline {:diff-context-lines 0})
  (write! "1\n2\n3\n4\n5\n")
  (let [[_ b _ _ _] (read!)]
    (is (not (str/includes? (:content (replace! {:remove_from b
                                                 :replacement_lines ["TWO"]}))
                            " 1│"))
        "no context lines at 0")))

;; -------------------------------------------------------- literal application

(deftest a-replacement-is-applied-literally-even-when-it-repeats-a-neighbour
  ;; There is no boundary dedup: a replacement that re-includes the line just
  ;; outside the range is applied exactly as written, so the repeat lands.
  (use-mode!)
  (testing "the NEXT line repeated stays a repeat"
    (write! "one\ntwo\nthree\nfour\n")
    (let [[_ b c _] (read!)]
      (replace! {:remove_from b :remove_to c :replacement_lines ["X" "four"]})
      (is (= "one\nX\nfour\nfour\n" (slurp file :encoding "UTF-8"))
          "the file has two 'four' lines now")))
  (testing "and so does the PREVIOUS line repeated"
    (write! "one\ntwo\nthree\n")
    (let [[_ b c] (read!)]
      (replace! {:remove_from b :remove_to c :replacement_lines ["one" "X"]})
      (is (= "one\none\nX\n" (slurp file :encoding "UTF-8"))
          "the leading 'one' is kept, not stripped"))))

;; ------------------------------------------------------- the auto-fixes

(deftest the-slips-are-fixed-and-reported
  (use-mode!)
  (write! "one\ntwo\nthree\n")
  (let [[_ b _] (read!)]
    (testing "a pasted whole row where an anchor belongs"
      (let [out (:content (replace! {:remove_from (str b "│two")
                                     :replacement_lines ["TWO"]}))]
        (is (str/includes? out "Edited"))
        (is (str/includes? out "Stripped") "and says what it stripped")
        (is (= "one\nTWO\nthree\n" (slurp file :encoding "UTF-8")))))
    (testing "a pasted diff row"
      (write! "one\ntwo\nthree\n")
      (let [[_ b2 _] (read!)]
        (is (str/includes? (:content (replace! {:remove_from (str "+" b2 "│two")
                                                :replacement_lines ["TWO"]}))
                           "Edited"))))
    (testing "the whole JSON array inside one element"
      (write! "one\ntwo\nthree\n")
      (let [[_ b3 _] (read!)]
        (is (str/includes? (:content (replace! {:remove_from b3
                                                :replacement_lines ["[\"X\",\"Y\"]"]}))
                           "Edited"))
        (is (= "one\nX\nY\nthree\n" (slurp file :encoding "UTF-8")))))
    (testing "an embedded newline splits into one line each"
      (write! "one\ntwo\nthree\n")
      (let [[_ b4 _] (read!)]
        (replace! {:remove_from b4 :replacement_lines ["X\nY"]})
        (is (= "one\nX\nY\nthree\n" (slurp file :encoding "UTF-8")))))
    (testing "a `│` prefix copied into the replacement"
      (write! "one\ntwo\nthree\n")
      (let [[_ b5 _] (read!)]
        (replace! {:remove_from b5 :replacement_lines [(str b5 "│X")]})
        (is (= "one\nX\nthree\n" (slurp file :encoding "UTF-8")))))))

(deftest strict-input-refuses-the-slips-instead
  (use-mode! :hashline {:strict-input true})
  (write! "one\ntwo\nthree\n")
  (let [[_ b _] (read!)]
    (let [out (:content (replace! {:remove_from (str b "│two")
                                   :replacement_lines ["TWO"]}))]
      (is (str/includes? out "strict-input"))
      (is (str/includes? out "Stripped") "and lists what it declined to fix"))
    (is (= "one\ntwo\nthree\n" (slurp file :encoding "UTF-8")) "nothing was written")))

;; ------------------------------------------------------- the refusals

(deftest a-nul-byte-is-refused-and-explained
  (use-mode!)
  (write! "one\ntwo\n")
  (let [[a _] (read!)
        out (:content (replace! {:remove_from a :replacement_lines ["x\u0000y"]}))]
    (is (str/includes? out "NUL"))
    (is (str/includes? out "Remove it") "the message says what to do")
    (is (= "one\ntwo\n" (slurp file :encoding "UTF-8")))))

(deftest emptying-a-file-is-refused-and-pointed-at-write
  (use-mode!)
  (write! "one\ntwo\n")
  (let [as (read!)
        out (:content (replace! {:remove_from (first as) :remove_to (last as)
                                 :replacement_lines []}))]
    (is (str/includes? out "empty"))
    (is (str/includes? out "write") "and names the tool that CAN clear a file")
    (is (= "one\ntwo\n" (slurp file :encoding "UTF-8")))))

(deftest an-edit-that-would-grow-the-file-past-the-limit-is-refused
  ;; Read and write share one size limit, so a result past it is refused BEFORE the
  ;; file is touched -- the next read could not open what the edit would have left.
  (use-mode!)
  (write! "one\ntwo\n")
  (let [[a _] (read!)]
    (with-redefs [files/max-bytes 10]
      (let [{:keys [content error]} (replace! {:remove_from a
                                               :replacement_lines ["a-much-longer-line"]})]
        (is (true? error) (pr-str content))
        (is (str/includes? content "byte limit") (pr-str content))))
    (is (= "one\ntwo\n" (slurp file :encoding "UTF-8")) "nothing was written")
    (testing "and an edit that stays under the limit still goes through"
      (with-redefs [files/max-bytes 1000]
        (is (false? (:error (replace! {:remove_from a :replacement_lines ["ONE"]}))))))))

(deftest a-line-number-where-an-anchor-belongs-gets-its-own-message
  (use-mode!)
  (write! "one\ntwo\n")
  (read!)
  (let [out (:content (replace! {:remove_from "2" :replacement_lines ["X"]}))]
    (is (str/includes? out "line number"))
    (is (str/includes? out "Copy the 4-character anchor"))))

(deftest malformed-payloads-are-named-one-by-one
  (use-mode!)
  (write! "one\ntwo\n")
  (let [[a _] (read!)
        check (fn [args fragment]
                (let [{:keys [content error]} (replace! args)]
                  (is (true? error) (pr-str args))
                  (is (str/includes? content fragment) (str args " -> " content))))]
    (check {:replacement_lines ["X"]} "remove_from")
    (check {:remove_from a} "replacement_lines")
    (check {:remove_from a :replacement_lines 42} "array of strings")
    (check {:remove_from a :replacement_lines [1 2]} "must be a string")
    (check {:remove_from "toolong" :replacement_lines []} "4-character anchor")
    (check {:remove_from a :replacement_lines [] :nonsense 1} "does not take")))

(deftest a-whole-field-sent-as-one-string-is-read
  ;; Models sometimes put the whole array in the FIELD rather than in one element.
  ;; Both readings are unambiguous, so they are applied and reported -- and a bare
  ;; one-line string is one line.
  (use-mode!)
  (write! "one\ntwo\n")
  (let [[a b] (read!)]
    (testing "a JSON array as the whole field"
      (let [{:keys [content error]} (replace! {:remove_from a :remove_to b
                                               :replacement_lines "[\"X\",\"Y\"]"})]
        (is (not error) (pr-str content))
        (is (str/includes? content "Unwrapped a JSON array") (pr-str content))))
    (testing "content as the whole field, split on newlines"
      (write! "one\ntwo\n")
      (let [[a b] (read!)
            {:keys [content error]} (replace! {:remove_from a :remove_to b
                                               :replacement_lines "P\nQ"})]
        (is (not error) (pr-str content))
        (is (str/includes? content "Split the whole") (pr-str content))))
    (testing "a bare one-line string is one line"
      (write! "one\ntwo\n")
      (let [[a _] (read!)
            {:keys [error]} (replace! {:remove_from a :replacement_lines "X"})]
        (is (not error) (pr-str error))))))

(deftest an-anchor-from-another-file-is-refused
  (use-mode!)
  (write! "one\ntwo\n")
  (let [[a _] (read!)]
    (testing "a four-letter string that is not in the pool was never an anchor"
      ;; The distinction earns its keep here: no amount of reading turns `Hasu`
      ;; into a name, so telling the model to read again would send it to repeat
      ;; the mistake. `Hasu` is a real 4-character string, just not one the table
      ;; hands out.
      (let [{:keys [content error]} (replace! {:remove_from "Hasu"
                                               :replacement_lines ["X"]})]
        (is (true? error))
        (is (str/includes? content "not an anchor"))
        (is (str/includes? content "read") "and still says where names come from")))
    (testing "a real anchor this session does not hold is what a read fixes"
      (let [other (first (map anchors/anchor-at (range 200000 200010)))
            {:keys [content] :as r} (replace! {:remove_from other
                                               :replacement_lines ["X"]})]
        (is (not (str/includes? content "is not an anchor")) (pr-str r))
        (is (str/includes? content "Call read") (pr-str r))))))

(deftest the-anchor-fields-have-compat-spellings
  ;; `replace_from`/`replace_to` and `from`/`to` are accepted for models that spell
  ;; the fields that way; the canonical name wins when more than one is present.
  (use-mode!)
  (write! "one\ntwo\n")
  (let [[a b] (read!)]
    (testing "replace_from/replace_to"
      (let [{:keys [content error]} (replace! {:replace_from a :replace_to b
                                               :replacement_lines ["X"]})]
        (is (not error) (pr-str content))
        (is (= "X\n" (slurp file :encoding "UTF-8")))))
    (write! "one\ntwo\n")
    (let [[a b] (read!)]
      (testing "from/to"
        (let [{:keys [content error]} (replace! {:from a :to b :replacement_lines ["Y"]})]
          (is (not error) (pr-str content))
          (is (= "Y\n" (slurp file :encoding "UTF-8"))))))
    (write! "one\ntwo\n")
    (let [[a b] (read!)]
      (testing "the canonical name wins over an alias"
        (let [out (replace! {:remove_from a :from b :replacement_lines ["Z"]})]
          (is (not (:error out)) (pr-str (:content out)))
          (is (= "Z\ntwo\n" (slurp file :encoding "UTF-8"))
              "remove_from (line 1) won, not `from` (line 2)"))))
    (testing "no anchor spelling at all is refused by name"
      (let [{:keys [content error]} (replace! {:replacement_lines ["X"]})]
        (is (true? error))
        (is (str/includes? content "remove_from") (pr-str content))))))

(deftest an-anchor-mistyped-in-case-names-the-one-that-is-held
  ;; The anchor table carries both `A-Z` and `a-z`, so `Hasu` and `hasu` are two
  ;; legal, different anchors. A model that miscases one it was handed is one
  ;; sentence away from the fix, and 'call read' is not that sentence.
  (use-mode!)
  (write! "one\ntwo\n")
  (let [[a _] (read!)
        flipped (apply str (map (fn [c] (if (Character/isUpperCase ^char c)
                                          (Character/toLowerCase ^char c)
                                          (Character/toUpperCase ^char c)))
                                a))
        {:keys [content error]} (replace! {:remove_from flipped
                                           :replacement_lines ["X"]})]
    (is (true? error))
    (is (str/includes? content "case-sensitive") (pr-str content))
    (is (str/includes? content a) "the true spelling is named in the answer")
    (testing "a name the session never had gets no such sentence"
      (let [{:keys [content]} (replace! {:remove_from "Zzzz" :replacement_lines ["X"]})]
        (is (not (str/includes? content "case-sensitive")) (pr-str content))))))

(deftest editing-an-unread-file-says-to-read-it
  (use-mode!)
  (write! "one\ntwo\n")
  (let [{:keys [content error]} (replace! {:remove_from "AAAB"
                                           :replacement_lines ["X"]})]
    (is (true? error))
    (is (str/includes? content "read") "the answer names the way in")))

(deftest a-drifted-file-is-refused
  (use-mode!)
  (write! "one\ntwo\nthree\n")
  (let [[_ b _] (read!)]
    (spit file "one\nTWO-EDITED-ELSEWHERE\nthree\n" :encoding "UTF-8")
    (let [{:keys [content error]} (replace! {:remove_from b
                                             :replacement_lines ["X"]})]
      (is (true? error))
      (is (str/includes? content "changed after it was read"))
      (is (str/includes? content "read") "and says to read again"))))

(deftest a-line-that-was-never-shown-cannot-be-edited
  ;; A paged read mints anchors for lines it did not return. They are real anchors
  ;; for real lines, and the model has never seen them -- editing one would be
  ;; changing a line it has not looked at, which is the guess this feature exists
  ;; to prevent.
  (use-mode!)
  (write! (str/join "\n" (map #(str "line" %) (range 1 31))))
  (let [out (:content (call "read" {:path "f.txt" :limit 5}))
        _   (is (str/includes? out "offset=6") "the first page stopped early")
        st  (store/state tid (path))
        unshown (first (remove (:served st) (:anchors st)))
        _   (is (some? unshown) "there ARE anchors outside the first page")]
    (let [{:keys [content error]} (replace! {:remove_from unshown
                                             :replacement_lines ["X"]})]
      (is (true? error))
      (is (str/includes? content "never shown"))
      (testing "and the lines come back with their anchors, so the retry is a retry"
        (let [row (first (filter #(str/includes? % "│line6") (str/split-lines content)))
              anchor (subs row 1 5)]
          (is (anchors/anchor? anchor) (str "row: " row))
          (is (false? (:error (replace! {:remove_from anchor
                                         :replacement_lines ["LINE6"]})))
              "the second call needs no read"))))))

;; ------------------------------------------------------ the no-op and title

(deftest a-no-op-edit-writes-nothing-and-keeps-the-undo-history
  (use-mode!)
  (write! "one\ntwo\nthree\n")
  (let [[_ b _] (read!)]
    (replace! {:remove_from b :replacement_lines ["TWO"]})
    (let [[_ b2 _] (read!)
          out (:content (replace! {:remove_from b2 :replacement_lines ["TWO"]}))]
      (is (str/includes? out "No change"))
      (is (= "one\nTWO\nthree\n" (slurp file :encoding "UTF-8")))
      (testing "the undo record from the FIRST edit is still there"
        ;; The no-op must not cost the model its ability to take back the edit
        ;; before it -- which is what writing the same text back would do.
        (is (= "one\ntwo\nthree\n" (:prior-text (store/undo-for (path)))))))))

(deftest an-edit-does-not-unshow-the-lines-it-did-not-touch
  ;; The `served` set answers 'has this session seen this line', and an edit only
  ;; ever ADDS to it: the answer the edit handed back shows a couple of lines, and
  ;; a session that let that short list REPLACE ten lines' worth of seen-ness would
  ;; start refusing the lines the model read and never touched -- 'this line was
  ;; never shown to you' about a line it is looking at.
  (use-mode!)
  (write! (str/join "\n" (map #(str "line" %) (range 1 11))))
  (let [[_ b _ _ _ _ _ _ _ i] (read!)]
    (replace! {:remove_from b :replacement_lines ["LINE2"]})
    (let [{:keys [content error]} (replace! {:remove_from i
                                             :replacement_lines ["LINE10"]})]
      (is (false? error) (str "editing a line the first read showed: " content))
      (is (= ["line1" "LINE2" "line3" "line4" "line5"
              "line6" "line7" "line8" "line9" "LINE10"]
             (str/split-lines (slurp file :encoding "UTF-8")))))))

(deftest a-path-that-agrees-with-the-anchor-is-not-a-mismatch
  ;; `:require-path true` makes the file's name an argument the session insists on,
  ;; and the model will give it the way it gives every other path -- relative to the
  ;; project. The agreement check therefore has to compare RESOLVED paths: comparing
  ;; the raw argument with the anchor's owner refuses every call in the mode that
  ;; exists to be given one. (It did, for one commit, and no other case noticed
  ;; because they all dropped `path` -- which is exactly what this case is for.)
  (use-mode! :hashline {:require-path true})
  (write! "one\ntwo\nthree\n")
  (let [[_ b _] (read!)]
    (testing "a relative path that names the right file goes through"
      (let [out (replace! {:path "f.txt" :remove_from b
                           :replacement_lines ["TWO"]})]
        (is (false? (:error out)) (:content out))
        (is (= "one\nTWO\nthree\n" (slurp file :encoding "UTF-8")))))
    (testing "and one that names a different file is still refused"
      (spit (io/file root "g.txt") "x\n" :encoding "UTF-8")
      ;; A FRESH anchor: the edit above freed `b`, and an anchor nobody owns takes
      ;; the 'not read' road instead of the mismatch one -- which would be a
      ;; different test wearing this one's name.
      (let [live (first (read!))
            {:keys [content error]} (replace! {:path "g.txt" :remove_from live
                                               :replacement_lines ["X"]})]
        (is (true? error))
        (is (str/includes? content "names a line in") content)))
    (testing "and omitting it is refused, because this session asked for it"
      (let [{:keys [content error]} (replace! {:remove_from b
                                               :replacement_lines ["X"]})]
        (is (true? error))
        (is (str/includes? content "required"))))))

(deftest a-read-after-an-edit-still-agrees-with-the-store
  ;; The anchors the edit handed out and the anchors a fresh read hands out have to
  ;; be the same ones, or the model is being told two different things about one
  ;; file.
  (use-mode!)
  (write! "one\ntwo\nthree\n")
  (let [[_ b _] (read!)]
    (replace! {:remove_from b :replacement_lines ["TWO"]})
    (is (= (read!) (:anchors (store/state tid (path))))
        "a re-read reproduces exactly what the store holds")))

;; ------------------------------------------------------------ what the model sees

(deftest the-described-tool-is-the-tool-that-runs
  ;; The description is the ONLY thing a model has to go on. This asserts the claims
  ;; the ticket requires it to make -- where anchors come from, that `[]` deletes,
  ;; that a new line carries no `│`, that the answer's anchors are immediately
  ;; usable, and that a refusal hands back what to retry with -- plus the parameter
  ;; shape they describe. It deliberately does NOT assert the one promise still
  ;; outstanding, that several replace calls in one message are one commit: that
  ;; sentence is absent from the description rather than shipped as a promise nothing
  ;; keeps, and this is where that shows.
  (use-mode!)
  (let [spec   (first (filter #(= "replace" (get-in % [:function :name]))
                              (tools/specs tid)))
        desc   (:description (:function spec))
        props  (get-in spec [:function :parameters :properties])]
    (is (some? spec) "replace is in this session's tool table")
    (is (= #{"remove_from" "remove_to" "replacement_lines"} (set (keys props))))
    (testing "the description says where anchors come from"
      (is (str/includes? desc "ANCHORS"))
      (is (str/includes? desc "read")))
    (testing "and what the payload means"
      (is (str/includes? desc "empty array deletes"))
      (is (str/includes? desc "no `│`")))
    (testing "and that the answer's anchors are usable without a re-read"
      (is (str/includes? desc "no read"))
      (is (str/includes? desc "CURRENT anchor")))
    (testing "and that a refusal comes back with something to retry with"
      (is (str/includes? desc "refusal"))
      (is (str/includes? desc "CURRENT"))
      (is (not (str/includes? desc "one commit"))
          "the batch promise waits for the ticket that makes it true"))
    (testing "every parameter's description is where the model reads the syntax"
      (is (str/includes? (get-in props ["remove_from" :description]) "│"))
      (is (str/includes? (get-in props ["remove_to" :description]) "Omit"))
      (is (str/includes? (get-in props ["replacement_lines" :description]) "[]")))))

;; --------------------------------------------------------- the undo record

(deftest the-undo-record-describes-the-edit-that-happened
  (use-mode!)
  (write! "one\ntwo\nthree\n")
  (let [[_ b _] (read!)
        before (store/undo-for (path))]
    (is (nil? before) "nothing to undo before the first edit")
    (replace! {:remove_from b :replacement_lines ["TWO"]})
    (let [u (store/undo-for (path))]
      (is (= "one\ntwo\nthree\n" (:prior-text u)))
      (is (= "one\nTWO\nthree\n" (:resulting-text u)))
      (is (seq (:anchors u)) "and the anchors that named the old text"))))

(deftest the-undo-record-round-trips-bom-and-endings
  (use-mode!)
  (spit file "\uFEFFone\r\ntwo\r\n" :encoding "UTF-8")
  (let [[_ b] (read!)]
    (replace! {:remove_from b :replacement_lines ["TWO"]})
    (let [u (store/undo-for (path))]
      (is (true? (:bom u)))
      (is (= "\r\n" (:ending u))))
    (testing "and the file itself is still CRLF with its BOM"
      (let [raw (slurp file :encoding "UTF-8")]
        (is (= \uFEFF (.charAt raw 0)))
        (is (str/includes? raw "\r\n"))))))
