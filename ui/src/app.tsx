// The page: one AG-UI agent wired straight to the harness, presented by
// assistant-ui.
//
// The browser talks to the harness directly -- there is no runtime in between,
// which is why the server carries CORS. The runtime built below is the AG-UI
// adapter: it owns AG-UI event parsing and message reconstruction
// (TEXT_MESSAGE_*, TOOL_CALL_*, THINKING_*/REASONING_*, STATE_SNAPSHOT, ...), so
// nothing here translates frames. We hand it an HttpAgent and it drives the
// thread -- messages, composer, auto-scroll, the welcome screen and the running
// state all come from <Thread/> off that runtime.
//
// The agent is built in a `useMemo`, not at module scope. Since ticket 06 it no
// longer owns the threadId: React state does (`threadId`, below), because the
// thread-list adapter's contract puts the id in the host's hands -- the runtime
// only reads it. The agent is written back before anything async happens, and
// every run still reads threadId off the agent (prepareRunAgentInput), so the
// wire is unchanged. The empty dependency list is load-bearing: rebuilding the
// agent on a re-render would throw the thread away mid-run. Module scope would
// look equivalent and would not be -- it survives Fast Refresh, quietly
// carrying a thread across edits while the developer believes they are looking
// at a fresh page.
//
// The `threadList` adapter is the session panel's spine, chosen over
// `adapters.history` because the page's job is "many threads, switch between
// them", which is exactly the adapter's shape; the reasoning is recorded once,
// in spec.md's decision 6. Its callbacks are where the ownership change is
// visible: both mint-or-adopt an id into React state and onto the agent BEFORE
// awaiting anything -- the adapter's hard rule -- and `onSwitchToThread` hands
// the runtime the rebuilt messages converted through `fromAgUiMessages`, the
// same conversion a history adapter would run. Restoring is refused while a
// run is in flight, from the runtime's own `isRunning`, with the reason
// surfaced on the row that was clicked.
//
// This ticket swaps the assembly again: the session panel's top strip is gone
// and the sidebar takes its place, beside the chat rather than above it. The
// sidebar owns the project grouping, the session rows and their refusals; this
// file keeps the two things it alone can do -- the agent and the runtime's
// thread-list adapter.
//
// Nothing about the ownership of the threadId changes here: React state still
// holds it, the adapter callbacks still mint-or-adopt an id BEFORE awaiting
// anything (the adapter's hard rule), and `onSwitchToThread` still hands the
// runtime the rebuilt messages converted through `fromAgUiMessages`. The refusal
// sentences moved to `lib/run-state.ts`, because the sidebar shows them and the
// adapter raises them -- the same reason a run in flight cannot be switched away
// from, worded once.
//
// The `threadList` adapter still carries ONLY the switching verbs. The list
// itself is the sidebar's own fetch of `GET /api/projects`, which no adapter can
// express: it is grouped by project and its rows carry a log's size and mtime,
// neither of which the runtime's thread shape has a field for.
//
// The `components` prop is where this repo's own rendering of tool calls and
// reasoning enters the copied element -- see `components/message-parts.tsx`,
// which also carries the one deliberate difference from upstream's defaults
// (everything arrives collapsed). It is a module-level constant so the object
// identity survives re-renders.
//
// `ApprovalBatchProvider` sits above the thread because a parked turn is
// answered as a batch, not a card at a time: AG-UI resumes with one response per
// open interrupt, so the decisions have to be collected somewhere that outlives
// the individual cards, and the cards are siblings with no owner of their own.
// It is above the thread, not inside it, because the thread is a copied file and
// this is ours -- and it has to be BELOW the runtime provider, since that is
// where it reads the pending interrupts from. See `components/approval-gate.tsx`.
//
// It also reports back up whether a gate is holding the run, and that closes the
// composer (`isSendDisabled`). The reason is in `approval-gate.tsx` and it is
// measured rather than assumed: a message sent while a gate is open is refused by
// the runtime and the refusal is silent -- the text is cleared from the composer
// and never arrives anywhere. Blocking is the only handling that does not eat
// what somebody typed. The state lives here, not in the provider, because
// `isSendDisabled` is an option of the hook called here.
import { HttpAgent } from "@ag-ui/client";
import { fromThreadMessageLike, type AssistantRuntime } from "@assistant-ui/core";
import { AssistantRuntimeProvider } from "@assistant-ui/react";
import { fromAgUiMessages, useAgUiRuntime } from "@assistant-ui/react-ag-ui";
import { useCallback, useMemo, useRef, useState } from "react";

