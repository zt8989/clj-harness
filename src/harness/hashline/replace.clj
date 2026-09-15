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

  A REFUSAL IS A DELIVERY. The reason anchors beat `old_string` is not that an
  edit lands -- both schemes land edits -- it is what happens when one does NOT.
  `old_string` answers 'not found', and the model re-reads the file. Here the
  refusal carries the lines it was talking about WITH their current anchors, and
  those rows count as shown, so the very next call succeeds without a read. That is
  `drifted!` and `unseen!` below, and it is why this namespace is longer than the
  four functions its happy path needs.

  WHAT THIS NAMESPACE OWNS THAT THE OTHERS DO NOT: the decision that an edit is
  going ahead at all, and the write. The grammar is edit.clj's; the anchors and
  checksums are anchors.clj's; what a file IS -- its BOM, its line endings, its
  permissions -- is files.clj's."
  (:require [clojure.string :as str]
            [harness.hashline.anchors :as anchors]
            [harness.hashline.edit :as edit]
            [harness.hashline.files :as files]
            [harness.hashline.serve :as serve]
            [harness.hashline.store :as store]))

(def ^:private heal-rows
  "How many of the range's lines a refusal prints. Printing them at all is the
  point -- they are what makes the retry need no read -- and a range longer than
  this cannot be retried in one edit anyway. So the remainder is NAMED and left to
  `read`, rather than turned into a wall of rows nobody asked for."
  40)

(defn- context-lines
  "How many untouched lines to show either side of a change. Read per call, so a
  session that changes its harness.edn sees the new value on its next edit."
  [config]
  (long (or (:diff-context-lines config) 1)))

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

(defn target-path
  "The file an edit addresses, from the anchors rather than from an argument.

  AN ANCHOR IS THE ADDRESS. It is unique to one file for one session -- that is what
  harness.hashline.store's ownership table guarantees -- so the file is DERIVED from
  `remove_from`, and `path` is only needed when the session insists on it
  (`:require-path`, which exists for callers that want the file named explicitly).

  Falls back to the argument when the session does not hold the anchor, which is
  the shape `locate` heals below: the caller named a file, so the file is known even
  though the anchor is not. Returns nil when neither is available, which the engine
  reports as 'not read'."
  [thread-id args config]
  (let [;; The PARSED anchor, not the raw field: a pasted `anchor│content` row is
        ;; fixed up by `edit/parse`, and the ownership lookup has to see the anchor
        ;; it fixed up to or a whole class of slips would arrive here as "not read".
        anchor (try (:from (edit/parse args {:strict? false :require-path? false}))
                    (catch Throwable _ nil))
        given  (let [p (:path args)] (when (and (string? p) (not (str/blank? p))) p))]
    (or (when (string? anchor) (store/owner-of thread-id anchor))
        given)))

(defn- locate
  "The range FROM..TO addresses in THREAD-ID's view of PATH. Returns
  [range rescued?], or throws.

  RESCUED is the fallback ticket 06 asks for, and it is deliberately narrow: the
  session has a view of the file the caller named, that view names the anchor, but
  the ownership table does not -- a half-lost anchor table, with a model still
  holding names that were handed out before it was lost. Nobody is saying which
  file the anchor meant, and the caller said: PATH. The view says which line, and
  the line's content is checked to be UNIQUE in the file, so an anchor cannot be
  quietly re-attached to a twin line further down.

  It is narrow because it has to be: an anchor whose line is not unique is an
  anchor that could mean two things, and guessing between them is the exact failure
  the whole scheme exists to prevent."
  [thread-id path from to warnings owned?]
  (let [st (store/state thread-id path)]
    (cond
      ;; No view at all means this session has never been shown this file (or a
      ;; write cleared it). The path is named even though the caller may have
      ;; derived it from the anchor: the model needs to know WHICH file to read.
      (nil? st)
      (throw (ex-info (str "this session has no anchors for " path " -- it has not been"
                           " read, or a write cleared it. Call read on " path " first:"
                           " editing is addressed by anchors, and the first read is"
                           " where they come from.")
                      {:path path :anchor from :reason :not-read}))

      owned? [(edit/resolve-range thread-id path from to warnings) false]

      :else
      (if-let [r (edit/range-from-view st from to)]
        [r true]
        (throw (unresolvable! from))))))

