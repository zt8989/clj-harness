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
// THIS RULE IS NO LONGER HOW THE PAGE REACHES THE HARNESS. `node scripts/dev.mjs`
// hands the page an absolute address (`VITE_AGENT_URL`, read by
// `src/lib/threads.ts`) and the harness answers a page served from this machine
// whatever port it is on, so the dev loop goes straight there and nothing has to
// be told which port this server got; the reason is in that script's header, and
// it is a defect in vite's own forwarder rather than a preference. What is left
// here is the standing prefix rule, kept correct for anyone who starts this dev
// server BY HAND and leaves `VITE_AGENT_URL` unset -- then the page talks to its
// own origin, this forwards, and no cross-origin request is made at all. Nothing
// about the app depends on which of the two it is: the address the page calls is
// one variable, and both spellings of it are somebody's job to set.
//
// ONE CONTEXT, because the harness is under one prefix: `/api/agent` is the
// AG-UI run endpoint and everything else under `/api` is the management edge.
// Nothing has to be told apart by METHOD here any more -- the run used to be at
// the server ROOT, which the page is also served from, and a bare `/` would have
// been a prefix match dragging every module request through this middleware.
//
// THE TARGET IS AN ENVIRONMENT VARIABLE, and that is the point of the whole
// arrangement: the harness can be started on any port (`scripts/dev.mjs` picks a
// free one and asks the OS for it), and nothing in the source has to know which.
//
// 5173 IS NO LONGER A CONTRACT WITH ANYBODY, and that is deliberate: it is only
// the address a person opens. The harness answers a page served from this machine
// whatever port it is on, so no number in this file has to be agreed with the
// server's -- they are two processes that need not be told about each other.
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
