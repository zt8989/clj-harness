(ns harness.infra.shell-test
  "harness.infra.shell's resolution: which shell this process spawns, and how a
  command is handed to it.

  THE CHAIN IS ASSERTED WITH THE EXISTENCE QUESTION HANDED IN. `select` takes that
  question as an argument and `locator` takes the PATH it searches, so the Git Bash,
  pwsh and cmd steps are exercised on a machine that has none of them -- which is the
  only way they can be exercised at all, since a suite runs on one machine. What is
  NOT faked is the spawn: that a command really goes through the shell this process
  resolved to is asserted by running one, with the answer computed from the machine
  rather than written down."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [harness.infra.shell :as shell]
            [harness.test-support :as support]))

(defn- path-with
  "A PATH-STRING holding one directory in which NAMES exist, as empty files -- which
  is all 'is this program on PATH' asks. Both spellings of each name are planted, so
  the same assertion says the same thing on Windows, where the program is `pwsh.exe`
  and not the name a person types."
  [& names]
  (let [d (support/temp-dir "shell-path")]
    (doseq [n names, f [(str n) (str n ".exe")]]
      (spit (io/file d f) ""))
    (str d)))

(defn- finds-only
  "A locator answering for exactly the candidates whose :command is one of COMMANDS
  -- the fabricated 'what this machine has' the chain walks. Nothing here touches the
  real filesystem, which is the point: what is under test is the ORDER."
  [& commands]
  (let [wanted (set commands)]
    (fn [{:keys [command]}] (when (wanted command) (str "/found/" command)))))

(deftest the-chain-takes-the-first-step-that-is-there
  (testing "a Git Bash install is pinned by absolute path"
    (let [r (shell/select shell/candidates
                          (finds-only "C:\\Program Files\\Git\\bin\\bash.exe"))]
      (is (= :git-bash (:kind r)))
      (is (str/ends-with? (:command r) "Git\\bin\\bash.exe"))))
  (testing "then a bash on PATH, with the path it was found at"
    (let [r (shell/select shell/candidates (finds-only "bash"))]
      (is (= :bash (:kind r)))
      (is (= "/found/bash" (:command r)))))
  (testing "then pwsh"
    (is (= :pwsh (:kind (shell/select shell/candidates (finds-only "pwsh"))))))
  (testing "then PowerShell"
    (is (= :powershell (:kind (shell/select shell/candidates (finds-only "powershell"))))))
  (testing "then cmd"
    (is (= :cmd (:kind (shell/select shell/candidates (finds-only "cmd"))))))
  (testing "and NOTHING is an answer rather than a name that cannot run"
    ;; The whole reason the chain exists: the old fallback was the string `bash`,
    ;; which on a Windows box without Git Bash resolves to the WSL launcher.
    (is (nil? (shell/select shell/candidates (constantly nil))))))

(deftest the-resolved-map-says-what-the-shell-is-and-how-it-starts
  (testing "bash does POSIX, and is handed the command with -lc"
    (is (true? (shell/posix? :bash)))
    (is (true? (shell/posix? :git-bash)))
    (is (= ["-lc"] (shell/argv-prefix :bash)))
    (is (= ["-lc"] (shell/argv-prefix :git-bash))))
  (testing "PowerShell needs its own flags, and does not read POSIX quoting"
    (is (false? (shell/posix? :pwsh)))
    (is (= ["-NoProfile" "-Command"] (shell/argv-prefix :pwsh)))
    (is (= ["-NoProfile" "-Command"] (shell/argv-prefix :powershell))))
  (testing "cmd neither"
    (is (false? (shell/posix? :cmd)))
    (is (= ["/c"] (shell/argv-prefix :cmd)))))

