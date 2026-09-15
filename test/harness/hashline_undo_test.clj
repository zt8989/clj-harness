(ns harness.hashline-undo-test
  "`undo_last_replace`: taking an edit back -- the text, the encoding, the
  permission bits and the ANCHORS -- and, more importantly, the case where it
  refuses to."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [harness.db :as db]
            [harness.hashline.anchors :as anchors]
            [harness.hashline.store :as store]
            [harness.home :as home]
            [harness.project :as project]
            [harness.tools :as tools])
  (:import [java.nio.file Files]
           [java.nio.file.attribute PosixFilePermission]))

(def ^:private root
  (str (io/file (System/getProperty "java.io.tmpdir") "harness-hashline-undo-test")))

(io/delete-file root true)
(.mkdirs (io/file root))

(def ^:private user-file (io/file (home/root) "harness.edn"))
(def ^:private project-file (io/file root ".harness" "harness.edn"))
(def ^:private file (io/file root "f.txt"))
(def ^:private other (io/file root "g.txt"))

(defn- path
  "A file under the scratch project, spelled the way the store books it."
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

(use-fixtures :each wipe clean-tables)

(def ^:private tid "ut")

(defn- use-mode! []
  (.mkdirs (.getParentFile project-file))
  (spit project-file "{:editing {:mode :hashline}}" :encoding "UTF-8")
  (project/bind! tid root))

(defn- call [name args]
  (tools/run! {:function {:name name :arguments (json/write-str args)}} tid))

(defn- content-of [out] (:content out))

(defn- read!
  ([] (read! "f.txt"))
  ([name]
   (let [out (:content (call "read" {:path name}))]
     (mapv (fn [r] (subs r 0 (str/index-of r "│"))) (str/split-lines out)))))

(defn- undo!
  ([] (undo! "f.txt"))
  ([name] (call "undo_last_replace" {:path name})))

(defn- modes [^java.io.File f]
  (Files/getPosixFilePermissions (.toPath f) (make-array java.nio.file.LinkOption 0)))

;; ------------------------------------------------------------ the round trip

(deftest an-edit-is-taken-back-byte-for-byte
  (use-mode!)
  (spit file "one\ntwo\nthree\n" :encoding "UTF-8")
  (let [[_ b _] (read!)]
    (call "replace" {:remove_from b :replacement_lines ["TWO"]})
    (is (= "one\nTWO\nthree\n" (slurp file :encoding "UTF-8")))
    (let [out (undo!)]
      (is (false? (:error out)) (content-of out))
      (is (str/includes? (content-of out) "Undid"))
      (is (= "one\ntwo\nthree\n" (slurp file :encoding "UTF-8"))
          "the bytes are back"))))

(deftest the-encoding-comes-back-too
  ;; "Byte-for-byte" is the claim, and a BOM plus CRLF is where a rewrite usually
  ;; loses it: the restored text has to be written back as the file it was.
  (use-mode!)
  (spit file "\uFEFFone\r\ntwo\r\n" :encoding "UTF-8")
  (let [[_ b] (read!)]
    (call "replace" {:remove_from b :replacement_lines ["TWO"]})
    (is (str/includes? (slurp file :encoding "UTF-8") "TWO"))
    (undo!)
    (let [raw (slurp file :encoding "UTF-8")]
      (is (= \uFEFF (.charAt raw 0)) "the BOM is back")
      (is (str/includes? raw "one\r\ntwo\r\n") "and so are the CRLFs"))))

(deftest the-permission-bits-come-back
  (use-mode!)
  (spit file "one\ntwo\n" :encoding "UTF-8")
  (Files/setPosixFilePermissions (.toPath file)
                                 (java.util.EnumSet/of PosixFilePermission/OWNER_READ
                                                       PosixFilePermission/OWNER_WRITE
                                                       PosixFilePermission/OWNER_EXECUTE))
  (let [before (modes file)
        [_ b]  (read!)]
    (call "replace" {:remove_from b :replacement_lines ["TWO"]})
    ;; the write preserves them, so change them again to prove the UNDO puts back
    ;; what the EDIT captured rather than what the file happens to have now
    (Files/setPosixFilePermissions (.toPath file)
                                   (java.util.EnumSet/of PosixFilePermission/OWNER_READ
                                                         PosixFilePermission/OWNER_WRITE))
    (undo!)
    (is (= before (modes file)) "the edit's own permission bits, restored")))

(deftest the-anchors-come-back-and-are-usable
  ;; THE POINT OF THE TICKET. A model undoes an edit in order to make a different
  ;; one, so the anchors it held before have to work again -- which means the view,
  ;; the anchors' ownership AND the shown set, not just the text.
  (use-mode!)
  (spit file "alpha\nbeta\ngamma\n" :encoding "UTF-8")
  (let [[a b c] (read!)]
    (call "replace" {:remove_from b :replacement_lines ["BETA"]})
    (let [out (undo!)]
      (is (false? (:error out)) (content-of out))
      (testing "the anchors from before the edit are addressable again"
        (let [again (call "replace" {:remove_from b :replacement_lines ["BETA2"]})]
          (is (false? (:error again)) (content-of again))
          (is (= "alpha\nBETA2\ngamma\n" (slurp file :encoding "UTF-8")))))
      (testing "and the ones the undo's own rows showed are current too"
        ;; The answer is a read of the restored file, so it re-registered the
        ;; anchors -- including ones the undo newly minted if any.
        (let [restored (str/split-lines (content-of out))
              anchor   (fn [line] (let [row (first (filter #(str/ends-with? % (str "│" line))
                                                           restored))]
                                    (subs row 0 4)))
              x (call "replace" {:remove_from (anchor "gamma")
                                 :replacement_lines ["GAMMA2"]})]
          (is (false? (:error x)) (content-of x))
          (is (= "alpha\nBETA2\nGAMMA2\n" (slurp file :encoding "UTF-8"))))))))

(deftest a-second-undo-has-nothing-to-take-back
  ;; One edit, one undo. The distinction this preserves is between "there was
  ;; nothing to undo" and "undoing failed" -- a model that cannot tell those apart
  ;; will retry the wrong one.
  (use-mode!)
  (spit file "one\ntwo\n" :encoding "UTF-8")
  (let [[_ b] (read!)]
    (call "replace" {:remove_from b :replacement_lines ["TWO"]})
    (is (false? (:error (undo!))))
    (let [out (undo!)]
      (is (true? (:error out)))
      (is (str/includes? (content-of out) "no edit to undo"))
      (is (= "one\ntwo\n" (slurp file :encoding "UTF-8")) "and the file is left alone"))))

(deftest an-untouched-file-has-nothing-to-undo
  (use-mode!)
  (spit file "one\ntwo\n" :encoding "UTF-8")
  (read!)
  (let [out (undo!)]
    (is (true? (:error out)))
    (is (str/includes? (content-of out) "no edit to undo"))))

(deftest a-write-clears-the-history
  ;; A write is the anchor boundary (07) and it is the undo boundary too: the text
  ;; an undo would restore is not what the file held a moment ago.
  (use-mode!)
  (spit file "one\ntwo\n" :encoding "UTF-8")
  (let [[_ b] (read!)]
    (call "replace" {:remove_from b :replacement_lines ["TWO"]})
    (call "write" {:path "f.txt" :content "something else\n"})
    (let [out (undo!)]
      (is (true? (:error out)))
      (is (str/includes? (content-of out) "no edit to undo"))
      (is (= "something else\n" (slurp file :encoding "UTF-8"))))))

;; ------------------------------------------------------------- the refusals

(deftest an-edit-somebody-else-undid-is-not-rolled-back-over
  ;; THE refusal that matters. If the file is not what the edit left, rolling back
  ;; would silently discard whatever changed it since -- an editor save, another
  ;; tool, a bash command. So nothing happens, and the record STAYS: the model may
  ;; still want it once it has looked at the file.
  (use-mode!)
  (spit file "one\ntwo\n" :encoding "UTF-8")
  (let [[_ b] (read!)]
    (call "replace" {:remove_from b :replacement_lines ["TWO"]})
    (spit file "one\nSOMEBODY-ELSE\n" :encoding "UTF-8")
    (let [out (undo!)]
      (is (true? (:error out)))
      (is (str/includes? (content-of out) "no longer what the last edit left behind"))
      (is (str/includes? (content-of out) "read") "and says what to do instead")
      (is (= "one\nSOMEBODY-ELSE\n" (slurp file :encoding "UTF-8"))
          "the later change was NOT eaten")
      (testing "and the record is still there for a later attempt"
        (is (some? (store/undo-for (path file))))))))

(deftest a-deleted-file-is-restored-from-the-history
  ;; The record holds the text before the edit, so a file that is gone can be put
  ;; back. A file reappearing is worth a sentence, and the answer says where it
  ;; came from.
  (use-mode!)
  (spit file "one\ntwo\n" :encoding "UTF-8")
  (let [[_ b] (read!)]
    (call "replace" {:remove_from b :replacement_lines ["TWO"]})
    (io/delete-file file)
    (let [out (undo!)]
      (is (false? (:error out)) (content-of out))
      (is (str/includes? (content-of out) "deleted") "the answer says what happened")
      (is (str/includes? (content-of out) "undo history") "and where it came from")
      (is (= "one\ntwo\n" (slurp file :encoding "UTF-8"))))))

(deftest the-path-argument-is-required-and-named
  (use-mode!)
  (doseq [[what args] {"missing" {}
                       "blank"   {:path ""}}]
    (let [{:keys [content error]} (call "undo_last_replace" args)]
      (is (true? error) what)
      (is (str/includes? content "path") (str what " -> " content)))))

;; --------------------------------------------------------------- the scope

(deftest one-file-is-not-another
  (use-mode!)
  (spit file "one\ntwo\n" :encoding "UTF-8")
  (spit other "alpha\nbeta\n" :encoding "UTF-8")
  (let [[_ b] (read!)
        [_ d] (read! "g.txt")]
    (call "replace" {:remove_from b :replacement_lines ["TWO"]})
    (call "replace" {:path "g.txt" :remove_from d :replacement_lines ["BETA"]})
    (testing "undoing one file leaves the other file's edit alone"
      (is (false? (:error (undo!))))
      (is (= "one\ntwo\n" (slurp file :encoding "UTF-8")))
      (is (= "alpha\nBETA\n" (slurp other :encoding "UTF-8")))
      (is (some? (store/undo-for (path other))) "and its record is untouched"))
    (testing "and the other file can still be undone after"
      (is (false? (:error (undo! "g.txt"))))
      (is (= "alpha\nbeta\n" (slurp other :encoding "UTF-8"))))))

(deftest a-record-outlives-the-process
  ;; The point of putting the record in the shared store rather than in memory:
  ;; there is nothing to reload, because the answer was never in memory. This is
  ;; what that buys, exercised the only way a test can -- by asking the store
  ;; directly, with no session state involved.
  (use-mode!)
  (spit file "one\ntwo\n" :encoding "UTF-8")
  (let [[_ b] (read!)]
    (call "replace" {:remove_from b :replacement_lines ["TWO"]})
    (let [u (store/undo-for (path file))]
      (is (= "one\ntwo\n" (:prior-text u)))
      (is (= "one\nTWO\n" (:resulting-text u)))
      (is (seq (:anchors u)))
      (is (set? (:served u)) "the shown set rode along")
      (is (some? (:mode u)) "and the permission bits, as a number"))))

(deftest an-anchor-that-moved-elsewhere-refuses-the-undo
  ;; Can the record name an anchor another file holds now? Yes: the edit freed it,
  ;; a later edit to another file minted it, and now the record is asking for a name
  ;; that is in use. Restoring it would put two lines under one name, so this
  ;; refuses -- and says so rather than doing something clever.
  (use-mode!)
  (spit file "one\ntwo\n" :encoding "UTF-8")
  (let [[_ b] (read!)
        taken b]
    (call "replace" {:remove_from b :replacement_lines ["TWO"]})
    (testing "hand the freed anchor to another file, as a later mint could"
      (db/with-transaction
        (fn [c]
          (db/execute! c "INSERT INTO hashline_ownership (thread_id, anchor, path)
                          VALUES (?, ?, ?) ON CONFLICT(thread_id, anchor) DO UPDATE
                          SET path = excluded.path"
                       tid taken (path other)))))
    (let [out (undo!)]
      (is (true? (:error out)) (content-of out))
      (is (str/includes? (content-of out) "in use elsewhere"))
      (is (= "one\nTWO\n" (slurp file :encoding "UTF-8")) "nothing was written"))))

;; ------------------------------------------------ the inherited behaviour

(deftest the-fence-and-the-re-root-are-untouched
  (use-mode!)
  (spit file "one\ntwo\n" :encoding "UTF-8")
  (let [[_ b] (read!)]
    (call "replace" {:remove_from b :replacement_lines ["TWO"]}))
  (testing "a relative path resolves against the project"
    (is (false? (:error (undo!)))))
  (testing "and an out-of-bounds path parks for a human"
    (let [res (call "undo_last_replace" {:path "/etc/hostname"})]
      (is (some? (:parked res)))
      (is (= :out-of-bounds (:reason (:parked res)))))))

;; ------------------------------------------------------ the tool's face

(deftest the-description-says-what-undo-will-and-will-not-do
  ;; Three things a model has to know before it reaches for this: one edit and not
  ;; a stack, a write clears the history, and a file that moved is refused rather
  ;; than rolled back over. Getting any of them wrong makes the tool look broken
  ;; when it is working exactly as designed.
  (use-mode!)
  (let [spec (first (filter #(= "undo_last_replace" (get-in % [:function :name]))
                            (tools/specs tid)))
        desc (:description (:function spec))]
    (is (some? spec) "the tool is served in anchor mode")
    (is (str/includes? desc "LAST"))
    (is (str/includes? desc "not a stack"))
    (is (str/includes? desc "write"))
    (is (str/includes? desc "REFUSES"))
    (is (str/includes? desc "no read needed") "and that anchors come back with it")))
