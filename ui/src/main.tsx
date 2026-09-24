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
// SO IS THE LANGUAGE, and its position in this list is the whole reason it is
// imported here rather than inside App: `./lib/i18n` initializes i18next and sets
// `<html lang>` as a side effect of being loaded, so importing it above the render
// is what makes the first paint already be in the right language. Imported from
// inside a component it would still work -- and would also let one frame of raw
// keys (`view.conversation`) reach the screen.
//
// AND THE MODULE IS ALSO READ HERE NOW, not only imported for that side effect: the
// page's first conversation is named by the server below, and the failure sentence for
// it is the `errors` catalog's -- see `boot`.
import { StrictMode } from "react";
import { createRoot } from "react-dom/client";

import { App } from "./app";
import i18n from "./lib/i18n";
import { mintThreadId } from "./lib/projects";
import "./styles.css";


const container = document.getElementById("root");
if (container === null) throw new Error("index.html has no #root element");
/// THE ROOT IS MADE HERE, where the narrowing above still holds: `boot` is a closure, and a
/// closure does not keep a narrowing -- which is why the render is the only thing it does
/// with this.
const root = createRoot(container);

/// THE PAGE'S FIRST CONVERSATION IS NAMED BY THE SERVER, AND THE NAME IS ASKED FOR
/// BEFORE THE FIRST PAINT (2026-09-23, `.scratch/server-named-sessions`).
///
/// WHY HERE AND NOT INSIDE `App`: the page is never without a conversation to be in --
/// that is a property the roster was built for (there is no "no session" box) -- and a
/// name that arrives asynchronously would have to be waited for SOMEWHERE. Waiting here
/// keeps every id in `App` a real string from the first render, and the price is one
/// request before the page appears: this harness SERVES the page, so a server that cannot
/// answer this one is a server that could not have sent the bundle either.
///
/// A FAILURE IS STILL WORTH A SENTENCE rather than a blank document, because in
/// development the page is served by vite while the harness is a second process -- and
/// "the backend is not running" should read as itself.
const t = i18n.getFixedT(null, "errors");

function BootFailure({ message }: { message: string }) {
  return (
    <p role="alert" data-slot="boot-error" className="text-destructive p-4 text-sm">
      {message}
    </p>
  );
}

async function boot(): Promise<void> {
  try {
    const firstThreadId = await mintThreadId(t);
    root.render(
      <StrictMode>
        <App initialThreadId={firstThreadId} />
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
