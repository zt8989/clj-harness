(ns harness.cap.git-test
  "The session's working tree, exercised against REAL repositories this test
  builds in a temp directory.

  A stubbed git would test the stub. The three things worth knowing are all
  properties of git itself -- that a branch listing is what it says it is, that a
  switch actually moves HEAD, and that git refuses a dirty switch in a sentence
  nobody here wrote -- so the fixture is a real `git init` and the assertions are
  read back off the repository rather than off our own answer.

  IT IS BUILT ONCE AND COPIED PER CASE. Every case wants the same repository and
  every case wants it clean, so the eight processes that make it are spent once and
  each case gets a copy -- which costs no process at all, and is the stronger
  isolation besides. See `scratch-repo`."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [harness.cap.git :as git]
            [harness.test-support :as support]
            [harness.infra.shell :as shell]))

(defn- run-in
  "Run COMMAND in DIR through the same seam the namespace under test uses. Returns
  trimmed stdout, and THROWS on a non-zero exit -- a fixture that failed to set
  itself up must not be mistaken for the assertion under it failing."
  [dir command]
  (let [{:keys [exit out err]} (shell/run {:command command :dir dir :timeout-ms 20000})]
    (when-not (= 0 exit)
      (throw (ex-info (str "fixture command failed: " command) {:exit exit :err err})))
    (str/trim (str out))))

(defn- build-repo!
  "A repository at DIR, built by REAL git: one commit on `main` and a second branch
  `side` pointing at the same commit. Identity is set per-repository so the fixture
  does not depend on -- or touch -- the developer's global git config.

  THIS IS THE EIGHT-PROCESS PART AND IT RUNS ONCE PER NAMESPACE, which is why
  everything else copies its answer instead (`scratch-repo`). It is still git that
  builds it, and that is not an implementation detail: every assertion in this file
  is read back OUT of a repository git made, so a pre-fabricated `.git` would
  replace the thing under test rather than speed it up. What was worth saving is the
  NUMBER of spawns, never the realness of the repository.

  THE DEFAULT BRANCH IS RENAMED RATHER THAN NAMED AT INIT, and that is a real
  constraint rather than tidiness: this machine runs git 2.23, where `git init -b`
  does not exist yet (`--initial-branch` arrived in 2.28). The product code stays
  inside the same fence deliberately -- it uses `status --porcelain=v2 --branch`
  (2.11), `branch --format` and `checkout`, all of which are years older than that
  -- and this fixture would be the first thing to notice if it did not."
  [dir]
  (.mkdirs (io/file dir))
  (run-in dir "git init -q")
  (run-in dir "git config user.email test@example.invalid")
  (run-in dir "git config user.name 'harness test'")
  (run-in dir "git config commit.gpgsign false")
  (spit (io/file dir "README.md") "hello\n" :encoding "UTF-8")
  (run-in dir "git add README.md")
  (run-in dir "git commit -q -m first")
  (run-in dir "git branch -m main")
  (run-in dir "git branch side")
  dir)

(def ^:private template-root
  "The per-process root the template is built under, from the same
  `support/temp-dir` every other fixture here uses -- it is under the system temp
  directory but uniquely named for THIS process, so two runs side by side (the
  suite and a `--scripted` walkthrough, say) cannot build into one directory. See
  `docs/rules/testing.md`."
  (support/temp-dir "git-template"))

(def ^:private template
  "The one repository this namespace builds, and the reason it builds only one.

  BUILT ONCE PER RUN RATHER THAN ONCE PER PROCESS, which is why this is an atom and
  not a delay: the `:once` fixture below deletes the template when its run ends -- a
  directory left behind in the system temp directory is the thing
  `docs/rules/testing.md` is about -- and a delay would hand a second run of this
  namespace in the same JVM the path of a directory that is no longer there."
  (atom nil))

(defn- template-repo
  "The template, built if this run has not built it yet. THE EIGHT PROCESSES HAPPEN
  HERE, at most once per run; every case below copies the answer instead."
  []
  (or @template
      (reset! template (build-repo! (str template-root "/repo")))))

