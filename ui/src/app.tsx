// The page: one AG-UI agent wired straight to the harness, presented by
// assistant-ui -- and since the parallel-sessions ticket, ONE RUNTIME PER
// SESSION.
//
// ------------------------------------------------------------------ the shape
//
// There used to be a single agent, a single `useAgUiRuntime` call, and therefore
// a single core -- one conversation, with switching implemented as "throw away
// what this core holds and refill it from the log". That is what made a run in
// flight unswitchable: the runtime's own `onSwitchToThread` clears the core
// BEFORE it asks the host for the new history, so a switch mid-run left the
// streaming frames landing in a repository that had been renamed -- the server
// writing session A's log while the page said B.
//
// The fix is structural rather than a better guard: every session that has been
// opened gets its OWN host component, its own agent, its own `useAgUiRuntime`,
// and therefore its own core. Nothing is ever refilled, so nothing can be
// orphaned. "Which session am I looking at" stops being a property of the runtime
// and becomes plain page state (`shown`), which is also what lets the sidebar
// stop receiving a `runtime` it only ever used to call `switchToThread` on.
//
// A host stays mounted once it exists -- including while it is not on screen --
// because its core is the only place its conversation lives: a run that is still
// streaming keeps streaming into it, and coming back to that session shows the
// same messages rather than a fresh rebuild. Its core is owned by a ref inside
// the hook, not by the DOM subtree, so a host that renders nothing still owns its
// run. That is exactly the property this ticket needs, and it is why the two
// `threadList` switching callbacks are gone: their effect was to apply external
// messages to the core that is now doing the streaming.
//
// ---------------------------------------------------------------- the history
//
// A host hydrates itself ONCE, when it mounts, through the runtime's `history`
// adapter -- which is what the adapter is for, and which runs exactly one load
// per core (`__internal_load`). That is the whole of the "first open rebuilds"
// rule: a session that already has a host never runs `rebuildThread` again, no
// matter how often it is shown. The judgement is the host's EXISTENCE, not the
// presence of a log.
//
// A session the client has just minted is hosted with `hydrate: false`, because
// there is no conversation under a brand-new id and asking the server to rebuild
// one would be asking it to find a file that is not there -- which
// `harness.edge.replay` refuses to invent, by design. The client knows that
// thread is empty; it just made the id up.
//
// The adapter's `append`/`update` are no-ops ON PURPOSE: the harness owns the log
// and appends every frame it serves, so this client has nothing to write back.
// The runtime calls them for messages it believes are persisted, and a no-op is
// the honest answer -- the alternative would be a second writer for a file the
// server already owns.
//
// A LOAD THAT FAILS LEAVES NO HOST. The server's own refusal -- a truncated or
// corrupt log, a stem naming two files -- arrives at `onError`; the host is
// dropped and the page falls back to the session it was on, so the sentence lands
// on the row that was clicked and clicking it again is a retry.
//
// -------------------------------------------------------------- what is where
//
// App owns three things: which session is on screen, which sessions have a live
// host, and one answer per session about whether it is running or parked. The
// first is `shown`, the second is the roster below, the third is reported back by
// each host (see SessionStatusReporter) and read by the sidebar, which no longer
// has a runtime to ask.
//
// The `components` prop is where this repo's own rendering of tool calls and
// reasoning enters the copied element -- see `components/message-parts.tsx`,
// which also carries the one deliberate difference from upstream's defaults
// (everything arrives collapsed). It is a module-level constant so the object
// identity survives re-renders.
import type { TFunction } from "i18next";

import { AssistantRuntimeProvider, useAuiState } from "@assistant-ui/react";
import { useAgUiInterrupts, useAgUiRuntime } from "@assistant-ui/react-ag-ui";
import { cn } from "cn";
import { useCallback, useEffect, useMemo, useRef, useState, type FC, type ReactNode } from "react";
import { useTranslation } from "react-i18next";

import { Thread } from "@/components/assistant-ui/elements/thread.aui";
import { HeldSessionContext, ThreadIdContext, type HeldSession } from "@/components/composer-chrome";
import { SessionRunContext } from "@/components/session-run-state";
// THE STOP THE COMPOSER DRAWS WHEN THE SERVER SAYS THIS CONVERSATION IS RUNNING (ticket
// 09): this host has the thread id the request has to name, which is the whole reason the
// element asks for it rather than drawing one of its own.
import { SessionRunStop } from "@/components/session-run-stop";
import { SubagentViewPanel } from "@/components/subagent-view";
import { SubagentViewContext, type RightPane, type SubagentView } from "@/components/subagent-view-context";
import { RightPaneOpenButton } from "@/components/right-pane-toggle";
import { TaskPane } from "@/components/task-pane";
import { ContextCards } from "@/components/context-card";
import { RecordNotice } from "@/components/record-notice";
import { readsOf, repositoryFrom } from "@/lib/thread-messages";
import { newId } from "@/lib/id";
import { TrajectoryView } from "@/components/trajectory-view";
import { TooltipProvider } from "@/components/ui/tooltip";
import {
  ApprovalBatchProvider,
  isParkedInterrupt,
} from "@/components/approval-gate";
import { Sidebar } from "@/components/sidebar";
import { SessionTitle } from "@/components/session-title";
import { firstUserText, titleOf } from "@/lib/session-title";
import { SidebarOpenButton, isWideWindow } from "@/components/sidebar-toggle";
import { THREAD_COMPONENTS } from "@/components/message-parts";
import { HarnessAgent } from "@/lib/agent";
import { imageAttachments } from "@/lib/attachments";
// BOTH HALVES OF THIS MERGE'S RECONCILIATION, and the two are not alternatives:
// `bindThread` is what a minted PROJECT session is registered with just before its first
// run, and `startTask` is the same for a task (with the id this page minted). Neither is
// called at click time -- see `pendingBinds` and `registerPending` below.
import { bindThread, startTask, type SidebarListing } from "@/lib/projects";
import {
  browserStorage,
  forgetSession,
  listedSession,
  rememberedSession,
  rememberSession,
} from "@/lib/session-memory";
import { AGENT_URL, rebuildThread, sofarThread } from "@/lib/threads";
import { pageThread, type WindowFrame } from "@/lib/feed";
import { subscribeMux } from "@/lib/mux";
import {
  aheadOf,
  aligned,
  applied,
  prepended,
  windowFrom,
  type Window,
  type WindowNotice,
} from "@/lib/window";
import { withHeldScroll } from "@/lib/window-scroll";
import { statusOf, type SessionStatus } from "@/lib/session-status";
import { type RecordHealth } from "@/lib/record-health";

/// HOW LONG TO WAIT BEFORE RE-OPENING A WINDOW WHOSE FEED CLOSED ON ITS OWN, in
/// milliseconds. NOT A POLL: while the connection is up nothing is asked at all, and
/// this is only the pause after a connection the server (or the network) ended -- a
/// deployment restarting, a proxy timing a long-lived response out. Opening the tail
/// page again and reconnecting from the entry this page already holds is the repair for
/// both a dropped connection and a missed push (`lib/window.ts`'s `aligned`); the delay
/// is what keeps a server that is DOWN from being asked in a tight loop.
const RECONNECT_MS = 1000;

// WHAT THE PAGE HANDS THE RUNTIME WHEN IT REBUILDS A CONVERSATION (`toThreadMessages` /
// `repositoryFrom`) AND HOW FAR ALONG IT IS (`readsOf`) now live in `lib/thread-messages.ts`:
// the status a rebuilt message is handed is the server's word over the AG-UI adapter's own
// guess, and that rule is pinned over literals by a suite that cannot import this file. See
// that module's head for the bug it exists for (a `bash` call in flight drawn 待审批).

/// One session's history, for the adapter that loads it exactly once. See the
/// header for why `hydrate` is a property of the session rather than something
/// derived here.
///
/// THE TRANSLATOR IS A PARAMETER, not a hook: this is not a component, and the one
/// sentence it can raise is `rebuildThread`'s fallback -- the `errors` face, which
/// the host that calls this already has (see `SessionHost`).
/// WHICH DOOR A HOST READS ITS CONVERSATION THROUGH, decided wherever the host is
/// created because it is a fact about WHY the session is being shown:
///
///   "none"    -- an id this client has just minted. There is no conversation under
///                it, so there is nothing to read and nothing to ask the server for.
///   "rebuild" -- a session opened from the sidebar: HAND IT OVER. That is the door
///                that closes a cut-off log off and names a corrupt one, and the
///                refusal belongs on the row that was clicked. IT FALLS THROUGH TO
///                "LOOK" WHEN THE SESSION IS RUNNING: a snapshot opens no feed, so a
///                page that opened a live conversation would have no server word for
///                the composer's gate and its Send would be refused 409 -- a live one
///                is watched instead (see `sessionHistory`).
///   "window"  -- the session this page was already in, landed in again (a reload).
///                LOOK AT IT, do not take it over: it may be in the middle of a run,
///                and looking must not write. What it gets is a WINDOW (ticket 06):
///                the tail page, then every entry as it lands, with "show earlier" for
///                the rest.
type HistoryRead = "none" | "rebuild" | "window";

/// READ A CONVERSATION THE WAY A RESTORED PAGE MUST: the read that does not write,
/// with the one fallback it needs.
///
/// `sofar` refuses exactly one log -- one that ends mid-run with nothing in the process
/// running it, i.e. a process that was killed -- and its sentence names the door that
/// repairs it (`rebuild`, which closes the run off). A page that landed here by itself
/// has nobody to relay that sentence to, so it FOLLOWS it: rebuild, and the
/// conversation comes back. Any other failure (a session that is gone, a server that
/// is not answering) fails here too and is reported as it is -- the same refusal a
/// `rebuild` door would have shown, because it is the same attempt.
///
/// THE WINDOW DOOR USES THIS FOR TWO THINGS AND NOT FOR ITS ENTRIES, which is worth
/// saying because it looks like the other door: the state it reports after a run
/// settles (the strip that says the conversation could not be saved -- see the effect
/// below), and the CUT-OFF LOG, which the window can also see coming (the page route
/// says `unfinished`) and which only `rebuild` repairs.
async function readSofar(threadId: string, t: TFunction<"errors">) {
  try {
    return await sofarThread(threadId, t);
  } catch {
    const rebuilt = await rebuildThread(threadId, t);
    return { ...rebuilt, state: "settled" as const };
  }
}

