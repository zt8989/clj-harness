// The vitest entry point for the UI suites, deliberately separate from
// vite.config.js.
//
// The dev server's config loads the React plugin (the browser bundle);
// a test run needs none of it -- the suites drive a real harness over HTTP from
// node, and the one case that renders a component renders it to a STRING
// (`react-dom/server`, see suites/sidebar.tsx), which needs no DOM. Vitest prefers
// `vitest.config.*` over `vite.config.*`, so this file existing is what keeps the
// two apart.
//
// NO DOM IS STILL THE RULE: nothing here mounts a component into a document, so
// there is no jsdom, no testing-library, and no `window` for a module to find.
// WHAT IS NO LONGER THE RULE is the stronger one that used to be written here --
// that src/ may only be imported when it needs no browser at all. That rule had a
// price and the price was paid on 2026-09-18: the session row's id line went blank
// in the i18n merge, and NOTHING IN THIS RUN COULD SEE IT, because nothing here
// rendered a component. 859 backend cases and 36 UI cases were green on a page
// whose sidebar had no titles; it was found by opening a browser
// (.scratch/session-title-blank/). So a component may be imported and rendered now,
// and that costs exactly two settings:
//
//   * `@` resolves here the way it does in vite.config.js, because a component
//     imports its neighbours that way.
//   * the JSX compiles through esbuild, which reads tsconfig.json's `jsx` -- the
//     same setting `npm run build` gates.
//
// A SUITE'S OWN IMPORTS STAY RELATIVE (`../../src/lib/format`), so which files a
// suite reaches for is read off its import list rather than out of the alias.
//
// WHAT RENDERING TO A STRING CANNOT SEE: layout. A `<code>` with no text has no
// line box, so the row that broke here was not short by a line -- it looked like a
// row that never had one. That is a browser's question, and the browser remains
// this repo's layer for it (`scripts/dev.mjs --scripted`, see AGENTS.md).
import { fileURLToPath } from "node:url";
import { defineConfig } from "vitest/config";

export default defineConfig({
  // The alias a component under src/ imports its neighbours through. Spelled out of
  // `import.meta.url` rather than `__dirname` because this file is ESM -- the same
  // line vite.config.js holds, and the same reason it holds it.
  resolve: {
    alias: { "@": fileURLToPath(new URL("./src", import.meta.url)) },
  },
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
