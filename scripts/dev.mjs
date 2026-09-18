#!/usr/bin/env node
//
// Start the harness on a port nobody is using, and the UI in front of it.
//
//   node scripts/dev.mjs                     harness (real, your own ~/.clj-harness) + UI
//   node scripts/dev.mjs --tmux              the harness's log in this pane, the dev server
//                                            in a new one to the right (run inside tmux)
//   node scripts/dev.mjs --port 8080         the address the client used to hardcode
//   node scripts/dev.mjs --scripted          the scripted double instead: no api-key, no
//                                            model, a temp home, a provider that replays
//   node scripts/dev.mjs --scripted my.json  ...with your own turns
//   node scripts/dev.mjs --ui-port 5199      somewhere other than 5173
//
// WHY THIS EXISTS. `npm run dev` on its own expects a harness on 8080, and 8080 is
// the one port a second checkout, a test run, or yesterday's forgotten session is
// most likely to be holding. So the backend is started on a port the OS picks and
// that port is handed to the dev server, which passes it on to the page as the
// address to call; nothing in the source learns a port.
//
// -------------------------------------------------- and why not vite's own proxy
//
// THE CLIENT TALKS TO THE HARNESS DIRECTLY, ACROSS ORIGINS, and that is a
// measurement rather than a preference. Vite's dev server can forward `/api` to
// the backend (see ui/vite.config.js), and for a while that is what the page did.
// What ended it is a defect in the FORWARDER, measured 2026-09-18 on this machine
// (vite 8.3.0, its bundled http-proxy-3 1.23.3): a proxied SSE response
// intermittently loses its last chunk -- every frame arrives, the terminating
// chunk never does -- so the browser's fetch never settles. Sixteen runs of one
// scripted tool-calling turn through the proxy lost it three times; the same
// sixteen straight to the harness lost it none, and so did twenty-eight through a
// hand-written Node proxy, with and without a keep-alive agent.
//
// WHAT THAT COST THE PAGE: the run's request never settles, so the runtime stays
// "running" forever -- the composer keeps its Cancel button and the sidebar row
// keeps its spinner -- while the conversation underneath is plainly finished.
//
// SO ONE ADDRESS IS PASSED, AND IT IS THE PAGE'S. The UI is handed
// VITE_AGENT_URL, which `ui/src/lib/threads.ts` already reads as "talk to a
// harness at this absolute address" -- the mode the harness's CORS allowance
// exists for. NOTHING NEEDS TO BE SAID TO THE HARNESS IN RETURN: it answers a page
// served from this machine whatever port it is on (see
// `harness.edge.http/localhost-page?`), so the port this script handed vite is
// vite's business and nobody else's. HARNESS_BACKEND_URL goes to the UI as well,
// so vite's proxy rule still points somewhere correct for anyone reaching for
// `npm run dev` by hand; the dev loop just no longer needs it.
//
// --TMUX IS THE SAME TWO PROCESSES, ARRANGED SO THAT NEITHER ONE BURIES THE OTHER.
// vite's output (its startup banner, every HMR round) and the harness's log lines are
// two continuous streams, and one terminal interleaves them: reading either means
// scrolling past the other. In tmux each gets a pane -- the harness in THIS one, in
// front of you, the dev server in a new one to the right. Nothing about the handshake
// changes (the port is still read back off the backend's banner and still reaches vite
// through HARNESS_BACKEND_URL), and this script still owns both: the pane is tmux's,
// so what ends the pair is the backend child in this pane, and Ctrl-C here takes the
// pane down on the way out.
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
  node scripts/dev.mjs --tmux              + the UI in a pane to the right, the harness's
                                           own log left in this one (run inside tmux)
  node scripts/dev.mjs --port 8080         the address the client used to hardcode
  node scripts/dev.mjs --scripted          the scripted double instead: no api-key, no
                                           model, a temp home, a provider that replays
  node scripts/dev.mjs --scripted my.json  ...with your own turns
  node scripts/dev.mjs --ui-port 5199      somewhere other than 5173

