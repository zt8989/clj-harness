// THE TASK PANE'S DATA: what this session has going on, READ ONCE and then PUSHED.
//
// WHY THE POLL IS GONE (ticket 01 of `.scratch/task-pane-push`, and the rule in
// `docs/rules/panel-data.md`). This hook used to ask two routes once a second
// (`TASK_PANE_POLL_MS`). A second is a guess at how stale a row may be, it pays a request and
// a server read for an answer that is usually identical to the last one, and it was the last
// poll left on this page.
//
// WHAT REPLACED IT. `subscribeTasks` -- a `task` frame on the session's socket
// (`harness.edge.http/task-send!`), sent when a job APPEARS or ENDS and when a delegation
// STARTS or ENDS. Those four moments are the only ones that change a row, so the pane is told
// about them instead of asking.
//
// THE THREE FACTS ARE STILL THE THREE, and each still ends the watching: the pane UNMOUNTS, the
// document goes HIDDEN, or the pane leaves the screen (the column is `display:none` below `md`
// while the state can still say "open"). What ended a poll now ends a SUBSCRIPTION, and coming
// back re-reads the snapshot first -- a push that was missed while nobody was listening is
// repaired by the read, never by a guess.
//
// ONE TIMER SURVIVES, AND IT IS THE ONE EXCEPTION THE RULE NAMES: 'how long has this been
// going' is a number the server cannot send (it would have to send it continuously), so this
// hook ticks LOCALLY (`TASK_PANE_TICK_MS`) to advance the durations of RUNNING rows -- and it
// ASKS NOTHING, and it STOPS when nothing is running. That is why `now` is part of what this
// hook returns: a duration is a function of two instants, and the instant that keeps moving is
// the client's own.
import { useEffect, useRef, useState, type RefObject } from "react";

import { jobsFor, isRunning, type JobRow } from "@/lib/jobs";
import { subscribeTasks } from "@/lib/mux";
import {
  rowsFromRuns,
  subagentsFor,
  type SubagentTaskRow,
} from "@/lib/subagents-runs";
import type { SubagentDefinition } from "@/lib/subagents";

/// HOW OFTEN A RUNNING ROW'S DURATION MOVES, in milliseconds. NOT A CADENCE OF REQUESTS --
/// the tick draws and asks nothing (`docs/rules/panel-data.md`: the one exception). Slow
/// enough to be a glance rather than a stopwatch's jitter, fast enough that a second is a
/// second.
export const TASK_PANE_TICK_MS = 1000;

/// What the pane draws, this moment: both of its sections, and the instant the durations in
/// them are measured against. `now` is the client's own clock -- see the note on the timer above.
export type TaskPaneData = {
  /// The session's background jobs, in the order the server listed them. Empty means
  /// either "nothing is running" or "we have not heard yet"; the pane draws the same
  /// sentence for both, and neither is an error.
  jobs: readonly JobRow[];
  /// AND THE SAME SESSION'S DELEGATIONS, in the server's order (newest first). Empty is
  /// the same kind of answer as `jobs`'s empty, and so is a section that could not be
  /// read this once: the pane keeps what it had rather than blinking to empty.
  subagents: readonly SubagentTaskRow[];
  /// MILLISECONDS, from this machine's clock, advanced once a second WHILE SOMETHING IS
  /// RUNNING and left alone otherwise. A row turns it into a duration.
  now: number;
};

