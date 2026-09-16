(ns harness.cap.hashline.write-test
  "`write` under anchor mode: the boundary where a file's anchors stop meaning
  anything, the refusal of an echo, and the rows that let the model edit what it
  just wrote without a read."
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
  (str (io/file (System/getProperty "java.io.tmpdir") "harness-hashline-write-test")))

(io/delete-file root true)
(.mkdirs (io/file root "sub"))

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

(def ^:private tid "wt")

(defn- use-mode!
  ([] (use-mode! :hashline {}))
  ([mode extra]
   (.mkdirs (.getParentFile project-file))
   (spit project-file (str "{:editing " (pr-str (merge {:mode mode} extra)) "}")
         :encoding "UTF-8")
   (project/bind! tid root)))

(defn- call [name args]
  (tools/run! {:function {:name name :arguments (json/write-str args)}} tid))

(defn- content-of [out] (:content out))

(defn- rows
  "The `anchor│content` rows of an answer, as [anchor content]."
  [out]
  (keep (fn [line]
          (let [i (str/index-of line "│")]
            (when (and i (>= i 4)) [(subs line (- i 4) i) (subs line (inc i))])))
        (str/split-lines out)))

(defn- read!
  "The anchors of f.txt in line order."
  []
  (let [out (:content (call "read" {:path "f.txt"}))]
    (mapv (fn [r] (subs r 0 (str/index-of r "│"))) (str/split-lines out))))

(defn- write! [content] (call "write" {:path "f.txt" :content content}))

;; ------------------------------------------------------ the write boundary

(deftest a-write-releases-the-anchors-it-invalidated
  ;; The file's content is no longer what those anchors were minted against -- not
  ;; approximately: the anchors addressed LINES, and these are other lines. So the
  ;; anchors go.
  (use-mode! :hashline {:auto-read false})
  (spit file "alpha\nbeta\ngamma\n" :encoding "UTF-8")
  (let [[_ b _] (read!)]
    (is (false? (:error (write! "completely\ndifferent\n"))))
    (testing "the anchors are gone from this session"
      (is (not (contains? (store/ownership tid) b)))
      (is (nil? (store/state tid (path))) "and so is the stored view"))
    (testing "and editing one of them says to read the file"
      (let [{:keys [content error]} (call "replace" {:remove_from b
                                                     :path "f.txt"
                                                     :replacement_lines ["X"]})]
        (is (true? error))
        (is (str/includes? content "Call read") (str content))
        (is (str/includes? content (path)) "and names the file to read")
        (is (= "completely\ndifferent\n" (slurp file :encoding "UTF-8"))
            "nothing was edited")))))

(deftest the-auto-read-replaces-the-anchors-the-write-took-away
  ;; With `:auto-read` on -- the default -- the file does not end up anchorless:
  ;; the rows in the answer mint a fresh set, so the model can go straight on
  ;; editing. The OLD anchors are still gone, which is the invariant.
  (use-mode!)
  (spit file "alpha\nbeta\n" :encoding "UTF-8")
  (let [[a _] (read!)]
    (write! "fresh\ncontent\n")
    (let [now (:anchors (store/state tid (path)))]
      (is (seq now) "the auto-read left a view behind")
      (is (not (contains? (set now) a)) "and it is not the set the write invalidated")
      (is (some? (store/state tid (path)))))))

(deftest a-write-clears-the-files-undo-history
  ;; The edit that came before this write is not something to offer to take back:
  ;; the text it would restore is not what the file held a moment ago.
  (use-mode!)
  (spit file "one\ntwo\nthree\n" :encoding "UTF-8")
  (let [[_ b _] (read!)]
    (call "replace" {:remove_from b :replacement_lines ["TWO"]})
    (is (some? (store/undo-for (path))) "an edit happened, so there was something to undo")
    (write! "fresh\n")
    (is (nil? (store/undo-for (path))) "and the write took it away")))

(deftest a-write-to-a-file-nobody-has-read-is-ordinary
  ;; The everyday case: no anchors to release, nothing to clear, no error.
  (use-mode!)
  (let [out (write! "brand new\nfile\n")]
    (is (false? (:error out)) (content-of out))
    (is (= "brand new\nfile\n" (slurp file :encoding "UTF-8")))
    (testing "and a read afterwards hands out anchors as usual"
      (is (every? anchors/anchor? (read!))))))

