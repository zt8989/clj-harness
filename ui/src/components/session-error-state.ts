// WHAT A SESSION THAT FAILED IS HOLDING, as a context rather than a prop -- and for
// the reason every other session value that reaches the composer is one
// (`session-run-state.ts`): the card is drawn INSIDE the copied `Thread` element,
// which the page renders as `children` and so cannot hand a prop to.
//
// THE VALUE IS THE FAILURE ITSELF AND NOT A CALLBACK. Whatever the page or the host
// decides to do about it (clear it on the next run, re-read the session) belongs to
// them; the card only draws what arrived.
import { createContext, useContext } from "react";

import type { SessionFailure } from "@/lib/session-error";

/// THE FAILURE THIS SESSION RAISED, or null while it has none -- the ordinary answer,
/// and the one the card draws nothing for.
export const SessionErrorContext = createContext<SessionFailure | null>(null);

/// What the composer's frame reads. A hook rather than the bare context so the
/// consumer names the fact instead of the plumbing.
export const useSessionFailure = (): SessionFailure | null => useContext(SessionErrorContext);