;; ------------------------------------------------------- the healing refusals

(defn- drift
  "What changed about the range's lines since the session was shown them, or nil
  when the file is still that file.

  `:changed` holds the 0-based indices that no longer match, and `:resized` says
  the file has a different number of lines than it did -- two facts, because they
  are two different messages: a line was edited, or lines were added and removed."
  [{:keys [checksums]} text]
  (let [live (anchors/line-checksums text)
        was  (count checksums)
        now  (count live)]
    (when (or (not= was now)
              (not-every? true? (map = checksums live)))
      {:changed (vec (filter #(not= (nth checksums %) (nth live %))
                             (range (min was now))))
       :resized (when (not= was now) {:was was :now now})})))

(defn- drift-said
  "The drift, as one sentence a model can act on: how many lines are not what they
  were, and which ones."
  [path {:keys [changed resized]}]
  (let [where (cond
                (empty? changed)      nil
                (= 1 (count changed)) (str "line " (inc (first changed))
                                           " is not what it was")
                :else                 (str "lines " (inc (first changed)) "-"
                                           (inc (last changed)) " are not what they"
                                           " were (" (count changed) " lines differ)"))]
    (str path " changed after it was read: "
         (str/join ", " (remove nil? [(when resized
                                        (str "it now has " (:now resized)
                                             " lines, it had " (:was resized)))
                                      where]))
         ".")))

