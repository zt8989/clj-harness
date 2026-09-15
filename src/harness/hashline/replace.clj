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
            [harness.hashline.insert :as insert]
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
  the anchor the payload carries (`remove_from` for a replace, `anchor` for an
  insert), and `path` is only needed when the session insists on it
  (`:require-path`, which exists for callers that want the file named explicitly).

  Falls back to the argument when the session does not hold the anchor, which is
  the shape `locate` heals below: the caller named a file, so the file is known even
  though the anchor is not. Returns nil when neither is available, which the engine
  reports as 'not read'."
  [thread-id args config]
  (let [;; The PARSED anchor where there is one, so that a pasted `anchor│content`
        ;; row is fixed up before the ownership lookup -- otherwise a whole class of
        ;; slips would arrive here as "not read". The tolerant field read is the
        ;; fallback: a payload the parser refuses still names its file, and the
        ;; refusal it deserves comes a moment later from `plan-edit`.
        anchor (or (try (:from (edit/parse args {:strict? false :require-path? false}))
                       (catch Throwable _ nil))
                   (edit/bare-anchor (or (:remove_from args) (:replace_from args)
                                         (:anchor args))))
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

(defn- wrapped
  "An edit's error, said in the terms of the BATCH it arrived in: which call of how
  many, and what was wrong with it.

  A batch is refused WHOLE when any of its edits is refused -- the ticket's rule,
  and the honest one: the model asked for several changes to one file as one
  thought, and applying the legal half of a thought is not a service. So the
  refusal has to say which edit it was, or the model has to guess which of its
  three calls to rewrite.

  PATH may be nil, and then the sentence drops the location: a call refused before
  anybody worked out which file it meant -- a payload too malformed to name one --
  has none to give, and inventing one would be worse than saying less."
  [i n path t]
  (ex-info (str "edit " (inc i) " of " n
                (when path (str " (addressed at " path ")"))
                " was refused, so NOTHING was written -- this message's edits to that"
                " file are one commit: " (ex-message t))
           (assoc (ex-data t) :batch-index i :batch-size n :reason :batch-refused)))

(defn- parse-any
  "ARGS as the edit it means, whichever of the two anchor tools it is for.

  `insert` carries an `anchor`, `replace` carries `remove_from`; neither name is a
  key of the other's payload, so the payload says which grammar applies. Parsed
  FIRST, before any file is looked up, because the refusals here are about the
  payload and a malformed call should hear about its own arguments rather than
  about a file nobody can yet name."
  [args config warnings]
  (if (contains? args :anchor)
    (insert/parse args {:warnings      warnings
                        :require-path? (:require-path config)
                        :strict?       (:strict-input config)})
    (edit/parse args {:strict?       (:strict-input config)
                      :require-path? (:require-path config)
                      :warnings      warnings})))

(defn- anchor-of
  "The anchor a PARSED edit addresses its file by."
  [parsed]
  (or (:anchor parsed) (:from parsed)))

(defn- resolve-target
  "The file this edit is about, as a canonical path -- from the anchor when the
  session holds it, from the argument otherwise, and refused when the two disagree.

  A named file that disagrees with the anchor is refused rather than reconciled:
  editing the file you named while addressing lines in another is the mistake this
  check exists for."
  [thread-id resolve-path args anchor]
  (let [owner (when anchor (store/owner-of thread-id anchor))
        ;; Resolved for the SESSION before it is compared with the owner: a relative
        ;; path means nothing until the binding is applied, and comparing `g.txt`
        ;; with `/proj/g.txt` would refuse every edit that names its file the way
        ;; the model actually names it.
        given (let [p (:path args)]
                (when (and (string? p) (not (str/blank? p)))
                  (store/canonical (resolve-path p))))
        path  (cond
                (and owner given (not= given (store/canonical owner)))
                (throw (ex-info (str "`path` says " given ", but that anchor names a"
                                     " line in " owner " in this session. Anchors"
                                     " resolve the target; drop the path, or name the"
                                     " file the anchors came from.")
                                {:path given :owner owner :reason :path-mismatch}))

                owner owner
                given given
                :else (throw (unresolvable! anchor)))]
    (store/canonical (resolve-path path))))

(defn- guard!
  "The checks that decide one edit may go ahead, against TEXT as the base:

    * the range's anchors are this session's (or are rescued by content, ticket 06)
    * the file is still the file the session was shown (else the healing refusal)
    * no line of the range is one the model has never been shown

  Returns the range, with the rescue's warning collected. Refusals THROW, which for
  a batch means the caller wraps them with the edit's index -- see `wrapped`."
  [thread-id path from to text config warnings owner?]
  (let [[range rescued?] (locate thread-id path from to warnings owner?)]
    (when rescued?
      (swap! warnings conj
             (str "These anchors were not in this session's ownership table; the"
                  " file you named was confirmed by the content of the lines they"
                  " point at.")))
    ;; The file moving is checked FIRST: it is the failure that invalidates the
    ;; range itself, so a range that drifts AND includes an unshown line is a
    ;; drift, not an unshown line.
    (when-let [d (drift range text)]
      (drifted! thread-id path range text config (drift-said path d)))
    (when (seq (:not-shown-idx range))
      (unseen! thread-id path range text config))
    (when rescued?
      ;; The ownership rows are what the NEXT edit derives its path from, so a
      ;; rescue that did not restore them would work once and then fail.
      (store/claim-anchors! thread-id path (:anchors range)))
    range))

(defn- undo-record
  "What undo needs to put this file back, gathered once: the text on both sides,
  the encoding it had, the anchors that named it, which of those the model had
  SEEN, and the permission bits.

  `served` and `mode` are here because an undo restores the anchors and the
  permission bits as well as the text -- see harness.hashline.store and
  harness.hashline.undo."
  [text text' range bom ending mode]
  {:prior-text     text
   :bom            bom
   :ending         ending
   :anchors        (:anchors range)
   :served         (:served range)
   :resulting-text text'
   :mode           (files/mode-bits mode)})

(defn- write-and-land!
  "The critical section's second half: record the undo, write the file, land the
  store -- and if the write throws, take the undo record back with it.

  A batch and a single edit do exactly this, in exactly this order, which is why
  it is one function: an edit whose store half landed but whose file write did not
  would describe a change that did not happen."
  [thread-id path change undo text' bom ending mode]
  (store/record-undo! path undo)
  (try
    (files/write-file! path text' {:bom bom :ending ending :mode mode})
    (catch Throwable t
      ;; The file is still the file it was (the write is a temp+rename), so the
      ;; undo record describing the edit that did not happen is taken back with it.
      (store/clear-undo! path)
      (throw t)))
  (store/advance-with-undo! thread-id path change undo))

;; ------------------------------------------------------------------ the batch

;; SEVERAL EDITS TO ONE FILE IN ONE MESSAGE ARE ONE COMMIT, and this is where that
;; is carried out. The reason is not tidiness: a turn's tool calls run
;; CONCURRENTLY (`harness.loop/drive!`), and two edits to one file each validate
;; against the state the session was shown and each compute their own new content.
;; Run independently, the later write wins and the earlier one vanishes -- with
;; BOTH reporting success. That is silent data loss, and no amount of locking
;; around one edit fixes it, because the two edits are individually correct.
;;
;; So the edits of one message are applied as one patch. Every edit is validated
;; against the state BEFORE the message (not against the previous edit's result),
;; the ranges must not overlap -- which makes "both edits touched one line" a
;; REFUSAL rather than a question about which ran first -- and the whole thing is
;; one write, one anchor advance and one undo record. The model therefore gets one
;; diff, one set of anchors, and one undo for the thought it had.
;;
;; WHO APPLIES IT is decided by `harness.tools/batch-plan`, which is where the
;; message's calls are all visible: the LAST call of a group returns the merged
;; answer, and the earlier ones say they were merged. Same node, same decision --
;; no call has to wait for another, so a refusal or a park anywhere in the message
;; cannot strand the rest.

(defn merged-note
  "What a batch member that is not the applier answers with: enough to say the edit
  was not dropped, and where its outcome will appear."
  [{:keys [index size path]}]
  (str "Edit " (inc index) " of " size " on " path " is part of ONE commit with the"
       " other edits in this message: they were checked against the same state, and"
       " they are written together (or not at all). The merged diff, the resulting"
       " anchors and the undo record are in the answer to the last of them."))

(defn- plan-edit
  "ONE call of a batch, resolved against the batch's base TEXT: {:range :lines
  :stripped :args :path}, ready for the arithmetic.

  WHICH SHAPE IT IS comes from the payload, not from the tool's name: an `insert`
  carries an `anchor`, a `replace` carries `remove_from`, and neither key appears in
  the other's grammar. One code path for the batch arithmetic, two grammars for the
  model -- and it is what lets an insert and a replace on one file be committed
  together.

  Every refusal carries the edit's index (see `wrapped`), because a batch is refused
  whole and the model has to know which of its calls to rewrite."
  [thread-id path text args config warnings i n]
  (try
    (let [{:keys [from to anchor direction lines]} (parse-any args config warnings)
          owner? (some? (store/owner-of thread-id (or anchor from)))]
      (if anchor
        (let [target (guard! thread-id path anchor anchor text config warnings owner?)
              ;; AN EMPTY FILE'S ONE EMPTY LINE IS THE FILE, not a line to insert
              ;; after: reading it gives a single anchored row so that the file is
              ;; addressable at all (ticket 04), and `after` on it has to mean 'put
              ;; this content in the file' rather than 'leave a blank line first'.
              ;; Any other file -- including one holding nothing but blank lines --
              ;; is taken at its word.
              at     (cond
                       (not= :after direction) (:start target)
                       (= "" text)             0
                       :else                   (:end target))]
          {:range    {:start at :end at
                      :anchors (:anchors target) :checksums (:checksums target)
                      :served (:served target)}
           :lines    (vec lines)
           :stripped 0
           :args     args
           :path     path})
        (let [range (guard! thread-id path from to text config warnings owner?)
              dedup (edit/dedup-edges (vec (:lines (edit/lines-of text))) range lines
                                      (:boundary-dedup config))]
          {:range    range
           :lines    (:lines dedup)
           :stripped (:stripped dedup)
           :args     args
           :path     path})))
    (catch Throwable t
      (throw (wrapped i n path t)))))

(defn- spans-of
  "The batch's edits as `harness.hashline.anchors/align` reads them: ascending,
  disjoint, and in BOTH coordinate systems.

  The new-side offsets are the running delta -- each edit shifts everything after
  it by (new length - old length) -- and that is the whole of 'apply several edits
  at once': the base is indexed by OLD positions, and every edit's place in the
  result depends on how much the edits before it added or removed."
  [plans]
  (loop [ps (sort-by (comp :start :range) plans), delta 0, out []]
    (if-not (seq ps)
      out
      (let [{:keys [range lines]} (first ps)
            {:keys [start end]} range
            len (count lines)]
        (recur (rest ps)
               (+ delta (- len (- end start)))
               (conj out {:old-start start :old-end end
                          :new-start (+ start delta) :new-end (+ start delta len)}))))))

(defn- disjoint!
  "Refuse a batch whose edits overlap, naming the two edits and the lines they both
  claim.

  AN OVERLAP IS AN ERROR, NOT AN ORDERING. Two edits to the same line, arriving in
  one message, are a mistake the model can fix -- and could not be resolved by
  picking a winner, because nothing in the payload says which was meant. So this is
  the one place a batch is refused for a reason that is about the batch rather than
  about one of its edits, and the message says so: which two, and where.

  EMPTY RANGES NEVER OVERLAP. An `insert` occupies no line, so it can sit inside
  the region another edit replaces, or share a position with another insert, and the
  result is still well defined (call order decides, and `sort-by` is stable). What
  is refused is two edits that both claim a line."
  [plans path]
  (let [sorted (sort-by (comp :start :range) plans)]
    (doseq [[{:keys [range]} {next-range :range}] (partition 2 1 sorted)
            :when (and (pos? (- (:end range) (:start range)))
                       (pos? (- (:end next-range) (:start next-range)))
                       (> (:end range) (:start next-range)))]
      (throw (ex-info (str "two edits in this message both claim lines "
                           (inc (:start next-range)) "-" (:end range) " of " path
                           ", so NOTHING was written. Edits in one message are one"
                           " commit and their line ranges must not overlap; send"
                           " them as separate messages (the first one's answer"
                           " gives you current anchors for the second), or address"
                           " the whole region with one edit.")
                      {:path path :reason :batch-overlap
                       :ranges [range next-range]}))))
  plans)

(defn- assemble
  "The batch's whole effect on TEXT, as [text' spans].

  Built by walking the spans in order and copying the untouched lines between them
  -- NOT by applying edit after edit, because each edit's positions are OLD
  positions and applying them one at a time would index a text they were never
  computed against. The new lines of each span come from the plan that owns it,
  found by old-start, which is unique precisely because the ranges are disjoint.

  The trailing-newline fact is carried over the same way the single edit carries
  it: a file that ended in a newline still does, and one that did not still does
  not."
  [text plans spans]
  (let [lines (vec (anchors/split-lines text))
        owner (into {} (map (fn [p] [(:start (:range p)) (:lines p)])) plans)
        out   (loop [ss spans, cursor 0, acc []]
                (if-not (seq ss)
                  (into acc (subvec lines cursor))
                  (let [{:keys [old-start old-end]} (first ss)]
                    (recur (rest ss) old-end
                           (into (into acc (subvec lines cursor old-start))
                                 (get owner old-start))))))]
    [(str (str/join "\n" out)
          (when (and (str/ends-with? text "\n") (pos? (count out))) "\n"))
     spans]))

(defn perform-edits!
  "Run ONE OR MORE edits to one file as one commit. ARGS-VEC is the message's edits
  in call order -- the tools pass one when the message had one; `harness.tools`
  passes all of them when it found several addressed at the same file. Returns the
  merged answer, or throws a refusal naming the edit that failed.

  THE BASE IS THE STATE BEFORE THE MESSAGE. Every edit is resolved against the text
  as the message found it, so an edit's anchors mean what they meant when the model
  wrote them -- which is what makes overlapping ranges detectable instead of a
  question about scheduling.

  WHICH FILE it is about comes from the first edit: PARSED FIRST, so a malformed
  payload is refused on its own terms (naming its own argument) rather than as a
  failure to locate a file. The caller has already grouped these by target
  (`harness.tools/turn-plan`), so a mismatch here would mean the grouping was wrong
  rather than that this call should do something different."
  [thread-id resolve-path args-vec config]
  (let [n        (count args-vec)
        warnings (atom [])
        first-    (try (parse-any (first args-vec) config warnings)
                       (catch Throwable t
                         (throw (wrapped 0 n (let [p (:path (first args-vec))]
                                               (when (string? p) p))
                                  t))))
        path     (resolve-target thread-id resolve-path (first args-vec)
                                 (anchor-of first-))]
    (store/with-path-lock
     path
     (fn []
       (let [{:keys [text bom ending mode]} (files/read-file path)
             plans (disjoint! (vec (map-indexed
                                    (fn [i a]
                                      (plan-edit thread-id path text a config warnings
                                                 i n))
                                    args-vec))
                              path)
             spans (spans-of plans)
             [text' spans] (assemble text plans spans)
             base  (:range (first plans))
             _     (edit/check-not-empty! text text')
             after  (anchors/split-lines text')
             checks (anchors/line-checksums text')
             change (assoc (anchors/align {:old-anchors   (:anchors base)
                                           :old-checksums (:checksums base)
                                           :new-checksums checks
                                           :spans         spans
                                           :path          path
                                           :owned         (store/ownership thread-id)
                                           :probe         (or (store/probe-of thread-id)
                                                              (anchors/seed thread-id))})
                           :file-checksum (anchors/file-checksum checks)
                           :line-checksums checks
                           :served        (:served base)
                           :served?       true)]
         (if (= text text')
           (str "No change: "
                (if (= 1 n)
                  "the edit adds up to what is already there"
                  (str "the " n " edits in this message add up to what is already"
                       " there"))
                ", so nothing was written to " path
                (when (> n 1) " (and the undo history is untouched)")
                ".")
           (do
             (write-and-land! thread-id path change
                              (undo-record text text' base bom ending mode)
                              text' bom ending mode)
             (let [{:keys [text shown]}
                   (edit/ok-message
                    path
                    {:before   (vec (anchors/split-lines text))
                     :after    after
                     :anchors  (:anchors change)
                     :spans    spans
                     :context  (context-lines config)
                     :stripped (reduce + 0 (map :stripped plans))
                     :added    (reduce + 0 (map (comp count :lines) plans))
                     :removed  (reduce + 0 (map (fn [p] (- (:end (:range p))
                                                           (:start (:range p))))
                                                plans))})]
               (store/mark-served! thread-id path shown)
               (if (seq @warnings)
                 (str "Note: " (str/join " " @warnings) "\n\n" text)
                 text)))))))))
(defn perform!
  "Run ONE anchor edit for THREAD-ID -- a `replace` or an `insert`, told apart by the
  payload (see `plan-edit`).

  ARGS is the tool's argument map; CONFIG is the session's resolved :editing map
  (see harness.editing). Returns a STRING -- what the model reads -- or throws a
  named error. The caller resolves the returned path for the session, exactly as
  `read` does -- a fence-marked call parks before this ever runs, and what runs
  inside is the same path arithmetic the read used.

  RESOLVE-PATH is `harness.project/resolve-path` partialled on the thread in
  production and a harness's own indirection in tests; it exists so the file
  operations are not spelled twice.

  SEVERAL edits to one file in one message arrive as `perform-edits!` instead -- see
  the batch section above for why they cannot simply be called one after another. A
  single edit is that function with one element, and what differs is one clause of
  the answer: 'No change: ...' reads differently for one edit than for four, and
  nothing else changes."
  [thread-id resolve-path args config]
  (perform-edits! thread-id resolve-path [args] config))
