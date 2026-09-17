(ns harness.infra.shell
  "The one place that decides which shell this process spawns, and how to run
  something in it with a payload on stdin and a bounded wait.

  WHY IT IS ITS OWN NAMESPACE: three callers now spawn shell commands -- the
  `bash` tool, the hook engine running a declared hook command, and an MCP
  server -- and the trap below is a property of the MACHINE, not of any caller.
  Written down twice, one of them would eventually be fixed and the other not.

  TWO KINDS OF SPAWN, and the difference is who talks first. `run` is the one-shot
  kind (stdin in, answer out, bounded wait). `start` is the LONG-LIVED kind: a
  process that is still there after it has answered, and that a caller talks to
  line by line. A hook is the first kind; an MCP server is the second.

  AND ON WINDOWS THE TWO KINDS DO NOT RUN IN THE SAME SHELL: `start` hands the
  command to Windows' own `cmd /c`, while `run` still goes through bash.
  That is not an inconsistency, it is what each kind of command IS -- see
  `start`'s own note, and `windows-argv` for the reason in full.

  THE TRAP. On Windows, `bash` on PATH is C:\\WINDOWS\\System32\\bash.exe -- the
  WSL launcher, which is a different filesystem entirely and, from a JVM, fails
  by producing nothing at all. So where a Git Bash install is found it is pinned
  by absolute path, and a `bash` found on PATH is REFUSED when it is that file:
  the lookup is the same on every platform, which is why this is a search rather
  than an os.name test.

  AND THE SEARCH IS WRITTEN DOWN, because on Windows Git Bash may simply not be
  installed at all. The chain is Git Bash (two known install paths) -> a `bash` on
  PATH -> `pwsh` -> `powershell` -> `cmd`, and each step knows HOW IT IS STARTED:
  `-lc` is bash's spelling, `-NoProfile -Command` is PowerShell's, `/c` is cmd's.
  What stood here before was `(or (find-git-bash) \"bash\")`, and on a Windows box
  without Git Bash that answer is a name which resolves to the WSL launcher -- a
  shell that runs nothing and says nothing. A resolution that names a program
  which cannot run is worse than one that admits there is none, so the chain has
  no guessed tail.

  WHAT A SHELL IS, AS THIS NAMESPACE ANSWERS IT:

    {:command \"C:\\Program Files\\Git\\bin\\bash.exe\"   ; what is spawned
     :kind    :git-bash                                   ; which one it is
     :posix?  true                                        ; -lc and single quotes mean
                                                          ;   anything here?
     :argv-prefix [\"-lc\"]}                                ; how the command is handed over

  IT IS RESOLVED ONCE PER PROCESS, because it is a fact about the machine rather
  than about a call, and the resolution is a pure function of the chain plus an
  existence question (`select`) -- so the Windows-only steps are asserted on a
  machine that has neither Git Bash nor pwsh."
  (:require [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.io BufferedReader]
           [java.nio.charset StandardCharsets]
           [java.util.concurrent LinkedBlockingQueue TimeUnit]))

;; ------------------------------------------------------ which shell, and how

(defn- windows?
  "Is this machine Windows? Asked rather than assumed, because the trap below is
  one platform's, and a test that wants to reason about the other one can say so."
  []
  (str/includes? (str/lower-case (System/getProperty "os.name" "")) "win"))

(def candidates
  "The chain, in order. Each row is a KIND and a NAME TO LOOK FOR -- the first two
  are absolute because a Git Bash install is not on PATH the way a person means it,
  and the rest are names, looked up on PATH when the resolution is asked for.
  Adding a step is adding a row; nothing else here knows how many there are."
  [{:kind :git-bash   :command "C:\\Program Files\\Git\\bin\\bash.exe"}
   {:kind :git-bash   :command "C:\\Program Files\\Git\\usr\\bin\\bash.exe"}
   {:kind :bash       :command "bash"}
   {:kind :pwsh       :command "pwsh"}
   {:kind :powershell :command "powershell"}
   {:kind :cmd        :command "cmd"}])

(def ^:private how-to-start
  "kind -> the argv a command goes behind. One table rather than a `case` at each
  spawn site: there are three spawn sites, and a second copy of this would
  eventually be fixed in one of them."
  {:git-bash   ["-lc"]
   :bash       ["-lc"]
   :pwsh       ["-NoProfile" "-Command"]
   :powershell ["-NoProfile" "-Command"]
   :cmd        ["/c"]})

(defn argv-prefix
  "How a command is handed to KIND. `-lc` is bash's spelling, `-NoProfile
  -Command` is PowerShell's, `/c` is cmd's -- and which one is right is not a
  style question: the wrong flags are a command arriving as arguments to a
  program that has no idea what to do with them."
  [kind]
  (get how-to-start kind ["-lc"]))

(defn posix?
  "Does KIND read `-lc` and single-quoted words the way this harness builds them?
  bash and Git Bash do; PowerShell and cmd do not."
  [kind]
  (contains? #{:git-bash :bash} kind))

(defn- path-like?
  "Does COMMAND name a place rather than a program to go looking for on PATH?"
  [command]
  (boolean (re-find #"[\\/]" (str command))))

(defn- wsl-launcher?
  "Is PATH Windows' own bash.exe -- the WSL launcher, whose filesystem is not this
  one and which, from a JVM, answers nothing at all? See this namespace's
  docstring for the trap."
  [path]
  (let [p (str/lower-case (str/replace (str path) "\\" "/"))]
    (str/ends-with? p "/system32/bash.exe")))

(defn on-path
  "The first existing FILE named COMMAND in PATH-STRING, as an absolute path, or
  nil.

  PATH-STRING IS AN ARGUMENT rather than something read here: what a machine's
  PATH holds is exactly the kind of thing the chain is asked about, and a function
  that read the environment itself could only ever be tested against the one this
  process happens to have. Because of that, `locator` is the only caller that
  has to know where PATH comes from.

  The suffixes are Windows': a program there is `pwsh.exe` while the name a person
  types is `pwsh`. ALL of them are tried on every platform rather than added on
  Windows alone, because the WSL trap this namespace exists for -- a `bash.exe` that
  is not a bash -- can only be reproduced by a lookup that considers the name, and a
  rule that can only be asserted on the platform it protects is a rule nobody ever
  re-checks."
  [command path-string]
  (let [sep   (java.util.regex.Pattern/quote (System/getProperty "path.separator" ":"))
        dirs  (remove str/blank? (str/split (str path-string) (re-pattern sep)))
        names [(str command) (str command ".exe") (str command ".cmd") (str command ".bat")]]
    (some (fn [dir]
            (some (fn [name]
                    (let [f (io/file dir name)]
                      (when (and (.exists f) (.isFile f)) (.getAbsolutePath f))))
                  names))
          dirs)))

(defn locator
  "The existence question `select` asks, as a function of PATH-STRING: where is
  this row, if it is anywhere?

  THE WSL LAUNCHER IS REFUSED HERE, not after the fact: a `bash` that is that
  file is not a bash for this harness's purposes, so the row counts as ABSENT and
  the chain walks on to pwsh. That refusal is the whole difference between this
  and the `(filter #(.exists ..))` it replaces."
  [path-string]
  (fn [{:keys [command]}]
    (if (path-like? command)
      (let [f (io/file command)]
        (when (and (.exists f) (.isFile f)) (.getAbsolutePath f)))
      (when-let [found (on-path command path-string)]
        (when-not (wsl-launcher? found) found)))))

(defn select
  "The row the chain lands on: the first candidate LOCATE can find, with :command
  replaced by where it actually is. Nil when it finds none of them.

  PURE, AND THAT IS THE POINT. The chain is data and 'is it there' is the
  argument, so every branch of it -- Git Bash, the WSL refusal, pwsh, cmd, none at
  all -- is asserted without changing this machine's PATH and without owning a
  Windows box to change it on."
  [candidates locate]
  (some (fn [c] (when-let [found (locate c)] (assoc c :command found))) candidates))

(defn resolve*
  "This process's shell, asked of the REAL machine -- the uncached read behind
  `resolution`:

    {:command \"..\" :kind :bash :posix? true :argv-prefix [\"-lc\"]}

  or NIL when the machine has none of the candidates. Nil is an answer, not a
  failure: a resolution naming a shell that cannot run is the worse one, and a
  caller that needs one says so through `require-shell!`."
  []
  (when-let [c (select candidates (locator (System/getenv "PATH")))]
    {:command     (:command c)
     :kind        (:kind c)
     :posix?      (posix? (:kind c))
     :argv-prefix (argv-prefix (:kind c))}))

(defonce ^:private resolved
  ;; A fact about the MACHINE, and machines do not change under a running process,
  ;; so it is asked once -- the same defonce and the same reason as the `binary`
  ;; it replaces. Held in a vector so that "asked, and there is none" is a cached
  ;; answer too, rather than a question asked again at every spawn.
  (atom nil))

(defn resolution
  "The process's shell -- {:command :kind :posix? :argv-prefix} -- resolved once
  per process, or nil when the machine has none of the chain's candidates."
  []
  (if-let [cached @resolved]
    (first cached)
    (let [r (resolve*)]
      (reset! resolved [r])
      r)))

(defn reset-resolution!
  "Forget the cached answer so the next `resolution` asks again. For tests that
  drive the chain; a running process's machine does not change under it."
  []
  (reset! resolved nil))

(defn require-shell!
  "The resolution, or a refusal that NAMES what is missing. A spawn site asks this
  rather than dereferencing `resolution` itself, so 'this machine has no shell' is
  a sentence instead of a null dereference three frames down."
  []
  (or (resolution)
      (throw (ex-info (str "this machine has no shell this harness can spawn: Git Bash,"
                           " bash, pwsh, PowerShell and cmd were all looked for and none"
                           " was found, so nothing can be run. Install one of them -- Git"
                           " for Windows is what this harness knows how to talk to.")
                      {:reason :no-shell}))))

(defn require-posix!
  "The resolution, or a refusal BY NAME when the shell this process spawns is not
  POSIX. CALLER names what cannot run, so the sentence can say so.

  A CALLER THAT BUILDS A COMMAND LINE is the one that has to ask. rg and git
  splice values into a line with POSIX single quotes (see `quote-arg`), which mean
  nothing to PowerShell and less to cmd -- a path with a space in it would arrive
  as two arguments and the caller would never know. Running anyway, under a second
  quoting convention nobody wrote, is the failure this exists to prevent."
  [caller]
  (let [r (resolution)]
    (cond
      (nil? r)
      (throw (ex-info (str caller " needs a POSIX shell, and this machine has no shell"
                           " at all: Git Bash, bash, pwsh, PowerShell and cmd were all"
                           " looked for. Install Git Bash (or put a bash on PATH) and it"
                           " comes back.")
                      {:reason :no-posix-shell :shell nil}))

      (:posix? r)
      r

      :else
      (throw (ex-info (str caller " needs a POSIX shell, and the shell this machine"
                           " would spawn is " (name (:kind r)) " (" (:command r) "), which"
                           " does not read the single-quoted command lines this harness"
                           " builds. Install Git Bash (or put a bash on PATH) and it"
                           " comes back.")
                      {:reason :no-posix-shell :shell (:kind r)})))))

(defn quote-arg
  "S as a single-quoted POSIX word, for a caller that is BUILDING a command line
  rather than passing one. The `'\\''` dance is the only way to put a quote
  inside a single-quoted word.

  Here rather than in the two namespaces that need it, for this namespace's own
  reason: a command line goes through `bash -lc`, so how a value is quoted is a
  property of how this process spawns things -- and a second copy of it is a
  second chance for one caller to be fixed and the other left open."
  [s]
  (str "'" (str/replace (str s) "'" "'\\''") "'"))

(defn- kill-tree!
  "Stop P and everything it started.

  THE CHILD IS NOT THE COMMAND, and that is the whole reason this exists: what a
  spawn site holds is a SHELL, and the process a person means is that shell's
  child. `npx` is the ordinary case (a node program that starts the real server
  itself), and so is `npm test` (bash starts npx, npx starts node). Killing the
  shell alone leaves the real one running with a closed stdin -- a leak that also
  outlives the harness that made it.

  So the descendants are collected FIRST (once their parent dies they are
  reparented, and the tree that was there a moment ago can no longer be walked),
  then the direct child is asked to stop, and anything still standing is killed
  outright. Gentle where it can be, conclusive where it must be.

  BOTH KINDS OF SPAWN END HERE: `run` when its time limit is reached, and `start`
  when a caller closes the handle."
  [^Process p]
  (let [kids (try (vec (.toList (.descendants (.toHandle p)))) (catch Exception _ []))]
    (.destroy p)
    (when-not (try (.waitFor p 2 TimeUnit/SECONDS) (catch Exception _ true))
      (.destroyForcibly p))
    (doseq [^java.lang.ProcessHandle k kids] (.destroy k))
    (doseq [^java.lang.ProcessHandle k kids :when (.isAlive k)] (.destroyForcibly k))
    nil))

(defn run
  "Run COMMAND the way A: once, with STDIN written to it and then CLOSED, and no
  more than TIMEOUT-MS of waiting. Returns

    {:exit n :out \"..\" :err \"..\"}                 it finished
    {:exit nil :out \"..\" :err \"..\" :timeout true} it was killed at the limit

  The output it produced BEFORE the timeout is returned rather than discarded:
  a hook that hangs after printing its reason should still be readable.

  AT THE LIMIT THE WHOLE TREE GOES, not just the shell we hold -- `kill-tree!`'s
  own note says why the child is not the command. Until this was wired in here,
  a timed-out `bash` tool call left `npm`/`node`/`sleep` running with nobody
  attached to it.

  STDIN IS CLOSED RATHER THAN LEFT OPEN: the write end is closed with the writer,
  so a command that reads stdin sees EOF instead of waiting forever for a parent
  that is not going to type anything.

  A command that cannot be spawned at all throws -- :exit only means anything for
  a process that started, and callers must not read 'we never ran it' as
  'it exited 0'."
  [{:keys [command stdin dir timeout-ms]}]
  (let [r  (require-shell!)
        pb (doto (ProcessBuilder. (vec (concat [(:command r)] (:argv-prefix r) [command])))
             (.redirectErrorStream false))
        _  (when dir (.directory pb (io/file dir)))
        p  (.start pb)
        w  (future
             (try
               (with-open [os (.getOutputStream p)]
                 (.write os (.getBytes (str stdin) StandardCharsets/UTF_8)))
               (catch Exception _ nil)))
        ;; Drain both pipes on their own threads: a process that fills a pipe
        ;; buffer blocks forever, so reading them only after waitFor is how a
        ;; well-behaved hook becomes a timeout.
        o  (future (slurp (.getInputStream p) :encoding "UTF-8"))
        e  (future (slurp (.getErrorStream p) :encoding "UTF-8"))
        done (.waitFor p (long (or timeout-ms 30000)) TimeUnit/MILLISECONDS)]
    (when-not done (kill-tree! p))
    (let [out (try (deref o 5000 "") (catch Exception _ ""))
          err (try (deref e 5000 "") (catch Exception _ ""))]
      @w
      (if done
        {:exit (.exitValue p) :out out :err err}
        {:exit nil :out out :err err :timeout true}))))

;; ----------------------------------------------------------- long-lived spawn

(def ^:private eof
  "What `:next-line` answers once the process has closed its stdout. A keyword
  rather than nil, because nil is what an empty line reads as and 'it ended' and
  'it printed nothing' are different facts."
  ::eof)

(defn eof? [v] (= eof v))

(defn timeout?
  "Did `:next-line` run out of patience? Asked here rather than compared against
  a literal, because `::timeout` is THIS namespace's keyword and a caller writing
  its own `::timeout` would be comparing two different keywords that print the
  same -- a bug that looks like 'the timeout case never fires'."
  [v]
  (= ::timeout v))


(def ^:private windows-argv
  "How a long-lived command is run on Windows, as the `[program flag]` a caller
  appends the command to -- resolved once, because where `cmd` lives is a fact
  about the machine and not about the call.

  WHY NOT BASH HERE, when this namespace's whole reason to exist was pinning one:
  on this platform that shell is Git Bash, and in a shell a backslash is an
  escape. So an absolute path handed to it is not a path --
  `C:\\Users\\me\\server.js` arrives as `C:Usersmeserver.js` -- and a server
  declared the way a Windows person writes one is never found: the command exits
  127 and the failure reads as 'this server would not start'. A person configuring
  a server on Windows writes Windows paths; the thing that runs it has to take
  them literally.

  cmd RATHER THAN pwsh/powershell, though both would also take the backslashes:
  PowerShell decodes a native command's stdout into strings and re-encodes it on
  the way out, in the console's code page -- GBK on a Chinese Windows. For a
  STREAM that is a protocol (a server speaking JSON lines) that is not a neutral
  choice: it puts a transcoder in the middle of the pipe. `cmd /c` is not a
  participant -- it starts the program and gets out of the way, so the bytes on
  the pipe are the bytes the server wrote."
  (delay
    (let [root (or (System/getenv "SystemRoot") "C:\\Windows")
          cmd  (io/file root "System32" "cmd.exe")]
      [(if (.exists cmd) (.getAbsolutePath cmd) "cmd.exe") "/c"])))

(defn spawn-argv
  "COMMAND as the argv to spawn for a LONG-LIVED process, in one of two SHAPES:

    :program  a PROGRAM TO LAUNCH. Its command line is a path plus flags, and it
              must survive verbatim -- which on Windows means Windows' own `cmd /c`
              (see `windows-argv` for why bash must not be used for this).
    :shell    a command written FOR A SHELL, exactly as `run` takes one: it goes to
              the shell this process resolved, with that shell's own argv prefix.

  THE TWO SHAPES DIFFER ON WINDOWS ONLY, and that is the whole reason this asks:
  there `:program` needs cmd and `:shell` needs Git Bash. Everywhere else both are
  the resolved shell, which is why the difference is easy to miss -- a server
  declared as `node server.js` and a *shell command* like `npm test &` look like
  the same thing until the machine is Windows.

  PURE IN THE TWO FACTS IT BRANCHES ON -- whether this is Windows, and the shell
  resolution -- so that BOTH branches can be asserted on one machine. A test asking
  for the Windows answer does not need Windows, and the branch it asserts is the
  one no mac will ever take on its own.

  ONLY `start` asks this. `run` always hands its command to the resolved shell,
  because everything `run` executes is written for a shell."
  ([shape command] (spawn-argv shape command (windows?) (resolution)))
  ([shape command on-windows? shell-res]
   (if (and on-windows? (= shape :program))
     (into (vec @windows-argv) [command])
     (let [r (or shell-res (require-shell!))]
       (into (vec (concat [(:command r)] (:argv-prefix r))) [command])))))

(defn start
  "Spawn COMMAND as a LONG-LIVED process -- the kind `run` cannot do: a process
  that has to still be there after it answers, and that is talked to line by line.

  Returns a handle, or throws when the process cannot be started at all (the
  shell itself missing, the process limit): a caller must be able to tell 'it
  never started' from 'it started and said nothing':

    {:process p
     :next-line (fn [timeout-ms] -> string | ::eof | ::timeout)
     :write-line! (fn [s] -> boolean)     ; one line out, flushed; false = it is gone
     :stderr (fn [] -> string)            ; what it has written to stderr SO FAR
     :alive? (fn [] -> boolean)
     :close! (fn [])}                     ; kill it and stop the pumps

  `:shape` says which of `spawn-argv`'s two kinds this is, and defaults to
  `:program` -- the server case, which is what `start` was written for.

  STDERR IS DRAINED AND ONLY DRAINED. A process that fills its stderr pipe blocks
  forever, so it is read on its own thread into a buffer -- and that buffer is
  DIAGNOSTICS, never protocol. A caller that parsed stderr as if it were an answer
  would be reading a log line as a decision, which is the same mistake the hook
  engine refuses to make.

  `:env` is ADDED to the inherited environment rather than replacing it: a server
  declared with one token still needs PATH to find its own runtime."
  [{:keys [command dir env shape] :or {shape :program}}]
  (let [pb (doto (ProcessBuilder. ^"[Ljava.lang.String;" (into-array String (spawn-argv shape command)))
             (.redirectErrorStream false))
        _  (when dir (.directory pb (io/file dir)))
        _  (when (seq env) (.putAll (.environment pb) (into {} env)))
        p  (.start pb)
        q  (LinkedBlockingQueue.)
        os (.getOutputStream p)
        err (StringBuilder.)
        ;; One thread per pipe, both started before anything reads: see the
        ;; stderr note above, and the same reason for stdout -- a caller that is
        ;; not reading right now must not be able to wedge the server.
        out-pump (future
                   (try
                     (with-open [r (io/reader (.getInputStream p) :encoding "UTF-8")]
                       (loop []
                         (when-let [line (.readLine ^BufferedReader r)]
                           (.put q line)
                           (recur))))
                     (catch Exception _ nil)
                     (finally (.put q eof))))
        err-pump (future
                   (try
                     (with-open [r (io/reader (.getErrorStream p) :encoding "UTF-8")]
                       (loop []
                         (when-let [line (.readLine ^BufferedReader r)]
                           (locking err (.append err (str line "\n")))
                           (recur))))
                     (catch Exception _ nil)))]
    {:process p
     :next-line (fn [timeout-ms]
                  (let [v (.poll q (long (or timeout-ms 1000)) TimeUnit/MILLISECONDS)]
                    (cond (nil? v) ::timeout
                          (= eof v) eof
                          :else v)))
     :write-line! (fn [s]
                    (try
                      (.write os (.getBytes (str s "\n") StandardCharsets/UTF_8))
                      (.flush os)
                      true
                      (catch Exception _ false)))
     :stderr (fn [] (locking err (.toString err)))
     :alive? (fn [] (.isAlive p))
     :close! (fn []
               (kill-tree! p)
               (try (.close os) (catch Exception _ nil))
               (future-cancel out-pump)
               (future-cancel err-pump)
               nil)}))
