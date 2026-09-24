// The trajectory view: what the model had in front of it, turn by turn.
//
// ------------------------------------------------------------ why this is not a nicer chat
//
// The conversation view shows what the CLIENT holds. This one shows what the SERVER
// SENT, and the difference is the whole feature: the system message's bytes, the
// instruction files and the skills catalog spliced in beside it, the skill body the
// model asked for mid-run, and every tool call with its arguments and its result. None
// of those is in the client's conversation -- it never had them -- and none of them is
// in an AG-UI frame. They exist on the jsonl record, and only there.
//
// So this view READS AN ENDPOINT INSTEAD OF THE RUNTIME. `lib/trajectory.ts` folds the
// record server-side; nothing here counts, estimates or reconstructs anything. When a
// fact is missing from the record the pane says so; it never fills the gap in from what
// the session happens to have today, which would be the one way this view could lie.
//
// ------------------------------------------- one pane, opened by the row you clicked
//
// THE LIST IS THE WHOLE PAGE UNTIL SOMETHING IS CLICKED. There is no fixed second column
// and no pair of tabs: a row opens ITS OWN detail on the right, and clicking it again
// (or the ×) closes the pane and gives the width back. That is the owner's rule, and it
// is also the honest shape of the data -- there is no "the turn's system prompt" to draw
// beside a turn, there is one item among twenty, and it is the one that was asked about.
//
// The two things a fixed pane would have shown are still reachable, because both of them
// ARE items: the system message is the `system` row (first turn, and again whenever its
// bytes change), and the tool table's SIGNATURE a call sent lives on that call's `assistant`
// row -- the row is the call's answer, the signature is the call's request, and the `:call`
// pointer the record gives us is what connects the two.
//
// Order in the list is the RECORD's order, not the reference screenshot's.
// `harness.edge.ag-ui/inbound` splices the opening blocks AFTER the system message and
// before the client's messages, and appends the run's context as a trailing user message;
// that order is what the model saw, so that order is what is drawn.
//
// --------------------------------------------- when it asks, and what it costs to ask
//
// ON MOUNT, ON A SESSION CHANGE, AND WHEN A RUN SETTLES -- the trigger `composer-stats`
// uses, and for the same reason: a turn's record is only complete once its run's tail
// is written. There is no polling: a long call streams for minutes and this view stands
// still for the length of it, which is honest, because the part of the record it would
// show does not exist yet.
import { type FC, useEffect, useMemo, useRef, useState } from "react";
import { useAuiState } from "@assistant-ui/react";
import type { TFunction } from "i18next";
import { WrenchIcon, XIcon } from "lucide-react";
import { useTranslation } from "react-i18next";

import { formatMillis, formatTime, formatTokens } from "@/lib/format";
import { asLanguage } from "@/lib/language";
import { type TrajectoryItem, type TrajectoryPayload, type TrajectoryTurn, trajectoryFor } from "@/lib/trajectory";
import { cn } from "@/lib/utils";
import { KIND_HUE } from "@/components/trajectory-colors";
import { type Mode, TrajectoryTimeline } from "@/components/trajectory-timeline";

/// The translator this face's words go through. PINNED TO THE NAMESPACE, like
/// `lib/format.ts` pins its own: a bare `TFunction` would mean "whatever the default
/// namespace is" and would accept a shell translator by mistake, while a `string` key
/// would lose the key check in the helpers below (`headline`).
type Translate = TFunction<"trajectory">;

/// The one-line preview: the first line that has anything on it, cut to a glance.
const preview = (text: string): string => {
  const line = text.split("\n").find((l) => l.trim() !== "") ?? "";
  return line.length > 110 ? `${line.slice(0, 110)}…` : line;
};