/// The read a host runs on mount, and what it reports back: `onRecord` carries the
/// record's health out with it (ADR 0002 decision 6), because it arrives on this read
/// and the column that says so is not the thing that made the request. On the window
/// door `onWindow` carries the WINDOW itself out, for the same reason and one more: the
/// host is the only thing that can follow it (a feed is a connection, and a connection
/// belongs to a component's lifetime).
///
/// THE RECORD IS REPORTED ON EVERY DOOR, including `rebuild` -- a conversation handed
/// over from a record the writer could not add to is exactly the case a reader needs
/// told, and it is the door a session is opened through.
///
/// `onWindow(null)` MEANS "THERE IS NO WINDOW TO FOLLOW", and it is the answer on the
/// one door here that has none: a log the window reports as `unfinished` is repaired by `rebuild`
/// (which closes the run off), the repair hands back the WHOLE conversation, and a
/// page that already holds all of it has nothing to follow and nothing in front.
function sessionHistory(
  threadId: string,
  read: HistoryRead,
  t: TFunction<"errors">,
  onRecord: (record: RecordHealth | null) => void,
  onWindow: (opened: Window | null) => void,
) {
  return {
    load: async () => {
      if (read === "none") return { messages: [] };
      if (read === "rebuild") {
        const rebuilt = await rebuildThread(threadId, t);
        onRecord(rebuilt.record ?? null);
        // A RUNNING CONVERSATION IS LOOKED AT, NOT HELD (ticket 04's other door).
        //
        // THIS DOOR HANDS OVER A SNAPSHOT AND OPENS NO FEED, so a page that opened a
        // RUNNING session from the sidebar -- every sidebar click is this door -- had no
        // server word to close the composer's gate with: `runState` stayed null, Send
        // looked available, and the only reply was the run edge's 409 (the bug reported
        // 2026-09-22). The server puts the state on this answer (`harness.edge.http`
        // `rebuild-post`) exactly so the client can tell, and the answer is to LOOK
        // instead: read the tail page and follow its feed, the same shape as the window
        // door below. The door a host came through is still the reason it was opened
        // (`HistoryRead`); what changes is that a live conversation is watched, and the
        // gate reopens on its own when the run settles.
        if (rebuilt.state === "running") {
          const page = await pageThread(threadId, t);
          onRecord(page.record ?? null);
          const opened = windowFrom(page);
          onWindow(opened);
          return repositoryFrom(
            opened.entries.map((entry) => entry.message),
            readsOf(page.state),
          );
        }
        return repositoryFrom(rebuilt.messages);
      }
      const page = await pageThread(threadId, t);
      onRecord(page.record ?? null);
      if (page.state === "unfinished") {
        // THE CUT-OFF LOG, WHICH THIS DOOR CAN SEE COMING: the page route says what the
        // record says, and the record's word for a log that stops mid-run is exactly
        // this. `rebuild` is the door that repairs it -- and the only one that does --
        // so the page follows the same sentence it follows from `sofar`'s refusal (see
        // `readSofar` above): hand the conversation over, and show it.
        const rebuilt = await rebuildThread(threadId, t);
        onRecord(rebuilt.record ?? null);
        onWindow(null);
        return repositoryFrom(rebuilt.messages, "settled");
      }
      const opened = windowFrom(page);
      onWindow(opened);
      return repositoryFrom(
        opened.entries.map((entry) => entry.message),
        readsOf(page.state),
      );
    },
    // No-ops: the harness owns the log (see the header).
    append: async () => {},
    update: async () => {},
  };
}

/// Reports ONE session's status up to the page, for the sidebar to draw.
///
/// It has to be a component of its own, rather than a hook called in SessionHost,
/// because both readings come from the runtime provider SessionHost RENDERS --
/// `App` is the component that renders the provider, so a hook reading the
/// assistant state in `App`'s body throws. The browser said so, for the old
/// single-runtime shape.
///
/// TWO REPORTS COME OUT OF IT, AND THEY ARE NOT THE SAME ANSWER (ticket 04 of
/// `.scratch/session-after-refresh`):
///
///   `onStatus`  -- the PAGE'S answer for this session, which is `lib/session-status`'s
///                  `statusOf`: this runtime's reading ORed with the server's own word
///                  (`serverState`, the window's `state`). The sidebar draws it, and a
///                  row has to light up for a conversation the server is answering even
///                  when this page is only watching it.
///   `onOwnRun`  -- the HOST'S own bookkeeping, which is the LOCAL reading ALONE. It is
///                  what `isOwnRun` answers, and that decides whether the window feed may
///                  import into this runtime: a run this page is only watching must keep
///                  importing, or the turn on screen would stop growing (see
///                  `useWindowFeed`). Folding the server's word in here would freeze the
///                  very conversation the reload was supposed to restore.
const SessionStatusReporter: FC<{
  threadId: string;
  /// THE SERVER'S OWN WORD FOR THIS SESSION (`statusOf`'s second half), straight off the
  /// window this host follows -- one of `running` / `parked` / `settled` / `unfinished`, or
  /// null for a host with no window to ask. NOT reduced to a boolean on the way in: which
  /// word it is, is the question `statusOf` answers.
  serverState: string | null;
  onStatus: (id: string, status: SessionStatus) => void;
  onOwnRun: (running: boolean) => void;
  onTitle: (id: string, title: string | null) => void;
  onForget: (id: string) => void;
}> = ({ threadId, serverState, onStatus, onOwnRun, onTitle, onForget }) => {
  const running = useAuiState((s) => s.thread.isRunning);
  // AND WHAT THIS CONVERSATION IS CALLED, read off the same runtime, one line up from
  // the status it is reported with. It is the SECOND source of a title rather than the
  // only one -- the store keeps a copy, written by the first run that arrived, and the
  // sidebar reads that for every session this page has never opened. This one exists
  // for the case the store cannot cover: the message just typed. `firstUserText`
  // answers a STRING (or null), which is the shape `useAuiState` needs -- a selector
  // answering an array or an object re-renders on every store change, because two
  // equal arrays are not the same array.
  const title = useAuiState((s) => firstUserText(s.thread.messages));
  // The parked reading: this session has stopped to ask a human. It is NOT
  // `running` -- a run that ends on an interrupt has `isRunning` false -- which
  // is exactly why the sidebar has two different things to say.
  const parked = useAgUiInterrupts().some(isParkedInterrupt);

  // AN EFFECT, NOT RENDER. Reporting upward during render is React's classic
  // mistake -- the value this host would read in that same render is not the one
  // it just reported. The page compares the two booleans, so a second report of
  // the same answer changes nothing and there is no loop to guard.
  useEffect(() => {
    onStatus(threadId, statusOf({ running, parked }, serverState));
  }, [threadId, running, parked, serverState, onStatus]);

  // THE HOST'S OWN READING, its own effect so it cannot be confused with the page's: see
  // the note above on why the two must not be merged.
  useEffect(() => {
    onOwnRun(running);
  }, [threadId, running, onOwnRun]);

  // ITS OWN EFFECT, for the reason the status one has its own: re-running the pair
  // together would report the status again every time a message arrives (the title
  // changes on the first one and then never), and the page compares the whole status
  // object before storing it.
  useEffect(() => {
    onTitle(threadId, title);
  }, [threadId, title, onTitle]);

  // AND FORGOTTEN ON UNMOUNT, so a host that goes away cannot leave its row lit
  // forever. Its own effect rather than a cleanup on the one above, which re-runs
  // on every status change and would blink the row off each time.
  useEffect(() => () => onForget(threadId), [threadId, onForget]);

  // A HOST THAT GOES AWAY TAKES ITS TITLE WITH IT, in the same cleanup: a title is
  // only ever as good as the runtime it came from, and leaving it behind would let a
  // page that dropped the session keep drawing words nobody can see the source of
  // (the store's copy takes over, which is the honest fallback).
  useEffect(() => () => onTitle(threadId, null), [threadId, onTitle]);

  return null;
};

/// WHAT THE COLUMN NEEDS TO DRAW A WINDOW'S TOP, reported up to the page the same way
/// the record's health is (`onRecord`): this host renders the column as its `children`,
/// so it cannot hand it a prop -- and the page is the component that does.
export type WindowControls = {
  hasMore: boolean;
  loading: boolean;
  onEarlier: () => void;
  notice: WindowNotice | null;
};

