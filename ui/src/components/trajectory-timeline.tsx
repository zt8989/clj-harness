// The strip above the trajectory: three lanes -- input, model, tools -- with one mark
// per thing that happened, laid out on a time axis.
//
// --------------------------------------------------- what it draws, and what it does not
//
// IT DRAWS NOTHING IT WAS NOT GIVEN. Every mark comes from a timestamp the record
// carries, straight out of the fold: a user message's `at` (the input line's own `:ts`),
// a call's `startedAt`/`endedAt` (the `model/*` pair), a tool call's
// `queuedAt`/`startedAt`/`endedAt` (the `tools/*` trio). A record that predates the
// model lines has an EMPTY model lane, and it says so in words rather than drawing a
// flat line at zero -- "this record cannot tell" is not "this call took no time".
//
// THE TWO MODES ARE TWO WAYS OF LAYING THE SAME MARKS OUT, not two datasets:
//
//   Duration -- a real time axis, earliest mark to latest. Gaps are real gaps: this is
//               the one that shows the two minutes spent waiting for a human.
//   Turns    -- every turn the same width, and each turn's own marks spread inside it.
//               A long quiet turn and a short loud one become comparable, which is what
//               a reader wants when the question is "which turn was the heavy one".
//
// AN EQUAL-WIDTH-PER-CALL THIRD MODE IS DELIBERATELY ABSENT: it is a third way to draw
// the same marks and it answers no question the other two do not. See the spec's
// non-goals.
//
// CONCURRENT TOOL CALLS REALLY DO OVERLAP, so the tool lane STACKS them instead of
// laying them end to end: a turn that ran three tools at once must not read as three
// tools in a row. The stacking is what makes the lane's height a quiet statement about
// how parallel that turn was.
import { type FC, useMemo } from "react";
import { useTranslation } from "react-i18next";

import { formatMillis } from "@/lib/format";
import { KIND_HUE, LANE_KIND, type Lane } from "@/components/trajectory-colors";
import type { TrajectoryPayload, TrajectoryTurn } from "@/lib/trajectory";
import { cn } from "@/lib/utils";

/// One mark on a lane: where it starts and ends, what to say about it, and WHICH ITEM it
/// stands for.
///
/// `index` IS THE ITEM'S POSITION IN ITS TURN, which is what makes a mark clickable: a
/// mark on a lane and a row in the list are two drawings of ONE thing, so clicking either
/// opens the same detail. It is `null` when there is no row to open -- a model call the
/// record never paired with an answer (a session read while its first call is still
/// streaming) is a real mark with no item behind it, and pretending otherwise would open
/// an empty pane.
type Mark = {
  turn: number;
  index: number | null;
  /// A label for the reader ("read", "turn 2", ...).
  label: string;
  start: number;
  end: number;
  /// A wait inside the mark -- the time a parked call spent waiting for a person. Drawn
  /// as a lighter lead-in so the execution time stays readable as its own segment.
  wait?: number;
  /// Never ran (a vetoed call): drawn as a hollow tick, because it happened and cost no
  /// run time, and a zero-width bar would say the opposite.
  never?: boolean;
};

const marksOf = (turn: TrajectoryTurn, lane: "input" | "model" | "tool"): Mark[] => {
  if (lane === "input") {
    return turn.items.flatMap((item, index) =>
      item.kind === "user" && item.at !== undefined
        ? [{ turn: turn.index, index, label: `turn ${turn.index}`, start: item.at, end: item.at }]
        : [],
    );
  }
  if (lane === "model") {
    /// A CALL'S ROW IS ITS ANSWER: the assistant item whose `:call` points at it. A call
    /// with no such item -- still streaming, or a record that predates the model lines --
    /// is drawn without a target rather than wired to the wrong row.
    return (turn.calls ?? []).flatMap((call) => {
      if (call.startedAt === undefined) return [];
      const row = turn.items.findIndex((item) => item.kind === "assistant" && item.call === call.index);
      return [
        {
          turn: turn.index,
          index: row === -1 ? null : row,
          label: call.model ?? `call ${call.index}`,
          start: call.startedAt,
          end: call.endedAt ?? call.startedAt,
        },
      ];
    });
  }
  return turn.items.flatMap((item, index) => {
      if (item.kind !== "tool" || item.arrivedAt === undefined) return [];
      /// THE BAR IS THE TOOL'S LIFE IN THE SEAM: it arrived, and -- unless nobody has
      /// answered yet -- it finished executing. `executedAt` is the moment it LEFT
      /// execution, so using it as the bar's END is what makes the bar as long as the
      /// tool really took; using it as the start would draw every tool as instantaneous.
      const resumed = item.resumedAt ?? item.arrivedAt;
      const end = item.executedAt ?? item.closedAt ?? resumed;
      return [
        {
          turn: turn.index,
          index,
          label: item.name ?? item.toolCallId,
          start: resumed,
          end,
          /// The park, when there was one, is the lead-in: time a PERSON spent, which is
          /// real but is not the tool working.
          wait: item.resumedAt === undefined ? undefined : item.resumedAt - item.arrivedAt,
          never: !item.executed,
        },
      ];
    });
};