/// WHAT A ROW SHOWS AS ITS ONE LINE.
///
/// AN ASSISTANT ROW THAT SHOWS NOTHING IS THE COMMON CASE, NOT AN EDGE ONE: a round that
/// only asks for tools carries `content: ""` and puts its words in `reasoning`, so a row
/// built from the text alone is a blank chip under the word "assistant". The reasoning is
/// what the model was saying at that moment, so it is what the row shows -- PREFIXED,
/// because a reader has to know which of the two they are looking at.
const headline = (item: TrajectoryItem, t: Translate): string => {
  switch (item.kind) {
    case "tool":
      // A SPACE between the two: it is a name and its arguments, and `bash{"command":…}`
      // reads as one long identifier.
      return `${item.name ?? item.toolCallId} ${item.argsText ?? ""}`;
    case "assistant":
      if (item.text.trim() !== "") return preview(item.text);
      if (item.reasoning !== undefined && item.reasoning.trim() !== "")
        return `${t("blocks.reasoning")}: ${preview(item.reasoning)}`;
      return t("row.noText");
    default:
      return preview(item.text);
  }
};

/// The word a kind wears, in the reader's language. A TABLE OF LITERAL KEYS rather than
/// `t(`kind.${kind}`)`: a built key is one the type gate cannot check and the last ticket
/// cannot find a use for (see `lib/catalogs.ts`).
///
/// THE KEY IS THE RECORD'S, THE WORD IS OURS: `item.kind` is the record's own discriminator
/// -- it is also the key into the shared colour table below -- and it stays exactly that.
/// What a reader reads is this table's entry, so a page in Chinese says 工具 where a page in
/// English says `tool`; the record says `tool` either way.
const KIND_LABEL: Record<TrajectoryItem["kind"], (t: Translate) => string> = {
  system: (t) => t("kind.system"),
  context: (t) => t("kind.context"),
  user: (t) => t("kind.user"),
  assistant: (t) => t("kind.assistant"),
  tool: (t) => t("kind.tool"),
};

/// The chip, wherever it appears: the row and the pane say the same word the same way,
/// so a reader who clicked a tool row is looking at the same word in the pane. THE COLOUR
/// COMES FROM THE SHARED TABLE (trajectory-colors), which is also what colours the strip's
/// lanes -- a lane and its chips are the same kind of thing, so they are the same colour.
const Chip: FC<{ kind: TrajectoryItem["kind"] }> = ({ kind }) => {
  const { t } = useTranslation("trajectory");
  return (
    <span
      data-slot="trajectory-item-chip"
      className={cn(
        "shrink-0 self-center rounded px-1.5 py-0.5 text-[0.68rem] leading-4",
        KIND_HUE[kind].chip,
      )}
    >
      {KIND_LABEL[kind](t)}
    </span>
  );
};

/// One row of the list. It CARRIES NO EXPANSION OF ITS OWN: opening a row means the
/// detail pane, because two ways to see the same text (a folded body here, a pane there)
/// is two places to keep in step.
const ItemRow: FC<{
  item: TrajectoryItem;
  index: number;
  selected: boolean;
  query: string;
  onSelect: () => void;
}> = ({ item, index, selected, query, onSelect }) => {
  const { t } = useTranslation("trajectory");
  const hit = query === "" || JSON.stringify(item).toLowerCase().includes(query);

  return (
    <li data-slot="trajectory-item" data-kind={item.kind} data-index={index} hidden={!hit}>
      <button
        type="button"
        onClick={onSelect}
        aria-expanded={selected}
        className={cn(
          "flex w-full items-baseline gap-2 border-b border-border/50 px-3 py-1.5 text-left",
          selected ? "bg-muted" : "hover:bg-muted/50",
        )}
      >
        <Chip kind={item.kind} />
        {/* ONE LINE, TWO HALVES: `name {args}` then `→ result`, each ellipsizing into
            whatever room is left. The arguments are usually the longer half and the
            result is usually the reason someone is scanning, so neither may be the one
            that gets cut first -- a single truncating span would always sacrifice the
            second half, and the second half is what happened.
            NO TIME AND NO TOKEN COUNT OUT HERE EITHER: those are the pane's, and numbers
            on every line turn a scan into a read. */}
        {item.kind === "tool" ? (
          <>
            <span
              data-slot="trajectory-item-head"
              className="min-w-0 flex-1 truncate font-mono text-xs text-foreground"
            >
              {headline(item, t)}
            </span>
            <span className="shrink-0 font-mono text-xs text-muted-foreground" aria-hidden="true">
              →
            </span>
            <span
              data-slot="trajectory-item-result"
              className="min-w-0 flex-1 truncate font-mono text-xs text-muted-foreground"
            >
              {preview(item.result)}
            </span>
          </>
        ) : (
          <span
            data-slot="trajectory-item-head"
            className="min-w-0 flex-1 truncate font-mono text-xs text-foreground"
          >
            {headline(item, t)}
          </span>
        )}
      </button>
    </li>
  );
};

