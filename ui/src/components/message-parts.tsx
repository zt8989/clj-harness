// The assistant message's parts: tool calls and reasoning.
//
// <Thread/> already knows how to render text, tool calls and reasoning; what it
// cannot know is what THIS repo wants them to look like. The three slots it
// leaves open are filled here:
//
//   ToolFallback    what an unregistered tool call looks like
//   ToolGroup       the wrapper around a run of adjacent tool calls
//   ReasoningGroup  the wrapper around a run of adjacent reasoning parts
//
// A named renderer registered by tool name (`part.toolUI`, resolved from the
// tool-UI registry in `thread.aui.tsx`) takes precedence over `ToolFallback` --
// that ordering is upstream's and is deliberately not re-implemented here. (It
// was verified rather than assumed: for one run, a throwaway renderer was
// registered for `read`, and the `read` calls drew it while `write` and `bash`
// still fell through to the card below.) No tool in this repo needs its own
// presentation today: all six are a name, some arguments and a result, and the
// the pre-runtime page used a single wildcard renderer for exactly the same
// reason. So the fallback is the only renderer registered, and a new tool shows
// up in the transcript with no front-end change -- which is the property worth
// keeping.
//
// ------------------------------------------------------- up front: collapsed
//
// Tool cards and reasoning start CLOSED and open only when clicked -- the
// content is never on screen until somebody asks for it. For reasoning that is
// the opposite of upstream, which holds the disclosure open while tokens stream
// (`streaming`) and snaps it shut when the run ends. We simply never pass
// `streaming`, so the resting state is closed and the reader is never ambushed
// by a panel that opened itself.
//
// The "still working" signal therefore moves onto the collapsed header: a
// spinning icon plus a status word for a tool call, a shimmering label for
// reasoning. Both are visible without opening anything, and both stop when the
// work stops.
//
// The one thing left open is the tool group, and it is not a contradiction: the
// group holds no content of its own, only the cards, so opening it reveals the
// tool names while every argument and every result stays folded one level down.
// `ToolCallsGroup` below carries that argument in full.
//
// `showThinking` (a `useAgUiRuntime` option, default true) is left alone: it is
// the switch that turns THINKING_*/REASONING_* events into parts at all, so
// turning it off would leave the reasoning slot with nothing to draw.
//
// ------------------------------------------------- and one thing beside them
//
// A tool call the run parked for approval gets a card of its own, drawn under
// the (still folded) tool card -- `approval-gate.tsx` mounts from `ToolCallCard`
// and carries the argument for where the buttons live, which seam answers them
// and why that card is written here instead of reused from the copied kit. It
// is not a fourth slot: it hangs off the same part, so it arrives with the same
// signal (`requires-action`) that tells the tool card what state it is in.
//
// Nothing in `components/assistant-ui/elements/` was edited to get this. A slot
// override keeps this file ours and the copied files byte-comparable on the
// next registry pull; the upstream pieces used are the copied atoms and
// disclosure shells -- `ToolFallbackRoot` / `ToolFallbackContent` /
// `ToolFallbackError` (animation, scroll lock, error block), `ToolGroupRoot` /
// `ToolGroupTrigger` / `ToolGroupContent`, and `ReasoningRoot` /
// `ReasoningTrigger` / `ReasoningContent` / `ReasoningText`.
import { type ElementType, type FC, type PropsWithChildren } from "react";
import {
  AlertCircleIcon,
  CheckIcon,
  ChevronDownIcon,
  LoaderIcon,
  XCircleIcon,
} from "lucide-react";
import { useAgUiInterrupts } from "@assistant-ui/react-ag-ui";
import {
  useToolCallElapsed,
  type ToolCallMessagePartComponent,
  type ToolCallMessagePartStatus,
} from "@assistant-ui/react";