/// FOLLOW THE CONVERSATION THIS PAGE IS ONLY WATCHING (ticket 06), which is what
/// replaced ticket 03's poll of the record.
///
/// A POLL AND A FEED ARE NOT TWO SPEEDS OF THE SAME THING. The poll re-read the whole
/// conversation every 1200ms and imported it wholesale, so it had to guess when to stop
/// (the answer no longer saying `running`), it could not be used for a conversation whose
/// record was behind, and every tick was a full read whether anything had happened or
/// not. A feed is one connection: it is opened with the newest entry this page holds
/// (`since`) and the window that entry belongs to (`generation`), answered with the
/// entries that landed after it, and ENDED BY THE SERVER the moment the window is over.
/// Nothing is asked while nothing happens, and a reader parked on an old conversation
/// costs one idle connection.
///
/// WHAT THIS HOOK OWNS, in the order the rules apply:
///
///   the window    -- the tail page the read opened (`start`), then every frame, merged
///                    by `lib/window.ts`'s rules: append what continues, ALIGN when a
///                    frame does not continue from what we hold, REOPEN when the window
///                    is over, and SAY SO in every case that is not an append.
///   the import    -- every accepted change goes into this host's runtime, so the
///                    conversation on screen is the window and not the first read --
///                    EXCEPT while a run THIS PAGE is driving is in flight, for the
///                    reason the ticket-03 poll wrote down: the host streaming that run
///                    is ahead of the record and importing over it would draw the answer
///                    backwards. `readSofar`'s one read per run still reports the record
///                    afterwards, and the next frame (or the watcher's own read) puts the
///                    window back in step.
///   the repairs   -- "show earlier" one page at a time, with ONE page in the air
///                    (`loading`), anchored so the reader's place does not move.
///
/// IT IS A HOOK AND NOT A COMPONENT because it has to read the runtime the host creates
/// (`useAgUiRuntime`), and hooks cannot be called in a component's children. It runs in
/// the host's own body, after the runtime exists, and it takes the window the READ
/// opened through a ref rather than as an argument: the read is an adapter the runtime
/// calls when it mounts, which is after this body has run.
function useWindowFeed(args: {
  threadId: string;
  read: HistoryRead;
  t: TFunction<"errors">;
  runtime: ReturnType<typeof useAgUiRuntime>;
  /// The window the read opened, or null when it opened none (the repair door, the
  /// fresh-id door, the sidebar door).
  start: { current: Window | null };
  /// Bumped by the read when it has an answer, because a ref does not re-run an effect.
  started: number;
  onRecord: (record: RecordHealth | null) => void;
  /// Whether a run THIS PAGE is driving is in flight (`ownRun`), read through a ref for
  /// the same reason: this hook must not re-run when it flips.
  isOwnRun: () => boolean;
  /// THE SERVER'S OWN WORD FOR THIS SESSION'S RUN, every time the window it says it about
  /// changes: one of `running` / `parked` / `settled` / `unfinished`, or null while there
  /// is no window at all. It is the ONLY reading that can tell this page that a run
  /// somebody else's process started is going (ticket 04 of
  /// `.scratch/session-after-refresh`): the host's `thread.isRunning` is false after a
  /// reload, so without this the composer offered Send for a conversation the server was
  /// still answering and the answer was the run edge's 409.
  onState: (state: string | null) => void;
  onControls: (controls: WindowControls) => void;
}): void {
  const { threadId, read, t, runtime, start, started, onRecord, isOwnRun, onState, onControls } =
    args;

  /// WHAT THIS PAGE HOLDS, and the mirror of it that re-renders: the ref is what the
  /// frame handler reads (a frame can arrive while a render is in flight), the state is
  /// what the top of the column draws.
  const held = useRef<Window | null>(null);
  const [view, setView] = useState<Window | null>(null);
  const [loading, setLoading] = useState(false);
  const [notice, setNotice] = useState<WindowNotice | null>(null);

  /// THE CONNECTION, and the guards around it: `close` is the feed's own way to hang up,
  /// `alive` stops a fetch or a timer from touching a host that is gone, and the two
  /// in-flight flags keep two DIFFERENT promises.
  ///
  ///   `repairing` -- one align-or-reopen at a time. Two repairs racing would each read
  ///                  the window the other is replacing, and the loser's `commit` would
  ///                  put back entries the winner had just dropped.
  ///   `paging`    -- one page in front at a time, which is what the button's disabled
  ///                  state promises.
  ///
  /// THEY ARE NOT ONE FLAG: a reader clicking "show earlier" must not swallow the repair
  /// for a frame that skipped ahead (that would leave the window behind until something
  /// else happened to land), and a repair must not be spliced under a page that was
  /// fetched for the window it is replacing (see the `baseSeq` re-check in `earlier`).
  /// A page IS refused while a repair is in the air: the answer would be about a window
  /// that no longer exists.
  const close = useRef<(() => void) | null>(null);
  const alive = useRef(true);
  const repairing = useRef(false);
  const paging = useRef(false);
  const retry = useRef<ReturnType<typeof setTimeout> | null>(null);

  const importWindow = useCallback(
    (next: Window) => {
      if (isOwnRun()) return;
      const messages = next.entries.map((entry) => entry.message);
      runtime.thread.import(
        repositoryFrom(messages, readsOf(next.state)) as Parameters<typeof runtime.thread.import>[0],
      );
    },
    [runtime, isOwnRun],
  );

  /// ACCEPT A CHANGE TO THE WINDOW: the ref, the render, the runtime and the state it
  /// carries, in that order (the import reads the window it is given, never the ref -- a
  /// second frame arriving in the same tick must not be imported twice). `onState` is
  /// reported from HERE rather than from a render: it is the same moment the window moved,
  /// and a state that changed with no entry to show still has to reach the page (the feed
  /// sends a frame of its own for exactly that -- `harness.edge.http/stream-feed!`).
  const commit = useCallback(
    (next: Window) => {
      held.current = next;
      setView(next);
      onState(next.state);
      importWindow(next);
    },
    [importWindow, onState],
  );

  const controls = useCallback(
    (earlier: () => void): WindowControls => ({
      hasMore: view?.hasMore ?? false,
      loading,
      onEarlier: earlier,
      notice,
    }),
    [view, loading, notice],
  );

  /// REPLACE THE WINDOW WITH A FRESH ANSWER FROM THE SERVER, and say what had to be
  /// dropped. `frame` is a tail page or the feed's opening window; `aheadOf` is the
  /// honest check (see `lib/window.ts`): an entry this copy held inside the range the
  /// answer covers, that the answer does not have.
  const adopt = useCallback(
    (frame: WindowFrame) => {
      const before = held.current;
      if (before !== null) {
        const gone = aheadOf(before, frame);
        if (gone.length > 0) setNotice({ kind: "ahead", count: gone.length });
      }
      commit(windowFrom(frame));
    },
    [commit],
  );

  /// THE LOOP'S OWN VERBS, in a ref rather than in each other's dependency lists.
  ///
  /// `follow` repairs through `align` and `reopen`, and both of those reopen the feed
  /// through `follow` -- a cycle. Written as `useCallback`s that name each other, the
  /// cycle becomes a dependency cycle, and it is not a style problem here: the effect
  /// that OPENS the connection depends on `follow`, so a `follow` whose identity changed
  /// on every render would tear the connection down and build it again on every render.
  /// The ref holds the current verbs and the functions below reach each other through it.
  const verbs = useRef<{ align: () => void; reopen: () => void; earlier: () => void }>({
    align: () => {},
    reopen: () => {},
    earlier: () => {},
  });

  /// OPEN THE WINDOW'S DOWNLINK SUBSCRIPTION FROM WHERE THIS PAGE IS. The opening frame
  /// carries the conversation's
  /// state whatever it is now (ticket 06's server half), so a state that changed with no
  /// entry to show still reaches this host.
  const follow = useCallback(() => {
    const window = held.current;
    if (window === null) return;
    close.current = subscribeMux(
      threadId,
      { since: window.cursor, generation: window.generation },
      {
        onFrame: (frame) => {
          if (!alive.current) return;
          // THE RECORD'S HEALTH RIDES ON THESE FRAMES (ADR 0002 decision 6): a write
          // failure that starts mid-run has to reach whoever is looking, and since
          // ticket 06 this connection -- not a poll -- is what is running.
          if (frame.record !== undefined) onRecord(frame.record ?? null);
          const current = held.current;
          if (current === null) return;
          const { window: next, effect } = applied(current, frame);
          if (effect.kind === "align") {
            // A FRAME THAT DOES NOT CONTINUE FROM WHAT WE HOLD. Pull the tail and merge
            // it; the reader's place is kept, which is why this is not a reopen.
            verbs.current.align();
            return;
          }
          if (effect.kind === "reopen") {
            // WHY IT CLOSED (`end`'s reason, or the generation changing) is not a
            // sentence this page can word -- the notice says the honest thing either way:
            // the window was over and it has been opened again at the newest entries.
            verbs.current.reopen();
            return;
          }
          // A REBUILD IS SAID BEFORE IT IS DRAWN: the frame did not continue what this
          // copy held, so entries it was showing are not in the window any more, and the
          // count is what the sentence is about.
          if (effect.kind === "rebuilt") setNotice({ kind: "rebuilt", dropped: effect.dropped });
          // AND A CLEAN FRAME RETIRES WHATEVER WAS SAID. The strip reports a state of
          // affairs that has just ended -- the window is being followed again, from here
          // -- and a sentence that stayed for the session's lifetime would be chrome
          // about an event the reader has long since seen. (A no-op when there is nothing
          // on screen: React skips a `null` set over a `null`.)
          else setNotice(null);
          // BY IDENTITY: a frame that changed nothing hands back the window it was
          // given, and an import for it would be a re-render per keep-alive.
          if (next !== current) commit(next);
        },
        onClosed: () => {
          // THE CONNECTION WENT AWAY -- a restart, a proxy, a sleeper. Nothing is wrong
          // with the window; it is BEHIND, and the repair is the tail page plus a
          // reconnect from the newest entry this page holds.
          if (!alive.current) return;
          verbs.current.align();
        },
      },
    );
  }, [threadId, commit, onRecord]);

  /// THE TAIL PAGE, MERGED INTO WHAT WE HOLD (`aligned`): the repair for a connection
  /// that dropped, and for a frame that skipped ahead of us. It answers whether the
  /// conversation moved at all, which is what decides whether the feed needs reopening.
  const align = useCallback(async () => {
    if (repairing.current) return;
    repairing.current = true;
    try {
      const tail = await pageThread(threadId, t);
      if (!alive.current) return;
      if (tail.record !== undefined) onRecord(tail.record ?? null);
      const current = held.current;
      if (current === null) {
        adopt(tail);
      } else {
        const { window: next, effect } = aligned(current, tail);
        if (effect.kind === "rebuilt") setNotice({ kind: "rebuilt", dropped: effect.dropped });
        commit(next);
      }
      follow();
    } catch (error) {
      if (!alive.current) return;
      // THE READ ITSELF FAILED (the server is not answering). Say nothing yet: the
      // reconnect below tries again, and a notice that appears and clears on its own is
      // worse than the state of affairs it describes.
      retry.current = setTimeout(() => {
        if (alive.current) void align();
      }, RECONNECT_MS);
    } finally {
      repairing.current = false;
    }
  }, [threadId, t, onRecord, adopt, commit, follow]);

  /// OPEN THE WINDOW AGAIN, from the newest entries, and SAY SO. This is what `end` and
  /// a refusal get: the window this page was holding is over, so there is nothing to
  /// merge with -- and a copy that quietly swapped in a different conversation would be
  /// showing something nobody asked for.
  const reopen = useCallback(async () => {
    if (repairing.current) return;
    repairing.current = true;
    try {
      const tail = await pageThread(threadId, t);
      if (!alive.current) return;
      if (tail.record !== undefined) onRecord(tail.record ?? null);
      adopt(tail);
      // THE REOPEN IS SAID LAST, and it wins over the `ahead` count `adopt` may have
      // raised: both are true, and the one a reader has to know first is that the window
      // they were looking at is over. The strip has one slot; this is the sentence that
      // belongs in it.
      setNotice({ kind: "reopened" });
      follow();
    } catch (error) {
      if (alive.current) {
        // A REFUSAL WITH THE SERVER'S OWN SENTENCE, rather than a silent stall: if the
        // tail page cannot be read either, this page cannot show this conversation, and
        // that is worth saying.
        setNotice({ kind: "failed", message: error instanceof Error ? error.message : String(error) });
      }
    } finally {
      repairing.current = false;
    }
  }, [threadId, t, onRecord, adopt, follow]);

  /// "SHOW EARLIER": ONE PAGE IN FRONT, ANCHORED. The window does not move at its
  /// newest end, so the feed stays open and nothing is re-read -- and because the page
  /// arrives before it is drawn, the reader's place is held by the scroll helper
  /// (`lib/window-scroll.ts`).
  const earlier = useCallback(async () => {
    const asked = held.current;
    if (asked === null || !asked.hasMore || paging.current || repairing.current) return;
    paging.current = true;
    setLoading(true);
    try {
      const page = await pageThread(threadId, t, asked.baseSeq);
      if (!alive.current) return;
      if (page.record !== undefined) onRecord(page.record ?? null);
      // THE WINDOW MAY HAVE MOVED WHILE THE PAGE WAS IN FLIGHT -- a repair replaced it,
      // or another frame appended to it. The page is an answer about the OLDEST entry
      // this copy held, so it is only spliced in when that entry is still the oldest:
      // otherwise the window would be assembled out of two different moments, which is
      // exactly the hole every rule in `lib/window.ts` exists to refuse. Nothing is said
      // and nothing is lost -- the control is still there, and clicking again asks about
      // the window that exists now.
      const now = held.current;
      if (now === null || now.baseSeq !== asked.baseSeq) return;
      withHeldScroll(() => commit(prepended(now, page)));
    } catch (error) {
      if (alive.current) {
        setNotice({ kind: "failed", message: error instanceof Error ? error.message : String(error) });
      }
    } finally {
      paging.current = false;
      setLoading(false);
    }
  }, [threadId, t, onRecord, commit]);

  // THE VERBS THE LOOP REACHES FOR, kept current. An effect with no dependency list runs
  // after every render, which is exactly what this needs: the ref is not state, nothing
  // re-renders on it, and the functions it points at only read refs and stable callbacks.
  useEffect(() => {
    verbs.current = {
      align: () => void align(),
      reopen: () => void reopen(),
      earlier: () => void earlier(),
    };
  });

  // THE READ'S ANSWER ARRIVES HERE: the page it opened, or null for a door that opened
  // none (in which case there is no feed and no button, and this host is an ordinary
  // one-shot reader). The state the tail page came with is reported with it -- the read is
  // where a reload FIRST learns that the conversation it landed in is still being answered,
  // and it must not have to wait for the next frame to say so.
  useEffect(() => {
    // A WINDOW WAS OPENED, WHATEVER DOOR OPENED IT: the window door always does, and the
    // rebuild door does when it finds the session RUNNING (see `sessionHistory`). `none`
    // never does, and a rebuild that found a settled session does not either -- for those
    // `start` is null, and the guard below is what decides, not the door's name.
    if (read === "none") return undefined;
    const opened = start.current;
    if (opened === null) return undefined;
    held.current = opened;
    setView(opened);
    onState(opened.state);
    follow();
    return () => {
      close.current?.();
      close.current = null;
    };
  }, [read, started, start, follow, onState]);

  // THE HOST IS GONE: hang up, and stop any timer that would reconnect for it. Without
  // this a page that navigated away keeps a request loop alive behind it.
  useEffect(() => {
    alive.current = true;
    return () => {
      alive.current = false;
      if (retry.current !== null) clearTimeout(retry.current);
      close.current?.();
      close.current = null;
    };
  }, []);

  // THE TOP OF THE COLUMN, out to the page (which draws the column as this host's
  // `children` and so cannot be handed a prop).
  useEffect(() => {
    onControls(controls(() => verbs.current.earlier()));
  }, [onControls, controls]);
}