The backend is started on port 0 (the OS picks); the port it announces becomes both the
address the page is told to call (VITE_AGENT_URL) and vite's proxy target
(HARNESS_BACKEND_URL). The UI's own port is vite's business alone: the harness answers a
page served from this machine whatever port it is on, so nothing is reported back to it.
Ctrl-C stops both, and the two temp homes the scripted mode made are deleted with it --
their paths are printed when it starts, which is the only time they exist. --tmux splits
the pane it was run in, so it wants a shell that is inside tmux.`;

const argv = process.argv.slice(2);
let port = 0;
let uiPort = 5173;
let scripted = false;
let scriptFile = "";
let tmux = false;

for (let i = 0; i < argv.length; i += 1) {
  const arg = argv[i];
  if (arg === "--port") {
    port = Number(argv[++i]);
  } else if (arg === "--ui-port") {
    uiPort = Number(argv[++i]);
  } else if (arg === "--tmux") {
    tmux = true;
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

// --TMUX ASKS THE WINDOW IT IS IN FOR A PANE, so there has to be a window: $TMUX is
// what a shell inside tmux is handed, and its absence is not a missing binary to go
// looking for but a different shape of terminal. (No Windows branch: tmux is not a
// program Windows has.)
if (tmux && !process.env.TMUX) {
  console.error(
    "dev.mjs: --tmux splits the window it was run in, so it wants a shell that is " +
      "inside tmux (try `tmux new -s harness`) -- there is no pane to put the UI in " +
      "otherwise. Without it, `node scripts/dev.mjs` puts both in this terminal.",
  );
  process.exit(2);
}

// ------------------------------------------------------------- the children

// `run` / `stopTree` / `exitOf` are in ./proc.mjs: the two platform branches they
// carry are knowledge, and a second copy of either would be a second thing to keep
// true (see that file).

let backend = null;
let ui = null;
let tmp = null;
/// The tmux pane the UI runs in under --tmux, and null in every other mode: there,
/// the dev server is a child this process spawned, and here it is a pane somebody
/// else's server owns -- which is why it is taken down by name (below) rather than
/// by signal.
let uiPane = null;
/// The pair --scripted made, kept so the banner can name them: a session's record is
/// written under the config root while the run is in flight, and this directory does
/// not outlive the script, so the paths are worth saying out loud once.
let homes = null;

/// Take the UI's pane down with us. Not awaited, and reachable from the `exit`
/// handler (where there is nothing left to wait on): the command outlives this
/// process either way, and it has no answer worth reading.
function closeUiPane() {
  if (uiPane === null) return;
  const pane = uiPane;
  uiPane = null;
  run("tmux", ["kill-pane", "-t", pane], { stdio: "ignore" });
}

let cleaned = false;
function cleanup() {
  if (cleaned) return;
  cleaned = true;
  closeUiPane();
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

// --------------------------------------------------------------- the tmux pane

/// A command's stdout as one line, for `tmux` -- whose answer to the one question
/// asked here is one line by construction (`split-window -P -F '#{pane_id}'`). What
/// tmux prints on the way to a non-zero exit becomes the reason the rejection
/// carries, because that is where it says WHY ("no space for new pane", "can't find
/// session").
function capture(command, args) {
  return new Promise((resolve, reject) => {
    const child = run(command, args, { stdio: ["ignore", "pipe", "pipe"] });
    let out = "";
    let err = "";
    child.stdout.on("data", (chunk) => {
      out += chunk.toString("utf8");
    });
    child.stderr.on("data", (chunk) => {
      err += chunk.toString("utf8");
    });
    child.on("error", (failure) =>
      reject(new Error(`could not run ${command}: ${failure.message}`)),
    );
    child.on("exit", (code) => {
      if (code === 0) return resolve(out.trim());
      const said = err.trim();
      reject(new Error(`${command} exited ${code}${said === "" ? "" : `: ${said}`}`));
    });
  });
}

/// WHAT THE DEV SERVER IS TOLD, in one place because two paths start it (this
/// process, and a tmux pane). `VITE_AGENT_URL` is the address the PAGE reads to
/// talk to the harness directly; `HARNESS_BACKEND_URL` is the one vite's own proxy
/// rule reads. Both name the same socket and both are said on purpose -- see the
/// header for why the dev loop stopped going through the proxy, and why the rule
/// is still pointed somewhere correct.
function uiEnv(backendPort) {
  return {
    VITE_AGENT_URL: `http://127.0.0.1:${backendPort}`,
    HARNESS_BACKEND_URL: `http://127.0.0.1:${backendPort}`,
  };
}

