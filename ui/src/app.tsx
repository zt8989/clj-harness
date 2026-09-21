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

import { fromThreadMessageLike } from "@assistant-ui/core";
import { AssistantRuntimeProvider, useAuiState } from "@assistant-ui/react";
import {
  fromAgUiMessages,
  useAgUiInterrupts,
  useAgUiRuntime,
} from "@assistant-ui/react-ag-ui";
import { cn } from "cn";
import { useCallback, useEffect, useMemo, useRef, useState, type FC, type ReactNode } from "react";
import { useTranslation } from "react-i18next";

import { Thread } from "@/components/assistant-ui/elements/thread.aui";
import { ThreadIdContext } from "@/components/composer-chrome";
import { ContextCards } from "@/components/context-card";
import { RecordNotice } from "@/components/record-notice";
import { keepInjectionCards } from "@/lib/injections";
import { TrajectoryView } from "@/components/trajectory-view";
import { TooltipProvider } from "@/components/ui/tooltip";
import {
  ApprovalBatchProvider,
  isParkedInterrupt,
} from "@/components/approval-gate";
import { Sidebar } from "@/components/sidebar";
import { SidebarOpenButton, isWideWindow } from "@/components/sidebar-toggle";
import { THREAD_COMPONENTS } from "@/components/message-parts";
import { HarnessAgent } from "@/lib/agent";
import { imageAttachments } from "@/lib/attachments";
import { startTask, type SidebarListing } from "@/lib/projects";
import {
  browserStorage,
  forgetSession,
  listedSession,
  rememberedSession,
  rememberSession,
} from "@/lib/session-memory";
import { AGENT_URL, rebuildThread, sofarThread, type SofarState } from "@/lib/threads";
import { type SessionStatus } from "@/lib/session-status";
import { type RecordHealth } from "@/lib/record-health";

/// HOW OFTEN A WATCHED CONVERSATION IS READ AGAIN, in milliseconds. Long enough that
/// a long run is not thousands of requests, short enough that a person watching it
/// grow does not think it has stopped.
const WATCH_INTERVAL_MS = 1200;

/// The converted history a restore hands the runtime: `fromAgUiMessages`
/// rebuilds text, reasoning and tool calls -- and reads back
/// `metadata.custom.agui.interrupts` when the log carried them -- but its
/// output is still the loose `ThreadMessageLike` shape; the repository wants
/// the finished one. The runtime's own snapshot-import path runs this exact
/// pair (AgUiThreadRuntimeCore.importMessagesSnapshot), so the conversion is
/// upstream's, quoted rather than reinvented.
/// HOW FAR ALONG THE CONVERSATION IS, as the messages are built: `running` is read
/// back off `GET /api/threads/<id>/sofar` (a run being answered in the process, which
/// this page may only be WATCHING), and null is every other case -- a session this
/// client has just minted, or one that was rebuilt and is therefore over by
/// definition.
///
/// THE LAST MESSAGE'S STATUS IS WHERE THAT LANDS, and it is not decoration:
/// `lib/turns.ts` folds a turn's steps into a one-line summary exactly when its last
/// message is settled, so a conversation still being written has to say so HERE or it
/// renders as a finished answer that happens to stop mid-sentence. Nothing else gets a
/// status of its own -- the messages before it really are complete.
type Reads = SofarState | null;

function toThreadMessages(agUiMessages: readonly unknown[], reads: Reads) {
  // `fromAgUiMessages` rebuilds text, reasoning and tool calls; the injection cards
  // are put back right after it, because upstream's converter has no case for a `data`
  // part (see `lib/injections.ts`). Everything else about a rebuilt message is
  // upstream's.
  const converted = keepInjectionCards(agUiMessages, fromAgUiMessages(agUiMessages));
  const last = converted.length - 1;
  return converted.map((message, index) =>
    fromThreadMessageLike(
      message,
      message.id ?? crypto.randomUUID(),
      index === last && reads === "running"
        ? { type: "running" as const }
        : { type: "complete" as const, reason: "unknown" as const },
    ),
  );
}

