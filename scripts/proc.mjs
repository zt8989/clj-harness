// WHAT A LAUNCHER HAS TO DO DIFFERENTLY PER PLATFORM, in one place.
//
// `dev.mjs` starts long-running children (a backend, a browser) and has to be able to
// stop them again -- and on Windows each of those is a DIFFERENT MECHANISM rather
// than a variation of the same one: a `.bat`/`.cmd` cannot be spawned without a
// shell, and there is no process group to signal. Getting either wrong is silent,
// so it lives here, with the reasons.
//
// THE THIRD ONE IS A QUESTION ABOUT AN ADDRESS RATHER THAN ABOUT A CHILD (`listenerOn`, and the
// two asks below it, written for `scripts/run.mjs --stop/--restart`): WHO IS HOLDING A PORT.
// It splits the same way -- `ss`/`lsof` on one side, `netstat`/`taskkill` on the other -- and it
// belongs next to the rest, because it is the same question asked of the OS instead of of a
// handle we kept.
import { spawn, spawnSync } from "node:child_process";
import fs from "node:fs";

/// CROSS-PLATFORM: on Windows `clojure` and `npm` are `.bat`/`.cmd`, and Node refuses
/// to spawn those without a shell (the fix for CVE-2024-27980). A shell changes the
/// argument rule in the other direction -- Node quotes NOTHING when one is in play --
/// so `run` quotes for it, below.
export const ON_WINDOWS = process.platform === "win32";