;; ----------------------------------------------------------- the echo guard

(deftest an-anchor-copied-back-into-the-content-is-refused
  ;; Copying content out of read output carries the `anchor│` prefix with it. Left
  ;; in, the file is polluted -- and worse, every later read hands the `Hasu│` back
  ;; out as if it were content, so the mistake reproduces itself.
  (use-mode!)
  (spit file "alpha\nbeta\n" :encoding "UTF-8")
  (let [[a b] (read!)
        out (write! (str a "│alpha\n" b "│beta\n"))]
    (is (true? (:error out)))
    (is (str/includes? (content-of out) "line 1") "which line")
    (is (str/includes? (content-of out) a) "which anchor")
    (is (= "alpha\nbeta\n" (slurp file :encoding "UTF-8")) "nothing was written")))

(deftest the-guard-does-not-refuse-content
  ;; The refusal has to be narrow, because refusing CONTENT is a much worse error
  ;; than accepting an echo. Four things that are not echoes -- and the last one is
  ;; the control, so a guard that simply never fired could not pass this.
  ;;
  ;; Each case reads its anchors FRESH, because a successful write releases them
  ;; and the auto-read mints new ones: reusing a name from before a write in a case
  ;; about something else is how a test ends up asserting that a stale anchor is
  ;; "not an echo", which would be true for the wrong reason.
  (use-mode!)
  (testing "a bare separator is text"
    (spit file "alpha\n" :encoding "UTF-8")
    (read!)
    (is (false? (:error (write! "│ leading separator\nsecond\n")))))
  (testing "four letters this file was never served are text"
    (spit file "alpha\n" :encoding "UTF-8")
    (read!)
    (let [other (first (map anchors/anchor-at (range 200000 200010)))]
      (is (false? (:error (write! (str other "│not my anchor\nsecond\n")))))))
  (testing "and a served anchor that is not at the head of a line is text"
    (spit file "alpha\n" :encoding "UTF-8")
    (let [[a] (read!)]
      (is (false? (:error (write! (str "prefix " a "│x\nsecond\n")))))))
  (testing "but the same anchor at the head of a line is refused"
    (spit file "alpha\n" :encoding "UTF-8")
    (let [[a] (read!)]
      (is (true? (:error (write! (str a "│alpha\n"))))))))

(deftest a-refused-write-changes-nothing-at-all
  (use-mode!)
  (spit file "alpha\nbeta\n" :encoding "UTF-8")
  (let [[a b] (read!)]
    (call "replace" {:remove_from a :replacement_lines ["ALPHA"]})
    (let [before-content (slurp file :encoding "UTF-8")
          before-owners  (store/ownership tid)
          before-undo    (store/undo-for (path))]
      (is (true? (:error (write! (str b "│beta\n")))))
      (is (= before-content (slurp file :encoding "UTF-8")) "the file is untouched")
      (is (= before-owners (store/ownership tid)) "the anchors are still this session's")
      (is (= before-undo (store/undo-for (path))) "and the undo record survives"))))

;; ------------------------------------------------------------- the auto-read

(deftest a-write-hands-back-rows-that-are-immediately-usable
  ;; THE POINT OF THE TICKET. Just written a file, the next move is usually to
  ;; adjust something in it -- and the write released the anchors, so without this
  ;; the model pays a read for a fact this call can supply.
  (use-mode!)
  (let [out (write! "one\ntwo\nthree\n")
        [anchor line] (first (filter (fn [[_ l]] (= "two" l)) (rows (content-of out))))]
    (is (some? anchor) (str "no row for line two in:\n" (content-of out)))
    (is (anchors/anchor? anchor))
    (testing "and a replace addressed at that anchor goes through, with no read"
      (let [again (call "replace" {:remove_from anchor :replacement_lines ["TWO"]})]
        (is (false? (:error again)) (content-of again))
        (is (= "one\nTWO\nthree\n" (slurp file :encoding "UTF-8")))))))