/// Watch WHAT THREAD-ID HAS RUNNING while the caller is mounted, rendered and the page is
/// visible. PANE is the column's own element -- the thing whose being on screen this reads.
///
/// THREAD-ID IS A DEPENDENCY OF THE EFFECT, so switching sessions ends the old read and its
/// subscription and starts one for the new session -- a pane showing one session must never
/// draw another's jobs, nor another's delegations.
export function useTaskPane(
  threadId: string,
  pane: RefObject<HTMLElement | null>,
): TaskPaneData {
  const [jobs, setJobs] = useState<readonly JobRow[]>([]);
  const [subagents, setSubagents] = useState<readonly SubagentTaskRow[]>([]);
  const [now, setNow] = useState<number>(() => Date.now());
  /// THE DEFINITIONS LAST READ, kept because a pushed frame carries RUNS and not definitions:
  /// a delegation's row draws the definition's description, so the join needs both, and the
  /// snapshot read is what supplies the second half. A hand-edited config.edn is therefore
  /// picked up by the next snapshot (a remount, a session switch, the page coming back) rather
  /// than by the next push -- which is what the definitions ARE: configuration, not state.
  const definitions = useRef<readonly SubagentDefinition[]>([]);

  useEffect(() => {
    let unsubscribe: (() => void) | null = null;
    let inFlight: AbortController | null = null;
    // THE THIRD FACT (`rendered`): "this hook is mounted" and "the pane is on screen" are
    // not the same thing, because the column is `display:none` below `md` and the state can
    // still say "open". True until the observer says otherwise, so the very first read
    // happens without waiting for a callback that is only sent on a CHANGE of state.
    let rendered = true;

    /// THE SNAPSHOT, and it is read on every RE-ENTRY (mount, a session switch, the page
    /// coming back): what the store says is what a missed frame would have said. One read at
    /// a time, the previous one aborted.
    const read = (): void => {
      inFlight?.abort();
      const flight = new AbortController();
      inFlight = flight;
      void jobsFor(threadId, flight.signal).then((rows) => {
        // null is "we could not ask" -- keep the rows we have. An empty LIST is a real answer
        // ("nothing is running") and does replace them.
        if (rows !== null && !flight.signal.aborted) setJobs(rows);
      });
      // THE SECOND READ OF THE SAME MOMENT, sharing the one controller: the two sections
      // answer one question ("what has this session got going on") and the same abort ends
      // both, so there is still exactly one thing in flight to stop.
      void subagentsFor(threadId, flight.signal).then((listing) => {
        if (listing === null || flight.signal.aborted) return;
        definitions.current = listing.subagents;
        setSubagents(listing.rows);
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
      // AND THE SOCKET COMING BACK IS A RE-ENTRY THIS HOOK CANNOT SEE BY ITSELF (a task frame
      // carries no cursor): the third argument is this hook's own snapshot read, so a reconnect
      // reads once more -- the same read a mount and a visibility change make, for the same reason.
      unsubscribe = subscribeTasks(threadId, (task) => {
        // A FRAME REPLACES BOTH SECTIONS WHOLESALE, which is what makes it the same answer the
        // read gives (`task-body` builds both from the same moment). A key the frame does not
        // carry leaves the section alone rather than emptying it -- 'not reported' is not
        // 'nothing there', the discipline both readers keep.
        if (Array.isArray(task.jobs)) setJobs(task.jobs as JobRow[]);
        if (Array.isArray(task.delegations)) {
          setSubagents(
            rowsFromRuns(task.delegations as never, definitions.current, threadId),
          );
        }
      }, read).unsubscribe;
    };

    /// Whether this pane should be following, asked of the three facts together. ONE
    /// function, because a second place that decided this would be a second answer to one
    /// question -- `stop()` is idempotent and `start()` is guarded, so the extra calls are free.
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
    // running something draws the row at once.
    sync();
    document.addEventListener("visibilitychange", sync);
    return () => {
      observer.disconnect();
      stop();
      document.removeEventListener("visibilitychange", sync);
    };
  }, [threadId, pane]);

  /// WHETHER ANYTHING IS STILL GOING, which is the whole of what the local tick is for: a
  /// duration that is not moving has nothing to redraw, and a pane with nothing running must
  /// leave no timer behind (the discipline the poll this replaced was written around, kept).
  const running =
    jobs.some((job) => isRunning(job)) || subagents.some((row) => row.running);
  useEffect(() => {
    if (!running) return;
    const timer = setInterval(() => setNow(Date.now()), TASK_PANE_TICK_MS);
    // AND ONE MEASUREMENT AT ONCE, so a row that has just appeared shows a duration now rather
    // than a second from now.
    setNow(Date.now());
    return () => clearInterval(timer);
  }, [running]);

  return { jobs, subagents, now };
}
