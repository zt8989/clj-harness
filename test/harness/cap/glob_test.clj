(ns harness.cap.glob-test
  "`glob`: find files by NAME, with the ignore rules as the outer bound.

  TWO PROMISES ARE CHECKED HERE, and they are the ones that pull against each
  other. A file finder has to respect the tree's own idea of what its files are
  (`.gitignore`, `.git`), AND it has to let a pattern be a pattern. rg's `--glob`
  promises the second by OVERRIDING the first -- its help says so -- which is why
  a pattern like `**/*` lists a vendored `node_modules`. So the cases below come in
  pairs: the pattern finds what it names, and the ignored tree stays out of the
  answer even when the pattern names it."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [harness.test-support :as support]
            [harness.cap.project :as project]
            [harness.infra.rg :as rg]
            [harness.kernel.tools :as tools]))

(def ^:private root
  (str (io/file (System/getProperty "java.io.tmpdir") "harness-glob-test")))

(defn- rm-rf
  "Delete a directory tree. `clojure.java.io/delete-file` does NOT recurse: it
  calls File.delete, which fails on a non-empty directory and says nothing with
  `silently` true -- so a fixture that used it would leave the previous case's tree
  exactly where the next case searches."
  [f]
  (let [f (io/file f)]
    (when (.isDirectory f)
      (doseq [c (.listFiles f)] (rm-rf c)))
    (io/delete-file f true)))

(defn- put! [name content]
  (let [f (io/file root name)]
    (.mkdirs (.getParentFile f))
    (spit f content :encoding "UTF-8")
    f))

(defn- tree!
  "The tree the cases below search: two files at the root, a nesting under src, one
  file that `.gitignore` excludes, one under `.git`, and a dotfile.

  `.git` IS A REAL DIRECTORY because `rg` only honours .gitignore inside a
  repository (its `require-git` default). Without it the ignore case would search
  everything and 'pass' for the wrong reason."
  []
  (rm-rf root)
  (.mkdirs (io/file root ".git"))
  (.mkdirs (io/file root "src" "nested"))
  (.mkdirs (io/file root "ignored"))
  (put! ".gitignore" "ignored/\n")
  (put! ".git/config" "[core]\n")
  (put! "a.clj" "(ns a)")
  (put! "b.txt" "not clojure")
  (put! ".hidden.clj" "(ns hidden)")
  (put! "src/one.clj" "(ns one)")
  (put! "src/two.clj" "(ns two)")
  (put! "src/nested/deep.clj" "(ns deep)")
  (put! "ignored/vendored.clj" "(ns vendored)")
  root)

(def ^:private tid "glob-test")

(defn- canonical-root
  "ROOT as a bound session's paths are spelled.

  `project/bind!` STORES the canonical form and `resolve-path` re-roots through it,
  so a session bound to this tree answers with `/private/var/...` on macOS where
  `java.io.tmpdir` said `/var/...`. Both spellings are stripped below rather than
  betting on which one comes back: a name that failed to strip would compare as a
  full path, and the case would fail for a reason that has nothing to do with glob."
  []
  (try (.getCanonicalPath (io/file root)) (catch Exception _ root)))

