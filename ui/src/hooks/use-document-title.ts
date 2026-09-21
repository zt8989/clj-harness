// The browser tab, kept in step with the session on screen.
//
// ---------------------------------------------------------------- why an effect
//
// `document.title` IS NOT REACT STATE, so there is nothing to render and no way to
// declare it: it is a fact about the outside world that has to be written, and an
// effect is where React puts "write to the outside world". The alternative -- a
// `<title>` element rendered into the document -- is a `react-helmet`-shaped
// dependency for one string.
//
// THE WHOLE TITLE IS COMPOSED IN `lib/session-title.ts` (`documentTitle`), not here:
// that module owns the words and the separator, and this one only decides WHEN they
// are written. Splitting it the other way -- the product name spelled here -- would
// put the same constant in two files.
//
// ------------------------------------------------------------------ the unmount
//
// THE CLEANUP PUTS THE PRODUCT NAME BACK, and it is not tidiness. A session whose
// history will not load leaves no host at all (see `App`'s `hostFailed`): if that was
// the only session open, the roster empties, the column unmounts, and the tab would
// otherwise keep the title of a conversation that is no longer on screen -- a title
// for a page you are not looking at.
//
// SWITCHING SESSIONS DOES NOT FLASH THROUGH IT. The outgoing host stops drawing its
// column and the incoming one starts in the SAME commit, so React runs the old
// cleanup and the new effect before the browser paints: the intermediate product name
// is a value in a commit, never a frame.
//
// ------------------------------------------------------- the caller is the guard
//
// ONLY THE VISIBLE SESSION CALLS THIS. `SessionHost` renders its column only when it
// is the session on screen (`{visible ? children : null}`), so "which session's title
// is in the tab" is answered by which component exists -- there is deliberately no
// registry of id-to-title and no reporter to keep it in step.
import { useEffect } from "react";

import { PRODUCT_NAME, documentTitle } from "@/lib/session-title";

/// Write `title` into the browser tab for as long as this component is mounted.
export function useDocumentTitle(title: string): void {
  useEffect(() => {
    document.title = documentTitle(title);
    return () => {
      document.title = PRODUCT_NAME;
    };
  }, [title]);
}
