#!/usr/bin/env node
//
// Start the harness on a port nobody is using, and the UI in front of it.
//
//   node scripts/dev.mjs                     harness (real, your own ~/.clj-harness) + UI
//   node scripts/dev.mjs --port 8080         the address the client used to hardcode
//   node scripts/dev.mjs --scripted          the scripted double instead: no api-key, no
//                                            model, a temp home, a provider that replays
//   node scripts/dev.mjs --scripted my.json  ...with your own turns
//   node scripts/dev.mjs --ui-port 5199      somewhere other than 5173
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
//
// LOOKING AT THE UI IS A VERIFICATION STEP IN THIS REPO -- the one layer a suite cannot
// reach -- so --scripted is that step's entry point, and the three rules the suites keep
// are properties of THIS invocation rather than a checklist somebody assembles by hand:
//
//   * THE HOMES ARE TEMP, SIBLINGS, AND GONE ON THE WAY OUT. The config root and the OS
//     home are made under one temp directory -- never nested, see AGENTS.md -- because a
//     session's jsonl is written under the root and ~/AGENTS.md plus ~/.agents/skills are
//     read from the home, and neither may be the developer's. Both are removed when this
//     stops, Ctrl-C included.
//   * NO PORT IS EVER WRITTEN DOWN. The backend is asked for port 0 and the port IT
//     announces becomes vite's proxy target, so no source file learns a number and there
//     is no 8080 to clear first.
//   * THE TEMP PATHS ARE PRINTED. A run's record lands under the root AS IT STREAMS and
//     the directory is gone once this exits, so the banner is the only window in which a
//     walkthrough can read the two sessions it just drove.
//
// The announced port is read from stdout, which is where AGENTS.md's "do not read a
// child's answer from stdout" does not bite: this is a handshake the e2e server prints
// on purpose, not an answer it computes, and the match runs over the accumulated stream
// -- so a JDK warning arriving first delays it by a chunk instead of losing it.
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";

import { exitOf, run, stopTree } from "./proc.mjs";

// THE REPOSITORY IS THIS FILE'S PARENT, and that is what makes the script movable: it is
// invoked from wherever a reader is standing (`node scripts/dev.mjs`, at the root or
// anywhere else) and every path below is derived from here rather than from the cwd.
const HERE = path.dirname(fileURLToPath(import.meta.url));
const ROOT = path.resolve(HERE, "..");
const UI_DIR = path.join(ROOT, "ui");

// ---------------------------------------------------------------- arguments

const USAGE = `Start the harness on a port nobody is using, and the UI in front of it.

  node scripts/dev.mjs                     harness (real, your own ~/.clj-harness) + UI
  node scripts/dev.mjs --port 8080         the address the client used to hardcode
  node scripts/dev.mjs --scripted          the scripted double instead: no api-key, no
                                           model, a temp home, a provider that replays
  node scripts/dev.mjs --scripted my.json  ...with your own turns
  node scripts/dev.mjs --ui-port 5199      somewhere other than 5173

The backend is started on port 0 (the OS picks) and the port it announces is handed
to the dev server as HARNESS_BACKEND_URL, which ui/vite.config.js uses as its proxy
target. Ctrl-C stops both, and the two temp homes the scripted mode made are deleted
with it -- their paths are printed when it starts, which is the only time they exist.`;

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

// `run` / `stopTree` / `exitOf` are in ./proc.mjs: the two platform branches they
// carry are knowledge, and a second copy of either would be a second thing to keep
// true (see that file).

let backend = null;
let ui = null;
let tmp = null;
/// The pair --scripted made, kept so the banner can name them: a session's record is
/// written under the config root while the run is in flight, and this directory does
/// not outlive the script, so the paths are worth saying out loud once.
let homes = null;

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
// SIGHUP IS IN THE LIST BECAUSE A CLOSED TERMINAL IS NOT Ctrl-C: with no handler Node
// takes the signal's default action and dies without running the exit handler above,
// which is how a temp pair gets left behind by the tidiest way to stop.
for (const [signal, code] of [["SIGINT", 130], ["SIGTERM", 143], ["SIGHUP", 129]]) {
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
  homes = { root: home, userHome };
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
console.log(`dev.mjs: UI on http://localhost:${uiPort} (Ctrl-C stops both)`);
if (homes !== null) {
  console.log(
    `dev.mjs: temp config root ${homes.root}` +
      " (a session's record is projects/<workspace>/<thread>.jsonl under it)",
  );
  console.log(`dev.mjs: temp OS home   ${homes.userHome}`);
  console.log("dev.mjs: siblings, deleted when this stops -- read the jsonl while it runs");
}
console.log(`dev.mjs: the log is ${logPath}`);

// ------------------------------------------------------------------- the UI

if (!fs.existsSync(path.join(UI_DIR, "node_modules"))) {
  console.log("dev.mjs: no ui/node_modules -- running npm install first");
  const install = run("npm", ["install"], { cwd: UI_DIR, stdio: "inherit" });
  const installed = await exitOf(install);
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

const uiExit = await exitOf(ui);
cleanup();
process.exit(uiExit);
