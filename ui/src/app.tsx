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
// The agent is built in a `useMemo`, not at module scope. It is the object that
// owns the conversation: HttpAgent mints a threadId at construction and holds
// it, and every run reads threadId + messages off it (prepareRunAgentInput).
// The adapter never mints one either -- it reads `agent.threadId`, falling back
// to "main" only if that is empty (AgUiThreadRuntimeCore). One agent per mounted
// page is therefore one thread per page, and that is the whole threadId story
// for this ticket; spec.md decision 6 carries the verification.
//
// The empty dependency list is load-bearing: rebuilding the agent on a re-render
// would throw the thread away mid-run. Module scope would look equivalent and
// would not be -- it survives Fast Refresh, quietly carrying a thread across
// edits while the developer believes they are looking at a fresh page.
//
// This ticket swaps the assembly: CopilotKit's provider and its chat are gone
// from the page. Four things that used to sit above the chat retired with it --
// the approval gate, the reasoning message, the session panel and the project
// panel -- and tickets 04-07 bring them back one at a time. So the page is, for
// now, only the thread. Nothing was lost: the retired versions are in the
// history, and their absence here is the price of doing the high-risk swap on
// its own.
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
import { AssistantRuntimeProvider } from "@assistant-ui/react";
import { useAgUiRuntime } from "@assistant-ui/react-ag-ui";
import { useMemo, useState } from "react";

import { Thread } from "@/components/assistant-ui/elements/thread.aui";
import { TooltipProvider } from "@/components/ui/tooltip";
import { ApprovalBatchProvider } from "@/components/approval-gate";
import { THREAD_COMPONENTS } from "@/components/message-parts";

/// The AG-UI endpoint. The trailing slash is the server's route; the origin is
/// also where the management endpoints live (project binding, the thread list),
/// which tickets 06 and 07 read back out of here.
const AGENT_URL = "http://localhost:8080/";

export function App() {
  const agent = useMemo(() => new HttpAgent({ url: AGENT_URL }), []);
  // Set by the approval gate below, read by the runtime on the next render.
  const [gateOpen, setGateOpen] = useState(false);
  const runtime = useAgUiRuntime({ agent, isSendDisabled: gateOpen });

  return (
    <AssistantRuntimeProvider runtime={runtime}>
      {/* The copied icon buttons (scroll-to-bottom, copy, ...) mount a Radix
          tooltip, and Radix throws when no provider sits above it. */}
      <TooltipProvider>
        {/* Thread's root is `h-full`, so it needs a parent that actually has a
            height -- `h-dvh` is the viewport. Tickets 06 and 07 add their panels
            above this line and must not steal that height from it. */}
        <ApprovalBatchProvider onHoldChange={setGateOpen}>
          <div className="h-dvh">
            <Thread components={THREAD_COMPONENTS} />
          </div>
        </ApprovalBatchProvider>
      </TooltipProvider>
    </AssistantRuntimeProvider>
  );
}
