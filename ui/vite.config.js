import { fileURLToPath } from "node:url";
import tailwindcss from "@tailwindcss/vite";
import react from "@vitejs/plugin-react";
import { defineConfig } from "vite";

// One build, one dev server.
//
//   @vitejs/plugin-react  compiles the TSX and drives Fast Refresh
//   @tailwindcss/vite     compiles the stylesheet (./src/styles.css) and scans
//                         the source tree for the utility classes it uses
//   tsc --noEmit          is the type gate, and runs as part of `npm run build`
//
// Vite bundles npm and serves the page; there is no second compiler in the loop
// any more, so the page's dependencies all arrive through Vite's normal
// dependency graph.
//
// `@` is the alias the assistant-ui components are written against: the copies
// we pull in import `@/lib/utils`, `@/components/ui/button` and friends, so the
// alias has to mean the same thing here as it does in their docs. It is spelled
// out of `import.meta.url` rather than `__dirname` because this file is ESM.
//
// ------------------------------------------------------------------ the proxy
//
// THE PAGE TALKS TO ITS OWN ORIGIN, and this dev server forwards to the harness.
// That is what `HARNESS_BACKEND_URL` below is for, and it is why the browser
// never makes a cross-origin request at all: no preflight, no CORS allowance to
// keep in step with the port, and the same address in development that a
// deployment has behind its own front. `src/lib/threads.ts` spells the same
// decision out from the client's side: the harness's address defaults to this
// origin, and the two paths that hang off it (`API_BASE`, `AGENT_URL`) are
// derived from that one value.
//
// ONE CONTEXT, because the harness is under one prefix: `/api/agent` is the
// AG-UI run endpoint and everything else under `/api` is the management edge.
// Nothing has to be told apart by METHOD here any more -- the run used to be at
// the server ROOT, which the page is also served from, and a bare `/` would have
// been a prefix match dragging every module request through this middleware.
//
// THE TARGET IS AN ENVIRONMENT VARIABLE, and that is the point of the whole
// arrangement: the harness can be started on any port (`dev.sh` picks a free one
// and asks the OS for it), and nothing in the source has to know which.
//
// 5173 IS STILL A CONTRACT, but a smaller one than it used to be. It is the
// address a person opens and the one the harness's own CORS allowance names;
// with the proxy in place the browser no longer exercises that allowance, but
// `strictPort` stays because a second dev server quietly landing on 5174 is a
// worse surprise than a failed start.
const backend = process.env.HARNESS_BACKEND_URL ?? "http://127.0.0.1:8080";

export default defineConfig({
  plugins: [react(), tailwindcss()],
  resolve: {
    alias: { "@": fileURLToPath(new URL("./src", import.meta.url)) },
  },
  server: {
    port: 5173,
    strictPort: true,
    proxy: {
      // Prefix match, so it covers `/api/agent` too. Everything else on this dev
      // server -- the page, the modules, the HMR client -- is Vite's and never
      // reaches this middleware.
      "/api": { target: backend, changeOrigin: true },
    },
  },
});
