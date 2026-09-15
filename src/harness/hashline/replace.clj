(ns harness.hashline.replace
  "`replace`: the tool that makes anchors worth having.

  IT IS A PIPELINE, and this namespace is the order the steps have to happen in:

    1. parse the payload (harness.hashline.edit) -- and fix or refuse the slips
    2. resolve the two anchors against what this session was shown
    3. validate the range against the file as it is on disk
    4. compute the new text and the new anchors
    5. record the undo, write the file, land the store -- one transaction, one
       critical section
    6. hand back the diff, whose `+` and context rows are the model's next edit

  UNDER THE FILE'S LOCK, THE WHOLE WAY THROUGH. Two edits to one file in one turn
  would otherwise each read the same base state and one of them would vanish with
  both reporting success -- see harness.hashline.store, which explains why the
  store's own isolation does not cover a read-check-write SEQUENCE.

  WHAT THIS NAMESPACE OWNS THAT THE OTHERS DO NOT: the decision that an edit is
  going ahead at all, and the write. The grammar is edit.clj's; the anchors and
  checksums are anchors.clj's; what a file IS -- its BOM, its line endings, its
  permissions -- is files.clj's."
  (:require [clojure.string :as str]
            [harness.hashline.anchors :as anchors]
            [harness.hashline.edit :as edit]
            [harness.hashline.files :as files]
            [harness.hashline.store :as store]))

(defn- context-lines
  "How many untouched lines to show either side of a change. Read per call, so a
  session that changes its harness.edn sees the new value on its next edit."
  [config]
  (long (or (:diff-context-lines config) 1)))

