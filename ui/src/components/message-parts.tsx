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
// NOTHING OPENS ITSELF: the reader's click is the only thing that does. A
// THOUGHT IS THE ONE PART THAT SHOWS WHILE IT IS STILL BEING WRITTEN, and it
// shows on its own ROW -- the row says the newest window of the thought and
// gives back the first line when the thought ends. It used to open the
// disclosure instead (upstream's `streaming`, whose rule is
// `userOpen ?? streaming`), and that is what this repo undid: a panel that
// unfolds itself ONCE PER THOUGHT -- a turn that thinks, reads and thinks again
// opens it twice -- moves the transcript under a reader who is looking at
// something else, in a column whose whole point is that its steps are listed
// rather than unfolded. The live WINDOW stays upstream's (`max-h-64`, the fades,
// the follow-the-newest-token scroll) and a reader who asks for it mid-run still
// gets it; what is gone is it opening by itself. See `ReasoningBlock` below and
// `lib/reasoning-preview.ts` for the row's words.
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
// override keeps this file ours, and the change lands here rather than in a copied
// file -- those copies are edited in place now, each edit marked `LOCAL:`, but this
// one did not need to be. The upstream pieces used are the copied atoms and
// disclosure shells -- `ToolFallbackRoot` / `ToolFallbackContent` /
// `ToolFallbackError` (animation, scroll lock, error block), and -- for
// reasoning -- the shell only: `ReasoningRoot` / `ReasoningContent` /
// `ReasoningText`. The group's own pieces are no longer imported at all: the
// slot draws nothing.
import {
  type ElementType,
  type FC,
  type PropsWithChildren,
  useLayoutEffect,
  useRef,
  useState,
} from "react";
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
  type ToolCallMessagePartComponent,
  type ToolCallMessagePartStatus,
} from "@assistant-ui/react";
import type { TFunction } from "i18next";
import { useTranslation } from "react-i18next";

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
import { firstLine, previewOf, thoughtAt } from "@/lib/reasoning-preview";
import { cn } from "@/lib/utils";

/// The translator this face's words go through. PINNED TO THE NAMESPACE, like
/// `lib/format.ts` pins its own: a bare `TFunction` would mean "whatever the
/// default namespace is" and would accept a shell translator by mistake, while a
/// `string` key would lose the key check in the helpers below entirely.
type Translate = TFunction<"thread">;

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
/// THE WORD IS THE CATALOG'S, AND IT ARRIVES AS A FUNCTION rather than a string
/// because the table is module-level and the language is not: each entry closes
/// over its own key, written literally (`t("status.running")`), which is what the
/// key discipline requires -- a `t(\`status.${state}\`)` at the call site would
/// make the type gate blind to a renamed key.
///
/// Upstream draws these states as icons only. This repo spells them out as well,
/// because a checkmark and a cross are easy to miss and a card whose state is
/// implied is a card whose state gets misread.
///
/// The icons are typed `ElementType` rather than `typeof LoaderIcon`: the table
/// holds five different icons, and naming one of them as the type of all of them
/// says the wrong thing. It is also what upstream's own `statusIconMap` is typed
/// as, and `lucide-react@1.46` exports no `LucideIcon` to reach for instead.
const CALL_STATES: Record<
  CallState,
  { label: (t: Translate) => string; icon: ElementType }
