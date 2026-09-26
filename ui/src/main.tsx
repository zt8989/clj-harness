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
//
// THE LANGUAGE IS AWAITED HERE, BEFORE THE ROOT EXISTS, and that is the whole reason it
// is not an import side effect any more: the language lives in config.edn and is fetched
// (./lib/i18n's `startLanguage`), so nothing can know it synchronously. Two things below
// depend on this order -- the first paint is already in the right language, and no frame
// of raw keys (`view.conversation`) reaches the screen. See ./lib/i18n for the cost of
// that round trip.
//
// AND IT IS THE ONLY THING THIS ENTRY WAITS FOR, which is worth saying because it was
// briefly two. For one day the page's first conversation was named by the SERVER, and that
// put a request in front of the first paint (2026-09-23, withdrawn on the 24th --
// `.scratch/client-named-sessions`). The name belongs to the CLIENT: that is AG-UI's
// design, `lib/id.ts` says which of its functions makes one and why that one, and `App`
// opens its first conversation itself, synchronously, the moment it mounts.
import { StrictMode } from "react";
import { createRoot } from "react-dom/client";

import { App } from "./app";
import { startLanguage } from "./lib/i18n";
import "./styles.css";

const container = document.getElementById("root");
if (container === null) throw new Error("index.html has no #root element");
/// THE ROOT IS MADE HERE, where the narrowing above still holds: `boot` is a closure, and a
/// closure does not keep a narrowing -- which is why the render is the only thing it does
/// with this.
const root = createRoot(container);

/// A FAILURE IS WORTH A SENTENCE rather than a blank document: the language is a fetch, and
/// in development this page is served by vite while the harness is a second process -- so
/// "the backend is not running" should read as itself.
function BootFailure({ message }: { message: string }) {
  return (
    <p role="alert" data-slot="boot-error" className="text-destructive p-4 text-sm">
      {message}
    </p>
  );
}

async function boot(): Promise<void> {
  try {
    // THE LANGUAGE COMES FIRST, and it is the one ordering that is not a preference: it is
    // what makes the first paint already be in the right language, and it is the catalogs
    // this sentence comes from.
    await startLanguage();
    root.render(
      <StrictMode>
        <App />
      </StrictMode>,
    );
  } catch (failure: unknown) {
    root.render(
      <StrictMode>
        <BootFailure
          message={failure instanceof Error ? failure.message : String(failure)}
        />
      </StrictMode>,
    );
  }
}

void boot();
