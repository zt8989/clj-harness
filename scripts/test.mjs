#!/usr/bin/env node
//
// Run this repo's suites. ONE ENTRY POINT, because the conventions a run has to
// keep are properties of the INVOCATION, and a hand-assembled one forgets them:
//
//   node scripts/test.mjs                       backend suite + ui type gate + ui suite
//   node scripts/test.mjs --backend             the backend suite alone
//   node scripts/test.mjs --ui                  the ui suite alone
//   node scripts/test.mjs --build               the ui type gate and bundle alone
//   node scripts/test.mjs --ns harness.edge.http-test,harness.cap.todos-test
//                                               a TARGETED backend run -- and this is the
//                                       one worth reading the rest of this for
//
// WHAT IT KEEPS, SO THAT NOBODY HAS TO REMEMBER IT (see AGENTS.md, which is short
// now precisely because this file is where the rules live):
//
//   * HOMES ARE ISOLATED. `harness.test-runner/-main` points the config root and the
//     OS home at fresh temp directories -- siblings, never nested -- before anything
//     loads, fingerprints the developer's REAL store before and after, and deletes
//     both temp directories when it is done. The backend leg below goes through that
//     entry point, so the isolation is not something this script has to do; it is
//     something this script must not skip.
//
//   * AND THE TARGETED LEG SKIPS IT JUST AS LITTLE. `--ns` is the shape an agent
//     reaches for when one namespace is failing, and the shape that goes wrong:
//     `clojure -M:test -e "(run-tests 'x)"` sends that namespace's jsonl into the
//     developer's real home, and `(isolate!)` on its own gets the homes right while
//     still leaving the temp root behind and skipping the verdict. So the eval
//     string below names NO protocol of its own -- it calls `run!`, which is the
//     same entry point `-main` uses, with a shorter namespace list.
//
//   * NO PORT IS EVER WRITTEN DOWN. The suites pick their own (`{:port 0}` and the
//     OS), and the ui suite starts its own backend the same way
//     (`ui/test/support/harness.ts`). Nothing here passes a port in either.
//
//   * NOTHING SURVIVES A STOP. Both legs are spawned into their own process group
//     (off Windows) or killed with `taskkill /T` (on it), so Ctrl-C does not leave a
//     JVM holding a socket.
import path from "node:path";
import { fileURLToPath } from "node:url";

import { exitOf, run, stopTree } from "./proc.mjs";

// THE REPOSITORY IS THIS FILE'S PARENT: the legs below run with this as their cwd, so the
// script is invoked from wherever a reader is standing (`node scripts/test.mjs`) rather
// than from the one directory that happens to hold deps.edn.
const HERE = path.dirname(fileURLToPath(import.meta.url));
const ROOT = path.resolve(HERE, "..");
const UI_DIR = path.join(ROOT, "ui");

const USAGE = `Run this repo's suites, the way they are meant to be run.

  node scripts/test.mjs                       backend suite + ui type gate + ui suite
  node scripts/test.mjs --backend             the backend suite alone
  node scripts/test.mjs --ui                  the ui suite alone
  node scripts/test.mjs --build               the ui type gate and bundle alone
  node scripts/test.mjs --ns <a,b,...>        a targeted backend run (isolation still applied)

The backend leg is \`harness.test-runner\`, which isolates the filesystem before
anything loads and checks the developer's real store was left alone afterwards. The
targeted leg is the same thing with a shorter namespace list -- never a bare
\`clojure -M:test -e "(run-tests ...)"\`, which is how a run ends up writing into the
real home. See AGENTS.md, and this file's header for why that is the one rule that
cannot be spelled out at a call site.`;

const argv = process.argv.slice(2);
let backend = false;
let ui = false;
let build = false;
let namespaces = [];

