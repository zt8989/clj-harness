// The assistant message's parts: tool calls and reasoning.
//
// <Thread/> already knows how to render text, tool calls and reasoning; what it
// cannot know is what THIS repo wants them to look like. The three slots it
// leaves open are filled here:
//
//   ToolFallback    what an unregistered tool call looks like
//   ToolGroup       where a run of adjacent tool calls would be wrapped -- a
//                   passthrough here, and the reason is below
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
// Tool cards start CLOSED and open only when clicked -- the content is never on
// screen until somebody asks for it.
//
// A THOUGHT IS THE ONE EXCEPTION, and it is the reader's own request: while its
// tokens stream, the disclosure opens itself and shows the thinking as it
// arrives. A row whose label is the thought's first line stops moving a second
// in, and the thing worth watching is the thought itself. It is upstream's
// behaviour (`streaming`), kept for upstream's reason -- the live window follows
// the newest token, and the panel folds itself when the thought ends -- with this
// repo's row around it. See `ReasoningBlock` for what it costs and what it does
// not change.
//
// The "still working" signal is on the collapsed ROW as well, not only in the
// panel: a spinning mark at its end for a tool call, a shimmering label for
// reasoning. Both are visible without opening anything, and both stop when the
// work stops.
//
// ------------------------------------------------------- what a row says it is
//
// A row is three things, left to right: an icon saying which KIND of call this
// is, the tool's name, and a one-line SUBJECT -- what this particular call is
// about. The subject is a PROJECTION of the call's arguments rather than a
// truncation of them: a clipped `{"path": "/Users/..."}` still reads as JSON,
// while `read · /Users/.../CONTEXT.md` reads as a sentence. The projection is a
// closed table (`subjectOf` below), one line per tool, and a tool nobody has
// taught this page about falls through to its first string argument -- which is
// what keeps "a new tool shows up with no front-end change" true.
//
// The state keeps a place of its own at the END of the row, because the middle
// now belongs to the subject: a mark (spinner, tick, cross, exclamation) plus the
// elapsed time it always had. The state WORD is gone from the row -- a status
// word in the `·` slot was what the subject needed -- but the state is not
// quieter for it: the mark still spins while running, turns destructive on a
// failure, the label is struck through when a call is cancelled, and a parked
// call still gets its "Needs approval" phrasing as accessible text on the mark
// (and its decision card, full width, below the row).
//
// -------------------------------------------------- one row, two kinds of step
//
// Reasoning is NOT a card here. Upstream draws it in one -- `ReasoningRoot`'s
// default variant is `outline`, a rounded, bordered box -- and a bordered box
// next to a bare `read` row reads as a different KIND of thing rather than the
// next step of the same turn. So the reasoning disclosure is `variant="ghost"`
// and its row is written in this file, deliberately shaped like `ToolCallTrigger`
// (see `ReasoningTrigger` below). The disclosure SHELL is still the copied kit's:
// the scroll lock, the fades and the animation are not ours to re-derive.
//
// The tool group draws nothing, and that is the whole of it: a run of tool calls
// is a run of ROWS, one per call. It used to carry a header -- "N tool calls" --
// and the count was never anything but 1. `groupPartByType` groups tool calls
// even when there is only one of them (upstream's own comment says so), and one
// tool call IS one assistant message on this wire (measured; see
// `.scratch/assistant-ui/issues/04`, section 8), so the page read "1 tool call"
// before every single call rather than "4 tool calls" once. The header's stated
// justification -- open it by default so the tool names stay readable -- was
// written for a premise that does not hold on the real page: it unfolded to
// reveal its own one row. `FlatToolGroup` below carries the rest.
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
// `ToolFallbackError` (animation, scroll lock, error block), and -- for
// reasoning -- the shell only: `ReasoningRoot` / `ReasoningContent` /
// `ReasoningText`. The group's own pieces are no longer imported at all: the
// slot draws nothing.
import { type ElementType, type FC, type PropsWithChildren } from "react";
import {
  AlertCircleIcon,
  BetweenHorizontalStartIcon,
  BracesIcon,
  BrainIcon,
  CheckIcon,
  FilePenLineIcon,
  FileTextIcon,
  FolderSearchIcon,
  GlobeIcon,
  ListTodoIcon,
  LoaderIcon,
  PencilIcon,
  ReplaceIcon,
  SearchIcon,
  SlidersHorizontalIcon,
  SparklesIcon,
  SquareTerminalIcon,
  TelescopeIcon,
  Undo2Icon,
  WrenchIcon,
  XCircleIcon,
} from "lucide-react";
import { useAgUiInterrupts } from "@assistant-ui/react-ag-ui";
import {
  useAuiState,
  useToolCallElapsed,
  type PartState,
  type ToolCallMessagePartComponent,
  type ToolCallMessagePartStatus,
} from "@assistant-ui/react";

