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

import { HttpAgent } from "@ag-ui/client";
import { fromThreadMessageLike } from "@assistant-ui/core";
import { AssistantRuntimeProvider, useAuiState } from "@assistant-ui/react";
import {
  fromAgUiMessages,
  useAgUiInterrupts,
  useAgUiRuntime,
} from "@assistant-ui/react-ag-ui";
import { useCallback, useEffect, useMemo, useRef, useState, type FC, type ReactNode } from "react";
import { useTranslation } from "react-i18next";

import { Thread } from "@/components/assistant-ui/elements/thread.aui";
import { ThreadIdContext } from "@/components/composer-chrome";
import { TrajectoryView } from "@/components/trajectory-view";
import { TooltipProvider } from "@/components/ui/tooltip";
import {
  ApprovalBatchProvider,
  isParkedInterrupt,
} from "@/components/approval-gate";
import { Sidebar } from "@/components/sidebar";
import { THREAD_COMPONENTS } from "@/components/message-parts";
import { imageAttachments } from "@/lib/attachments";
import { type ProjectSummary } from "@/lib/projects";
import {
  browserStorage,
  forgetSession,
  listedSession,
  rememberedSession,
  rememberSession,
} from "@/lib/session-memory";
import { AGENT_URL, rebuildThread, sofarThread, type SofarState } from "@/lib/threads";
import { type SessionStatus } from "@/lib/session-status";

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
  const converted = fromAgUiMessages(agUiMessages);
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
function sessionHistory(
  threadId: string,
  read: HistoryRead,
  t: TFunction<"errors">,
  onReads: (reads: Reads) => void,
) {
  return {
    load: async () => {
      if (read === "none") return { messages: [] };
      if (read === "rebuild") {
        const rebuilt = await rebuildThread(threadId, t);
        return repositoryFrom(rebuilt.messages);
      }
      const answer = await readSofar(threadId, t);
      onReads(answer.state);
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
  children: ReactNode;
}> = ({ threadId, read, visible, onStatus, onForget, onError, children }) => {
  // The agent is built ONCE for this host and owns this session's id for the
  // host's whole life. Rebuilding it would throw the thread away mid-run -- the
  // same reason the old single-agent memo had an empty dependency list, paid per
  // session now instead of once for the page.
  const agent = useMemo(() => {
    const created = new HttpAgent({ url: AGENT_URL });
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

  const history = useMemo(
    () => sessionHistory(threadId, read, tErrors, onReads),
    [threadId, read, tErrors, onReads],
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
  }, [read, readCount, threadId, runtime, tErrors]);

  return (
    <AssistantRuntimeProvider runtime={runtime}>
      <SessionStatusReporter
        threadId={threadId}
        onStatus={onStatus}
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
}> = ({ threadId, view, onView }) => {
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
          className="flex shrink-0 items-center gap-1 border-b border-border px-3 py-1.5"
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
        <div className="min-h-0 flex-1">
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
  // THE ROSTER. The first session is minted here and hosted EMPTY: a brand-new
  // id has no log, and the server refuses to invent a conversation for one.
  const [roster, setRoster] = useState<Roster>(() => {
    const id = crypto.randomUUID();
    return { shown: id, live: [{ id, read: "none", attempt: 0 }] };
  });
  // One answer per session, reported by its host and read by the sidebar.
  const [statuses, setStatuses] = useState<Record<string, SessionStatus>>({});
  // The sessions whose history would not load, keyed by session, so the refusal
  // lands on the row that was clicked.
  const [openErrors, setOpenErrors] = useState<Record<string, string>>({});
  // WHICH VIEW THE COLUMN SHOWS. Session-scoped UI state and deliberately NOT
  // persisted: it is a way of looking at the conversation in front of you, not a
  // preference about sessions, and a stored one would surprise a reader on the
  // next launch.
  const [view, setView] = useState<"conversation" | "trajectory">("conversation");

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

  /// Show a session this client has just minted (and, for every path that has a
  /// project, just bound): nothing to rebuild, so no load.
  const showFresh = useCallback(
    (id: string) => {
      show(id, "none");
    },
    [show],
  );
  const showExisting = useCallback(
    (id: string) => {
      show(id, "rebuild");
    },
    [show],
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

  /// THE MOUNT RESTORE: the session this page was in before it was reloaded (ticket
  /// 03). THREE THINGS ABOUT IT, and each is a decision:
  ///
  ///   * IT IS DRIVEN BY THE SIDEBAR'S LISTING, not by a fetch of its own: the page
  ///     already reads every session of every project, so "is that id still a
  ///     session" is a question about an answer that is on its way anyway.
  ///   * IT HAPPENS ONCE, and only on the FIRST listing: it is a restore, not a
  ///     policy -- an id that disappears from the list later (somebody archived it)
  ///     leaves the page where it is.
  ///   * A REMEMBERED ID THAT IS GONE FALLS BACK TO THE FRESH SESSION THE ROSTER
  ///     ALREADY HAS, silently. A session that was deleted, archived or moved by hand
  ///     is not a situation anybody can act on, so it is not a sentence either; the
  ///     ID IS FORGOTTEN so the next reload does not ask again.
  ///
  /// `sofar` rather than `rebuild` is the host's door here, and that is the whole
  /// ticket: the conversation may be IN THE MIDDLE OF A RUN, which rebuild refuses and
  /// which looking at must not disturb.
  const restored = useRef(false);
  const onListed = useCallback(
    (projects: readonly ProjectSummary[]) => {
      if (restored.current || pending === null) return;
      restored.current = true;
      const listed = listedSession(pending, projects);
      if (listed !== null) {
        // NO LOG YET means the conversation is empty by construction -- a session made
        // on the sidebar and never run -- so there is nothing to read and nothing to
        // ask for; a session WITH a log is read through the door that may only look.
        show(pending, listed.bytes === null ? "none" : "sofar");
      } else {
        forgetSession(browserStorage(), pending);
      }
      setPending(null);
    },
    [pending, show],
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
          page scrolls instead of the message list. */}
      <div className="flex h-dvh">
        {/* OUTSIDE every runtime provider now, because it manages ALL sessions:
            its rows, their refusal sentences, and the projects they belong to.
            It used to sit inside the one provider only to reach
            `runtime.threads.switchToThread`, and it no longer has one. */}
        <Sidebar
          currentThreadId={roster.shown}
          onListed={onListed}
          statuses={statuses}
          openErrors={openErrors}
          onShow={showExisting}
          onShowFresh={showFresh}
        />
        {/* `min-w-0` IS LOAD-BEARING, not tidiness: a flex item's automatic minimum
            width is its content's min-content width, and the trajectory's rows are
            single-line mono JSON with no spaces -- so without this the column
            refuses to be narrower than the longest argument list, and the page
            scrolls sideways with every preview running off the edge. The chat never
            needed it because its text wraps. */}
        <div className="min-h-0 min-w-0 flex-1">
          {roster.live.map((host) => (
            <SessionHost
              key={`${host.id}:${host.attempt}`}
              threadId={host.id}
              read={host.read}
              visible={host.id === roster.shown}
              onStatus={reportStatus}
              onForget={forgetStatus}
              onError={hostFailed}
            >
              <SessionColumn threadId={host.id} view={view} onView={setView} />
            </SessionHost>
          ))}
        </div>
      </div>
    </TooltipProvider>
  );
}

