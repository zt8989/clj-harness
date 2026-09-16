// The vitest entry point for the UI suites, deliberately separate from
// vite.config.js.
//
// The dev server's config loads the React plugin (the browser bundle);
// a test run needs none of it -- the suites drive a real harness over HTTP from
// node. Vitest prefers `vitest.config.*` over `vite.config.*`, so this file
// existing is what keeps the two apart.
//
// NOTHING FROM src/ THAT NEEDS A BROWSER: no React, no DOM, and no `@` alias
// either (it is declared in vite.config.js, which this run does not load). A pure
// module -- one that imports nothing, like src/lib/format.ts, whose job is turning
// numbers into the strings a person reads -- is imported by RELATIVE path by the
// suite that tests it (see suites/stats.ts). That is the whole exception, and it
// exists because the alternative is an untested formatter.
import { defineConfig } from "vitest/config";

export default defineConfig({
  test: {
    // Only the ONE driver is a test file. The suites under test/suites/ are
    // plain modules it imports, and support/ is helpers -- neither should be
    // collected as a test file of its own, because a second file would mean a
    // second harness (a JVM per file).
    include: ["test/**/*.test.ts"],
    // With one file there is nothing to interleave, and if a second driver ever
    // appears this keeps them from each booting a server at the same time.
    fileParallelism: false,
    // A case can span several runs (a tool round, an approval and its resume),
    // and the harness boots a JVM before any of them.
    testTimeout: 120_000,
    hookTimeout: 240_000,
    // Each test's name is printed as it runs, next to its own result.
    reporters: ["verbose"],
  },
});
