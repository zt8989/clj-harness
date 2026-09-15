(ns harness.hashline.serve
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
  harness.hashline.anchors/align falls back to its common-prefix alignment. Lines
  that did not move keep their names, which is what lets a model that read a file,
  then had it change underneath it, still address the lines that are still there.

  The stores are consulted under the file's own lock, so a read racing an edit of
  the same file sees one of the two states and never a mixture."
  (:require [harness.hashline.anchors :as anchors]
            [harness.hashline.reading :as reading]
            [harness.hashline.store :as store])
  (:import [java.io File]))

(defn- fresh-change
  "An alignment for CONTENT against whatever the session already knew."
  [thread-id path content {:keys [anchors line-checksums]}]
  (let [checks (anchors/line-checksums content)]
    (assoc (anchors/align {:old-anchors   anchors
                           :old-checksums line-checksums
                           :new-checksums checks
                           :path          path
                           :owned         (store/ownership thread-id)
                           :probe         (or (store/probe-of thread-id)
                                              (anchors/seed thread-id))})
           :file-checksum (anchors/file-checksum checks)
           :line-checksums checks)))

(defn serve!
  "THREAD-ID reads PATH, which must already be resolved and classified. Returns
  `harness.hashline.reading/preview`'s map, with `:anchors` added -- the file's
  anchors in line order, which the next edit will be aligned against.

  OPTS carries :offset and :limit, and the page's rows are all this returns; the
  anchors for the WHOLE file are stored regardless, because an edit is addressed
  by an anchor and must not depend on which page happened to be read."
  [thread-id path content {:keys [offset limit] :as opts}]
  (store/with-path-lock
   path
   (fn []
     (let [stored  (store/state thread-id path)
           checks  (anchors/line-checksums content)
           current (anchors/file-checksum checks)
           live    (when (and stored (= current (:file-checksum stored)))
                     stored)
           change  (if live
                     {:anchors        (:anchors live)
                      :line-checksums (:line-checksums live)}
                     (fresh-change thread-id path content stored))]
       (when-not live
         ;; Persist the alignment BEFORE the rows go out. If the write fails the
         ;; caller gets the failure and no anchors were shown; the other order
         ;; would hand out anchors the store does not know about, and the next
         ;; edit would reject every one of them.
         (store/advance! thread-id path change))
       (assoc (reading/preview content (:anchors change)
                               {:offset offset :limit limit :path path})
              :anchors (:anchors change))))))

(defn read!
  "The whole anchored read: classify PATH, take its text, serve it. Returns
  `serve!`'s map.

  Classification happens here rather than in the tool so that `read` stays a
  dispatch and this stays a function over (session, path) -- which is what the
  tests drive."
  [thread-id path opts]
  (let [f (File. ^String path)]
    (reading/classify f)
    (serve! thread-id path (reading/read-text f) opts)))

