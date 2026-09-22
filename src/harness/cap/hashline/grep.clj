(ns harness.cap.hashline.grep
  "`grep`: a search whose results can be EDITED.

  WHY THIS EXISTS. A model that searches for something usually wants to change what
  it found, and a line number cannot be edited -- so between a plain grep and the
  change there is a whole read of every file that matched. Here each hit comes back
  as an anchored row, so the search and the edit are one step apart instead of two.

      === src/harness/tools.clj ===
        42 │ Hasu│(defn- t-edit [{:keys [path old_string new_string]}]

  The line number is still printed, and it is for the READER -- a human scanning the
  output, or the model talking about a hit. What the model edits by is the anchor to
  the right of the `│`, and the two are deliberately not the same kind of thing.

  IT IS `ripgrep`, AND THAT IS NOT AN IMPLEMENTATION DETAIL. `rg` respects
  .gitignore, skips binaries, and is fast on a tree nobody has indexed; a hand-rolled
  walk of the file system here would be slower and, worse, would quietly disagree
  with what every other tool in the session sees. When `rg` is not on PATH the answer
  is a NAMED FAILURE saying what to install -- never a silent fall back to reading
  directories, because a search that got ten times slower without saying so is a
  mystery to whoever has to work out why.

  RUNNING IT IS NOT THIS NAMESPACE'S BUSINESS ANY MORE: the executable, the timeout
  and the three refusals a spawned search can produce live in harness.infra.rg, because
  `glob` searches with the same program. What stays here is the READING of rg's
  `--json` stream -- a hit is a LINE, and a line is the thing this namespace has to
  hand an anchor to.

  REGEXES THAT CAN HANG ARE REFUSED. This is not a general regex engine's problem to
  solve: a pattern the model wrote by accident (`(a+)+b`) can take exponential time
  on a line that does not match, and the session would sit inside a subprocess until
  its timeout. So the shapes that do it are refused up front, with `literal: true`
  named as the way out -- which is right for the common case anyway, since a search
  for a piece of code is usually a search for text."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [harness.cap.hashline.anchors :as anchors]
            [harness.cap.hashline.files :as files]
            [harness.cap.hashline.reading :as reading]
            [harness.cap.hashline.serve :as serve]
            [harness.cap.hashline.store :as store]
            [harness.infra.rg :as rg]))

(def max-bytes
  "How much search output one call may return, in bytes of rendered rows. A search
  that finds ten thousand hits is not a search that answered a question."
  51200)

(def default-limit
  "How many hits to return when the model does not say. Enough for 'where is this
  used', small enough that a common word does not return the whole repository."
  100)

;; ---------------------------------------------------------- the pattern

