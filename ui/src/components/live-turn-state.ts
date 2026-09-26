// THE TURN THE SERVER SAYS IS OPEN -- `turn/start` / `turn/end`, as they arrived, seeded from the
// window's own state word where the family is silent. See `lib/live-turn.ts` for why this is not
// `SessionRunContext` (that one is the RUN's word) and why the two are kept apart.
//
// ============================================================== why it is its own module
//
// THE SAME REASON `components/session-run-state.ts` GIVES, applied to the message footer: the thing
// that reads this value is INSIDE the copied element (`<Thread/>`), which `App` renders as
// `children` and so cannot hand a prop to. A context is the door, and it lives beside the value it
// carries rather than inside `app.tsx`, so a suite that renders what stands on it does not have to
// import the whole page.
import { createContext } from "react";

import { NO_TURN, type LiveTurn } from "@/lib/live-turn";

/// THE OPEN TURN, or `NO_TURN` when there is none: read by the turn's own furniture end
/// (`thread.aui.tsx`), which draws the "still working" dot while it is open and nobody else's.
export const SessionTurnContext = createContext<LiveTurn>(NO_TURN);
