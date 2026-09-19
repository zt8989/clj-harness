(ns harness.cap.hashline.batch-test
  "Several edits to one file in one message: one commit, one diff, one undo -- and
  the refusals that keep the batch honest.

  The reason batching exists is not tidiness. A turn's calls run concurrently, and
  two edits to one file each validate against what the session was shown; run
  independently the later write wins and the earlier one disappears, with both
  reporting success. So the cases below check the OUTCOME (one write, correct
  content, one undo) and not just the happy-path diff."
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
  (support/temp-dir "hashline-batch"))

(def ^:private user-file (io/file (home/root) "harness.edn"))
(def ^:private project-file (io/file root ".harness" "harness.edn"))
(def ^:private file (io/file root "f.txt"))
(def ^:private other (io/file root "g.txt"))

(defn- path
  "The path the store books f.txt under -- canonical, which on macOS is not the
  string `(str file)` produces (the temp directory is reached through a symlink)."
  [^java.io.File f]
  (store/canonical (str f)))

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

(def ^:private tid "bt")

(defn- use-mode! []
  (.mkdirs (.getParentFile project-file))
  (spit project-file "{:editing {:mode :hashline}}" :encoding "UTF-8")
  (project/bind! tid root))

(defn- call
  "One tool call in the provider's shape."
  [id name args]
  {:id id :type "function"
   :function {:name name :arguments (json/write-str args)}})

(defn- run-one [call] (tools/run! call tid))