import {
  ApprovalGate,
  isApprovalInterrupt,
} from "@/components/approval-gate";
import {
  ReasoningContent,
  ReasoningRoot,
  ReasoningText,
  ReasoningTrigger,
} from "@/components/assistant-ui/elements/reasoning.aui";
import type {
  ThreadComponents,
  ThreadGroupPart,
} from "@/components/assistant-ui/elements/thread.aui";
import {
  ToolGroupContent,
  ToolGroupRoot,
  ToolGroupTrigger,
} from "@/components/assistant-ui/elements/tool-group.aui";
import {
  ToolFallbackContent,
  ToolFallbackError,
  ToolFallbackRoot,
} from "@/components/assistant-ui/elements/tool-fallback.aui";
import { CollapsibleTrigger } from "@/components/ui/collapsible";
import { cn } from "@/lib/utils";

// ------------------------------------------------------------- tool call card

/// The vocabulary the card draws from: every state a tool call can be in.
type CallState =
  | "running"
  | "done"
  | "failed"
  | "cancelled"
  | "needs-approval";

/// How each state is drawn: the word and the mark, in one table. Two parallel
/// `Record<CallState, ...>`s would say the same thing in two places; here a
/// state cannot gain a word without a mark.
///
/// Upstream draws these states as icons only. This repo spells them out as well,
/// because a checkmark and a cross are easy to miss and a card whose state is
/// implied is a card whose state gets misread.
///
/// The icons are typed `ElementType` rather than `typeof LoaderIcon`: the table
/// holds five different icons, and naming one of them as the type of all of them
/// says the wrong thing. It is also what upstream's own `statusIconMap` is typed
/// as, and `lucide-react@1.46` exports no `LucideIcon` to reach for instead.
const CALL_STATES: Record<CallState, { label: string; icon: ElementType }> = {
  running: { label: "Running", icon: LoaderIcon },
  done: { label: "Done", icon: CheckIcon },
  failed: { label: "Failed", icon: XCircleIcon },
  cancelled: { label: "Cancelled", icon: XCircleIcon },
  "needs-approval": { label: "Needs approval", icon: AlertCircleIcon },
};

/// The state, read from the two places that report one.
///
/// `isError` is the wire's own "this result is an error" flag and wins over
/// everything: a call that came back flagged is failed whether or not the
/// status agrees. Otherwise the status decides, and its `incomplete` covers two
/// different things -- a call the human stopped ("cancelled") and a call that
/// broke. Note that in THIS repo a tool that fails does not fail the run: the
/// harness hands the failure back to the model as ordinary result text (see
/// `harness.tools`), so `failed` here means the call itself did not finish, not
/// that the tool did not like its input.
function callState(
  status: ToolCallMessagePartStatus | undefined,
  isError: boolean | undefined,
): CallState {
  if (isError === true) return "failed";
  switch (status?.type) {
    case "running":
      return "running";
    case "requires-action":
      return "needs-approval";
    case "incomplete":
      return status.reason === "cancelled" ? "cancelled" : "failed";
    default:
      return "done";
  }
}

/// Elapsed milliseconds, in the same buckets upstream's duration uses: sub-second
/// is "<1s", then one decimal, then whole seconds, then minutes and seconds.
function formatDuration(ms: number): string {
  if (ms < 1000) return "<1s";
  const seconds = ms / 1000;
  if (seconds < 10) return `${(Math.floor(seconds * 10) / 10).toFixed(1)}s`;
  if (seconds < 60) return `${Math.floor(seconds)}s`;
  return `${Math.floor(seconds / 60)}m ${Math.floor(seconds % 60)}s`;
}