import {
  ApprovalGate,
  ElicitationGate,
  isApprovalInterrupt,
  isElicitationInterrupt,
} from "@/components/approval-gate";
import { ComposerAttachButton, ComposerFrame, ComposerTools } from "@/components/composer-chrome";
import {
  ReasoningContent,
  ReasoningRoot,
  ReasoningText,
} from "@/components/assistant-ui/elements/reasoning.aui";
import type {
  ThreadComponents,
  ThreadGroupPart,
} from "@/components/assistant-ui/elements/thread.aui";
import {
  ToolFallbackContent,
  ToolFallbackError,
  ToolFallbackRoot,
} from "@/components/assistant-ui/elements/tool-fallback.aui";
import { CollapsibleTrigger } from "@/components/ui/collapsible";
import { formatMillis } from "@/lib/format";
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
/// `harness.kernel.tools`), so `failed` here means the call itself did not finish, not
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

// ------------------------------------------------ which kind of call this is

/// The icon at the head of a row, by tool name.
///
/// It answers "what KIND of call is this" -- a file read, a shell command, a
/// search -- and nothing else. It used to answer "how is it doing" instead (the
/// state icon), which left the row's kind unsaid and put the state in the one
/// place a reader scans first. The five states have their own place at the end of
/// the row now (see `ToolCallTrigger`), and the two questions no longer share a
/// slot.
///
/// Keys are tool NAMES, exactly as `harness.kernel.tools` registers them: `CONTEXT.md`
/// says the names are not to be aliased, and this table is one more reason not
/// to -- a renamed tool loses its icon silently.
const TOOL_ICONS: Record<string, ElementType> = {
  read: FileTextIcon,
  write: FilePenLineIcon,
  edit: PencilIcon,
  replace: ReplaceIcon,
  insert: BetweenHorizontalStartIcon,
  undo_last_replace: Undo2Icon,
  anchor_grep: SearchIcon,
  glob: FolderSearchIcon,
  bash: SquareTerminalIcon,
  eval: BracesIcon,
  skill: SparklesIcon,
  "session-configure": SlidersHorizontalIcon,
  todo_write: ListTodoIcon,
  web_fetch: GlobeIcon,
  web_search: TelescopeIcon,
};

/// What a tool this page has never heard of gets: MCP tools registered at
/// runtime, and every tool added after today.
///
/// It is deliberately not one of the icons above. A fallback that looked like
/// `read` would claim to know what a call does, and the whole point of the
/// fallback is that the page does not know. `WrenchIcon` says "a tool" and stops.
const FALLBACK_TOOL_ICON = WrenchIcon;

// ------------------------------------------------- what this call is about

type Args = Record<string, unknown>;

/// The arguments, or null while they are still arriving.
///
/// `argsText` is the raw JSON the model streams, so for most of a call's life it
/// is a half-written object: `{"path": "/Users/zh` parses as nothing. Returning
/// null is the honest answer, and the row then shows the name alone rather than
/// half a payload it would have to take back a moment later.
function parseArgs(argsText: string): Args | null {
  if (argsText.trim() === "") return null;
  try {
    const value: unknown = JSON.parse(argsText);
    if (value === null || typeof value !== "object" || Array.isArray(value)) {
      return null;
    }
    return value as Args;
  } catch {
    return null;
  }
}

/// One argument as a non-empty string, or undefined -- empty strings are treated
/// as absent, because `read · ` with nothing after the dot is worse than `read`.
function stringArg(args: Args, key: string): string | undefined {
  const value = args[key];
  return typeof value === "string" && value !== "" ? value : undefined;
}