/// A labelled block of text in the detail pane. `mono` is for the things that ARE the
/// bytes (a prompt, an argument list, a result); prose gets the normal face.
const Block: FC<{ label?: string; text: string; mono?: boolean }> = ({ label, text, mono }) => (
  <div className="mb-3">
    {label !== undefined && (
      <p className="mb-1 text-[0.7rem] uppercase tracking-wide text-muted-foreground">{label}</p>
    )}
    <pre
      className={cn(
        "overflow-x-auto whitespace-pre-wrap break-words rounded bg-muted/50 p-2 text-xs",
        mono === true && "font-mono",
      )}
    >
      {text}
    </pre>
  </div>
);

/// A row of small facts: `label: value`. A value the record does not carry is simply not
/// drawn -- there is no placeholder and no zero standing in for a missing number.
const Facts: FC<{ pairs: readonly (readonly [string, string | null])[] }> = ({ pairs }) => {
  const shown = pairs.filter(([, value]) => value !== null);
  if (shown.length === 0) return null;
  return (
    <div data-slot="trajectory-facts" className="mb-3 flex flex-wrap gap-x-4 gap-y-1 text-xs">
      {shown.map(([label, value]) => (
        <span key={label} className="text-muted-foreground">
          {label}: <span className="tabular-nums text-foreground">{value}</span>
        </span>
      ))}
    </div>
  );
};

/// EVERY DISTINCT TOOL TABLE A TURN SENT, BY ITS SIGNATURE, with the calls that sent it.
///
/// A turn almost always sends one table -- it is the session's toolset, resolved once per
/// call -- but a turn that changed tools mid-flight (the editing mode was switched, an MCP
/// server came up) sends more, and then the DIFFERENCE is the fact worth showing. THE
/// TABLE ITSELF IS NOT ON THE RECORD ANY MORE (ticket 04): what a call leaves is the NAME
/// set as a hash and the count, and grouping by the hash keeps the common case one list --
/// two tables that differ only in a description group together, which is the point of the
/// name set.
const tablesOf = (turn: TrajectoryTurn): { calls: readonly number[]; count: number; key: string }[] => {
  const groups: { calls: number[]; count: number; key: string }[] = [];
  for (const call of turn.calls ?? []) {
    if (call.toolsNamesHash === undefined) continue;
    const key = call.toolsNamesHash;
    const hit = groups.find((g) => g.key === key);
    if (hit === undefined) groups.push({ calls: [call.index], count: call.toolsCount ?? 0, key });
    else hit.calls.push(call.index);
  }
  return groups;
};