for (let i = 0; i < argv.length; i += 1) {
  const arg = argv[i];
  if (arg === "--backend") backend = true;
  else if (arg === "--ui") ui = true;
  else if (arg === "--build") build = true;
  else if (arg === "--ns") {
    namespaces = String(argv[++i] ?? "")
      .split(",")
      .map((name) => name.trim())
      .filter((name) => name !== "");
    if (namespaces.length === 0) {
      console.error("test.mjs: --ns wants a comma-separated list of namespaces");
      process.exit(2);
    }
    backend = true;
  } else if (arg === "-h" || arg === "--help") {
    console.log(USAGE);
    process.exit(0);
  } else {
    console.error(`test.mjs: unknown argument: ${arg}\n`);
    console.error(USAGE);
    process.exit(2);
  }
}
// No choice made means the whole thing: the default is what CI would run, and a
// silent "nothing to do" on a bare `node scripts/test.mjs` would be a green run that proved
// nothing -- the failure mode the ui suite pins its own case count against.
if (!backend && !ui && !build) [backend, ui, build] = [true, true, true];

let running = null;
let cleaned = false;
function cleanup() {
  if (cleaned) return;
  cleaned = true;
  stopTree(running);
}
process.on("exit", cleanup);
for (const [signal, code] of [["SIGINT", 130], ["SIGTERM", 143]]) {
  process.on(signal, () => {
    cleanup();
    process.exit(code);
  });
}

/// One leg, run to completion, printing its own output. Answers the exit code.
async function leg(name, command, args, options = {}) {
  console.log(`\n=== ${name} :: ${[command, ...args].join(" ")}`);
  running = run(command, args, { cwd: options.cwd ?? ROOT, stdio: "inherit" });
  const code = await exitOf(running);
  running = null;
  console.log(`=== ${name} :: ${code === 0 ? "ok" : `FAILED (exit ${code})`}`);
  return code;
}

/// THE NAMESPACE LIST IS THE ONLY THING A CALLER SUPPLIES, and it is spliced into an
/// eval string that goes through the runner's own entry point -- so the isolation,
/// the verdict and the cleanup are the runner's, not this file's. Namespaces are
/// checked against the shape one can have, so the single path from an argument to a
/// JVM's `-e` cannot carry anything else.
function backendArgs() {
  if (namespaces.length === 0) return ["clojure", "-M:test", "-m", "harness.test-runner"];
  for (const name of namespaces) {
    if (!/^[A-Za-z][\w.-]*$/.test(name)) {
      console.error(`test.mjs: not a namespace: ${name}`);
      process.exit(2);
    }
  }
  // THE WHOLE PROTOCOL, NOT THE CONVENIENT PART OF IT. `run-suite!` is the door
  // `harness.test-runner/-main` goes through too: fingerprint the real store,
  // isolate, require, run, verdict, clean up, answer an exit code. The shorter
  // spelling an agent reaches for by hand -- `(isolate!)` and then `run-tests` --
  // covers the first two and none of the rest: it leaves a temp root behind on
  // every invocation, and it reports green without ever looking at the one
  // condition the whole arrangement exists for.
  //
  // ONE QUOTE, AROUND THE WHOLE VECTOR OF BARE NAMES. Quoting each name as well
  // would read as `'('a.b)` -- which Clojure accepts as a form and then hands to
  // `require` as a lib name, failing somewhere far from here. The shape check
  // above is what makes splicing safe: a name that cannot be a namespace never
  // reaches a JVM.
  return [
    "clojure",
    "-M:test",
    "-e",
    `(require 'harness.test-runner) (System/exit (harness.test-runner/run-suite! '(${namespaces.join(" ")})))`,
  ];
}

const failures = [];
if (backend) {
  const [command, ...args] = backendArgs();
  const code = await leg(
    namespaces.length === 0 ? "backend (harness.test-runner)" : `backend (${namespaces.join(", ")})`,
    command,
    args,
  );
  if (code !== 0) failures.push("backend");
}
if (build && (await leg("ui type gate + bundle", "npm", ["run", "build"], { cwd: UI_DIR })) !== 0) {
  failures.push("build");
}
if (ui && (await leg("ui suite", "npm", ["test"], { cwd: UI_DIR })) !== 0) {
  failures.push("ui");
}

cleanup();
if (failures.length > 0) {
  console.error(`\ntest.mjs: FAILED -- ${failures.join(", ")}`);
  process.exit(1);
}
console.log("\ntest.mjs: ok");
