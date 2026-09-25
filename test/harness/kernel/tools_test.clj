(ns harness.kernel.tools-test
  (:require [clojure.core.async :as async]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [harness.cap.jobs :as jobs]
            [harness.cap.project :as project]
            [harness.fake :as fake]
            [harness.infra.home :as home]
            [harness.infra.shell :as shell]
            [harness.kernel.loop :as loop]
            [harness.kernel.tools :as tools]
            [harness.test-support :as support]))

(use-fixtures :once support/with-builtins)

;; Every test in this namespace shares one scratch directory. It is empty because
;; mkdtemp made it, so there is nothing to clear -- and there is nothing to collide
;; with, which a composed name under java.io.tmpdir could not promise.
(def ^:private dir (support/temp-dir "tools"))

(defn- call [name args]
  (tools/run! {:function {:name name :arguments (json/write-str args)}}))

(defn- background
  "`job` -- the verb that starts a job, and the ONE way to start one now that starting
  is its own name again. The answer is the id-and-path pair the cases below parse, and
  the second arity is the same call under a session of its own."
  ([command] (call "job" {:command command}))
  ([thread-id command] (tools/run! {:function {:name "job"
                                               :arguments (json/write-str {:command command})}}
                                   thread-id)))

(defn- record-file
  "The record JOB-ID left on disk, found by the id at the front of its name.

  NO ANSWER NAMES THIS PATH ANY MORE -- `job` and `job_kill` say `job_output` instead, and
  that verb asks by id (`.scratch/job-receipt-no-path`). A case whose subject is the FILE
  itself (what is in it, who may read it) therefore finds it the way the harness does:
  the session's directory under the configuration home, then the id.

  THREAD-ID may be nil -- that is the unbound session the one-arity `call` runs as, and
  its records land straight in `<home>/jobs`."
  [thread-id job-id]
  (let [d (io/file (home/root) "jobs" (home/sanitize thread-id))
        f (->> (or (.listFiles d) (make-array java.io.File 0))
               (filter #(str/starts-with? (.getName ^java.io.File %) (str job-id "-")))
               (first))]
    (when f (.getAbsolutePath ^java.io.File f))))

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
  ;; registered like any other -- a tool's own properties (an approval mark, a
  ;; read-only flag) belong to the TOOL, not to the list -- and `ask` is in BOTH,
  ;; because it belongs to no editing family: a session can ask a person a
  ;; question whichever way it edits files.
  (testing "the default session is served the anchor toolset"
    (let [names (mapv #(get-in % [:function :name]) (tools/specs))]
      (is (= ["ask" "bash" "eval" "glob" "grep" "insert" "job" "job_kill" "job_list"
              "job_output" "read" "replace" "skill" "todo_read" "todo_write"
              "undo_last_replace" "web_fetch" "web_search" "write"]
             names))
      (is (every? #(seq (get-in % [:function :description])) (tools/specs)))))
  (testing "and a session that asks for the exact-string editor gets it"
    (let [names (mapv #(get-in % [:function :name])
                      (tools/specs "tt-strrep-toolset"))]
      (is (= ["ask" "bash" "edit" "eval" "glob" "job" "job_kill" "job_list" "job_output"
              "read" "skill" "todo_read" "todo_write" "web_fetch" "web_search" "write"]
             names)))))

(deftest a-bound-session-roots-relative-paths-at-its-project
  (let [pdir (support/temp-dir "tools-project")]
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
        ;; The child asks the OS for its own pid (`support/child-command`): `$!` here
        ;; is MSYS's number, which `ProcessHandle/of` cannot resolve, and a
        ;; `gone-within?` asked about it answers 'gone' before anything was killed.
        {:keys [content error]} (call "bash"
                                      {:command (str "echo said-before-hanging; "
                                                     (support/child-command pid-file))
                                       :timeout 4000})
        elapsed (- (System/currentTimeMillis) started)]
    (try
      (testing "it is information, not a failed run"
        (is (false? error)))
      (testing "what it printed is kept, and the answer says where it stopped"
        (is (str/includes? content "said-before-hanging"))
        (is (str/includes? content "[timed out after 4000ms")))
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
;; ----------------------------------------------------------- the way in
;;
;; A `bash` call could only ever say things BY the command string: feeding a program
;; some text meant `echo … | …` (quoting, newlines and size all the model's
;; problem), and running it somewhere else meant a `cd … &&` prefix -- which the
;; `job` tool does not need, since it resolves its cwd the same way this one does.
;; These two arguments come from `infra.shell/run`, which has always taken them.

(deftest a-bash-call-carries-its-own-stdin
  ;; `sort` with nothing on the pipe would BLOCK rather than answer: the point of
  ;; the case is that the text got there AND that the stream was closed after it.
  (let [{:keys [content error]} (call "bash" {:command "sort" :stdin "b\na\n"})]
    (is (false? error))
    (is (= ["a" "b"] (str/split-lines content)))))

(deftest stdin-reaches-the-executor-as-the-call-gave-it
  ;; Through a stand-in, so the assertion is about what the TOOL asked for rather
  ;; than about a shell's spelling -- the same shape the timeout case uses.
  (let [asked (atom [])]
    (with-redefs [shell/run (fn [opts] (swap! asked conj opts) {:exit 0 :out "" :err ""})]
      (call "bash" {:command "true" :stdin "hello"})
      (is (= "hello" (:stdin (first @asked)))
          "stdin goes to the executor, which closes it after writing it")
      (call "bash" {:command "true"})
      (is (nil? (:stdin (second @asked)))
          "and a call that says nothing about stdin is exactly what it was"))))

(deftest bash-runs-in-the-directory-the-call-named
  (let [pdir (io/file dir "workdir-project")
        sub  (io/file pdir "sub")]
    (.mkdirs sub)
    ;; Two files with the SAME relative name, one in the project and one below it:
    ;; a relative `cat` can only find one of them, so which one came back says
    ;; where the command was sitting -- without parsing `pwd`, whose spelling
    ;; differs between Git Bash and the JVM.
    (spit (io/file pdir "marker.txt") "project-root" :encoding "UTF-8")
    (spit (io/file sub "marker.txt") "the-subdirectory" :encoding "UTF-8")
    (project/bind! "tt-workdir" (.getAbsolutePath pdir))
    (try
      (letfn [(run [args] (tools/run! {:function {:name "bash"
                                                  :arguments (json/write-str args)}}
                                      "tt-workdir"))]
        (testing "`workdir` is resolved the way every other path in this table is"
          (is (= "the-subdirectory" (str/trim (:content (run {:command "cat marker.txt"
                                                              :workdir "sub"}))))))
        (testing "and no `workdir` still means the project directory"
          (is (= "project-root" (str/trim (:content (run {:command "cat marker.txt"})))))))
      (finally (project/bind! "tt-workdir" nil)))))

(deftest a-workdir-that-is-not-a-directory-is-refused-by-name
  ;; Two different facts, two different sentences: a file is there and is not a
  ;; directory, or there is nothing there at all. Guessing between them is how a
  ;; model ends up hunting for a typo it did not make.
  (let [a-file (io/file dir "not-a-dir.txt")
        missing (io/file dir "no-such-place")]
    (spit a-file "x" :encoding "UTF-8")
    (let [{:keys [content error]} (call "bash" {:command "true"
                                                :workdir (.getAbsolutePath a-file)})]
      (is (true? error))
      (is (str/includes? content "`workdir` must be a directory"))
      (is (str/includes? content "is a file")))
    (let [{:keys [content error]} (call "bash" {:command "true"
                                                :workdir (.getAbsolutePath missing)})]
      (is (true? error))
      (is (str/includes? content "nothing is there")))))

;; ------------------------------------------------------------- which shell
;;
;; A `bash` CALL MAY NAME THE SHELL THAT INTERPRETS ITS LINE. The default is untouched --
;; no `shell` means this machine's own, exactly as before -- and a name that cannot be
;; honoured is refused rather than substituted: running the caller's `%VAR%` line under a
;; shell that does not read it would come back looking like a bug in the command.

(defn- a-shell-this-machine-lacks
  "The name of a kind this machine does NOT have, or nil when it has all of them. ASKED
  of the machine rather than written down: which kinds exist is the one thing about this
  that differs between the machines this suite runs on."
  []
  (->> shell/candidates (map :kind) distinct (map name)
       (remove #(some? (shell/resolution (keyword %))))
       first))

(deftest a-call-can-name-the-shell-that-interprets-it
  ;; `%CD%` IS THE PROOF, and it cannot be anything else: bash does not expand it, so a
  ;; Windows path in the answer means cmd really read the line. Wrapping `cmd /c` inside a
  ;; bash call -- the workaround this replaces -- cannot produce that, because bash would
  ;; be the one handing the line over.
  (when (shell/resolution :cmd)
    (let [{:keys [content error]} (call "bash" {:command "echo %CD%" :shell "cmd"})]
      (is (false? error))
      (is (re-find #"[A-Za-z]:[\\/]" content)
          (str "cmd expanded %CD%: " (pr-str content))))))

(deftest leaving-the-shell-out-is-exactly-what-it-was
  (let [{:keys [content error]} (call "bash" {:command "echo hi"})]
    (is (false? error))
    (is (= "hi" (str/trim content))
        "no `shell` is not a new default -- it is no change at all")))

(deftest a-shell-name-that-is-not-one-of-them-is-refused-as-a-word
  (let [{:keys [content error]} (call "bash" {:command "echo hi" :shell "bash5"})]
    (is (true? error))
    (is (str/includes? content "`shell` must name one of")
        "the sentence says what the list is: the reader fixes this one by reading it")
    (is (str/includes? content "bash5") "and it quotes what was actually asked for")))

(deftest a-shell-this-machine-lacks-is-refused-with-what-it-has
  (if-let [absent (a-shell-this-machine-lacks)]
    (let [{:keys [content error]} (call "bash" {:command "echo hi" :shell absent})]
      (is (true? error))
      (is (str/includes? content (str "no `" absent "` shell on this machine")))
      (is (str/includes? content "what it has is")
          "re-reading the list fixes nothing here, so it says what the machine DOES have"))
    (is true "this machine has every kind the harness knows -- nothing to refuse")))

(deftest a-job-takes-the-same-shell
  ;; THE SAME PROOF AS THE FOREGROUND CASE: `%CD%` is not something bash expands, so a
  ;; Windows path in the RECORD means cmd really read the line -- and the record is the
  ;; only place a job's output ever appears.
  ;;
  ;; AND THE ANSWER SAYS NOTHING ABOUT IT. Which shell ran a job is the caller's own
  ;; choice, not part of the job's identity, and `job_output` never needs to know -- so a
  ;; call that named a shell answers in exactly the shape a call that did not, and both
  ;; are held to it here rather than only the new one.
  (let [two-facts #"job \S+ started; read it with `job_output \{\"job\": \"\S+\"\}`\."
        default-answer (call "job" {:command "echo hi"})]
    (is (re-matches two-facts (str/trim (:content default-answer)))
        "the default job answer is still two facts, one line")
    (when (shell/resolution :cmd)
      (let [answer (:content (call "job" {:command "echo %CD%"
                                          :shell "cmd"}))
            job-id (second (re-find #"job (j\d+) started" answer))]
        (is (re-matches two-facts (str/trim answer))
            "and naming a shell does not add a third: two facts, one line")
        (is (some? job-id) (str "the answer carries the id `job_output` takes: " answer))
        (is (re-find #"[A-Za-z]:[\\/]"
                     (:content (call "job_output" {:job job-id :wait true :timeout 20000})))
            "the record holds what cmd printed, which bash could not have expanded")
        (jobs/shutdown!)))))

(deftest a-job-naming-a-missing-shell-registers-nothing
  ;; THE REFUSAL HAPPENS BEFORE THE ID IS TAKEN, so a call that named a kind this machine
  ;; does not have leaves no id, no record file and no process behind -- the same promise
  ;; `start!` makes about a command that cannot be spawned at all.
  (if-let [absent (a-shell-this-machine-lacks)]
    (let [{:keys [content error]} (call "job" {:command "echo hi"
                                               :shell absent})]
      (is (true? error))
      (is (str/includes? content (str "no `" absent "` shell on this machine")))
      (is (not (str/starts-with? content "job ")) "and nothing was registered for it"))
    (is true "this machine has every kind the harness knows -- nothing to refuse")))

;; ------------------------------------------------------- what an answer may carry
;;
;; THE CEILING HAS A FLOOR: below it nothing changes at all -- `echo hi` is still
;; `hi`, with no path and no file -- which is why that is asserted first. Above it the
;; answer is a tail, a count of the bytes left out and where the rest of them are.

(deftest a-command-that-fits-is-answered-as-it-always-was-and-writes-nothing
  (let [records (io/file (home/root) "jobs")
        files   (fn [] (count (filter #(.isFile %) (file-seq records))))]
    (let [before (files)
          {:keys [content error]} (call "bash" {:command "echo hi"})]
      (is (false? error))
      (is (= "hi" (str/trim content)) "byte for byte what it always was")
      (is (not (str/includes? content "[truncated")))
      (is (= before (files)) "echoing `hi` is not worth a file"))))

(deftest a-huge-answer-comes-back-as-a-tail-and-a-way-to-read-the-rest
  (let [{:keys [content error]} (call "bash" {:command "seq 1 200000"})]
    (is (false? error))
    (let [path (second (re-find #"the whole output is (\S+)\]" content))]
      (is (some? path) (str "the answer names the record: " (subs content 0 160)))
      (is (.exists (io/file path)) "and the file is there to be read")
      (let [whole (slurp path :encoding "UTF-8")
            ;; what the command itself printed: the record, without the line this repo
            ;; appends to say how it ended.
            own   (subs whole 0 (str/last-index-of whole "[exit 0]"))
            kept  (first (str/split content #"\n\[truncated:"))
            omitted (Long/parseLong (second (re-find #"omitted (\d+) bytes" content)))]
        (testing "the record holds ALL of it -- one line per number, nothing dropped"
          (is (= 200000 (count (str/split-lines own))))
          (is (= "1" (first (str/split-lines own))))
          (is (= "200000" (last (str/split-lines own)))))
        (testing "the answer is the TAIL: the end of it is here, the beginning is not"
          (is (str/includes? content "\n200000"))
          (is (not (str/includes? content "\n1\n"))))
        (testing "and the bytes it left out are the bytes that are missing, exactly"
          (is (= (alength (.getBytes ^String own "UTF-8"))
                 (+ omitted (alength (.getBytes ^String kept "UTF-8")))))))
      (testing "a reader following the answer needs no human and no new verb"
        ;; The record is in the configuration home, which the fence lists as free --
        ;; so all three readers reach it, and the path in the answer is a live one.
        (let [cmd (str "grep -c '^199999$' " (support/shell-path path))]
          (is (= "1" (str/trim (:content (call "bash" {:command cmd}))))))
        (is (some #(str/includes? % "199999")
                  (read-lines (:content (call "read" {:path path :offset 199990}))))
            "the `read` tool reads the same file")
        (is (str/includes? (:content (call "grep" {:pattern "^199999$" :path path}))
                           "199999")
            "and so does `grep`")))))

(deftest a-loud-stdout-does-not-eat-a-line-of-stderr
  ;; The two streams are counted on their own: one line of stderr is not a casualty of
  ;; a hundred thousand lines of stdout.
  (let [answer (:content (call "bash" {:command "seq 1 200000; echo BOOM >&2"}))]
    (is (str/includes? answer "BOOM"))
    (is (= 1 (count (re-seq #"\[truncated" answer))) "only stdout was over budget")
    (is (str/includes? answer "of stdout"))))

(deftest a-quiet-stdout-does-not-save-a-loud-stderr
  ;; The mirror of the case above, and not symmetry for its own sake: a budget that
  ;; counted the two streams together would let a loud stdout decide how much of a
  ;; loud stderr survives -- and a failed build's whole account is on stderr.
  (let [answer (:content (call "bash" {:command "seq 1 200000 >&2; exit 7"}))]
    (is (= 1 (count (re-seq #"\[truncated" answer))) "only stderr was over budget")
    (is (str/includes? answer "of stderr"))
    (is (str/includes? answer "200000") "what it kept is the end of it")
    (is (str/ends-with? answer "[exit 7]") "and the ending line comes last, as it always did")))

(deftest a-command-stopped-at-the-limit-leaves-what-it-had-said
  ;; The record's last line and the answer's last line are the SAME line, so the two
  ;; can never disagree about how the command ended.
  ;;
  ;; ITS LIMIT HAS TO CLEAR THE LOGIN PROFILE. `bash -lc` pays for /etc/profile and the
  ;; person's own profile BEFORE it runs the command at all: 2.2s on the machine this was
  ;; measured on (2026-09-23; `bash -lc true` 1.7s against `bash -c true` 0.22s -- the same
  ;; measurement harness.infra.shell-test's two limit cases carry). At 1500ms the command
  ;; never started, so there was no output, no "truncated" line and no file to read, which
  ;; reads as a lost record rather than as a slow machine.
  (let [limit 8000
        {:keys [content]} (call "bash" {:command "seq 1 5000; sleep 300" :timeout limit})
        path (second (re-find #"the whole output is (\S+)\]" content))
        ending (str "[timed out after " limit "ms — the command was stopped]")]
    (is (str/includes? content ending))
    (let [whole (slurp path :encoding "UTF-8")]
      (is (= ending (last (str/split-lines whole))))
      (is (= 5000 (count (remove #(str/starts-with? % "[") (str/split-lines whole))))
          "everything it printed before the limit is in the record"))))

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
      (let [answer (:content (background "tt-job" "cat marker.txt"))
            job-id (second (re-find #"job (j\d+) started" answer))
            path   (record-file "tt-job" job-id)]
        (is (some? job-id) (str "the tool answered with a job id: " answer))
        (is (str/includes? answer (str "job_output {\"job\": \"" job-id "\"}"))
            (str "and with the verb that reads it: " answer))
        (is (some? path) (str "and the file is where the harness keeps it: " answer))
        (testing "the record holds what the command printed, from where it ran"
          ;; The command's exit line is the record's end marker, so waiting for it is
          ;; waiting for the job -- and `cat` of a file only the project directory has
          ;; is the evidence that the command ran there.
          (is (support/holds-within?
               #(str/includes? (slurp path :encoding "UTF-8") "job-here") 10000))
          ;; ...and the record's LAST line says how the command ended, which is how a
          ;; reader knows it is done rather than quiet.
          (is (support/holds-within?
               #(= "[exit 0]" (last (str/split-lines (slurp path :encoding "UTF-8"))))
                  10000)))
        (testing "and both reader tools reach it -- the fence does not park them"
          ;; The evidence for the sentence this feature rests on: the record is in
          ;; the configuration home, which cap.project/fence lists as free, so
          ;; reading it needs no human.
          (let [read-back (:content (tools/run! {:function {:name "read"
                                                            :arguments (json/write-str {:path path})}}
                                                "tt-job"))
                searched  (:content (tools/run! {:function {:name "grep"
                                                            :arguments (json/write-str
                                                                        {:pattern "job-here"
                                                                         :path path})}}
                                                "tt-job"))]
            (is (str/includes? read-back "job-here"))
            (is (str/includes? searched "job-here")))))
      (finally
        (jobs/shutdown!)
        (project/bind! "tt-job" nil)))))

(deftest a-background-call-comes-back-before-the-command-does
  ;; The whole point: the call is not the command's lifetime.
  (let [started (System/currentTimeMillis)
        answer  (:content (background "sleep 30"))
        elapsed (- (System/currentTimeMillis) started)]
    (is (re-find #"job j\d+ started" answer))
    (is (< elapsed 5000) (str "it returned while the command was still running (" elapsed "ms)"))
    (jobs/shutdown!)))

(deftest starting-a-job-is-a-verb-of-its-own
  ;; THE SPLIT, as behaviour rather than as a parameter list: `bash` waits, `job` does
  ;; not, and the command itself -- where it runs, what it writes its record into, what
  ;; tool answers for it -- is the same either way. `.scratch/bash-background` merged
  ;; these two into one tool; this is the reversal, because 'does this call wait' as a
  ;; boolean is the judgement that got made wrong in the first place.
  (testing "`job` answers at once, and nothing limits the command"
    (let [t0     (System/currentTimeMillis)
          answer (:content (call "job" {:command "sleep 2; echo late"}))
          elapsed (- (System/currentTimeMillis) t0)
          job-id (second (re-find #"job (j\d+) started" answer))
          path   (record-file nil job-id)]
      (is (some? path) (str "the job left a record on disk: " answer))
      (is (< elapsed 1000) (str "the call came back at once: " elapsed "ms"))
      (Thread/sleep 1200)
      (is (not (str/includes? (slurp path :encoding "UTF-8") "[exit"))
          "two seconds in, the command has no ending line -- it is still running")
      (jobs/shutdown!)))
  (testing "the fields the OTHER verb needs are not read here at all"
    ;; THE FIELDS ARE DELETED, NOT ARGUED WITH. Neither schema declares them, and each
    ;; body destructures its own arguments and stops -- so a key carried over from the
    ;; merged shape (or from a `bash` habit) is not something this side has an opinion
    ;; about: it is not an argument of this verb, and there is nothing here that reads it.
    (let [plain (:content (call "bash" {:command "echo hi"}))
          {:keys [content error]} (call "bash" {:command "echo hi" :run_in_background true})]
      (is (not error) "a key the schema does not have is not an error")
      (is (= plain content) "and it changes nothing: `bash` waited, as this verb always does"))
    (let [{:keys [content error]} (call "job" {:command "cat" :stdin "never-shown-anywhere"})]
      (is (not error))
      (let [job-id (second (re-find #"job (j\d+) started" content))
            path   (record-file nil job-id)]
        (is (some? path) (str "a job started all the same: " content))
        (Thread/sleep 300)
        (is (not (str/includes? (slurp path :encoding "UTF-8") "never-shown-anywhere"))
            "`stdin` has no reader on this side, so the text is not written anywhere")))
    (let [{:keys [content error]} (call "job" {:command "sleep 2" :timeout 1})]
      (is (not error))
      (let [job-id (second (re-find #"job (j\d+) started" content))
            path   (record-file nil job-id)]
        (Thread/sleep 1500)
        (is (not (str/includes? (slurp path :encoding "UTF-8") "[timed out"))
            "the number limited nothing: a job has no timeout to send one to")))
    (jobs/shutdown!)))

(deftest the-answers-name-what-happened-and-the-verb-that-reads-it
  ;; A RECEIPT MAY CARRY ONE SENTENCE OF POINTER: `write` says 'read it to get the
  ;; anchors', and the two background answers say 'read it with `job_output`' (CONTEXT.md's
  ;; 回执 entry). What none of them carries is where the record sits -- the caller can do
  ;; nothing with that, and `job_output` asks by id (`.scratch/job-receipt-no-path`).
  (let [started (:content (background "sleep 30"))
        job-id  (second (re-find #"job (j\d+) started" started))
        pointer (str "`job_output {\"job\": \"" job-id "\"}`")]
    (is (str/includes? started pointer) "the starting answer says how to read it")
    (is (not (str/includes? started (home/root)))
        "and names no path under the configuration home")
    (is (not (str/includes? (:content (call "job_output" {:job job-id})) "read it with"))
        "the reader's own answer does not point at itself")
    (let [killed (:content (call "job_kill" {:job job-id}))]
      (is (str/includes? killed "stopped") "the stopping answer says how it went")
      (is (str/includes? killed pointer) "and how to read what it said")
      (is (not (str/includes? killed (home/root))) "and, like the starting one, no path"))
    (jobs/shutdown!)))

(deftest an-unknown-job-reaches-the-model-as-an-error
  (let [{:keys [content error]} (call "job_kill" {:job "j-not-a-job"})]
    (is (true? error))
    (is (str/includes? content "unknown job: j-not-a-job"))))

(deftest the-background-tools-take-the-arguments-they-need
  ;; The seam's missing-argument check is per tool, which is one of the reasons
  ;; these are two names rather than one with an `action`. Starting a job is not one
  ;; of them any more: it is `bash`, and what it needs is still a command.
  (is (str/includes? (:content (call "bash" {})) "missing required argument"))
  (is (str/includes? (:content (call "job_kill" {})) "missing required argument"))
  (is (str/includes? (:content (call "job_output" {})) "missing required argument")))

(deftest the-record-a-job-leaves-is-readable-with-bash
  ;; NO ANSWER HANDS OUT THE PATH ANY MORE -- `job_output` is the model's reader -- but the
  ;; record is still a plain file in the configuration home, which `cap.project/fence` lists
  ;; as free, so the tools the harness already has reach it with no human in the loop.
  (let [answer (:content (background "echo one; echo two; sleep 30"))
        job-id (second (re-find #"job (j\d+) started" answer))
        path   (record-file nil job-id)]
    (is (some? path) (str "the job left a record on disk: " answer))
    (support/read-until #(slurp path :encoding "UTF-8") #(re-find #"two" (:answer %)) 10000)
    (let [found  (:content (call "bash" {:command (str "grep two " (support/shell-path path))}))
          tailed (:content (call "bash" {:command (str "tail -1 " (support/shell-path path))}))]
      (is (str/includes? found "two") "`bash` can search the record")
      (is (= "two" (str/trim tailed)) "and read its last line"))
    (jobs/shutdown!)))

(deftest a-job-says-when-the-command-sends-its-own-output-away
  ;; THE COMMAND IS SHELL TEXT, so the path inside it is spelled the way the shell
  ;; reads it (`support/shell-path`): a Windows path goes in with backslashes and
  ;; arrives as `C:Userszhouteng..` -- the shell eats each `\` as an escape -- so the
  ;; redirection would land somewhere else entirely and the note would name that.
  (let [tmp (io/file dir "redirected.txt")
        target (support/shell-path (.getAbsolutePath tmp))
        answer (:content (background (str "echo hi > " target)))]
    (testing "the command still runs -- a note is not a refusal"
      (is (some? (re-find #"job j\d+ started" answer))))
    (testing "and the answer says where the output went, and why the record stays empty"
      (is (str/includes? answer target))
      (is (str/includes? answer "record will stay empty")))
    (testing "the redirection really did happen: the command's output is in that file"
      ;; The predicate has to tolerate the file not being there YET: the shell has just
      ;; been spawned, and the whole point of waiting is that the redirection is the
      ;; command's own doing rather than ours.
      (is (support/holds-within?
           #(and (.exists tmp) (= "hi" (str/trim (slurp tmp :encoding "UTF-8")))) 5000)))
    (testing "while a command that sends nothing away is not given the note"
      (let [plain (:content (background "sleep 30"))]
        (is (not (str/includes? plain "record will stay empty")))))
    (jobs/shutdown!)))

(deftest a-background-job-can-be-stopped-and-is-then-readable
  (let [started (:content (background "sleep 30"))
        job-id  (second (re-find #"job (j\d+) started" started))
        path    (record-file nil job-id)
        started-at (System/currentTimeMillis)
        answer  (:content (call "job_kill" {:job job-id}))
        elapsed (- (System/currentTimeMillis) started-at)]
    (is (some? job-id))
    (testing "the answer says it was stopped, and how to read what it said"
      (is (str/includes? answer "stopped"))
      (is (str/includes? answer (str "`job_output {\"job\": \"" job-id "\"}`"))))
    (testing "and it does NOT wait for the process to die before answering"
      ;; Killing a tree is `destroy`, a bounded wait, then `destroyForcibly`; that
      ;; wait belongs to the killing, not to the tool call that asked for it.
      (is (< elapsed 1500) (str "the call came back in " elapsed "ms")))
    (testing "the record survives the stop, with `[stopped]` as its last line"
      (is (= "[stopped]" (last (str/split-lines (slurp path :encoding "UTF-8"))))))
    (testing "and the job still answers when asked how it went"
      ;; THE ID IS NOT REFUSED THE SECOND TIME: it was handed out by this session,
      ;; and 'it is over' is an answer, not an unknown id.
      (let [{:keys [content error]} (call "job_output" {:job job-id})]
        (is (false? error))
        (is (str/ends-with? content "[stopped]"))))
    (testing "while stopping it again answers the same thing instead of refusing"
      (let [{:keys [content error]} (call "job_kill" {:job job-id})]
        (is (false? error))
        (is (str/includes? content "was already over"))
        (is (str/includes? content "[stopped]")))))
  (testing "a job that ended on its own is reported with its own exit line"
    (let [started (:content (background "exit 3"))
          job-id  (second (re-find #"job (j\d+) started" started))
          path    (record-file nil job-id)]
      ;; Wait for it to end before stopping it: `[stopped]` and `[exit 3]` are two
      (is (some? path) (str "the job call answered: " started))
      ;; Wait for it to end before stopping it: `[stopped]` and `[exit 3]` are two
      (support/read-until #(slurp path :encoding "UTF-8") #(re-find #"\[exit" (:answer %)) 10000)
      (let [answer (:content (call "job_kill" {:job job-id}))]
        (is (str/includes? answer "was already over"))
        (is (str/includes? answer "[exit 3]"))
        (is (= "[exit 3]" (last (str/split-lines (slurp path :encoding "UTF-8")))))))))

;; ------------------------------------------------------- reading a job's output
;;
;; The face is thin: harness.cap.jobs reads the record and answers the facts (status,
;; window, totals) and what is here is an answer a model can read. What only a tool
;; call can show -- and what these cases are for -- is the WAITING, which is the one
;; thing no reader of a file can do.

(deftest the-three-faces-say-what-they-are-for
  ;; THE AXIS, asserted rather than assumed: how long a command takes is not the
  ;; question -- 'am I going to wait for it' is -- and since the split it is a choice
  ;; between two VERBS rather than a boolean inside one. A model that had to read two
  ;; descriptions in which one said 'slow' picked that one and then built its own `join`
  ;; out of `sleep` (`.scratch/bash-record/spec.md` has that session).
  (let [spec (fn [name] (get-in (first (filter #(= name (get-in % [:function :name]))
                                               (tools/specs)))
                                [:function :description]))]
    (testing "`bash` waits, says so, and points at the verb that does not"
      (is (str/includes? (spec "bash") "WAIT for"))
      (is (str/includes? (spec "bash") "use `job`"))
      (is (not (str/includes? (spec "bash") "run_in_background"))
          "the merged parameter is advertised nowhere")
      (is (str/includes? (spec "bash") (str jobs/answer-budget-bytes " bytes"))))
    (testing "`job` starts one, and says what that costs"
      (is (str/includes? (spec "job") "NO timeout"))
      (is (str/includes? (spec "job") "WHEN IT ENDS YOU ARE TOLD"))
      (is (str/includes? (spec "job") "`job_output`") "and names the verb that reads it"))
    (testing "`job_output` is the one that can wait, and the one that says how it went"
      (is (str/includes? (spec "job_output") "`wait: true` blocks"))
      ;; THE RIGHT-HAND SIDE IS A LITERAL, and that is the whole point of this line.
      ;; The description is part of the bytes that go out, so an assertion that
      ;; recomputed the expected text from the very expression under test would pass
      ;; while the description carried ANYTHING -- which is exactly how
      ;; `@<identity-hash>` rode along unnoticed. See
      ;; .scratch/llm-prefix-cache/issues/01-the-identity-hash-in-the-tool-table.md
      (is (str/includes? (spec "job_output") "120000ms")))
    (testing "and every face is in the table under its own name"
      (is (some? (spec "job")) "starting is a name again, not a flag on `bash`"))))

(deftest a-job-output-call-can-wait-for-the-command-to-finish
  (let [started (:content (background "echo one; sleep 1; echo two"))
        job-id  (second (re-find #"job (j\d+) started" started))
        t0      (System/currentTimeMillis)
        {:keys [content error]} (call "job_output" {:job job-id :wait true :timeout 20000})
        elapsed (- (System/currentTimeMillis) t0)]
    (is (some? job-id))
    (is (false? error))
    (testing "the LAST line is how it went, and it is the record's own last line"
      (is (str/ends-with? content "[exit 0]")))
    (testing "above it, what the command said"
      (is (str/includes? content "one\ntwo\n")))
    (testing "and the call really waited: it came back after the command, not before"
      (is (>= elapsed 900) (str "elapsed " elapsed "ms")))
    (jobs/shutdown!)))

(deftest a-wait-that-runs-out-is-an-answer-not-an-error
  (let [started (:content (background "sleep 30"))
        job-id  (second (re-find #"job (j\d+) started" started))
        t0      (System/currentTimeMillis)
        {:keys [content error]} (call "job_output" {:job job-id :wait true :timeout 300})
        elapsed (- (System/currentTimeMillis) t0)]
    (is (false? error) "a job that outlives the wait is not a failure")
    (is (str/ends-with? content "[running]"))
    (is (< elapsed 10000) (str "it answered at the timeout: " elapsed "ms"))
    (jobs/shutdown!)))

(deftest a-job-output-call-reads-a-window-of-the-record
  (let [started (:content (background "echo one; echo two; echo three; sleep 30"))
        job-id  (second (re-find #"job (j\d+) started" started))
        path    (record-file nil job-id)]
    (support/read-until #(slurp path :encoding "UTF-8") #(re-find #"three" (:answer %)) 10000)
    (testing "an offset in the record, exactly as `grep -n` would number the same lines"
      (let [answer (:content (call "job_output" {:job job-id :offset 2 :limit 1}))]
        (is (= ["two"
                (str "[3 lines in all; this answer shows lines 2-2; the whole record is " path "]")
                "[running]"]
               (str/split-lines answer)))
        (is (str/includes? answer path)
            "and the part it did not carry is not lost: the answer names the file")))
    (testing "and no offset means the tail -- what it has just said"
      (let [answer (:content (call "job_output" {:job job-id}))]
        (is (str/ends-with? answer "[running]"))
        (is (str/includes? answer "three"))
        (is (not (str/includes? answer path))
            "the window IS the record here, so there is nothing beyond it to point at")))
    (testing "a job that has said nothing answers with its state, and says so above it"
      ;; THE EMPTY BODY IS STILL A BODY: the answer is the sentence `bash` uses, then the ending
      ;; -- which is also the case a reader is most likely to skim past.
      (let [quiet (:content (background "sleep 30"))
            id    (second (re-find #"job (j\d+) started" quiet))]
        (is (some? id))
        (is (= "(no output)\n[running]" (:content (call "job_output" {:job id}))))))
    (testing "an unknown job is refused, naming what this session does have"
      (let [{:keys [content error]} (call "job_output" {:job "j-not-a-job"})]
        (is (true? error))
        (is (str/includes? content "unknown job: j-not-a-job"))))
    (jobs/shutdown!)))

(deftest a-window-that-is-not-the-whole-record-names-the-file
  ;; THE PATH COMES BACK WITH THE WINDOW, and only then. `bash` says where the rest of an
  ;; over-budget output is (`jobs/truncation-line`); this is the same fact for a record -- a
  ;; window that leaves something out is exactly when `read` / `grep` / `bash` can be put to
  ;; use on the file. A window that IS the record names nothing
  ;; (`.scratch/job-receipt-no-path`).
  (let [answer (:content (background "seq 1 5000"))
        job-id (second (re-find #"job (j\d+) started" answer))
        path   (record-file nil job-id)]
    (support/read-until #(slurp path :encoding "UTF-8") #(re-find #"\[exit" (:answer %)) 20000)
    (let [{:keys [content error]} (call "job_output" {:job job-id})]
      (is (false? error))
      (is (str/includes? content "[5000 lines in all;")
          "the answer says the window is a part of it")
      (is (str/includes? content (str "; the whole record is " path "]"))
          "and names the file the rest is in"))
    (jobs/shutdown!)))

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

;; ------------------------------------------------------- the bytes of the tool table
;;
;; EVERY CASE BELOW IS ABOUT THE SAME FACT: this table is serialized into the request's
;; HEAD -- ahead of the system prompt and of every message -- so the vendor's prefix
;; cache keys on those bytes, and ONE changed byte there throws away the whole prefix,
;; the conversation included. That is why the order is a definition rather than a sort,
;; and why a capability's schema is not trusted to write its own key order.
;; See .scratch/llm-prefix-cache/spec.md.

(defn- objects-out-of-order
  "Every object inside VALUE whose keys are not in sorted order, each with the PATH that
  reaches it -- so a failure names the schema rather than only saying 'not sorted'."
  [value path]
  (cond
    (map? value) (concat (when-not (= (vec (keys value)) (sort (keys value)))
                           [(assoc path :keys (vec (keys value)))])
                         (mapcat (fn [[k v]] (objects-out-of-order v (conj path k))) value))
    (sequential? value) (mapcat (fn [i v] (objects-out-of-order v (conj path i)))
                                (range) value)
    :else nil))

(defn- specs-names [table] (mapv (comp :name :function) table))

(deftest the-tool-table-is-a-pure-function-of-the-tools
  (let [once  (json/write-str (tools/specs) :escape-unicode false)
        twice (json/write-str (tools/specs) :escape-unicode false)]
    (testing "assembling twice in one process gives the same bytes"
      ;; THE NECESSARY HALF ONLY, and it is worth saying why: an identity hash is the
      ;; SAME inside one process, so this assertion alone would have passed on the bug in
      ;; .scratch/llm-prefix-cache/issues/01. The sentinel below is what catches that
      ;; class. The cross-process half is EVIDENCE over recorded sessions, read by
      ;; scripts/llm-prefix-report.mjs (issues/04) -- deliberately not a synthetic child
      ;; process, which either would not inherit the test runner's isolated home, or
      ;; would have to install a table of its own and would then be testing the
      ;; installer.
      (is (= once twice)))
    (testing "and no face carries a stringified Clojure object"
      ;; THE SHAPE IS NOT GUESSWORK: an object's `toString` is either a munged class name
      ;; with a `$`, an `@` and an identity hash, or `#<...>`. An un-called fn, an
      ;; underef'd delay and a stray var all print that way, so this catches the SHAPE
      ;; rather than one instance of it. If a description ever legitimately carries a `$`
      ;; or an `@`, narrow this to the offending part -- do not delete it.
      (is (not (re-find #"harness\.[A-Za-z0-9_.\-]+\$" once)))
      (is (not (re-find #"@[0-9a-f]{6,}" once)))
      (is (not (str/includes? once "#<"))))))

(deftest every-object-the-table-sends-has-its-keys-in-sorted-order
  (let [table (tools/specs)]
    (testing "at every depth of every schema"
      (is (= [] (objects-out-of-order table []))
          "a plain map's key order is an accident of its size: an array map keeps
           insertion order up to eight keys and then silently flips to hash order --
           deterministic, but not the same promise, because it re-shuffles when the key
           SET changes. Sorted keys make the order a pure function of the key set."))
    (testing "and the envelope's own order, both levels, named here so a reader sees it"
      (let [one (first table)]
        (is (= [:function :type] (vec (keys one))))
        (is (= [:description :name :parameters] (vec (keys (:function one)))))))
    (testing "while an array keeps the order it was written in"
      ;; `required`, `enum` and `oneOf` are sequences, and their order is part of what
      ;; they say -- so the door sorts objects and leaves arrays alone, at every depth.
      (let [todos (first (filter #(= "todo_write" (get-in % [:function :name])) table))
            items (get-in todos [:function :parameters :properties "todos" :items])]
        (is (= ["content" "status"] (:required items)))
        (is (= ["pending" "in_progress" "completed"]
               (get-in items [:properties "status" :enum])))))))

(deftest a-sessions-own-roster-sorts-after-the-built-ins
  ;; THE ORDER IS PART OF THE BYTES, so the half whose membership can change while the
  ;; process runs must not be able to re-position the half that cannot. A global
  ;; `sort-by` said the opposite: `mcp__*` sorted in between `job_output` and `read`, so
  ;; one server's roster moving moved BUILT-INS.
  (let [t "jt-specs-roster"
        before (tools/specs t)
        n (count before)]
    (tools/session-register! t "mcp__fake__aaa"
                             {:source :mcp
                              :description "d"
                              ;; A SCHEMA SHAPED THE WAY ONE ARRIVES FROM A SERVER, because
                              ;; that path is the one the door cannot see coming: the object
                              ;; keys get sorted, and the two ARRAYS keep the order the server
                              ;; gave -- `required` is what the execution seam checks a call
                              ;; against, and an `enum` is a list somebody wrote down.
                              :parameters {"type" "object"
                                           "properties" {"mode" {"type" "string"
                                                                 "enum" ["slow" "fast"
                                                                         "auto" "off"]}}
                                           "required" ["mode" "target" "reason" "retries"]}})
    (tools/session-register! t "mcp__fake__zzz"
                             {:source :mcp :description "d" :parameters {}})
    (let [after (tools/specs t)]
      (testing "the built-in run is the same BYTES, in the same order, at the front"
        (is (= (json/write-str before :escape-unicode false)
               (json/write-str (subvec after 0 n) :escape-unicode false))))
      (testing "and the roster sits after it, in name order"
        (is (= 2 (- (count after) n)))
        (is (= ["mcp__fake__aaa" "mcp__fake__zzz"] (drop n (specs-names after)))))
      (testing "a server's own schema keeps its arrays in the server's order"
        (let [params (get-in (first (filter #(= "mcp__fake__aaa" (get-in % [:function :name]))
                                            after))
                             [:function :parameters])]
          (is (= ["mode" "target" "reason" "retries"] (get params "required")))
          (is (= ["slow" "fast" "auto" "off"]
                 (get-in params ["properties" "mode" "enum"])))
          (is (= ["properties" "required" "type"] (vec (keys params)))
              "while its OBJECT keys are sorted, so the bytes do not depend on how it wrote them"))))))

(deftest a-set-reaching-a-schema-is-refused-by-name
  ;; A set's iteration order is not a contract, and the symptom of shipping one is the
  ;; cold prefix nobody can explain -- so the door refuses rather than quietly choosing
  ;; an order for it, which would make the lie look fixed.
  (let [t "jt-specs-set"]
    (tools/session-register! t "bad-tool"
                             {:source :builtin
                              :description "d"
                              :parameters {"thing" {:type "string"
                                                    :enum #{"before" "after"}}}})
    (let [thrown (try (tools/specs t) nil (catch Exception e e))]
      (is (some? thrown) "a set in a schema must not be given an order and shipped")
      (is (str/includes? (ex-message thrown) "a set may not reach the wire")))))

(deftest job-list-answers-what-is-there-and-not-one-id
  ;; THE ONE HAND IN THIS FAMILY THAT ADDRESSES NO ID, which is its whole reason to exist: the
  ;; model that needs it has just been born into a session (a restart, a compaction) and holds
  ;; none of the ids `job_output` / `job_kill` would want. So this is the answer a reader gets
  ;; when it does not know what to ask about -- and it must say enough to ask next time.
  (let [started (:content (background "echo one; exit 0"))
        job-id  (second (re-find #"job (j\d+) started" started))]
    (is (some? job-id) (str "a job really started: " started))
    (let [{:keys [content error]} (call "job_list" {})]
      (is (false? error))
      (testing "the row names the job, how it went, what it was, and where its record is"
        (is (str/includes? content job-id) "the id, so the next call can address it")
        (is (str/includes? content "this run") "and which run it belongs to")
        (is (str/includes? content (str (home/root))) "the record's path, which is a reader's next move")
        (is (str/includes? content "echo one; exit 0") "and the command, for a job this process holds"))
      (testing "a job's status is its own record's last line, said the way `job_output` says it"
        (is (re-find #"\[(exit [^\]]*|stopped|running)\]" content)))
      (testing "and the answer counts what it drew"
        (is (re-find #"\d+ records?: \d+ still running, \d+ ended\." content))))
    (testing "a command that is a script reads as ONE line"
      ;; ITS OWN SESSION, because this namespace's home is shared by every case in it and a
      ;; session's listing is every record it has: a fixed thread-id keeps this assertion about
      ;; the command's shape rather than about what the case before it started.
      (let [t "jt-list-script"
            started (:content (background t "echo one &&\n  echo two"))
            job-id  (second (re-find #"job (j\d+) started" started))
            drawn   (:content (tools/run! {:function {:name "job_list"
                                                     :arguments (json/write-str {})}}
                                           t))]
        (is (some? job-id))
        (is (str/includes? drawn "echo one && echo two") "the newline folded to a space")
        (is (not (str/includes? drawn "echo one &&\n"))
            "and no row in the answer is a wrapped command")))
    (testing "the cap says how many it left out, and where the rest are"
      ;; THE NUMBER IN THE NOTE IS ASSERTED, not just the phrase: 'some rows were left out' is
      ;; true of any cap, and the count is the half a reader acts on.
      (let [all  (:content (call "job_list" {}))
            rows (count (re-seq #"(?m)^[jc]\d+ · " all))]
        (is (pos? rows) "the listing drew rows to leave out")
        (with-redefs [jobs/max-listed-records 1]
          (let [drawn (:content (call "job_list" {}))]
            (is (str/includes? drawn (str "(" (- rows 1) " more not listed")))
            (is (str/includes? drawn (str (home/root)))
                "under the configuration home, where the rest are")))))
    (testing "a session with nothing at all gets the sentence `unknown-job` refuses with"
      (let [answer (:content (tools/run! {:function {:name "job_list"
                                                   :arguments (json/write-str {})}}
                                         "jt-list-nothing"))]
        (is (= jobs/no-jobs-line (str/trim answer)))))))