import { Thread } from "@/components/assistant-ui/elements/thread.aui";
import { TooltipProvider } from "@/components/ui/tooltip";
import { ApprovalBatchProvider } from "@/components/approval-gate";
import { Sidebar } from "@/components/sidebar";
import { THREAD_COMPONENTS } from "@/components/message-parts";
import { AGENT_URL, rebuildThread } from "@/lib/threads";
import {
  RUN_IN_PROGRESS_NEW_THREAD_REFUSAL,
  RUN_IN_PROGRESS_REFUSAL,
  runInProgress,
} from "@/lib/run-state";

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

export function App() {
  // The threadId's owner since ticket 06. Minted here, written back to the
  // agent below, and adopted by `onSwitchToThread` when a session from the
  // list is opened.
  const [threadId, setThreadId] = useState<string>(() => crypto.randomUUID());
  // `threadId` is read once, at birth -- afterwards the adapter callbacks keep
  // the two in step, and the memo's empty deps keep the agent (and with it any
  // in-flight run) alive across re-renders.
  const agent = useMemo(() => {
    const created = new HttpAgent({ url: AGENT_URL });
    created.threadId = threadId;
    return created;
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);
  // Set by the approval gate below, read by the runtime on the next render.
  const [gateOpen, setGateOpen] = useState(false);
  // The switching guards read `isRunning` off the runtime, but the runtime
  // does not exist yet while the adapter object is being built -- the ref
  // closes that loop. Assigned right after the hook, before anything can click.
  const runtimeRef = useRef<AssistantRuntime | null>(null);

  // Id first, then await -- the thread-list adapter's hard rule: the selected
  // id is set before history is waited for, because the runtime discards the
  // messages of a switch that a later one superseded, and the id must already
  // name the winner when that verdict lands.
  const adoptThread = useCallback(
    (id: string) => {
      agent.threadId = id;
      setThreadId(id);
    },
    [agent],
  );

  const onSwitchToThread = useCallback(
    async (id: string) => {
      if (runtimeRef.current && runInProgress(runtimeRef.current)) {
        throw new Error(RUN_IN_PROGRESS_REFUSAL);
      }
      adoptThread(id);
      const rebuilt = await rebuildThread(id);
      return { messages: toThreadMessages(rebuilt.messages) };
    },
    [adoptThread],
  );

  // `onSwitchToNewThread` IS HERE, and only one thing calls it: removing the last
  // project. Everywhere else a new task goes through the sidebar's mint-bind-
  // switch, because a session belongs to a project and the id is what the bind
  // needs. Removing the last project is the one moment when there is nothing left
  // to bind to -- and the page must still get off the project that just went away
  // -- so this mints an id, adopts it, and lets the runtime show an empty thread.
  //
  // NO REBUILD, and that is the point rather than an omission: there is no
  // conversation under a brand-new id, so asking the server to rebuild one would
  // be asking it to find a file that is not there (a 404), and inventing an empty
  // conversation on the SERVER is exactly what `harness.replay/locate` refuses to
  // do. The client knows this thread is empty -- it just made the id up -- so the
  // empty conversation belongs here.
  const onSwitchToNewThread = useCallback(async () => {
    if (runtimeRef.current && runInProgress(runtimeRef.current)) {
      throw new Error(RUN_IN_PROGRESS_NEW_THREAD_REFUSAL);
    }
    adoptThread(crypto.randomUUID());
  }, [adoptThread]);

  const runtime = useAgUiRuntime({
    agent,
    isSendDisabled: gateOpen,
    adapters: {
      threadList: {
        threadId,
        onSwitchToThread,
        onSwitchToNewThread,
      },
    },
  });
  runtimeRef.current = runtime;

  return (
    <AssistantRuntimeProvider runtime={runtime}>
      {/* The copied icon buttons (scroll-to-bottom, copy, ...) mount a Radix
          tooltip, and Radix throws when no provider sits above it. */}
      <TooltipProvider>
        <ApprovalBatchProvider onHoldChange={setGateOpen}>
          {/* The ROW is the viewport, and the two children each get their height
              from it: the sidebar is a fixed-width `shrink-0` column, the chat
              is `min-h-0 flex-1`. `Thread`'s root is `h-full`, so it reads the
              height off this wrapper -- which is why `min-h-0` is here and not on
              the thread: without it a flex child will not shrink below its
              content, and the whole page scrolls instead of the message list. */}
          <div className="flex h-dvh">
            <Sidebar runtime={runtime} currentThreadId={threadId} />
            <div className="min-h-0 flex-1">
              <Thread components={THREAD_COMPONENTS} />
            </div>
          </div>
        </ApprovalBatchProvider>
      </TooltipProvider>
    </AssistantRuntimeProvider>
  );
}