/// The header row: what was called, what it is doing, and how long it has been
/// doing it. `useToolCallElapsed` reads the part's timing and returns undefined
/// when the runtime recorded none, in which case the duration simply is not
/// drawn -- an absent number, not a zero.
const ToolCallTrigger: FC<{ toolName: string; state: CallState }> = ({
  toolName,
  state,
}) => {
  const elapsedMs = useToolCallElapsed();
  const { label, icon: Icon } = CALL_STATES[state];
  const isRunning = state === "running";

  return (
    <CollapsibleTrigger
      data-slot="tool-call-trigger"
      className="aui-tool-call-trigger group/trigger text-muted-foreground hover:text-foreground flex w-fit max-w-full origin-left items-center gap-2 py-1.5 text-sm transition-[color,scale] active:scale-[0.98]"
    >
      <Icon
        data-slot="tool-call-trigger-icon"
        className={cn(
          "aui-tool-call-trigger-icon size-4 shrink-0",
          isRunning && "animate-spin [animation-duration:0.6s]",
          state === "failed" && "text-destructive",
        )}
      />
      <span
        data-slot="tool-call-trigger-label"
        className={cn(
          "aui-tool-call-trigger-label inline-block min-w-0 text-start leading-none",
          state === "cancelled" && "line-through",
          isRunning && "shimmer motion-reduce:animate-none",
        )}
      >
        <b className="aui-tool-call-trigger-name break-all">{toolName}</b>
        <span className="aui-tool-call-trigger-state">
          {" · "}
          {label}
        </span>
      </span>
      {elapsedMs !== undefined && (
        <span
          data-slot="tool-call-trigger-duration"
          className="aui-tool-call-trigger-duration shrink-0 text-xs tabular-nums"
        >
          {formatDuration(elapsedMs)}
        </span>
      )}
      <ChevronDownIcon
        data-slot="tool-call-trigger-chevron"
        className={cn(
          "aui-tool-call-trigger-chevron size-4 shrink-0",
          "transition-transform duration-(--animation-duration) ease-[cubic-bezier(0.32,0.72,0,1)] motion-reduce:transition-none",
          "-rotate-90",
          "group-data-open/trigger:rotate-0",
        )}
      />
    </CollapsibleTrigger>
  );
};

/// The arguments, as the model wrote them.
///
/// `argsText` is the raw JSON the model streamed -- not a re-serialized object
/// -- and it is shown whole. No clipping, no summarising, no max-height: the
/// point of opening this panel is to see exactly what is about to be executed,
/// and a viewer that shortens that is worse than not having one. (It wraps
/// rather than scrolls horizontally so a long path or a long string stays
/// readable in a narrow thread.)
const ToolCallArgs: FC<{ argsText: string }> = ({ argsText }) => {
  if (argsText === "") return null;

  return (
    <div data-slot="tool-call-args" className="aui-tool-call-args flex flex-col">
      <p className="aui-tool-call-args-header text-muted-foreground text-xs font-medium">
        Arguments
      </p>
      <pre className="aui-tool-call-args-value bg-muted/50 text-foreground/90 mt-1 rounded-md p-2.5 text-xs whitespace-pre-wrap">
        {argsText}
      </pre>
    </div>
  );
};

/// A result, rendered the way a value can be rendered: a string is shown as
/// itself (tool results are text on the wire), anything else is pretty-printed,
/// and a value that cannot be serialised says so instead of throwing.
function resultText(result: unknown): string | null {
  if (result === undefined || result === null) return null;
  if (typeof result === "string") return result;
  if (result instanceof Error) return String(result);

  try {
    const json = JSON.stringify(result, null, 2);
    if (json !== undefined) return json;
  } catch {
    // fall through to String()
  }

  try {
    return String(result);
  } catch {
    return "[Unserializable value]";
  }
}

/// What upstream's error block is about to print, or null when it stays quiet.
///
/// `<ToolFallbackError>` reports `status.error` for an incomplete call, and the
/// card needs to know whether it did, because that block is the card's account
/// of the failure: a broken call must not be reported twice, once as
/// `status.error` and once as the result. The formatting below differs from
/// upstream's by indentation only, which cannot change whether there is
/// anything to print.
function statusErrorText(status: ToolCallMessagePartStatus | undefined): string | null {
  if (status?.type !== "incomplete") return null;

  const text = resultText(status.error);
  return text !== null && text.trim() !== "" ? text : null;
}