/// One session, alive. It renders the runtime provider and -- when it is the
/// session on screen -- the column; otherwise it renders nothing and keeps
/// owning its run.
const SessionHost: FC<{
  threadId: string;
  read: HistoryRead;
  visible: boolean;
  onStatus: (id: string, status: SessionStatus) => void;
  onTitle: (id: string, title: string | null) => void;
  onForget: (id: string) => void;
  onError: (id: string, message: string) => void;
  /// WHAT THIS SESSION'S RECORD LOOKS LIKE, reported to the page rather than kept
  /// here: the sentence is drawn by the column, which this host renders as
  /// `children` and therefore cannot hand a prop to. `null` is the ordinary answer
  /// and means the record has nothing to say.
  onRecord: (id: string, record: RecordHealth | null) => void;
  /// AND THE SAME THING FOR THE WINDOW (ticket 06): whether there is more in front,
  /// whether a page is in flight, how to ask for one, and what happened to the window.
  /// A session that is not being followed (`read` is not "window", or its log had to be
  /// repaired) reports `null`, and the column draws no window top at all.
  onWindow: (id: string, controls: WindowControls | null) => void;
  /// AND THE ONE THING A RUN NEEDS SAID BEFORE IT GOES OUT (this merge's
  /// reconciliation): the id this host speaks for, so the page can register it with the
  /// server if it was minted here and has never been written. The agent below calls this
  /// on every run request; the page decides whether this is that id's first
  /// (`registerPending`, which is a no-op for every session the server already knows).
  /// A host is therefore the only thing that can hand this over: the id lives here, and
  /// so does the moment the request is built.
  onReady: (id: string) => Promise<void>;
  children: ReactNode;
}> = ({ threadId, read, visible, onStatus, onTitle, onForget, onError, onRecord, onWindow, onReady, children }) => {
  // The agent is built ONCE for this host and owns this session's id for the
  // host's whole life. Rebuilding it would throw the thread away mid-run -- the
  // same reason the old single-agent memo had an empty dependency list, paid per
  // session now instead of once for the page.
  //
  // AND IT CARRIES `ready`: the one hook the client library has no seam for, which is
  // what lets a session this page MINTED exist on the server by the time its first run
  // request arrives (the run edge refuses an unknown id). It is the page's callback, not
  // this host's state -- the pending directory lives up there -- so the memo depends on
  // its identity, and `registerPending` is a `useCallback` with stable dependencies.
  const agent = useMemo(() => {
    // THE DOWNLINK IS WHERE THIS PAGE'S RUN FRAMES COME FROM (ADR 0004): the POST answers an
    // ack and the socket carries the events, so the sender and every watcher read one stream.
    const created = new HarnessAgent({ url: AGENT_URL, ready: onReady });
    created.threadId = threadId;
    return created;
  }, [threadId, onReady]);

  // A TRANSLATION HOOK IS NOT AN ASSISTANT HOOK, and the difference matters here:
  // the runtime-state hooks (`useAuiState` and friends) throw in this body, because
  // this is the component that RENDERS the runtime provider -- but this one reads
  // i18next's state, which sits above the whole page and has nothing to do with the
  // runtime. See `SessionStatusReporter` for the readings that had to move out.
  const { t: tErrors } = useTranslation("errors");

  // THIS HOST'S GATE (ticket 05). One session stopping to ask a human must not
  // close another session's composer, and `isSendDisabled` is an option of THIS
  // hook -- so the boolean is host state, fed by this host's own gate below.
  const [gateOpen, setGateOpen] = useState(false);

  // THE WINDOW THE READ OPENS, handed from the history adapter to `useWindowFeed` below
  // through a ref plus a counter: the adapter runs when the RUNTIME mounts (after this
  // body has run), so an argument cannot carry it -- and a ref alone would not re-run
  // the effect that follows it.
  const windowStart = useRef<Window | null>(null);
  const [windowStarted, setWindowStarted] = useState(0);
  /// WHETHER THIS HOST HOLDS A WINDOW AT ALL, which is what decides who reports a write
  /// failure: a window is a connection, and the feed carries the record's health on every
  /// frame (`harness.edge.http/window-frame`), so the one-read-per-run below is only
  /// needed on the doors that opened no window -- a session this page just minted, or one
  /// whose log had to be repaired.
  const following = useRef(false);
  const onWindowRead = useCallback((opened: Window | null) => {
    following.current = opened !== null;
    if (opened === null) {
      // NO WINDOW TO FOLLOW: the read took the `rebuild` door (the sidebar's, or the
      // repair a cut-off log needs), which hands over the whole conversation. The page
      // is told, so the column draws no window top for this session.
      onWindow(threadId, null);
      return;
    }
    windowStart.current = opened;
    setWindowStarted((count) => count + 1);
  }, [threadId, onWindow]);

  // The record's health, out to the page: nothing in this host's effects re-runs on it,
  // and the page's own state is what decides whether the sentence is on screen.
  const reportRecord = useCallback(
    (record: RecordHealth | null) => onRecord(threadId, record),
    [threadId, onRecord],
  );

  // WHETHER A RUN THIS HOST DROVE IS IN FLIGHT, and whether one ever was. Taken from
  // the same reading the page's registry gets (`SessionStatusReporter` below) -- THE LOCAL
  // HALF OF IT, which is the point: this is "a run of MY OWN agent", and it decides whether
  // the window feed may import into this runtime (ticket 04 of `.scratch/session-after-refresh`).
  //
  // A REF ALONGSIDE THE STATE, because "was there ever a run" is not a thing to
  // re-render for -- it only decides whether the effect below has anything to ask
  // about -- while `ownRun` IS state, because flipping it is what runs the effect.
  const ranSomething = useRef(false);
  const [ownRun, setOwnRun] = useState(false);
  const ownRunNow = useRef(false);
  const reportOwnRun = useCallback((running: boolean) => {
    if (running) ranSomething.current = true;
    ownRunNow.current = running;
    setOwnRun(running);
  }, []);

  /// WHAT THE SERVER SAYS THIS SESSION IS DOING, as the window last reported it: `running`
  /// while a run of this conversation is in flight in the harness process -- WHETHER OR NOT
  /// THIS PAGE STARTED IT. It is state here rather than inside `useWindowFeed` because two
  /// things outside that hook need it, and neither can read a hook's own state: the
  /// composer's gate (`isSendDisabled` below, which is a runtime OPTION and so has to be
  /// known in this body) and the STOP the composer draws when a run of this conversation
  /// is going (through `SessionRunContext` and the `ComposerStop` component below, both of
  /// which reach it from here).
  const [runState, setRunState] = useState<string | null>(null);

  const history = useMemo(
    () => sessionHistory(threadId, read, tErrors, reportRecord, onWindowRead),
    [threadId, read, tErrors, reportRecord, onWindowRead],
  );

  const runtime = useAgUiRuntime({
    agent,
    // THE COMPOSER'S GATE, and there are two reasons for it to be closed, so it is an OR
    // (ticket 04 of `.scratch/session-after-refresh`):
    //
    //   `gateOpen`  -- this session has stopped to ask a human (this host's own approval
    //                  batch). A message sent while a gate is open is refused by the runtime
    //                  SILENTLY (see `components/approval-gate.tsx`), so the door has to be
    //                  shut rather than the message swallowed.
    //   `runState`  -- THE SERVER IS STILL ANSWERING THIS CONVERSATION. The other reading
    //                  (`thread.isRunning`) is false for a run this page did not start, so
    //                  without this a page that reloaded into a running session offered Send
    //                  and the only reply was the run edge's 409 ("this session already has a
    //                  run in this process").
    //
    // IT IS `running` OR `parked` NOW, and ticket 06 is why `parked` is included: a parked
    // run has ENDED (its interrupt is its terminal), but the CARD that answers it comes back
    // on a rebuilt conversation (`toThreadMessages` above keeps the status that carries it),
    // so the composer can be shut without shutting the only door -- the way through is that
    // card. `unfinished` is still not included: a process that died mid-run has no card to
    // press. See `lib/session-status.ts`'s `statusOf`.
    isSendDisabled: gateOpen || runState === "running" || runState === "parked",
    adapters: {
      // IMAGES IN THE COMPOSER, and this one line is what enables them -- see
      // lib/attachments.ts: `capabilities.attachments` is `!!adapters.attachments`,
      // and paste, drop and `+` all consult that flag before doing anything.
      attachments: imageAttachments,
      // Hydration, once per mount. See the header.
      history,
      // ONLY the id: the two switching callbacks are gone (ticket 02). Their
      // effect was to clear and refill the core that is now doing the streaming,
      // and every caller of `runtime.threads.switchToThread` has been rerouted to
      // "show this session" on the page instead.
      threadList: { threadId },
    },
    onError: (error) => {
      // A CANCELLATION IS NOT A HOST FAILURE (ticket 09 of `.scratch/session-after-refresh`).
      //
      // WHEN THIS PAGE PRESSES STOP, the server ends the run and its `code: "stopped"` terminal
      // reaches `lib/agent.ts`, which turns it into the library's cancellation channel
      // (`onRunFailed` with an `AbortError`). The library then dispatches `RUN_CANCELLED` -- the
      // turn is drawn "Cancelled" -- BUT it also hands the same error to this callback first,
      // and the runtime only suppresses that when its OWN controller was aborted (which it was
      // not: the stop was a request to the server, not a local abort). Left alone, this page
      // would set `openErrors[threadId]` for a run that ended exactly as somebody asked, and
      // the whole session column would be dropped for it.
      //
      // SO THE NAME IS THE TEST, exactly as it is on the way in (`asAbort`): an error the
      // client renamed to `AbortError` is a run that ended because somebody stopped it, and
      // there is no host failure to report.
      if (error.name === "AbortError") return;
      onError(threadId, error.message);
    },
  });

  /// ASK HOW THE RECORD IS DOING ONCE A RUN THIS PAGE DROVE HAS ENDED.
  ///
  /// THE HOLE THIS FILLS IS THE ONE THE BROWSER WALKTHROUGH FOUND (2026-09-21): the
  /// read that opens a session answers BEFORE the run exists, and the poll below only
  /// runs for a session this page is WATCHING -- so a write failure during a run
  /// somebody is driving themselves reached nobody, and the conversation went on
  /// unsaved in silence. That is the one thing ADR 0002 decision 6 refuses.
  ///
  /// THE SAME READ THE MOUNT USES, fallback included, and the fallback is not an
  /// accident: a degraded record is exactly when the log can end mid-run, and
  /// `readSofar` follows its refusal into the rebuild that closes the run off -- which
  /// is what makes the conversation readable again AND what reports the health. There
  /// is no new endpoint and no new read path; this is one read per run.
  ///
  /// IT ASKS ONCE THE RUN IS OVER, AND NEVER WHILE ONE IS GOING: this host is the one
  /// streaming that run, and a record read mid-run LAGS the live conversation -- importing
  /// it then would draw the answer backwards. A terminal changes that: the record now says
  /// everything the stream did, so its reading is the conversation, and importing it is
  /// what puts an INJECTED MESSAGE in its own column (ticket 05 of
  /// `.scratch/session-opening` -- the adapter lands a `CUSTOM` card on the message being
  /// streamed, because it drops the frame's `messageId`, and only the record has the
  /// message the card belongs to).
  ///
  /// IT REPORTS, AND NOTHING ELSE -- no `import`, deliberately. The copy of the
  /// conversation this run wrote reaches the page ON THE RUN ITSELF now: the edge sends a
  /// `MESSAGES_SNAPSHOT` with it (`harness.edge.ag_ui/conversation-snapshot`), so the
  /// adapter places an injected message in the column the record puts it in while the run
  /// is still streaming, instead of this effect re-importing the reading afterwards. The
  /// owner's rule is the reason: the backend decides what a client is shown, and the
  /// client draws it -- so a run that writes part of the conversation hands that part
  /// over as messages, not as cards the caller has to go and fetch.
  ///
  /// AND IT IS ONLY FOR THE DOORS THAT OPENED NO WINDOW (ticket 06). A host following
  /// one is told about a write failure by the FEED -- every frame carries the record's
  /// health -- so asking again here would be one read per run that says what the
  /// connection already said. The doors without a window (a session this page just
  /// minted, one handed over from the sidebar, a log that had to be repaired) have no
  /// connection to be told on, and this is their one read.
  useEffect(() => {
    if (ownRun || !ranSomething.current || following.current) return undefined;
    ranSomething.current = false;
    let cancelled = false;
    void (async () => {
      try {
        const answer = await readSofar(threadId, tErrors);
        if (cancelled) return;
        reportRecord(answer.record ?? null);
      } catch {
        // The read failed -- a session that is gone, a harness that is not answering.
        // Nothing to say about the record, and the conversation on screen stays as it
        // was: the same reasoning the poll below writes down.
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [ownRun, threadId, tErrors, reportRecord, runtime]);

  /// THE PAGE'S COPY OF THIS SESSION'S WINDOW (ticket 06), for the three things it
  /// draws and one it says: whether there is more in front, whether a page is in flight,
  /// how to ask for one, and what happened to the window when the answer was not an
  /// append. `null` means this host is not following one, and the column draws nothing.
  const reportControls = useCallback(
    (next: WindowControls) => onWindow(threadId, next),
    [threadId, onWindow],
  );

  // AND FOLLOW IT. This is what replaced the poll that used to live here (ticket 03's
  // `sofar` every 1200ms): the same problem -- a run belongs to the PROCESS, so a page
  // that reloaded into a conversation somebody is still answering has no stream to
  // attach to -- answered with one connection instead of a full read per tick. The hook
  // above carries the whole argument.
  const isOwnRun = useCallback(() => ownRunNow.current, []);
  useWindowFeed({
    threadId,
    read,
    t: tErrors,
    runtime,
    start: windowStart,
    started: windowStarted,
    onRecord: reportRecord,
    isOwnRun,
    onState: setRunState,
    onControls: reportControls,
  });

  return (
    // THE SERVER'S WORD, HANDED TO THE COMPOSER. It is a context rather than a prop
    // because the composer is INSIDE the copied element (`<Thread/>`), which this host
    // renders as `children` and so cannot hand anything to -- the same reason
    // `components/composer-chrome.tsx` takes the thread id through one.
    //
    // IT WRAPS THE PROVIDER RATHER THAN SITTING INSIDE IT, deliberately: which run this
    // session has going is not a fact about the runtime (that is exactly what the bug was),
    // and nothing between these two lines reads it.
    <SessionRunContext.Provider value={runState}>
      <AssistantRuntimeProvider runtime={runtime}>
        <SessionStatusReporter
          threadId={threadId}
          // THE TWO READINGS, REPORTED SEPARATELY: `onStatus` carries what the PAGE draws --
          // this runtime's own run ORed with the server's word, by `statusOf` in the
          // reporter -- while `onOwnRun` keeps the local half here. See the reporter's own
          // header for why they must not be merged.
          serverState={runState}
          onStatus={onStatus}
          onOwnRun={reportOwnRun}
          // AND THE TITLE STRAIGHT THROUGH (the brand-header side): `liveTitles` is what a
          // row draws for a session the store has not listed yet, and there is no host-side
          // reading of it to keep.
          onTitle={onTitle}
          onForget={onForget}
        />
        {/* Per host, and BELOW this host's provider because it reads ITS pending
            interrupts. The reason the composer closes at all is in
            `components/approval-gate.tsx`: a message sent while a gate is open is
            refused by the runtime and the refusal is silent. */}
        <ApprovalBatchProvider onHoldChange={setGateOpen}>
          {visible ? children : null}
        </ApprovalBatchProvider>
      </AssistantRuntimeProvider>
    </SessionRunContext.Provider>
  );
};

/// One host's column: the view switch, then the conversation or the trajectory.
/// A component rather than inline JSX so the page does not re-create it for every
/// host on every render.
const SessionColumn: FC<{
  threadId: string;
  view: "conversation" | "trajectory";
  onView: (view: "conversation" | "trajectory") => void;
  /// WHETHER THE FLOATING CONTROL IS SITTING IN THIS BAR'S LEADING CORNER. The
  /// fold button is drawn by the page, absolutely positioned over this column
  /// (`components/sidebar-toggle.tsx`), so the bar it lands on has to make room for
  /// it -- and it is a prop rather than a fixed leading pad because the room is only
  /// needed while the control is there. Every host gets it: they all draw the same
  /// bar, and only the one on screen is what a person is looking at.
  folded: boolean;
  /// THE RECORD'S HEALTH FOR THIS SESSION, or null when it has nothing to say. It is
  /// a prop from the page rather than something this column reads because the fact
  /// arrives on the HOST's read (see `SessionHost`), which renders this column as
  /// `children` and so cannot hand it anything.
  record: RecordHealth | null;
  /// AND THE WINDOW'S TOP FOR THIS SESSION (ticket 06), from the same place and for the
  /// same reason: `null` when this host is not following a window at all, which is every
  /// session that was opened from the sidebar or whose log had to be repaired.
  window: WindowControls | null;
  /// THE PAGE'S OWN PENDING BINDINGS, BY SESSION ID -- the map the first run registers
  /// from (`registerPending`), which is where a minted session's directory lives until
  /// somebody sends. Handed down so the composer can tell the two kinds of session
  /// apart; see `heldSession`.
  binds: Map<string, string | null>;
}> = ({ threadId, view, onView, folded, record, window, binds }) => {
  const { t } = useTranslation();
  /// THE COMPOSER'S STOP, AS A COMPONENT THE ELEMENT CAN DRAW (ticket 09 of
  /// `.scratch/session-after-refresh`): it carries THIS session's id, which is what the
  /// request that stops a run has to name, and it holds its own press-state
  /// (`components/session-run-stop.tsx`). Memoized on the id so this column's other
  /// re-renders do not remount the button -- a state frame arrives on every change, and a
  /// fresh component type each time would lose the "pressing" state mid-request.
  const composerStop = useMemo(
    () => () => <SessionRunStop threadId={threadId} />,
    [threadId],
  );
  return (
    // The composer's chrome needs to know which session it is configuring -- the
    // model override and the branch are both per-session.
    <ThreadIdContext.Provider value={threadId}>
      {/* WHAT THE PAGE IS HOLDING FOR THIS SESSION, if it is holding anything at all:
          the composer's directory picker has to tell a session that exists in this home
          from one this page minted a moment ago, because only the first of the two may
          be written to. NESTED INSIDE the id, because it is the same fact -- this
          session's -- and the same bar reads both. See `heldSession`. */}
      <HeldSessionContext.Provider value={heldSession(threadId, binds)}>
      <div className="flex h-full min-h-0 min-w-0 flex-col">
        {/* The switch sits ABOVE the column. The trajectory reads the run's own
            state for its refetch trigger, and it does that INSIDE the runtime
            provider -- from its own body, not from here: a hook reading the
            assistant state in this body throws. The browser said so. */}
        <div
          data-slot="view-switch"
          // ONE CLASS STRING, ONE ADDITION, built rather than written twice -- `cn` is the
          // same merge the copied shadcn primitives use, so there is no second place for
          // the bar's own style to drift out of step with this one.
          className={cn(
            // `h-12` IS THE DEMO'S `3rem` ROW, and it is a HEIGHT rather than padding
            // because the sidebar's brand row is the same height: the two `border-b`
            // lines then land on ONE y, which is what makes the divider read as a single
            // line across the app rather than as two lines at two heights.
            "flex h-12 shrink-0 flex-col justify-center border-b border-border px-3",
            // `ps-12` CLEARS THE FLOATING CONTROL BY ITS BOX AND NOT BY ITS TEXT: 8px of
            // inset plus the button's 32px puts the button's trailing edge at 40, and the
            // tab row below is pulled 8px back (`-ms-2`, to line its first word up with the
            // title's) -- so the inset that clears the button *for the tabs* is 40 + 8 = 48.
            // `ps-11` was enough while the bar was one line of text and is not any more:
            // it left the tab row's box 4px under the button, where a click meant for the
            // view switch would open the sidebar instead. A stylesheet cannot see any of
            // this, so the numbers that produce it sit next to the class they clear.
            //
            // `lg:ps-3` IS THE OTHER HALF OF THE SAME FACT: there is no floating button on a
            // wide window (a folded column is the RAIL there, and it takes its own 48px out
            // of the row), so the inset that exists to clear that button must not survive
            // into a window that has none -- the bar's own `px-3` is the whole story there.
            folded && "ps-12 lg:ps-3",
          )}
        >
          {/* TWO LINES IN ONE 48px BLOCK: what this session IS on top, and which view of
              it you are looking at underneath -- the title is a NAME, the switch is a
              CONTROL, and putting them on one line made the name compete with two buttons
              for the same strip of space. The title comes from the conversation itself
              (`components/session-title.tsx`, which also writes the browser tab). It is
              rendered INSIDE this host's runtime provider (this component is that
              provider's child), which is what lets it read the messages with nothing
              plumbed through App. */}
          <SessionTitle />
          {/* `-ms-2` PUTS THE FIRST TAB'S WORD UNDER THE TITLE'S FIRST LETTER: a tab carries
              `px-2` of its own, so without it the row below would read as indented against
              the row above. The demo takes the same 8px back off its header's first control
              (`-ms-1.5`) for the same reason -- there it is the open button, here a tab. */}
          <div
            data-slot="view-switch-tabs"
            className="-ms-2 flex shrink-0 items-center gap-1"
          >
            {(
              [
                ["conversation", "view.conversation"],
                ["trajectory", "view.trajectory"],
              ] as const
            ).map(([key, label]) => (
              <button
                key={key}
                type="button"
                data-slot="view-switch-tab"
                data-view={key}
                aria-pressed={view === key}
                onClick={() => onView(key)}
                className={
                  view === key
                    ? "rounded-md bg-muted px-2 py-0.5 text-xs text-foreground"
                    : "rounded-md px-2 py-0.5 text-xs text-muted-foreground hover:text-foreground"
                }
              >
                {t(label)}
              </button>
            ))}
          </div>
        </div>
        {/* THE CONVERSATION THAT COULD NOT BE SAVED (ADR 0002 decision 6, ticket 02).
            Above the conversation and below the tab strip, so it is read before the
            text it is about -- and it is a STRIP rather than a floating toast because
            it stays true until somebody acts on it. The sentence itself lives in
            `components/record-notice.tsx`, where a test run can render it. */}
        <RecordNotice record={record} />
        <div className="min-h-0 flex-1">
          {/* THE INJECTION CARD'S REGISTRATION, and it is a rendering: a data part's
              renderer is registered by MOUNTING the component `makeAssistantDataUI`
              answers, so this line is the whole of "the conversation column knows how
              to draw an injected context". It draws nothing itself. It sits INSIDE
              this provider (`AssistantRuntimeProvider` is above), because a
              registration is scoped to the runtime that resolves the parts. */}
          <ContextCards />
          {view === "conversation" ? (
            <Thread
              components={{ ...THREAD_COMPONENTS, ComposerStop: composerStop }}
              window={window}
            />
          ) : (
            <TrajectoryView threadId={threadId} />
          )}
        </div>
      </div>
      </HeldSessionContext.Provider>
    </ThreadIdContext.Provider>
  );
};

/// WHAT THE PAGE IS HOLDING FOR ONE SESSION, in the shape the composer reads
/// (`HeldSessionContext`), or null for a session this home can answer for.
///
/// THE TEST IS AN ENTRY IN `binds`, the very map the first run registers from: it holds
/// the sessions this page MINTED and has not registered -- the ones nothing in this home
/// has been asked to keep yet, and the ones a directory pick may therefore only remember.
///
/// BUILT PER RENDER rather than kept, deliberately: `dir` is read out of a map the pick
/// MUTATES (a `remember` writes the directory the first send will bind, and the run reads
/// it from that same map), so a value held across renders would be the
/// snapshot-versus-fact mistake this repo keeps out of its caches. It costs one lookup.
function heldSession(threadId: string, binds: Map<string, string | null>): HeldSession | null {
  if (!binds.has(threadId)) return null;
  return {
    dir: binds.get(threadId) ?? null,
    // NOTHING IS POSTED, NOTHING IS WRITTEN, AND NOTHING CAN FAIL: the first send is
    // what binds a session (`registerPending`), and this is only the directory it will
    // bind with.
    remember: (dir: string) => {
      binds.set(threadId, dir);
    },
  };
}

/// One live host, and how many times it has been mounted. `attempt` is bumped
/// when a session whose history would not load is opened again, and it is part of
/// the React key -- so "open it again" is a genuine remount (and a genuine second
/// load) rather than a no-op on a host that is already there.
///
/// `read` is decided where the host is CREATED and read once, when it mounts -- see
/// `HistoryRead`. It is not state the host can change later: which door a
/// conversation was read through is a fact about why it was opened.
type HostSpec = { id: string; read: HistoryRead; attempt: number };

/// Which session is on screen, and every session that has a live host.
type Roster = { shown: string; live: readonly HostSpec[] };

const dropKey = <T,>(record: Record<string, T>, key: string): Record<string, T> => {
  if (record[key] === undefined) return record;
  const next = { ...record };
  delete next[key];
  return next;
};

export function App() {
  // The failures this page raises itself -- today, one: the registration a minted session
  // gets immediately before its first run (see `registerPending`), which the server can
  // refuse. The sentence comes from the `errors` catalog, like every other refusal the
  // interface did not get from the server.
  const { t: tErrors } = useTranslation("errors");
  /// THE SESSIONS THIS PAGE MINTED AND THE STORE HAS NOT ANSWERED FOR YET -- the only
  /// sessions a live title is allowed to name (`reportTitle` below), and the reason is
  /// main's window: a host's `s.thread.messages` is NOT the conversation. A session
  /// served as a window holds a TAIL PAGE (ADR 0003, `docs/architecture/client.md`), so
  /// the first user message in it is a message from the middle of a long conversation --
  /// and speaking that as the row's name would RENAME a session merely because somebody
  /// opened it. For a session this page minted the window is EMPTY, so its first user
  /// message really is the conversation's first, and that is the one case the live title
  /// exists for: the message just typed, before the listing has caught up.
  ///
  /// AN ID LEAVES THIS SET WHEN A LISTING NAMES IT (`forgetListedTitles`, from `onListed`):
  /// at that moment the store has the row and its own `sessions.title`, which is the
  /// authority on names for every session -- the same "one source of truth per fact" the
  /// sidebar's rows are built on.
  const minted = useRef<Set<string>>(new Set());
  /// THE SESSION THIS PAGE MINTED THAT THE SERVER HAS NOT BEEN TOLD ABOUT YET, keyed by
  /// session id, holding the directory it is waiting to belong to -- `null` for a task.
  ///
  /// A REF RATHER THAN STATE, and both halves of that are deliberate: the value is read by
  /// a callback that runs while a request is being built (the agent's `ready`, which must
  /// not re-render the page to find it), and writing it must not re-render anything either,
  /// because nothing on screen depends on it -- the row it would belong to does not exist
  /// yet. This is what LAZY CREATION costs: the sidebar used to bind a new session
  /// immediately (`POST /api/project`), so the store knew where it lived before anybody
  /// had typed; now the directory waits here until there is a run to attach it to.
  ///
  /// TASKS ARE IN HERE TOO, WITH `null`: the same lazy creation mints a task's id, and the
  /// same rule makes it need registering -- main's run edge refuses a session this home has
  /// never been asked to keep, project or not -- through `startTask` rather than a bind.
  /// An EMPTY MAP is the ordinary state of this page: every session opened from the sidebar,
  /// and every session the store already lists, is one the server knows.
  ///
  /// IT IS DECLARED HERE, WITH THE MINT, rather than next to `registerPending` below, for
  /// the one entry that is not written by `showFresh`: THE FIRST SESSION, minted by the
  /// `useState` initializer a few lines down. It is a task, so it is recorded as `null`,
  /// and leaving it out would refuse the very first message of a first-ever visit -- the
  /// edge's rule is about what the store has been asked to keep, not about where the id
  /// came from.
  const pendingBinds = useRef<Map<string, string | null>>(new Map());
  // THE ROSTER. The first session is minted here and hosted EMPTY: a brand-new
  // id has no log, and the server refuses to invent a conversation for one.
  //
  // THE MINT IS THE PAGE'S, which is the brand-header half of this merge and the owner's
  // rule (点击新增不立刻会话，发送才新建): an id is a name this page gives a conversation
  // before anybody has typed, and nothing is written to the store until the first SEND --
  // `registerPending` is what carries the id across at that moment. The other half is
  // main's: a run aimed at an id this home has never been asked to keep is refused
  // (`refuse-unknown-session!`), which is exactly why that registration exists rather
  // than being an idle write. Asking the server for the id here would be the client
  // giving up its own name to avoid one POST per session -- and it would put the mint
  // back at page load, which is the write this feature removed from the click.
  const [roster, setRoster] = useState<Roster>(() => {
    const id = newId();
    // THE FIRST SESSION IS MINTED HERE TOO, so it is registered (as a task: nothing is
    // pending but `null`) and live-titled like any other. A first visit sends its first
    // message into exactly this id, and the row that appears a moment later is the one
    // the walkthrough reads the name off.
    pendingBinds.current.set(id, null);
    minted.current.add(id);
    return { shown: id, live: [{ id, read: "none", attempt: 0 }] };
  });
  // WHAT THE RIGHT-HAND COLUMN IS SHOWING, AND WHETHER IT IS THERE AT ALL
  // (`.scratch/right-pane-tasks`, decision 1). ONE value with three shapes -- see
  // `RightPane` in `components/subagent-view-context.ts`: the TASK VIEW the switch opens, one
  // delegation's MIRROR the transcript's `agent` card opens, or `null` for a closed column.
  // A SINGLE VALUE rather than an `open` bit plus a `which`: opening the column IS choosing
  // the task view, and there is no moment at which the column is open with nothing to show.
  //
  // THE MIRROR HALF IS UNCHANGED (ticket 05): one delegation at a time, opening another
  // REPLACES this one, and the render below remounts the panel on a switch.
  //
  // IT LIVES HERE, ON THE PAGE, because the two things that have to reach each other are the
  // transcript's `agent` card (which opens the mirror) and the column (which draws either
  // state) -- and the card is rendered by a `SessionHost` that the column is `children` of,
  // so neither can hand the other a prop.
  const [rightPane, setRightPane] = useState<RightPane>(null);
  /// WHAT THE `agent` CARD IS HANDED: it sets one of the three shapes, and it is the mirror.
  ///
  /// MEMOIZED BECAUSE IT IS A CONTEXT VALUE, not because this page is shy of callbacks: it is
  /// the one prop every tool card in every transcript reads (`useOpenSubagentView`), and a fresh
  /// function per render would re-render all of them on every status or title this page holds.
  const openMirror = useCallback(
    (view: SubagentView) => setRightPane({ kind: "mirror", ...view }),
    [],
  );
  // One answer per session, reported by its host and read by the sidebar.
  const [statuses, setStatuses] = useState<Record<string, SessionStatus>>({});
  // AND ONE TITLE PER SESSION, from the same reporter and read by the same rows -- but
  // ONLY for a session this page minted (`minted` above). The store's copy
  // (`SessionSummary.firstUserText`) is the authority for every other row, because what a
  // host holds for one of those is a window and its first user message is not the
  // conversation's first. For a minted session this is fresher than the store's copy by
  // exactly the message that has not been listed yet, which is the one just typed.
  const [liveTitles, setLiveTitles] = useState<Record<string, string>>({});
  // WHICH SESSIONS ARE SITTING ON BYTES THAT DID NOT REACH THE RECORD, reported by
  // their host on the read that opens the session and on every poll after it. A
  // session that is absent from this map is FINE -- that is the ordinary answer, and
  // the same absence the server sends (`lib/record-health.ts`).
  const [records, setRecords] = useState<Record<string, RecordHealth>>({});
  // AND THE WINDOW'S TOP, per session: whether there is more in front, whether a page is
  // in flight, how to ask for one, and what happened to the window. It lives up here for
  // the same reason the record's health does -- the column is drawn as the HOST's
  // `children`, so a host cannot hand it a prop.
  const [windows, setWindows] = useState<Record<string, WindowControls | null>>({});
  // The sessions whose history would not load, keyed by session, so the refusal
  // lands on the row that was clicked.
  const [openErrors, setOpenErrors] = useState<Record<string, string>>({});
  // WHICH VIEW THE COLUMN SHOWS. Session-scoped UI state and deliberately NOT
  // persisted: it is a way of looking at the conversation in front of you, not a
  // preference about sessions, and a stored one would surprise a reader on the
  // next launch.
  const [view, setView] = useState<"conversation" | "trajectory">("conversation");
  // WHETHER THE SIDEBAR IS FOLDED AWAY, and it is the PAGE's state rather than the
  // sidebar's for the one reason a component cannot solve: on a NARROW window a folded
  // sidebar is a hidden subtree, so it cannot draw the control that unfolds it -- the
  // floating corner button does, and that button is this file's. (On a wide window the
  // folded column is the rail and holds that control itself, which is why this page draws
  // the corner one only below `lg`.) The controls and what they agree on are in
  // `components/sidebar-toggle.tsx`.
  //
  // IT STARTS FROM THE WINDOW (`isWideWindow`): there on a wide one, folded away on a
  // phone-sized one. `useState` reads that ONCE, for the first render -- after that the
  // person owns the bit, and the window only decides how it is DRAWN (`lg:` classes) and
  // which of the drawer's courtesies apply (`foldDrawer` below).
  //
  // TRANSIENT, AND DELIBERATELY NOT PERSISTED, the same call `view` above makes:
  // folding the sidebar is a way of looking at the page, not a fact about the work,
  // and a remembered fold would meet somebody in a wide window with their project
  // list hidden for a reason they set on a narrow one.
  const [folded, setFolded] = useState(() => !isWideWindow());

  /// FOLD IT IF THIS WINDOW HAS A DRAWER TO FOLD. On a wide window the sidebar is a
  /// column beside the conversation and nothing is covering anything, so closing it on a
  /// pick or on Escape would be taking the page apart for no reason; on a narrow one the
  /// panel is over the conversation and staying open is what makes a pick look like it
  /// did nothing.
  ///
  /// THE WIDTH IS READ NOW, at the moment of the decision, and not remembered from mount:
  /// a window dragged across the breakpoint since then is exactly the case a cached answer
  /// gets wrong. This is one of the three readers named in `isWideWindow`.
  const foldDrawer = useCallback(() => {
    if (!isWideWindow()) setFolded(true);
  }, []);

  // ESCAPE CLOSES THE DRAWER, because that is what Escape does to a panel drawn over the
  // page. A Radix overlay that already took this key -- the remove-project dialog, a
  // picker -- calls `preventDefault` before it dismisses, so the drawer does not fold out
  // from under a dialog that just closed; `defaultPrevented` is that hand-off.
  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key !== "Escape" || event.defaultPrevented) return;
      foldDrawer();
    };
    window.addEventListener("keydown", onKeyDown);
    return () => window.removeEventListener("keydown", onKeyDown);
  }, [foldDrawer]);

  /// Show a session, hosting it if it has no host yet. `hydrate` says whether
  /// there is a conversation under that id to rebuild -- true when a row from the
  /// list was opened, false for an id this client has just minted. It is read
  /// ONCE, when the host mounts; showing a session that already has a host
  /// changes nothing about it.
  const show = useCallback(
    (id: string, read: HistoryRead = "rebuild") => {
      setRoster((prev) => {
        const existing = prev.live.find((host) => host.id === id);
        let live = prev.live;
        if (existing === undefined) {
          live = [...live, { id, read, attempt: 0 }];
        } else if (openErrors[id] !== undefined) {
          // Its history would not load last time. Opening it again is a retry, so
          // the host is remounted (the key carries the attempt) and the load runs
          // once more.
          live = live.map((host) =>
            host.id === id ? { ...host, attempt: host.attempt + 1 } : host,
          );
        }
        return { shown: id, live };
      });
      setOpenErrors((prev) => dropKey(prev, id));
    },
    [openErrors],
  );

  /// A host whose history would not load LEAVES NO HOST: there is no conversation
  /// behind it, so keeping it would mean an empty column pretending to be that
  /// session. The page falls back to the last session it still has -- which is
  /// where the old code left you, because a failed switch never moved the page.
  const hostFailed = useCallback((id: string, message: string) => {
    setOpenErrors((prev) => ({ ...prev, [id]: message }));
    setRoster((prev) => {
      const live = prev.live.filter((host) => host.id !== id);
      const fallback = live[live.length - 1];
      return {
        shown: prev.shown === id && fallback !== undefined ? fallback.id : prev.shown,
        live,
      };
    });
  }, []);

  const reportStatus = useCallback((id: string, status: SessionStatus) => {
    setStatuses((prev) => {
      const current = prev[id];
      return current !== undefined &&
        current.running === status.running &&
        current.parked === status.parked
        ? prev
        : { ...prev, [id]: status };
    });
  }, []);

  const forgetStatus = useCallback((id: string) => {
    setStatuses((prev) => dropKey(prev, id));
    // AND THE WINDOW GOES WITH IT: a host that went away is not following anything, and
    // a control left behind for a session that is restored later would draw the last
    // window's button over a page that has not read one yet.
    setWindows((prev) => dropKey(prev, id));
  }, []);

  /// MAKE THE SERVER KNOW THIS SESSION, IMMEDIATELY BEFORE ITS FIRST RUN REQUEST.
  ///
  /// THIS IS WHERE THE TWO HALVES OF THIS MERGE MEET. The page mints a session's id and
  /// writes nothing (点击新增不立刻会话，发送才新建); the run edge refuses a run aimed at an
  /// id this home has never been asked to keep (`refuse-unknown-session!`). Both are kept
  /// by moving the registration from the click to here: a project session is BOUND to the
  /// directory the sidebar handed over (`bindThread`), a task is registered with no project
  /// (`startTask` with the id this page minted). Both routes are find-or-create on the
  /// server, so asking is idempotent -- never wrong, only unnecessary.
  ///
  /// WHY HERE AND NOT SOMEWHERE ELSE: the agent's fetch wrapper awaits this before the
  /// request leaves the browser, which is the last moment that is unambiguously BEFORE the
  /// run. Later -- a send handler, a title arriving -- races the very request it is meant
  /// to precede, and earlier -- the click -- is the write lazy creation removed. Keeping
  /// the ordering is what keeps the edge's refusal out of the conversation.
  ///
  /// ONCE PER ID, AND NOT RETRIED: the entry is deleted BEFORE the await, so two runs
  /// racing on one id (a send and a resume, a retry after a dropped socket) still write
  /// once, and a failure is not written again behind the person's back -- the entry is
  /// gone, and the next run takes the server's own refusal into the conversation, which is
  /// the honest place for it.
  ///
  /// A REFUSAL IS ALSO A SENTENCE ON THE ROW, through the same `openErrors` map a history
  /// that would not load uses: a bind the server would not take (a project removed in
  /// another tab) is a fact about the row the person clicked. It does NOT stop the run --
  /// the run is what they asked for -- and if the id is still unknown when it arrives, the
  /// edge's refusal is what the person sees: it reaches this page as a run error
  /// (`useAgUiRuntime`'s `onError` -> `hostFailed`), which is a sentence on that same row.
  /// The two failures therefore land in one place, and the run is never silently dropped.
  const registerPending = useCallback(
    async (id: string): Promise<void> => {
      if (!pendingBinds.current.has(id)) return;
      const dir = pendingBinds.current.get(id) ?? null;
      pendingBinds.current.delete(id);
      try {
        if (dir !== null) await bindThread(id, dir, tErrors);
        else await startTask(tErrors, id);
      } catch (failure: unknown) {
        setOpenErrors((prev) => ({
          ...prev,
          [id]: failure instanceof Error ? failure.message : String(failure),
        }));
      }
    },
    [tErrors],
  );

  /// WHAT THIS PAGE LEARNS FROM A HOST: what a session it MINTED is CALLED, and nothing
  /// else.
  ///
  /// A TITLE ARRIVING MEANS SOMEBODY SENT THE FIRST MESSAGE: this callback is fed by
  /// `firstUserText` over the runtime's own messages (`SessionStatusReporter`), so a
  /// non-null title is that exact event and there is no other.
  ///
  /// AND IT IS SPOKEN ONLY FOR A SESSION THIS PAGE MINTED (`minted`). The runtime's
  /// messages are NOT the conversation for a session served as a window -- they are a
  /// tail page (`docs/architecture/client.md`: 它手里是一段窗口，不是整场会话) -- so the
  /// first user message in them is a message from the MIDDLE, and taking it as the row's
  /// name would relabel an old conversation the moment somebody opened it. The row draws
  /// `liveTitle` in preference to the store's copy, so a wrong one here is not a stale
  /// name, it is a wrong one. For a minted session the window is empty and the first
  /// message is genuinely the first; for every other session the store's
  /// `sessions.title` -- the authority on names -- answers, and this returns without
  /// touching anything.
  ///
  /// IT ALSO USED TO BE WHERE THE PENDING REGISTRATION WAS CASHED IN, and that moved to
  /// `registerPending`: main's run edge made the registration something the REQUEST needs
  /// rather than something that happens after it, and a title is only known once the run's
  /// first message has been streamed back -- far too late to register the session the run
  /// is aimed at. The half that stays is the one a title really answers.
  const reportTitle = useCallback((id: string, title: string | null) => {
    if (!minted.current.has(id)) return;
    setLiveTitles((prev) => {
      // NULL IS "NOTHING SAID YET", and the registry holds titles rather than
      // answerless entries: dropping the key hands the row back to the store's copy,
      // which is the same answer from the other source.
      if (title === null) return dropKey(prev, id);
      return prev[id] === title ? prev : { ...prev, [id]: title };
    });
  }, []);

  /// Take one session's record health, or drop it when the host reports there is
  /// nothing to say. THE NO-NEWS PATH IS THE COMMON ONE and returns the same object,
  /// so the page does not re-render every poll with an unchanged (empty) answer.
  const reportRecord = useCallback((id: string, record: RecordHealth | null) => {
    setRecords((prev) => {
      if (record === null) return prev[id] === undefined ? prev : dropKey(prev, id);
      return { ...prev, [id]: record };
    });
  }, []);

  /// TAKE ONE SESSION'S WINDOW TOP -- or drop it when the host reports it is not
  /// following one (`null`: the sidebar door, the fresh-id door, or a log that had to be
  /// repaired). The no-window path removes the key for the same reason `reportRecord`
  /// does: an absent entry and an entry saying "nothing" are the same drawing, and the
  /// page should not re-render over the difference.
  const reportWindow = useCallback((id: string, controls: WindowControls | null) => {
    setWindows((prev) => {
      if (controls === null) return prev[id] === undefined ? prev : dropKey(prev, id);
      return { ...prev, [id]: controls };
    });
  }, []);

  /// Show a session this client has just minted: nothing to rebuild, so no load. The
  /// directory it will belong to travels with it (see `onShowFresh` in `sidebar.tsx`) and
  /// is only REMEMBERED here -- the registration itself happens at the first run.
  ///
  /// THIS AND `showExisting` ARE THE SIDEBAR'S TWO DOORS, and because they are, BOTH CLOSE
  /// THE DRAWER on a narrow window (`foldDrawer`): the panel listed the conversation, and
  /// leaving it over the one just chosen is a pick that looks like it did nothing.
  ///
  /// THE PAGE'S OWN PATH DOES NOT COME THROUGH HERE -- `onListed` calls `show` directly --
  /// and that is the distinction worth keeping: a page landing on the session it already
  /// remembers has nobody to get out of the way of.
  ///
  /// EVERY MINTED SESSION IS REMEMBERED HERE, INCLUDING A TASK: the entry is keyed by id
  /// and holds `null` for a task, which is what tells `registerPending` that this one is
  /// registered with `startTask` rather than bound to a directory.
  const showFresh = useCallback(
    (id: string, projectDir: string | null) => {
      pendingBinds.current.set(id, projectDir);
      // AND IT MAY BE NAMED BY ITS OWN RUNTIME until the store answers for it -- see
      // `minted`. The two do different jobs: this one is about the ROW's name, the map
      // above is about the session's EXISTENCE.
      minted.current.add(id);
      foldDrawer();
      show(id, "none");
    },
    [foldDrawer, show],
  );
  /// The same door for a session that HAS a conversation: rebuild it once, through the
  /// read that refuses to invent one for an id with no log (`HistoryRead`).
  const showExisting = useCallback(
    (id: string) => {
      foldDrawer();
      show(id, "rebuild");
    },
    [foldDrawer, show],
  );

  /// THE SESSION THE PAGE REMEMBERS, read ONCE at mount, and null once the restore has
  /// dealt with it. It has to be read before anything is written: the id on screen at
  /// mount is a fresh one this page has just minted, and remembering it first would
  /// overwrite the very id the restore is about to look for.
  const [pending, setPending] = useState<string | null>(() => rememberedSession(browserStorage()));

  // WHAT IS ON SCREEN IS REMEMBERED -- one effect on `shown` rather than a line inside
  // `show`, because THE FIRST SESSION IS MINTED BY `useState` AND NEVER GOES THROUGH
  // `show` AT ALL, and that is the session a person's first message lands in: a restore
  // that could not find it would hand them an empty conversation after every reload.
  //
  // AND NOT WHILE A RESTORE IS PENDING. The minted id is not yet the page's memory then;
  // writing it would clobber the remembered one before the listing has had a chance to
  // say whether it is still there (see `onListed`).
  useEffect(() => {
    if (pending !== null) return;
    rememberSession(browserStorage(), roster.shown);
  }, [roster.shown, pending]);

  /// RETIRE THE LIVE TITLES THE STORE CAN NOW ANSWER FOR. This is the second half of the
  /// rule `reportTitle` states: the page speaks for a session only until the store can
  /// name it, and from then on the store's copy -- the authority on names -- answers.
  ///
  /// A LISTED ROW IS NOT ENOUGH, and that is the whole subtlety. The store's name is
  /// written by the run's own END, so a listing read a moment too early holds the row with
  /// nothing in `firstUserText` -- and dropping the page's copy there would hand the row
  /// its fallback, which is the SESSION ID (a row draws `titleOf(firstUserText) ?? id`). It
  /// would then read as a uuid until somebody pressed refresh, because the sidebar re-asks
  /// only for a session that is MISSING from its listing (`asked`). REPRODUCED with thirty
  /// sends in a row against the real backend, where the row was left as its id while the
  /// store had the words. So the test here is the ROW'S OWN: the store answers once `titleOf`
  /// can read a name out of it, and that is the moment the page stops speaking.
  ///
  /// IT IS NOT AN OPTIMISATION EITHER. Leaving the entry in place past that point would keep
  /// the runtime's copy winning over the store's for the rest of the page's life, which is
  /// exactly how a window's middle-of-the-conversation message would keep a row renamed
  /// after the store had already answered correctly. Dropping it is also what makes `minted`
  /// bounded: an id leaves the set here, and the live title leaves with it.
  ///
  /// ONLY THE IDS THIS LISTING CAN NAME ARE TOUCHED, so a minted session the store has not
  /// answered for keeps its name (and its `asked` refetch -- see `sidebar.tsx`, which needs
  /// the live title to know there is a row missing).
  const forgetListedTitles = useCallback((listing: SidebarListing) => {
    const named = new Set<string>();
    for (const project of listing.projects)
      for (const session of project.sessions)
        if (titleOf(session.firstUserText) !== null) named.add(session.threadId);
    for (const session of listing.tasks)
      if (titleOf(session.firstUserText) !== null) named.add(session.threadId);
    for (const id of minted.current) if (named.has(id)) minted.current.delete(id);
    setLiveTitles((prev) => {
      const listed = Object.keys(prev).filter((id) => named.has(id));
      if (listed.length === 0) return prev;
      const next = { ...prev };
      for (const id of listed) delete next[id];
      return next;
    });
  }, []);

  /// THE MOUNT RESTORE: the session this page was in before it was reloaded (ticket
  /// 03). THREE THINGS ABOUT IT, and each is a decision:
  ///
  ///   * IT IS DRIVEN BY THE SIDEBAR'S LISTING, not by a fetch of its own: the page
  ///     already reads every session of every project AND every task, so "is that id
  ///     still a session" is a question about an answer that is on its way anyway --
  ///     and a task can be the answer, which is why it is the whole listing that is
  ///     handed over rather than the projects inside it.
  ///   * IT HAPPENS ONCE, and only on the FIRST listing: it is a restore, not a
  ///     policy -- an id that disappears from the list later (somebody archived it)
  ///     leaves the page where it is.
  ///   * A REMEMBERED ID THAT IS GONE FALLS BACK TO THE FRESH SESSION THE ROSTER
  ///     ALREADY HAS, silently, and the id is FORGOTTEN so the next reload does not ask
  ///     again. A session that was deleted, archived or moved by hand is not a situation
  ///     anybody can act on, so it is not a sentence either. (There is no page with no
  ///     session on it to say one into: the roster mints one at mount, and this is what
  ///     that mint is for.)
  ///
  /// THE WINDOW, NOT A REBUILD, is the host's door here, and that is the whole ticket:
  /// the conversation may be IN THE MIDDLE OF A RUN, which rebuild refuses and which
  /// looking at must not disturb -- and the window is what lets the page keep looking
  /// (`feed`), rather than reading the conversation once and falling behind.
  const restored = useRef(false);
  const onListed = useCallback(
    (listing: SidebarListing) => {
      // EVERY LISTING, not just the first: this is where the page learns that the row it
      // minted has arrived (see `forgetListedTitles`), and the sidebar hands up each one
      // it lands. The restore below is the part that happens once.
      forgetListedTitles(listing);
      if (restored.current || pending === null) return;
      restored.current = true;
      const listed = listedSession(pending, listing);
      if (listed !== null) {
        // NOTHING EVER SENT means the conversation is empty by construction -- a
        // session made on the sidebar and never used -- so there is nothing to read
        // and nothing to ask for; a session that HAS been sent to is read through the
        // door that may only look. (The test used to be the log's size, which is the
        // same fact read off the disk side; it is a store column now.)
        show(pending, listed.lastSentAt === null ? "none" : "window");
      } else {
        forgetSession(browserStorage(), pending);
      }
      setPending(null);
    },
    [forgetListedTitles, pending, show],
  );

  return (
    // THE COPIED ICON BUTTONS mount a Radix tooltip, and Radix throws when no
    // provider sits above it. Once for the page now, because the columns are no
    // longer nested inside one runtime provider -- the tooltips are the sidebar's
    // and the composer's, and neither belongs to a session.
    <TooltipProvider>
      {/* The ROW is the viewport, and the two children each get their height from
          it: the sidebar is a fixed-width `shrink-0` column, the chat is
          `min-h-0 flex-1`. `Thread`'s root is `h-full`, so it reads the height off
          this wrapper -- which is why `min-h-0` is here and not on the thread:
          without it a flex child will not shrink below its content, and the whole
          page scrolls instead of the message list.

          `relative` IS FOR THE FOLD. On a narrow window the sidebar and the control
          that brings it back are `absolute` (see `components/sidebar.tsx` and
          `components/sidebar-toggle.tsx`), so this row is the box they are placed
          against -- and it is the one element that knows the viewport's height. */}
      <SubagentViewContext.Provider value={openMirror}>
      <div className="relative flex h-dvh">
        {/* THE BACKDROP EXISTS ON NARROW WINDOWS ONLY, where the sidebar floats
            over the conversation: a panel covering what you were reading needs a
            way out that is not a hunt for the corner, and tapping beside it is
            the gesture people already have. On a wide window the sidebar is a
            column, nothing is covered, and `lg:hidden` keeps this from swallowing
            the clicks of the conversation beside it. It is `aria-hidden` because
            it carries no information: it is the second way to press the collapse
            button, and that button is the one with a name. */}
        {!folded && (
          <div
            data-slot="sidebar-backdrop"
            aria-hidden={true}
            onClick={() => setFolded(true)}
            className="absolute inset-0 z-20 bg-black/30 lg:hidden"
          />
        )}
        {/* OUTSIDE every runtime provider, because it manages ALL sessions:
            its rows, their refusal sentences, and the projects they belong to.
            It used to sit inside the one provider only to reach
            `runtime.threads.switchToThread`, and it no longer has one.

            AND IT IS NEVER UNMOUNTED, folded or not -- `folded` is a class inside it
            (`hidden` on a narrow window, the rail's own width on a wide one), not a
            missing element. Its `refresh` is the only reader of
            `GET /api/projects`, and that reading is where the mount restore learns
            whether the remembered session still exists (`onListed`): a phone-sized
            window starts folded, so unmounting it here would leave exactly those
            windows unable to restore anything, with the remembered id going stale
            until somebody unfolded the list. `components/sidebar.tsx` argues the
            same property from its own side. */}
        <Sidebar
          currentThreadId={roster.shown}
          onListed={onListed}
          statuses={statuses}
          liveTitles={liveTitles}
          openErrors={openErrors}
          onShow={showExisting}
          onShowFresh={showFresh}
          folded={folded}
          onCollapse={() => setFolded(true)}
          onExpand={() => setFolded(false)}
        />
        {/* THE WAY BACK FOR A NARROW WINDOW, and it lives here rather than in the
            sidebar for the reason the state does: a folded sidebar is hidden on a narrow
            window, and a hidden subtree cannot draw a control that is meant to be seen.
            On a wide one the same control is drawn by the rail's top cell instead -- the
            component is the same, its `shape` decides, and that one carries `lg:hidden`,
            so exactly one of the two is ever on screen. */}
        {folded && <SidebarOpenButton onOpen={() => setFolded(false)} />}
        {/* AND THE WAY BACK FOR THE RIGHT-HAND COLUMN, drawn here for the same reason the
            sidebar's is: a closed column is not drawn at all, so the control that brings it
            back cannot live inside it. It floats in the top-right corner ONLY WHILE THE COLUMN
            IS CLOSED, and it is `hidden md:flex` because below `md` the column does not exist
            either -- a control that does nothing when pressed is worse than no control. See
            `components/right-pane-toggle.tsx` for the pair and where each control sits. */}
        {rightPane === null && <RightPaneOpenButton onOpen={() => setRightPane({ kind: "tasks" })} />}
        {/* `min-w-0` IS LOAD-BEARING, not tidiness: a flex item's automatic minimum
            width is its content's min-content width, and the trajectory's rows are
            single-line mono JSON with no spaces -- so without this the column
            refuses to be narrower than the longest argument list, and the page
            scrolls sideways with every preview running off the edge. The chat never
            needed it because its text wraps. */}
        <div className="min-h-0 min-w-0 flex-1">
          {/* AND A PAGE THAT COULD NOT REGISTER A MINTED SESSION SAYS SO ON ITS ROW, not
              here: `registerPending`'s failure goes into `openErrors`, which the sidebar
              draws under the session it belongs to. There is no "no session" box any more
              (the roster mints one at mount, so the column is never empty), and there is
              no ask whose refusal could leave one. */}
          {roster.live.map((host) => (
            <SessionHost
              key={`${host.id}:${host.attempt}`}
              threadId={host.id}
              read={host.read}
              visible={host.id === roster.shown}
              onStatus={reportStatus}
              onTitle={reportTitle}
              onForget={forgetStatus}
              onError={hostFailed}
              onRecord={reportRecord}
              onWindow={reportWindow}
              onReady={registerPending}
            >
              <SessionColumn
                threadId={host.id}
                view={view}
                onView={setView}
                folded={folded}
                record={records[host.id] ?? null}
                window={windows[host.id] ?? null}
                // THE LIVE MAP, and not a copy of it: a `remember` from the composer's
                // directory picker writes the directory the first send will bind with, and
                // `registerPending` reads it out of this same object.
                binds={pendingBinds.current}
              />
            </SessionHost>
          ))}
        </div>
        {/* THE THIRD COLUMN: ONE COLUMN IN TWO STATES, beside the conversation rather than
            over it (`.scratch/right-pane-tasks`, decision 1). It is a `shrink-0` sibling AFTER
            the chat column, so the middle column's `min-w-0 flex-1` is what gives up the room
            -- and each state keeps the same fixed width, which is why the main conversation can
            never be squeezed to nothing by it.

            THE MIRROR'S `key` IS THE CHILD'S ID, and it is load-bearing: switching subagents
            must mount a NEW panel, not reuse the open one. The runtime, the agent and the
            follow connection all belong to one child, and a reused host would keep the first
            child's run (and its subscription) alive behind the second's name. Remounting is
            what makes "one at a time" true at the connection level rather than only on screen.
            (The task pane holds no connection, so it needs no key.) */}
        {rightPane !== null && rightPane.kind === "mirror" && (
          <SubagentViewPanel
            key={rightPane.threadId}
            view={rightPane}
            onClose={() => setRightPane(null)}
            // AND THE LIST THE ROW CAME FROM: the mirror's trailing control goes back to the
            // task view, which is the one state that is there whatever the transcript did.
            onBack={() => setRightPane({ kind: "tasks" })}
          />
        )}
        {rightPane !== null && rightPane.kind === "tasks" && (
          // THE THREAD ID IS THE ONE ON SCREEN (`roster.shown`), which is what the pane polls:
          // a pane showing one session must never draw another's jobs or delegations. And
          // `onOpen` is the SAME writer the transcript's `agent` card uses, so a row and a card
          // open one state rather than two doors that could drift apart.
          <TaskPane
            threadId={roster.shown}
            onCollapse={() => setRightPane(null)}
            onOpen={openMirror}
          />
        )}
      </div>
      </SubagentViewContext.Provider>
    </TooltipProvider>
  );
}

