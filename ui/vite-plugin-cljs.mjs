// The bridge between shadow-cljs and Vite, in one file.
//
// Why not `shadow-cljs-vite-plugin`? Because it cannot run on Windows, in two
// independent ways, neither configurable away:
//
//   1. It spawns `spawn("shadow-cljs", [...])` with no shell. The CLI on Windows is
//      `node_modules/.bin/shadow-cljs.cmd`; libuv resolves no extension on its own,
//      and Node refuses to spawn a `.cmd` without `shell: true` (EINVAL). So both
//      `watch` and `release` fail with ENOENT -- measured, not assumed.
//   2. It embeds a raw `path.resolve()` result into a JS string literal:
//      `export * from "C:\...\cljs-out\main.js"`. The backslashes are read as escape
//      sequences and eaten, so the specifier arrives mangled.
//
// What is left after dropping its HMR orchestration is small enough to own:
//
//   1. keep `shadow-cljs watch` alive in dev, run `shadow-cljs release` before a build
//   2. hand the compiled build to Vite as `virtual:shadow-cljs/<buildId>`, waiting for
//      it when no build has landed yet
//
// One trap, learned the hard way: do NOT add `cljs-out` to `server.watch.ignored` to
// keep Vite from reloading over shadow-cljs's own writes. Vite's module graph only
// drops a transform when its watcher reports the file changed, so ignoring the output
// dir makes the first transform of every file permanent -- the browser keeps getting
// the build from whenever the page first loaded, and edits appear to do nothing.
import { spawn } from "node:child_process";
import { existsSync } from "node:fs";
import { resolve } from "node:path";

const isWindows = process.platform === "win32";
const VIRTUAL_PREFIX = "virtual:shadow-cljs/";

/// The CLI is a `.cmd` shim on Windows, which only a shell can run.
function spawnCljs(args, cwd) {
  return spawn("shadow-cljs", args, {
    cwd,
    shell: isWindows,
    stdio: ["ignore", "inherit", "inherit"],
    // A process group, so the whole JVM goes down with the watcher on exit.
    detached: !isWindows,
  });
}

function killTree(proc) {
  if (!proc || proc.exitCode !== null || proc.signalCode !== null) return;
  if (isWindows) {
    // `proc` is the shell; /T takes the JVM it started with it.
    spawn("taskkill", ["/PID", String(proc.pid), "/T", "/F"], { stdio: "ignore" });
  } else {
    try {
      process.kill(-proc.pid, "SIGTERM");
    } catch {
      /* already gone */
    }
  }
}

function runOnce(args, cwd) {
  return new Promise((pass, fail) => {
    const proc = spawnCljs(args, cwd);
    proc.on("error", fail);
    proc.on("close", (code) =>
      code === 0
        ? pass()
        : fail(new Error(`shadow-cljs ${args.join(" ")} exited ${code}`)),
    );
  });
}

async function waitFor(file, timeoutMs = 180_000) {
  const deadline = Date.now() + timeoutMs;
  while (!existsSync(file)) {
    if (Date.now() > deadline) throw new Error(`shadow-cljs never produced ${file}`);
    await new Promise((r) => setTimeout(r, 50));
  }
  return file;
}

/// `path.resolve` yields backslashes on Windows, which a JS string literal would eat.
const toSpecifier = (file) => file.split("\\").join("/");

export function cljs({ buildId = "app", module = "main", outputDir = "cljs-out" } = {}) {
  let root = process.cwd();
  const entryFile = () => resolve(root, outputDir, `${module}.js`);
  const captureRoot = (config) => {
    root = config.root;
  };

  // Dev. `watch` stays up for the life of the server, and Vite's own startup holds
  // until a build has landed -- otherwise the first page load races a ~10s compile
  // and 404s on the entry.
  const watchPlugin = {
    name: "cljs:watch",
    apply: "serve",
    configResolved: captureRoot,
    async configureServer(server) {
      console.log("[cljs] shadow-cljs watch");
      const watcher = spawnCljs(["watch", buildId], root);
      await waitFor(entryFile());
      const stop = () => killTree(watcher);
      server.httpServer?.once("close", stop);
      process.once("exit", stop);
    },
  };

  // Build. One release per build, however many times buildStart fires.
  let release = null;
  const releasePlugin = {
    name: "cljs:release",
    apply: "build",
    configResolved: captureRoot,
    async buildStart() {
      release ??= runOnce(["release", buildId], root).catch((err) => {
        release = null;
        throw err;
      });
      console.log("[cljs] shadow-cljs release");
      await release;
    },
  };

  const virtualPlugin = {
    name: "cljs:virtual",
    configResolved: captureRoot,

    resolveId: (id) => (id.startsWith(VIRTUAL_PREFIX) ? `\0${id}` : undefined),

    async load(id) {
      if (id !== `\0${VIRTUAL_PREFIX}${buildId}`) return undefined;
      return `export * from "${toSpecifier(await waitFor(entryFile()))}";\n`;
    },
  };

  return [watchPlugin, releasePlugin, virtualPlugin];
}
