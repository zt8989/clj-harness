(ns harness.glob
  "`glob`: find files by NAME. The other search tool finds files by CONTENT.

  WHY BOTH EXIST. \"Which files are clj files under src\" and \"which files mention
  `bind!`\" are two different questions, and only the second one is about lines.
  Before this existed the first was `bash ls` / `bash find`, which meant the model
  had to write a find expression and then read a listing that agreed with nobody's
  ignore rules. Here it is one call, and the paths come back ready to hand to
  `read`.

  IT LISTS PATHS, SO IT HAS NO ANCHORS AND NO EDITION. A path is not a line: there
  is nothing to mint a 4-character name for. That is why this tool belongs to
  NEITHER editing family (harness.editing) and is served in both modes, and why it
  lives here rather than under harness.hashline.*.

  THE SEARCH ITSELF IS harness.rg's -- the same executable, timeout and named
  refusals `anchor_grep` uses. What is this namespace's own: which files count, how
  many of them one answer may carry, and what the answer looks like.

  ORDER IS BY PATH, NOT BY TIME. Two identical calls must answer identically, and a
  listing ordered by modification time makes the answer depend on who happened to
  touch what -- which is a fact about the tree's history rather than about the
  question that was asked."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [harness.rg :as rg]))

(def default-limit
  "How many paths one answer may carry. THE SAME NUMBER AS `anchor_grep`'s
  default-limit, deliberately: it bounds the same kind of thing (how many places in
  the tree one answer may name), and two different caps for one idea would be a
  difference nobody asked for and nobody would remember."
  100)

(defn- tidy
  "PATH made absolute with its `.` and `..` segments collapsed, LEXICALLY.

  Lexically and not through the store's `canonical`, because this namespace keys
  nothing: nothing here is filed under this string, it is only printed and handed
  back to `read`, which resolves and canonicalizes for itself. So the question is
  'is the answer tidy', and `java.nio.file.Path/normalize` answers it without
  touching the filesystem -- no symlink chasing, no IO, and a path that does not
  exist still normalizes.

  Absolute rather than as-given because a bound session's resolution leaves a
  literal `.` in the root (`/proj/.`), and because an unbound session's root is
  `.` -- which as a rg target prints every hit as `./src/a.clj`. One shape for
  every session is worth more than echoing back the spelling nobody asked about."
  [^String path]
  (let [s (str (.normalize (.toPath (.getAbsoluteFile (io/file path)))))]
    (if (str/blank? s) "." s)))

(defn- check-pattern!
  "Refuse a pattern that is not worth running. Empty is the case that matters: rg
  treats an empty `--glob` as 'match everything', so a blank pattern would quietly
  become a request for the whole tree instead of a refusal."
  [pattern]
  (when (or (nil? pattern) (str/blank? pattern))
    (throw (ex-info "`pattern` is required: the glob to match file names against, e.g. \"**/*.clj\"."
                    {:argument :pattern :reason :missing}))))

(defn- rg-args
  "The flags this listing runs with. PATTERN, when given, is one more `--glob`.

  `--hidden` with `!.git` matches `anchor_grep` exactly, and for the same two
  reasons: a repo's dot-directories are half of what a person means by 'the
  files', and `.git`'s contents are never one of them. `.gitignore` is left to
  rg's default -- see `perform!` for the one thing that needs care."
  [root pattern]
  (cond-> ["--files" "--color=never" "--hidden" "--glob" "!.git"]
    pattern (conj "--glob" pattern)
    true    (conj "--" root)))

(defn- paths
  "RG's listing as tidied path strings, one per line."
  [args]
  (->> (str/split-lines (:out (rg/run args nil)))
       (remove str/blank?)
       (map tidy)
       (vec)))

(defn perform!
  "Run one glob for THREAD-ID. ARGS is the tool's argument map; returns a STRING,
  or throws a named error.

  RESOLVE-PATH is the session's path resolution, applied to the search root -- the
  file tools' own, so a relative root means what it means everywhere else.

  THE PATTERN IS RUN TWICE AND THE ANSWER IS THE INTERSECTION, which is the only
  shape that keeps both promises a file finder has to keep. rg's own help says
  `--glob` 'always overrides any other ignore logic', so handing the pattern to rg
  alone lets it outrank `.gitignore`: `**/*` -- an ordinary thing to ask for --
  matches the DIRECTORY `node_modules`, which whitelists everything under it, and
  the answer is thirty thousand vendored files. The other call is rg's plain
  listing: 'the files of this tree', ignore rules in force. Intersecting them says
  the pattern is applied WITHIN the tree rather than over the top of it, and the
  glob keeps rg's own meaning instead of a matcher invented here.

  `--glob PATTERN` rather than a positional argument, because rg reads a positional
  as a PATH: a pattern like `src/**` is a valid-looking path, and the failure mode
  would be an empty answer rather than an error."
  [thread-id resolve-path args]
  (let [{:keys [pattern path]} args
        _     (check-pattern! pattern)
        root  (tidy (resolve-path (if (and (string? path) (not (str/blank? path))) path ".")))
        every (set (paths (rg-args root nil)))
        found (->> (paths (rg-args root pattern))
                   (filter every)
                   (sort)
                   (vec))
        total (count found)
        shown (vec (take default-limit found))]
    (when (empty? found)
      (throw (ex-info (str "no files match " (pr-str pattern) " under " root ".")
                      {:reason :no-matches :pattern pattern :path root})))
    (str (str/join "\n" shown)
         (when (> total default-limit)
           (str "\n[... " total " files match; showing the first " default-limit
                ". Narrow the pattern or the `path` to see the rest.]")))))
