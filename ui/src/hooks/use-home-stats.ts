// THE STATISTICS PAGE'S DATA: what the whole home has been doing, within one window, READ ONCE
// and then PUSHED (`docs/rules/panel-data.md`).
//
// THE SAME THREE FACTS `hooks/use-task-pane.ts` watches, for the same reasons: the view UNMOUNTS,
// the document goes HIDDEN, or the column leaves the screen. Each of those ends the subscription,
// and coming back re-reads the snapshot first -- a frame missed while nobody listened is repaired
// by the read, never by a guess.
//
// THE WINDOW IS A DEPENDENCY, NOT A FILTER. Changing 7 to 30 days ends the old subscription and
// starts one for the new question (the server captured the old window in the push it registered),
// and re-reads the snapshot for it -- so the two halves are always about the same range.
//
// NO LOCAL TIMER: nothing here is a duration, so there is no number the server cannot send.
import { useEffect, useState, type RefObject } from "react";

import {
  homeStats,
  subscribeHomeStats,
  type HomeStats,
} from "@/lib/home-stats";

/// Watch the home's statistics for DAYS while the caller is mounted, rendered and the page is
/// visible. PANE is the view's own element -- the thing whose being on screen this reads.
export function useHomeStats(
  pane: RefObject<HTMLElement | null>,
  days: number,
): HomeStats | null {
  const [stats, setStats] = useState<HomeStats | null>(null);

  useEffect(() => {
    let unsubscribe: (() => void) | null = null;
    let inFlight: AbortController | null = null;
    // "this hook is mounted" and "the view is on screen" are not the same thing (the rule
    // `use-task-pane.ts` states at length). True until the observation says otherwise, so the
    // first read happens without waiting for a callback that only arrives on a CHANGE.
    let rendered = true;

    /// THE SNAPSHOT, read on every re-entry (mount, a range change, the page coming back): what
    /// the store says is what a missed frame would have said. One read at a time, the previous
    /// aborted -- and an answer for a range this effect has already left is not drawn.
    const read = (): void => {
      inFlight?.abort();
      const flight = new AbortController();
      inFlight = flight;
      void homeStats(days, flight.signal).then((answer) => {
        // null is "we could not ask" -- keep what we have. An empty list is a real answer
        // ("nobody has called a tool in this window") and does replace it.
        if (answer !== null && !flight.signal.aborted) setStats(answer);
      });
    };

    const stop = (): void => {
      unsubscribe?.();
      unsubscribe = null;
      inFlight?.abort();
      inFlight = null;
    };

    const start = (): void => {
      // Guarded, so a visibility event that repeats cannot stack a second subscription.
      if (unsubscribe !== null) return;
      read();
      // AND THE SOCKET NEEDS NO READ ON RECONNECT: `events.stats` has no cursor and pushes the
      // whole answer as its opening frame, so the reopen is itself the repair.
      unsubscribe = subscribeHomeStats(days, (answer) => setStats(answer));
    };

    const sync = (): void => {
      if (document.visibilityState === "visible" && rendered) start();
      else stop();
    };

    // WHETHER THE VIEW IS DRAWN IS ASKED OF THE LAYOUT, NOT OF A BREAKPOINT: an observation
    // reports `false` for an element with no boxes at all -- `display:none`, which is exactly the
    // case when the page has put the conversation back -- and it goes on telling the truth if the
    // view is hidden some other way later.
    const observer = new IntersectionObserver((entries) => {
      const last = entries[entries.length - 1];
      if (last !== undefined) rendered = last.isIntersecting;
      sync();
    });
    if (pane.current !== null) observer.observe(pane.current);

    sync();
    document.addEventListener("visibilitychange", sync);
    return () => {
      observer.disconnect();
      stop();
      document.removeEventListener("visibilitychange", sync);
    };
  }, [pane, days]);

  return stats;
}