(use-fixtures :once
  (fn [run]
    ;; Before any case runs, so that a template which failed to build reports as a
    ;; fixture failure rather than as every case in the file failing on its own.
    (template-repo)
    (try (run)
         ;; THE TEMPLATE GOES WITH THE RUN. The copies the cases made live under
         ;; their own roots, not this one -- see `docs/rules/testing.md` for why a
         ;; directory that outlives the run is worth this trouble at all.
         (finally (support/wipe-tree! template-root)
                  (reset! template nil)))))

(defn- copy-tree!
  "SRC copied to DST with plain file operations -- no process, which is the whole
  point: this is what replaced seven of the eight spawns per case.

  PATHS ARE RELATIVIZED WITH `java.nio.file.Path`, NOT SLICED AS STRINGS. On Windows
  `File.getPath` answers with backslashes, so subtracting a prefix spelled with
  slashes matches nothing and the copy lands in the wrong place or nowhere --
  `harness.layers-test`'s own docstring is about the day that happened, and this is
  the version of the arithmetic that means the same thing on both platforms."
  [src dst]
  (let [root (io/file src)
        src  (.toPath root)
        dst  (.toPath (io/file dst))
        none (make-array java.nio.file.attribute.FileAttribute 0)]
    (doseq [f (file-seq root)]
      (let [target (.resolve dst (.relativize src (.toPath f)))]
        (if (.isDirectory f)
          (java.nio.file.Files/createDirectories target none)
          (do (java.nio.file.Files/createDirectories (.getParent target) none)
              (java.nio.file.Files/copy (.toPath f) target
                                        (make-array java.nio.file.CopyOption 0))))))))

(defn- scratch-repo
  "A fresh repository at DIR: a COPY of the template, which is what eight processes
  bought for every case until this was written.

  THE COPY IS THE ISOLATION, and it is a stronger one than cleaning a shared
  repository would be. Cases in this file DO write -- an untracked file, a modified
  file, a commit that makes the two branches actually differ -- and `git clean`
  would have to be trusted to undo exactly that and nothing else. A copy cannot fail
  to undo anything: 'did the last case leave something behind' has no way to be
  answered wrongly, and the dirty-switch cases below are where a wrong answer would
  go unnoticed, because a repository somebody else dirtied makes 'git refused' true
  for the wrong reason."
  [dir]
  (copy-tree! (template-repo) dir)
  dir)

(def ^:private dir
  (support/temp-dir "git"))

(deftest a-directory-that-is-not-a-repository-is-an-answer-not-a-failure
  ;; The strip has to draw for every session, and most directories are not
  ;; repositories. Both of the empty cases answer the same shape, and neither
  ;; spawns git: a nil directory is a real state (a session with no project), and
  ;; an existing one that is not a repository is the common case.
  (testing "no directory at all"
    (is (= {:repo? false} (git/state nil)))
    (is (= {:repo? false} (git/state ""))))
  (testing "a directory that exists but is not a repository"
    (let [plain (support/temp-dir "git-plain")]
      (is (= {:repo? false} (git/state plain)))))
  (testing "a directory that does not exist"
    (is (= {:repo? false} (git/state (str dir "-nope"))))))