/// An argument that could be read as two words, or as a metacharacter, gets quotes.
/// Only ever called on the Windows path, where a shell is between us and the child.
function quoteForWindows(arg) {
  return /[\s"&|<>^()]/.test(arg) ? `"${arg.replace(/"/g, '\\"')}"` : arg;
}

export function run(command, args, options = {}) {
  return spawn(command, ON_WINDOWS ? args.map(quoteForWindows) : args, {
    ...options,
    shell: ON_WINDOWS,
    // CROSS-PLATFORM, and this is the half with no shared answer: off Windows a
    // process GROUP is what can be stopped as a unit, and `clojure` (a launcher that
    // execs java) and `npm` (a shell that spawns vite) both leave orphans behind when
    // only the direct child is killed. Windows has no process groups to signal, so it
    // gets `taskkill /T` in `stopTree` instead.
    detached: !ON_WINDOWS,
  });
}

/// Start a child this process means to LEAVE BEHIND: detached, its own process, its
/// standard streams on files the CALLER opened.
///
/// NOT `run` WITH `detached: true`, and the difference is measured rather than stylistic.
/// On Windows `run` goes through a shell (a `.cmd` cannot be spawned without one), and a
/// child started that way LOSES A CUSTOM STDIO ENTIRELY: the same fd that receives both
/// streams when `cmd.exe` is spawned as the program receives NOTHING through
/// `shell: true` -- measured on this machine 2026-09-26, node 24, `cmd /d /s /c echo`
/// into an fd opened with `fs.openSync` (direct: both lines land; `shell: true`: the file
/// stays empty). A launcher whose whole readiness check is 'read the line it prints'
/// cannot be built on that, so the command line is quoted HERE and `cmd.exe` is the
/// program.
///
/// AND `detached` IS NOT WHAT IT LOOKS LIKE HERE EITHER. With `detached: true`, a
/// console program's output does not reach the file at all -- `java -version > f 2>&1`
/// through a detached `cmd` leaves `f` at 0 bytes while cmd's own `echo` into the same
/// file lands (same machine, same minute; the JVM is the child that goes quiet, and this
/// launcher exists to READ what its child says). Without `detached` the console child
/// outlives the launcher anyway -- measured: a clojure started this way was still alive
/// after the launcher had exited -- because Windows does not take a child down with its
/// parent. So: no shell, no `detached`, cmd does the redirection, and `logPath` is where
/// the child's two streams go.
///
/// OFF WINDOWS NONE OF THAT IS TRUE: no shell stands in the way, an fd is the whole
/// mechanism, and `detached` is what keeps the child out of the launcher's process group
/// (so it is not signalled when the group is).
export function leaveBehind(command, args, options = {}) {
  const { logPath, ...spawnOptions } = options;
  if (ON_WINDOWS) {
    const line =
      [command, ...args].map(quoteForWindows).join(" ") + ` > "${logPath}" 2>&1`;
    const child = spawn("cmd", ["/d", "/s", "/c", line], {
      ...spawnOptions,
      // The two streams belong to the REDIRECTION above, not to this process: nothing
      // here is inherited, so nothing here can be broken by the launcher leaving.
      stdio: "ignore",
      windowsHide: true,
      // The line above is already quoted; node's own argument quoting would escape
      // the quotes around the log path into nonsense.
      windowsVerbatimArguments: true,
    });
    child.unref();
    return child;
  }
  const fd = fs.openSync(logPath, "a");
  const child = spawn(command, args, {
    ...spawnOptions,
    stdio: ["ignore", fd, fd],
    detached: true,
  });
  child.unref();
  return child;
}

/// Stop a child and everything it started. Idempotent, and quiet when there is
/// nothing to stop -- the callers run on several paths at once (a signal, the normal
/// end, a failure on the way in).
export function stopTree(child) {
  if (child === null || child === undefined) return;
  if (child.exitCode !== null || child.signalCode !== null) return;
  if (ON_WINDOWS) {
    // `/T` is the whole tree -- the same job the process group does off Windows.
    spawn("taskkill", ["/pid", String(child.pid), "/T", "/F"], { stdio: "ignore" });
    return;
  }
  try {
    process.kill(-child.pid, "SIGTERM");
  } catch {
    // No group (already reparented, or never got one): the direct child is the best
    // that is left.
    child.kill("SIGTERM");
  }
}

/// A child's exit code, as a promise. `code` is null when a signal killed it, which
/// callers read as a failure -- 128 + the signal would be the shell's convention and
/// would need the signal number, which is not what any caller here is asking about.
export function exitOf(child) {
  return new Promise((resolve) => child.on("exit", (code) => resolve(code ?? 1)));
}

/// Start a child that is ORPHANED AT BIRTH: not merely out of the launcher's process group,
/// but nobody's child by the time the caller gets its answer.
///
/// WHY THAT IS THE POINT, in the harness's own words: when that JVM exits it walks
/// `.descendants` -- THE PPID TREE AT EVERY DEPTH -- and stops what it finds
/// (`harness.infra.shell/reap!`). `detached` alone (see `leaveBehind`) does not hide a child
/// from that walk; being reparented does. `setsid -f` forks and its intermediate exits at once,
/// so the real process is handed to init BEFORE THIS FUNCTION RETURNS, which is what a caller
/// needs when the thing it is about to kill is its own ancestor (a harness restarting itself).
///
/// WINDOWS HAS NO FORK, so the orphan-at-birth half is POSIX-only: there the child keeps this
/// launcher as its (dead) parent, which is the mechanism `leaveBehind` above already had
/// measured. Its pid is the one `spawn` answered with; on POSIX that pid is `setsid`'s, which
/// is gone by then -- a caller that wants the real pid asks `listenerOn` once the port answers.
export function leaveBehindOrphan(command, args, options = {}) {
  if (ON_WINDOWS) return leaveBehind(command, args, options);
  const { logPath, pidPath, ...spawnOptions } = options;
  const fd = fs.openSync(logPath, "a");
  // WHO THE PROCESS IS, WRITTEN BY THE PROCESS ITSELF: `setsid -f` replaces the intermediate,
  // so the pid `spawn` answered with is gone before this returns -- yet 'is it still up' and
  // 'what do I kill' both need the real number. `$$` before an `exec` IS the pid the program
  // will have, so one line of shell is the whole mechanism (no pid file is written when the
  // caller did not ask for one).
  const line =
    pidPath === null || pidPath === undefined
      ? [command, ...args]
      : ["bash", "-c", 'echo $$ > "$1"; shift; exec "$@"', "_", pidPath, command, ...args];
  const child = spawn("setsid", ["-f", ...line], {
    ...spawnOptions,
    // The two streams go to the CALLER's file, exactly as in `leaveBehind`: what a launcher
    // reads to know the child came up is on those streams.
    stdio: ["ignore", fd, fd],
    detached: true,
  });
  child.unref();
  return child;
}

/// WHO IS LISTENING ON PORT, as `{ pids, command }`, or null when the address is free.
///
/// THE ADDRESS IS THE IDENTITY a restart needs: a port is what a browser was pointed at and
/// what a README names, while a pid written down yesterday may already belong to something
/// else. So 'the backend' is 'whoever is holding :PORT', asked now -- there is no state file
/// to go stale.
///
/// `pids` IS A LIST because an address can be held by more than one process (a forked worker,
/// SO_REUSEPORT); all of them have to go for the port to come free.
export function listenerOn(port) {
  if (ON_WINDOWS) {
    // `netstat -ano`:  Proto  Local Address  Foreign Address  State  PID. The STATE WORD is
    // localized on some installs, so no line is matched on it -- the shape is enough (a TCP
    // line whose local address ends in the port, with a pid-shaped last column).
    const netstat = spawnSync("netstat", ["-ano"], { encoding: "utf8" });
    if (netstat.error) throw new Error(`问不出 :${port} 上是谁：${netstat.error.message}`);
    const pids = [];
    for (const line of netstat.stdout.split("\n")) {
      const columns = line.trim().split(/\s+/);
      if (columns.length < 5 || !/^TCP$/i.test(columns[0])) continue;
      if (!columns[1].endsWith(`:${port}`)) continue;
      const pid = Number(columns[columns.length - 1]);
      if (Number.isInteger(pid) && pid > 0 && !pids.includes(pid)) pids.push(pid);
    }
    return pids.length === 0 ? null : { pids, command: null };
  }
  // POSIX: `ss` (-p needs no privilege for a socket this user owns, which is the case here),
  // with `lsof` as the fallback for a machine that has no iproute2.
  const ss = spawnSync("ss", ["-ltnpH", `sport = :${port}`], { encoding: "utf8" });
  if (!ss.error && ss.status === 0) return fromSs(ss.stdout);
  const lsof = spawnSync("lsof", ["-ti", `tcp:${port}`, "-sTCP:LISTEN"], { encoding: "utf8" });
  if (lsof.error) throw new Error(`这台机器上既没有 ss 也没有 lsof，问不出 :${port} 上是谁`);
  const pids = lsof.stdout
    .split("\n")
    .map((line) => Number(line.trim()))
    .filter((pid) => Number.isInteger(pid) && pid > 0);
  return pids.length === 0 ? null : { pids, command: null };
}

function fromSs(text) {
  const pids = [...text.matchAll(/pid=(\d+)/g)].map((match) => Number(match[1]));
  if (pids.length === 0) return null;
  const command = text.match(/users:\(\("([^"]+)"/);
  return { pids: [...new Set(pids)], command: command === null ? null : command[1] };
}

/// ASK whoever holds PORT to stop. SIGTERM on POSIX, which is the signal the harness answers
/// with its own exit hook (it reaps what it started and closes its stores) -- so this is the
/// honest 'please stop', not a kill. Windows has no such signal for a console program: the
/// mechanism this repo measured is `taskkill /T /F` (`stopTree` above), so that is the ask.
///
/// Answers the pids it asked, for the caller's log line.
export function askListenerToStop(port) {
  const found = listenerOn(port);
  if (found === null) return [];
  if (ON_WINDOWS) {
    for (const pid of found.pids) {
      spawn("taskkill", ["/pid", String(pid), "/T", "/F"], { stdio: "ignore" });
    }
    return found.pids;
  }
  for (const pid of found.pids) {
    try {
      process.kill(pid, "SIGTERM");
    } catch {
      // Gone between the look and the ask: that is the answer we wanted anyway.
    }
  }
  return found.pids;
}

/// THE SECOND ASK, for a process that did not take the first. SIGKILL on POSIX: nothing it is
/// holding is worth a third round. Windows gets the same `taskkill /T /F` again -- there was
/// never a gentle first ask to escalate from.
export function insistListenerStop(port) {
  const found = listenerOn(port);
  if (found === null) return [];
  if (ON_WINDOWS) {
    for (const pid of found.pids) {
      spawn("taskkill", ["/pid", String(pid), "/T", "/F"], { stdio: "ignore" });
    }
    return found.pids;
  }
  for (const pid of found.pids) {
    try {
      process.kill(pid, "SIGKILL");
    } catch {
      // Same as above: already gone.
    }
  }
  return found.pids;
}
