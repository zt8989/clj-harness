(ns harness.hashline.edit
  "One anchor-addressed edit: the payload's grammar, the input slips that are
  fixed rather than refused, and the row-shaped answer that hands the model its
  next edit.

  THE PAYLOAD IS THREE FIELDS. `remove_from` and `remove_to` are anchors -- the
  first and last line of the range, the same anchor twice for one line -- and
  `replacement_lines` is one string per new line, with no `│` and no embedded
  newlines. An empty array deletes the range; an array holding one empty string is
  one blank line.

  WHY THE ANSWER IS A DIFF AND NOT 'edited'. A model that has just changed a file
  immediately wants to change the line next to the one it changed. With string
  replacement that costs a re-read of the whole file, every time -- the single
  biggest cost of the old scheme, and the reason anchors are worth it at all.
  So the answer carries the changed region as `+`/`-`/context rows, each with the
  CURRENT anchor for its line: the `+` rows and the context rows are immediately
  addressable, and no read is needed. The `-` rows' anchors are gone (their lines
  are), which is why their anchor column is blanked -- a model that copied a stale
  anchor because it looked available would be back to guessing.

  THE SLIPS ARE FIXED, AND SAID SO. A model pastes the whole JSON array into one
  element, embeds `\\n`, copies a row's `anchor│` prefix, or keeps the `+` from a
  diff preview. Each of those has exactly one sensible reading, so it is applied
  and REPORTED, with `:strict-input true` available for the sessions that would
  rather see the slip refused than silently interpreted.

  WHAT IT WILL NOT FIX. A NUL byte (a text file cannot hold one, and writing it
  would break every later read), emptying a non-empty file (that is `write`'s job,
  not an edit's), and -- per `:boundary-dedup` -- a replacement that re-includes
  the line at the edge of its own range, which is either stripped, refused, or
  taken literally depending on how the session is configured."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [harness.hashline.anchors :as anchors]
            [harness.hashline.store :as store]))

;; ------------------------------------------------------------ the payload