(deftest the-auto-read-does-not-flood-the-answer
  ;; It is a head, not the file: enough to cover where a first edit lands, with the
  ;; offset that continues named for the rest.
  (use-mode!)
  (let [out (content-of (write! (str/join "\n" (map #(str "line" %) (range 1 60)))))]
    (is (< (count (str/split-lines out)) 40) "not sixty rows")
    (is (str/includes? out "offset=") "and the rest is one read away")))

(deftest auto-read-off-says-how-to-get-anchors
  ;; A session that does not want the extra read-back gets one sentence instead of
  ;; rows -- and it says what to do, because 'no anchors here' is only useful with
  ;; the next step.
  (use-mode! :hashline {:auto-read false})
  (let [out (content-of (write! "one\ntwo\n"))]
    (is (not (str/includes? out "│")) "no rows came back")
    (is (str/includes? out "read") "the way to get anchors is named")
    (testing "and the write itself still happened"
      (is (= "one\ntwo\n" (slurp file :encoding "UTF-8"))))))

(deftest a-write-that-cannot-be-read-back-still-succeeded
  ;; A note that could not be produced is not a failure of the thing it is a note
  ;; about. The write is done; what went wrong is said so the model knows why it
  ;; has no anchors.
  (use-mode!)
  (let [out (content-of (write! "text\u0000binary\n"))]
    (is (str/includes? out "wrote") "the write is reported as done")
    (is (not (str/includes? out "Error")) (str out))
    (is (str/includes? out "could not be produced") "and the missing note is explained")
    (testing "the file really is what was written"
      (is (str/includes? (slurp file :encoding "UTF-8") "text\u0000binary")))))

;; ------------------------------------------------- one file, one ledger

(deftest both-spellings-of-one-path-share-one-set-of-anchors
  ;; The store books anchors BY PATH, so `sub/../f.txt` and `f.txt` have to collapse
  ;; to one key. Otherwise the model is handed anchors that "stop working" when it
  ;; addresses the same file the other way -- a phantom, and one that would look to
  ;; the model like the anchor scheme lying to it.
  (use-mode!)
  (spit file "one\ntwo\n" :encoding "UTF-8")
  (let [direct (:content (call "read" {:path "f.txt"}))
        round  (:content (call "read" {:path "sub/../f.txt"}))]
    (is (= direct round) "the same file, read two ways, is one set of rows")
    (testing "and a write through the other spelling releases them"
      (let [[a _] (read!)]
        (call "write" {:path "sub/../f.txt" :content "changed\n"})
        (is (not (contains? (store/ownership tid) a)))
        (is (true? (:error (call "replace" {:remove_from a
                                            :replacement_lines ["X"]}))))))))

;; ------------------------------------------------ the inherited behaviour

(deftest the-fence-and-the-re-root-are-untouched-by-the-mode
  (use-mode!)
  (testing "a relative path still resolves against the project"
    (is (false? (:error (write! "re-rooted\n"))))
    (is (= "re-rooted\n" (slurp file :encoding "UTF-8"))))
  (testing "and an out-of-bounds path still parks for a human"
    (let [res (call "write" {:path "/etc/hostname" :content "nope\n"})]
      (is (some? (:parked res)) "the call is waiting for a human")
      (is (= :out-of-bounds (:reason (:parked res)))))))

(deftest str-replace-mode-writes-as-before
  ;; The mode's own behaviour is opt-in: a session that edits by old_string must not
  ;; acquire anchor bookkeeping it never asked for.
  (use-mode! :str-replace {})
  (spit file "alpha\nbeta\n" :encoding "UTF-8")
  (let [out (content-of (write! "rewritten\n"))]
    (is (str/includes? out "wrote"))
    (is (not (str/includes? out "│")) "no anchor rows in the answer")
    (is (= "rewritten\n" (slurp file :encoding "UTF-8")))
    (testing "and the echo guard is not imposed on a mode with no anchors"
      (is (false? (:error (write! "Hasu│not an anchor here\n")))))))

(deftest the-write-description-follows-the-mode
  ;; What the model reads has to match what the call does -- in both directions.
  (let [desc-for (fn [thread-id]
                   (:description (:function (first (filter
                                                    #(= "write" (get-in % [:function :name]))
                                                    (tools/specs thread-id))))))]
    (use-mode! :hashline {})
    (is (str/includes? (desc-for tid) "RELEASES"))
    (use-mode! :str-replace {})
    (is (not (str/includes? (desc-for tid) "RELEASES"))
        "a session with no anchors is not told about them")))