(defn- verify!
  "Refuse the edit if the file on disk is not the file the session was shown, or if
  the range includes a line the model has never seen.

  Two different failures, and the distinction is worth keeping: one is the FILE
  having moved (somebody else's edit, an editor save), the other is the MODEL
  addressing a line it was not shown (a page of a long file it never asked for).
  A retry fixes the second by reading the range; the first needs a fresh read of
  the whole file, because every anchor may have moved. Ticket 06 turns both of
  these into answers the model can act on without a round trip; here they are
  named refusals, which is the honest half of the same thing."
  [path range text]
  (let [stored (:checksums range)
        live   (anchors/line-checksums text)]
    (when (or (not= (count stored) (count live))
              (not-every? true? (map = stored live)))
      (throw (ex-info (str "the file changed after it was read, so these anchors no"
                           " longer describe it. Call read for current anchors.")
                      {:path path :reason :drifted})))
    (when (seq (:not-shown range))
      (throw (ex-info (str "this range includes " (count (:not-shown range))
                           " line(s) that were never shown to you ("
                           (str/join ", " (take 3 (:not-shown range)))
                           "). Read the range you mean and edit again -- editing a line"
                           " you have not seen is the guess anchors exist to prevent.")
                      {:path path :anchors (:not-shown range) :reason :not-shown})))))

(defn target-path
  "The file an edit addresses, from the anchors rather than from an argument.

  AN ANCHOR IS THE ADDRESS. It is unique to one file for one session -- that is what
  harness.hashline.store's ownership table guarantees -- so the file is DERIVED from
  `remove_from`, and `path` is only needed when the session insists on it
  (`:require-path`, which exists for callers that want the file named explicitly).
  When a path IS given it must agree with the anchor's owner: editing the file you
  named while addressing lines in another is the exact mistake that check is for.

  Returns nil when the anchor belongs to nothing; `unresolvable!` says which of
  the two ways that happened and what to do about it."
  [thread-id args config]
  (let [;; The PARSED anchor, not the raw field: a pasted `anchor│content` row is
        ;; fixed up by `edit/parse`, and the ownership lookup has to see the anchor
        ;; it fixed up to or a whole class of slips would arrive here as "not read".
        anchor (try (:from (edit/parse args {:strict? false :require-path? false}))
                    (catch Throwable _ nil))
        given  (:path args)
        owner  (when (string? anchor) (store/owner-of thread-id anchor))]
    (if-let [p (and (string? given) (not (str/blank? given)) given)]
      (if (and owner (not= (store/canonical p) (store/canonical owner)))
        (throw (ex-info (str "`path` says " p ", but that anchor names a line in "
                             owner " in this session. Anchors resolve the target;"
                             " drop the path, or name the file the anchors came from.")
                        {:path p :owner owner :reason :path-mismatch}))
        p)
      owner)))

(defn- unresolvable!
  "The error for an anchor this session cannot turn into a file -- two shapes, and
  the difference decides what the model does next.

  A name the anchor table does not hold was never an anchor: it is a letter typed
  wrong or a number invented, and no amount of reading will turn it into a file, so
  'call read' would be an instruction to retry the same mistake. A real anchor this
  session does not hold, on the other hand, is exactly what a read fixes -- the
  window moved, the process restarted, or the model is addressing a file it has not
  opened. Both are refused; only one is a retry."
  [anchor]
  (if (anchors/anchor? anchor)
    (ex-info (str "no anchors here name a file in this session. Call read on the file"
                  " you mean first -- editing is addressed by anchors, and read is"
                  " where they come from.")
             {:anchor anchor :reason :not-read})
    (ex-info (str "`" anchor "` is not an anchor. The names this scheme hands out are"
                  " four letters from a fixed table, and this one is not among them"
                  " (the anchor table has no digits in it, and the letters have to be"
                  " spelled exactly). Copy the anchor from the left of the read row"
                  " you mean -- reading the file again is what produces the name.")
             {:anchor anchor :reason :not-an-anchor})))

(defn perform!
  "Run one edit for THREAD-ID.

  ARGS is the tool's argument map; CONFIG is the session's resolved :editing map
  (see harness.editing). Returns a STRING -- what the model reads -- or throws a
  named error. The caller resolves the returned path for the session, exactly as
  `read` does -- a fence-marked call parks before this ever runs, and what runs
  inside is the same path arithmetic the read used.

  PATH-FN is `(fn [p] p)` in production and a harness's own indirection in tests;
  it exists so the file operations are not spelled twice."
  [thread-id resolve-path args config]
  (let [warnings (atom [])
        {:keys [from to lines]} (edit/parse args {:strict?       (:strict-input config)
                                                  :require-path? (:require-path config)
                                                  :warnings      warnings})
        path (or (target-path thread-id args config)
                 (throw (unresolvable! from)))
        path (resolve-path path)]
    (store/with-path-lock
     path
     (fn []
       (let [{:keys [text bom ending mode]} (files/read-file path)
             range  (edit/resolve-range thread-id path from to warnings)
             _      (verify! path range text)
             ;; Dedup first, then compose: the lines that survive dedup are what
             ;; the file actually receives, and the span has to describe the edit
             ;; that was MADE, not the one that was asked for.
             dedup  (edit/dedup-edges (vec (:lines (edit/lines-of text))) range lines
                                      (:boundary-dedup config))
             lines  (:lines dedup)
             [text' span] (edit/apply-range text range lines)
             _      (edit/check-not-empty! text text')
             after   (anchors/split-lines text')
             checks  (anchors/line-checksums text')
             change  (assoc (anchors/align {:old-anchors   (:anchors range)
                                            :old-checksums (:checksums range)
                                            :new-checksums checks
                                            :spans         [span]
                                            :path          path
                                            :owned         (store/ownership thread-id)
                                            :probe         (or (store/probe-of thread-id)
                                                               (anchors/seed thread-id))})
                            :file-checksum (anchors/file-checksum checks)
                            :line-checksums checks
                            :served        (:served range)
                            :served?       true)]
         (if (= text text')
           ;; Nothing changed: no write, no undo record, and the undo history that
           ;; was there stays there -- a no-op must not cost the model its ability
           ;; to take back the edit before it.
           (str "No change: the replacement is identical to what is already there, so"
                " nothing was written to " path ".")
           (do
             (store/record-undo! path {:prior-text     text
                                       :bom            bom
                                       :ending         ending
                                       :anchors        (:anchors range)
                                       :resulting-text text'
                                       :mode           nil})
             (try
               (files/write-file! path text' {:bom bom :ending ending :mode mode})
               (catch Throwable t
                 ;; The file is still the file it was (the write is a temp+rename),
                 ;; so the undo record describing the edit that did not happen is
                 ;; taken back with it.
                 (store/clear-undo! path)
                 (throw t)))
             (store/advance-with-undo!
              thread-id path change
              {:prior-text     text
               :bom            bom
               :ending         ending
               :anchors        (:anchors range)
               :resulting-text text'
               :mode           nil})
             (let [{:keys [text shown]}
                   (edit/ok-message path
                                    {:before   (:before span)
                                     :after    after
                                     :anchors  (:anchors change)
                                     :span     span
                                     :context  (context-lines config)
                                     :stripped (:stripped dedup)
                                     :added    (count lines)
                                     :removed  (- (:end range) (:start range))})]
               ;; ...and tell the store which anchors that answer put in the model's
               ;; hands. Without it the very next edit is refused, because a freshly
               ;; minted anchor is owned but has not been SHOWN to anyone until the
               ;; row carrying it goes out -- and these rows just did.
               (store/mark-served! thread-id path shown)
               ;; Any fix made along the way is REPORTED. A silent interpretation is
               ;; how a model learns to keep making the slip -- it never found out.
               (if (seq @warnings)
                 (str "Note: " (str/join " " @warnings) "\n\n" text)
                 text)))))))))
