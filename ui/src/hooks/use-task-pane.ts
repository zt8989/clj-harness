// THE TASK PANE'S TICK: what this session has going on, asked once a second for as long
// as somebody is looking.
//
// WHY POLLING AND NOT A SUBSCRIPTION (`.scratch/right-pane-tasks`, decision 7). A job's
// ending has no push channel to a client -- the notice rides the NEXT model call, not a
// frame -- and this pane wants "how is it NOW". A one-second poll that stops the moment
// nobody is looking is cheaper than a new SSE channel opened for one panel, and it needs
// no server-side subscription to leak.
//
// ONE TICK, ONE PLACE. The pane has TWO sections to fill and they read the same moment:
// ticket 02 fills the jobs half and ticket 03 the subagents half, and ticket 03's read
// joins HERE -- this one tick -- rather than a second interval that would be a
// second clock and a second thing to stop. That is why this hook is named for the PANE
// and not for the jobs list.
//
// STOPPED WHEN NOBODY IS LOOKING, and that is the whole discipline (the same rule the
// mirror's channel keeps). THREE things end it, and each clears the timer AND aborts the
// read in flight, so a pane nobody can see leaves nothing running on the server's behalf:
// the pane UNMOUNTS (closing it does that), the document goes HIDDEN, and the pane is NO
// LONGER RENDERED -- the third one was found by a walkthrough on a narrow window, where
// the column is `display:none` below `md` while the STATE can still be "open", so a
// mounted pane was asking the server for rows nothing could draw. What a source read can
// pin is these decisions; that a browser really stops asking is the walkthrough's half.
import { useEffect, useState, type RefObject } from "react";

import { jobsFor, type JobRow } from "@/lib/jobs";
import { subagentsFor, type SubagentTaskRow } from "@/lib/subagents-runs";

/// HOW OFTEN THE PANE ASKS, in milliseconds. A NAMED CONSTANT because the cadence is a
/// decision rather than a detail: fast enough that a running job's clock visibly moves,
/// slow enough that a pane left open is one request a second rather than a loop. It is
/// exported so a test can name the number instead of re-writing it.
export const TASK_PANE_POLL_MS = 1000;

/// What the pane draws, this moment: both of its sections, read on the same tick.
export type TaskPaneData = {
  /// The session's background jobs, in the order the server listed them. Empty means
  /// either "nothing is running" or "we have not heard yet"; the pane draws the same
  /// sentence for both, and neither is an error.
  jobs: readonly JobRow[];
  /// AND THE SAME SESSION'S DELEGATIONS, in the server's order (newest first). Empty is
  /// the same kind of answer as `jobs`'s empty, and so is a section that could not be
  /// read this second: the pane keeps what it had rather than blinking to empty.
  subagents: readonly SubagentTaskRow[];
};

/// Watch WHAT THREAD-ID HAS RUNNING while the caller is mounted, rendered and the page is
/// visible. PANE is the column's own element -- the thing whose being on screen this reads.
///
/// THREAD-ID IS A DEPENDENCY OF THE EFFECT, so switching sessions stops the old tick and
/// starts one for the new session -- a pane showing one session must never draw another's
/// jobs, nor another's delegations.
export function useTaskPane(
  threadId: string,
  pane: RefObject<HTMLElement | null>,
): TaskPaneData {
  const [jobs, setJobs] = useState<readonly JobRow[]>([]);
  const [subagents, setSubagents] = useState<readonly SubagentTaskRow[]>([]);

  useEffect(() => {
    let timer: ReturnType<typeof setInterval> | null = null;
    let inFlight: AbortController | null = null;
    // THE THIRD FACT (`rendered`): "this hook is mounted" and "the pane is on screen" are
    // not the same thing, because the column is `display:none` below `md` and the state can
    // still say "open". True until the observer says otherwise, so the very first read
    // happens without waiting for a callback that is only sent on a CHANGE of state.
    let rendered = true;

    const read = (): void => {
      // ONE READ AT A TIME, the previous one aborted: two answers arriving out of order
      // would let the older one win, and a read still in flight when the pane closes is
      // exactly the thing this hook promises to leave behind nothing of.
      inFlight?.abort();
      const flight = new AbortController();
      inFlight = flight;
      void jobsFor(threadId, flight.signal).then((rows) => {
        // null is "we could not ask" -- keep the rows we have. An empty LIST is a real answer
        // ("nothing is running") and does replace them.
        if (rows !== null && !flight.signal.aborted) setJobs(rows);
      });
      // THE SECOND READ OF THE SAME MOMENT, sharing the tick's ONE controller: the two
      // sections answer one question ("what has this session got going on") and the same
      // abort ends both, so there is still exactly one thing in flight to stop.
      void subagentsFor(threadId, flight.signal).then((rows) => {
        if (rows !== null && !flight.signal.aborted) setSubagents(rows);
      });
    };

    const stop = (): void => {
      if (timer !== null) {
        clearInterval(timer);
        timer = null;
      }
      inFlight?.abort();
      inFlight = null;
    };

    const start = (): void => {
      // Guarded, so a visibility event that repeats cannot stack a second interval.
      if (timer !== null) return;
      read();
      timer = setInterval(read, TASK_PANE_POLL_MS);
    };

    /// Whether the tick should be running, asked of the three facts together. ONE function,
    /// because a second place that decided this would be a second answer to one question --
    /// `stop()` is idempotent and `start()` is guarded, so the extra calls are free.
    const sync = (): void => {
      if (document.visibilityState === "visible" && rendered) start();
      else stop();
    };

    // WHETHER THE COLUMN IS DRAWN IS ASKED OF THE LAYOUT, NOT OF A BREAKPOINT. An
    // observation reports `false` for an element with no boxes at all -- `display:none`,
    // which is exactly the narrow-window case -- and it goes on telling the truth if the
    // column is hidden some other way later. A resize listener would be this module
    // deciding a fact the stylesheet already has; see `components/sidebar-toggle.tsx`,
    // which argues the same line about the sidebar's fold.
    const observer = new IntersectionObserver((entries) => {
      const last = entries[entries.length - 1];
      if (last !== undefined) rendered = last.isIntersecting;
      sync();
    });
    if (pane.current !== null) observer.observe(pane.current);

    // FIRST READ IMMEDIATELY, not in a second: a pane opened onto a session already
    // running something draws the row at once, and the interval is for what comes after.
    sync();
    document.addEventListener("visibilitychange", sync);
    return () => {
      observer.disconnect();
      stop();
      document.removeEventListener("visibilitychange", sync);
    };
  }, [threadId, pane]);

  return { jobs, subagents };
}