> = {
  running: { label: (t) => t("status.running"), icon: LoaderIcon },
  done: { label: (t) => t("status.done"), icon: CheckIcon },
  failed: { label: (t) => t("status.failed"), icon: XCircleIcon },
  cancelled: { label: (t) => t("status.cancelled"), icon: XCircleIcon },
  "needs-approval": { label: (t) => t("status.needsApproval"), icon: AlertCircleIcon },
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
///
/// THE ONE THING THIS FUNCTION SAYS ITSELF IS A HANDFUL OF WORDS, and they are the
/// catalog's (`delete` / `N lines` / `clear` / `done`, and 删除 / N 行 / 清空 / 完成 in
/// Chinese). Everything around them is a VALUE -- a path, an anchor, a command --
/// and stays verbatim, tool names included. The symbols between them (`…`, ` → `,
/// ` · `, the slash of `3/5`) are punctuation rather than words, so they stay
/// literal too: only the numbers are interpolated.
function subjectOf(toolName: string, args: Args, t: Translate): string | null {
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
      return lines === 0
        ? `${range} → ${t("subject.delete")}`
        : `${range} → ${t("subject.lines", { count: lines })}`;
    }
    case "insert": {
      const anchor = stringArg(args, "anchor");
      if (anchor === undefined) return null;
      const direction = stringArg(args, "direction") ?? "after";
      return `${direction} ${anchor} · ${t("subject.lines", { count: lineCount(args, "lines") })}`;
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
      if (todos.length === 0) return t("subject.clear");
      const done = todos.filter(
        (item) =>
          item !== null &&
          typeof item === "object" &&
          (item as Args)["status"] === "completed",
      ).length;
      return `${done}/${todos.length} ${t("subject.todoDone")}`;
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
  const { t } = useTranslation("thread");
  // The elapsed time's words come from the `format` face, because `formatMillis` is
  // the one formatter the tool card and the trajectory share (ticket 02 merged them).
  const { t: tFormat } = useTranslation("format");
  const { label: labelFor, icon: StatusIcon } = CALL_STATES[state];
  const label = labelFor(t);
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
          {formatMillis(elapsedMs, tFormat)}
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
  const { t } = useTranslation("thread");
  if (argsText === "") return null;

  return (
    <div data-slot="tool-call-args" className="aui-tool-call-args flex flex-col">
      <p className="aui-tool-call-args-header text-muted-foreground text-xs font-medium">
        {t("args.header")}
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
function resultText(result: unknown, t: Translate): string | null {
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
    return t("result.unserializable");
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
function statusErrorText(
  status: ToolCallMessagePartStatus | undefined,
  t: Translate,
): string | null {
  if (status?.type !== "incomplete") return null;

  const text = resultText(status.error, t);
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
  const { t } = useTranslation("thread");
  const text = resultText(result, t);

  if (text === null || text.trim() === "") {
    if (failureExplained) return null;

    return (
      <p
        data-slot="tool-call-no-result"
        className="aui-tool-call-no-result text-muted-foreground text-xs"
      >
        {t("result.noResult")}
      </p>
    );
  }

  return (
    <div
      data-slot="tool-call-result"
      className="aui-tool-call-result flex flex-col"
    >
      <p className="aui-tool-call-result-header text-muted-foreground text-xs font-medium">
        {failed && !failureExplained ? t("result.error") : t("result.header")}
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
  const { t } = useTranslation("thread");
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
  const failureExplained = statusErrorText(status, t) !== null;

  // What the row says this call is about. Null while the arguments are still
  // arriving, or when this tool's projection has nothing to say -- the row then
  // shows the name alone.
  const parsedArgs = parseArgs(argsText);
  const subject = parsedArgs === null ? null : subjectOf(toolName, parsedArgs, t);

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
// text-[13px]`, revealed by a click and by nothing else -- a thought that is
// still arriving included. Upstream's reasoning is a CARD -- `ReasoningRoot`'s
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
// The row carries the SUBJECT of the thought -- its first line once it has
// stopped, its newest window while it is still arriving -- for the same reason a
// tool row carries its subject: a step whose content is invisible until clicked
// makes the reader click to find out whether they needed to. The label is this
// row's own
// word and it lives in the `thread` catalog (`思考` / `Thinking`), so each language
// has its own -- it was once the one Chinese label in an English transcript, and
// moving this face's words into the catalog is what stopped that being true. The
// tool names stay literal (`read`, `bash`): they are the model's vocabulary, and
// translating them would break the correspondence with the arguments panel.

// The row's WORDS are `lib/reasoning-preview.ts`: the first line at rest, the
// newest window of a live thought, and the 120-character bound both halves are
// held to. They live in that module rather than here because a suite can call
// them; what the row LOOKS like is the browser walkthrough's half
// (`.scratch/thinking-row-tail/`).
///
/// The row's subject is drawn in TWO shapes, and which one is the same question
/// as whether the thought is still arriving. A thought that has stopped is a plain
/// string -- the row's own `truncate` cuts it and the `…` is the module's. A live
/// one is `ReasoningTail` below: the same one line, inside a window that keeps its
/// END in view, so characters leave at the left edge while the new ones arrive at
/// the right one.

const ReasoningTrigger: FC<{ active: boolean; preview: string }> = ({
  active,
  preview,
}) => {
  const { t } = useTranslation("thread");

  return (
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
        // THE SHIMMER IS NOT ON THIS SPAN ANY MORE, and that is a bug fix with
        // pixels behind it. `shimmer` (tw-shimmer) paints text THROUGH A MASK --
        // `-webkit-mask-clip: text`, the glyphs are the mask -- and that mask is
        // taken from the text's LAYOUT. The live line is drawn somewhere else (the
        // drag is a transform), so everything the window moves is masked away.
        // Measured on one row: the shimmer on this span cuts its ink from 1097
        // pixels to 583 at rest, and while a thought is arriving the row was
        // BLANK -- which is what a reader saw (空白 while it thinks, 思考 · 首行 the
        // instant it stops, i.e. once the tail is gone and the mask lines up
        // again). On the NAME alone it keeps the whole of its signal and costs the
        // line nothing (1032 of those 1097 pixels).
        className="aui-reasoning-trigger-label min-w-0 flex-1 truncate text-start leading-none"
      >
        <b
          className={cn(
            "aui-reasoning-trigger-name",
            active && "shimmer motion-reduce:animate-none",
          )}
        >
          {t("reasoning.label")}
        </b>
        {preview !== "" && (
          <span
            data-slot="reasoning-trigger-subject"
            className="aui-reasoning-trigger-subject"
          >
            {" · "}
            {active ? <ReasoningTail text={preview} /> : preview}
          </span>
        )}
      </span>
    </CollapsibleTrigger>
  );
};

/// The live window: the newest part of a thought, kept in view and sliding left
/// as it arrives -- characters leave at the LEFT edge while the new ones arrive at
/// the right one.
///
/// WHY THE MOTION IS A TRANSFORM. The window shows a line's worth of a thought and
/// the thought is longer, so something has to move; the question is WHAT moves it.
/// Moving the text by LAYOUT -- which is all a left-edge clip does -- happens
/// inside one frame: the browser draws position A and then position B, and a reader
/// sees a snap per token (the first cut of this shipped that way, and a real
/// session is what showed it). So the line is laid out left-aligned and the WHOLE
/// of it is dragged left by a transform until its END sits at the window's right
/// edge, and `styles.css` gives that transform a transition: the same motion,
/// interpolated a frame at a time. The measure is taken after layout and before
/// paint (`useLayoutEffect`), so the untranslated line is never drawn.
///
/// What is dragged is the ARRIVED text (`lib/reasoning-preview.ts`), not a window
/// cut to its last N characters: dropping what has scrolled off the left edge would
/// hand the motion back to layout, one dropped character at a time -- the snap
///
/// THE DRAG HAS A SPEED RATHER THAN A DURATION. Interpolating over a fixed time
/// would leave a lag proportional to how fast the model is writing -- the drag is
/// always that many milliseconds behind the arrival, so a fast stream shows text
/// that is a second old, and the newest characters (the ones the window exists
/// for) would be the ones cut off at the right edge. So each step is given the
/// time it takes to travel at this many PIXELS PER MILLISECOND, capped at
/// `TAIL_SETTLE_MAX`: a character's worth of text moves in a few milliseconds, and
/// a whole sentence that lands at once still slides rather than teleports.
///
/// 4px/ms is about 300 characters a second at this size, which is as fast as a
/// vendor streams on a good day -- so a real session sees the drag keep up (the
/// newest characters a moment from being in view), and only a burst that outruns
/// it is allowed to fall behind. Measured at a scripted, bandwidth-throttled
/// ~250 characters a second: the whole of what is still off the right edge is
/// under a quarter of the window (`.scratch/thinking-row-tail/`).
const TAIL_SPEED = 4;
const TAIL_SETTLE_MAX = 400;

const ReasoningTail: FC<{ text: string }> = ({ text }) => {
  const windowRef = useRef<HTMLSpanElement>(null);
  const trackRef = useRef<HTMLSpanElement>(null);
  /// Where the last step left the line, in the same units as the transform.
  const draggedRef = useRef(0);

  useLayoutEffect(() => {
    const window = windowRef.current;
    const track = trackRef.current;
    if (window === null || track === null) return;
    // How much of the line is off the window's right side: pull the line left by
    // exactly that much, so its END is what the window shows. A line that FITS is
    // not moved at all, which is what keeps a short thought sitting right after
    // `思考 · ` instead of jumping to the window's right edge.
    const hidden =
      track.getBoundingClientRect().width - window.getBoundingClientRect().width;
    const target = hidden > 0 ? -hidden : 0;
    const travelled = Math.abs(target - draggedRef.current);
    track.style.transitionDuration = `${Math.min(TAIL_SETTLE_MAX, travelled / TAIL_SPEED)}ms`;
    track.style.transform = `translateX(${target}px)`;
    draggedRef.current = target;
  });

  return (
    <span
      ref={windowRef}
      data-slot="reasoning-trigger-tail"
      className="aui-reasoning-trigger-tail"
    >
      <span ref={trackRef}>{text}</span>
    </span>
  );
};

/// A THOUGHT, behind ONE ROW -- folded unless the reader opens it, a thought that
/// is still arriving included.
///
/// ONE ROW PER THOUGHT, AND A TOOL CALL IS WHAT ENDS ONE. A turn that thinks, reads
/// and then thinks again gives three rows in that order: the first thought, the
/// call, the second thought -- the row follows the THOUGHT and not the run. TEXT
/// DOES NOT END ONE, and that is the whole of `thoughtAt`: a vendor interleaves its
/// thinking with the answer it is writing (measured on a real session: reasoning,
/// the answer's first token, then ` in Chinese.` -- the tail of the same thought),
/// the runtime makes each of those blocks a MESSAGE of its own, and drawing them as
/// they come leaves a second 思考 row AFTER the answer carrying the fragment -- what
/// a reader reported. So a thought is gathered across the messages of its turn, and
/// the row that began it is the one that draws it: a continuation returns nothing,
/// and the row above it stays live for reasoning that arrives later (see `thoughtAt`
/// in `lib/reasoning-preview.ts` for both walks).
///
/// A restored conversation is never running, so history arrives as first lines.
///
/// THE OPEN STATE IS HELD HERE and it starts false, which is the whole of "a live
/// thought does not unfold itself". Upstream keeps that state internally as
/// `userOpen ?? (streaming || defaultOpen)` -- a rule that opens the panel for a
/// condition nobody clicked on -- so passing `streaming` alone is what used to
/// unfold every live thought, twice in a turn that thinks around a tool call.
/// Holding it is what removes that and keeps everything else upstream's:
/// `streaming` still decides whether an OPEN panel follows the newest token and
/// grows a bottom fade, so a reader who clicks during a run gets exactly the live
/// window they got before.
///
/// The kit's live window is therefore kept for the streaming case and only for
/// it. Without a height cap (`max-h-64`, the kit's own) the open panel would grow
/// for as long as the model thinks, and the newest tokens -- the ones the window
/// exists to follow -- would be the furthest down the page. A thought that has
/// stopped gets `max-h-none` back, so a reader who opens one reads it whole, the
/// same rule a tool's result gets.
const ReasoningBlock: FC<PropsWithChildren<{ group: ThreadGroupPart }>> = ({
  children,
}) => {
  // WHICH THOUGHT THIS ROW IS ABOUT is a question about the whole TURN, not about
  // this group: `s.thread.messages` is the conversation and `s.message.index` is
  // where this message sits in it, which is what `thoughtAt` walks (both rules and
  // their reasons are in `lib/reasoning-preview.ts`). Two selectors rather than one
  // object, because the comparison is by reference -- an object literal here would
  // re-render on every store update (see `useAuiState`'s own note on that).
  const messages = useAuiState((s) => s.thread.messages);
  const index = useAuiState((s) => s.message.index);
  const thought = thoughtAt(messages, index);
  // The tail while it runs, the first line once it stops.
  const preview = previewOf(thought.parts, thought.running);
  const [open, setOpen] = useState(false);

  // NOT DRAWN: this run is the model going back to a thought it already started --
  // see the file comment. The row that began it says the same words a moment later
  // anyway (it is handed the newest part), so nothing is lost by saying this one
  // twice.
  if (!thought.drawn) return null;

  return (
    <ReasoningRoot
      variant="ghost"
      className="mb-0"
      streaming={thought.running}
      open={open}
      onOpenChange={setOpen}
    >
      <ReasoningTrigger active={thought.running} preview={preview} />
      <ReasoningContent aria-busy={thought.running}>
        <ReasoningText className={thought.running ? "pt-1" : "max-h-none pt-1"}>
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
