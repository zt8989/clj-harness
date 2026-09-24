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
// mirror's follow channel keeps): the timer exists only while (a) the pane is MOUNTED --
// closing it unmounts this hook -- and (b) the document is VISIBLE. Either ending clears
// the timer AND aborts the read in flight, so a closed or hidden pane leaves nothing
// running on the server's behalf. What a source read can pin is these decisions; that a
// browser really stops asking when the tab is hidden is the walkthrough's half.
import { useEffect, useState } from "react";

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

/// Watch WHAT THREAD-ID HAS RUNNING while the caller is mounted and the page is visible.
///
/// THREAD-ID IS A DEPENDENCY OF THE EFFECT, so switching sessions stops the old tick and
/// starts one for the new session -- a pane showing one session must never draw another's
/// jobs, nor another's delegations.
export function useTaskPane(threadId: string): TaskPaneData {
  const [jobs, setJobs] = useState<readonly JobRow[]>([]);
  const [subagents, setSubagents] = useState<readonly SubagentTaskRow[]>([]);

  useEffect(() => {
    let timer: ReturnType<typeof setInterval> | null = null;
    let inFlight: AbortController | null = null;

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

    const onVisibility = (): void => {
      if (document.visibilityState === "visible") start();
      else stop();
    };

    // FIRST READ IMMEDIATELY, not in a second: a pane opened onto a session already
    // running something draws the row at once, and the interval is for what comes after.
    if (document.visibilityState === "visible") start();
    document.addEventListener("visibilitychange", onVisibility);
    return () => {
      stop();
      document.removeEventListener("visibilitychange", onVisibility);
    };
  }, [threadId]);

  return { jobs, subagents };
}
