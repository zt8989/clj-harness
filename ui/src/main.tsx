// Browser entry: owns the React root and the stylesheet.
//
// The page itself is ./app, kept in its own module so a hot reload can swap it
// without tearing the root down and starting over.
//
// Mounting on load *is* the entry. The root is created once per page load and
// lives for the life of the document; edits inside App and below are picked up
// by Fast Refresh, which re-renders without re-running this module.
//
// The stylesheet is imported here, and this is the only place it is named: it is
// the entry the Vite plugin compiles, and everything the copied components rely
// on (Tailwind, the shadcn theme tokens, the shimmer/collapsible keyframes) is
// reached through it. See src/styles.css.
import { StrictMode } from "react";
import { createRoot } from "react-dom/client";

import { App } from "./app";
import "./styles.css";


const container = document.getElementById("root");
if (container === null) throw new Error("index.html has no #root element");

createRoot(container).render(
  <StrictMode>
    <App />
  </StrictMode>,
);