/// The result block, or an explicit "No result".
///
/// Upstream renders nothing at all when the result is absent, which reads
/// exactly like a card whose result failed to render. A finished call with
/// nothing to show is a fact about the call, so it is printed as one.
///
/// `failureExplained` says the error block above already reported why this call
/// broke. Then this block stops being a verdict and becomes a payload: the
/// heading drops to "Result:" instead of a second "Error:", and "No result" is
/// suppressed -- a call that never finished has no result to be missing, and
/// saying so under the reason reads as a second, milder finding about one event.
const ToolCallResult: FC<{
  result: unknown;
  failed: boolean;
  failureExplained: boolean;
}> = ({ result, failed, failureExplained }) => {
  const text = resultText(result);

  if (text === null || text.trim() === "") {
    if (failureExplained) return null;

    return (
      <p
        data-slot="tool-call-no-result"
        className="aui-tool-call-no-result text-muted-foreground text-xs"
      >
        No result
      </p>
    );
  }

  return (
    <div
      data-slot="tool-call-result"
      className="aui-tool-call-result flex flex-col"
    >
      <p className="aui-tool-call-result-header text-muted-foreground text-xs font-medium">
        {failed && !failureExplained ? "Error:" : "Result:"}
      </p>
      <pre className="aui-tool-call-result-value bg-muted/50 text-foreground/90 mt-1 rounded-md p-2.5 text-xs whitespace-pre-wrap">
        {text}
      </pre>
    </div>
  );
};

/// The card itself. Not memoized on purpose: a call re-renders while its
/// arguments and its status arrive -- that is every delta of the call -- so a
/// shallow compare would buy nothing and would risk holding a stale state.
const ToolCallCard: ToolCallMessagePartComponent = ({
  toolCallId,
  toolName,
  argsText,
  result,
  status,
  isError,
}) => {
  // Parked is read from TWO places, and the second one is not a belt-and-
  // braces duplication. The runtime opens a new client-side assistant message
  // for every server TEXT_MESSAGE, finalizing the previous one as `complete`
  // (its `beginDistinctTextMessage` wipes its accumulation and the interrupt
  // metadata rides only on the LAST message of a run). A turn that parks two
  // calls therefore lands its first parked call in an already-finalized
  // message, where the part's status says `complete` -- "Done", by the table
  // above -- while the server holds the run open for exactly that call.
  // Trusting the status alone would draw that card as finished and mount no
  // gate on it, and since the runtime refuses a partial resume, the batch
  // could never be answered: a deadlock the user can see only as a composer
  // that never reopens. Found by running the two-call batch in a real
  // browser, not by reading this code; the pending-interrupt check is what
  // makes the card honest again.
  const parkedByInterrupt = useAgUiInterrupts().some(
    (candidate) =>
      isApprovalInterrupt(candidate) && candidate.toolCallId === toolCallId,
  );
  const state = parkedByInterrupt
    ? "needs-approval"
    : callState(status, isError);

  // While the call is still in flight -- running, or parked for a decision --
  // there is no result to report, and "No result" would be a lie: the answer is
  // "not yet". The collapsed header already says which of the two it is.
  const settled = state === "done" || state === "failed";

  // When upstream's error block has something to say about a call that broke,
  // it says all of it; see `statusErrorText`.
  const failureExplained = statusErrorText(status) !== null;

  return (
    <>
      {/* Uncontrolled, and `defaultOpen` stays at its default of false: this is
          the whole of "collapsed by default" for a tool call. Nothing here opens
          the card on its own, and that is now true of a parked call too -- the
          card that says `Needs approval` is as folded as any other, and the
          decision it is waiting for is a block of its own underneath (below),
          drawn at full width so it cannot be missed. */}
      <ToolFallbackRoot>
        <ToolCallTrigger toolName={toolName} state={state} />
        <ToolFallbackContent>
          <ToolFallbackError status={status} />
          <ToolCallArgs argsText={argsText} />
          {/* A cancelled call shows its reason, not a result -- there is none to
              show, and upstream's card drew the same line. */}
          {settled ? (
            <ToolCallResult
              result={result}
              failed={isError === true || state === "failed"}
              failureExplained={failureExplained}
            />
          ) : null}
        </ToolFallbackContent>
      </ToolFallbackRoot>

      {/* The approval card, OUTSIDE the disclosure: a gate whose buttons need a
          click to reveal is a gate that can be overlooked, and this one is
          holding a run. `requires-action` is the protocol's own "a human has to
          decide" signal -- see `approval-gate.tsx`, which also argues why this
          is written here rather than reused from the copied kit. */}
      {state === "needs-approval" ? (
        <ApprovalGate
          toolCallId={toolCallId}
          toolName={toolName}
          argsText={argsText}
        />
      ) : null}
    </>
  );
};

