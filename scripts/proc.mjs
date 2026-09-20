// The two things a launcher has to do differently per platform, in one place.
//
// `dev.mjs` starts long-running children (a backend, a browser) and has to be able to
// stop them again -- and on Windows each of those is a DIFFERENT MECHANISM rather
// than a variation of the same one: a `.bat`/`.cmd` cannot be spawned without a
// shell, and there is no process group to signal. Getting either wrong is silent,
// so it lives here, with the reasons.
import { spawn } from "node:child_process";

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
