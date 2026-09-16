(ns harness.rg
  "The one place that knows how this harness runs ripgrep.

  TWO TOOLS SEARCH WITH IT -- `anchor_grep` for lines, `glob` for paths -- so the
  three facts below live here rather than inside either of them: what the
  executable is called, how long a search may take, and what a missing rg means.
  Written down twice, one of them would eventually be fixed and the other not,
  which is the same reasoning that put the shell lookup in harness.shell.

  WHAT DOES NOT LIVE HERE. The `--json` event parsing stays in
  harness.hashline.grep: a HIT is that namespace's idea, because a hit is a line
  that has to be handed an anchor. And the answer budgets (`default-limit`,
  `max-bytes`) stay with the tool whose answer they bound -- this namespace knows
  how to RUN a search, not how big an answer may be.

  RG IS NOT OPTIONAL, AND A MISSING ONE IS NAMED. `rg` respects .gitignore, skips
  binaries, and is fast on a tree nobody has indexed; a hand-rolled walk here
  would be slower and, worse, would quietly disagree with what every other tool in
  the session sees. So when it is not on PATH the answer says which executable is
  missing and what to install -- never a silent fall back, because a search that
  got ten times slower without saying so is a mystery to whoever has to work out
  why."
  (:require [clojure.string :as str]
            [harness.shell :as shell]))

(def binary
  "The executable a search is run with. Named so the refusal below can say what to
  install, and so there is one place to change it."
  "rg")

(def timeout-ms
  "How long one search may take. Ten seconds, upstream's number: long enough for a
  real tree, short enough that a pathological pattern does not hold the session."
  10000)

(defn- quoted
  "ARG as one single-quoted shell word. harness.shell hands the whole line to
  `bash -lc`, so an argument holding a space, a quote or a glob character would
  otherwise be read by the SHELL rather than by rg -- and a search for `foo bar`
  would silently become two arguments. The embedded quote is handled the POSIX
  way: close the quoted run, emit an escaped quote, open a new one."
  [^String arg]
  (str "'" (str/replace arg "'" "'\\''") "'"))

(defn command-line
  "BINARY followed by ARGS, each quoted for the shell. Public so a test can
  assert that an argument with a space in it survives as ONE word."
  [args]
  (str binary " " (str/join " " (map quoted args))))

(defn- missing-executable?
  "Did the shell report that there is no such program?

  EXIT 127 IS THE SIGNAL: that is the shell's own 'command not found', and it is
  the same number in bash, sh and zsh. The phrase is checked as well, because a
  shell that reports it some other way is cheaper to tolerate than to debug.

  DELIBERATELY NOT `No such file or directory`, which is what the first version of
  this checked. That string is also what rg prints for a search root that does not
  exist (`IO error for operation on /nope: No such file or directory`), so the
  branch it guarded answered 'install ripgrep' for a typo'd `path` -- and, on
  macOS and Linux, never fired at all, because bash does not print it for a missing
  program. A refused typo that blames the installation is worse than the generic
  failure it replaced."
  [^String err exit]
  (or (= 127 exit) (str/includes? err "command not found")))

(defn run
  "Run rg with ARGS -- a vector of already-flag-shaped strings -- in DIR.
  Returns harness.shell/run's own map when rg finished: {:exit :out :err}.

  RG'S EXIT CODES ARE PART OF ITS INTERFACE, so the two that are ANSWERS rather
  than failures are not turned into errors here: 0 is 'there were matches', 1 is
  'there were none', and both come back to the caller to interpret. Anything else
  -- and a search that ran out of time -- is a NAMED failure, because 'nothing
  was found' and 'the search never happened' must not look the same to a model."
  [args dir]
  (let [res (shell/run {:command (command-line args) :dir dir :timeout-ms timeout-ms})
        {:keys [exit out err]} res]
    (cond
      ;; A timeout is judged by what came back, not by the exit code alone: rg
      ;; killed at the limit can still have printed hits, and those are worth
      ;; keeping. Blank output is the case that has to be named.
      (and (:timeout res) (not= 1 exit) (str/blank? out))
      (throw (ex-info (str "the search did not finish within " timeout-ms "ms and was"
                           " stopped. Narrow it: give a `path` or a `glob`, or search"
                           " for a plainer pattern.")
                      {:reason :search-timeout :timeout-ms timeout-ms}))

      (contains? #{0 1} exit) res

      (missing-executable? (str err) exit)
      (throw (ex-info (str "`" binary "` is not on this process's PATH, so nothing was"
                           " searched. Install ripgrep (`brew install ripgrep`,"
                           " `apt install ripgrep`) or use `bash` with the tools you"
                           " have.")
                      {:reason :rg-missing :executable binary}))

      :else
      (throw (ex-info (str "the search failed (exit " exit "): " (str/trim (str err)))
                      {:exit exit :reason :rg-failed :stderr (str/trim (str err))})))))