/// A lane as a row of positioned marks. Positions are fractions of the axis, so the
/// lane's own width never enters the arithmetic.
const Lane: FC<{
  name: string;
  lane: Lane;
  marks: readonly Mark[];
  span: Span;
  mode: Mode;
  open: { turn: number; index: number } | null;
  onOpen: (target: { turn: number; index: number }) => void;
}> = ({ name, lane, marks, span, mode, open, onOpen }) => {
  const { t } = useTranslation("format");
  /// The stacking: marks that overlap in time get their own vertical offset, so the
  /// lane's height grows with the turn's parallelism and no mark hides another.
  const rows = useMemo(() => {
    const placed: { mark: Mark; row: number }[] = [];
    for (const mark of marks) {
      const [from, to] = position(mark, span, mode);
      let row = 0;
      // A simple first-fit: each mark takes the lowest row whose last mark ends before
      // this one starts. Enough for the handful of concurrent tool calls a turn makes.
      while (
        placed.some(
          (p) =>
            p.row === row &&
            position(p.mark, span, mode)[1] > from &&
            to > position(p.mark, span, mode)[0],
        )
      ) {
        row += 1;
      }
      placed.push({ mark, row });
    }
    return placed;
  }, [marks, span, mode]);

  const height = Math.max(1, ...rows.map((r) => r.row + 1)) * 10;

  return (
    <div className="flex items-center gap-2" data-slot="trajectory-lane" data-lane={name}>
      <span className="w-14 shrink-0 text-[0.7rem] text-muted-foreground">{name}</span>
      <div className="relative flex-1" style={{ height }} data-slot="trajectory-lane-track">
        {rows.map(({ mark, row }, i) => {
          const [from, to] = position(mark, span, mode);
          const waitWidth =
            mark.wait === undefined || mark.start === 0
              ? 0
              : (mark.wait / Math.max(1, mark.end - mark.start + mark.wait)) * 100;
          const isOpen = mark.index !== null && open?.turn === mark.turn && open.index === mark.index;
          const duration = formatMillis(Math.max(0, mark.end - mark.start), t);
          const geometry = {
            left: `${from * 100}%`,
            width: `${Math.max(to - from, 0.004) * 100}%`,
            transform: `translateY(${row * 10}px)`,
          };
          const skin = cn(
            "absolute top-0 h-1.5 min-w-[2px] rounded-full",
            // THE LANE'S OWN HUE, taken from the shared table: a mark on the `tools`
            // lane is a tool call and wears the tool chip's colour, so the strip and
            // the list read as one language.
            KIND_HUE[LANE_KIND[lane]].bar,
            // A call that never ran is hollow: it happened and cost nothing, and a
            // filled bar would say the opposite.
            mark.never === true && "bg-transparent ring-1 ring-muted-foreground/60",
          );
          const wait =
            waitWidth > 0 && (
              <span
                data-slot="trajectory-segment-wait"
                className="absolute inset-y-0 left-0 rounded-full bg-muted-foreground/25"
                style={{ width: `${waitWidth}%` }}
              />
            );

          /// A MARK WITH A ROW BEHIND IT IS A BUTTON -- clicking it opens the same detail
          /// the row would, because the two are one thing drawn twice. A mark with no row
          /// (a call whose answer is not in the record yet) stays a plain span: it is a
          /// fact about the run, not a doorway to nowhere.
          return mark.index === null ? (
            <span key={`${mark.label}-${i}`} title={`${mark.label} · ${duration}`} data-slot="trajectory-segment" data-mode={mode} className={skin} style={geometry}>
              {wait}
            </span>
          ) : (
            <button
              key={`${mark.label}-${i}`}
              type="button"
              onClick={() => onOpen({ turn: mark.turn, index: mark.index as number })}
              title={`${mark.label} · ${duration} — click to open it`}
              aria-label={`open ${mark.label} in turn ${mark.turn}`}
              aria-pressed={isOpen}
              data-slot="trajectory-segment"
              data-mode={mode}
              data-open={isOpen}
              className={cn(
                skin,
                "cursor-pointer hover:h-2",
                /// The open one is ringed, so the strip and the list agree about what is
                /// being looked at in both directions.
                isOpen && "h-2 ring-2 ring-foreground/40",
              )}
              style={geometry}
            >
              {wait}
            </button>
          );
        })}
      </div>
    </div>
  );
};

