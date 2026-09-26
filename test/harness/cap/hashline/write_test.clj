(ns harness.cap.hashline.write-test
  "`write` under anchor mode: the boundary where a file's anchors stop meaning
  anything, the refusal of an echo, and the answer that states what happened
  instead of handing a head of the file back."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [harness.infra.db :as db]
            [harness.cap.hashline.anchors :as anchors]
            [harness.cap.hashline.store :as store]
            [harness.cap.hashline.write :as hashline-write]
            [harness.cap.hashline.undo :as hashline-undo]
            [harness.infra.home :as home]
            [harness.cap.project :as project]
            [harness.kernel.tools :as tools]
            [harness.test-support :as support]))

(use-fixtures :once support/with-builtins)

(def ^:private root
  (support/temp-dir "hashline-write"))

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
  (use-mode!)
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
  ;; and a later read mints a new set: reusing a name from before a write in a case
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

;; ----------------------------------------------------- the answer is a receipt

(deftest the-answer-states-the-two-facts-and-names-read
  ;; WRITING IS NOT READING. What was written is in the call that wrote it, and the
  ;; anchors are gone because the write released them -- so the answer says exactly
  ;; that, and names the one action that brings anchors back. Nothing that was
  ;; written is handed back: a model that wants to edit a line has to be shown it.
  (use-mode!)
  (let [out (content-of (write! "one\ntwo\n"))]
    (is (str/includes? out "wrote") "how much was written")
    (is (str/includes? out (path)) "where it went")
    (is (not (str/includes? out "│")) "no rows came back")
    (is (str/includes? out "read") "and the way to get anchors is named")
    (testing "the write itself still happened"
      (is (= "one\ntwo\n" (slurp file :encoding "UTF-8"))))))

(deftest no-answer-grows-with-the-file
  ;; It is not a head, a tail or a sample: the same two facts come back for a file
  ;; of three lines and a file of sixty. (The auto-read this replaced showed the
  ;; first twenty.)
  (use-mode!)
  (let [out (content-of (write! (str/join "\n" (map #(str "line" %) (range 1 60)))))]
    (is (< (count (str/split-lines out)) 5) "not sixty rows, and not twenty")
    (is (not (str/includes? out "offset=")) "and nothing points at a continuation")))

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
    (let [res (call "write" {:path (support/outside-path "hostname") :content "nope\n"})]
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
;; --------------------------------------------------------- the lock order
;;
;; LOCK ORDER IS PART OF THE DATA. `read` takes the session lock first and the file's
;; underneath it; `write` and `undo_last_replace` USED to take the file's first and
;; reach the session lock underneath (through the read that minted the anchors the
;; answer handed back). Two tool calls in one message run on two threads, so one of
;; them plus a `read` of the same file was all it took -- and then neither finishes:
;; no result, no run end, a spinner forever.
;;
;; WRITE NO LONGER TAKES THE SESSION LOCK AT ALL: its answer hands nothing back, so
;; nothing inside it mints. `undo_last_replace` is the call whose order still matters,
;; and the write case below is the evidence for the other half -- a write lands WHILE
;; the session lock is held, because it never wanted it.
;;
;; THE CYCLE NEEDS A CALLER THAT HOLDS THE SESSION LOCK AND HAS NOT TAKEN THE FILE'S
;; YET, and nothing can be gated inside that instant. So the undo case holds the session
;; lock itself and freezes the call ONE STEP EARLIER -- at `store/canonical`, which
;; either order runs before it takes anything -- then asks what the call did with the
;; file after that. The freeze is what takes the machine's speed out of the question:
;; what happens next is decided by the ORDER the two locks are taken in.

(defn- behind-the-locks
  "Run F -- write's or undo's `perform!` -- while the session lock is held by another
  thread, and answer what the FILE showed once F was let go past its freeze:

    :held?         the session lock really was held before F started
    :entered?      F reached the point immediately before its first lock
    :file-changed? the file's text was replaced while F waited

  :file-changed? IS THE WHOLE QUESTION, and it is the only one of the three that is
  decided by the lock order rather than by the clock: while another thread holds the
  session lock, a call that takes the session lock first cannot reach the file at all,
  and a call that takes the file's lock first has, by then, already written it.

  The session lock is released and both threads are waited for on the way out, so a
  failing case does not leave anyone parked on a lock for the rest of the suite."
  [f]
  (let [holding (promise)
        release (promise)
        holder  (future (store/with-session-lock
                         tid
                         (fn []
                           (deliver holding true)
                           (deref release 30000 false))))
        held?   (true? (deref holding 5000 false))
        gate    (support/window-gate #'store/canonical)
        before  (slurp file :encoding "UTF-8")
        call    (future (f))]
    (try
      (let [entered? (support/holds-within? #(= 1 ((:entered gate))) 5000)]
        ((:release gate))                       ; let it take its first lock
        {:held?         held?
         :entered?      entered?
         :file-changed? (support/holds-within?
                         #(not= before (slurp file :encoding "UTF-8")) 2000)})
      (finally
        ((:release gate))
        ((:restore gate))
        (deliver release true)
        (deref holder 10000 ::stuck)
        (deref call 10000 ::stuck)))))

(deftest a-write-does-not-wait-for-the-session-lock
  ;; THE DEADLOCK FACE SHRANK, and this is the evidence. `write` used to take the
  ;; session lock (through the read that minted the anchors it handed back); it takes
  ;; only the file's now, so a write lands even while another thread holds the session
  ;; lock. That edge of the cycle is gone rather than merely reordered.
  (use-mode!)
  (spit file "alpha\nbeta\n" :encoding "UTF-8")
  (let [holding (promise)
        release (promise)
        holder  (future (store/with-session-lock
                         tid
                         (fn []
                           (deliver holding true)
                           (deref release 30000 false))))
        held?   (true? (deref holding 5000 false))
        call    (future (hashline-write/perform! tid identity
                                                {:path (path) :content "fresh\n"}))]
    (try
      (is held? "the session lock really was held")
      (is (support/holds-within? #(= "fresh\n" (slurp file :encoding "UTF-8")) 5000)
          "the write waited for the session lock instead of not wanting it")
      (finally
        (deliver release true)
        (deref holder 10000 ::stuck)
        (deref call 10000 ::stuck)))))

(deftest an-undo-never-holds-the-file-while-it-waits-for-the-session
  (use-mode!)
  (spit file "one\ntwo\nthree\n" :encoding "UTF-8")
  (let [[_ b _] (read!)]
    (call "replace" {:remove_from b :replacement_lines ["TWO"]})
    (let [edited (slurp file :encoding "UTF-8")
          out    (behind-the-locks
                  #(hashline-undo/perform! tid identity
                                           {:path (path)}
                                           {:mode :hashline}))]
      (is (:held? out) "the session lock really was held")
      (is (:entered? out) "and the undo was frozen one step before its first lock")
      (is (false? (:file-changed? out))
          (str "the file was left alone while the session lock was held -- the same"
               " cycle as the write, and the undo is worse: it would have put the old"
               " text back before it waited"))
      (is (not= edited (slurp file :encoding "UTF-8"))
          "the undo lands once the session lock is released"))))
