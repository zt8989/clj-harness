// Vitest's config, deliberately separate from vite.config.js.
//
// The dev server's config loads `vite-plugin-cljs.js`, whose whole job is to keep
// `shadow-cljs watch` alive and hand the compiled build to Vite as a virtual
// module. A test run must not do that: it needs no ClojureScript in the browser,
// and spawning a second watcher against a build the dev server already owns fails
// with "already started". Vitest prefers `vitest.config.*` over `vite.config.*`,
// so this file existing is what keeps the two apart.
import { defineConfig } from "vitest/config";

export default defineConfig({
  test: {
    // Only *.test.js is a test. The suites themselves are ClojureScript, loaded
    // by the one file that matches this glob; nothing else under test/ is one
    // (support/ is helpers).
    include: ["test/**/*.test.js"],
    // One file, and it spawns a harness and runs the CLJS tests in sequence. The
    // suites share one server on purpose (the alternative is a JVM per test) and
    // serialise themselves, so parallelism here would only interleave output.
    fileParallelism: false,
    // A live harness is a JVM that has to boot and compile before the first test
    // can run. The default 5s hook timeout would fail on startup alone.
    testTimeout: 120_000,
    hookTimeout: 240_000,
    // Each test's name is printed as it runs: the CLJS side reports a pass count
    // and failure text, and vitest shows both next to the test they belong to.
    reporters: ["verbose"],
  },
});