(defn- catastrophic?
  "Does this pattern contain a shape that can take exponential time to FAIL to
  match? A deliberately small list, and each entry is a shape a real engine
  backtracks over:

    \\1..\\9        backreferences, which no amount of DFA work can rescue
    ( ... )+, *     a quantified group whose body is itself quantified
    a{1000}         a repetition count big enough to be a scan on its own
    (a|aa)+        an alternation with a quantified, variable-length branch

  This is a HEURISTIC and it is not trying to be a proof -- it is the set of
  mistakes a model actually writes. The way out is always the same and it is named
  in the refusal: search for the literal text instead."
  [^String pattern]
  (boolean
   (or (re-find #"\\[1-9]" pattern)
       (re-find #"\([^)]*[+*][^)]*\)\s*[+*]" pattern)
       (re-find #"\{\s*\d{3,}" pattern)
       (re-find #"\([^)]*\|[^)]*\)\s*[+*]" pattern))))

(defn check-pattern!
  "Refuse a pattern that is not worth running -- empty, or one of the shapes above.

  LITERAL is the way out and is offered as one: a model searching for a piece of
  source code is usually searching for text, and `literal: true` says so without
  giving up the search."
  [^String pattern literal?]
  (when (or (nil? pattern) (str/blank? pattern))
    (throw (ex-info "`pattern` is required: the text or regular expression to search for."
                    {:argument :pattern :reason :missing})))
  (when (and (not literal?) (catastrophic? pattern))
    (throw (ex-info (str "this pattern contains a shape that can take exponential time"
                         " to fail to match (" (pr-str pattern) "), so it was not run."
                         " Pass literal: true to search for it as plain text, or"
                         " simplify it -- nested quantifiers, backreferences and"
                         " quantified alternations are the shapes that hang a regex"
                         " engine.")
                    {:argument :pattern :value pattern :reason :unsafe-regex}))))

;; ------------------------------------------------------------- the options

(defn- positive-int [k v]
  (when (some? v)
    (when-not (and (integer? v) (pos? v))
      (throw (ex-info (str "`" (name k) "` must be a positive integer (1 or more); got "
                           (pr-str v) ".")
                      {:argument k :value v :reason :not-a-positive-integer})))
    v))

(defn- non-negative-int [k v]
  (when (some? v)
    (when-not (and (integer? v) (not (neg? v)))
      (throw (ex-info (str "`" (name k) "` must be an integer of 0 or more; got "
                           (pr-str v) ".")
                      {:argument k :value v :reason :not-a-non-negative-integer})))
    v))

;; --------------------------------------------------------------- running rg

(defn- rg-args
  "The flags this search runs with. `--max-count` is LIMIT+1 for the reaper in
  `run-rg`: one extra hit is how 'there were more' is detected without asking for
  the whole tree."
  [{:keys [pattern path glob ignore-case literal context limit]}]
  (cond-> ["--json" "--line-number" "--color=never" "--hidden"
           ;; .git contents are never what a search means, and walking them is
           ;; pure cost. Everything .gitignore says is respected by default.
           "--glob" "!.git"
           "--max-count" (str (inc (long limit)))]
    literal     (conj "--fixed-strings")
    ignore-case (conj "--ignore-case")
    context     (conj "--context" (str (long context)))
    glob        (conj "--glob" glob)
    ;; `--` so a path can never be read as a flag, and a pattern beginning with a
    ;; dash is still a pattern.
    true        (conj "--" pattern (or path "."))))

(defn run-rg
  "Run the search and return `rg`'s parsed events.

  The running is harness.infra.rg's -- the executable, the timeout, and the three
  refusals a spawned search can produce are shared with `glob` now. What is left
  here is the one thing that is THIS tool's: reading the `--json` stream, where a
  `match` event means a line that has to be given an anchor.

  A line that is not JSON is DROPPED rather than reported: rg's protocol puts one
  JSON object per line, so a non-object line is not a hit, and the malformed-payload
  case that a model can act on is the refusal harness.infra.rg raises, not this."
  [args dir]
  (let [out (:out (rg/run (rg-args args) dir))]
    (vec (keep (fn [line]
                 (when-not (str/blank? line)
                   (try (json/read-str line :key-fn keyword)
                        (catch Exception _ nil))))
               (str/split-lines out)))))

;; --------------------------------------------------------------- the hits

(defn- hits
  "The `match` events as [{:path :line :text}] in rg's own order.

  Only the `match` kind: rg's JSON stream also carries `begin`/`end`/`context`/
  `summary` events, and the context lines are re-derived from the file below --
  where they can be given anchors, which an rg context event's text alone cannot."
  [events]
  (keep (fn [e]
          (when (= "match" (:type e))
            (let [d (:data e)]
              {:path (:text (:path d))
               :line (:line_number d)
               :text (:text (:lines d))})))
        events))

;; ------------------------------------------------------------- the rendering

(defn- anchor-rows
  "The rows for a file's hit line NUMBERS, as {:text :shown} -- the same shape
  harness.cap.hashline.reading produces, and for the same reason: the caller has to know
  which anchors it PRINTED, because printing is what makes a line addressable.

  THE ROWS COME FROM THE FILE, not from rg's output. rg hands back three fields (a
  number, a line, a path), and a row needs the file's whole line arrangement to name
  a line stably -- which is exactly what `serve/sync!` establishes, so this goes
  through it. A search that shows a model a line of a file it has never read has, in
  fact, shown it that line, and the anchor beside it has to work."
  [thread-id path numbers {:keys [context]}]
  (let [{:keys [text]} (files/read-file path)
        view  (serve/sync! thread-id path text)
        lines (vec (anchors/split-lines text))
        as    (vec (:anchors view))
        total (count lines)
        ctx   (long (or context 0))
        wanted (vec (sort (into #{} (mapcat (fn [n]
                                              (range (max 0 (- (dec n) ctx))
                                                     (min total (+ n ctx))))
                                            numbers))))
        rows  (mapv (fn [i]
                      (format "%6d │ %s" (inc i)
                              (reading/row-for path (nth lines i) (nth as i) (inc i))))
                    wanted)]
    {:text  (str/join "\n" rows)
     :shown (into #{} (keep #(nth as % nil)) wanted)}))

(defn- render
  "The whole answer: one block per file, under the byte budget.

  Returns {:text :shown :files :truncated?}, where SHOWN maps each file's path to the
  anchors its block printed -- per file, because that is how the store records them.
  A block that would take the answer over `max-bytes` is not printed at all rather
  than printed in part: half a file's hits is a set of anchors that look arbitrary
  to whoever is reading them."
  [thread-id files {:keys [limit] :as opts}]
  (loop [fs (seq files), bytes 0, out [], shown {}, hits 0]
    (if-not (seq fs)
      {:text (str/join "\n" out) :shown shown :files (count out)
       :truncated? false :hits hits :limit (long limit)}
      (let [[^String abs ns] (first fs)
            ;; ask rg for limit+1 hits per file so 'there were more' is knowable;
            ;; the extra one is what makes that visible in the header below
            nos    (vec (take (long limit) ns))
            more?  (> (count ns) (count nos))
            page   (anchor-rows thread-id abs nos opts)
            block  (str "=== " abs " ==="
                        (when more?
                          (str " (first " (count nos) " matches; narrow the search"
                               " or raise `limit` to see the rest)"))
                        "\n" (:text page))
            size   (alength (.getBytes ^String block "UTF-8"))]
        (if (and (pos? bytes) (> (+ bytes size) max-bytes))
          {:text (str (str/join "\n" out)
                      (when (seq out) "\n")
                      "[... output budget reached; " (count out) " file(s) shown."
                      " Narrow the search with `path` or `glob` to see the rest.]")
           :shown shown :files (count out) :truncated? true :hits hits :limit (long limit)}
          (recur (rest fs) (+ bytes size) (conj out block)
                 ;; `shown` is the OUTER map here and `(:shown page)` is one file's
                 ;; set of anchors -- two different things, and giving both the name
                 ;; `shown` is how this line first read as a cast exception.
                 (assoc shown abs (:shown page))
                 (inc hits)))))))

(defn perform!
  "Run one grep for THREAD-ID.

  ARGS is the tool's argument map; returns a STRING, or throws a named error.
  RESOLVE-PATH is the session's path resolution, applied to the search root AND to
  every file rg reports -- so the anchors the answer hands out are filed under the
  same path an edit will look them up by.

  THE RENDERING AND THE MARKING SHARE ONE SESSION LOCK. `render` reads each file's
  anchors through `serve/sync!` under the session lock, and the `mark-served!` that
  records what each block printed derives from that read. A concurrent edit landing
  between them prunes the freed anchors in `advance-on!` and this unions them back
  in, so a name no longer in `:anchors` would be recorded as shown. Holding the lock
  across both closes the gap."
  [thread-id resolve-path args _config]
  (let [{:keys [pattern path glob ignore-case literal context limit]} args
        literal? (boolean literal)
        _        (check-pattern! pattern literal?)
        limit    (long (or (positive-int :limit limit) default-limit))
        context  (long (or (non-negative-int :context context) 0))
        root     (resolve-path (if (and (string? path) (not (str/blank? path))) path "."))
        ;; The root is canonicalized before rg sees it, so the paths it reports are
        ;; free of the `.` segments a bound session's resolution leaves in them.
        root     (store/canonical root)
        events   (run-rg {:pattern pattern :glob glob :ignore-case ignore-case
                          :literal literal? :context context :limit limit
                          :path root}
                         nil)
        found    (hits events)
        ;; rg's own order, and one entry per file: the answer reads like the search.
        grouped  (reduce (fn [acc {:keys [path line]}]
                           (update acc path (fnil conj []) line))
                         (array-map)
                         found)
        ;; A FILE THE SESSION COULD NOT READ AS TEXT IS SKIPPED WITHOUT AN ERROR:
        ;; the model did not ask about it, it merely happened to match. That is a
        ;; different thing from a search that failed, which throws below.
        ;;
        ;; CANONICALIZED, both because the store books anchors by canonical path and
        ;; because rg reports paths relative to the root it was handed -- so the
        ;; answer would otherwise print `..././src/a.clj` and file its anchors under
        ;; a spelling no edit will ever ask for.
        prepared (vec (keep (fn [[p ns]]
                              (let [abs (store/canonical (resolve-path p))]
                                (try
                                  (reading/classify (io/file abs))
                                  [abs ns]
                                  (catch Throwable _ nil))))
                            grouped))
        ;; THE RENDER ITSELF READS EACH FILE'S ANCHORS (`serve/sync!` inside
        ;; `render`), so it and the marking below take the SAME session lock -- a
        ;; concurrent edit must not land between the read and the mark (see the
        ;; docstring: `advance-on!` prunes, `mark-served!` unions).
        result   (store/with-session-lock
                  thread-id
                  (fn []
                    (let [r (render thread-id prepared (assoc {:limit limit} :context context))]
                      (doseq [[abs anchors] (:shown r)]
                        (store/mark-served! thread-id abs anchors))
                      r)))]
    (let [text (:text result)]
      (when (str/blank? text)
        (throw (ex-info (str "no matches for " (pr-str pattern)
                             (when root (str " under " root))
                             (when glob (str " matching " glob))
                             (when (seq found)
                               (str " (there were " (count found) " match(es), but in"
                                    " files that cannot be read as text)"))
                             ".")
                        {:reason :no-matches :pattern pattern})))
      text)))