/// One tool, folded. A NATIVE `<details>`: collapsed it is `name · first line of the
/// description`, expanded it is the whole description and the definition the model was
/// handed -- name, description and schema all present, because that entry IS what went on
/// the wire.
const ToolRow: FC<{ tool: unknown }> = ({ tool }) => {
  const { t } = useTranslation("trajectory");
  const fn =
    typeof tool === "object" && tool !== null && "function" in tool
      ? (tool as { function?: unknown }).function
      : tool;
  const name =
    typeof fn === "object" && fn !== null && "name" in fn && typeof fn.name === "string"
      ? fn.name
      : t("tools.unnamed");
  const description =
    typeof fn === "object" && fn !== null && "description" in fn && typeof fn.description === "string"
      ? fn.description
      : "";
  const firstLine = description.split("\n").find((l) => l.trim() !== "") ?? "";
  return (
    <li data-slot="trajectory-tool">
      <details className="rounded hover:bg-muted/40">
        <summary className="flex cursor-pointer items-baseline gap-2 px-2 py-1">
          <WrenchIcon className="size-3.5 shrink-0 self-center text-muted-foreground" aria-hidden="true" />
          <span className="shrink-0 font-mono text-xs text-foreground">{name}</span>
          <span className="truncate text-xs text-muted-foreground">{firstLine}</span>
        </summary>
        <div className="px-2 pb-2 pl-7">
          {description !== "" && (
            <p className="mb-2 whitespace-pre-wrap text-xs text-foreground">{description}</p>
          )}
          <Block text={JSON.stringify(tool, null, 2)} mono />
        </div>
      </details>
    </li>
  );
};

/// The tool list of one turn. THE TABLE ITSELF WHEN THE ITEM CARRIES IT: the system row's
/// envelope keeps it (`:tools`), so a trajectory item is SELF-CONTAINED and this pane
/// never has to go and pull a second record to find out what tools the run served. Falls
/// back to the per-call envelope groups for a record written before the table moved to
/// the envelope.
const ToolList: FC<{ turn: TrajectoryTurn; tools?: readonly unknown[] }> = ({ turn, tools }) => {
  const { t } = useTranslation("trajectory");
  if (tools !== undefined && tools.length > 0) {
    return (
      <div data-slot="trajectory-tool-tables">
        <p className="mb-1 text-[0.7rem] uppercase tracking-wide text-muted-foreground">
          {t("call.tools", { n: tools.length })}
        </p>
        <ul className="rounded border border-border/60">
          {tools.map((tool, i) => (
            <ToolRow key={i} tool={tool} />
          ))}
        </ul>
      </div>
    );
  }
  const tables = tablesOf(turn);
  if (turn.calls === undefined) {
    return (
      <p className="text-xs text-muted-foreground">
        {t("tools.predates")}
      </p>
    );
  }
  if (tables.length === 0) {
    return <p className="text-xs text-muted-foreground">{t("tools.noTable")}</p>;
  }
  return (
    <div data-slot="trajectory-tool-tables">
      {tables.map(({ calls, count }) => (
        <div key={calls.join("-")} className="mb-2">
          <p className="mb-1 text-[0.7rem] uppercase tracking-wide text-muted-foreground">
            {t("call.sent", { count: calls.length, names: calls.join(", ") })} ·{" "}
            {t("call.tools", { n: count })}
          </p>
          <p className="text-xs text-muted-foreground">{t("tools.notKept")}</p>
        </div>
      ))}
    </div>
  );
};

/// The clicked item, in full. ONE ITEM, ONE PANE: what it is, the whole of what it
/// carries, and -- for the kinds that have them -- the facts the record states about it.
/// WHICH OF A SYSTEM ITEM'S TWO HALVES IS SHOWING. Only a system item has two: the
/// prompt the model was given, and the tool table that went out with it. They are ONE
/// subject with two faces -- the tools the run SERVED ride the item itself (the system row's
/// envelope `:tools`) -- so they share the pane as tabs rather than stacking, which would
/// prompt under a wall of JSON.
type SystemTab = "prompt" | "tools";

