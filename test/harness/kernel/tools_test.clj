(ns harness.kernel.tools-test
  (:require [clojure.core.async :as async]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [harness.cap.jobs :as jobs]
            [harness.cap.project :as project]
            [harness.fake :as fake]
            [harness.infra.shell :as shell]
            [harness.kernel.loop :as loop]
            [harness.kernel.tools :as tools]
            [harness.test-support :as support]))

(use-fixtures :once support/with-builtins)

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
  business and has its own tests (harness.cap.hashline.read-test); a case here that
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
      (is (= ["anchor_grep" "bash" "bash_background" "bash_kill" "bash_output" "eval"
              "glob" "insert" "read" "replace" "session-configure" "skill"
              "todo_write" "undo_last_replace" "web_fetch" "web_search" "write"]
             names))
      (is (every? #(seq (get-in % [:function :description])) (tools/specs)))))
  (testing "and a session that asks for the exact-string editor gets it"
    (let [names (mapv #(get-in % [:function :name])
                      (tools/specs "tt-strrep-toolset"))]
      (is (= ["bash" "bash_background" "bash_kill" "bash_output" "edit" "eval" "glob"
              "read" "session-configure" "skill" "todo_write" "web_fetch"
              "web_search" "write"]
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

;; -------------------------------------------------------------------- the limit
;;
;; A `bash` call has a ceiling now. The DEFAULT is asserted at the seam rather
;; than by waiting two minutes for it: a `shell/run` stand-in records what the
;; tool asked for, and the body runs on this thread, so `with-redefs` reaches it
;; (harness.infra.env-test watches its own seam the same way). The MECHANISM is
;; asserted by really stopping a command, with a limit small enough to watch.

(deftest bash-waits-no-longer-than-it-was-told
  (let [asked (atom [])]
    (with-redefs [shell/run (fn [opts] (swap! asked conj opts) {:exit 0 :out "" :err ""})]
      (testing "no `timeout` argument means the default, and only one place knows it"
        (call "bash" {:command "true"})
        (is (= 120000 (:timeout-ms (first @asked)))))
      (testing "a `timeout` argument is what the call waits"
        (call "bash" {:command "true" :timeout 5000})
        (is (= 5000 (:timeout-ms (second @asked))))))))

(deftest a-timeout-that-is-not-a-number-of-milliseconds-is-refused
  ;; The same refusal `offset`/`limit` get, because the same helper answers it:
  ;; 0, -3 and 1.5 are all things a model can actually send.
  (doseq [bad [0 -3 1.5 "soon"]]
    (let [{:keys [content error]} (call "bash" {:command "true" :timeout bad})]
      (is (true? error) (str (pr-str bad) " is refused"))
      (is (str/includes? content "`timeout` must be a positive integer")))))

(deftest a-command-that-would-hang-is-stopped-at-the-limit
  ;; Through the TOOL, not just through `shell/run`: this is the path a model's call
  ;; takes, and the pid file is the command's own child -- the thing that used to
  ;; survive the call. `<cmd> &` plus `wait` keeps the shell around so the shape under
  ;; test really is two processes.
  (let [dir (support/temp-dir "tools-hang")
        pid-file (io/file dir "child.pid")
        started (System/currentTimeMillis)
        {:keys [content error]} (call "bash"
                                      {:command (str "echo said-before-hanging; sleep 60 & echo $! > "
                                                     (shell/quote-arg (.getAbsolutePath pid-file))
                                                     "; wait")
                                       :timeout 1500})
        elapsed (- (System/currentTimeMillis) started)]
    (try
      (testing "it is information, not a failed run"
        (is (false? error)))
      (testing "what it printed is kept, and the answer says where it stopped"
        (is (str/includes? content "said-before-hanging"))
        (is (str/includes? content "[timed out after 1500ms")))
      (testing "and the call came back at the limit, not at the end"
        (is (< elapsed 20000) (str "elapsed " elapsed "ms")))
      (testing "and the child the command started is gone with it"
        (let [pid (Long/parseLong (str/trim (slurp pid-file :encoding "UTF-8")))]
          (is (support/gone-within? pid 5000)
              (str "pid " pid " outlived the call that started it"))))
      (finally (support/wipe-tree! dir)))))

(deftest a-command-that-finishes-is-answered-exactly-as-it-was
  ;; The ceiling is not allowed to change any other answer: a quiet command, a
  ;; command with output, and a command that failed.
  (let [{:keys [content error]} (call "bash" {:command "echo hi"})]
    (is (false? error))
    (is (= "hi" (str/trim content))))
  (let [{:keys [content error]} (call "bash" {:command "echo boom >&2; exit 3"})]
    (is (false? error))
    (is (str/includes? content "boom"))
    (is (str/includes? content "[exit 3]"))
    (is (not (str/includes? content "timed out"))))
  (is (= "(no output)" (str/trim (:content (call "bash" {:command "true"}))))))

;; ------------------------------------------------------------------ background
;;
;; The faces are here; the arithmetic is harness.cap.jobs' own tests. What this
;; namespace owns is the two things only a tool call can show: where the command
;; runs, and how a refusal reaches the model.

(deftest a-background-command-runs-where-a-foreground-one-would
  (let [pdir (io/file dir "job-project")]
    (.mkdirs pdir)
    ;; The same trick the `bash` cwd case uses: a relative cat finding a file that
    ;; exists ONLY in the project directory proves where the command was sitting,
    ;; without parsing pwd (whose spelling differs between Git Bash and the JVM).
    (spit (io/file pdir "marker.txt") "job-here" :encoding "UTF-8")
    (project/bind! "tt-job" (.getAbsolutePath pdir))
    (try
      (let [answer (:content (tools/run! {:function {:name "bash_background"
                                                     :arguments (json/write-str
                                                                 {:command "cat marker.txt"})}}
                                         "tt-job"))
            job-id (second (re-find #"job (j\d+) started" answer))]
        (is (some? job-id) (str "the tool answered with a job id: " answer))
        (is (= ["job-here"]
               (:lines (support/read-until #(:content (tools/run!
                                                       {:function {:name "bash_output"
                                                                   :arguments (json/write-str
                                                                               {:job job-id})}}
                                                       "tt-job"))
                                           #(re-find #"\[exit" (:answer %)) 10000)))))
      (finally
        (jobs/shutdown!)
        (project/bind! "tt-job" nil)))))

(deftest a-background-call-comes-back-before-the-command-does
  ;; The whole point: the call is not the command's lifetime.
  (let [started (System/currentTimeMillis)
        answer  (:content (call "bash_background" {:command "sleep 30"}))
        elapsed (- (System/currentTimeMillis) started)]
    (is (re-find #"job j\d+ started" answer))
    (is (< elapsed 5000) (str "it returned while the command was still running (" elapsed "ms)"))
    (jobs/shutdown!)))

(deftest an-unknown-job-reaches-the-model-as-an-error
  (let [{:keys [content error]} (call "bash_output" {:job "j-not-a-job"})]
    (is (true? error))
    (is (str/includes? content "unknown job: j-not-a-job"))))

(deftest the-background-tools-take-the-arguments-they-need
  ;; The seam's missing-argument check is per tool, which is one of the reasons
  ;; these are three names rather than one with an `action`.
  (is (str/includes? (:content (call "bash_background" {})) "missing required argument"))
  (is (str/includes? (:content (call "bash_output" {})) "missing required argument")))

(deftest a-background-job-can-be-stopped-and-is-then-gone
  (let [started (:content (call "bash_background" {:command "sleep 30"}))
        job-id  (second (re-find #"job (j\d+) started" started))
        answer  (:content (call "bash_kill" {:job job-id}))]
    (is (some? job-id))
    (is (str/includes? answer "[stopped]"))
    (testing "and reading it afterwards is the same as reading one that never existed"
      (let [{:keys [content error]} (call "bash_output" {:job job-id})]
        (is (true? error))
        (is (str/includes? content (str "unknown job: " job-id))))))
  (testing "a job that ended on its own reports its exit code instead"
    (let [started (:content (call "bash_background" {:command "exit 3"}))
          job-id  (second (re-find #"job (j\d+) started" started))]
      ;; Wait for it to end before stopping it: `[stopped]` and `[exit 3]` are two
      ;; different facts, and only the second one is true once the command is gone.
      (support/read-until #(:content (tools/run! {:function {:name "bash_output"
                                                             :arguments (json/write-str {:job job-id})}}))
                          #(re-find #"\[exit" (:answer %)) 10000)
      (is (str/includes? (:content (call "bash_kill" {:job job-id})) "[exit 3]")))))

(deftest bash-kill-refuses-a-job-it-does-not-have
  (let [{:keys [content error]} (call "bash_kill" {:job "j-not-a-job"})]
    (is (true? error))
    (is (str/includes? content "unknown job: j-not-a-job"))))

;; ------------------------------------------------- the turn plan, per session
;;
;; `turn-plan` used to be ONE slot every session shared. Two sessions running at
;; once -- harness.edge.http gives every run its own go block -- would overwrite
;; each other's plan, and the anchor batch (one write for several edits to one
;; file) would silently degrade into concurrent edits that lose updates. It is
;; now keyed by thread-id and named by a token, so one turn's teardown cannot
;; delete another turn's plan. The cases below pin that, and the ORDER the seam
;; relies on.

(defn- a-call
  "One call in the provider's shape, with the id the plan is keyed by."
  [id name]
  {:id id :type "function"
   :function {:name name :arguments "{}"}})

(deftest a-turn-plan-is-keyed-by-thread-id
  (let [a "t-plan-a" b "t-plan-b"]
    (try
      (let [ta (tools/register-turn! a [(a-call "a1" "todo_write") (a-call "a2" "todo_write")])
            tb (tools/register-turn! b [(a-call "b1" "todo_write")])]
        (testing "each registration carries its own token"
          (is (not= ta tb)))
        (testing "the later registration changes no other thread-id's counts"
          (is (false? (binding [tools/*thread-id* a]
                        (tools/sole-call-of-its-name? "todo_write")))
              "a called todo_write twice, and b's one call does not un-say that")
          (is (true? (binding [tools/*thread-id* b]
                       (tools/sole-call-of-its-name? "todo_write")))))
        (testing "a thread-id that never registered reads no counts"
          (is (true? (binding [tools/*thread-id* "t-plan-never-registered"]
                       (tools/sole-call-of-its-name? "todo_write"))))))
      (finally (tools/forget-turn!)))))

(deftest forget-turn-only-drops-the-turn-that-owns-the-token
  (let [a "t-token-a" b "t-token-b"]
    (try
      (let [ta (tools/register-turn! a [(a-call "x1" "todo_write") (a-call "x2" "todo_write")])
            _  (tools/register-turn! b [(a-call "y1" "todo_write")])
            tb (tools/register-turn! b [(a-call "y2" "todo_write") (a-call "y3" "todo_write")])]
        (testing "a teardown whose token has been superseded changes nothing"
          (tools/forget-turn! b ta)
          (is (false? (binding [tools/*thread-id* b]
                        (tools/sole-call-of-its-name? "todo_write")))
              "b's LATER turn is still registered -- the stale token did not drop it"))
        (testing "the owning token drops only its own thread-id's entry"
          (tools/forget-turn! b tb)
          (is (true? (binding [tools/*thread-id* b]
                       (tools/sole-call-of-its-name? "todo_write"))))
          (is (false? (binding [tools/*thread-id* a]
                        (tools/sole-call-of-its-name? "todo_write")))
              "a's turn is untouched by b's teardown")))
      (finally (tools/forget-turn!)))))

(deftest batch-role-reads-only-its-own-thread-ids-plan
  ;; `batch-role` is private -- it is the seam's own question -- so the test asks it
  ;; the way the seam does: through the var, under the thread-id the seam binds.
  (let [a "t-role-a" b "t-role-b"]
    (try
      (let [teardown (tools/install!
                     {:name "id-planner"
                      :planner (fn [_tid calls]
                                 (into {} (map (fn [{:keys [id]}]
                                                 [id {:role :applier :run (constantly "ok")}]))
                                       calls))})]
        (try
          (tools/register-turn! a [(a-call "a1" "read")])
          (tools/register-turn! b [(a-call "b1" "read")])
          (is (some? (binding [tools/*thread-id* a] (#'tools/batch-role "a1")))
              "a's own call is in a's plan")
          (is (nil? (binding [tools/*thread-id* a] (#'tools/batch-role "b1")))
              "and b's call id is not")
          (is (nil? (binding [tools/*thread-id* b] (#'tools/batch-role "a1"))))
          (finally (teardown))))
      (finally (tools/forget-turn!)))))

(deftest the-seam-is-told-about-a-turn-before-any-of-it-runs
  ;; Two `todo_write` calls in one message: the body reads `sole-call-of-its-name?`
  ;; itself (harness.cap.tools), so the count of two has to be visible before either
  ;; body runs. A PLANNER THAT SLEEPS is what makes that deterministic rather than a
  ;; race: while it sleeps the seam is still inside `register-turn!`, so a seam that
  ;; spawned the tools first would leave them reading the empty plan -- the answer
  ;; the old code gave. Both calls are refused here because the turn genuinely holds
  ;; two of them.
  (let [tid "t-slow-plan"]
    (try
      (let [teardown (tools/install! {:name "slow-planner"
                                      :planner (fn [_tid _calls]
                                                 (Thread/sleep 200)
                                                 {})})]
        (try
          (let [ch  (loop/run-chan (fake/scripted
                                    [{:content ""
                                      :tool-calls [{:id "c1" :name "todo_write" :arguments {:todos []}}
                                                   {:id "c2" :name "todo_write" :arguments {:todos []}}]}
                                     {:content "done"}])
                                   [] {:thread-id tid})
                seen (loop [acc []]
                       (if-let [ev (async/<!! ch)]
                         (if (= :run/done (:type ev)) acc (recur (conj acc ev)))
                         acc))
                results (filter #(= :tool/result (:type %)) seen)]
            (is (= 2 (count results)))
            (is (every? :error results)
                "both calls see a turn that holds two todo_writes, so neither is sole"))
          (finally (teardown))))
      (finally (tools/forget-turn!)))))
