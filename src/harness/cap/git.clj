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

(defn state
  "DIR as a working tree, as the composer's directory+.
  branch strip needs it:

    {:repo? false}                                  not a repository, or no dir
    {:repo? true :branch \"main\" :branches [..] :dirty n}

  NOT BEING A REPOSITORY IS AN ORDINARY ANSWER, not a failure. A session can be
  bound to any directory, most directories are not repositories, and the strip
  simply has nothing to show for those -- which is why this answers a shape rather
  than throwing: a caller drawing a branch picker should not have to catch an
  exception to learn there is no branch to pick.

  :branch is nil on a DETACHED head, and that is reported rather than papered
  over with the commit id: `git rev-parse --abbrev-ref HEAD` answers the literal
  \"HEAD\" there, which is not a branch anybody can switch back to, and drawing it
  as one would offer a name that does not exist in :branches.

  :dirty counts the entries `git status --porcelain` reports -- modified, staged,
  untracked alike. It is shown, not enforced: the count is why a switch may be
  about to be refused, and saying so before the click is kinder than the refusal
  after it.

  :branches is the LOCAL branches, newest-commit first (git's own order for
  --sort=-committerdate), because the branch somebody wants is almost always the
  one they were just on. A nil DIR, one that does not exist, or one that is a file
  answers {:repo? false} WITHOUT SPAWNING ANYTHING -- a session rebound away from
  a directory that has since been deleted is a real state, and `harness.infra.shell/run`
  throws rather than reporting failure when its working directory is not there, so
  the existence check is what keeps a deleted directory an answer instead of a
  500."
  [dir]
  (if (or (str/blank? (str dir)) (not (.isDirectory (io/file (str dir)))))
    {:repo? false}
    (let [inside (git dir "rev-parse" "--is-inside-work-tree")]
      (if (or (not= 0 (:exit inside)) (not= "true" (:out inside)))
        {:repo? false}
        (let [head   (git dir "rev-parse" "--abbrev-ref" "HEAD")
              branch (let [b (:out head)]
                       (when (and (= 0 (:exit head)) (not= "HEAD" b) (not (str/blank? b)))
                         b))
              list   (git dir "branch" "--format=%(refname:short)" "--sort=-committerdate")
              status (git dir "status" "--porcelain")]
          {:repo? true
           :branch branch
           :branches (if (= 0 (:exit list))
                       (vec (remove str/blank? (str/split-lines (:out list))))
                       [])
           :dirty (if (= 0 (:exit status))
                    (count (remove str/blank? (str/split-lines (:out status))))
                    0)})))))

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
