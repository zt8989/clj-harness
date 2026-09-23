(ns harness.cap.spill
  "SPILL: move ONE just-produced, oversized TOOL RESULT out of the conversation and leave the
  model a PICKUP SLIP -- a preview, an OPAQUE LOCATOR, and the sentence that says how to get the
  rest back.

  WHERE IT SITS AMONG THE THREE WAYS TO SHRINK A REQUEST, which is the whole point of this
  ticket. SPILL acts on a SINGLE result the moment a tool produces it, BEFORE it is ever put in
  the history. PRUNING (`harness.edge.prune`) elides a large tool result already ON the record.
  A compaction SUMMARY replaces a whole run of history. None of the three touches the ENVELOPE
  -- the system prompt and the tool table are not history, and nothing here may cut them.

  IT IS NOT THE OTHER SPILL. `harness.cap.jobs/spill!` writes a FOREGROUND COMMAND's whole answer
  to a record when it does not fit its own byte budget (a `bash` call over 8000 bytes); that is
  ONE tool's answer kept in the JOBS tree, and the answer keeps a tail plus a line saying where
  the rest is. THIS is the general mechanism: ANY tool result over `threshold-chars`, moved
  under the SPILL tree, replaced in the conversation by a slip. Different directories, different
  ids, different answer shapes -- so a reader never has to guess which spill wrote a file.

  THE LOCATOR IS OPAQUE. This namespace answers `{:locator .. :retrieval ..}` and builds the slip
  from those two; it never parses the locator or assumes it is a path -- a backend may answer a
  URI or a key. The LOCAL backend's locator IS an absolute path and its retrieval sentence names
  the `read` tool, because a file under the configuration home is what `read` reaches with the
  fence out of the way; but that is a fact about this backend, not about the locator.

  IT IS BEST EFFORT. Any failure -- the home is not writable, a file is already there -- leaves
  the ORIGINAL result inline, because a successful tool call must NEVER be turned into an
  `isError` by a record that could not be written. The failure is logged: a spill that silently
  stops happening is a leak, not a hiccup.

  THE WRITE IS EXCLUSIVE. The file is created with CREATE_NEW, so a path that already exists --
  a symlink somebody planted to redirect the write -- is neither followed nor written through;
  the parent directory is refused if it is itself a symbolic link, for the same reason.

  THE FILES ARE KEPT. They ARE the retrievable originals, so deleting one would delete the
  thing the slip points at; nothing sweeps this tree yet, which is a known cost rather than a
  design -- an age policy belongs with whoever decides how long a session's records live."
  (:require [clojure.java.io :as io]
            [harness.infra.home :as home]
            [harness.infra.log :as log]
            [harness.infra.text :as text])
  (:import [java.nio.charset StandardCharsets]
           [java.nio.file Files OpenOption StandardOpenOption]
           [java.nio.file.attribute FileAttribute]))

(def threshold-chars
  "A tool result longer than this many CODE POINTS is moved out of the conversation. The ordinary
  `read` or `grep` answer is far below it; a fetched page or a fifty-thousand-line match is far
  above. Nothing about a `bash` answer lands here: it is already bounded by `cap.jobs`' byte
  budget, an order below this."
  20000)

(def preview-chars
  "How many code points of the original stay inline, as a preview, so the model can see how the
  output began and decide whether it needs the whole thing."
  1500)

(defn- spill-dir
  "Where THREAD-ID's spilled results live, under the configuration home: `<root>/spill/<session>`.
  A directory the fence already frees, so `read` and `grep` reach it with no human in the way --
  the same reason `cap.jobs` puts a command's record there."
  [thread-id]
  (io/file (home/root) "spill" (home/sanitize thread-id)))

(defn write-new!
  "Write TEXT to P with CREATE_NEW, or throw. THE EXCLUSIVE RULE, in one place: an existing
  path -- a file, a symlink somebody planted to redirect the write -- is refused, never
  followed and never written through. Public so the rule can be asserted by planting one."
  [^java.io.File p txt]
  (Files/write (.toPath p) (.getBytes ^String txt StandardCharsets/UTF_8)
               (into-array OpenOption [StandardOpenOption/CREATE_NEW StandardOpenOption/WRITE]))
  (.getAbsolutePath p))

(defn- exclusive-write!
  "Write TEXT as THREAD-ID's next spilled result and answer its absolute path, or throw.

  The session directory is refused if it is itself a symbolic link, and the file is made
  through `write-new!`: the name is a fresh UUID, so the only way CREATE_NEW meets an existing
  file is a deliberate plant, which is exactly what must not be written through."
  [thread-id txt]
  (let [dir (spill-dir thread-id)
        _   (Files/createDirectories (.toPath dir) (make-array FileAttribute 0))]
    (when (Files/isSymbolicLink (.toPath dir))
      (throw (ex-info (str "the spill directory is a symbolic link: " (.getAbsolutePath dir))
                      {:dir (.getAbsolutePath dir) :reason :spill-dir-is-link})))
    (write-new! (io/file dir (str (java.util.UUID/randomUUID) ".txt")) txt)))

(defn local-backend
  "The default backend: a file under the configuration home, retrieved with the `read` tool.
  Answers `{:locator <absolute path> :retrieval <the sentence that says how to read it>}`.

  THE RETRIEVAL SENTENCE IS THE CONTRACT, not the locator: a consumer renders this and nothing
  else, so swapping in a backend that answers a URI changes only this function and the sentence
  it hands back."
  [thread-id txt]
  (let [path (exclusive-write! thread-id txt)]
    {:locator   path
     :retrieval (str "To read the whole result, use the read tool with path = " (pr-str path) ".")}))

(defn slip-text
  "The PICKUP SLIP the model reads in place of CONTENT: how much there was, the opaque LOCATOR,
  the backend's own RETRIEVAL sentence, and a preview of the head. Built from the locator and
  the retrieval sentence -- never from a path this layer assembled itself."
  [content locator retrieval]
  (str "[spilled: this tool result was " (text/code-point-count content)
       " characters, so the whole of it was moved out of the conversation. The locator below is"
       " OPAQUE -- act on the retrieval line, not on the locator's shape.]\n"
       "Locator: " locator "\n"
       retrieval "\n"
       "Preview (its first " (min preview-chars (text/code-point-count content)) " characters):\n"
       (text/head content preview-chars)))

(defn slip
  "CONTENT of a just-produced tool result -> what the model reads instead of it, or CONTENT
  unchanged when it is small enough or when the write could not be made.

  The shape a caller wires up is `(fn [content] -> content)`: this never throws and never
  returns nil, so the one path that must not break -- a tool call that succeeded -- cannot be
  broken by the spill. BACKEND defaults to `local-backend` and is the seam a test or another
  deployment replaces."
  ([thread-id content] (slip thread-id content local-backend))
  ([thread-id content backend]
   (if (or (not (string? content))
           (<= (text/code-point-count content) threshold-chars))
     content
     (try
       (if-some [{:keys [locator retrieval]} (backend thread-id content)]
         (slip-text content locator retrieval)
         content)
       (catch Throwable t
         (log/warn! :spill/not-written {:thread-id thread-id :reason (ex-message t)})
         content)))))