(defn- range-rows
  "The rows that answer 'these are the lines you meant, and these are their names
  now': the range's lines with their CURRENT anchors, in the same `anchor│content`
  shape as a read, plus a way to see the rest of a long range.

  Returns {:text ... :shown #{...}}. SHOWN matters for the same reason the diff's
  does: rows that have been printed are addressable, and rows that have not are
  not, so the caller has to record which of these went out."
  [view text {:keys [start end]} context]
  (let [lines (vec (anchors/split-lines text))
        as    (vec (:anchors view))
        total (min (count as) (count lines))
        from  (max 0 (- start context))
        to    (min total (+ start heal-rows))
        rows  (mapv (fn [i] (edit/row-of " " (nth as i) (nth lines i))) (range from to))]
    {:text  (str (str/join "\n" rows)
                 (when (< to end)
                   (str "\n     │... the range runs to line " end "; read with"
                        " offset=" (inc to) " to see the rest.")))
     :shown (into #{} (keep #(nth as % nil)) (range from to))}))

(defn- drifted!
  "Refuse an edit addressed at a file that moved underneath it, and hand back the
  lines it addressed with their CURRENT anchors.

  THE ANCHORS COME FROM THE RE-ALIGNED VIEW. `serve/sync!` brings the session's
  view up to what is on disk first, so the rows printed here name lines as they are
  NOW -- printing them from the view that was already known to be out of date would
  hand out names that address nothing, and the retry would fail the same way."
  [thread-id path range text config reason]
  (serve/sync! thread-id path text)
  (let [view (store/state thread-id path)
        {:keys [text shown]} (range-rows view text range (context-lines config))]
    (store/mark-served! thread-id path shown)
    (throw (ex-info (str reason " The lines you addressed are below, each with the"
                         " anchor that names it now -- retry with those anchors;"
                         " nothing needs to be re-read.\n\n" text)
                    {:path path :reason :drifted :shown shown}))))

(defn- unseen!
  "Refuse an edit whose range includes lines this session was never shown -- a page
  of a long file that was minted but never returned -- and SHOW THEM. A refusal
  that only pointed at `read` would be a round trip for something this call can
  already do."
  [thread-id path range text config]
  (let [view (store/state thread-id path)
        {:keys [text shown]} (range-rows view text range (context-lines config))
        idx   (:not-shown-idx range)]
    (store/mark-served! thread-id path shown)
    (throw (ex-info (str "this range includes " (count idx) " line(s) that were never"
                         " shown to you (line "
                         (str/join ", " (map inc (take 5 idx)))
                         (when (> (count idx) 5) ", ...") "). Editing a line you have"
                         " not seen is the guess anchors exist to prevent, so those"
                         " lines are below with their anchors -- retry with them and"
                         " nothing needs to be re-read.\n\n" text)
                    {:path path :anchors (:not-shown range)
                     :reason :not-shown :shown shown}))))

;; ------------------------------------------------------------------ the edit

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
        ;; Every branch above answers with a path resolved for this session and then
        ;; CANONICALIZED, because the store books anchors by path: the view, the
        ;; ownership rows and the alignment's notion of 'this file' all have to be
        ;; the same string, or a second spelling of the same path mints a second set
        ;; of anchors for it.
        owner (store/owner-of thread-id from)
        ;; The given path is RESOLVED FOR THE SESSION before it is compared with
        ;; the owner: a relative path means nothing until the binding is applied,
        ;; and comparing `g.txt` with `/proj/g.txt` refuses every edit that names
        ;; its file the way the model actually names it.
        given (let [p (:path args)]
                (when (and (string? p) (not (str/blank? p)))
                  (store/canonical (resolve-path p))))
        path  (cond
                ;; A named file that disagrees with the anchor is refused rather
                ;; than reconciled: editing the file you named while addressing
                ;; lines in another is the mistake this check exists for.
                (and owner given (not= given (store/canonical owner)))
                (throw (ex-info (str "`path` says " given ", but that anchor names a"
                                     " line in " owner " in this session. Anchors"
                                     " resolve the target; drop the path, or name the"
                                     " file the anchors came from.")
                                {:path given :owner owner :reason :path-mismatch}))

                owner owner
                given given
                :else (throw (unresolvable! from)))]
    (store/with-path-lock
     path
     (fn []
       (let [{:keys [text bom ending mode]} (files/read-file path)
             [range rescued?] (locate thread-id path from to warnings (some? owner))
             d (drift range text)]
         (when rescued?
           (swap! warnings conj
                  (str "These anchors were not in this session's ownership table; the"
                       " file you named was confirmed by the content of the lines they"
                       " point at.")))
         ;; The file moving is checked FIRST: it is the failure that invalidates the
         ;; range itself, so a range that drifts AND includes an unshown line is a
         ;; drift, not an unshown line.
         (when d
           (drifted! thread-id path range text config (drift-said path d)))
         (when (seq (:not-shown-idx range))
           (unseen! thread-id path range text config))
         (when rescued?
           ;; The ownership rows are what the NEXT edit derives its path from, so a
           ;; rescue that did not restore them would work once and then fail.
           (store/claim-anchors! thread-id path (:anchors range)))
         (let [;; Dedup first, then compose: the lines that survive dedup are what
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
             (let [;; What undo needs to put this file back, gathered once: the text on
                   ;; both sides, the encoding it had, the anchors that named it, which
                   ;; of those the model had SEEN, and the permission bits. `served`
                   ;; and `mode` are here because an undo restores the anchors too --
                   ;; see harness.hashline.store and harness.hashline.undo.
                   undo {:prior-text     text
                         :bom            bom
                         :ending         ending
                         :anchors        (:anchors range)
                         :served         (:served range)
                         :resulting-text text'
                         :mode           (files/mode-bits mode)}]
               (store/record-undo! path undo)
               (try
                 (files/write-file! path text' {:bom bom :ending ending :mode mode})
                 (catch Throwable t
                   ;; The file is still the file it was (the write is a temp+rename),
                   ;; so the undo record describing the edit that did not happen is
                   ;; taken back with it.
                   (store/clear-undo! path)
                   (throw t)))
               (store/advance-with-undo! thread-id path change undo)
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
                   text))))))))))
