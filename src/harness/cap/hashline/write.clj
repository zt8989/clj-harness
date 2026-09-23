(ns harness.cap.hashline.write
  "`write` in anchor mode: the boundary where anchors stop meaning anything.

  A WRITE INVALIDATES EVERY ANCESTOR IT HAS. The file's content is no longer the
  content those anchors were minted against -- not mostly, not approximately: the
  anchors addressed LINES, and the lines are different lines now. So a successful
  write releases the file's whole anchor set for this session and clears its undo
  record, and the next edit addressed at those anchors is refused with 'read it
  first'. Keeping them would be worse than useless: they would stay out of
  circulation for a session that cannot use them (see store/forget-file!), and a
  view the session still trusts would let the next edit validate against a file
  that no longer exists.

  THE REFUSAL OF AN ECHO IS WRITE'S OWN SHAPE CONSTRAINT. A model copying content
  out of read output naturally copies the `anchor│` prefix with it. Written in, the
  file is polluted, and -- worse than polluted -- the next read hands the `Hasu│`
  rows back out as if they were content, minting anchors for lines that begin with
  an old anchor and re-emitting them forever. The mistake reproduces itself. So it
  is refused by name, at the line, with the anchor that was copied.

  It lives here rather than as a PreToolUse rule on purpose: this is what `write`
  IS in anchor mode, not a policy somebody might reasonably set differently. The
  hook point is there (`{:tool_name :tool_input}`, exit 2 to refuse and hand stderr
  back) if it ever needs to become configurable; this is where it starts.

  WRITING IS NOT READING, so the answer does not pretend otherwise. A write
  releases the file's anchors; it does not hand back a fresh set. What was written
  is already in the call that wrote it, so echoing a head of the file back would
  pay for the same tokens twice -- and worse, it would read as a grant to edit the
  whole file when what it showed was the first twenty lines. The answer therefore
  states two facts (how much was written, and that the anchors are gone) and names
  the one action that brings them back: `read`. The two halves agree: an echo of a
  line is refused above, and a freshly written line is not an anchor until a read
  has shown it."
  (:require [clojure.string :as str]
            [harness.cap.hashline.store :as store]))

(defn- stored-anchors
  "The anchors this session holds for PATH -- every one it was served, not only
  the ones a page happened to show. Superset on purpose: an echo of an anchor the
  model never saw is still an echo, and refusing it costs nothing."
  [thread-id path]
  (set (:anchors (store/state thread-id path))))

(defn echo-line
  "The first line of CONTENT that looks like read output copied back in, as
  [index anchor], or nil.

  Two conditions have to hold, and the second is what keeps this from refusing
  ordinary text: the line must start with four alphanumerics and the `│`
  separator, AND those four characters must be an anchor THIS FILE was served in
  THIS session. A line that merely contains a box-drawing character, or one that
  starts with `Hasu│` in a session that never saw `Hasu` on this file, is content
  -- and refusing content is a much worse error than accepting an echo."
  [thread-id path content]
  (let [known (stored-anchors thread-id path)]
    (when (seq known)
      (first (keep-indexed
              (fn [i line]
                (when-let [m (re-matches #"^([A-Za-z0-9]{4})│[\s\S]*$" line)]
                  (when (contains? known (nth m 1)) [i (nth m 1)])))
              (str/split content #"\n" -1))))))

(defn check-no-echo!
  "Refuse CONTENT that has read's markup written into it. Names the line and the
  anchor, because 'somewhere in there' is not something a model can fix."
  [thread-id path content]
  (when-let [[i anchor] (echo-line thread-id path content)]
    (throw (ex-info (str "`content` line " (inc i) " starts with `" anchor
                         "│`, and " anchor " is an anchor this session was served"
                         " for this file. The four characters and the `│` are read's"
                         " markup, not file content -- writing them in would put them"
                         " in the file and every later read would hand them back out"
                         " as if they were text. Send the line without them.")
                    {:path path :line (inc i) :anchor anchor :reason :anchor-echo}))))

(defn perform!
  "Run one `write` for THREAD-ID in anchor mode.

  ARGS is the tool's argument map. Returns a STRING.

  THE ORDER IS THE CONTRACT. Check the echo (nothing written, nothing released);
  write the file; release the anchors and clear the undo; and answer with the two
  facts. A refusal therefore costs the file, the ownership table and the undo
  record nothing.

  ONE LOCK, THE PATH'S. The answer does not read the file back (see the namespace
  docstring), so nothing in here wants the session's anchors: `forget-file!` and
  `clear-undo!` are database transactions. Reading the head back is what used to
  make a second, session-wide lock necessary here."
  [thread-id resolve-path args]
  (let [path (store/canonical (resolve-path (:path args)))
        content (:content args)]
    (store/with-path-lock
     path
     (fn []
       (check-no-echo! thread-id path content)
       (let [f (java.io.File. ^String path)]
         (when-let [p (.getParentFile f)] (.mkdirs p))
         (spit f content :encoding "UTF-8"))
       (store/forget-file! thread-id path)
       (store/clear-undo! path)
       (str "wrote " (count content) " chars to " path
            "\n\nThe file's anchors have been released: read it to get the"
            " anchors for what is there now.")))))
