(ns harness.cap.hashline.read-test
  "The anchored `read`: what a row looks like, what paging does, what gets
  refused by name, and the two properties the rest of the feature depends on --
  that two reads of an unchanged file hand back the SAME anchors, and that
  reading is when a session first learns them."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [harness.infra.db :as db]
            [harness.cap.hashline.anchors :as anchors]
            [harness.cap.hashline.reading :as reading]
            [harness.cap.hashline.store :as store]
            [harness.infra.home :as home]
            [harness.cap.project :as project]
            [harness.kernel.tools :as tools]
            [harness.test-support :as support]))

(use-fixtures :once support/with-builtins)

;; CANONICALIZED through harness.cap.hashline.store, because that is how the store
;; books a file. Two things make this necessary and they are the same thing twice:
;; java.io.tmpdir ends with a separator on macOS, so `(str tmpdir "/name")` produces
;; a DOUBLE slash; and on macOS the temp directory itself is reached through the
;; `/var` -> `/private/var` symlink, so even `(io/file tmpdir name)` is a different
;; string from what the store keys on. A test that compared either spelling would
;; get nil back and look like a missing row.
(def ^:private root
  (str (io/file (System/getProperty "java.io.tmpdir") "harness-hashline-read-test")))

(defn- path-of
  "A file in the scratch project, spelled the way the tools resolve it."
  [name]
  (store/canonical (str (io/file root name))))

;; One scratch project directory for the whole namespace, started clean at load
;; time so test ordering cannot break it (the tools-test precedent: a directory
;; that a bind! call has to exist is a fixture bug, not a test failure).
(io/delete-file root true)
(.mkdirs (io/file root))

(def ^:private user-file (io/file (home/root) "harness.edn"))
(def ^:private project-file (io/file root ".harness" "harness.edn"))

(defn- wipe [f]
  (io/delete-file user-file true)
  (io/delete-file project-file true)
  (f)
  (io/delete-file user-file true)
  (io/delete-file project-file true))


(defn- use-mode! [thread-id mode]
  (project/bind! thread-id root)
  (.mkdirs (.getParentFile project-file))
  (spit project-file (str "{:editing {:mode " mode "}}") :encoding "UTF-8"))

(defn- call [thread-id name args]
  (tools/run! {:function {:name name :arguments (json/write-str args)}} thread-id))

(defn- read-raw [thread-id args]
  (:content (call thread-id "read" args)))

(defn- put! [name content]
  (spit (path-of name) content :encoding "UTF-8")
  name)