/// How many lines an array-valued argument holds; 0 when it is absent or is not
/// an array. Used by the two anchor tools, whose payload IS the line list.
function lineCount(args: Args, key: string): number {
  const value = args[key];
  return Array.isArray(value) ? value.length : 0;
}

/// The first line that is not blank, trimmed. Models open a thought -- and often
/// a command -- with a newline, and "the first line" would then be nothing.
function firstLine(text: string): string {
  for (const line of text.split("\n")) {
    const trimmed = line.trim();
    if (trimmed !== "") return trimmed;
  }
  return "";
}

/// What this call is about, in one line.
///
/// The tool names this table knows are the ones `docs/architecture/client.md`
/// lists -- that page is where this file's current state is written down, and the
/// two are meant to be read together. A tool that is not in it falls through to the
/// last branch rather than to nothing, which is the property that lets a new
/// tool (or an MCP one) appear in the transcript with a readable row and no
/// front-end change.
///
/// Nothing here parses the arguments beyond reading them: no path is resolved, no
/// anchor is looked up, no command is rewritten. The row reports what the model
/// asked for, in the model's own words, which is what the arguments panel would
/// show one click later.
function subjectOf(toolName: string, args: Args): string | null {
  switch (toolName) {
    case "read":
    case "write":
    case "edit":
    case "undo_last_replace":
      return stringArg(args, "path") ?? null;
    case "replace": {
      // No path argument to show: `replace` addresses lines by anchor and the
      // path is derived from them, so the anchors ARE the subject.
      const from = stringArg(args, "remove_from");
      if (from === undefined) return null;
      const to = stringArg(args, "remove_to");
      const range = to === undefined || to === from ? from : `${from}…${to}`;
      const lines = lineCount(args, "replacement_lines");
      return lines === 0 ? `${range} → 删除` : `${range} → ${lines} 行`;
    }
    case "insert": {
      const anchor = stringArg(args, "anchor");
      if (anchor === undefined) return null;
      const direction = stringArg(args, "direction") ?? "after";
      return `${direction} ${anchor} · ${lineCount(args, "lines")} 行`;
    }
    case "anchor_grep":
      return stringArg(args, "pattern") ?? null;
    case "bash":
      return firstLine(stringArg(args, "command") ?? "") || null;
    case "eval":
      return firstLine(stringArg(args, "code") ?? "") || null;
    case "skill":
      return stringArg(args, "name") ?? null;
    case "session-configure":
      return (
        ["provider", "model", "reasoning-effort"]
          .flatMap((key) => {
            const value = stringArg(args, key);
            return value === undefined ? [] : [`${key}=${value}`];
          })
          .join(" ") || null
      );
    case "glob": {
      const pattern = stringArg(args, "pattern");
      if (pattern === undefined) return null;
      const root = stringArg(args, "path");
      return root === undefined ? pattern : `${pattern} · ${root}`;
    }
    case "todo_write": {
      const todos = args["todos"];
      if (!Array.isArray(todos)) return null;
      if (todos.length === 0) return "清空";
      const done = todos.filter(
        (item) =>
          item !== null &&
          typeof item === "object" &&
          (item as Args)["status"] === "completed",
      ).length;
      return `${done}/${todos.length} 完成`;
    }
    case "web_fetch":
      // The whole URL, however long: the row ellipsises, and shortening it here
      // would cut the same end the CSS does -- the path -- while spending a second
      // rule on the same question.
      return stringArg(args, "url") ?? null;
    case "web_search":
      return stringArg(args, "query") ?? null;
    default:
      // The first string argument, in the order the model wrote them. Nested
      // objects are not searched: a row that dug through an MCP tool's payload
      // would be inventing knowledge, and the first string is a defensible guess.
      return (
        Object.values(args).find(
          (value): value is string => typeof value === "string" && value !== "",
        ) ?? null
      );
  }
}