/// The rebuilt messages as the repository the history adapter returns: a flat
/// chain, each message parented to the one before it. Built here rather than with
/// `ExportedMessageRepository.fromArray` because that helper assigns fresh ids,
/// and these messages already have the ids the runtime recorded.
function repositoryFrom(agUiMessages: readonly unknown[], reads: Reads = null) {
  const messages = toThreadMessages(agUiMessages, reads);
  let parentId: string | null = null;
  const items = messages.map((message) => {
    const item = { parentId, message };
    parentId = message.id;
    return item;
  });
  return { messages: items };
}

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
///                refusal belongs on the row that was clicked.
///   "sofar"   -- the session this page was already in, landed in again (a reload).
///                LOOK AT IT, do not take it over: it may be in the middle of a run,
///                which `rebuild` refuses outright, and looking must not write.
type HistoryRead = "none" | "rebuild" | "sofar";

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
async function readSofar(threadId: string, t: TFunction<"errors">) {
  try {
    return await sofarThread(threadId, t);
  } catch {
    const rebuilt = await rebuildThread(threadId, t);
    return { ...rebuilt, state: "settled" as const };
  }
}

/// The read a host runs on mount, and what it reports back: `onReads` carries the
/// conversation's own state out of the adapter, because the PAGE needs it -- the poll
/// below keeps reading while it says `running`, and only the adapter has been told.
/// `onRecord` carries the record's health out with it (ADR 0002 decision 6), for the
/// same reason: it arrives on this read, and the column that says so is not the thing
/// that made the request.
///
/// IT IS REPORTED ON BOTH DOORS, including `rebuild` -- a conversation handed over
/// from a record the writer could not add to is exactly the case a reader needs told,
/// and it is the door a session is opened through.
function sessionHistory(
  threadId: string,
  read: HistoryRead,
  t: TFunction<"errors">,
  onReads: (reads: Reads) => void,
  onRecord: (record: RecordHealth | null) => void,
) {
  return {
    load: async () => {
      if (read === "none") return { messages: [] };
      if (read === "rebuild") {
        const rebuilt = await rebuildThread(threadId, t);
        onRecord(rebuilt.record ?? null);
        return repositoryFrom(rebuilt.messages);
      }
      const answer = await readSofar(threadId, t);
      onReads(answer.state);
      onRecord(answer.record ?? null);
      return repositoryFrom(answer.messages, answer.state);
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
const SessionStatusReporter: FC<{
  threadId: string;
  onStatus: (id: string, status: SessionStatus) => void;
  onForget: (id: string) => void;
}> = ({ threadId, onStatus, onForget }) => {
  const running = useAuiState((s) => s.thread.isRunning);
  // The parked reading: this session has stopped to ask a human. It is NOT
  // `running` -- a run that ends on an interrupt has `isRunning` false -- which
  // is exactly why the sidebar has two different things to say.
  const parked = useAgUiInterrupts().some(isParkedInterrupt);

  // AN EFFECT, NOT RENDER. Reporting upward during render is React's classic
  // mistake -- the value this host would read in that same render is not the one
  // it just reported. The page compares the two booleans, so a second report of
  // the same answer changes nothing and there is no loop to guard.
  useEffect(() => {
    // [DEBUG-a4f2] the projection the Send/Cancel toggle reads.
    console.log(`[DEBUG-a4f2] reporter ${threadId} running=${running} parked=${parked}`);
    onStatus(threadId, { running, parked });
  }, [threadId, running, parked, onStatus]);

  // AND FORGOTTEN ON UNMOUNT, so a host that goes away cannot leave its row lit
  // forever. Its own effect rather than a cleanup on the one above, which re-runs
  // on every status change and would blink the row off each time.
  useEffect(() => () => onForget(threadId), [threadId, onForget]);

  return null;
};

/// One session, alive. It renders the runtime provider and -- when it is the
/// session on screen -- the column; otherwise it renders nothing and keeps
/// owning its run.
const SessionHost: FC<{
  threadId: string;
  read: HistoryRead;
  visible: boolean;
  onStatus: (id: string, status: SessionStatus) => void;
  onForget: (id: string) => void;
  onError: (id: string, message: string) => void;
  /// WHAT THIS SESSION'S RECORD LOOKS LIKE, reported to the page rather than kept
  /// here: the sentence is drawn by the column, which this host renders as
  /// `children` and therefore cannot hand a prop to. `null` is the ordinary answer
  /// and means the record has nothing to say.
  onRecord: (id: string, record: RecordHealth | null) => void;
  children: ReactNode;
}> = ({ threadId, read, visible, onStatus, onForget, onError, onRecord, children }) => {
  // The agent is built ONCE for this host and owns this session's id for the
  // host's whole life. Rebuilding it would throw the thread away mid-run -- the
  // same reason the old single-agent memo had an empty dependency list, paid per
  // session now instead of once for the page.
  const agent = useMemo(() => {
    const created = new HarnessAgent({ url: AGENT_URL });
    created.threadId = threadId;
    return created;
  }, [threadId]);

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

  // WHAT THE CONVERSATION SAID ABOUT ITSELF when it was read, as a REF plus a counter
  // rather than as state: the poll below has to re-arm while the answer STAYS
  // `running`, and setting state to the same value is a no-op React happily skips --
  // which would leave the last read on screen and the run still growing behind it. The
  // counter is what re-runs the effect; the ref is what it reads.
  const reads = useRef<Reads>(null);
  const [readCount, setReadCount] = useState(0);
  const onReads = useCallback((next: Reads) => {
    reads.current = next;
    setReadCount((count) => count + 1);
  }, []);

  // The record's health, out to the page. NOT a ref-and-counter like `reads` above:
  // nothing in this host's effects re-runs on it, and the page's own state is what
  // decides whether the sentence is on screen.
  const reportRecord = useCallback(
    (record: RecordHealth | null) => onRecord(threadId, record),
    [threadId, onRecord],
  );

  // WHETHER A RUN THIS HOST DROVE IS IN FLIGHT, and whether one ever was. Taken from
  // the same reading the page's registry gets (`SessionStatusReporter` below), for the
  // one effect that has to know: the record re-read after a run ends.
  //
  // A REF ALONGSIDE THE STATE, because "was there ever a run" is not a thing to
  // re-render for -- it only decides whether the effect below has anything to ask
  // about -- while `ownRun` IS state, because flipping it is what runs the effect.
  const ranSomething = useRef(false);
  const [ownRun, setOwnRun] = useState(false);
  const reportStatus = useCallback(
    (id: string, status: SessionStatus) => {
      if (status.running) ranSomething.current = true;
      setOwnRun(status.running);
      onStatus(id, status);
    },
    [onStatus],
  );

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
  /// IT REPORTS AND NOTHING ELSE -- no `import`. This host is the one streaming that
  /// run, and the record LAGS it: importing the log back over the live conversation
  /// would draw the answer backwards. The watching poll may import precisely because a
  /// watcher has no stream of its own to regress.
  useEffect(() => {
    if (ownRun || !ranSomething.current) return undefined;
    ranSomething.current = false;
    let cancelled = false;
    void (async () => {
      try {
        const answer = await readSofar(threadId, tErrors);
        if (!cancelled) reportRecord(answer.record ?? null);
      } catch {
        // The read failed -- a session that is gone, a harness that is not answering.
        // Nothing to say about the record, and the conversation on screen stays as it
        // was: the same reasoning the poll below writes down.
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [ownRun, threadId, tErrors, reportRecord]);

  const history = useMemo(
    () => sessionHistory(threadId, read, tErrors, onReads, reportRecord),
    [threadId, read, tErrors, onReads, reportRecord],
  );

  const runtime = useAgUiRuntime({
    agent,
    isSendDisabled: gateOpen,
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
    onError: (error) => onError(threadId, error.message),
  });

  // KEEP READING A CONVERSATION THIS PAGE IS ONLY WATCHING (ticket 03, spec decision
  // one: read the record and poll, never a second streaming path).
  //
  // The run belongs to the PROCESS, not to the tab that started it, so a page that
  // reloaded into a session somebody is still answering has no stream to attach to and
  // no way to be told. What it has is the record, which is appended frame by frame --
  // so it asks again, and each answer is imported into this host's own runtime. THAT
  // IMPORT IS THE POINT of the poll: without it the conversation on screen would be
  // whatever the first read found, and the turn would look finished because nothing on
  // this page is running.
  //
  // IT STOPS WHEN THE ANSWER STOPS SAYING `running` -- a settled or parked
  // conversation is not going to grow, and polling one would be a loop with no fact
  // behind it. The interval is a compromise, not a rule: frames land in the file as
  // they are written, so a slower poll lags and a faster one asks for the same bytes.
  useEffect(() => {
    if (read !== "sofar" || reads.current !== "running") return undefined;
    let cancelled = false;
    const timer = setTimeout(async () => {
      try {
        const answer = await readSofar(threadId, tErrors);
        if (cancelled) return;
        reads.current = answer.state;
        setReadCount((count) => count + 1);
        // A RECORD THAT WENT DEGRADED MID-RUN IS SAID HERE, on the poll that is
        // already running: the read that opened this session answered before the
        // failure existed, so without this the sentence would wait for the next
        // reload -- which is the silence ADR 0002 decision 6 refuses.
        reportRecord(answer.record ?? null);
        runtime.thread.import(
          repositoryFrom(answer.messages, answer.state) as Parameters<typeof runtime.thread.import>[0],
        );
      } catch {
        // The read stopped working (the harness went away mid-run). Nothing to say
        // here: the poll simply stops, and the conversation on screen stays as it was
        // -- the last thing that was true.
        if (!cancelled) reads.current = null;
      }
    }, WATCH_INTERVAL_MS);
    return () => {
      cancelled = true;
      clearTimeout(timer);
    };
  }, [read, readCount, threadId, runtime, tErrors, reportRecord]);

  return (
    <AssistantRuntimeProvider runtime={runtime}>
      <SessionStatusReporter
        threadId={threadId}
        // THE WRAPPED ONE, not the page's: this host reads its own run state out of the
        // same report (`reportStatus` above), and the page's registry is the wrong place
        // to read it back from -- a host that is not on screen still owns its run.
        onStatus={reportStatus}
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
}> = ({ threadId, view, onView, folded, record }) => {
  const { t } = useTranslation();
  return (
    // The composer's chrome needs to know which session it is configuring -- the
    // model override and the branch are both per-session.
    <ThreadIdContext.Provider value={threadId}>
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
            "flex shrink-0 items-center gap-1 border-b border-border px-3 py-1.5",
            // `ps-11` CLEARS THE FLOATING CONTROL: 8px of inset plus its 32px, over the
            // `px-3` above (which `ps-` replaces on that side). The tabs must not end up
            // underneath a button the page draws on top of them -- and a stylesheet cannot
            // see that, so the numbers that produce it are next to the class they clear.
            folded && "ps-11",
          )}
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
            <Thread components={THREAD_COMPONENTS} />
          ) : (
            <TrajectoryView threadId={threadId} />
          )}
        </div>
      </div>
    </ThreadIdContext.Provider>
  );
};

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
  // THE ROSTER, AND IT STARTS EMPTY. The page no longer mints the first session's id:
  // an id belongs to the process that keeps the conversation (ADR 0002 decision 9), so
  // the page asks for one -- `startTask` below, or the sidebar's own buttons -- and
  // shows what it is given. What used to be here was `crypto.randomUUID()`, an id the
  // server had never heard of, which the run edge then quietly registered on the first
  // run; that silent create is gone (the edge refuses an unknown session by name), and
  // with it the client's licence to name a conversation.
  //
  // EMPTY IS A STATE THE PAGE IS ALLOWED TO BE IN, and it lasts one listing: the
  // restore below either lands on the session this page remembers or asks the server
  // for a fresh one. In between, the column area holds the sentence that says what
  // went wrong if that ask failed -- never a blank page pretending to be a session.
  const [roster, setRoster] = useState<Roster>(() => ({ shown: "", live: [] }));
  // WHY THERE IS NO SESSION ON SCREEN, when there is none because the ask failed. The
  // server's own sentence (`startTask`'s refusal), drawn where the conversation would
  // be: an empty column is indistinguishable from a page still loading, and a page
  // that lost its only session to a 500 has to say so.
  const [freshError, setFreshError] = useState<string | null>(null);
  /// The page's translator for the sentences the SERVER did not write: `startTask`
  /// speaks this interface's fallback words, and which language that is has nothing to
  /// do with a session (the hosts below each keep their own).
  const { t: tErrors } = useTranslation("errors");
  // One answer per session, reported by its host and read by the sidebar.
  const [statuses, setStatuses] = useState<Record<string, SessionStatus>>({});
  // WHICH SESSIONS ARE SITTING ON BYTES THAT DID NOT REACH THE RECORD, reported by
  // their host on the read that opens the session and on every poll after it. A
  // session that is absent from this map is FINE -- that is the ordinary answer, and
  // the same absence the server sends (`lib/record-health.ts`).
  const [records, setRecords] = useState<Record<string, RecordHealth>>({});
  // The sessions whose history would not load, keyed by session, so the refusal
  // lands on the row that was clicked.
  const [openErrors, setOpenErrors] = useState<Record<string, string>>({});
  // WHICH VIEW THE COLUMN SHOWS. Session-scoped UI state and deliberately NOT
  // persisted: it is a way of looking at the conversation in front of you, not a
  // preference about sessions, and a stored one would surprise a reader on the
  // next launch.
  const [view, setView] = useState<"conversation" | "trajectory">("conversation");
  // WHETHER THE SIDEBAR IS FOLDED AWAY, and it is the PAGE's state rather than the
  // sidebar's for the one reason a component cannot solve: a folded sidebar is a hidden
  // subtree, so it cannot draw the control that unfolds it -- the floating corner button
  // does, and that button is this file's. The pair and what they agree on are in
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

  /// Show a session the server has just named (and, for every path from the sidebar
  /// that has a project, just bound): nothing to rebuild, so no load.
  ///
  /// THIS AND `showExisting` ARE THE SIDEBAR'S TWO DOORS, and because they are, BOTH CLOSE
  /// THE DRAWER on a narrow window (`foldDrawer`): the panel listed the conversation, and
  /// leaving it over the one just chosen is a pick that looks like it did nothing.
  ///
  /// THE PAGE'S OWN TWO PATHS DO NOT COME THROUGH HERE -- `onListed` and `openFresh` call
  /// `show` directly -- and that is the distinction worth keeping: a page landing on the
  /// session it already remembers, or on a fresh one it just asked the server for, has
  /// nobody to get out of the way of.
  const showFresh = useCallback(
    (id: string) => {
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

  /// ASK THE SERVER FOR A CONVERSATION AND SHOW IT. The page's own door onto
  /// `POST /api/sessions`: with no id to bring, the answer names the session (see the
  /// roster's comment for why the page may not name one itself), and it is hosted EMPTY,
  /// because an id that has just been named has no log to read.
  ///
  /// A REFUSAL IS A SENTENCE, NOT AN EMPTY PAGE. There is nothing else on screen in this
  /// case -- the reason this ask happens at all is that the page has no session -- so a
  /// failure that said nothing would leave a blank column that looks exactly like a page
  /// still loading. The server's own words go up (`startTask` throws its `{:error ..}`),
  /// in the box the conversation would have been drawn in.
  const openFresh = useCallback(async () => {
    try {
      const id = await startTask(tErrors);
      setFreshError(null);
      show(id, "none");
    } catch (failure: unknown) {
      setFreshError(failure instanceof Error ? failure.message : String(failure));
    }
  }, [show, tErrors]);

  /// THE SESSION THE PAGE REMEMBERS, read ONCE at mount, and null once the restore has
  /// dealt with it. It has to be read before anything is written, because the restore's
  /// answer -- the remembered session, or the fresh one the server names when there is
  /// nothing to come back to -- is what the page will remember from then on.
  const [pending, setPending] = useState<string | null>(() => rememberedSession(browserStorage()));

  // WHAT IS ON SCREEN IS REMEMBERED -- one effect on `shown` rather than a line inside
  // `show`, because an effect is what can wait for `pending` to settle.
  //
  // AND NOT WHILE A RESTORE IS PENDING: the page has nothing on screen yet, and writing
  // that down would erase the very id the first listing is about to look for (see
  // `onListed`). An EMPTY `shown` is not written down either -- there is no session to
  // remember, and a remembered empty string is a value the reader would have to know to
  // ignore (`rememberedSession` does, and this is why it has to).
  useEffect(() => {
    if (pending !== null || roster.shown === "") return;
    rememberSession(browserStorage(), roster.shown);
  }, [roster.shown, pending]);

  /// THE MOUNT RESTORE: the session this page was in before it was reloaded (ticket
  /// 03). FOUR THINGS ABOUT IT, and each is a decision:
  ///
  ///   * IT IS DRIVEN BY THE SIDEBAR'S LISTING, not by a fetch of its own: the page
  ///     already reads every session of every project AND every task, so "is that id
  ///     still a session" is a question about an answer that is on its way anyway --
  ///     and a task can be the answer, which is why it is the whole listing that is
  ///     handed over rather than the projects inside it.
  ///   * IT HAPPENS ONCE, and only on the FIRST listing: it is a restore, not a
  ///     policy -- an id that disappears from the list later (somebody archived it)
  ///     leaves the page where it is.
  ///   * A REMEMBERED ID THAT IS GONE IS FORGOTTEN, and is not a sentence: a session
  ///     that was deleted, archived or moved by hand is not a situation anybody can act
  ///     on, so the page says nothing about it. What it does NOT do is keep a page with
  ///     no session on it.
  ///   * NOTHING TO RESTORE MEANS ASK FOR A SESSION, which is the other half of the
  ///     roster's starting empty: a first-ever load has no id to come back to, and the
  ///     page cannot make one up any more. It asks (`openFresh`), and the server names
  ///     the conversation -- so "somebody loaded the page and typed" is a session the
  ///     sidebar can list, with the log under the id the server chose.
  ///
  /// `sofar` rather than `rebuild` is the host's door here, and that is the whole
  /// ticket: the conversation may be IN THE MIDDLE OF A RUN, which rebuild refuses and
  /// which looking at must not disturb.
  const restored = useRef(false);
  const onListed = useCallback(
    (listing: SidebarListing) => {
      if (restored.current) return;
      restored.current = true;
      const listed = pending === null ? null : listedSession(pending, listing);
      if (pending !== null && listed !== null) {
        // NO LOG YET means the conversation is empty by construction -- a session made
        // on the sidebar and never run -- so there is nothing to read and nothing to
        // ask for; a session WITH a log is read through the door that may only look.
        show(pending, listed.bytes === null ? "none" : "sofar");
      } else {
        if (pending !== null) forgetSession(browserStorage(), pending);
        void openFresh();
      }
      setPending(null);
    },
    [openFresh, pending, show],
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

            AND IT IS NEVER UNMOUNTED, folded or not -- `folded` is a `hidden` class
            inside it, not a missing element. Its `refresh` is the only reader of
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
          openErrors={openErrors}
          onShow={showExisting}
          onShowFresh={showFresh}
          folded={folded}
          onCollapse={() => setFolded(true)}
        />
        {/* THE WAY BACK, and it lives here rather than in the sidebar for the
            reason the state does: a folded sidebar is hidden, and a hidden subtree
            cannot draw a control that is meant to be seen. */}
        {folded && <SidebarOpenButton onOpen={() => setFolded(false)} />}
        {/* `min-w-0` IS LOAD-BEARING, not tidiness: a flex item's automatic minimum
            width is its content's min-content width, and the trajectory's rows are
            single-line mono JSON with no spaces -- so without this the column
            refuses to be narrower than the longest argument list, and the page
            scrolls sideways with every preview running off the edge. The chat never
            needed it because its text wraps. */}
        <div className="min-h-0 min-w-0 flex-1">
          {/* A PAGE WITH NO SESSION SAYS SO, when the reason is that the ask for one
              failed: `openFresh` is the only way a session appears out of nothing, and a
              refusal there would otherwise be an empty column, which is what a page still
              loading looks like. The sentence is the server's own. */}
          {freshError !== null && roster.live.length === 0 && (
            <p data-slot="no-session" className="text-destructive p-6 text-sm">
              {freshError}
            </p>
          )}
          {roster.live.map((host) => (
            <SessionHost
              key={`${host.id}:${host.attempt}`}
              threadId={host.id}
              read={host.read}
              visible={host.id === roster.shown}
              onStatus={reportStatus}
              onForget={forgetStatus}
              onError={hostFailed}
              onRecord={reportRecord}
            >
              <SessionColumn
                threadId={host.id}
                view={view}
                onView={setView}
                folded={folded}
                record={records[host.id] ?? null}
              />
            </SessionHost>
          ))}
        </div>
      </div>
    </TooltipProvider>
  );
}

