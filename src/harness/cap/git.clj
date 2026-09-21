(ns harness.cap.git
  "A session's directory as a git working tree: which branch it is on, which
  branches it could be on, and moving it between them.

  WHY THIS IS A NAMESPACE AND NOT A FEW LINES IN THE ROUTE. Every fact here is
  read by SPAWNING GIT, and the two things that go wrong with that are properties
  of the machine rather than of the route: a repository that is not one (git exits
  non-zero and says so in its own words), and a request that names a branch the
  worktree does not have. Both are ordinary answers here, not exceptions, and both
  would be re-derived differently at a second call site.

  THE BRANCH NAME NEVER REACHES A SHELL UNQUOTED, and that is the whole reason
  `switch!` takes the string it validated against a LIST rather than the string it
  was handed. A branch name is attacker-shaped input the moment it crosses the
  wire, and `harness.infra.shell` runs its command through `bash -lc` -- so the value
  that gets interpolated is one GIT ITSELF printed a moment ago, single-quoted on
  the way in. Validating against the listing is not belt-and-braces: it is what
  makes the quoting a second line rather than the only one.

  SWITCHING IS THE ONE WRITE, AND IT CAN FAIL IN WAYS THAT ARE NOT OUR BUSINESS.
  A dirty worktree, a branch checked out in another worktree, a conflict -- git
  refuses each by name and this namespace hands that sentence back untouched. It
  never passes --force, --hard or -f: the difference between 'switch branches' and
  'throw away what I was doing' is not one this harness gets to blur, and a UI
  button is exactly where somebody would blur it."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [harness.infra.shell :as shell]))

(def ^:private timeout-ms
  "A git command that has not answered in ten seconds has stopped being worth
  waiting for. Same order as the hook engine's own bound; long enough for a cold
  `git status` on a large repository, short enough that a wedged one does not hold
  an HTTP request open indefinitely."
  10000)

(defn- quoted
  "S as a single-quoted POSIX word. The `'\\''` dance is the only way to put a
  quote inside one -- see the namespace docstring for why a value ever gets here
  at all."
  [s]
  (str "'" (str/replace (str s) "'" "'\\''") "'"))

(defn- git
  "Run `git ARGS` in DIR. Answers {:exit :out :err} from `harness.infra.shell/run`, with
  the output TRIMMED because every caller here compares or prints it and a
  trailing newline is never part of the answer.

  :timeout is carried through rather than thrown: a git that hung is a fact the
  caller may want to report, and it is not the same fact as git refusing.

  IT REFUSES BY NAME FIRST when this machine has no POSIX shell: every argument
  below is spliced into a single-quoted command line (see `quoted`), so running a
  git it could not quote for would be the harness quietly asking a different
  question than the one it wrote down."
  [dir & args]
  (shell/require-posix! "`git`, which this session reads its branch and directory with,")
  (let [{:keys [exit out err timeout]}
        (shell/run {:command (str/join " " (cons "git" (map quoted args)))
                    :dir dir
                    :timeout-ms timeout-ms})]
    {:exit exit
     :out (str/trim (str out))
     :err (str/trim (str err))
     :timeout (boolean timeout)}))

(defn- porcelain-header
  "The value of one `# branch.<NAME>` header in a `status --porcelain=v2 --branch`
  output, or nil when the output does not carry it. THE HEADERS ARE THE ONLY PART
  OF THAT FORMAT THAT IS NOT A CHANGE, and that is what makes the whole thing
  parsable: they start with `#`, and `porcelain-dirty` below counts by the same
  distinction from the other side."
  [out name]
  (let [prefix (str "# branch." name " ")]
    (some (fn [line] (when (str/starts-with? line prefix) (subs line (count prefix))))
          (str/split-lines (str out)))))

(defn- porcelain-branch
  "The branch a `status --porcelain=v2 --branch` output names, or nil -- and BOTH
  nils are why this is a function rather than a `subs`.

  ONE COMMAND ANSWERS THREE QUESTIONS, and this is the mapping that buys it. The
  left column is what porcelain says, the middle is what the four spawns this
  replaced said about the same repository, the right is the answer `state` owes:

    | porcelain v2               | the four spawns said                 | answer     |
    |----------------------------|--------------------------------------|------------|
    | exit 128                   | `rev-parse --is-inside-work-tree`    | not a repo |
    |                            | was not `true`                       |            |
    | `# branch.head main`       | `rev-parse --abbrev-ref HEAD` = main | \"main\"     |
    | `# branch.head (detached)` | the same command = the literal HEAD  | nil        |
    | `# branch.oid (initial)`   | the same command EXITED 128 (unborn) | nil        |
    | the non-`#` line count     | `status --porcelain`'s line count    | :dirty n   |

  THE `(initial)` ROW IS THE ONE THAT IS NEW, and it is the detached row's own trap
  wearing different clothes. `:branch` has to name something that is IN `:branches`.
  An unborn head has an empty listing, and porcelain answers `# branch.head master`
  with exit 0 anyway -- copying that name out would draw a branch that exists
  nowhere. `rev-parse` exits 128 there, so nil is also what the old code always
  answered; this row is what keeps that true.

  SO A NAME IS ONLY EVER TAKEN WHEN PORCELAIN ALSO SAID THE HEAD HAS A COMMIT
  (`# branch.oid` is not `(initial)`) AND IS NOT DETACHED. Anything else this
  command cannot answer is answered nil, which is the one value a branch strip can
  draw without inventing a name."
  [out]
  (let [oid  (porcelain-header out "oid")
        head (porcelain-header out "head")]
    (when (and (not (str/blank? head))
               (not= "(detached)" head)
               (not= "(initial)" oid))
      head)))

(defn- porcelain-dirty
  "The number of changes in a `status --porcelain=v2 --branch` output: every line
  that is not a header. ONE LINE PER ENTRY, the way `--porcelain` counts them -- a
  rename is one line (its old name rides along inside it, tab-separated) and an
  untracked file is one line -- so the number is the one the strip already showed.

  Modified, staged and untracked alike, which is `--porcelain`'s own scope: ignored
  files are not counted, because neither command was asked for `--ignored`."
  [out]
  (count (remove #(or (str/blank? %) (str/starts-with? % "#"))
                 (str/split-lines (str out)))))

(def ^:private branch-ref-prefix
  "What a local branch ref is called in full. The line between a name and a
  sentence about the current head is this prefix -- see `local-branches`."
  "refs/heads/")

(defn- local-branches
  "The branch names in a `branch --format=%(refname)` output, in the order git
  printed them (newest-commit first, for `--sort=-committerdate`).

  `%(refname)` RATHER THAN `%(refname:short)`, AND KEEPING THE PREFIX IS THE POINT.
  On a DETACHED head git prints one more line, and it is not a branch: the refname
  it reports for it is the whole sentence `(HEAD detached at 0f506fe)`. Asked for
  `:short` -- which is what this asked for until the detached case got a test --
  that sentence comes back looking exactly like a name, and the strip then offers
  a branch nobody can switch to. `:branch` is nil in that state precisely because
  no branch is checked out; a listing that offers one anyway is the other half of
  the same lie. ONLY `refs/heads/...` LINES BECOME NAMES, which is the definition
  of a local branch rather than a guess about the shape of the text."
  [out]
  (into []
        (comp (filter #(str/starts-with? % branch-ref-prefix))
              (map #(subs % (count branch-ref-prefix))))
        (remove str/blank? (str/split-lines (str out)))))

(defn state
  "DIR as a working tree, as the composer's directory+.
  branch strip needs it:

    {:repo? false}                                  not a repository, or no dir
    {:repo? true :branch \"main\" :branches [..] :dirty n}

  NOT BEING A REPOSITORY IS AN ORDINARY ANSWER, not a failure. A session can be
  bound to any directory, most directories are not repositories, and the strip
  simply has nothing to show for those -- which is why this answers a shape rather
  than throwing: a caller drawing a branch picker should not have to catch an
  exception to learn there is no branch to pick. THE LINE IS GIT'S OWN EXIT 128,
  which is what a plain directory, a bare repository and `.git` itself all answer
  -- and, at the far edge, a repository whose index git cannot read, where the four
  spawns this used to take would have answered a branch and `:dirty 0`. See
  `porcelain-branch` for that one.

  :branch is nil -- NEVER a commit id, and never a name that is not in :branches --
  in the two states where git will name one anyway. A DETACHED head is the first:
  `git status --porcelain=v2 --branch` answers the literal \"(detached)\" where
  `rev-parse --abbrev-ref HEAD` used to answer \"HEAD\", and neither is a branch
  anybody can switch back to. An UNBORN head (a `git init` with no commit yet) is
  the second, and it is the same trap in different clothes: that command exits 0
  and cheerfully offers `master`, while the listing such a name would have to
  appear in is empty.

  :dirty counts the entries the same command reports -- modified, staged, untracked
  alike. It is shown, not enforced: the count is why a switch may be about to be
  refused, and saying so before the click is kinder than the refusal after it.

  ONE COMMAND ANSWERS THREE OF THE FOUR FACTS. `status --porcelain=v2 --branch`
  says whether this is a repository at all (its own exit 128 says it is not), which
  branch it is on, and what is in the way; `branch --format` is then the second and
  last spawn, because that format carries no branch listing. Two processes where
  four used to run -- `porcelain-branch` below holds the mapping that replaced them,
  including the one repository it now calls 'not a repository' that the old probe
  did not.

  :branches is the LOCAL branches, newest-commit first (git's own order for
  --sort=-committerdate), because the branch somebody wants is almost always the
  one they were just on -- and :branch, when it is not nil, is always one of them.
  A detached head adds nothing to that listing; `local-branches` is where the line
  between a branch and a sentence about the current head is drawn. A nil DIR, one that does not exist, or one that is a file
  answers {:repo? false} WITHOUT SPAWNING ANYTHING -- a session rebound away from
  a directory that has since been deleted is a real state, and `harness.infra.shell/run`
  throws rather than reporting failure when its working directory is not there, so
  the existence check is what keeps a deleted directory an answer instead of a
  500."
  [dir]
  (if (or (str/blank? (str dir)) (not (.isDirectory (io/file (str dir)))))
    {:repo? false}
    ;; TWO SPAWNS, and the second one carries the only fact porcelain cannot:
    ;; the branch listing. Everything else is in the answer to this one command.
    (let [status (git dir "status" "--porcelain=v2" "--branch")]
      ;; 128 IS GIT SAYING 'NOT A REPOSITORY' (or 'not a work tree', which a bare
      ;; repository and `.git` itself both answer), and that is exactly the set the
      ;; old `rev-parse --is-inside-work-tree` probe refused. A failure that is NOT
      ;; 128 lands here too rather than throwing: a caller draws a strip out of
      ;; this, and 'we could not read it' has never been one of the things this
      ;; namespace reports as an exception -- the old code answered {:repo? false}
      ;; for a missing git and for a timeout by the very same route.
      (if (not= 0 (:exit status))
        {:repo? false}
        (let [list (git dir "branch" "--format=%(refname)" "--sort=-committerdate")]
          {:repo? true
           :branch (porcelain-branch (:out status))
           :branches (if (= 0 (:exit list))
                       (local-branches (:out list))
                       [])
           :dirty (porcelain-dirty (:out status))})))))

(defn switch!
  "Move DIR's working tree onto BRANCH, and answer `state` afterwards.

  REFUSES A BRANCH THE WORKTREE DOES NOT HAVE, BY NAME, before spawning anything:
  the listing `state` already read is the allow-list, and it is also what makes
  the value safe to interpolate. A name that is there and still fails is git's own
  refusal (a dirty tree, a branch held by another worktree) and travels back in
  git's words, because those sentences are better than any paraphrase of them --
  they name the file or the worktree that is in the way.

  NO --force, EVER: see the namespace docstring. Answers
  {:ok {:branch .. :branches .. :dirty ..}} or {:error \"git's own sentence\"}."
  [dir branch]
  (let [before (state dir)
        wanted (str branch)]
    (cond
      (not (:repo? before))
      {:error (str (or dir "no directory") " is not a git working tree")}

      (str/blank? wanted)
      {:error "no branch named"}

      (not (some #(= % wanted) (:branches before)))
      {:error (str "this worktree has no branch " (pr-str wanted)
                   "; it has " (pr-str (:branches before)))}

      (= wanted (:branch before))
      ;; Already there. Not an error and not a switch: running git would be a
      ;; no-op that still prints nothing, and answering 'switched' would be a
      ;; claim about a move that did not happen.
      {:ok before}

      :else
      (let [switched (git dir "checkout" wanted)]
        (if (= 0 (:exit switched))
          {:ok (state dir)}
          ;; THE WHOLE OF STDERR, not its first line, and that is the point of
          ;; handing git's sentence back at all: the first line of a refused
          ;; checkout is "error: Your local changes to the following files would
          ;; be overwritten by checkout:" and the file NAMES -- the only part a
          ;; reader can act on -- are on the lines under it. A refusal that says
          ;; something is in the way without saying what is worse than no
          ;; refusal.
          {:error (or (not-empty (:err switched))
                      (not-empty (:out switched))
                      (str "git checkout exited " (:exit switched)))})))))