/// The row: what was called, what it is about, and how it is doing.
///
/// `useToolCallElapsed` reads the part's timing and returns undefined when the
/// runtime recorded none, in which case the duration simply is not drawn -- an
/// absent number, not a zero.
///
/// The row is `w-full` (not the `w-fit` it was) because the subject has to be
/// ellipsised rather than wrapped: a one-line transcript is only a transcript if
/// the lines stay one line high. That also makes the whole width clickable, which
/// is why the chevron could go: a control this wide does not need an arrow to say
/// it can be opened, and a column of arrows read as a list of to-dos.
///
/// 13px, against the answer's 14px and the payloads' 12px -- the three numbers
/// and their reading rule are written down in `styles.css`, where the 14px for
/// the answer lives. The step rows are ours, so their size is stated here.
const ToolCallTrigger: FC<{
  toolName: string;
  state: CallState;
  subject: string | null;
}> = ({ toolName, state, subject }) => {
  const elapsedMs = useToolCallElapsed();
  const { label, icon: StatusIcon } = CALL_STATES[state];
  const isRunning = state === "running";
  const KindIcon = TOOL_ICONS[toolName] ?? FALLBACK_TOOL_ICON;

  return (
    <CollapsibleTrigger
      data-slot="tool-call-trigger"
      className="aui-tool-call-trigger group/trigger text-muted-foreground hover:text-foreground flex w-full origin-left items-center gap-2 py-1.5 text-[13px] transition-[color,scale] active:scale-[0.98]"
    >
      <KindIcon
        data-slot="tool-call-trigger-icon"
        className="aui-tool-call-trigger-icon size-4 shrink-0"
        aria-hidden="true"
      />
      <span
        data-slot="tool-call-trigger-label"
        className={cn(
          "aui-tool-call-trigger-label min-w-0 flex-1 truncate text-start leading-none",
          state === "cancelled" && "line-through",
          isRunning && "shimmer motion-reduce:animate-none",
        )}
      >
        <b className="aui-tool-call-trigger-name">{toolName}</b>
        {subject !== null && (
          <span
            data-slot="tool-call-trigger-subject"
            className="aui-tool-call-trigger-subject"
          >
            {" · "}
            {subject}
          </span>
        )}
      </span>
      {elapsedMs !== undefined && (
        <span
          data-slot="tool-call-trigger-duration"
          className="aui-tool-call-trigger-duration shrink-0 text-xs tabular-nums"
        >
          {formatMillis(elapsedMs)}
        </span>
      )}
      {/* The state, at the end of the row: a mark and -- for a reader who cannot
          see the mark -- its word, off screen. The word is what makes a spinner
          and a tick mean the same thing to everybody; it is not drawn inline any
          more because that slot is the subject's. */}
      <span
        data-slot="tool-call-trigger-status"
        title={label}
        className="aui-tool-call-trigger-status flex shrink-0 items-center"
      >
        <StatusIcon
          className={cn(
            "size-4",
            isRunning && "animate-spin [animation-duration:0.6s]",
            state === "failed" && "text-destructive",
          )}
          aria-hidden="true"
        />
        <span className="sr-only">{label}</span>
      </span>
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
  const parkedByInterrupt = useAgUiInterrupts().find(
    (candidate) =>
      (isApprovalInterrupt(candidate) || isElicitationInterrupt(candidate)) &&
      candidate.toolCallId === toolCallId,
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

  // What the row says this call is about. Null while the arguments are still
  // arriving, or when this tool's projection has nothing to say -- the row then
  // shows the name alone.
  const parsedArgs = parseArgs(argsText);
  const subject = parsedArgs === null ? null : subjectOf(toolName, parsedArgs);

  return (
    <>
      {/* Uncontrolled, and `defaultOpen` stays at its default of false: this is
          the whole of "collapsed by default" for a tool call. Nothing here opens
          the card on its own, and that is now true of a parked call too -- the
          card that says `Needs approval` is as folded as any other, and the
          decision it is waiting for is a block of its own underneath (below),
          drawn at full width so it cannot be missed. */}
      <ToolFallbackRoot>
        <ToolCallTrigger toolName={toolName} state={state} subject={subject} />
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
      {/* TWO KINDS OF PARK, TWO CARDS, ONE SLOT. Which one is decided by the
          interrupt's reason -- the same value the server put there -- so a card
          cannot be drawn for the wrong kind of stop. */}
      {parkedByInterrupt !== undefined &&
      isElicitationInterrupt(parkedByInterrupt) ? (
        <ElicitationGate toolCallId={toolCallId} />
      ) : null}
      {parkedByInterrupt !== undefined &&
      isApprovalInterrupt(parkedByInterrupt) ? (
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

/// Nothing. The slot is filled only so that no header is drawn.
///
/// ## Why a passthrough rather than no override at all
///
/// Leaving the slot empty is not the same as drawing nothing. `thread.aui.tsx`
/// renders its own group header when no override is given, which is exactly the
/// "N tool calls" line this file exists to remove. So the override stays and
/// returns its children.
///
/// ## Why the group node is left standing
///
/// The honest way to say "these calls are not a group" would be to stop grouping
/// them -- `groupBy` lives in the copied `thread.aui.tsx`, and dropping
/// `group-tool` from the `"tool-call"` path there would remove the node. That is
/// one array element, and it is still a worse trade: it edits a copied registry
/// file, and this repo keeps those diffable against upstream on the next pull.
/// The slot can express the same thing, so it does. The cost is a group node of
/// size one in the tree whose only renderer draws nothing -- a shape, not a
/// behaviour.
///
/// ## What went with it
///
/// The inner `gap-1` (4px) was the group content's, so it went too: adjacent
/// rows are now separated by their own `py-1.5` alone, 12px apart.
const FlatToolGroup: FC<PropsWithChildren<{ group: ThreadGroupPart }>> = ({
  children,
}) => <>{children}</>;

// ---------------------------------------------------------------- reasoning
//
// Reasoning is drawn as a ROW, and it is deliberately the same row a tool call
// is drawn as: an icon, a bold name, the subject this step is about, `py-1.5
// text-[13px]`, revealed by a click -- or, while the thought is still arriving,
// by the thought itself. Upstream's reasoning is a CARD -- `ReasoningRoot`'s
// default variant is `outline`, i.e. `rounded-lg border px-3 py-2` -- and this
// repo does not want a second visual species in one transcript: the things a turn
// did (thought, read, thought, ran) are a list of steps, and a step that is boxed
// while the step next to it is not reads as a different KIND of thing rather than
// a different step.
//
// So the trigger below is written here rather than taken from the copied kit,
// for the same reason `ToolCallTrigger` is: the row is this repo's presentation,
// and the only way "reasoning looks like a tool call" stays true is if one piece
// of code says what that row is. The disclosure SHELL still comes from the kit
// (`ReasoningRoot` / `ReasoningContent` / `ReasoningText`), because the scroll
// lock, the fades and the animation are not ours to re-derive.
//
// `variant="ghost"` is what removes the card. The kit's inner `max-h-64` scroll
// box is kept for a thought that is still arriving and removed for one that has
// stopped: a live window has to be bounded, and a finished thought is read whole
// like a tool's output -- the reasoning for both halves is on `ReasoningBlock`.
//
// `active` (the shimmer) is the live-run signal, exactly as it is on a tool
// card: the row says "still going" while the work is going, and stops when it
// stops.
//
// The row carries the FIRST LINE of the thought, for the same reason a tool row
// carries its subject: a step whose content is invisible until clicked makes the
// reader click to find out whether they needed to. The label is `思考` -- the
// reference this repo was asked to match uses that word, and it is the one place
// in the transcript where a Chinese label sits next to English ones. The tool
// names stay literal (`read`, `bash`): they are the model's vocabulary, and
// translating them would break the correspondence with the arguments panel.

/// How much of a thought the row shows before the CSS ellipsis takes over.
///
/// The clip is not only cosmetic. The preview is a STRING (see `previewOf`), and
/// `useAuiState` compares what a selector returns BY VALUE -- so once the first
/// line has reached this many characters, the row stops re-rendering on every
/// token of a thought that is still arriving. Handing the row the whole text and
/// letting CSS do all the cutting would keep that subscription alive for the
/// length of the stream.
const PREVIEW_LIMIT = 120;

function clip(text: string): string {
  return text.length > PREVIEW_LIMIT
    ? `${text.slice(0, PREVIEW_LIMIT).trimEnd()}…`
    : text;
}

/// The first line of a reasoning group's thinking, or "".
///
/// The group knows which parts it covers (`indices`) and the row knows nothing
/// else, so the parts are read from the message state here. A group can hold
/// several parts; the first one WITH a non-blank line wins, because joining them
/// would put a seam in the middle of a sentence.
///
/// This returns a string rather than the parts for the reason `PREVIEW_LIMIT`
/// gives: the caller is `useAuiState`, and a string it can compare by value is
/// what keeps the row from re-rendering per token.
function previewOf(
  parts: readonly PartState[],
  indices: readonly number[],
): string {
  for (const index of indices) {
    const part = parts[index];
    if (part?.type !== "reasoning") continue;
    const line = firstLine(part.text);
    if (line !== "") return clip(line);
  }
  return "";
}

const ReasoningTrigger: FC<{ active: boolean; preview: string }> = ({
  active,
  preview,
}) => (
  <CollapsibleTrigger
    data-slot="reasoning-trigger"
    className="aui-reasoning-trigger group/trigger text-muted-foreground hover:text-foreground flex w-full origin-left items-center gap-2 py-1.5 text-[13px] transition-[color,scale] active:scale-[0.98]"
  >
    <BrainIcon
      data-slot="reasoning-trigger-icon"
      className="aui-reasoning-trigger-icon size-4 shrink-0"
      aria-hidden="true"
    />
    <span
      data-slot="reasoning-trigger-label"
      className={cn(
        "aui-reasoning-trigger-label min-w-0 flex-1 truncate text-start leading-none",
        active && "shimmer motion-reduce:animate-none",
      )}
    >
      <b className="aui-reasoning-trigger-name">思考</b>
      {preview !== "" && (
        <span
          data-slot="reasoning-trigger-subject"
          className="aui-reasoning-trigger-subject"
        >
          {" · "}
          {preview}
        </span>
      )}
    </span>
  </CollapsibleTrigger>
);

/// A run of adjacent reasoning parts, behind one row that is folded unless the
/// thought is still arriving.
///
/// `streaming` is what does that, and it is read off the GROUP rather than off the
/// message: `group.status` runs while any part the group covers is running, so the
/// panel follows the THOUGHT and not the run -- a turn that thinks, reads and then
/// thinks again opens for the first thought, folds, and opens again for the next
/// one. When the last token lands the panel folds itself and the row is left
/// saying the first line, which is what a thought that never streamed says too. A
/// restored conversation is never streaming, so history arrives folded.
///
/// The kit's live window is kept for the streaming case and only for it. Without a
/// height cap (`max-h-64`, the kit's own) the panel would grow for as long as the
/// model thinks, and the newest tokens -- the ones the window exists to follow --
/// would be the furthest down the page. A thought that has stopped gets
/// `max-h-none` back, so a reader who opens one reads it whole, the same rule a
/// tool's result gets.
///
/// Nothing is remembered across that transition: the open state is the kit's
/// (`userOpen ?? streaming`), so a panel opened by hand stays open and one closed
/// by hand stays closed.
const ReasoningBlock: FC<PropsWithChildren<{ group: ThreadGroupPart }>> = ({
  group,
  children,
}) => {
  const running = group.status.type === "running";
  const preview = useAuiState((s) => previewOf(s.message.parts, group.indices));

  return (
    <ReasoningRoot variant="ghost" className="mb-0" streaming={running}>
      <ReasoningTrigger active={running} preview={preview} />
      <ReasoningContent aria-busy={running}>
        <ReasoningText className={running ? "pt-1" : "max-h-none pt-1"}>
          {children}
        </ReasoningText>
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
/// result instead of showing nothing, the group draws nothing so a run of calls
/// is a run of rows, and reasoning is drawn as the same bare row a tool call
/// gets -- never as a card, never streaming its disclosure open. Everything
/// else -- the message list, the text parts, the welcome screen, the composer --
/// stays upstream's.
export const THREAD_COMPONENTS: ThreadComponents = {
  ToolFallback: ToolCallCard,
  ToolGroup: FlatToolGroup,
  ReasoningGroup: ReasoningBlock,
  // The composer's chrome: the directory and branch strip above it, the model and
  // thinking pickers inside it, and the attach button beside them. See
  // composer-chrome.tsx -- they are slots rather than an edited composer so the
  // copied element keeps its shape.
  ComposerFrame,
  ComposerTools,
  ComposerAddAttachment: ComposerAttachButton,
};
