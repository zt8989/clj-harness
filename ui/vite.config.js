import { defineConfig } from "vite";
import { cljs } from "./vite-plugin-cljs.js";

// Two compilers, one dev server.
//
//   shadow-cljs  compiles ClojureScript (and hot-reloads it over its own socket)
//   Vite         bundles npm and serves the page
//
// `virtual:shadow-cljs/app` is the seam: the compiled build reaches Vite as an
// ordinary module, so React, CopilotKit and CopilotKit's own stylesheet all arrive
// through Vite's normal dependency graph. shadow-cljs never has to understand npm --
// which is the only reason this works at all.
//
// The port is a contract, not a preference: the harness CORS-allows exactly
// http://localhost:5173 (src/harness/http.clj).
export default defineConfig({
  plugins: [cljs()],
  server: { port: 5173, strictPort: true },
});
