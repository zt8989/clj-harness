(ns harness.cap.hashline.serve
  "Handing a file to the model as anchored rows, and remembering what was handed.

  This is the one place a snapshot is WRITTEN for a file the model only looked at.
  `read` calls it; nothing else does. Keeping it here rather than inside the read
  tool means the whole of 'what does a session know about this file after a read'
  is one function a test can drive without a tool call or a run.

  THE SNAPSHOT IS REUSED WHEN THE FILE DID NOT MOVE. The stored file checksum is
  compared against the file on disk: equal means every anchor would be minted the
  same way again, so the stored anchors are correct and are handed straight back.
  That is what makes reading the same file twice cheap AND stable -- the second
  read does not walk the anchor table at all.

  WHEN IT DID MOVE, the anchors are re-aligned against the new content, with NO
  spans: this was not our edit, so nobody is saying how the file changed, and
  harness.cap.hashline.anchors/align falls back to its common-prefix alignment. Lines
  that did not move keep their names, which is what lets a model that read a file,
  then had it change underneath it, still address the lines that are still there.

  The stores are consulted under the file's own lock, so a read racing an edit of
  the same file sees one of the two states and never a mixture."
  (:require [harness.cap.hashline.anchors :as anchors]
            [harness.cap.hashline.files :as files]
            [harness.cap.hashline.reading :as reading]
            [harness.cap.hashline.store :as store])
  (:import [java.io File]))

(defn- fresh-change
  "An alignment for CONTENT against whatever the session already knew.

  `:served` is carried through as it stands and then INTERSECTED with the new
  anchors by the store: an anchor whose line survived is still something the model
  has seen, and one that was freed or replaced was seen and is gone. Anchors minted
  here were not shown to anybody until the page that contains them goes out."
  [thread-id path content {:keys [anchors line-checksums served]}]
  (let [checks (anchors/line-checksums content)]
    (assoc (anchors/align {:old-anchors   anchors
                           :old-checksums line-checksums
                           :new-checksums checks
                           :path          path
                           :owned         (store/ownership thread-id)
                           :probe         (or (store/probe-of thread-id)
                                              (anchors/seed thread-id))})
           :file-checksum (anchors/file-checksum checks)
           :line-checksums checks
           :served         (or served #{})
           :served?        true)))

(defn sync!
  "Bring THREAD-ID's stored view of PATH up to CONTENT, and return it -- the same
  shape `store/state` returns, or nil-proof: this always answers with a view.

  Split out of `serve!` because hands out anchors are not the only caller. An edit
  that finds the file has moved underneath it needs the same thing -- the view
  re-aligned to what is on disk and landed -- without a page being emitted; the
  refusal it is about to write does the showing (`harness.cap.hashline.replace`). One
  function for 'what does this session now know about this file', so a healing
  answer and a read can never disagree about it.

  THE VIEW IS REUSED WHEN THE FILE DID NOT MOVE: the stored file checksum is
  compared against the file on disk, and equal means every anchor would be minted
  the same way again. That is what makes reading the same file twice cheap AND
  stable -- the second read does not walk the anchor table at all.

  PATH IS TAKEN AS ITS CANONICAL SELF, and so is every other entry point in this
  namespace. The store books anchors BY PATH, so two spellings of one file have to
  collapse to one key before anything is minted for it -- otherwise `a/f.txt` and
  `a/../a/f.txt` are two files with two anchor sets, and the model is handed names
  that 'stop working' when it addresses the same file the other way."
  [thread-id path content]
  (let [path (store/canonical path)]
    ;; SESSION FIRST, PATH SECOND -- a fixed order (see store/with-session-lock).
    ;; Minting walks the session's probe, so two files served at once for one
    ;; session are not independent even though their paths are.
    (store/with-session-lock
     thread-id
     (fn []
       (store/with-path-lock
        path
        (fn []
          (let [stored  (store/state thread-id path)
                checks  (anchors/line-checksums content)
                current (anchors/file-checksum checks)
                live    (when (and stored (= current (:file-checksum stored)))
                          stored)]
            (if live
              live
              (do
                ;; Persist the alignment BEFORE anything is emitted or decided on it.
                ;; If the write fails the caller gets the failure and no anchors were
                ;; shown. The other order -- show, then persist -- would hand out
                ;; anchors the store does not know about, and the next edit would
                ;; reject every one.
                (store/advance! thread-id path (fresh-change thread-id path content stored))
                (store/state thread-id path))))))))))

(defn serve!
  "THREAD-ID reads PATH, which must already be resolved and classified. Returns
  `harness.cap.hashline.reading/preview`'s map, with `:anchors` added -- the file's
  anchors in line order, which the next edit will be aligned against.

  OPTS carries :offset and :limit, and the page's rows are all this returns; the
  anchors for the WHOLE file are stored regardless, because an edit is addressed
  by an anchor and must not depend on which page happened to be read.

  THE READ AND THE MARKING SHARE ONE SESSION LOCK. `sync!` reads `:anchors` under
  the session lock and `mark-served!` writes the shown set derived from that read:
  a concurrent edit landing in between prunes the freed anchors in `advance-on!`
  and the marking unions them straight back in, recording as shown a name that is
  no longer in `:anchors`. Holding the lock across both closes the gap."
  [thread-id path content {:keys [offset limit] :as opts}]
  (store/with-session-lock
   thread-id
   (fn []
     (let [view (sync! thread-id path content)
           page (reading/preview content (:anchors view)
                                 {:offset offset :limit limit
                                  :path (store/canonical path)})]
       ;; ...and record WHICH of those anchors the model actually saw. This is the half
       ;; that makes 'owned' and 'shown' different facts: the page that was not
       ;; returned holds anchors that exist and were never displayed. Under the SAME
       ;; session lock as the `sync!` above, so no edit can prune `served` in between.
       (store/mark-served! thread-id path (:shown page))
       (assoc page :anchors (:anchors view))))))

(defn read!
  "The whole anchored read: classify PATH, take its text, serve it. Returns
  `serve!`'s map.

  Classification happens here rather than in the tool so that `read` stays a
  dispatch and this stays a function over (session, path) -- which is what the
  tests drive.

  THE TEXT COMES FROM `harness.cap.hashline.files`, not from a bare slurp, and that is
  load-bearing rather than tidy: the checksums an edit validates against are
  computed over the text WITHOUT its byte-order mark and with line endings
  normalized. A read that slurped raw would checksum the BOM as part of line 1 and
  the first edit of every BOM'd file would be refused as drifted -- a refusal about
  a file nobody touched. One reading of a file, one set of bytes to agree on."
  [thread-id path opts]
  (let [path (store/canonical path)
        f    (File. ^String path)]
    (reading/classify f)
    (serve! thread-id path (:text (files/read-file path)) opts)))

