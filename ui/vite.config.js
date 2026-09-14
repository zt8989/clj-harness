import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// One build, one dev server.
//
//   @vitejs/plugin-react  compiles the TSX and drives Fast Refresh
//   tsc --noEmit          is the type gate, and runs as part of `npm run build`
//
// Vite bundles npm and serves the page; there is no second compiler in the loop
// any more, so the page's dependencies all arrive through Vite's normal
// dependency graph.
//
// The port is a contract, not a preference: the harness CORS-allows exactly
// http://localhost:5173 (src/harness/http.clj).
export default defineConfig({
  plugins: [react()],
  server: { port: 5173, strictPort: true },
});
