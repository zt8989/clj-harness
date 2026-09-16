(ns harness.cap.hashline.undo
  "`undo_last_replace`: take back the last edit to a file -- the text, the encoding
  it had, and the anchors that named it.

  ONE CALL IS ONE UNDO. The unit is the edit, not the line: `replace` may have
  rewritten nine lines, and what the model wants back is the file as it was before
  it asked. That is why the tool is named after the edit it reverses rather than
  being a general `undo`.

  IT REFUSES RATHER THAN OVERWRITING. The record holds the text on both sides of
  the edit, so the current file can be compared with what the edit produced. If
  they disagree, something changed the file after the edit -- a person in an
  editor, another tool, another session -- and rolling back would silently eat
  that work. So a mismatch does NOTHING, leaves the record in place, and says that
  the file has moved on. That case is the one place where 'undo' can destroy more
  than it restores, and it is the only case this namespace is careful about.

  A FILE THAT IS GONE IS RESTORED. The record has the text before the edit, so a
  deleted file can be put back, and the answer says that is where it came from --
  a file reappearing is worth a sentence, not a surprise.

  WHAT IT DOES *NOT* DO: reach further back than one edit (the record is a single
  row per file, replaced by the next edit and cleared by `write`), or undo another
  file's edit (the record is keyed by path). Both are stated in the tool's own
  description, because a model that expects a stack and finds one step will
  conclude the tool is broken rather than that it is shallow."
  (:require [clojure.string :as str]
            [harness.cap.hashline.anchors :as anchors]
            [harness.cap.hashline.files :as files]
            [harness.cap.hashline.serve :as serve]
            [harness.cap.hashline.store :as store]))

(defn- cannot!
  [path why]
  (throw (ex-info why {:path path :reason :cannot-undo})))

(defn- undo-rows
  "The restored file as anchored rows, so the answer leaves the model able to edit
  again with no read -- the same property every other answer in this feature has.

  A row renderer rather than a bare dump of the restored text: what came back is
  the FILE, and the file is addressed by anchors. This re-uses the view the restore
  just landed rather than minting a second set for the same content."
  [thread-id path]
  (:text (serve/read! thread-id path {})))

(defn- restore-change
  "The alignment-shaped map that puts THREAD-ID's view of PATH back to the anchors
  UNDO names.

  `:added` and `:freed` are the difference between the anchors that were there
  before the edit and the ones there now -- computed HERE rather than by an
  alignment, because the record already states the answer and an alignment would
  be deriving it from the wrong direction (its job is to keep what survived an
  edit, not to resurrect what that edit freed)."
  [thread-id path undo checks]
  (let [old     (vec (:anchors undo))
        current (vec (:anchors (store/state thread-id path)))
        old-set (set old)]
    {:anchors        old
     :line-checksums checks
     :file-checksum  (anchors/file-checksum checks)
     :added          (vec (remove (store/ownership thread-id) old))
     :freed          (vec (remove old-set current))
     :served         (:served undo)
     :served?        true
     ;; The probe is NOT rewound: the anchors this file is giving up have been
     ;; handed out, and minting over them again would produce two lines with one
     ;; name. Where it stands is where it stays.
     :probe          (or (store/probe-of thread-id) (anchors/seed thread-id))}))

(defn- borrowed!
  "Refuse when one of the anchors the record names has since been handed to a
  DIFFERENT file in this session.

  It can happen: an anchor freed by the edit went back into the pool, and a later
  edit to another file minted it. Restoring the view here would then claim a name
  that is already addressing a line somewhere else, so the honest answer is that
  this edit can no longer be taken back -- and the record STAYS, because the fact
  of it is still true even though it can no longer be applied."
  [thread-id path undo]
  (let [owned (store/ownership thread-id)
        taken (vec (for [a (:anchors undo)
                         :let [holder (get owned a)]
                         :when (and holder (not= holder path))]
                     a))]
    (when (seq taken)
      (cannot! path (str "nothing was undone: the anchors this edit would put back"
                         " are in use elsewhere in this session now ("
                         (str/join ", " (take 3 taken))
                         (when (> (count taken) 3) ", ...")
                         "), so the lines they name cannot be restored without two"
                         " lines sharing a name. Read the file and edit the lines you"
                         " want changed instead.")))))

(defn perform!
  "Run one undo for THREAD-ID: PUT BACK the file UNDO describes, and the anchors
  with it. Returns a STRING.

  ARGS carries only `path` -- the target is a file, not an anchor. The anchors this
  call is about are the ones the record names, and the model may not hold any of
  them any more, which is half of why it is undoing."
  [thread-id resolve-path args _config]
  (let [given (:path args)]
    (when (or (nil? given) (and (string? given) (str/blank? given)))
      (throw (ex-info "`path` is required: name the file whose last edit you want back."
                      {:reason :missing-path})))
    (let [path (store/canonical (resolve-path given))]
      (store/with-path-lock
       path
       (fn []
         (let [undo (store/undo-for path)]
           (when-not undo
             (cannot! path (str "no edit to undo in " path ": either nothing has"
                                " changed it, or the last change was a write (which"
                                " clears the undo history), or it has already been"
                                " undone. `undo_last_replace` takes back ONE edit,"
                                " so check the file with read before assuming a"
                                " change did not happen.")))
           (borrowed! thread-id path undo)
           (let [f      (java.io.File. ^String path)
                 exists (.exists f)
                 live   (when exists (:text (files/read-file path)))]
             ;; The refusal that matters: the file is not what the edit left, so
             ;; rolling back would quietly discard whatever changed it since.
             (when (and exists (not= live (:resulting-text undo)))
               (cannot! path (str "nothing was undone: " path " is no longer what"
                                  " the last edit left behind, so something changed"
                                  " it afterwards (an editor, another tool, a bash"
                                  " command). Taking the edit back now would discard"
                                  " that later change as well. The undo history is"
                                  " kept -- read the file to see what it now holds,"
                                  " and edit the lines you actually want changed.")))
             (let [checks (anchors/line-checksums (:prior-text undo))
                   _      (store/restore! thread-id path undo
                                          (restore-change thread-id path undo checks))]
               (files/write-file! path (:prior-text undo)
                                  {:bom    (:bom undo)
                                   :ending (:ending undo)
                                   ;; The CAPTURED permissions, not the file's
                                   ;; current ones: this is a restore, so what the
                                   ;; edit saw is what comes back.
                                   :mode   (files/mode-from-bits (:mode undo))})
               (str "Undid the last edit to " path "."
                    (when-not exists
                      (str " The file had been deleted; it is restored from the undo"
                           " history."))
                    "\n\n" (undo-rows thread-id path))))))))))