const ItemDetail: FC<{ item: TrajectoryItem; turn: TrajectoryTurn; onClose: () => void }> = ({
  item,
  turn,
  onClose,
}) => {
  const { t: tFormat, i18n } = useTranslation("format");
  const { t } = useTranslation("trajectory");
  const locale = asLanguage(i18n.language);
  /// Reset per item by the `key` the caller gives this component, so clicking a system
  /// row always opens on the prompt and the tools are one deliberate click away.
  const [tab, setTab] = useState<SystemTab>("prompt");
  /// The call this item belongs to, when the record pointed at one. It is where an
  /// assistant row's model, timing, tokens and tool table come from: the item itself
  /// carries only the pointer.
  ///
  /// `in` rather than a direct read: `call` is on the context, assistant and tool items
  /// and NOT on system/user, so an unguarded `item.call` is a type error -- the type
  /// system saying what the record says, that a system message belongs to no call.
  const callIndex = "call" in item ? item.call : undefined;
  const call = callIndex === undefined ? undefined : turn.calls?.[callIndex];

  return (
    <aside
      data-slot="trajectory-detail"
      className="flex min-h-0 min-w-0 flex-col border-l border-border"
    >
      <div className="flex shrink-0 items-center gap-2 border-b border-border px-3 py-1.5">
        <Chip kind={item.kind} />
        <span className="truncate text-xs text-muted-foreground">
          {t("turn.label", { n: turn.index })}
          {item.kind === "tool" && item.name !== undefined ? ` · ${item.name}` : ""}
          {callIndex === undefined ? "" : ` · ${t("call.label", { n: callIndex })}`}
        </span>
        <button
          type="button"
          onClick={onClose}
          aria-label={t("pane.close")}
          data-slot="trajectory-detail-close"
          className="ml-auto rounded-md p-1 text-muted-foreground hover:bg-muted hover:text-foreground"
        >
          <XIcon className="size-3.5" aria-hidden="true" />
        </button>
      </div>
      {/* The two tabs, when there are two. Not a row of furniture for every kind: only
          a system item has a second thing to show. */}
      {item.kind === "system" && (
        <div
          data-slot="trajectory-detail-tabs"
          className="flex shrink-0 items-center gap-1 border-b border-border px-2 py-1.5"
        >
          {(
            [
              ["prompt", t("tabs.systemPrompt")],
              ["tools", t("tabs.tools")],
            ] as const
          ).map(([key, label]) => (
            <button
              key={key}
              type="button"
              data-slot="trajectory-detail-tab"
              data-tab={key}
              aria-pressed={tab === key}
              onClick={() => setTab(key)}
              className={cn(
                "rounded-md px-2 py-0.5 text-xs",
                tab === key ? "bg-muted text-foreground" : "text-muted-foreground hover:text-foreground",
              )}
            >
              {label}
            </button>
          ))}
        </div>
      )}
      <div className="min-h-0 flex-1 overflow-auto p-3">
        {item.kind === "system" && tab === "prompt" && (
          <>
            <Facts
              pairs={[
                [t("facts.prompt"), item.initial === true ? t("values.initial") : t("values.changed")],
                [t("facts.bytes"), `${item.text.length}`],
              ]}
            />
            <Block text={item.text} mono />
          </>
        )}
        {item.kind === "system" && tab === "tools" && <ToolList turn={turn} tools={item.tools} />}

        {item.kind === "context" && (
          <>
            {/* ONE FACT AND THE CALL IT RODE: where a block sat relative to the
                client's messages used to be a second fact, and it stopped being one
                when every injection moved behind the question (see
                harness.edge.trajectory/context-item). */}
            <Facts
              pairs={[
                [t("facts.injected"), t("values.injected")],
                [t("facts.by"), item.call === undefined ? null : t("call.label", { n: item.call })],
              ]}
            />
            <Block text={item.text} mono />
          </>
        )}

        {item.kind === "user" && (
          <>
            <Facts
              pairs={[
                [t("facts.arrived"), item.at === undefined ? null : formatTime(item.at, locale)],
                [t("facts.id"), item.id ?? null],
              ]}
            />
            <Block text={item.text} />
          </>
        )}

        {item.kind === "assistant" && (
          <>
            <Facts
              pairs={[
                [t("facts.model"), call?.model ?? null],
                [
                  t("facts.took"),
                  call?.startedAt === undefined || call.endedAt === undefined
                    ? null
                    : formatMillis(call.endedAt - call.startedAt, tFormat),
                ],
                [t("facts.tokens"), call?.tokens === undefined ? null : formatTokens(call.tokens)],
                [t("facts.finished"), call?.finishReason ?? null],
                /// The COUNT only: the table itself is on the turn's system row, and the
                /// same JSON in two panes is one place to keep in step too many.
                [t("facts.tools"), call?.toolsCount === undefined ? null : `${call.toolsCount}`],
              ]}
            />
            {item.reasoning !== undefined && <Block label={t("blocks.reasoning")} text={item.reasoning} />}
            {item.text.trim() !== "" && <Block label={t("blocks.answer")} text={item.text} />}
          </>
        )}

        {item.kind === "tool" && (
          <>
            <Facts
              pairs={[
                [t("facts.executed"), item.executed ? t("values.yes") : t("values.no")],
                [t("facts.outcome"), item.outcome ?? null],
                /// THE TWO DURATIONS ARE DIFFERENT KINDS OF TIME. `waited` is the park --
                /// a person deciding; `ran` is the tool working, from the moment execution
                /// could start (`resumedAt`, or the arrival when nothing was parked) to the
                /// moment it LEFT execution. A call that never ran has neither.
                [
                  t("facts.waited"),
                  item.resumedAt === undefined || item.arrivedAt === undefined
                    ? null
                    : formatMillis(item.resumedAt - item.arrivedAt, tFormat),
                ],
                [
                  t("facts.ran"),
                  item.executedAt === undefined
                    ? null
                    : formatMillis(item.executedAt - (item.resumedAt ?? item.arrivedAt ?? item.executedAt), tFormat),
                ],
                [t("facts.id"), item.toolCallId],
              ]}
            />
            {item.argsText !== undefined && <Block label={t("blocks.arguments")} text={item.argsText} mono />}
            {item.error !== undefined && <Block label={t("blocks.error")} text={item.error} mono />}
            <Block label={t("blocks.result")} text={item.result} mono />
          </>
        )}
      </div>
    </aside>
  );
};

