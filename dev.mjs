#!/usr/bin/env node
//
// Start the harness on a port nobody is using, and the UI in front of it.
//
//   node dev.mjs                     harness (real, your own ~/.clj-harness) + UI
//   node dev.mjs --port 8080         the address the client used to hardcode
//   node dev.mjs --scripted          the scripted double instead: no api-key, no
//                                    model, a temp home, a provider that replays
//   node dev.mjs --scripted my.json  ...with your own turns
//   node dev.mjs --ui-port 5199      somewhere other than 5173
//
// WHY THIS EXISTS. `npm run dev` on its own expects a harness on 8080, and 8080 is
// the one port a second checkout, a test run, or yesterday's forgotten session is
// most likely to be holding. So the backend is started on a port the OS picks, that
// port becomes the dev server's proxy target (`HARNESS_BACKEND_URL`, read by
// ui/vite.config.js), and the browser keeps talking to its own origin -- no CORS
// allowance to keep in step, and nothing in the source learns a port.
//
// NODE RATHER THAN A SHELL SCRIPT, so that one file works on all three platforms.
// The two Bash spellings this replaces were both POSIX-only: process groups for
// stopping the tree, and a signal trap that only fires between foreground commands.
// Neither has a portable Bash answer, and Windows has neither. Everything below is
// Node core and three `process.platform` branches, each marked CROSS-PLATFORM.
//
// THE PORT IS READ BACK, NOT GUESSED. The backend is asked for port 0 and the port
// it actually got is what it prints; probing for a free port first and then handing
// the number over would be a race with every other process on the machine, and would
// ask the backend to trust a port it did not choose. So the wait below is for that
// line, not for a sleep.
//
// THE REAL MODE USES YOUR OWN ~/.clj-harness -- that is what it is for: your harness,
// your config, your provider, your key. THE SCRIPTED MODE MUST NOT: it gets a temp
// config root AND a temp OS home (the two are siblings, never nested -- see AGENTS.md
// on the homes), both deleted on exit, exactly as the suites do it. A scripted turn is
//
//   {"turns": [{"content": "..."},
//              {"content": "", "tool-calls": [{"id": "c1", "name": "read",
//                                             "arguments": {"path": "deps.edn"}}]}]}
//
// consumed one per model call -- one tool round costs two.
import { spawn } from "node:child_process";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";

const ROOT = path.dirname(fileURLToPath(import.meta.url));
const UI_DIR = path.join(ROOT, "ui");

/// CROSS-PLATFORM: on Windows `clojure` and `npm` are `.bat`/`.cmd`, and Node
/// refuses to spawn those without a shell (the fix for CVE-2024-27980). A shell
/// changes the argument rule in the other direction -- Node quotes NOTHING when
/// one is in play -- so `run` quotes for it, below.
const ON_WINDOWS = process.platform === "win32";

// ---------------------------------------------------------------- arguments

const USAGE = `Start the harness on a port nobody is using, and the UI in front of it.

  node dev.mjs                     harness (real, your own ~/.clj-harness) + UI
  node dev.mjs --port 8080         the address the client used to hardcode
  node dev.mjs --scripted          the scripted double instead: no api-key, no
                                   model, a temp home, a provider that replays
  node dev.mjs --scripted my.json  ...with your own turns
  node dev.mjs --ui-port 5199      somewhere other than 5173

The backend is started on port 0 (the OS picks) and the port it announces is handed
to the dev server as HARNESS_BACKEND_URL, which ui/vite.config.js uses as its proxy
target. Ctrl-C stops both, and the temp home the scripted mode made is deleted with
it.`;

const argv = process.argv.slice(2);
let port = 0;
let uiPort = 5173;
let scripted = false;
let scriptFile = "";