(deftest the-resolution-of-this-machine-really-runs-a-command
  ;; Not faked, and not written down: this machine's own shell is asked, and a command
  ;; is run through it. A wrong prefix -- `-lc` handed to pwsh, `/c` to bash -- would
  ;; fail exactly here, which is the one thing a pure test of `argv-prefix` cannot say.
  (let [r (shell/resolution)]
    (is (some? r) "the machine this suite runs on has one of the chain's shells")
    (is (string? (:command r)) "the name to spawn is a string, not a map")
    (is (= (shell/argv-prefix (:kind r)) (:argv-prefix r)) "and how to start it comes from its kind")
    (let [{:keys [exit out err]} (shell/run {:command "echo harness-shell-test"
                                             :timeout-ms 10000})]
      (is (= 0 exit) (str "the command ran (stderr: " (str/trim (str err)) ")"))
      (is (str/includes? (str out) "harness-shell-test")))))

(deftest a-resolution-can-be-asked-for-a-named-kind
  ;; THE MACHINE'S OWN SHELL IS ONE QUESTION; a kind the caller NAMES is another. They
  ;; have to agree where they overlap, or `bash {shell: "cmd"}` would run somewhere
  ;; other than where `(resolution :cmd)` promised -- which is worse than refusing.
  (let [r (shell/resolution)]
    (is (some? r) "this machine resolves a shell at all")
    (is (= r (shell/resolution (:kind r)))
        "the kind the chain landed on is reachable BY NAME and answers the same thing"))
  (testing "every kind this harness knows answers with that kind, or with nothing"
    ;; Nothing is a legitimate answer. A resolution that came back as a DIFFERENT kind
    ;; would mean the name was ignored -- and a fallback is the one outcome worse than a
    ;; refusal, so it is asserted against rather than assumed away.
    (doseq [k [:git-bash :bash :pwsh :powershell :cmd]]
      (let [r (shell/resolution k)]
        (is (or (nil? r) (= k (:kind r)))
            (str (name k) " answered as " (pr-str (:kind r)))))))
  (testing "and a kind nobody has heard of is nothing, not somebody else's shell"
    (is (nil? (shell/resolution :nope)))
    (is (empty? (shell/kind-candidates :nope))
        "no candidates is WHY -- a mis-spelling has nowhere to fall to")))

(deftest a-named-kinds-answer-is-cached-too
  ;; One rule for both caches: a fact about the machine is asked once. The MISS matters
  ;; as much as the hit -- "this machine has no pwsh" is exactly the answer that must not
  ;; be re-derived at every spawn.
  (let [asked (atom 0)
        fake  {:command "/fake/cmd" :kind :cmd :posix? false :argv-prefix ["/c"]}]
    (try
      (shell/reset-resolution!)
      (with-redefs [shell/resolve-kind* (fn [k] (swap! asked inc) (when (= k :cmd) fake))]
        (is (= fake (shell/resolution :cmd)))
        (is (= fake (shell/resolution :cmd)))
        (is (= 1 @asked) "asked once; the second answer came out of the cache")
        (is (nil? (shell/resolution :pwsh)))
        (is (nil? (shell/resolution :pwsh)))
        (is (= 2 @asked) "and a MISS is cached too: two kinds asked, two questions")
        (shell/reset-resolution!)
        (is (= fake (shell/resolution :cmd)))
        (is (= 3 @asked) "reset forgets the named answers too, not just the machine's"))
      (finally (shell/reset-resolution!)))))

