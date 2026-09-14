// Compile the ClojureScript test build.
//
// A script rather than a bare `shadow-cljs compile test` in package.json, for one
// reason: Java. shadow-cljs 3.x needs Java 21, and on a machine with several JDKs
// `java` on PATH is usually not it -- so the child gets an explicit JAVA_HOME
// rather than a note in a README that nobody reads at the moment it matters.
//
// `compile`, not `release`. Release mode drops `deftest` entirely (:load-tests
// defaults false for optimized builds), producing a bundle with zero tests and no
// error -- the worst possible failure for a test build. `compile` keeps the
// compiler's test-instrumented pipeline and is plenty: these suites do no work
// that optimization would speed up.
import { spawnSync } from "node:child_process";
import path from "node:path";
import { fileURLToPath } from "node:url";

import { javaHome21 } from "./java.js";

const UI_ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..", "..");

const java = javaHome21();
if (!java) {
  console.warn(
    "warning: no Java 21 found (checked $JAVA_HOME and the usual install locations).\n" +
      "         shadow-cljs needs it; falling back to whatever `java` is on PATH.",
  );
}

const env = { ...process.env };
if (java) env.JAVA_HOME = java;

// The CLI is a .cmd shim on Windows, which only a shell can run.
const result = spawnSync("shadow-cljs", ["compile", "test"], {
  cwd: UI_ROOT,
  env,
  stdio: "inherit",
  shell: process.platform === "win32",
});

if (result.error) {
  console.error(`could not run shadow-cljs: ${result.error.message}`);
  console.error("Is node_modules installed? (npm install)");
  process.exit(1);
}
process.exit(result.status ?? 1);