// ------------------------------------------------------------------ the group

/// A run of adjacent tool calls: a header that counts them, and then the cards
/// themselves.
///
/// This slot is overridden for one prop. Upstream's group starts closed, and
/// with the cards also starting closed a run of four calls would read as
/// "4 tool calls" and nothing else -- the tool NAMES would be off screen until
/// the group was opened. The page this replaces listed every call with its name
/// and its status and folded only the details, so a transcript you cannot read
/// the tool names off is a regression dressed up as tidiness. The group
/// therefore opens by default while each card inside stays folded: names and
/// statuses at a glance, arguments and results one click each. It can still be
/// closed by hand, and the wrapper still only wraps -- every call in it renders
/// through its own card, so concurrent calls are neither merged nor lost.
const ToolCallsGroup: FC<PropsWithChildren<{ group: ThreadGroupPart }>> = ({
  group,
  children,
}) => {
  const running = group.status.type === "running";

  return (
    <ToolGroupRoot variant="ghost" defaultOpen>
      <ToolGroupTrigger count={group.indices.length} active={running} />
      <ToolGroupContent>{children}</ToolGroupContent>
    </ToolGroupRoot>
  );
};

// ---------------------------------------------------------------- reasoning

/// A run of adjacent reasoning parts, behind one collapsed header.
///
/// `streaming` is deliberately not passed, and that is the entire difference
/// from upstream's reasoning group: without it the disclosure's open state is
/// the reader's alone (`userOpen ?? false`), so thinking arrives folded and
/// stays folded. A shimmering label marks the live run instead.
const ReasoningBlock: FC<PropsWithChildren<{ group: ThreadGroupPart }>> = ({
  group,
  children,
}) => {
  const running = group.status.type === "running";

  return (
    <ReasoningRoot>
      <ReasoningTrigger active={running} />
      <ReasoningContent aria-busy={running}>
        <ReasoningText>{children}</ReasoningText>
      </ReasoningContent>
    </ReasoningRoot>
  );
};

// ------------------------------------------------------------------ the slots

/// The thread's component overrides, module-level so the object identity is
/// stable. `components` is a prop on `<Thread/>`: a fresh object per render
/// would invalidate the copied element's context and re-render the whole
/// message subtree on every keystroke in the composer.
///
/// All three slots the copied element leaves open are filled, and each override
/// earns its place: the card states a status in words and reports an absent
/// result instead of showing nothing, the group opens by default so the tool
/// names are readable, and the reasoning group never streams its disclosure
/// open. Everything else -- the message list, the text parts, the welcome
/// screen, the composer -- stays upstream's.
export const THREAD_COMPONENTS: ThreadComponents = {
  ToolFallback: ToolCallCard,
  ToolGroup: ToolCallsGroup,
  ReasoningGroup: ReasoningBlock,
};