(deftest a-kind-this-machine-lacks-is-refused-by-name
  ;; NEVER A QUIET FALLBACK. Running a command under a shell nobody asked for is worse
  ;; than not running it: the caller's `%VAR%` or `$env:VAR` would be read by somebody
  ;; who does not understand it, and the answer would look like a bug in the command.
  (let [e (try (shell/require-shell! :nope) nil (catch Exception e e))]
    (is (some? e) "a kind nobody has heard of is a refusal, not a nil dereference")
    (is (= :no-such-shell (:reason (ex-data e))))
    (is (= :nope (:kind (ex-data e))))
    (is (str/includes? (ex-message e) "nope") "the sentence names what was asked for")
    (is (str/includes? (ex-message e) "what it has is")
        "and says what this machine does have, so a missing install reads differently"))
  (testing "and the spawn sites refuse rather than falling back"
    (doseq [f [#(shell/run {:command "echo hi" :kind :nope :timeout-ms 5000})
               #(shell/start {:command "echo hi" :shape :shell :kind :nope})]]
      (let [e (try (f) nil (catch Exception e e))]
        (is (= :no-such-shell (:reason (ex-data e))))))))

(deftest a-call-can-name-the-shell-it-runs-in
  ;; `%CD%` IS THE PROOF, and it has to be: bash cannot expand it, so a Windows path in
  ;; the answer is not a coincidence -- it means cmd really interpreted the line. That is
  ;; the whole point of naming a shell instead of wrapping `cmd /c` inside bash.
  (when (shell/resolution :cmd)
    (testing "foreground"
      (let [{:keys [exit out]} (shell/run {:command "echo %CD%" :kind :cmd :timeout-ms 20000})]
        (is (= 0 exit))
        (is (re-find #"[A-Za-z]:[\\/]" (str out)) (str "cmd expanded %CD%: " (pr-str out)))))
    (testing "and the long-lived shape takes the same kind"
      (let [h (shell/start {:command "echo %CD%" :shape :shell :kind :cmd})]
        (try
          (let [lines (loop [v ((:next-line h) 20000), lines []]
                        (cond
                          (shell/timeout? v) (recur ((:next-line h) 20000) lines)
                          (shell/eof? v)     lines
                          :else              (recur ((:next-line h) 20000) (conj lines v))))]
            (is (re-find #"[A-Za-z]:[\\/]" (str/join "\n" lines))
                (str "asked for cmd, got: " (pr-str lines))))
          (finally ((:close! h))))))))

(deftest a-login-shells-logout-does-not-clear-the-pipe
  ;; Git for Windows ships /etc/bash.bash_logout, and a `bash -lc` whose OWN `exit`
  ;; ends it -- every COMPOUND command, since the last `exit` makes bash itself the
  ;; thing that ends; a lone command is exec-optimized away and never reads the file
  ;; -- runs /usr/bin/clear there when the shell believes it is one nesting level
  ;; deep, i.e. a console. The wipe (ESC[H ESC[2J ESC[3J) rides the same stdout pipe
  ;; as the command's own output: a jobs record that ends on a screen wipe, a hook
  ;; answer with a clear in it. `with-shlvl!` pins the count above one -- INNER
  ;; SHELL -- and the logout file, written for a person's last window, stands down.
  ;; This is how the bug was found (2026-09-19: on Windows the jobs record of
  ;; `echo one; echo two; echo three; exit 0` carried a wipe as its last line, two
  ;; cases red; the same suite was green on mac, which ships no such file).
  (testing "the pin reaches the child's environment and not this process's"
    (let [before (System/getenv "SHLVL")]
      (shell/with-shlvl! (fn []))
      (is (= before (System/getenv "SHLVL")) "the parent's own environment is untouched")))
  (testing "a compound command that ends the shell itself leaves no wipe in the pipe"
    (let [{:keys [exit out]} (shell/run {:command "echo one; echo two; exit 0"
                                         :timeout-ms 15000})]
      (is (= 0 exit))
      (is (not (re-find #"\x1B\[" (str out))) "no escape sequence rides the command's stdout")))
  (testing "and the long-lived shape is pinned the same way"
    (let [h (shell/start {:command "echo last; exit 0" :shape :shell})]
      (try
        (loop [v ((:next-line h) 5000), lines []]
          (cond
            (shell/timeout? v) (recur ((:next-line h) 5000) lines)
            (shell/eof? v)     (is (not-any? #(re-find #"\x1B\[" %) lines)
                                   "no line the record would hold is a screen wipe")
            :else              (recur ((:next-line h) 5000) (conj lines v))))
        (finally ((:close! h)))))))

(deftest a-command-that-does-not-finish-is-stopped-together-with-what-it-started
  ;; THE CHILD IS NOT THE COMMAND. `<shell> -lc "..."` means the process this
  ;; namespace holds is a shell, and the one a person means by "the command" is its
  ;; child -- `npm test` is the ordinary case (bash starts npx, npx starts node).
  ;; Killing the shell alone leaves that child running with nobody attached to it,
  ;; which is how two test JVMs survived sixteen hours.
  (let [dir (support/temp-dir "shell-tree")
        pid-file (io/file dir "child.pid")
        ;; The child is kept alive and asked for its OWN pid (`support/child-command`):
        ;; `$!` is MSYS's number here, not the one the OS handed the process, and a
        ;; `gone-within?` asked about that number would answer 'gone' about a child
        ;; still running. `wait` keeps the shell around either way, so the shape under
        ;; test really is two processes.
        command (support/child-command pid-file)
        started (System/currentTimeMillis)
        ;; THE BUDGET HAS TO CLEAR THE LOGIN PROFILE. `bash -lc` runs /etc/profile and the
        ;; person's own profile before it runs the command at all, and on the machine this was
        ;; measured on that costs 2.2s (`bash -lc true` 1.7s against `bash -c true` 0.22s, and
        ;; 2.2s through `run`, timed 2026-09-23). A 4s limit therefore kills the shell BEFORE
        ;; `node` has been started, and the case reports 'the shell never named a child' about
        ;; a machine that was merely slow -- not about the tree-kill it means to be testing.
        res (shell/run {:command command :timeout-ms 12000})
        elapsed (- (System/currentTimeMillis) started)
        pid (Long/parseLong (str/trim (slurp pid-file :encoding "UTF-8")))]
    (testing "the call gives up at the limit rather than waiting for the command"
      (is (true? (:timeout res)))
      (is (nil? (:exit res)) "a process that never finished has no exit code")
      (is (< elapsed 20000) (str "it came back at the limit, not when the command did ("
                                 elapsed "ms)")))
    (testing "and the child it started is gone too"
      (is (some? pid) "the shell really did start a child")
      (is (support/gone-within? pid 5000)
          (str "pid " pid " outlived the call that started it")))))

(deftest everything-this-process-started-goes-with-it
  ;; THE IN-FLIGHT CASE, which is the one no capability-owned reaper covers: a
  ;; command still being waited on when the process itself ends. Measured before
  ;; `reap!` existed (2026-09-22): a SIGTERM to a harness holding one background job
  ;; and one foreground command took the job's tree with it and left this one --
  ;; shell and child both -- running, its stdout in a pipe nobody held any more.
  ;;
  ;; IT KILLS EVERY DESCENDANT OF THIS JVM, and that is the function rather than a
  ;; side effect: the case is the only thing running at this moment (tests are
  ;; sequential in one JVM), so what it asserts on is its own, and anything an
  ;; earlier namespace left standing is a leak being cleaned rather than a victim.
  (let [dir (support/temp-dir "shell-reap")
        run-pid (io/file dir "run.pid")
        start-pid (io/file dir "start.pid")
        in-flight (future (shell/run {:command (support/child-command run-pid)
                                      :timeout-ms 60000}))
        handle (shell/start {:command (support/child-command start-pid) :shape :shell})]
    (try
      (let [run-child (support/child-pid run-pid 15000)
            start-child (support/child-pid start-pid 15000)]
        (testing "both kinds really are running, each with a child of its own"
          (is (some? run-child) "the command in flight booted and named itself")
          (is (some? start-child) "the long-lived command did too")
          (is (support/alive? run-child))
          (is (support/alive? start-child)))
        (testing "one walk of what this process started takes both trees"
          (is (pos? (shell/reap!)))
          (is (support/gone-within? run-child 10000)
              (str "the in-flight command's child (pid " run-child
                   ") outlived the process that started it"))
          (is (support/gone-within? start-child 10000)
              (str "the long-lived command's child (pid " start-child
                   ") outlived the process that started it"))))
      (finally
        (try ((:close! handle)) (catch Throwable _ nil))
        (try (deref in-flight 15000 nil) (catch Throwable _ nil))))))

(deftest the-exit-hook-is-installed-once-and-only-once
  (let [installs (atom 0)]
    (try
      (with-redefs [shell/install-hook! (fn [_] (swap! installs inc))]
        (shell/reset-exit-hook!)
        (dotimes [_ 3] (shell/ensure-exit-hook!))
        (is (= 1 @installs) "three calls, one hook -- two would reap twice"))
      (finally
        ;; AND LEAVE THIS PROCESS WITH THE HOOK IT SHOULD HAVE, installed for real:
        ;; the compare-and-set above was satisfied by the fake.
        (shell/reset-exit-hook!)
        (shell/ensure-exit-hook!)))))

(deftest what-a-command-printed-before-the-limit-comes-back
  ;; The output is not discarded: a command that hangs after saying why it cannot
  ;; finish is exactly the case worth reading.
  ;;
  ;; THIS IS ALSO THE CASE THAT CATCHES A `-lc` -> `-c` "OPTIMIZATION", and it costs
  ;; 10s of wall clock when it does -- both pipe drains run out their 5s default: with
  ;; the profile skipped the timed-out child is never reaped, nothing closes the pipes,
  ;; and the output is thrown away. Found exactly that way on 2026-09-20, when `-c`
  ;; looked like a free 700ms off every spawn.
  (let [{:keys [out timeout]} (shell/run {:command "echo said-before-hanging; sleep 30"
                                          ;; THE LIMIT HAS TO CLEAR THE LOGIN PROFILE (see the
                                          ;; case above for the 2.2s measurement): at 2s the
                                          ;; echo has not run yet, and an empty `out` reads as
                                          ;; 'the output was thrown away' rather than 'the
                                          ;; command never started'.
                                          :timeout-ms 8000})]
    (is (true? timeout))
    (is (str/includes? (str out) "said-before-hanging"))))

(deftest the-two-long-lived-shapes-differ-exactly-on-windows
  ;; The one branch no machine in this repo will ever take on its own, asserted
  ;; anyway: a SERVER is a program to launch (and on Windows that means `cmd /c`, so
  ;; its backslash paths survive), while a background SHELL COMMAND is a shell
  ;; command -- bash's promise, Git Bash on Windows. Passing the two facts in is what
  ;; makes both branches reachable from this machine.
  (let [git-bash {:command "C:\\Program Files\\Git\\bin\\bash.exe" :kind :git-bash
                  :posix? true :argv-prefix ["-lc"]}]
    (testing "on Windows a program goes to cmd, and its command is left alone"
      (let [argv (shell/spawn-argv :program "node server.js --port 1" true nil)]
        (is (str/ends-with? (first argv) "cmd.exe"))
        (is (= ["/c" "node server.js --port 1"] (rest argv)))))
    (testing "while a shell command goes to the shell this process resolved"
      (is (= ["C:\\Program Files\\Git\\bin\\bash.exe" "-lc" "npm test"]
             (shell/spawn-argv :shell "npm test" true git-bash))))
    (testing "and off Windows both are the resolved shell, which is why the "
      (let [r (shell/resolution)]
        (is (some? r) "this machine resolves a shell")
        (let [expected (into [(:command r)] (:argv-prefix r))]
          (is (= (conj expected "npm test") (shell/spawn-argv :shell "npm test" false r)))
          (is (= (conj expected "node server.js") (shell/spawn-argv :program "node server.js" false r))))))))

(deftest a-bash-that-is-windows-wsl-launcher-does-not-count-as-a-bash
  ;; The trap. C:\\WINDOWS\\System32\\bash.exe is another filesystem, and from a JVM it
  ;; answers nothing at all; a chain that took it would report a shell that runs
  ;; nothing. The plant is a `bash.exe` under a directory called System32 and a real
  ;; pwsh next door: the lookup refuses the first and the chain walks to the second.
  ;;
  ;; THE CHAIN WALKED HERE IS THE ONE WITHOUT THE PINNED GIT BASH ROWS, and that is
  ;; the difference between a case about PATH and a case about this machine: those two
  ;; rows name absolute install paths and are answered by an existence question, not by
  ;; PATH -- so on a machine where Git really is installed (the ordinary one for this
  ;; repo) they win, pwsh is never reached, and the case fails for a reason that has
  ;; nothing to do with the WSL trap. What is under test here is the refusal and the
  ;; walking, so the chain given to `select` holds only the rows PATH can answer for.
  ;; The pinned rows are asserted in `the-chain-takes-the-first-step-that-is-there`,
  ;; where the locator is fabricated and no machine has a say.
  (let [path-chain [{:kind :bash :command "bash"}
                    {:kind :pwsh :command "pwsh"}
                    {:kind :cmd  :command "cmd"}]
        sys32 (io/file (support/temp-dir "wsl-root") "System32")
        bin   (path-with "pwsh")]
    (.mkdirs sys32)
    (spit (io/file sys32 "bash.exe") "")
    (let [path (str sys32 (System/getProperty "path.separator") bin)]
      (testing "the launcher is not a bash"
        (is (nil? ((shell/locator path) {:kind :bash :command "bash"}))))
      (testing "so the chain does not stop there"
        (is (= :pwsh (:kind (shell/select path-chain (shell/locator path))))))
      (testing "and with NOTHING but the launcher the answer is nothing at all"
        ;; Rather than the string "bash", which is the name of a program that
        ;; answers nothing on this machine.
        (is (nil? (shell/select path-chain (shell/locator (str sys32)))))
        (testing "while a real bash elsewhere on PATH is taken"
          (let [bin (path-with "bash")]
            (is (= :bash (:kind (shell/select path-chain (shell/locator bin)))))))))))

(deftest the-resolution-is-asked-once-per-process
  ;; A fact about the machine, asked once -- so a test can drive the chain, and so a
  ;; spawn site does not pay for the lookup again.
  (let [asked (atom 0)]
    (try
      (with-redefs [shell/resolve* (fn []
                                     (swap! asked inc)
                                     {:command "/fake/bash" :kind :bash
                                      :posix? true :argv-prefix ["-lc"]})]
        (shell/reset-resolution!)
        (is (= "/fake/bash" (:command (shell/resolution))))
        (is (= "/fake/bash" (:command (shell/resolution))))
        (is (= 1 @asked) "asked once, then remembered"))
      (finally (shell/reset-resolution!)))
    (is (some? (shell/resolution)) "and the real answer is back afterwards")))

(deftest a-caller-that-builds-a-command-line-refuses-by-name-without-a-posix-shell
  ;; The refusal is what keeps rg and git from running under a quoting convention
  ;; nobody wrote: their command lines are POSIX single-quoted words, which mean
  ;; nothing to PowerShell and less to cmd.
  (try
    (testing "a shell that is not POSIX"
      (with-redefs [shell/resolve* (constantly {:command "/x/pwsh" :kind :pwsh
                                                :posix? false
                                                :argv-prefix ["-NoProfile" "-Command"]})]
        (shell/reset-resolution!)
        (let [e (try (shell/require-posix! "`git`, which") nil (catch Exception e e))]
          (is (some? e) "it refuses")
          (is (str/includes? (ex-message e) "POSIX shell") "and names what is missing")
          (is (str/includes? (ex-message e) "pwsh") "and what it would have used instead")
          (is (str/includes? (ex-message e) "Git Bash") "and how to fix it")
          (is (= :no-posix-shell (:reason (ex-data e)))))))
    (testing "no shell at all"
      (with-redefs [shell/resolve* (constantly nil)]
        (shell/reset-resolution!)
        (let [e (try (shell/require-posix! "`git`, which") nil (catch Exception e e))]
          (is (some? e))
          (is (str/includes? (ex-message e) "POSIX shell"))
          (is (= :no-posix-shell (:reason (ex-data e)))))))
    (testing "a POSIX one is handed back rather than refused"
      (with-redefs [shell/resolve* (constantly {:command "/bin/bash" :kind :bash
                                                :posix? true :argv-prefix ["-lc"]})]
        (shell/reset-resolution!)
        (is (= :bash (:kind (shell/require-posix! "`git`, which"))))))
    (finally (shell/reset-resolution!))))

;; ------------------------------------------- what a shell does with a command
;;
;; THE LINE, NOT THE ARGV. On Windows the JVM builds ONE command line out of the argv
;; vector and Git Bash reads it back by rules of its own, so a command's bytes arrive
;; only if the two agree about the one character a shell command cannot do without
;; (harness.infra.shell's own section says which rule, and why). These cases are that
;; agreement, asserted where it matters -- by running the command and reading what the
;; shell says it received.

(def ^:private q
  "ONE double quote -- the character this whole subject is about, named rather than
  spelled inside a literal: a case that writes it has to quote its own source twice,
  and the second quoting is where a reader stops reading."
  (str (char 34)))

(def ^:private bs
  "ONE backslash, named for the same reason: it is what the escape is made of."
  (str (char 92)))

(deftest a-windows-command-line-carries-the-word-the-shell-will-read-back
  (testing "off Windows the command is the command: there the argv IS what is run"
    (is (= (str "printf 'a b' " q "c d" q)
           (shell/command-word :bash (str "printf 'a b' " q "c d" q) false)))
    (is (= "echo %CD%" (shell/command-word :cmd "echo %CD%" false))))
  (testing "on Windows a quote is escaped, and a backslash with it"
    (is (= (str "echo " bs q "a b" bs q)
           (shell/command-word :bash (str "echo " q "a b" q) true)))
    (is (= (str "echo a" bs bs "b")
           (shell/command-word :bash (str "echo a" bs "b") true))))
  (testing "and a command the JVM would not quote at all is given a leading blank"
    ;; The blank is what makes the JVM quote the word, and only a quoted word is read
    ;; back by the rule above. A shell skips it, so the command is unchanged.
    (is (= (str " a=" bs q "b" bs q)
           (shell/command-word :bash (str "a=" q "b" q) true)))
    (is (= "npm test" (shell/command-word :bash "npm test" true))
        "a command that already holds a blank needs nothing added"))
  (testing "cmd is handed its command exactly as the caller wrote it"
    ;; cmd reads no MSVCRT rule -- it takes a quote as a quote -- so escaping for it
    ;; would put backslashes IN the command instead of taking them out.
    (is (= (str "echo " q "a b" q " & echo c")
           (shell/command-word :cmd (str "echo " q "a b" q " & echo c") true)))))

(def ^:private quoted-word-probes
  "kind -> [what to run, the words its answer holds when the shell received the quoted
  word AS ONE WORD]. One row per shell this harness can name, each written in the
  language that shell's own `argv-prefix` promises a caller."
  {:git-bash   ["printf '[%s]' ONE \"TWO THREE\" FOUR" "[ONE][TWO THREE][FOUR]"]
   :bash       ["printf '[%s]' ONE \"TWO THREE\" FOUR" "[ONE][TWO THREE][FOUR]"]
   :pwsh       ["Write-Output \"ONE TWO\"" "ONE TWO"]
   :powershell ["Write-Output \"ONE TWO\"" "ONE TWO"]
   :cmd        ["echo \"ONE TWO\"" "\"ONE TWO\""]})

(deftest a-quoted-word-reaches-every-shell-this-machine-has-whole
  ;; THE BUG THIS PINS DOWN, as a caller meets it: a command that writes "TWO THREE"
  ;; means ONE word. If the quotes are eaten on the way in, the shell runs a DIFFERENT
  ;; command and the answer is merely wrong -- which the caller cannot tell from a
  ;; result it did not expect. Measured on this machine before the fix: a printf of
  ;; four words answered two of them and then stopped, with the rest of the line gone.
  ;;
  ;; EVERY SHELL THIS MACHINE HAS, because what is under test is the seam and not one
  ;; kind's way through it: the machine's own shell (the unnamed call) and every named
  ;; kind that resolves here. A kind this machine has not got is not run -- it cannot
  ;; be -- but the machine's own kind is asserted to be among the ones that were.
  (let [machine (:kind (shell/resolution))
        kinds   (->> (cons machine [:git-bash :bash :pwsh :powershell :cmd])
                     distinct
                     (filter #(some? (shell/resolution %))))]
    (is (some? machine) "this machine resolves a shell at all")
    (is (contains? (set kinds) machine)
        "and it is one of the kinds run below, not a kind skipped for being absent")
    (doseq [kind kinds
            :let [[command expected] (get quoted-word-probes kind)]]
      (testing (str "under " (name kind))
        (let [{:keys [exit out err]} (shell/run {:command command :kind kind :timeout-ms 30000})]
          (is (= 0 exit) (str (name kind) " ran it (stderr: " (str/trim (str err)) ")"))
          (is (str/includes? (str out) expected)
              (str (name kind) " answered " (pr-str out) ", which does not hold "
                   (pr-str expected) " -- the quotes a caller wrote did not arrive")))))))

(deftest the-rest-of-the-line-runs-after-a-quoted-word
  ;; THE OTHER TWO FACES OF THE SAME BUG. A bare quote ends the receiving parser's
  ;; quoted run, so what follows the next one is read as ARGV rather than as part of
  ;; the command: the commands after a `|` never ran, and a quote left open came back
  ;; as the SHELL's own complaint -- `unexpected EOF while looking for matching` about
  ;; a line the caller never wrote. Both are asserted here, on one line, because that
  ;; is how a caller met them.
  (let [{:keys [exit out err]} (shell/run
                                {:command (str "printf '[%s]' ONE \"TWO THREE\" FOUR; echo;"
                                               " echo END-MARKER; echo one | cat")
                                 :timeout-ms 20000})]
    (is (= 0 exit) (str "the command ran (stderr: " (str/trim (str err)) ")"))
    (is (str/includes? (str out) "[ONE][TWO THREE][FOUR]"))
    (is (str/includes? (str out) "END-MARKER") "and so did what followed on the same line")
    (is (str/includes? (str out) "one") "and what a pipe fed to the next command")
    (is (not (re-find #"(?i)unexpected (end of file|EOF)|unmatched" (str err)))
        (str "and the shell reported no quote it never saw closed: " (pr-str err)))))

(deftest the-shapes-a-command-is-built-out-of-still-mean-what-they-mean
  ;; The escaping between the caller's bytes and the shell is one more thing that can
  ;; be got wrong, so the four things a command is built out of besides a word -- a
  ;; single-quoted word, a redirection, a pipe, and stdin -- are asserted beside it.
  (let [dir (support/temp-dir "shell-command-shapes")]
    (testing "a single-quoted word is still one word"
      (let [{:keys [exit out]} (shell/run {:command "printf '[%s]' ONE 'TWO THREE' FOUR"
                                           :timeout-ms 20000})]
        (is (= 0 exit))
        (is (str/includes? (str out) "[ONE][TWO THREE][FOUR]"))))
    (testing "a redirection still writes the file it names"
      (let [target (io/file dir "redirected.txt")
            {:keys [exit]} (shell/run
                            {:command (str "echo written > "
                                           (shell/quote-arg (support/shell-path (.getAbsolutePath target))))
                             :timeout-ms 20000})]
        (is (= 0 exit))
        (is (= "written" (str/trim (slurp target :encoding "UTF-8"))))))
    (testing "a pipe still feeds what follows it"
      (let [{:keys [exit out]} (shell/run {:command "printf 'a\\nb\\nc\\n' | wc -l"
                                           :timeout-ms 20000})]
        (is (= 0 exit))
        (is (str/includes? (str/trim (str out)) "3"))))
    (testing "and stdin still reaches the command that reads it"
      (let [{:keys [exit out]} (shell/run {:command "cat"
                                           :stdin "sent-through-stdin"
                                           :timeout-ms 20000})]
        (is (= 0 exit))
        (is (str/includes? (str out) "sent-through-stdin"))))))
