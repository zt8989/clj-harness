// The vitest suite, and the seam between two languages.
//
// The tests themselves are ClojureScript, mirroring src/ under
// ui/test/harness/ui/ -- this file is what lets one runner own them. Two things
// happen here and nothing else: a harness is started for the run, and every
// `deftest` in the compiled ClojureScript is registered as one vitest case, run
// by id, with its cljs.test failures re-thrown so vitest reports them.
//
// Why the seam exists at all: `shadow-cljs compile test` compiles the suites into
// cljs-test/tests.cjs, a UMD bundle exporting two functions -- `tests()` (the
// list) and `run(id)` (one test -> {pass, fail, error}). Everything vitest does
// not need to know about stays on the CLJS side of that line: assertions,
// fixtures, the reporter, the async protocol.
//
// Node's interop carries that bundle here: `module.exports = factory()` on the
// other side arrives as the DEFAULT export, because the bundle is CommonJS and
// this file is ESM. The bundle has to be .cjs (the package is `"type": "module"`,
// so a .js file in it would be parsed as ESM and the UMD wrapper's `__dirname`
// would not exist) -- its extension is fixed by shadow-cljs's :node-library
// target, not by taste. It is a build artifact and is gitignored.
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { afterAll, beforeAll, describe, test } from "vitest";

import { startHarness } from "./support/harness.js";

const HERE = path.dirname(fileURLToPath(import.meta.url));
const COMPILED = path.resolve(HERE, "..", "cljs-test", "tests.cjs");

if (!fs.existsSync(COMPILED)) {
  throw new Error(
    `the ClojureScript test build is missing: ${COMPILED}\n` +
      `Run \`npm run test:build\` first (or \`npm test\`, which does it for you).`,
  );
}

const cljs = (await import(COMPILED)).default;

let harness = null;

beforeAll(async () => {
  harness = await startHarness();
  // The two facts the suites need. Globals rather than arguments because they
  // cross into ClojureScript, where a global is one line to read and an injected
  // parameter would have to be threaded through every test body.
  globalThis.HARNESS_URL = harness.url;
  globalThis.HARNESS_SCRIPT = harness.scriptPath;
  console.log(`harness for this run: ${harness.url}`);
}, 240_000);

afterAll(async () => {
  if (harness) await harness.stop();
});

describe("ui (ClojureScript, driven by vitest)", () => {
  const tests = Array.from(cljs.tests());

  if (tests.length === 0) {
    // A green run that proved nothing is the failure mode this whole bridge
    // could hide most easily -- a build without `:load-tests`, a suite missing
    // `(deftest-index)`, or a file dropped from the runner's `suites` list all
    // produce a silent zero here.
    test("there is at least one test to run", () => {
      throw new Error(
        "no ClojureScript tests were found. Check `:load-tests true` in shadow-cljs.edn " +
          "and that the suite ends with (deftest-index).",
      );
    });
  }

  for (const { id, name } of tests) {
    test(name, async () => {
      const r = await cljs.run(id);
      const problems = [
        ...Array.from(r.error || []).map((e) => `error: ${e}`),
        ...Array.from(r.fail || []).map((f) => `fail:  ${f}`),
      ];

      // A test that asserted nothing is reported as empty, not as passing: it
      // means an `is` never ran, which is a different problem from a green one.
      if (problems.length === 0 && r.pass === 0) {
        throw new Error(`${id} passed with 0 assertions -- did the test body run?`);
      }
      if (problems.length > 0) {
        throw new Error(
          `${id} (${r.pass} passed, ${problems.length} failed)\n  ${problems.join("\n  ")}`,
        );
      }
    });
  }
});
