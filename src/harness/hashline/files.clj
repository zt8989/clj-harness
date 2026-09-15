(ns harness.hashline.files
  "Reading and writing a file without losing what the file had: byte-order mark,
  line endings, and permission bits.

  WHY THIS IS ITS OWN NAMESPACE. An edit rewrites a whole file -- that is what the
  anchor scheme amounts to, since the anchors are for LINES and the text between
  them is rebuilt -- and a whole-file rewrite is exactly the operation that
  flattens a CRLF file to LF, drops a BOM, or resets a mode bit. Each of those is
  silent: nothing errors, the content looks right, and the diff a human reviews is
  enormous. So the three facts are CAPTURED before the edit and RESTORED after it,
  and this is where both halves live.

  The model never sees any of it. `read` shows lines with their carriage returns
  stripped (the checksum ignores them anyway, so showing them would be showing
  something the anchor does not describe); the edit puts back the ending the file
  actually had.

  THE THREE FACTS ARE CAPTURED FOR THE UNDO RECORD TOO, which is why `mode-bits`
  and its inverse live here rather than at the one call site that needs them: an
  undo restores what an edit captured, and the encoding of a permission set into a
  column and back out again is this namespace's kind of problem."
  (:require [clojure.string :as str])
  (:import [java.io File]
           [java.nio.file CopyOption Files LinkOption StandardCopyOption]
           [java.nio.file.attribute PosixFilePermission]))

(def ^:private bom-char \uFEFF)

(defn strip-bom
  "S minus a leading UTF-8 BOM, if it has one. Returns [S' had-bom?]."
  [^String s]
  (if (and (pos? (.length s)) (= bom-char (.charAt s 0)))
    [(subs s 1) true]
    [s false]))

(defn detect-ending
  "The line ending TEXT uses: a carriage return followed by a line feed, a line
  feed alone, or a carriage return alone. The FIRST one seen wins, and a file with
  no newline at all gets a line feed -- the choice only matters for a file that is
  being GIVEN lines, and LF is the answer that keeps a one-line file from acquiring
  carriage returns it never had."
  [^String text]
  (let [i (str/index-of text "\n")
        r (str/index-of text "\r")]
    (cond
      (and i (pos? i) (= \return (.charAt text (dec i)))) "\r\n"
      i                                               "\n"
      r                                               "\r"
      :else                                            "\n")))

(defn to-lf
  "TEXT with every line ending normalized to \\n -- the form every line operation
  here works on."
  [^String text]
  (-> text (.replace "\r\n" "\n") (.replace "\r" "\n")))

(defn from-lf
  "TEXT with \\n replaced by ENDING. The inverse of `to-lf` for a text that has
  been normalized, and the only place an edit's result acquires carriage
  returns."
  [^String text ^String ending]
  (if (= "\n" ending) text (.replace text "\n" ending)))

(defn- mode-of
  "PATH's POSIX permissions as a set, or nil where the platform has none (Windows)
  or the file is not there. Nil is the honest answer, not an empty set: 'this
  platform does not track it' and 'the file has no permissions' are not the same
  fact, and only the second one should be restored."
  ^java.util.Set [^String path]
  (try
    (let [f (File. path)]
      (when (.exists f)
        (Files/getPosixFilePermissions (.toPath f) (make-array LinkOption 0))))
    (catch Exception _ nil)))

(defn mode-bits
  "PERMISSIONS as an integer, for the undo record to carry. Nil stays nil, which
  is what tells 'this platform has no such thing' apart from 'nothing is allowed'
  -- the same distinction `mode-of` draws, preserved through the round trip."
  [permissions]
  (when permissions
    (reduce (fn [^long acc ^PosixFilePermission p]
              (bit-or acc (bit-shift-left 1 (.ordinal p))))
            0
            permissions)))

(defn mode-from-bits
  "The permissions `mode-bits` encoded, or nil. The inverse, so what an undo puts
  back is what the edit captured rather than whatever the file has now."
  [bits]
  (when (some? bits)
    (let [es (java.util.EnumSet/noneOf PosixFilePermission)]
      (doseq [^PosixFilePermission p (PosixFilePermission/values)]
        (when (pos? (bit-and (long bits) (bit-shift-left 1 (.ordinal p))))
          (.add es p)))
      es)))

(defn- restore-mode!
  [^File f mode]
  (when mode
    (try
      (Files/setPosixFilePermissions (.toPath f) ^java.util.Set mode)
      (catch Exception _ nil))))

(defn read-file
  "PATH as {:text <without BOM, line endings normalized to \\n>
            :bom <boolean> :ending <string> :mode <permissions or nil>}.

  Everything an edit must put back, captured in one read so the write that follows
  cannot disagree with it."
  [^String path]
  (let [raw        (slurp (File. path) :encoding "UTF-8")
        [body bom] (strip-bom raw)]
    {:text   (to-lf body)
     :bom    bom
     :ending (detect-ending body)
     :mode   (mode-of path)}))

(defn write-file!
  "Write TEXT to PATH as the file it already was: BOM back, line endings back,
  permissions back.

  WRITTEN AS A TEMP FILE AND RENAMED, so a failure part-way through leaves the
  original untouched rather than truncated -- the caller has already decided the
  edit is valid, and losing the file at that point would be the worst outcome
  available. The temp file is a sibling, so the rename is atomic on one filesystem."
  [^String path ^String text {:keys [bom ending mode]}]
  (let [f    (File. path)
        tmp  (File. (str path ".hashline-tmp"))
        body (str (when bom (str bom-char)) (from-lf text (or ending "\n")))]
    (spit tmp body :encoding "UTF-8")
    (restore-mode! tmp mode)
    (Files/move (.toPath tmp) (.toPath f) (into-array CopyOption [StandardCopyOption/REPLACE_EXISTING]))
    f))