(defn- rows
  "The output as [anchor line] pairs, footer excluded."
  [out]
  (->> (str/split-lines out)
       (remove #(str/starts-with? % "["))
       (mapv (fn [row]
               (let [i (str/index-of row "│")]
                 [(subs row 0 i) (subs row (inc i))])))))

(defn- anchor-of [out line]
  (some (fn [[a l]] (when (= l line) a)) (rows out)))

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
  `sync!` before it made one. A session with no lock at all is recorded as NOT held."
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

(defn- clean-tables []
  (when (.exists (home/db-file))
    (db/with-transaction
      (fn [c]
        (doseq [t ["hashline_snapshots" "hashline_ownership" "hashline_sessions" "hashline_undo"]]
          (db/execute! c (str "DELETE FROM " t)))))))

(use-fixtures :each wipe (fn [f] (clean-tables) (f) (clean-tables)))

;; ------------------------------------------------------------------ the rows

(deftest a-read-comes-back-as-anchor-rows
  (use-mode! "r1" ":hashline")
  (put! "three.txt" "alpha\nbeta\ngamma\n")
  (let [out (read-raw "r1" {:path "three.txt"})]
    (testing "one row per line, anchor then separator then the line"
      (is (= 3 (count (rows out))))
      (is (= ["alpha" "beta" "gamma"] (mapv second (rows out)))))
    (testing "and every anchor is four letters out of the pool"
      (doseq [[a _] (rows out)]
        (is (anchors/anchor? a) a)))
    (testing "no header and no line numbers -- the first thing in the output is a row"
      (is (not (str/includes? out "1│")))
      (is (str/starts-with? out (first (map (fn [[a l]] (str a "│" l)) (rows out)))))
      (is (not (str/ends-with? out "\n")) "and no trailing newline"))
    (testing "three identical-looking anchors are in fact three different ones"
      (is (= 3 (count (set (map first (rows out)))))))))

(deftest two-identical-lines-get-two-different-anchors
  (use-mode! "r1" ":hashline")
  (put! "same.txt" "same\nsame\nsame\n")
  (let [as (mapv first (rows (read-raw "r1" {:path "same.txt"})))]
    (is (= 3 (count as)))
    (is (apply distinct? as))))

(deftest the-separator-is-the-box-drawing-one
  ;; The anchor table was curated so that `anchor│` is three tokens. A plain pipe
  ;; looks almost the same and tokenizes differently, so this is worth pinning.
  (use-mode! "r1" ":hashline")
  (put! "sep.txt" "x\n")
  (let [out (read-raw "r1" {:path "sep.txt"})]
    (is (str/includes? out "│"))
    (is (not (str/includes? out "|")))
    (is (= 1 (count (str/split-lines out))))))

(deftest an-empty-file-still-has-something-to-address
  (use-mode! "r1" ":hashline")
  (put! "empty.txt" "")
  (let [out (read-raw "r1" {:path "empty.txt"})]
    (testing "one empty anchored row"
      (let [[[a line]] (rows out)]
        (is (anchors/anchor? a))
        (is (= "" line))))
    (testing "and a hint that says what to do with it"
      (is (str/includes? out "empty"))
      (is (str/includes? out "replace")))))

(deftest carriage-returns-are-not-shown
  (use-mode! "r1" ":hashline")
  (put! "crlf.txt" "a\r\nb\r\n")
  (let [out (read-raw "r1" {:path "crlf.txt"})]
    (is (not (str/includes? out "\r")))
    (is (= ["a" "b"] (mapv second (rows out))))))

;; ----------------------------------------------------------------- paging

(deftest offset-and-limit-page-through-a-file
  (use-mode! "r1" ":hashline")
  (put! "ten.txt" (str/join "\n" (map #(str "line" %) (range 1 11))))
  (testing "offset is 1-based and lands on the line asked for"
    ;; It starts there and runs on: offset sets the START, it is not a window of
    ;; one. `limit` is what ends a page early.
    (let [from-3 (mapv second (rows (read-raw "r1" {:path "ten.txt" :offset 3})))]
      (is (= "line3" (first from-3)))
      (is (= 8 (count from-3)) "lines 3..10")))

  (testing "offset and limit together are a window"
    (let [win (mapv second (rows (read-raw "r1" {:path "ten.txt" :offset 3 :limit 2})))]
      (is (= ["line3" "line4"] win))))
  (testing "limit stops early and says how to continue"
    (let [out (read-raw "r1" {:path "ten.txt" :limit 3})]
      (is (= ["line1" "line2" "line3"] (mapv second (rows out))))
      (is (str/includes? out "offset=4") "the footer names the next offset")))
  (testing "the last page has no footer -- there is nowhere to continue to"
    (let [out (read-raw "r1" {:path "ten.txt" :offset 8})]
      (is (= 3 (count (rows out))))
      (is (not (str/includes? out "Use offset=")))))
  (testing "and the offsets chain: page 2 starts where page 1 stopped"
    (let [page-1 (read-raw "r1" {:path "ten.txt" :limit 4})
          _      (is (str/includes? page-1 "offset=5"))
          page-2 (read-raw "r1" {:path "ten.txt" :limit 4 :offset 5})]
      (is (= "line5" (second (first (rows page-2))))))))

(deftest a-bad-offset-or-limit-is-named-not-swallowed
  (use-mode! "r1" ":hashline")
  (put! "three.txt" "a\nb\nc\n")
  (doseq [v [0 -1 1.5 "2"]]
    (doseq [k [:offset :limit]]
      (let [{:keys [content error]} (call "r1" "read" {:path "three.txt" k v})]
        (is (true? error) (str k "=" v))
        (is (str/includes? content (name k)) (str k "=" v))
        (is (str/includes? content "positive integer") (str k "=" v))))))

(deftest an-offset-past-the-end-says-how-long-the-file-is
  (use-mode! "r1" ":hashline")
  (put! "three.txt" "a\nb\nc\n")
  (let [{:keys [content error]} (call "r1" "read" {:path "three.txt" :offset 99})]
    (is (true? error))
    (is (str/includes? content "3 lines"))
    (is (str/includes? content "offset=1"))
    (is (str/includes? content "offset=3") "and the offset that reads the last line")))

;; --------------------------------------------------------------- oversized

(deftest a-line-too-long-to-show-still-gets-its-anchor
  (use-mode! "r1" ":hashline")
  (let [long-line (apply str (repeat 60000 "x"))]
    (put! "long.txt" (str "short\n" long-line "\nshort2\n"))
    (let [out (read-raw "r1" {:path "long.txt"})]
      (testing "the long line is still a row, with an anchor and a message"
        (is (= 3 (count (rows out))))
        (let [[a line] (second (rows out))]
          (is (anchors/anchor? a))
          (is (str/includes? line "not shown"))
          (is (str/includes? line "60000") "the message says how long it is")
          (is (str/includes? line "long.txt") "and names the file to inspect")))
      (testing "and the rows around it are untouched"
        (is (= ["short" "short2"] [(second (first (rows out))) (second (nth (rows out) 2))]))))))

(deftest an-enormous-line-does-not-cost-the-page-the-rows-after-it
  ;; The byte budget measures what the model RECEIVES, and an oversized line's row
  ;; is short. Measuring the LINE instead would stop the page at that line and hide
  ;; the anchors of everything below it -- for a reason the model never sees.
  (use-mode! "r1" ":hashline")
  (put! "huge-first.txt" (str (apply str (repeat 60000 "y")) "\nafter\n"))
  (let [out (read-raw "r1" {:path "huge-first.txt"})]
    (is (= 2 (count (rows out))) "both lines are rows, though one is enormous")
    (is (str/includes? out "not shown"))
    (is (= "after" (second (second (rows out)))) "and the line below is shown in full")
    (testing "so both anchors are in the store's hands"
      (is (= (mapv first (rows out))
             (:anchors (store/state "r1" (path-of "huge-first.txt"))))))
    (testing "and no footer claims there is more to read"
      (is (not (str/includes? out "Use offset="))))))

;; ------------------------------------------------------------- the refusals

(deftest what-cannot-be-read-is-refused-by-name
  (use-mode! "r1" ":hashline")
  (testing "a directory"
    (let [{:keys [content error]} (call "r1" "read" {:path ".harness"})]
      (is (true? error))
      (is (str/includes? content "directory"))
      (is (str/includes? content "bash") "and it says what to do instead")))
  (testing "a binary file"
    (with-open [o (io/output-stream (path-of "bin.dat"))]
      (.write o (byte-array (concat (map byte (range 1 40)) [0 0] (map byte (range 1 40))))))
    (let [{:keys [content error]} (call "r1" "read" {:path "bin.dat"})]
      (is (true? error))
      (is (str/includes? content "binary"))
      (is (str/includes? content "NUL"))))
  (testing "a PNG"
    (with-open [o (io/output-stream (path-of "pic.png"))]
      (.write o (byte-array [0x89 0x50 0x4E 0x47 0x0D 0x0A 0x1A 0x0A 0 0 0 0])))
    (let [{:keys [content error]} (call "r1" "read" {:path "pic.png"})]
      (is (true? error))
      (is (str/includes? content "PNG"))
      (is (str/includes? content "image"))))
  (testing "a JPEG"
    (with-open [o (io/output-stream (path-of "pic.jpg"))]
      (.write o (byte-array [0xFF 0xD8 0xFF 0xE0 0 0 0 0 0 0 0 0])))
    (is (str/includes? (:content (call "r1" "read" {:path "pic.jpg"})) "JPEG")))
  (testing "UTF-16 text -- which would otherwise decode into a string full of NULs"
    (with-open [o (io/output-stream (path-of "utf16.txt"))]
      (.write o (byte-array [0xFF 0xFE 0x41 0x00 0x42 0x00])))
    (let [{:keys [content error]} (call "r1" "read" {:path "utf16.txt"})]
      (is (true? error))
      (is (str/includes? content "UTF-16LE"))
      (is (str/includes? content "iconv") "and the conversion that would fix it")))
  (testing "and a file that is simply not there"
    (let [{:keys [content error]} (call "r1" "read" {:path "nope.txt"})]
      (is (true? error))
      (is (str/includes? content "no such file")))))

(deftest str-replace-mode-reads-what-it-always-read
  ;; The ticket's regression guarantee, stated where it could break: `read` is one
  ;; tool name, and the mode changes what it RENDERS -- not what it is willing to
  ;; OPEN. The text gate belongs to the anchored read alone, because it is the
  ;; anchored read that would otherwise mint anchors against a file decoded into
  ;; nonsense. Plain `read` has never had that problem, and tightening it here
  ;; would be a behaviour change nothing asked for.
  (with-open [o (io/output-stream (path-of "bin2.dat"))]
    (.write o (byte-array [65 0 66])))
  (use-mode! "r-both" ":str-replace")
  (let [{:keys [content error]} (call "r-both" "read" {:path "bin2.dat"})]
    (is (false? error) "plain read still reads it, as it always did")
    (is (str/includes? content "A")))
  (use-mode! "r-both" ":hashline")
  (let [{:keys [content error]} (call "r-both" "read" {:path "bin2.dat"})]
    (is (true? error) "the anchored read refuses it, because it would mint anchors")
    (is (str/includes? content "binary"))))

;; -------------------------------------------------- what the session learns

(deftest reading-is-when-a-session-learns-the-anchors
  (use-mode! "r1" ":hashline")
  (put! "learn.txt" "one\ntwo\n")
  (is (nil? (store/state "r1" (path-of "learn.txt"))) "nothing known before the read")
  (read-raw "r1" {:path "learn.txt"})
  (let [st (store/state "r1" (path-of "learn.txt"))]
    (is (some? st))
    (is (= 2 (:line-count st)))
    (testing "the anchors it handed out are the ones it recorded"
      (is (= (mapv first (rows (read-raw "r1" {:path "learn.txt"}))) (:anchors st))))
    (testing "and they are recorded as owned"
      (is (every? #(contains? (store/ownership "r1") %) (:anchors st))))))

(deftest reading-the-same-file-twice-hands-back-the-same-anchors
  ;; The stability the whole feature rests on. It also means the second read does
  ;; not walk the anchor table at all -- it finds the file unchanged and reuses
  ;; what it stored.
  (use-mode! "r1" ":hashline")
  (put! "stable.txt" "one\ntwo\nthree\n")
  (let [first-rows  (read-raw "r1" {:path "stable.txt"})
        second-rows (read-raw "r1" {:path "stable.txt"})]
    (is (= first-rows second-rows))))

(deftest a-file-that-moved-underneath-keeps-the-anchors-that-still-fit
  ;; Nobody is saying how it changed -- it was not our edit -- so the alignment
  ;; falls back to its common prefix. What matters is that the lines that did not
  ;; move are still addressable after the file changed.
  (use-mode! "r1" ":hashline")
  (put! "moved.txt" "one\ntwo\nthree\n")
  (let [before (read-raw "r1" {:path "moved.txt"})
        a1     (anchor-of before "one")
        a2     (anchor-of before "two")]
    (spit (path-of "moved.txt") "one\ntwo\nTHREE\n" :encoding "UTF-8")
    (let [after (read-raw "r1" {:path "moved.txt"})]
      (testing "the lines that did not move kept their anchors"
        (is (= a1 (anchor-of after "one")))
        (is (= a2 (anchor-of after "two"))))
      (testing "and the line that changed got a new one"
        (is (not= (anchor-of before "three") (anchor-of after "THREE"))))
      (testing "the new anchors are what the store now holds"
        (is (= (mapv first (rows after))
               (:anchors (store/state "r1" (path-of "moved.txt")))))))))

(deftest two-sessions-reading-one-file-hold-different-anchors
  ;; An anchor is minted for ONE session, so the store's snapshot is per session
  ;; and neither read reaches the other's.
  (use-mode! "r-a" ":hashline")
  (use-mode! "r-b" ":hashline")
  (put! "shared.txt" "one\ntwo\n")
  (let [a (read-raw "r-a" {:path "shared.txt"})
        b (read-raw "r-b" {:path "shared.txt"})]
    (is (not= a b) "different sessions, different anchors for the same file")
    (is (= ["one" "two"] (mapv second (rows a))))
    (is (= ["one" "two"] (mapv second (rows b))))))

;; ----------------------------------------------- the inherited behaviour

(deftest relative-paths-and-the-fence-are-untouched-by-the-mode
  ;; Both of these predate the editing mode and must survive it: re-rooting is
  ;; what a project binding IS, and the fence is what parks an out-of-bounds read
  ;; for a human.
  (use-mode! "r-fence" ":hashline")
  (put! "inside.txt" "here\n")
  (testing "a relative path still resolves against the project"
    (is (str/includes? (read-raw "r-fence" {:path "inside.txt"}) "here")))
  (testing "an absolute path inside the project works too"
    (is (str/includes? (read-raw "r-fence" {:path (path-of "inside.txt")}) "here")))
  (testing "and one outside the project and the config home still parks"
    (let [res (call "r-fence" "read" {:path (support/outside-path "hosts")})]
      (is (some? (:parked res)) "the call is waiting for a human")
      (is (= :out-of-bounds (:reason (:parked res)))))))

(deftest str-replace-mode-reads-the-file-as-before
  (use-mode! "r-plain" ":str-replace")
  (put! "plain.txt" "alpha\nbeta\n")
  (testing "the text comes back byte for byte, with no anchors"
    (is (= "alpha\nbeta\n" (read-raw "r-plain" {:path "plain.txt"})))
    (is (not (str/includes? (read-raw "r-plain" {:path "plain.txt"}) "│"))))
  (testing "and nothing was recorded about the file"
    (is (nil? (store/state "r-plain" (path-of "plain.txt"))))))

;; ------------------------------------------------------- the tool's face

(deftest the-description-follows-the-mode-and-so-do-the-parameters
  (let [spec-for    (fn [thread-id]
                      (first (filter #(= "read" (get-in % [:function :name]))
                                     (tools/specs thread-id))))
        anchor-spec (do (use-mode! "r-face" ":hashline") (spec-for "r-face"))
        plain-spec  (do (use-mode! "r-face" ":str-replace") (spec-for "r-face"))]
    (testing "the anchored face describes anchors and paging"
      (is (str/includes? (get-in anchor-spec [:function :description]) "anchors"))
      (is (str/includes? (get-in anchor-spec [:function :description]) "offset"))
      (is (contains? (get-in anchor-spec [:function :parameters :properties]) "offset"))
      (is (contains? (get-in anchor-spec [:function :parameters :properties]) "limit"))
      (is (contains? (get-in anchor-spec [:function :parameters :properties]) "path")))
    (testing "the plain face does not"
      (is (not (str/includes? (get-in plain-spec [:function :description]) "anchor")))
      (is (not (contains? (get-in plain-spec [:function :parameters :properties]) "offset"))))
    (testing "the two faces really are different"
      (is (not= (get-in anchor-spec [:function :description])
                (get-in plain-spec [:function :description]))))
    (testing "and the plain face takes exactly the path"
      (is (= #{"path"} (set (keys (get-in plain-spec [:function :parameters :properties]))))))))

;; ------------------------------------------ the marking shares the read's lock

(deftest the-read-marks-what-it-showed-under-the-session-lock
  ;; THE BUG THIS PINS: `sync!` reads `:anchors` under the session lock, and the
  ;; marking derived from that read used to happen after the lock was released. A
  ;; concurrent edit in that gap prunes the freed anchors (`advance-on!`) and the
  ;; marking unions them straight back in -- so `served` names an anchor that is no
  ;; longer in `:anchors`. The stub turns 'was the lock held when the marking ran?'
  ;; into a value a test can read, with no race and no scheduling to hope for.
  (use-mode! "r-lock" ":hashline")
  (put! "lock.txt" "one\ntwo\n")
  (let [seen (lock-witness #(read-raw "r-lock" {:path "lock.txt"}))
        held (get seen "r-lock")]
    (is (seq held) "the read did mark the rows it showed")
    (is (every? true? held)
        (str "every mark-served! call must hold r-lock's session lock; got "
             (pr-str held)))))

(deftest a-concurrent-edit-cannot-leave-a-freed-anchor-marked-shown
  ;; THE SECOND SAFETY NET, and it does not rest on the stub above. This one FORCES
  ;; the interleaving the bug lives in instead of racing for it: the read is stopped
  ;; in `reading/preview`, which sits exactly between `sync!` (the read) and
  ;; `mark-served!` (the marking), and an edit of the same file is run there before
  ;; the read is let go. Under the old code the edit prunes `served` in `advance-on!`
  ;; and the marking unions the pruned names straight back, so `served` names an
  ;; anchor no longer in `:anchors` -- the assertion below is that invariant. Under
  ;; the fix the read holds the session lock across the window, so the edit waits its
  ;; turn and the invariant survives.
  (use-mode! "r-race" ":hashline")
  (put! "race.txt" "one\ntwo\nthree\n")
  (let [target (anchor-of (read-raw "r-race" {:path "race.txt"}) "three")
        gate   (support/window-gate #'reading/preview)
        r-err  (atom nil)
        e-err  (atom nil)]
    (try
      (let [reader (Thread. (fn []
                              (try (read-raw "r-race" {:path "race.txt"})
                                   (catch Throwable t (reset! r-err t)))
                              nil))
            editor (Thread. (fn []
                              (try (call "r-race" "replace"
                                         {:remove_from target
                                          :replacement_lines ["THREE"]})
                                   (catch Throwable t (reset! e-err t)))
                              nil))]
        (.start reader)
        (is (support/holds-within? #(<= 1 ((:entered gate))) 5000)
            "the read reached the window between its anchors and the marking")
        (.start editor)
        ;; Let the edit either LAND in the window -- the bug, where the window is the
        ;; read's own unprotected gap -- or settle against the session lock the read is
        ;; holding -- the fix, where it cannot land at all. Bounded, so a read that
        ;; never reaches the gate fails loudly instead of hanging the suite.
        (support/holds-within? #(not (.isAlive editor)) 5000)
        ((:release gate))
        (.join reader 15000)
        (.join editor 15000))
      (finally ((:restore gate))))
    (is (nil? @r-err) (str "the read threw: " @r-err))
    (is (nil? @e-err) (str "the edit threw: " @e-err))
    (let [st (store/state "r-race" (path-of "race.txt"))]
      (is (some? st))
      (testing "every served anchor still exists in :anchors"
        (is (every? (set (:anchors st)) (:served st))
            (str "served=" (pr-str (:served st))
                 " anchors=" (pr-str (:anchors st))))))))