/// The UI in a pane to the RIGHT of this one, and the pane's id back so `cleanup` can
/// take it down again.
///
/// -d IS WHAT KEEPS FOCUS HERE. A split makes the new pane current by default, and
/// this pane is where the harness's log is arriving and where Ctrl-C ends the pair --
/// so the pane you asked to be in front of you has to stay in front of you.
///
/// -e PATH RATHER THAN THE SERVER'S OWN: tmux hands a pane the environment of the
/// SERVER, which was started by some earlier shell, while `npm` was found by way of
/// the PATH this process was handed. (Passed only when there is one: `-e PATH=` would
/// be an empty path, which is worse than whatever the server has.)
///
/// -k KEEPS THE PANE UP WHEN VITE EXITS, and that is for the failure rather than the
/// success: `strictPort` is on, so a dev server that could not take the port it was
/// given FAILS rather than quietly moving -- and the message it prints when it does
/// is the one there is no second copy of, since a pane that closes on the way out
/// takes it with it.
async function openUiPane(backendPort, uiPort) {
  // NAMED ONE BY ONE, not the whole environment: a pane takes these as argv, and
  // handing tmux every variable this process happens to hold is both an argv
  // length this script does not control and a copy of things a pane has no
  // business reading.
  const environment = [];
  if (process.env.PATH) environment.push(`PATH=${process.env.PATH}`);
  for (const [name, value] of Object.entries(uiEnv(backendPort))) {
    environment.push(`${name}=${value}`);
  }
  const pane = await capture("tmux", [
    "split-window", "-h", "-d", "-k", "-c", UI_DIR,
    ...environment.flatMap((pair) => ["-e", pair]),
    "-P", "-F", "#{pane_id}",
    `npm run dev -- --port ${uiPort}`,
  ]);
  return pane;
}

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
// THE BACKEND'S OUTPUT IS THIS TERMINAL'S OUTPUT UNDER --tmux: this pane is where the
// harness was asked to run, so its log lines arrive here as they do in the file. Off
// --tmux the file is enough -- the terminal belongs to vite there, and it says so by
// taking it over (`stdio: "inherit"` below). Each stream keeps its own: the pane
// interleaves them exactly as it would if the harness were its foreground process.
const tee = (chunk, sink) => {
  log.write(chunk);
  if (tmux) sink.write(chunk);
};
backend.stdout.on("data", (chunk) => tee(chunk, process.stdout));
backend.stderr.on("data", (chunk) => tee(chunk, process.stderr));

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
console.log(
  tmux
    ? `dev.mjs: UI on http://localhost:${uiPort}, in the pane to the right`
    : `dev.mjs: UI on http://localhost:${uiPort} (Ctrl-C stops both)`,
);
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

let exitCode;
if (tmux) {
  // THE PANE IS OPENED ONCE THE PORT IS KNOWN, which is the same ordering the other
  // mode has: the dev server is handed the backend's address at the moment it starts,
  // and there is no second chance to tell it.
  try {
    uiPane = await openUiPane(boundPort, uiPort);
  } catch (failure) {
    console.error(`dev.mjs: could not open the UI's pane -- ${failure.message}`);
    process.exit(1);
  }
  // AND THE THING THAT ENDS IS THE BACKEND: it is the child this process owns, so its
  // exit is the one event to wait on. The pane is tmux's child -- said goodbye to by
  // `cleanup` by name, because nothing here can wait on it.
  exitCode = await exitOf(backend);
} else {
  ui = run("npm", ["run", "dev", "--", "--port", String(uiPort)], {
    cwd: UI_DIR,
    stdio: "inherit",
    env: { ...process.env, ...uiEnv(boundPort) },
  });

  exitCode = await exitOf(ui);
}

cleanup();
process.exit(exitCode);