(deftest state-reads-the-branch-the-branches-and-what-is-in-the-way
  (let [repo (scratch-repo dir)]
    (testing "on a clean first commit"
      (let [s (git/state repo)]
        (is (true? (:repo? s)))
        (is (= "main" (:branch s)))
        (is (= #{"main" "side"} (set (:branches s))))
        (is (zero? (:dirty s)))))
    (testing "an untracked file is one change in the way"
      (spit (io/file repo "scratch.txt") "x\n" :encoding "UTF-8")
      (is (= 1 (:dirty (git/state repo)))))
    (testing "and the branch is still readable while it is dirty"
      (is (= "main" (:branch (git/state repo)))))))

(deftest a-detached-head-is-no-branch-and-nothing-to-switch-back-to
  ;; BOTH HALVES OF THE SAME LIE ARE CHECKED HERE. :branch has to name something
  ;; that is IN :branches, and a detached head names nothing: the commit is real,
  ;; but it is not a branch. porcelain answers the literal (detached) where the old
  ;; probe answered the literal HEAD, and both are refused for one reason.
  ;;
  ;; The second half is the listing, and it was wrong until this test was written:
  ;; git prints a line for the detached head too, whose refname is the whole
  ;; sentence (HEAD detached at <sha>) -- and asked for :short, it came back
  ;; looking exactly like a name, so the strip offered a branch nobody could switch
  ;; to. The set below is the assertion that catches it.
  (let [repo (scratch-repo (str dir "-detached"))]
    (run-in repo "git checkout -q --detach HEAD")
    (let [s (git/state repo)]
      (is (true? (:repo? s)))
      (is (nil? (:branch s)) "porcelain answers (detached); that is no branch")
      (is (= #{"main" "side"} (set (:branches s)))
          "the branches that exist, and not the (HEAD detached at ..) sentence"))))

(deftest an-unborn-head-is-no-branch-and-not-the-name-git-offers
  ;; The row of the mapping that is new, and the reason it is a row at all. An
  ;; unborn head -- a `git init` with no commit yet -- gets exit 0 from porcelain,
  ;; a `# branch.oid (initial)` AND a cheerful `# branch.head master`, while
  ;; `branch --format` lists nothing. Copying that name out would draw a branch
  ;; that is in no picker; the old `rev-parse` exited 128 there and answered nil.
  (let [repo (support/temp-dir "git-unborn")
        prefix "# branch.head "]
    (.mkdirs (io/file repo))
    (run-in repo "git init -q")
    (run-in repo "git config user.email test@example.invalid")
    (run-in repo "git config user.name 'harness test'")
    (spit (io/file repo "first.txt") "x\n" :encoding "UTF-8")
    (let [s (git/state repo)
          offered (some (fn [line]
                          (when (str/starts-with? line prefix) (subs line (count prefix))))
                        (str/split-lines (run-in repo "git status --porcelain=v2 --branch")))]
      (is (some? offered) "porcelain really does offer a name here -- that is the trap")
      (is (true? (:repo? s)) "a repository with no commit is still a repository")
      (is (nil? (:branch s)) "and the name it offers is not one")
      (is (= [] (:branches s)) "because the listing it would have to appear in is empty")
      (is (not (some #(= % offered) (:branches s))))
      (is (= 1 (:dirty s)) "the untracked file counts, by --porcelain's own rule"))))

(deftest switching-moves-head-and-says-so
  (let [repo (scratch-repo (str dir "-switch"))]
    (testing "a branch the worktree has really moves it"
      (let [answer (git/switch! repo "side")]
        (is (nil? (:error answer)))
        (is (= "side" (:branch (:ok answer))))))
    (testing "and the repository agrees, not just our answer"
      (is (= "side" (run-in repo "git rev-parse --abbrev-ref HEAD"))))
    (testing "switching where it already is is not an error, and not a move"
      (let [answer (git/switch! repo "side")]
        (is (= "side" (:branch (:ok answer))))))))

(deftest a-branch-this-worktree-does-not-have-is-refused-by-name
  ;; The refusal is also the guard: the name that reaches the shell is one git
  ;; printed a moment ago. A name that gets through anyway must not be able to
  ;; smuggle anything into the command line built from it.
  (let [repo (scratch-repo (str dir "-refuse"))]
    (testing "an ordinary unknown name"
      (let [answer (git/switch! repo "no-such-branch")]
        (is (str/includes? (:error answer) "no branch"))
        (is (str/includes? (:error answer) "no-such-branch"))))
    (testing "the refusal lists what it could have been"
      (is (str/includes? (:error (git/switch! repo "nope")) "side")))
    (testing "a shell metacharacter gets no further than the listing"
      (let [answer (git/switch! repo "main; touch /tmp/harness-git-pwned")]
        (is (some? (:error answer)))
        (is (not (.exists (io/file "/tmp/harness-git-pwned")))
            "a branch name must never reach a shell as more than one word")))
    (testing "and nothing moved"
      (is (= "main" (:branch (git/state repo)))))))

(deftest a-dirty-switch-is-refused-in-gits-own-words
  ;; The refusal is git's, not ours: it names the file in the way, and that
  ;; sentence is more use than any paraphrase. Nothing here passes --force.
  ;;
  ;; THE BRANCHES HAVE TO DIFFER FIRST, and that is the whole subtlety: with both
  ;; pointing at the same commit, a modified file carries over and git allows the
  ;; switch -- there is nothing to overwrite. So main gains a commit of its own,
  ;; and only then does the local edit have somewhere to conflict with.
  (let [repo (scratch-repo (str dir "-dirty"))]
    (spit (io/file repo "README.md") "committed on main\n" :encoding "UTF-8")
    (run-in repo "git commit -q -am second")
    (spit (io/file repo "README.md") "changed, not committed\n" :encoding "UTF-8")
    (let [answer (git/switch! repo "side")]
      (is (some? (:error answer)))
      (is (str/includes? (:error answer) "README.md")
          "git's refusal names the file, and we hand its sentence back untouched")
      (is (= "main" (run-in repo "git rev-parse --abbrev-ref HEAD"))))))

(deftest an-uncommitted-change-that-does-not-conflict-does-not-block-the-switch
  ;; The other half of the test above, and the reason it is worth having both:
  ;; 'dirty' is not by itself what stops a switch -- a conflict is. A count of
  ;; changed files is a hint that the switch MAY be refused, never a promise that
  ;; it will be, which is what the strip's warning has to say.
  (let [repo (scratch-repo (str dir "-clean-dirty"))]
    (spit (io/file repo "scratch.txt") "untracked\n" :encoding "UTF-8")
    (let [answer (git/switch! repo "side")]
      (is (nil? (:error answer)))
      (is (= "side" (:branch (:ok answer))))
      (is (= "untracked\n" (slurp (io/file repo "scratch.txt") :encoding "UTF-8"))
          "the working file came along, which is why git allowed it"))))

(deftest switching-needs-a-directory
  (is (str/includes? (:error (git/switch! nil "main")) "not a git working tree"))
  (is (str/includes? (:error (git/switch! "/tmp" "main")) "not a git working tree")))

(deftest each-case-gets-its-own-repository-and-the-template-collects-nothing
  ;; THE COPY HAS TO BE AN ISOLATION AND NOT ONLY A SPEED-UP, and this is the case
  ;; that would catch it if it were not. TWO COPIES AT ONCE, rather than 'this case
  ;; against the one before it': clojure.test's order is not a promise this file
  ;; ever made, and what is being asked -- is a's writing invisible in b -- has an
  ;; answer whatever order the cases run in.
  ;;
  ;; Both halves matter for the same reason. A shared repository would make the
  ;; dirty-switch cases below pass for the wrong reason (git refuses in a repository
  ;; somebody else dirtied too), and a template that collected the cases' writes
  ;; would make the NEXT case start from a repository that is not the one this
  ;; file's assertions describe.
  (let [a (scratch-repo (str dir "-own-a"))
        b (scratch-repo (str dir "-own-b"))]
    (spit (io/file a "one-case-only.txt") "x\n" :encoding "UTF-8")
    (spit (io/file a "README.md") "changed in a\n" :encoding "UTF-8")
    (is (= 2 (:dirty (git/state a))) "a is exactly as dirty as this case made it")
    (is (zero? (:dirty (git/state b))) "and none of it reached b")
    (is (not (.exists (io/file b "one-case-only.txt"))))
    (is (= "hello\n" (slurp (io/file b "README.md") :encoding "UTF-8"))
        "b still has the file the one commit put there")
    (is (= (run-in a "git rev-parse HEAD") (run-in b "git rev-parse HEAD"))
        "both copies are of the same repository, made by git and not by hand")
    ;; Asserted on the FILES rather than with `git status`, because 'the template is
    ;; untouched' is exactly 'this case's writes are not in it' -- and a case that
    ;; asked git about the template would add two processes to a namespace whose
    ;; fixture cost is the thing `dev/scratch_git_spawns.clj` is measuring.
    (is (not (.exists (io/file (template-repo) "one-case-only.txt")))
        "and none of it reached the template every case copied")
    (is (= "hello\n" (slurp (io/file (template-repo) "README.md") :encoding "UTF-8"))
        "which is still the repository git made, untouched")))
