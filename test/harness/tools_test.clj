(ns harness.tools-test
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [harness.project :as project]
            [harness.tools :as tools]))

(def ^:private dir (str (System/getProperty "java.io.tmpdir") "/harness-tools-test"))

;; Every test in this namespace shares one scratch directory; start it clean.
;; Done at load time rather than in a test, so ordering cannot break it.
(io/delete-file dir true)

(defn- call [name args]
  (tools/run! {:function {:name name :arguments (json/write-str args)}}))

;; ...and the ONE session that is deliberately the other editing mode. Everything
;; else here runs unbound, which since ticket 12 means anchor editing.
(use-fixtures :each
  (fn [f]
    (let [f' (io/file dir ".harness" "harness.edn")]
      (.mkdirs (.getParentFile f'))
      (spit f' "{:editing {:mode :str-replace}}" :encoding "UTF-8"))
    (project/bind! "tt-strrep-toolset" dir)
    (project/bind! "tt-edit" dir)
    (f)
    (project/bind! "tt-strrep-toolset" nil)
    (project/bind! "tt-edit" nil)
    (io/delete-file (io/file dir ".harness") true)))

(defn- tmp [name] (str dir "/" name))

(defn- read-lines
  "What `read` returned, as plain lines -- with the `anchor│` prefix stripped when
  this session's mode puts one there.

  Asserting CONTENT through this rather than against the raw answer is what makes
  a case about content true in EITHER editing mode. The row SHAPE is the mode's own
  business and has its own tests (harness.hashline-read-test); a case here that
  spelled the rows out would be a case about the default, wearing the name of a
  case about reading."
  [content]
  (mapv (fn [line]
          (if-let [i (str/index-of line "│")]
            (subs line (inc i))
            line))
        (str/split-lines content)))

(defn- bind-mode!
  "Bind THREAD-ID to DIR with MODE selected project-side. Used by the cases that
  are about one mode's BEHAVIOUR -- `edit`'s, above all -- so they say which mode
  they mean instead of inheriting whatever the default happens to be this month."
  [thread-id dir mode]
  (project/bind! thread-id dir)
  (let [f (io/file dir ".harness" "harness.edn")]
    (.mkdirs (.getParentFile f))
    (spit f (str "{:editing {:mode " mode "}}") :encoding "UTF-8")))

(deftest write-then-read-roundtrips
  (let [p (tmp "round-trip.txt")]
    (is (false? (:error (call "write" {:path p :content "第一行\nsecond"}))))
    (let [{:keys [content error]} (call "read" {:path p})]
      (is (false? error))
      (is (= ["第一行" "second"] (read-lines content))))))

(deftest edit-requires-an-exact-unique-match
  ;; RUN IN STR-REPLACE MODE, explicitly. `edit`'s behaviour is unchanged and this
  ;; is still the case that guards it -- what changed is that it is no longer the
  ;; default, so the case has to name the mode it is testing rather than assume it.
  (let [p (tmp "edit.txt")
        ;; One thread bound to a STR-REPLACE project, one thread bound to NOTHING
        ;; (which since ticket 12 means anchor editing). The path is absolute, so
        ;; the unbound thread needs no binding to find the file.
        run (fn [name args] (tools/run! {:function {:name name
                                                    :arguments (json/write-str args)}}
                                        "tt-edit"))
        run-default (fn [name args]
                      (tools/run! {:function {:name name
                                              :arguments (json/write-str args)}}))]
    (bind-mode! "tt-edit" dir ":str-replace")
    (run "write" {:path p :content "alpha beta gamma"})
    (testing "a unique match is replaced"
      (is (false? (:error (run "edit" {:path p :old_string "beta" :new_string "BETA"}))))
      (is (= ["alpha BETA gamma"] (read-lines (:content (run "read" {:path p}))))))
    (testing "a missing match is an error, not a silent no-op"
      (let [{:keys [content error]} (run "edit" {:path p :old_string "nope" :new_string "x"})]
        (is (true? error))
        (is (str/includes? content "not found"))))
    (testing "an ambiguous match is refused"
      (run "write" {:path p :content "aa"})
      (let [{:keys [content error]} (run "edit" {:path p :old_string "a" :new_string "b"})]
        (is (true? error))
        (is (str/includes? content "2 times"))))
    (testing "and in the DEFAULT mode the same call is refused by name instead"
      ;; The flip, seen from the tool that lost its place in the default toolset.
      (run-default "write" {:path p :content "alpha beta gamma"})
      (let [{:keys [content error]} (run-default "edit" {:path p :old_string "beta"
                                                         :new_string "BETA"})]
        (is (true? error))
        (is (str/includes? content "not served"))
        (is (str/includes? content "replace") "and it names what to use instead")
        (is (= "alpha beta gamma" (slurp p :encoding "UTF-8"))
            "and nothing was written")))))

(defn- shell-name-on-this-host
  "What `uname -s` must report on THIS machine, for the shell the bash tool runs.

  On Windows the tool pins Git Bash by absolute path, because the `bash` on PATH
  is C:\\WINDOWS\\System32\\bash.exe -- the WSL launcher, a different filesystem
  entirely, which fails silently from a JVM. So the Windows answer starts MINGW.

  Everywhere else the tool runs the host's own shell: there is no second
  filesystem to reach by accident, and the answer is simply the host's name. The
  invariant the assertion below is after is the same on every platform -- the
  shell belongs to THIS machine -- but the spelling of the answer is not, which
  is why it is computed rather than written down."
  []
  (let [os (str/lower-case (System/getProperty "os.name"))]
    (cond
      (str/includes? os "win")   "mingw"
      (str/includes? os "mac")   "darwin"
      (str/includes? os "linux") "linux"
      :else                      os)))

(deftest bash-runs-the-hosts-own-shell-not-wsl
  (let [{:keys [content error]} (call "bash" {:command "uname -s"})]
    (is (false? error))
    (testing "uname names this machine's shell"
      ;; On Windows: Git Bash (MINGW), pinned by path so the WSL launcher -- a
      ;; different filesystem, silent from a JVM -- is never reached. Elsewhere
      ;; the host's own shell. Both are the same claim about which filesystem
      ;; the shell sees; only the name it answers with differs.
      (is (str/includes? (str/lower-case content)
                         (shell-name-on-this-host))
          (str "uname -s reported " (pr-str content)
               ", which does not name this host (" (shell-name-on-this-host) ")"))))
  (testing "the working directory is the project"
    ;; Assert the invariant, not a substring of the project's name: bash must be
    ;; sitting in the same directory the JVM considers its own. Git Bash reports
    ;; POSIX paths (/c/Users/...) where the JVM says C:\Users\..., so compare the
    ;; normalized tail -- the drive letter is the only legitimate difference.
    (let [pwd  (str/trim (:content (call "bash" {:command "pwd"})))
          cwd  (System/getProperty "user.dir")
          norm (fn [p] (-> p (str/replace "\\" "/") (str/replace #"^[A-Za-z]:" "")
                           (str/replace #"^/c/" "/") (str/replace #"/+$" "")))]
      (is (= (norm cwd) (norm pwd)))))
  (testing "a non-zero exit is surfaced, not swallowed"
    (let [{:keys [content error]} (call "bash" {:command "exit 3"})]
      (is (false? error))
      (is (str/includes? content "[exit 3]")))))

(deftest eval-captures-stdout-and-value
  (let [{:keys [content error]} (call "eval" {:code "(println \"hi\") (+ 1 2)"})]
    (is (false? error))
    (is (str/includes? content "hi"))
    (is (str/includes? content "3"))))

(deftest eval-defs-persist-across-calls
  (call "eval" {:code "(def persist-probe 41)"})
  (is (= "42" (:content (call "eval" {:code "(inc persist-probe)"})))))

(deftest eval-truncates-runaway-output
  (let [{:keys [content]} (call "eval" {:code "(apply str (repeat 9000 \"a\"))"})]
    (is (str/ends-with? content "...[truncated]"))
    (is (< (count content) 8100))))

(deftest eval-reports-errors-as-content
  (let [{:keys [content error]} (call "eval" {:code "(throw (ex-info \"boom\" {}))"})]
    (is (true? error))
    (is (str/includes? content "boom"))))

(deftest malformed-arguments-are-an-error-not-a-crash
  (let [{:keys [content error]} (tools/run! {:function {:name "read" :arguments "{not json"}})]
    (is (true? error))
    (is (string? content)))
  (testing "the lifecycle still closes: execute carries the error, post always arrives"
    ;; Malformed JSON dies before the pass branch starts, so there is no
    ;; :tool/pre-execute -- but post-execute must never be skipped.
    (let [seen (atom [])]
      (tools/run! {:function {:name "read" :arguments "{not json"}}
                  nil #(swap! seen conj %))
      (is (= [:tool/execute :tool/post-execute] (mapv :type @seen)))
      (is (string? (:error (first @seen)))))))

(deftest missing-args-and-unknown-tools-are-errors
  (let [{:keys [content error]} (call "read" {})]
    (is (true? error))
    (is (str/includes? content "missing required argument")))
  (is (true? (:error (call "nope" {})))))

(deftest specs-expose-every-base-tool
  ;; TWO lists, because there are two toolsets and the default is now the anchor
  ;; one (ticket 12). Sorted, so this doubles as a check that every tool is
  ;; registered like any other -- `session-configure` is only special in being
  ;; marked for approval, which is a property of the tool, not of the list.
  (testing "the default session is served the anchor toolset"
    (let [names (mapv #(get-in % [:function :name]) (tools/specs))]
      (is (= ["anchor_grep" "bash" "eval" "insert" "read" "replace"
              "session-configure" "skill" "undo_last_replace" "write"]
             names))
      (is (every? #(seq (get-in % [:function :description])) (tools/specs)))))
  (testing "and a session that asks for the exact-string editor gets it"
    (let [names (mapv #(get-in % [:function :name])
                      (tools/specs "tt-strrep-toolset"))]
      (is (= ["bash" "edit" "eval" "read" "session-configure" "skill" "write"]
             names)))))

(deftest a-bound-session-roots-relative-paths-at-its-project
  (let [pdir (str (System/getProperty "java.io.tmpdir") "/harness-tools-project")]
    (io/delete-file pdir true)
    (.mkdirs (io/file pdir))
    (project/bind! "tt-bound" pdir)
    (letfn [(call-as [name args]
              ;; run! binds *thread-id* to this thread around the tool body --
              ;; exactly what a real run does -- which is what makes the tools
              ;; consult the binding.
              (tools/run! {:function {:name name :arguments (json/write-str args)}}
                          "tt-bound"))]
      (testing "a relative write lands in the project directory"
        (let [{:keys [content error]} (call-as "write" {:path "marker.txt" :content "here"})]
          (is (false? error))
          (is (str/includes? content "marker.txt"))
          (is (= "here" (slurp (str (io/file pdir "marker.txt")) :encoding "UTF-8")))))
      (testing "a relative read reads from the project directory"
        (is (= ["here"] (read-lines (:content (call-as "read" {:path "marker.txt"}))))))
      (testing "an absolute path is not redirected"
        (is (= ["here"] (read-lines (:content (call-as "read"
                                                       {:path (str pdir "/marker.txt")}))))))
      (testing "a relative edit re-roots too"
        ;; An ANCHOR edit, because that is what this session is served; the path
        ;; arithmetic being tested is the same one `edit` uses, which is what the
        ;; case is about.
        (let [anchor (first (map (fn [row] (subs row 0 (str/index-of row "│")))
                                 (str/split-lines (:content (call-as "read"
                                                                     {:path "marker.txt"})))))
              {:keys [error]} (call-as "replace" {:remove_from anchor
                                                  :replacement_lines ["there"]})]
          (is (false? error))
          (is (= "there" (slurp (str (io/file pdir "marker.txt")) :encoding "UTF-8")))))
      (testing "bash runs with the project directory as its cwd"
        ;; Proven WITHOUT parsing pwd: Git Bash prints POSIX-style paths
        ;; (/c/Users/...) where the JVM says C:\Users\..., so instead cat a
        ;; file that exists only in the project directory -- a relative cat
        ;; finding it proves exactly where bash was sitting.
        (let [{:keys [content error]} (call-as "bash" {:command "cat marker.txt"})]
          (is (false? error))
          (is (str/includes? content "there")))))
    (testing "an unbound thread still resolves relative to the process cwd"
      ;; The same relative read through a DIFFERENT thread: no binding, so the
      ;; path passes through unchanged and lands in the process cwd.
      (let [{:keys [content error]}
            (tools/run! {:function {:name "read"
                                    :arguments (json/write-str {:path "deps.edn"})}}
                        "tt-unbound")]
        (is (false? error))
        (is (some #(str/includes? % "{:paths") (read-lines content)))))))
