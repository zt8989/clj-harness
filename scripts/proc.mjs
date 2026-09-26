// The two things a launcher has to do differently per platform, in one place.
//
// `dev.mjs` starts long-running children (a backend, a browser) and has to be able to
// stop them again -- and on Windows each of those is a DIFFERENT MECHANISM rather
// than a variation of the same one: a `.bat`/`.cmd` cannot be spawned without a
// shell, and there is no process group to signal. Getting either wrong is silent,
// so it lives here, with the reasons.
import { spawn } from "node:child_process";
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
