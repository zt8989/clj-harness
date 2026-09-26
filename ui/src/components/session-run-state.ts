// WHAT THE SERVER SAID THIS SESSION'S RUN IS DOING -- `running` / `parked` / `settled` /
// `unfinished`, straight off the window the page follows, or null while there is nothing
// to say.
//
// ============================================================== why it is its own module
//
// IT IS A CONTEXT AND NOT A PROP because the composer lives INSIDE the copied element
// (`<Thread/>`), which `App` renders as `children` and so cannot hand anything to -- the
// same reason the thread id travels through one (see `composer-chrome.tsx`).
//
// AND IT IS A MODULE OF ONE VALUE because the suite that renders what stands on it cannot
// import `components/composer-chrome.tsx`: that one reaches `lib/attachments.ts`, which
// reaches `lib/i18n.ts`, which touches `document` as it loads. So the state lives here,
// the thing drawn from it lives beside it (`session-run-stop.tsx`), and the two are
// importable by a test run that has no DOM.
//
// THE FACT ITSELF IS `App`'s `runState` (ticket 04 of `.scratch/session-after-refresh`):
// a run belongs to the PROCESS, not to the tab that started it, so a page that reloaded
// into a conversation somebody is still answering has no reading of its own -- the
// window's own state (`useWindowFeed`'s `onState`) is the only thing that knows, and the
// page hands it down rather than re-deriving it.
import { createContext } from "react";

/// THE SERVER'S OWN WORD FOR THIS SESSION'S RUN, one of the four above, or null. Read by
/// the composer's action row: `running` is the one word that turns Send into Stop, because
/// it is the one word that means a run of this conversation is going in the process.
export const SessionRunContext = createContext<string | null>(null);