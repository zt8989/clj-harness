(ns harness.infra.env
  "What this MACHINE is, as the few facts a model needs before it writes a shell
  command: the platform, the shell a command actually goes to, and which
  command-line enhancers that shell can see.

  WHY THIS IS NOT DERIVED WHERE IT IS PRINTED. All three are properties of the
  machine rather than of a session -- the same answers hold for every thread in this
  process, and for the whole life of it -- so they are resolved once here and the
  block that states them (harness.cap.system-prompt's <env> row) reads and never
  re-decides. That is the same split as harness.infra.shell's own resolution.

  AND WHY THE PROBE GOES THROUGH THE SHELL. `System/getenv` would answer with the
  JVM's PATH, and the JVM's PATH is not the one the model's command will meet: the
  shell is started as a LOGIN shell, and a login shell re-sources the profile, which
  is where a person's PATH actually lives. Asking the JVM would produce a sentence
  that is false by the time it is acted on -- 'there is no rg here' printed by a
  shell that has one -- so the question is put to the same shell that will run the
  commands the answer is about. One spawn, cached.

  NOT KNOWING IS AN ANSWER. A shell that will not start, or one that takes longer
  than the bound, leaves the enhancer half saying it could not ask rather than
  disappearing: a block that vanishes when a machine is unusual is worse than one
  that admits it does not know."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [harness.infra.shell :as shell]))

(def enhancers
  "The command-line enhancers this harness asks about, in the order they are named.
  THE LIST IS THE POINT: 'write an `rg` and the machine has no rg' is the one
  mistake this half of the block exists to prevent, and it is only preventable for
  names somebody wrote down. Adding a name is adding a row."
  ["rg" "fd" "jq" "git"])

(def ^:private probe-timeout-ms
  "How long the probe may take. Short: it is one `command -v` loop, and it runs while
  a run is waiting to start."
  5000)

(defn platform
  "This machine's platform, as one of windows / macos / linux."
  []
  (let [n (str/lower-case (System/getProperty "os.name" ""))]
    (cond (str/includes? n "win")   "windows"
          (str/includes? n "mac")   "macos"
          (str/includes? n "linux") "linux"
          :else n)))

;; ------------------------------------------------------- the machine's temp dirs
;;
;; NOT PART OF THE <env> BLOCK, and the reason it lives here anyway: 'which directories
;; is scratch meant to live in' is a fact about the MACHINE, decided from the platform's
;; own property rather than derived at each reader. The one reader is
;; harness.cap.project/fence, which frees them; the block that states the fence reads the
;; fence, not this.

(def ^:dynamic *temp-dir-override*
  "Test-only stand-in for this machine's temporary directories -- the same rule and the
  same reason as harness.infra.home/*root-override*: a test run has to be able to point
  the fence's temp entry somewhere its own fixtures do NOT live, or freeing temp would
  free the suite's project and config-home fixtures too (isolate! puts every one of them
  under java.io.tmpdir) and the fence tests would prove nothing. Nil in production, where
  the property stands; bound by the test runner, never touched by production code."
  nil)

(defn temp-dirs
  "This machine's temporary directories, canonical and de-duplicated, in order:
  java.io.tmpdir, then POSIX /tmp when it is a real absolute directory of its own.

  BOTH SPELLINGS A PERSON ACTUALLY WRITES, which is the point: on macOS java.io.tmpdir is
  the per-user /var/folders/.../T while /tmp is a different directory, and on Linux the
  two collapse to one entry. Windows has no /tmp -- a leading / there is drive-relative
  rather than absolute -- so it is not offered as one.

  CANONICALIZING IS WHAT MAKES /var/... AND /private/var/... ONE ANSWER rather than two,
  and it is the same collapse harness.cap.project/under? performs on the path it tests,
  so this answer and the gate cannot disagree about which directory a path is in.

  THE OVERRIDE REPLACES the list rather than adding to it: an isolated run wants one
  stand-in, not the machine's real temp beside it."
  []                              ; *temp-dir-override* replaces the machine's answer
  (or *temp-dir-override*
      (->> [(System/getProperty "java.io.tmpdir") "/tmp"]
           (remove nil?)
           (map io/file)
           (filter #(.isAbsolute ^java.io.File %))
           (filter #(.isDirectory ^java.io.File %))
           (map #(.getCanonicalPath ^java.io.File %))
           distinct
           vec)))

(defn- platform-line
  "PLATFORM with the version and architecture the OS reports. One line rather than
  three: 'which python is this, which libc' is the next question, and the version is
  where that answer starts."
  []
  (str "platform: " (platform) " (" (System/getProperty "os.name") " "
       (System/getProperty "os.version") ", " (System/getProperty "os.arch") ")"))

(defn- shell-line
  "Which shell a command is handed to, and HOW -- the kind, the program, and the
  argv it goes behind. All three come from harness.infra.shell's resolution, the one
  place that decides this; on a machine that has none of the chain's candidates the
  line says exactly that, because 'nothing can be run here' is the single most
  important thing a model writing a command could be told."
  []
  (if-let [r (shell/resolution)]
    (str "shell: " (name (:kind r)) " (at " (:command r) "); commands run through `"
         (:command r) " " (str/join " " (:argv-prefix r)) "`")
    (str "shell: none of Git Bash, bash, pwsh, PowerShell or cmd was found on this"
         " machine, so nothing can be run here")))

(defn- probe-command
  "One command line that prints, one per line, the NAMES this shell can see.

  A PURE FUNCTION OF KIND, because every shell asks its own way: `command -v` is a
  POSIX builtin, `Get-Command` is PowerShell's, `where` is cmd's. There is no
  shorter command that means the same thing to all three, and the wrong one is not
  a weaker answer -- it is a command that fails."
  [kind names]
  (case kind
    :cmd
    (str "for %n in (" (str/join " " names) ")"
         " do @(where %n >nul 2>nul && echo %n)")

    (:pwsh :powershell)
    (str "foreach ($n in @(" (str/join "," (map #(str "'" % "'") names)) "))"
         " { if (Get-Command $n -ErrorAction SilentlyContinue) { $n } }")

    ;; POSIX, and the default: bash is what the rest of this repository assumes.
    (str "for n in " (str/join " " names)
         "; do command -v \"$n\" >/dev/null 2>&1 && echo \"$n\"; done")))

(defn probe*
  "The names from `enhancers` this machine's shell can see, as a set -- or :unknown
  when the question could not be put to it. The uncached read behind `probe`."
  []
  (if-let [r (shell/resolution)]
    (try
      (let [{:keys [out timeout]}
            (shell/run {:command (probe-command (:kind r) enhancers)
                        :timeout-ms probe-timeout-ms})]
        (if timeout
          :unknown
          (set (filter (set enhancers) (str/split-lines (str out))))))
      (catch Exception _ :unknown))
    :unknown))

(defonce ^:private probed
  ;; A fact about the MACHINE, asked once -- `resolution`'s own reason and shape.
  ;; Held in a vector so that :unknown is a cached answer too, rather than a
  ;; question asked again on every assembly.
  (atom nil))

(defn probe
  "The names from `enhancers` this shell can see, as a set, or :unknown when the
  shell could not be asked.

  ONE SPAWN PER PROCESS, cached: which programs a machine has does not change while
  a session runs, and a probe on every run would put a shell spawn in front of every
  message. A machine that gained rg mid-session gains it at the next restart, which
  is when the resolution above would change too."
  []
  (if-let [cached @probed]
    (first cached)
    (let [p (probe*)]
      (reset! probed [p])
      p)))

(defn reset-probe!
  "Forget the cached answer so the next `probe` asks again. For tests that drive the
  two halves of the answer; a running process's machine does not change under it."
  []
  (reset! probed nil))

(defn- tools-lines
  "The two lines that answer 'which of the enhancers can I use here' -- 'have' and
  'not found' both stated, because the second is the whole reason the first is not
  enough. A probe that could not answer says so instead, and says nothing about what
  is missing: claiming a program is absent when it was never asked for is the
  failure this half is careful about."
  []
  (let [p (probe)]
    (if (= :unknown p)
      [(str "available: unknown (this shell did not answer, so the command-line tools"
            " on this machine could not be checked)")]
      (let [have (filterv p enhancers)
            miss (filterv (complement p) enhancers)]
        [(str "available: " (if (seq have) (str/join ", " have) "(none from the list)"))
         (str "not found: " (if (seq miss) (str/join ", " miss) "(nothing from the list)"))]))))

(defn lines
  "The lines the <env> block states, in order: the platform, the shell commands go
  to, and which of `enhancers` that shell can see.

  The tag itself is the printing block's business (harness.cap.system-prompt): this
  namespace answers facts, and a fact is the same fact whichever tags it."
  []
  (into [(platform-line) (shell-line)] (tools-lines)))
