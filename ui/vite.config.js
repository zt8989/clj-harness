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
// The port is a contract, not a preference: the harness CORS-allows exactly
// http://localhost:5173 (src/harness/http.clj).
export default defineConfig({
  plugins: [react(), tailwindcss()],
  resolve: {
    alias: { "@": fileURLToPath(new URL("./src", import.meta.url)) },
  },
  server: { port: 5173, strictPort: true },
});
