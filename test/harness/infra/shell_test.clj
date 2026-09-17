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

(deftest a-bash-that-is-windows-wsl-launcher-does-not-count-as-a-bash
  ;; The trap. C:\\WINDOWS\\System32\\bash.exe is another filesystem, and from a JVM it
  ;; answers nothing at all; a chain that took it would report a shell that runs
  ;; nothing. The plant is a `bash.exe` under a directory called System32 and a real
  ;; pwsh next door: the lookup refuses the first and the chain walks to the second.
  (let [sys32 (io/file (support/temp-dir "wsl-root") "System32")
        bin   (path-with "pwsh")]
    (.mkdirs sys32)
    (spit (io/file sys32 "bash.exe") "")
    (let [path (str sys32 (System/getProperty "path.separator") bin)]
      (testing "the launcher is not a bash"
        (is (nil? ((shell/locator path) {:kind :bash :command "bash"}))))
      (testing "so the chain does not stop there"
        (is (= :pwsh (:kind (shell/select shell/candidates (shell/locator path))))))
      (testing "and with NOTHING but the launcher the answer is nothing at all"
        ;; Rather than the string "bash", which is the name of a program that
        ;; answers nothing on this machine.
        (is (nil? (shell/select shell/candidates (shell/locator (str sys32)))))
        (testing "while a real bash elsewhere on PATH is taken"
          (let [bin (path-with "bash")]
            (is (= :bash (:kind (shell/select shell/candidates (shell/locator bin)))))))))))

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
