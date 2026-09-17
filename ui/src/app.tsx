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
import { useCallback, useEffect, useMemo, useState, type FC, type ReactNode } from "react";
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
import { AGENT_URL, rebuildThread } from "@/lib/threads";
import { type SessionStatus } from "@/lib/session-status";

/// The converted history a restore hands the runtime: `fromAgUiMessages`
/// rebuilds text, reasoning and tool calls -- and reads back
/// `metadata.custom.agui.interrupts` when the log carried them -- but its
/// output is still the loose `ThreadMessageLike` shape; the repository wants
/// the finished one. The runtime's own snapshot-import path runs this exact
/// pair (AgUiThreadRuntimeCore.importMessagesSnapshot), so the conversion is
/// upstream's, quoted rather than reinvented.
function toThreadMessages(agUiMessages: readonly unknown[]) {
  return fromAgUiMessages(agUiMessages).map((message) =>
    fromThreadMessageLike(message, message.id ?? crypto.randomUUID(), {
      type: "complete",
      reason: "unknown",
    }),
  );
}

/// The rebuilt messages as the repository the history adapter returns: a flat
/// chain, each message parented to the one before it. Built here rather than with
/// `ExportedMessageRepository.fromArray` because that helper assigns fresh ids,
/// and these messages already have the ids the runtime recorded.
function repositoryFrom(agUiMessages: readonly unknown[]) {
  const messages = toThreadMessages(agUiMessages);
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
function sessionHistory(threadId: string, hydrate: boolean, t: TFunction<"errors">) {
  return {
    load: async () => {
      if (!hydrate) return { messages: [] };
      const rebuilt = await rebuildThread(threadId, t);
      return repositoryFrom(rebuilt.messages);
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
  hydrate: boolean;
  visible: boolean;
  onStatus: (id: string, status: SessionStatus) => void;
  onForget: (id: string) => void;
  onError: (id: string, message: string) => void;
  children: ReactNode;
}> = ({ threadId, hydrate, visible, onStatus, onForget, onError, children }) => {
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

  const history = useMemo(
    () => sessionHistory(threadId, hydrate, tErrors),
    [threadId, hydrate, tErrors],
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
type HostSpec = { id: string; hydrate: boolean; attempt: number };

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
    return { shown: id, live: [{ id, hydrate: false, attempt: 0 }] };
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
    (id: string, hydrate: boolean = true) => {
      setRoster((prev) => {
        const existing = prev.live.find((host) => host.id === id);
        let live = prev.live;
        if (existing === undefined) {
          live = [...live, { id, hydrate, attempt: 0 }];
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
      show(id, false);
    },
    [show],
  );
  const showExisting = useCallback(
    (id: string) => {
      show(id, true);
    },
    [show],
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
              hydrate={host.hydrate}
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

