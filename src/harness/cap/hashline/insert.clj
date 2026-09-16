(ns harness.cap.hashline.insert
  "`insert`: add lines next to a line, without disturbing the line it names.

  WHY THIS IS NOT JUST `replace` WITH EXTRA STEPS. In the replace grammar an
  insertion is 'a range of one line, rewritten as the new lines plus that line' --
  correct, but it makes the model write the anchored line's text back out, and if it
  gets that text slightly wrong it has changed the line it only meant to insert
  beside. `insert` takes the intent directly: one anchor, a direction, and the lines
  to add. The anchored line is never retyped, so it cannot be mistyped.

  THE ANCHORED LINE KEEPS ITS ANCHOR, and that is a property of the IMPLEMENTATION,
  not a promise bolted on. `harness.cap.hashline.anchors/align` keeps an anchor when the
  content at a POSITIONAL SLOT is unchanged, so an insertion is expressed as a
  ZERO-LENGTH span at the insertion point rather than as a one-line range that gets
  rewritten: every line keeps its old index (and so its name), the new lines are
  minted into the slots they occupy, and everything after them shifts. Writing it as
  'replace that line with itself plus the new lines' would keep the anchor only in
  the `after` direction -- a line that moves to a later slot cannot keep a name that
  is decided by position -- and would show the untouched line as a removal and an
  addition in the diff.

  NO BOUNDARY DEDUP. `:boundary-dedup` exists for the replace slip where a model
  deletes a line and writes it back at the edge of its own range; it is about an
  UNINTENDED repeat. Inserting a line identical to its neighbour is the whole point
  of an insert, so this tool never dedups, whatever the session is configured to
  do."
  (:require [clojure.string :as str]
            [harness.cap.hashline.edit :as edit]))

(def directions
  "The only two values `direction` takes. A string, because that is what arrives
  over JSON -- and the message below names them, so the legal set is stated where
  the reader needs it."
  #{"before" "after"})

(defn parse
  "ARGS (the tool's argument map) as the insertion it means: {:anchor :direction
  :lines}.

  The anchor and the lines go through `harness.cap.hashline.edit`'s field parsers, so
  the slips a model makes here are fixed and reported exactly as they are for
  `replace` -- one vocabulary for one idea, and `:strict-input` covers both."
  [args {:keys [warnings require-path? strict?]}]
  (let [warnings (or warnings (atom []))
        known    #{"anchor" "direction" "lines" "path"}
        extras   (sort (remove known (map name (keys args))))]
    (when (seq extras)
      (throw (ex-info (str "insert does not take " (pr-str (vec extras))
                           "; it takes anchor, direction and lines (plus path in a"
                           " session that requires it).")
                      {:unknown (vec extras) :reason :unknown-argument})))
    (when (and require-path? (str/blank? (str (:path args))))
      (throw (ex-info (str "`path` is required in this session (the project's"
                           " harness.edn sets :editing {:require-path true}), and must"
                           " name the file the anchor was served for.")
                      {:argument :path :reason :missing})))
    (when-not (contains? args :anchor)
      (throw (ex-info (str "`anchor` is required: the 4-character anchor of the line"
                           " to insert next to, from a read row (the text before the"
                           " `│`).")
                      {:argument :anchor :reason :missing})))
    (when-not (contains? args :direction)
      (throw (ex-info (str "`direction` is required: \"after\" to add the lines below"
                           " the anchored one, or \"before\" to add them above it.")
                      {:argument :direction :reason :missing})))
    (let [anchor (edit/anchor-field :anchor (:anchor args) warnings)
          d      (:direction args)
          d      (cond
                   (and (string? d) (contains? directions (str/lower-case d)))
                   (keyword (str/lower-case d))

                   (contains? #{:before :after} d) d

                   :else
                   (throw (ex-info (str "`direction` must be \"before\" or \"after\";"
                                        " got " (pr-str d) ".")
                                   {:argument :direction :value d
                                    :reason :bad-direction})))
          lines  (edit/replacement-field (:lines args) warnings)]
      (edit/check-nul! lines "lines")
      (when (and strict? (seq @warnings))
        (throw (ex-info (str "strict-input mode refuses anything it would have had to"
                             " fix. It declined:\n- " (str/join "\n- " @warnings))
                        {:warnings @warnings :reason :strict-input})))
      {:anchor anchor :direction d :lines lines :warnings @warnings})))
