(ns harness.cap.git-test
  "The session's working tree, exercised against REAL repositories this test
  builds in a temp directory.

  A stubbed git would test the stub. The three things worth knowing are all
  properties of git itself -- that a branch listing is what it says it is, that a
  switch actually moves HEAD, and that git refuses a dirty switch in a sentence
  nobody here wrote -- so the fixtures below are real `git init`s and the
  assertions are read back off the repository rather than off our own answer."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
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

(defn- scratch-repo
  "A fresh repository at DIR with one commit on `main` and a second branch `side`
  pointing at the same commit. Identity is set per-repository so the fixture does
  not depend on -- or touch -- the developer's global git config.

  THE DEFAULT BRANCH IS RENAMED RATHER THAN NAMED AT INIT, and that is a real
  constraint rather than tidiness: this machine runs git 2.23, where `git init -b`
  does not exist yet (`--initial-branch` arrived in 2.28). The product code stays
  inside the same fence deliberately -- it uses `rev-parse`, `branch --format`,
  `status --porcelain` and `checkout`, all of which are years older than that --
  and this fixture would be the first thing to notice if it did not."
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