(defn- rel
  [^String p]
  (let [prefixes (map #(str % "/") [root (canonical-root)])]
    (if-let [pfx (first (filter #(str/starts-with? p %) prefixes))]
      (subs p (count pfx))
      p)))

(use-fixtures :once support/with-builtins)

(use-fixtures :each
  (fn [f]
    (tree!)
    (project/bind! tid root)
    (tools/forget-turn!)
    (f)
    (tools/forget-turn!)))

(defn- call [name args]
  (tools/run! {:id "c" :type "function"
               :function {:name name :arguments (json/write-str args)}} tid))

(defn- glob!
  ([] (glob! {}))
  ([args] (call "glob" args)))

(defn- names
  "A glob answer's paths as names relative to ROOT, sorted, with the budget note
  dropped -- so a case reads as the set it is about."
  [content]
  (->> (str/split-lines content)
       (remove #(str/starts-with? % "[..."))
       (map rel)
       sort))

;; --------------------------------------------------------------- what it finds

(deftest a-pattern-finds-the-files-that-match-it-at-any-depth
  (let [{:keys [content error]} (glob! {:pattern "**/*.clj"})]
    (is (false? error) content)
    (testing "every clj file under the root, at every depth"
      (is (= [".hidden.clj" "a.clj" "src/nested/deep.clj" "src/one.clj" "src/two.clj"]
             (names content))))
    (testing "and the paths are absolute, so they can be handed straight to read"
      (is (every? #(str/starts-with? % "/") (str/split-lines content))))))

(deftest a-pattern-with-no-slash-matches-file-names-at-any-depth
  ;; rg's own convention, and the one a model means by `*.clj`: without a `/` the
  ;; pattern is a NAME, not a path. Getting this wrong would answer with the root's
  ;; own files and nothing else, which reads as 'the src tree has no clj files'.
  (is (= (names (:content (glob! {:pattern "**/*.clj"})))
         (names (:content (glob! {:pattern "*.clj"}))))))

(deftest hidden-files-are-listed
  (is (some #(= ".hidden.clj" %) (names (:content (glob! {:pattern "**/*.clj"}))))))

(deftest a-pattern-that-names-nothing-is-a-named-refusal
  (let [{:keys [content error]} (glob! {:pattern "**/*.rs"})]
    (is (true? error) "information for the model, not a run failure")
    (is (str/includes? content "no files match"))))

(deftest a-blank-pattern-is-refused
  ;; rg reads an empty `--glob` as 'match everything', so a blank pattern would
  ;; quietly become a request for the whole tree instead of a refusal.
  (doseq [pattern ["" "   "]]
    (let [{:keys [content error]} (glob! {:pattern pattern})]
      (is (true? error) (pr-str pattern))
      (is (str/includes? content "pattern") (pr-str pattern)))))

(deftest a-missing-pattern-is-the-ordinary-missing-argument-refusal
  (let [{:keys [content error]} (glob! {})]
    (is (true? error))
    (is (str/includes? content "missing required argument"))))

;; ------------------------------------------------------------- what stays out

(deftest the-ignore-rules-are-the-outer-bound-not-the-pattern
  ;; THE CASE THIS TOOL EXISTS TO GET RIGHT. rg's `--glob` 'always overrides any
  ;; other ignore logic' (its own help), so a pattern that matches the DIRECTORY
  ;; `ignored/` whitelists everything under it -- and `**/*`, which is an ordinary
  ;; thing to ask for, does exactly that. The answer is the intersection with rg's
  ;; plain listing precisely so this cannot happen.
  (testing "a pattern whose shape only matches files"
    (is (not (some #(str/starts-with? % "ignored/")
                   (names (:content (glob! {:pattern "**/*.clj"})))))))
  (testing "and a pattern that matches directories too"
    (let [{:keys [content error]} (glob! {:pattern "**/*"})]
      (is (false? error) content)
      (is (not (str/includes? content "ignored/"))
          "an ignored directory stays ignored whatever the pattern says")
      (is (not (str/includes? content "/.git/"))
          "and so does .git")
      (is (str/includes? content "a.clj") "while the files that are not ignored are all there"))))

(deftest the-search-can-be-narrowed-to-a-subtree
  (let [{:keys [content]} (glob! {:pattern "**/*.clj" :path "src"})]
    (is (= ["src/nested/deep.clj" "src/one.clj" "src/two.clj"] (names content))))
  (testing "a relative path resolves against the session's project directory"
    (is (= ["src/one.clj"] (names (:content (glob! {:pattern "**/one.clj" :path "."})))))))

(deftest the-fence-is-on-the-tool
  ;; The declaration is what makes an out-of-bounds root park for a human. It is
  ;; asked here rather than through a park because that is the mechanical half: a
  ;; tool that declares the rule and one that does not are indistinguishable until
  ;; somebody is interrupted, and the parking itself is the seam's ordinary
  ;; approval flow (tested where approvals are tested).
  (let [rule (:park-reason (get (tools/effective-tools tid) "glob"))]
    (is (fn? rule) "glob declares a park rule")
    (testing "which fires for a root outside the project and the configuration home"
      (is (= :out-of-bounds (rule tid {:pattern "**/*.clj" :path "/etc"}))))
    (testing "and stays quiet for one inside it"
      (is (nil? (rule tid {:pattern "**/*.clj" :path "src"}))))
    (testing "and for a call with no path at all -- the argument check says that, one line later"
      (is (nil? (rule tid {:pattern "**/*.clj"}))))))

;; ------------------------------------------------------------------- the order

(deftest the-answer-is-ordered-by-path-and-not-by-time
  (let [first-call  (glob! {:pattern "**/*.clj"})
        second-call (glob! {:pattern "**/*.clj"})]
    (testing "two identical calls over an unchanged tree answer identically"
      (is (= (:content first-call) (:content second-call))))
    (testing "and a file that just changed sorts where its NAME belongs"
      ;; `src/aaa.clj` is the newest thing in the tree, so an answer ordered by
      ;; modification time would put it first -- and would differ between two calls
      ;; that asked the same question.
      (put! "src/aaa.clj" "(ns aaa)")
      (.setLastModified (io/file root "src" "aaa.clj") (System/currentTimeMillis))
      (is (= ["src/aaa.clj" "src/nested/deep.clj" "src/one.clj" "src/two.clj"]
             (filter #(str/starts-with? % "src/")
                     (names (:content (glob! {:pattern "**/*.clj"})))))))))

;; -------------------------------------------------------------------- the cap

(deftest an-answer-too-big-to-print-is-capped-and-says-so
  (dotimes [i 105] (put! (str "many/f" (format "%03d" i) ".clj") "(ns many)"))
  (let [{:keys [content error]} (glob! {:pattern "**/*.clj"})]
    (is (false? error) content)
    (let [lines (str/split-lines content)]
      (is (= 101 (count lines)) "100 paths and the note that there are more")
      (is (str/starts-with? (last lines) "[..."))
      (is (str/includes? (last lines) "110")
          "the note counts every match, not just the ones printed")
      (is (str/includes? (last lines) "first 100")))))

;; --------------------------------------------------------- when rg is not there

(deftest a-missing-ripgrep-is-refused-by-name
  ;; rg is not optional and the message has to say what to install, so the case is
  ;; exercised by pointing the one var that names the executable at something that
  ;; is not there -- the same seam discipline as the rest of this repo: a var,
  ;; altered for the test and put back, never a PATH the host also uses.
  (let [real rg/binary]
    (try
      (alter-var-root #'rg/binary (constantly "definitely-not-ripgrep"))
      (let [{:keys [content error]} (glob! {:pattern "**/*.clj"})]
        (is (true? error))
        (is (str/includes? content "definitely-not-ripgrep"))
        (is (str/includes? content "PATH"))
        (is (str/includes? content "install ripgrep")
            "the refusal is the one that says what to install"))
      (finally
        (alter-var-root #'rg/binary (constantly real))))))

(deftest a-search-root-that-does-not-exist-blames-the-path-not-the-install
  ;; The pair of the case above, and the reason its detection is by EXIT CODE
  ;; rather than by the phrase `No such file or directory`: that phrase is also
  ;; what rg prints for a root that is not there, and answering 'install ripgrep'
  ;; for a typo'd path sends the reader to fix the wrong thing.
  (let [{:keys [content error]} (glob! {:pattern "**/*.clj" :path "no-such-dir"})]
    (is (true? error))
    (is (not (str/includes? content "install ripgrep")))
    (is (str/includes? content "the search failed"))))