/// A real time axis, or the same axis per turn. See the header.
export type Mode = "duration" | "turns";

/// The axis one mode implies: the whole span, how many turns there are, and each
/// turn's own span. `count` is kept apart from `perTurn` because a turn with no marks
/// at all has no span of its own -- it still occupies a slot in `turns` mode, and
/// reading the slot count off the map would silently shrink the axis.
type Span = {
  from: number;
  to: number;
  count: number;
  perTurn: Map<number, { from: number; to: number }>;
};

/// Where a mark sits on the axis, as [0..1] fractions. In `turns` mode the turn's own
/// slot is the unit, so every turn is equally wide however long it took.
const position = (mark: Mark, span: Span, mode: Mode): [number, number] => {
  if (mode === "duration") {
    const width = Math.max(1, span.to - span.from);
    return [(mark.start - span.from) / width, (mark.end - span.from) / width];
  }
  const turns = Math.max(1, span.count);
  const slot = 1 / turns;
  const own = span.perTurn.get(mark.turn) ?? { from: mark.start, to: mark.end };
  const width = Math.max(1, own.to - own.from);
  const offset = (mark.turn - 1) * slot;
  return [
    offset + ((mark.start - own.from) / width) * slot,
    offset + ((mark.end - own.from) / width) * slot,
  ];
};

const spanOf = (turns: readonly TrajectoryTurn[]): Span => {
  const all = turns.flatMap((turn) => [
    ...marksOf(turn, "input"),
    ...marksOf(turn, "model"),
    ...marksOf(turn, "tool"),
  ]);
  /// A turn with no marks is left OUT of the map rather than given an empty span:
  /// `position` falls back to the mark's own span for a turn it cannot find, and an
  /// Infinity-to-minus-Infinity entry would instead produce NaN geometry.
  const perTurn = new Map(
    turns
      .map((turn) => {
        const own = [
          ...marksOf(turn, "input"),
          ...marksOf(turn, "model"),
          ...marksOf(turn, "tool"),
        ];
        return [turn.index, own] as const;
      })
      .filter(([, own]) => own.length > 0)
      .map(([index, own]) => [
        index,
        { from: Math.min(...own.map((m) => m.start)), to: Math.max(...own.map((m) => m.end)) },
      ] as const),
  );
  return {
    from: Math.min(...all.map((m) => m.start)),
    to: Math.max(...all.map((m) => m.end)),
    count: turns.length,
    perTurn,
  };
};

export const TrajectoryTimeline: FC<{
  payload: TrajectoryPayload;
  mode: Mode;
  onMode: (mode: Mode) => void;
  open: { turn: number; index: number } | null;
  onOpen: (target: { turn: number; index: number }) => void;
}> = ({ payload, mode, onMode, open, onOpen }) => {
  const { t } = useTranslation("format");
  const span = useMemo(() => spanOf(payload.turns), [payload.turns]);
  const lanes: { name: string; lane: Lane }[] = [
    { name: "input", lane: "input" },
    { name: "model", lane: "model" },
    { name: "tools", lane: "tool" },
  ];
  const total = span.to - span.from;
  const nothingToDraw = !Number.isFinite(total);

  return (
    <div data-slot="trajectory-timeline" className="border-b border-border px-3 py-2">
      <div className="mb-1.5 flex items-center gap-2">
        {(["duration", "turns"] as const).map((m) => (
          <button
            key={m}
            type="button"
            data-slot="trajectory-mode"
            data-mode={m}
            aria-pressed={mode === m}
            onClick={() => onMode(m)}
            className={cn(
              "rounded-md px-1.5 py-0.5 text-xs",
              mode === m ? "bg-muted text-foreground" : "text-muted-foreground hover:text-foreground",
            )}
          >
            {m}
          </button>
        ))}
        {!nothingToDraw && (
          <span className="ml-auto text-xs tabular-nums text-muted-foreground">
            {formatMillis(Math.max(0, total), t)} total
          </span>
        )}
      </div>
      {/* A record with no marks at all gets a sentence, not an empty axis. */}
      {nothingToDraw ? (
        <p className="text-xs text-muted-foreground">
          this record has no timings yet — nothing to lay out.
        </p>
      ) : (
        lanes.map(({ name, lane }) => (
          <Lane
            key={lane}
            name={name}
            lane={lane}
            marks={payload.turns.flatMap((turn) => marksOf(turn, lane))}
            span={span}
            mode={mode}
            open={open}
            onOpen={onOpen}
          />
        ))
      )}
    </div>
  );
};
