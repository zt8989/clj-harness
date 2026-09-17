#!/bin/zsh
# Evidence for .scratch/bash-lifetime -- run it from the repo root:
#
#   zsh .scratch/bash-lifetime/evidence/bash-lifetime.clj.sh
#
# Three claims, each measured rather than asserted in prose:
#   1. a foreground call that hangs is stopped at its limit AND the child it started
#      is gone -- the child's pid comes from the command itself, and the check is made
#      INSIDE the process that ran the command, before anything exits;
#   2. a background job can be started, read twice, and stopped -- and stopping takes
#      its child too;
#   3. a JVM that EXITS (not just stops a job) leaves no job process behind, checked
#      from OUTSIDE that JVM with `ps` -- the one claim the test suite cannot make
#      without forking a JVM.
#
# The temp home keeps this off the developer's real ~/.clj-harness and ~/AGENTS.md:
# the config root via CLJ_HARNESS_HOME, the OS home via -Duser.home, and they are
# passed as SEPARATE argv elements (the launcher here ignores :jvm-opts, and
# `-J-Duser.home=...` is one word on purpose).
set -u
cd "$(dirname "$0")/../../.." || exit 1
REPO="$(pwd)"
TMP="$(mktemp -d /tmp/bash-lifetime-evidence-XXXXXX)"
export CLJ_HARNESS_HOME="$TMP/home"
mkdir -p "$CLJ_HARNESS_HOME" "$TMP/os-home"
CLJ=(clojure "-J-Duser.home=$TMP/os-home" -M:test)
PIDFILE="$TMP/child.pid"
STOPFILE="$TMP/stopped.pid"
EXITFILE="$TMP/jvm-exit.pid"
export PIDFILE STOPFILE EXITFILE

echo "repo:      $REPO"
echo "temp home: $CLJ_HARNESS_HOME"
echo

echo "== 1) 前台：2000ms 的时限，命令自己起一个 sleep 60 的孩子 =="
"${CLJ[@]}" -e '
(do
(require (quote [clojure.data.json :as json])
         (quote [harness.cap.tools :as cap-tools])
         (quote [harness.kernel.tools :as tools]))
(defn alive? [pid]
  (boolean (when-let [h (.orElse (java.lang.ProcessHandle/of (long pid)) nil)]
             (.isAlive ^java.lang.ProcessHandle h))))
(cap-tools/install!)
(let [t0 (System/currentTimeMillis)
      answer (:content (tools/run! {:function {:name "bash"
                                               :arguments (json/write-str
                                                           {:command (str "sleep 60 & echo $! > " (System/getenv "PIDFILE") "; wait")
                                                            :timeout 2000})}}))
      ms (- (System/currentTimeMillis) t0)
      pid (Long/parseLong (clojure.string/trim (slurp (System/getenv "PIDFILE"))))]
  (println "answer:      " (pr-str answer))
  (println "elapsed-ms:  " ms " (the limit was 2000, the command wanted 60s)")
  (println "child pid:   " pid)
  (println "child alive: " (alive? pid)))
nil)
'
echo

echo "== 2) 后台：起一条打三行的命令，读两次；再起一条并停掉它 =="
"${CLJ[@]}" -e '
(do
(require (quote [clojure.data.json :as json])
         (quote [harness.cap.tools :as cap-tools])
         (quote [harness.kernel.tools :as tools]))
(defn alive? [pid]
  (boolean (when-let [h (.orElse (java.lang.ProcessHandle/of (long pid)) nil)]
             (.isAlive ^java.lang.ProcessHandle h))))
(cap-tools/install!)
(defn call [n a] (:content (tools/run! {:function {:name n :arguments (json/write-str a)}})))
(let [started (call "bash_background" {:command "for i in 1 2 3; do echo tick-$i; sleep 0.4; done"})
      id (second (re-find #"job (j\d+) started" started))]
  (println "start:   " started)
  (Thread/sleep 700)
  (println "read #1: " (pr-str (call "bash_output" {:job id})))
  (Thread/sleep 2500)
  (println "read #2: " (pr-str (call "bash_output" {:job id}))))
(let [started (call "bash_background"
                    {:command (str "sleep 60 & echo $! > " (System/getenv "STOPFILE") "; wait")})
      id (second (re-find #"job (j\d+) started" started))]
  (Thread/sleep 1500)
  (let [pid (Long/parseLong (clojure.string/trim (slurp (System/getenv "STOPFILE"))))
        answer (call "bash_kill" {:job id})]
    (println "kill:        " (pr-str answer) " on" id)
    (println "child pid:   " pid)
    (println "child alive: " (alive? pid)))
  (println "read after:  " (pr-str (call "bash_output" {:job id}))))
nil)
'
echo

echo "== 3) 一个真的退出（不是停作业）的 JVM，它的作业进程还在不在 =="
"${CLJ[@]}" -e '
(do
(require (quote [harness.cap.jobs :as jobs]))
(let [id (jobs/start! "evidence" {:command (str "sleep 60 & echo $! > " (System/getenv "EXITFILE") "; wait")})]
  (println "child JVM started job:" id)
  (Thread/sleep 2000))
(println "child JVM is exiting now")
nil)
'
sleep 2
if [ -s "$EXITFILE" ]; then
  EPID="$(cat "$EXITFILE")"
  echo "job child pid: $EPID"
  if ps -p "$EPID" > /dev/null 2>&1; then
    echo "child alive:   YES  <-- 退出钩子没收掉"
  else
    echo "child alive:   no   (checked from OUTSIDE the JVM that started it, with ps)"
  fi
else
  echo "the child JVM never wrote its job's pid"
fi
echo

rm -rf "$TMP"
echo "temp home removed: $TMP"