(defn- message!
  "Run CALLS as ONE turn: register the batch plan the way the run loop does, then
  run every call -- CONCURRENTLY when asked, which is what the loop actually does
  and what this feature exists to survive."
  [calls & {:keys [concurrent?] :or {concurrent? true}}]
  (tools/register-turn! tid calls)
  (try
    (if concurrent?
      (let [fs (mapv #(future (run-one %)) calls)]
        (mapv deref fs))
      (mapv run-one calls))
    (finally (tools/forget-turn!))))

(defn- read!
  ([] (read! "f.txt"))
  ([name]
   (let [out (:content (run-one (call "r" "read" {:path name})))]
     (mapv (fn [r] (subs r 0 (str/index-of r "│"))) (str/split-lines out)))))

(defn- contents [^java.io.File f] (slurp f :encoding "UTF-8"))

(defn- diff-rows
  "The rows of an answer, as [prefix anchor line], the prose dropped."
  [out]
  (->> (str/split-lines out)
       (remove #(str/starts-with? % "Edited"))
       (remove #(str/starts-with? % "The anchors"))
       (remove #(str/starts-with? % "Note:"))
       (remove str/blank?)
       (keep (fn [row]
               (when (>= (count row) 6)
                 (let [prefix (subs row 0 1)
                       anchor (subs row 1 5)
                       line   (subs row 6)]
                   [prefix (if (str/blank? anchor) nil anchor) line]))))))

;; --------------------------------------------------------------- the merge

(deftest two-edits-in-one-message-are-one-commit
  (use-mode!)
  (spit file "alpha\nbeta\ngamma\ndelta\n" :encoding "UTF-8")
  (let [[_ b _ d] (read!)
        [first-out last-out] (message! [(call "c1" "replace" {:remove_from b
                                                             :replacement_lines ["BETA"]})
                                        (call "c2" "replace" {:remove_from d
                                                             :replacement_lines ["DELTA"]})])]
    (is (= "alpha\nBETA\ngamma\nDELTA\n" (contents file))
        "both edits landed")
    (testing "the earlier call says it was merged, and renders nothing"
      (is (str/includes? (:content first-out) "ONE commit"))
      (is (not (str/includes? (:content first-out) "Edited")))
      (is (not (str/includes? (:content first-out) "│"))
          "no rows from the member that did not apply the batch"))
    (testing "the last call carries the merged diff"
      (is (str/includes? (:content last-out) "Edited"))
      (is (= #{"BETA" "DELTA"}
             (into #{} (comp (filter (fn [[p _ _]] (= "+" p))) (map (fn [[_ _ l]] l)))
                   (diff-rows (:content last-out))))
          "both changes appear in the one diff"))))

(deftest the-merged-anchors-are-immediately-usable
  ;; The batch has to leave the model able to keep editing, which means the rows it
  ;; printed are current and registered as shown -- for BOTH changes, not just the
  ;; last one's neighbourhood.
  (use-mode!)
  (spit file "alpha\nbeta\ngamma\ndelta\n" :encoding "UTF-8")
  (let [[_ b _ d] (read!)
        [_ last-out] (message! [(call "c1" "replace" {:remove_from b
                                                      :replacement_lines ["BETA"]})
                                (call "c2" "replace" {:remove_from d
                                                      :replacement_lines ["DELTA"]})])
        by-line (into {} (map (fn [[_ a l]] [l a])) (diff-rows (:content last-out)))]
    (is (some? (get by-line "BETA")))
    (is (some? (get by-line "DELTA")))
    (testing "and each printed anchor addresses the line it was printed beside"
      (doseq [[line text] {"BETA" "BETA2" "DELTA" "DELTA2"}]
        (let [out (run-one (call (str "x" line) "replace"
                                 {:remove_from (get by-line line)
                                  :replacement_lines [text]}))]
          (is (false? (:error out)) (:content out))))
      (is (= "alpha\nBETA2\ngamma\nDELTA2\n" (contents file))))))

(deftest one-undo-takes-back-the-whole-batch
  (use-mode!)
  (spit file "alpha\nbeta\ngamma\ndelta\n" :encoding "UTF-8")
  (let [[_ b _ d] (read!)]
    (message! [(call "c1" "replace" {:remove_from b :replacement_lines ["BETA"]})
               (call "c2" "replace" {:remove_from d :replacement_lines ["DELTA"]})])
    (is (= "alpha\nBETA\ngamma\nDELTA\n" (contents file)))
    (let [out (run-one (call "u" "undo_last_replace" {:path "f.txt"}))]
      (is (false? (:error out)) (:content out))
      (is (= "alpha\nbeta\ngamma\ndelta\n" (contents file))
          "both edits came back, not just the last"))))

(deftest a-no-op-batch-writes-nothing-and-keeps-the-undo-history
  ;; Both edits put back exactly what is already there, so the batch has no effect
  ;; -- and the undo record from the edit BEFORE it must survive, because a no-op
  ;; must not cost the model its ability to take back what it did earlier.
  (use-mode!)
  (spit file "alpha\nbeta\ngamma\n" :encoding "UTF-8")
  (let [[_ b _] (read!)]
    (run-one (call "e" "replace" {:remove_from b :replacement_lines ["BETA"]}))
    (let [[_ b2 _] (read!)
          [c1-out _] (message! [(call "c1" "replace" {:remove_from b2
                                                      :replacement_lines ["beta"]})
                                (call "c2" "replace" {:remove_from b2
                                                      :replacement_lines ["beta"]})])]
      (is (str/includes? (:content c1-out) "ONE commit") (:content c1-out))
      (is (= "alpha\nBETA\ngamma\n" (contents file))
          "the batch changed nothing at all")
      (testing "the undo record from the earlier edit is still there"
        (is (= "alpha\nbeta\ngamma\n" (:prior-text (store/undo-for (path file)))))))))

;; ------------------------------------------------------------ the refusals

(deftest overlapping-edits-refuse-the-whole-batch
  ;; Two edits to the same line, arriving in one message, are a mistake the model
  ;; can fix -- and nothing in the payload says which was meant, so picking a winner
  ;; would be inventing an answer. The refusal names both edits and the lines.
  (use-mode!)
  (spit file "alpha\nbeta\ngamma\ndelta\n" :encoding "UTF-8")
  (let [[_ b c _] (read!)
        [o1 o2] (message! [(call "c1" "replace" {:remove_from b :remove_to c
                                                 :replacement_lines ["X"]})
                           (call "c2" "replace" {:remove_from c
                                                 :replacement_lines ["Y"]})])]
    (testing "the applier refuses, and says which lines both edits claim"
      (is (true? (:error o2)) (:content o2))
      (is (str/includes? (:content o2) "both claim lines"))
      (is (str/includes? (:content o2) "NOTHING was written")))
    (testing "and the member reports the same outcome rather than a success"
      (is (or (true? (:error o1))
              (str/includes? (:content o1) "ONE commit"))))
    (is (= "alpha\nbeta\ngamma\ndelta\n" (contents file))
        "not one byte was written")))

(deftest one-bad-edit-refuses-the-batch-and-says-which
  ;; The whole commit or none of it -- and the model has to know WHICH of its calls
  ;; to rewrite, or it has to guess.
  ;;
  ;; The bad edit is a MALFORMED PAYLOAD rather than an unknown anchor, and that is
  ;; deliberate: a call whose target cannot be derived at all never joins a batch
  ;; (see `a-call-that-cannot-be-targeted-is-not-in-the-batch`), so it could not
  ;; exercise this at all. Here the call addresses the right file and is refused on
  ;; its own terms -- which is the case a batch has to survive.
  (use-mode!)
  (spit file "alpha\nbeta\ngamma\n" :encoding "UTF-8")
  (let [[_ b _] (read!)
        outs (message! [(call "c1" "replace" {:remove_from b
                                              :replacement_lines ["BETA"]})
                        (call "c2" "replace" {:remove_from b
                                              :replacement_lines "no array"})])
        last-out (last outs)]
    (is (true? (:error last-out)) (:content last-out))
    (is (str/includes? (:content last-out) "edit 2 of 2") "which call")
    (is (str/includes? (:content last-out) "array of strings") "and why")
    (is (= "alpha\nbeta\ngamma\n" (contents file))
        "the legal edit was not written on its own")))

(deftest a-batch-whose-edit-was-never-shown-refuses-the-batch
  (use-mode!)
  (spit file (str (str/join "\n" (map #(str "line" %) (range 1 31))) "\n")
        :encoding "UTF-8")
  (let [out (:content (run-one (call "r" "read" {:path "f.txt" :limit 5})))
        _   (is (str/includes? out "offset=6"))
        st  (store/state tid (path file))
        unshown (first (remove (:served st) (:anchors st)))
        live    (first (:served st))
        outs (message! [(call "c1" "replace" {:remove_from live
                                              :replacement_lines ["LINE1"]})
                        (call "c2" "replace" {:remove_from unshown
                                              :replacement_lines ["X"]})])]
    (is (true? (:error (last outs))))
    (is (str/includes? (:content (last outs)) "never shown"))
    (is (str/starts-with? (contents file) "line1\n") "nothing was written")))

;; ------------------------------------------------------------- the scope

(deftest different-files-are-different-commits
  (use-mode!)
  (spit file "alpha\nbeta\n" :encoding "UTF-8")
  (spit other "one\ntwo\n" :encoding "UTF-8")
  (let [[_ b] (read!)
        [_ d] (read! "g.txt")]
    (message! [(call "c1" "replace" {:remove_from b :replacement_lines ["BETA"]})
               (call "c2" "replace" {:path "g.txt" :remove_from d
                                     :replacement_lines ["TWO"]})])
    (is (= "alpha\nBETA\n" (contents file)))
    (is (= "one\nTWO\n" (contents other)))
    (testing "and each file has its own undo"
      (run-one (call "u1" "undo_last_replace" {:path "f.txt"}))
      (is (= "alpha\nbeta\n" (contents file)))
      (is (= "one\nTWO\n" (contents other)) "the other file's edit is untouched")
      (run-one (call "u2" "undo_last_replace" {:path "g.txt"}))
      (is (= "one\ntwo\n" (contents other))))))

(deftest a-call-that-cannot-be-targeted-is-not-in-the-batch
  ;; Best effort on purpose: a call whose target cannot be worked out runs on its
  ;; own and reports its own error, rather than dragging down edits that are fine.
  (use-mode!)
  (spit file "alpha\nbeta\ngamma\n" :encoding "UTF-8")
  (let [[_ b _] (read!)
        outs (message! [(call "c1" "replace" {:remove_from b
                                              :replacement_lines ["BETA"]})
                        (call "c2" "replace" {:remove_from "Hasu"
                                              :replacement_lines ["X"]})
                        (call "c3" "replace" {:remove_from b
                                              :replacement_lines ["BETA2"]})])]
    ;; c1 and c3 target the same file and overlap; c2 targets nothing.
    (testing "the untargetable call is refused on its own terms"
      (is (str/includes? (:content (second outs)) "not an anchor")
          (:content (second outs))))
    (testing "and the two that could be targeted were refused together"
      (is (true? (:error (last outs))))
      (is (str/includes? (:content (last outs)) "both claim lines")))
    (is (= "alpha\nbeta\ngamma\n" (contents file)) "and nothing was written")))

;; ------------------------------------------------------------ concurrency

(deftest concurrent-edits-to-one-file-do-not-lose-each-other
  ;; THE REASON THIS TICKET EXISTS. The two calls are started at the same moment,
  ;; as the run loop starts them. Before batching, each validated against the state
  ;; the session was shown, each computed its own new content, the later write won
  ;; and the earlier one vanished -- with both reporting success.
  (use-mode!)
  (spit file (str (str/join "\n" (map #(str "line" %) (range 1 21))) "\n")
        :encoding "UTF-8")
  (let [as (read!)
        outs (message! [(call "c1" "replace" {:remove_from (nth as 1)
                                              :replacement_lines ["LINE2"]})
                        (call "c2" "replace" {:remove_from (nth as 9)
                                              :replacement_lines ["LINE10"]})
                        (call "c3" "replace" {:remove_from (nth as 19)
                                              :replacement_lines ["LINE20"]})])
        lines (str/split-lines (contents file))]
    (is (= ["LINE2" "LINE10" "LINE20"] [(nth lines 1) (nth lines 9) (nth lines 19)])
        "every edit landed, and nothing overwrote anything")
    (is (= 20 (count lines)) "and no line was lost or duplicated")
    (testing "the store agrees with the file: the anchors are current"
      (let [st (store/state tid (path file))]
        (is (= 20 (count (:anchors st))))
        (is (= (anchors/line-checksums (contents file)) (:line-checksums st))
            "the stored checksums are the file's"))
      (is (= (:anchors (store/state tid (path file))) (read!))
          "and a fresh read hands out exactly what the store holds"))
    (testing "and one undo takes back all three"
      (is (false? (:error (run-one (call "u" "undo_last_replace" {:path "f.txt"})))))
      (is (= (str (str/join "\n" (map #(str "line" %) (range 1 21))) "\n")
             (contents file))))
    (testing "at most one of them claims to have written"
      (is (= 1 (count (filter #(str/includes? (:content %) "Edited") outs))))
      (is (every? #(not (true? (:error %))) outs)
          "the members did not fail; they were merged"))))

;; ---------------------------------------------------------- the tool's face

(deftest the-description-promises-batching-and-forbids-overlap
  ;; The model has to know both facts to use this well: that several edits become
  ;; one commit and one undo, and that their ranges must not overlap.
  (use-mode!)
  (let [spec (first (filter #(= "replace" (get-in % [:function :name]))
                            (tools/specs tid)))
        desc (:description (:function spec))]
    (is (str/includes? desc "ONE commit") (str desc))
    (is (str/includes? desc "must not overlap") (str desc))
    (is (str/includes? desc "ONE undo") (str desc))))