(def separators
  "What a `│`-prefixed anchor looks like when it arrives where it should not: a
  row pasted out of read output. Trimmed from an argument that is supposed to be a
  bare anchor, and from the head of a replacement line."
  #"^([A-Za-z0-9]{4})│")

(defn- strip-row-prefix
  "S with a leading `anchor│` removed, or [S nil]."
  [^String s]
  (if-let [m (re-matches #"^([A-Za-z0-9]{4}│)([\s\S]*)$" s)]
    [(nth m 2) (subs (nth m 1) 0 4)]
    [s nil]))

(defn- strip-diff-marker [^String s]
  (if (and (pos? (.length s))
           (contains? #{\+ \- \space} (.charAt s 0))
           (>= (.length s) 5)
           (re-matches #"[A-Za-z0-9]{4}│.*" (subs s 1)))
    [(subs s 1) (.charAt s 0)]
    [s nil]))

(defn- extract-anchor
  "S -> [anchor stripped], where ANCHOR is a four-character anchor or nil and
  STRIPPED lists what had to be removed to find it.

  Three shapes arrive, each with exactly one sensible reading: the bare anchor; a
  whole pasted ROW (`anchor│content`, whose first four characters are the anchor);
  and a diff row (`+anchor│...`). Nothing else is an anchor, and `anchor-arg`
  turns that nil into a message that names the mistake."
  [s]
  (let [t (str/trim s)]
    (if (re-matches #"[A-Za-z0-9]{4}" t)
      [t []]
      (let [[s1 mark] (strip-diff-marker t)
            [_ row]   (strip-row-prefix (str/trim s1))]
        (if row
          [row (cond-> []
                 mark (conj (str "a `" mark "` diff marker"))
                 true (conj (str "a pasted `" row "│...` row")))]
          [nil []])))))

(defn- anchor-arg
  "One of the two anchor fields, as a bare anchor, with whatever was pasted around
  it stripped and reported."
  [k v warnings]
  (when-not (string? v)
    (throw (ex-info (str "`" (name k) "` must be a 4-character anchor from a read row"
                         " (the text before the `│`); got " (pr-str v) ".")
                    {:argument k :value v :reason :bad-anchor})))
  (let [[anchor stripped] (extract-anchor v)]
    (when (seq stripped)
      (swap! warnings conj (str "Stripped " (str/join " and " stripped)
                                " from `" (name k) "`; pass the bare anchor.")))
    (or anchor
        (let [t (str/trim v)]
          (throw (ex-info
                  (cond
                    ;; A line number is the mistake worth its own message: the model
                    ;; is addressing a line by counting rather than by the name it
                    ;; was handed.
                    (re-matches #"\d+" t)
                    (str "`" (name k) "` is a line number (" (pr-str v) "), not an anchor."
                         " Copy the 4-character anchor from the left of the row you"
                         " mean -- line numbers are never used to edit.")

                    (str/includes? t "\n")
                    (str "`" (name k) "` looks like several pasted lines. Use only the"
                         " first anchor as `remove_from` and the last as `remove_to`;"
                         " the new content goes in `replacement_lines`.")

                    :else
                    (str "`" (name k) "` must be a 4-character anchor (e.g. \"Hasu\");"
                         " got " (pr-str v) "."))
                  {:argument k :value v :reason :bad-anchor}))))))

(defn- replacement-arg
  "`replacement_lines` as a vector of strings, with the four auto-fixes applied and
  reported."
  [v warnings]
  (cond
    (nil? v)
    (throw (ex-info "`replacement_lines` is required: an array with one string per"
                    " new line, or [] to delete the range."
                    {:argument :replacement_lines :reason :missing}))

    (not (sequential? v))
    (throw (ex-info (str "`replacement_lines` must be an array of strings, one per line"
                         " ([] deletes the range); got " (pr-str v) ".")
                    {:argument :replacement_lines :value v :reason :not-an-array}))

    :else
    (vec (mapcat (fn [i line]
                   (cond
                     (not (string? line))
                     (throw (ex-info (str "`replacement_lines` element " (inc i) " is "
                                          (pr-str line) "; every element must be a string"
                                          " holding one line.")
                                     {:argument :replacement_lines :index i
                                      :value line :reason :not-a-string}))

                     ;; The whole array pasted into one element.
                     (and (str/starts-with? (str/trim line) "[")
                          (str/ends-with? (str/trim line) "]"))
                     (let [parsed (try (json/read-str (str/trim line))
                                       (catch Exception _ nil))]
                       (if (and (sequential? parsed) (every? string? parsed))
                         (do (swap! warnings conj
                                    (str "Unwrapped a JSON array pasted into `replacement_lines`"
                                         " element " (inc i) "."))
                             parsed)
                         [line]))

                     ;; Embedded newlines: one element per line is the contract,
                     ;; and splitting is the reading that was meant.
                     (str/includes? line "\n")
                     (do (swap! warnings conj
                                (str "Split embedded newlines in `replacement_lines` element "
                                     (inc i) " into one line each."))
                         (str/split (str/replace line "\r\n" "\n") #"\n" -1))

                     :else
                     (let [[s _] (strip-diff-marker line)
                           [s2 a] (strip-row-prefix s)]
                       (when a
                         (swap! warnings conj
                                (str "Stripped a `" a "│` prefix from `replacement_lines`"
                                     " element " (inc i) ".")))
                       [(or s2 s)])))
                 (range) v))))

(defn- check-nul!
  "A NUL byte cannot be written into a text file without breaking every later read
  and edit of it, and no fix makes one acceptable -- so this is a refusal, and the
  message says why rather than just refusing."
  [lines]
  (when-let [i (first (keep-indexed (fn [i l] (when (str/includes? l "\u0000") i)) lines))]
    (throw (ex-info (str "`replacement_lines` element " (inc i)
                         " contains a NUL byte (U+0000). A text file cannot hold one:"
                         " it would make every later read and edit of this file"
                         " unreliable. Remove it and retry ([] deletes the range).")
                    {:argument :replacement_lines :index i :reason :nul}))))

(defn parse
  "ARGS (the tool's argument map) as the edit it means: {:from :to :lines
  :warnings}. Throws a named error for anything that is not an edit.

  STRICT? comes from the session's `:strict-input` and changes the treatment of
  the four auto-fixes: with it on, anything that WOULD have been fixed is refused
  instead, listing every fix it declined to make. That is for a session that would
  rather the model wrote it again than have it interpreted.

  A caller that wants to report the fixes passes its own atom as `:warnings`; the
  one that does not (a caller that only wants the anchor) gets a throwaway. This
  was a throwaway for everyone until the answer was found not to mention the fixes
  it had made -- the caller's atom was being shadowed here, so the collected
  warnings went nowhere."
  [args {:keys [strict? require-path? warnings]}]
  (let [warnings (or warnings (atom []))
        known    #{"remove_from" "remove_to" "replacement_lines" "path"
                   "replace_from" "replace_to" "file_path"}
        ;; `known` holds STRINGS, so the names are computed before the removal --
        ;; asking a set of strings about keywords matches nothing and reports every
        ;; argument as unknown. (That is exactly how this read the first time.)
        extras   (sort (remove known (map name (keys args))))
        from-key (if (contains? args :replace_from) :replace_from :remove_from)
        to-key   (if (contains? args :replace_to) :replace_to :remove_to)]
    (when (seq extras)
      (throw (ex-info (str "replace does not take " (pr-str (vec extras)) "; it takes"
                           " remove_from, remove_to and replacement_lines"
                           (when require-path? ", plus path")
                           ".")
                      {:unknown (vec extras) :reason :unknown-argument})))
    ;; With :require-path the caller must NAME the file, and the engine then checks
    ;; that the name agrees with the anchor's owner. Without it the path is not
    ;; refused for being present -- the model may well pass the path it just read --
    ;; it is simply not required, and is checked the same way when it is given.
    (when (and require-path? (str/blank? (str (:path args))))
      (throw (ex-info (str "`path` is required in this session (the project's harness.edn"
                           " sets :editing {:require-path true}), and must name the file"
                           " the anchors were served for.")
                      {:argument :path :reason :missing})))
    (let [from  (anchor-arg from-key (:remove_from args) warnings)
          ;; `remove_to` omitted means a single line -- the commonest edit there is,
          ;; and making the model repeat the anchor for it would be asking it to
          ;; spell the same thing twice.
          to    (if (contains? args to-key)
                  (anchor-arg to-key (:remove_to args) warnings)
                  from)
          lines (replacement-arg (:replacement_lines args) warnings)]
      (check-nul! lines)
      (when (and strict? (seq @warnings))
        (throw (ex-info (str "strict-input mode refuses anything it would have had to"
                             " fix. It declined:\n- " (str/join "\n- " @warnings))
                        {:warnings @warnings :reason :strict-input})))
      ;; NOTE: the two anchors are NOT compared for order here. Which of them names
      ;; the earlier line is a question about the FILE, and only the stored anchor
      ;; array can answer it -- by pool position they are unrelated, which is the
      ;; whole point of allocating them (see `resolve-range`, which does it right).
      {:from from :to to :lines lines :warnings @warnings})))

;; -------------------------------------------------------------- the range

(defn lines-of
  "TEXT as {:lines [...] :trailing? bool}, where TRAILING? says whether the file
  ended with a newline -- the one fact `split-lines` cannot carry and a rewrite
  must put back."
  [^String text]
  {:lines     (anchors/split-lines text)
   :trailing? (str/ends-with? text "\n")})

(defn- join-lines
  [{:keys [lines trailing?]}]
  (str (str/join "\n" lines) (when (and trailing? (pos? (count lines))) "\n")))

(defn- range-map
  "The range [I, J] (0-based, inclusive) of VIEW: which lines, their anchors, their
  checksums, and which of those anchors the model has never been shown.

  One constructor for the one idea, because three things read this map -- an edit,
  the healing answer, and the content fallback -- and a range that meant something
  slightly different to any one of them would be a range that is wrong in a way
  nobody can see."
  [view i j]
  (let [as   (vec (:anchors view))
        serv (:served view)]
    {:start      i
     :end        (inc j)
     :anchors    as
     :checksums  (vec (:line-checksums view))
     :served     serv
     ;; Both the anchors and the LINE INDICES of the unshown lines: the anchors are
     ;; what a message names when it is talking about what to re-send, and the
     ;; indices are what the healing answer needs to page the file to the right
     ;; place. Computing either from the other later would mean searching, and a
     ;; search can find a different line with the same anchor -- which only happens
     ;; if something is already wrong.
     :not-shown  (vec (remove serv (subvec as i (inc j))))
     :not-shown-idx (vec (filter #(not (contains? serv (nth as %))) (range i (inc j))))}))

(defn resolve-range
  "The half-open line range [start, end) that FROM..TO addresses in the file's
  stored view.

  Refusals here are about the anchors not being usable -- not owned by this
  session, not shown to it, or not describing the line any more. Each one names
  what is wrong and what to do; turning them into answers the model can act on
  without a round trip is ticket 06's, and this is the shape it enriches.

  WARNINGS collects the fixes made along the way, so the answer can say what was
  interpreted -- the reversal below is one of them."
  [thread-id path from to warnings]
  (let [st (store/state thread-id path)]
    (when-not st
      (throw (ex-info (str "no anchors for " path " in this session. Call read on it"
                           " first -- editing is addressed by anchors, and the first"
                           " read is where they come from.")
                      {:path path :reason :not-read})))
    (let [as (vec (:anchors st))
          i  (some (fn [[k a]] (when (= a from) k)) (map-indexed vector as))
          j  (some (fn [[k a]] (when (= a to) k)) (map-indexed vector as))]
      (when (nil? i)
        (throw (ex-info (str "\"" from "\" is not an anchor of " path " in this session."
                             " Call read on it for current anchors.")
                        {:path path :anchor from :reason :unknown-anchor})))
      (when (nil? j)
        (throw (ex-info (str "\"" to "\" is not an anchor of " path " in this session."
                             " Call read on it for current anchors.")
                        {:path path :anchor to :reason :unknown-anchor})))
      ;; Reversed anchors are SWAPPED, not refused: the model said which two lines
      ;; it meant, and writing them back in the other order is unambiguous. The
      ;; comparison is by POSITION IN THIS FILE'S ANCHOR ARRAY -- the only ordering
      ;; that means anything about lines. Comparing them as pool values would be
      ;; comparing two anchors that were chosen to be unrelated.
      (let [[i j swapped?] (if (> i j)
                             (do (swap! warnings conj
                                        (str "remove_from and remove_to were reversed;"
                                             " swapped them."))
                                 [j i true])
                             [i j false])]
        (assoc (range-map st i j) :swapped? swapped?)))))

(defn range-from-view
  "The range FROM..TO names in VIEW, located by CONTENT rather than by ownership.

  This is the fallback for an anchor the session no longer holds -- the name was
  handed out, the ownership row is gone (a cleared anchor table, a file the session
  stopped tracking), and the model is still holding it. The anchor's position in
  the stored view says WHICH LINE was meant, and that line's checksum says what to
  look for in the file as it is now: exactly one line carries it, or nothing here
  can tell which line the model means and the honest answer is to read the file.

  Returns nil when there is no stored view, when the anchor is not in it, when the
  line is gone from the file, or when the file has more than one line that matches
  -- every one of which ends in the same refusal, because none of them can be
  turned into a guess worth making."
  [view from to]
  (let [as  (vec (:anchors view))
        cs  (vec (:line-checksums view))
        idx (fn [a]
              (when-let [i (some (fn [[k v]] (when (= a v) k)) (map-indexed vector as))]
                (let [c    (nth cs i)
                      hits (keep-indexed (fn [k lc] (when (= c lc) k)) cs)]
                  (when (= 1 (count hits)) (first hits)))))]
    (when-let [i (idx from)]
      (when-let [j (if (= from to) i (idx to))]
        (when (<= i j)
          (range-map view i j))))))

(defn apply-range
  "TEXT with lines [START, END) replaced by LINES. Returns [new-text span].

  The span uses `:old-start`/`:old-end` for the range as it was and `:new-start`/
  `:new-end` for what it became -- the SAME vocabulary `anchors/align` reads its
  `:spans` in, which is why these are the key names and not `:start`/`:end`. One
  spelling for one idea; the two were briefly different and the misalignment showed
  up as a null-pointer three frames down."
  [^String text {:keys [start end]} new-lines]
  (let [{:keys [lines trailing?]} (lines-of text)
        head    (subvec (vec lines) 0 start)
        tail    (subvec (vec lines) end)
        updated (vec (concat head new-lines tail))
        ;; An empty replacement that leaves the file with nothing in it is not an
        ;; edit -- see `check-not-empty!` -- but an empty TAIL is: `(pos? count)`
        ;; keeps a file that never ended in a newline from acquiring one.
        text'   (str (str/join "\n" updated)
                     (when (and trailing? (pos? (count updated))) "\n"))]
    [text'
     {:old-start start :old-end end
      :new-start start :new-end (+ start (count new-lines))
      :before    (vec lines) :after updated}]))

(defn check-not-empty!
  "Refuse an edit that would leave a file with no content at all.

  `replace` replaces a range; emptying a file is not a range replacement, it is
  `write`, and the two have different consequences (an empty file has one empty
  line and no content to undo back to). Pointing at `write` is more useful than
  doing it."
  [^String before ^String after]
  (when (and (pos? (count (.trim before))) (zero? (count (.trim after))))
    (throw (ex-info (str "this edit would empty the file. `replace` replaces a range;"
                         " to clear a file, use `write` with empty content.")
                    {:reason :would-empty}))))

;; ------------------------------------------------------------ the dedup

(defn dedup-edges
  "Boundary dedup: strip replacement lines that merely repeat the line adjacent to
  the range.

  WHY THIS EXISTS. The natural way to write 'change this line and add another
  after it' is to replace the range and include the boundary line again in the
  replacement. That is not wrong, it is just redundant -- and left in, the file
  gains a duplicate line that then has to be edited back out.

  Which lines count depends on where the range is: the line BEFORE it for a
  replacement that starts by repeating it, the line AFTER for one that ends by
  repeating it. The trailing case is only stripped when the block is unique in the
  rest of the file, because a repeated line that also appears elsewhere is not
  obviously a boundary artefact."
  [lines {:keys [start end]} new-lines mode]
  (if (= :off mode)
    {:lines new-lines :stripped 0}
    (let [before-line (when (pos? start) (nth lines (dec start)))
          after-line  (when (< end (count lines)) (nth lines end))
          n           (count new-lines)
          lead        (when (and before-line (= before-line (first new-lines))) 1)
          trail       (when (and after-line (> n (or lead 0))
                                 (= after-line (last new-lines)))
                        1)]
      (cond
        ;; :strict refuses instead of stripping, so the model learns the shape.
        (and (= :strict mode) (or lead trail))
        (throw (ex-info (str "the replacement re-includes a line at the edge of the"
                             " range"
                             (when lead (str " (line " start ", the line before it)"))
                             (when trail (str " (line " (inc end) ", the line after it)"))
                             ". Boundary dedup is set to :strict in this session, so it"
                             " was refused rather than stripped -- resend without"
                             (when lead " the first") (when trail " the last")
                             " line.")
                        {:reason :boundary-strict :lead lead :trail trail}))

        :else
        ;; `cond->` would thread the replacement into `subvec` as its COLLECTION
        ;; (first argument), so the two cuts are written out: drop the head, then
        ;; drop the tail of whatever is left.
        (let [kept (cond-> (vec new-lines)
                     lead  (as-> v (subvec v 1))
                     trail (as-> v (subvec v 0 (dec (count v)))))]
          {:lines    (vec kept)
           :stripped (+ (if lead 1 0) (if trail 1 0))
           :lead     (boolean lead)
           :trail    (boolean trail)})))))

;; ------------------------------------------------------------- the answer

(defn row-of
  "One diff row: PREFIX, then the anchor column, then the line.

  PREFIX is \"+\" for a line this edit added, \" \" for context, and \"-\" for a
  line it removed. The removed rows' anchor column is BLANKED -- four spaces --
  because those anchors are gone: their lines are not in the file any more, so an
  anchor printed beside one would be an anchor that no longer addresses anything."
  [prefix anchor ^String line]
  (str prefix (if anchor anchor "    ") "│" (str/replace line "\r" "")))

(defn render-diff
  "The row-shaped answer for an edit, read top to bottom like the file:

      lead    up to CONTEXT untouched lines before the range, with their anchors
      -       every line the edit removed, its anchor column blanked
      +       every line it added, with the anchor that now names it
      trail   up to CONTEXT untouched lines after it

  A `+   │... N line(s) before/after` row stands in for anything skipped, and a
  `dedup│...` row -- the one row that is NOT an anchor, and says so by not looking
  like one -- reports what boundary dedup removed.

  Returns {:text ... :shown #{...}}, and SHOWN is the point of returning a map: the
  anchors that were printed are exactly the ones the model may now address, and the
  store has to be told about them or the very next edit would be refused as
  addressing a line nobody was shown. Computing that set anywhere else would be a
  second list of index ranges to keep in step with this one."
  [{:keys [before after anchors span context stripped]}]
  (let [{:keys [old-start old-end new-start new-end]} span
        ;; Context rows are cut from AFTER by their NEW index, which is the index
        ;; the anchor array is in as well: a lead row's line is unchanged by an edit
        ;; that starts at or after it, so its old index and its new one agree.
        lead-from (max 0 (- old-start context))
        lead      (mapv (fn [i] (row-of " " (nth anchors i nil) (nth (vec after) i)))
                        (range lead-from old-start))
        added     (mapv (fn [i] (row-of "+" (nth anchors i nil) (nth (vec after) i)))
                        (range new-start new-end))
        trail-to  (min (count after) (+ new-end context))
        trail     (mapv (fn [i] (row-of " " (nth anchors i nil) (nth (vec after) i)))
                        (range new-end trail-to))
        removed   (mapv (fn [i] (row-of "-" nil (nth (vec before) i))) (range old-start old-end))]
    {:text  (str/join
             "\n"
             (concat
              (when (pos? lead-from)
                [(str "+   │... " lead-from " line(s) before the change")])
              lead
              (when (and (empty? removed) (empty? added)) ["    │(no lines changed)"])
              removed
              added
              trail
              (when (< trail-to (count after))
                [(str "+   │... " (- (count after) trail-to) " line(s) after the change")])
              (when (pos? stripped)
                [(str "dedup│" stripped " line(s) at the boundary were not added again")])))
     ;; The rows that carry a usable anchor: the `+` rows and the context rows.
     ;; The `-` rows carry none (their lines are gone) and the ellipsis and dedup
     ;; rows are not rows of the file at all.
     :shown (into #{} (comp (remove nil?)) (concat (map (fn [i] (nth anchors i nil))
                                                       (range lead-from old-start))
                                                  (map (fn [i] (nth anchors i nil))
                                                       (range new-start new-end))
                                                  (map (fn [i] (nth anchors i nil))
                                                       (range new-end trail-to))))}))

(defn ok-message
  "The success line and the diff, plus the anchors the diff made addressable. See
  `render-diff` for why the set travels with the text."
  [path {:keys [added removed] :as diff-args}]
  (let [{:keys [text shown]} (render-diff diff-args)]
    {:text  (str "Edited " path
                 (when (or (pos? added) (pos? removed))
                   (str " (" (when (pos? added) (str added " line(s) added"))
                        (when (and (pos? added) (pos? removed)) ", ")
                        (when (pos? removed) (str removed " line(s) removed")) ")"))
                 ".\n\n" text
                 "\n\nThe anchors above are current: edit those lines directly, no read"
                 " needed.")
     :shown shown}))