for (let i = 0; i < argv.length; i += 1) {
  const arg = argv[i];
  if (arg === "--port") {
    port = Number(argv[++i]);
  } else if (arg === "--ui-port") {
    uiPort = Number(argv[++i]);
  } else if (arg === "--scripted") {
    scripted = true;
    // An OPTIONAL file: what follows is the script only when it is not another
    // option.
    if (argv[i + 1] !== undefined && !argv[i + 1].startsWith("--")) scriptFile = argv[++i];
  } else if (arg === "-h" || arg === "--help") {
    console.log(USAGE);
    process.exit(0);
  } else {
    console.error(`dev.mjs: unknown argument: ${arg}\n`);
    console.error(USAGE);
    process.exit(2);
  }
}
for (const [name, value] of [["--port", port], ["--ui-port", uiPort]]) {
  if (!Number.isInteger(value) || value < 0 || value > 65535) {
    console.error(`dev.mjs: ${name} wants a port number, got ${value}`);
    process.exit(2);
  }
}

// ------------------------------------------------------------- the children

/// CROSS-PLATFORM: a shell on Windows is what makes `.bat`/`.cmd` runnable at all,
/// and it is also what takes the quoting away -- so an argument that could be read
/// as two words, or as a metacharacter, is quoted here instead.
function quoteForWindows(arg) {
  return /[\s"&|<>^()]/.test(arg) ? `"${arg.replace(/"/g, '\\"')}"` : arg;
}

let backend = null;
let ui = null;
let tmp = null;

function run(command, args, options = {}) {
  return spawn(command, ON_WINDOWS ? args.map(quoteForWindows) : args, {
    ...options,
    shell: ON_WINDOWS,
    // CROSS-PLATFORM, and this is the half with no shared answer: off Windows a
    // process GROUP is what can be stopped as a unit, and `clojure` (a launcher
    // that execs java) and `npm` (a shell that spawns vite) both leave orphans
    // behind when only the direct child is killed. Windows has no process groups
    // to signal, so it gets `taskkill /T` in `stopTree` instead.
    detached: !ON_WINDOWS,
  });
}

/// Stop a child and everything it started. Idempotent, and quiet when there is
/// nothing to stop -- the callers below run on several paths at once.
function stopTree(child) {
  if (child === null || child.exitCode !== null || child.signalCode !== null) return;
  if (ON_WINDOWS) {
    // `/T` is the whole tree -- the same job the process group does off Windows.
    spawn("taskkill", ["/pid", String(child.pid), "/T", "/F"], { stdio: "ignore" });
    return;
  }
  try {
    process.kill(-child.pid, "SIGTERM");
  } catch {
    // No group (already reparented, or never got one): the direct child is the
    // best that is left.
    child.kill("SIGTERM");
  }
}

let cleaned = false;
function cleanup() {
  if (cleaned) return;
  cleaned = true;
  stopTree(ui);
  stopTree(backend);
  if (tmp !== null) fs.rmSync(tmp, { recursive: true, force: true });
}

// A SIGNAL HANDLER HERE IS NOT A TRAP THAT FIRES BETWEEN COMMANDS. The Bash
// version had to background its children and `wait` on them, because Bash defers a
// trapped signal until the foreground command finishes -- so `npm run dev` as the
// last line meant Ctrl-C reaching only the script did nothing at all. Node runs
// handlers on the event loop whatever the children are doing, so there is nothing
// to arrange; the handlers below are all of it.
process.on("exit", cleanup);
for (const [signal, code] of [["SIGINT", 130], ["SIGTERM", 143]]) {
  process.on(signal, () => {
    cleanup();
    process.exit(code);
  });
}

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

// --------------------------------------------------------------- the backend

tmp = fs.mkdtempSync(path.join(os.tmpdir(), "clj-harness-dev-"));
const logPath = path.join(tmp, "backend.log");

/// The line each backend announces itself on, and the port it names: the e2e
/// server's is machine-readable on purpose, `start!`'s is its own banner with the
/// BOUND port on it (which is the whole reason `--port 0` is usable at all).
const READY = scripted
  ? /PRINT-READY \{:port (\d+)\}/
  : /harness listening on http:\/\/localhost:(\d+)/;

let backendArgv;
let backendEnv = { ...process.env };
if (scripted) {
  const home = path.join(tmp, "home");
  const userHome = path.join(tmp, "user-home");
  fs.mkdirSync(home);
  fs.mkdirSync(userHome);
  fs.writeFileSync(
    path.join(home, "config.edn"),
    '{:default {:protocol :fake :base-url "http://offline.invalid/v1" :model "seeded"}}\n',
  );
  if (scriptFile === "") {
    scriptFile = path.join(tmp, "script.json");
    fs.writeFileSync(scriptFile, '{"turns": [{"content": "（这是脚本厂商的一条回答。）"}]}\n');
  }
  backendArgv = [
    "clojure", "-M:dev", "-m", "harness.e2e-server",
    "--script-file", scriptFile,
    "--port", String(port),
    "--user-home", userHome,
  ];
  backendEnv.CLJ_HARNESS_HOME = home;
} else {
  backendArgv = ["clojure", "-M:run", "--port", String(port)];
}

const log = fs.createWriteStream(logPath);
backend = run(backendArgv[0], backendArgv.slice(1), {
  cwd: ROOT,
  env: backendEnv,
  stdio: ["ignore", "pipe", "pipe"],
});
backend.stdout.on("data", (chunk) => log.write(chunk));
backend.stderr.on("data", (chunk) => log.write(chunk));

let boundPort = "";
let seen = "";
let settled = false;
const announced = new Promise((resolve, reject) => {
  const done = (fn, value) => {
    if (settled) return;
    settled = true;
    clearTimeout(timer);
    fn(value);
  };
  const timer = setTimeout(
    () => done(reject, new Error("the backend never announced a port within 60s")),
    60_000,
  );
  backend.stdout.on("data", (chunk) => {
    if (settled) return;
    // Accumulated rather than matched per chunk: the line can arrive split across
    // two of them, and a regex that only ever sees half of it never fires.
    seen += chunk.toString("utf8");
    const found = READY.exec(seen);
    if (found) done(resolve, found[1]);
  });
  backend.on("exit", (code) =>
    done(reject, new Error(`the backend stopped before it listened (exit ${code})`)),
  );
  // A COMMAND THAT IS NOT THERE IS AN EVENT, NOT A REJECTION: without this it is
  // an unhandled 'error' and the process dies with a stack instead of the one
  // sentence that says which binary is missing (usually `clojure`).
  backend.on("error", (failure) =>
    done(reject, new Error(`could not start ${backendArgv[0]}: ${failure.message}`)),
  );
});

try {
  boundPort = await announced;
} catch (failure) {
  // The backend's own output, when there is any: a bind that failed, a config it
  // would not read. A command that was never there printed nothing, and saying
  // "its output: (nothing)" about it would read as though it had.
  const output = fs.readFileSync(logPath, "utf8").trim();
  console.error(`dev.mjs: ${failure.message}${output === "" ? "" : ". Its output:"}`);
  if (output !== "") console.error(output);
  process.exit(1);
}

console.log(
  `dev.mjs: harness on http://127.0.0.1:${boundPort}` +
    (port === 0 ? " (a port the OS picked)" : ""),
);
console.log(`dev.mjs: the log is ${logPath}`);
console.log(`dev.mjs: UI on http://localhost:${uiPort} (Ctrl-C stops both)`);

// ------------------------------------------------------------------- the UI

if (!fs.existsSync(path.join(UI_DIR, "node_modules"))) {
  console.log("dev.mjs: no ui/node_modules -- running npm install first");
  const install = run("npm", ["install"], { cwd: UI_DIR, stdio: "inherit" });
  const installed = await new Promise((resolve) => install.on("exit", (code) => resolve(code)));
  if (installed !== 0) {
    console.error("dev.mjs: npm install failed");
    process.exit(1);
  }
}

ui = run("npm", ["run", "dev", "--", "--port", String(uiPort)], {
  cwd: UI_DIR,
  stdio: "inherit",
  env: { ...process.env, HARNESS_BACKEND_URL: `http://127.0.0.1:${boundPort}` },
});

const uiExit = await new Promise((resolve) => ui.on("exit", (code) => resolve(code)));
cleanup();
process.exit(uiExit ?? 0);