export const TrajectoryView: FC<{ threadId: string }> = ({ threadId }) => {
  const { t } = useTranslation("trajectory");
  /// THE REFETCH TRIGGER, read off the runtime here rather than handed in: one ReAct
  /// round is one assistant message on this side, so a rise in that count is a call that
  /// just finished, and `isRunning` catches the run that ends without one (an error).
  /// It has to be read from INSIDE the provider, which is why this component -- not the
  /// app shell above it -- owns it. Same reading, same reason, as `ComposerStats`.
  const assistantCount = useAuiState(
    (s) => s.thread.messages.filter((m) => m.role === "assistant").length,
  );
  const isRunning = useAuiState((s) => s.thread.isRunning);
  const [payload, setPayload] = useState<TrajectoryPayload | null>(null);
  /// WHICH ROW IS OPEN, as (turn, position in that turn). NOTHING IS OPEN BY DEFAULT:
  /// the list is the page, and the pane is something a reader asks for.
  const [selected, setSelected] = useState<{ turn: number; index: number } | null>(null);
  const [query, setQuery] = useState("");
  const [mode, setMode] = useState<Mode>("duration");

  useEffect(() => {
    let live = true;
    void trajectoryFor(threadId).then((next) => {
      // A late answer from a previous session must not land on this one.
      if (live) setPayload(next);
    });
    return () => {
      live = false;
    };
  }, [threadId, isRunning, assistantCount]);

  /// A CLICK ON THE STRIP OPENS THE SAME THING A CLICK ON THE ROW DOES -- so when one
  /// comes from up there, the row it names must be brought into view: otherwise the pane
  /// fills in and the reader has nothing to compare it against.
  const listRef = useRef<HTMLOListElement | null>(null);
  useEffect(() => {
    if (selected === null) return;
    const row = listRef.current?.querySelector(
      `[data-slot='trajectory-turn'][data-turn='${selected.turn}'] [data-slot='trajectory-item'][data-index='${selected.index}']`,
    );
    row?.scrollIntoView({ block: "nearest" });
  }, [selected]);

  const turns = payload?.turns ?? [];
  /// The open row, resolved against the CURRENT payload: a refetch can change what the
  /// record holds, and a pane describing an item that is no longer in it would be the one
  /// kind of lie this view must not tell. Failing to resolve closes the pane instead.
  const open = useMemo(() => {
    if (selected === null) return null;
    const turn = turns.find((candidate) => candidate.index === selected.turn);
    const item = turn?.items[selected.index];
    return turn === undefined || item === undefined ? null : { turn, item };
  }, [selected, turns]);

  if (payload === null) {
    return (
      <div data-slot="trajectory-view" className="flex h-full items-center justify-center">
        <p className="text-sm text-muted-foreground">{t("empty.noRecord")}</p>
      </div>
    );
  }

  if (turns.length === 0) {
    return (
      <div data-slot="trajectory-view" className="flex h-full items-center justify-center">
        <p className="text-sm text-muted-foreground">
          {t("empty.nothingRun")}
        </p>
      </div>
    );
  }

  return (
    <div data-slot="trajectory-view" className="flex h-full min-h-0 min-w-0 flex-col">
      <TrajectoryTimeline
        payload={payload}
        mode={mode}
        onMode={setMode}
        open={selected}
        onOpen={(target) => setSelected(target)}
      />
      <div className="flex shrink-0 items-center gap-2 border-b border-border px-3 py-1.5">
        <input
          type="search"
          value={query}
          data-slot="trajectory-search"
          onChange={(e) => setQuery(e.target.value)}
          placeholder={t("search.placeholder")}
          className="h-7 w-64 rounded-md border border-border bg-background px-2 text-xs"
        />
        <span className="text-xs text-muted-foreground">
          {t("turn.count", { count: turns.length })}
        </span>
        {payload.incomplete && (
          <span data-slot="trajectory-incomplete" className="text-xs text-amber-600 dark:text-amber-400">
            {t("header.incomplete")}
          </span>
        )}
      </div>
      {/* NO GRID UNTIL SOMETHING IS OPEN: with nothing selected the list gets the whole
          width, which is what "by default it is not shown" means in pixels. */}
      <div
        className={cn(
          "min-h-0 min-w-0 flex-1",
          open !== null && "grid grid-cols-[minmax(0,3fr)_minmax(0,2fr)]",
        )}
      >
        {/* `overflow-hidden` on the cross axis is what makes the rows' `truncate`
            authoritative: with `auto` the list still reports its content's min-content
            width upward, and the whole column grows instead of the text ellipsizing. */}
        <ol
          ref={listRef}
          className="h-full min-h-0 min-w-0 overflow-y-auto overflow-x-hidden"
          data-slot="trajectory-turns"
        >
          {turns.map((turn) => (
            <li key={turn.index} data-slot="trajectory-turn" data-turn={turn.index}>
              <div className="border-y border-border bg-muted/40 px-3 py-1">
                <span className="text-[0.7rem] font-medium">{t("turn.label", { n: turn.index })}</span>
                <span className="ml-2 text-[0.7rem] text-muted-foreground">
                  {t("turn.items", { n: turn.items.length })}
                  {turn.calls === undefined ? "" : ` · ${t("turn.calls", { n: turn.calls.length })}`}
                </span>
              </div>
              <ul>
                {turn.items.map((item, i) => (
                  <ItemRow
                    key={`${item.kind}-${i}`}
                    item={item}
                    index={i}
                    query={query.toLowerCase()}
                    selected={selected?.turn === turn.index && selected.index === i}
                    onSelect={() =>
                      setSelected((was) =>
                        was?.turn === turn.index && was.index === i ? null : { turn: turn.index, index: i },
                      )
                    }
                  />
                ))}
              </ul>
            </li>
          ))}
          {query !== "" && (
            <li className="px-3 py-2 text-xs text-muted-foreground">
              {t("list.hidden")}
            </li>
          )}
        </ol>
        {open !== null && (
          <ItemDetail
            key={selected === null ? "none" : `${selected.turn}-${selected.index}`}
            item={open.item}
            turn={open.turn}
            onClose={() => setSelected(null)}
          />
        )}
      </div>
    </div>
  );
};
