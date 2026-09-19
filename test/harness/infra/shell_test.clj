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
    (is (= ["-lc"] (shell/argv-prefix :bash))))
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
        res (shell/run {:command command :timeout-ms 4000})
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

(deftest what-a-command-printed-before-the-limit-comes-back
  ;; The output is not discarded: a command that hangs after saying why it cannot
  ;; finish is exactly the case worth reading.
  (let [{:keys [out timeout]} (shell/run {:command "echo said-before-hanging; sleep 30"
                                          :timeout-ms 2000})]
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
