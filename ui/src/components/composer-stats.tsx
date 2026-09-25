// The status strip under the composer: how many turns and model calls this
// session has run, how fast, how many tokens, how much of it was cached.
//
// ------------------------------------------------- where it sits, and why there
//
// It renders INSIDE `ComposerFrame` and after the composer, so it lands under the
// input and inside the same rounded box. The context bar above the composer is the
// other half of this arrangement: that one is about WHERE the session is going to
// run and folds away once the conversation starts, while this one reports what the
// conversation HAS cost and appears once there is anything to report.
//
// It does not touch `components/assistant-ui/elements/thread.aui.tsx` -- that is a
// copy of the assistant-ui element, and the two LOCAL: insertion points in
// composer-chrome.tsx are the whole reason this file can exist without editing it.
// (That copy does carry in-place edits of its own now, each marked `LOCAL:`; this
// file's copy simply is not one of them.)
//
// --------------------------------------------- the numbers are the SERVER's, not ours
//
// The five facts come from GET /api/threads/<stem>/stats, folded from the session's
// jsonl record. NONE OF THEM IS COMPUTED HERE, and the tempting shortcut is worth
// naming: the client does hold the conversation, so it could count turns and steps
// and estimate tokens from the bytes it has streamed (the AG-UI runtime already
// does `chars / 4` for its own timing). That estimate is not the vendor's usage, and
// cache hits are not derivable from it at all -- so a strip built on it would be
// printing an invention under a heading that promises a measurement. The record is
// the only place those facts exist; see .scratch/composer-status/spec.md.
//
// ------------------------------------------------------- when it asks, and when not
//
// IT DOES NOT ASK FOR THEM ITSELF any more: the fetch and its triggers live in ONE
// place for the whole composer (components/composer-numbers.tsx), so the strip and the
// ring beside the model cannot end up showing two moments of the same log. Read that
// file for when it asks and for why there is no polling.
//
// ----------------------------------- a row that will not wrap, and what it gives up
//
// THE ROW IS FIXED AT ONE LINE (`h-5`, from `.scratch/mobile-adaptation/` 04: the
// strip holds its row from the first paint, so a number arriving late cannot shove
// the composer), and the cells in it are NOT fixed: four digits where there was one,
// and two groups that fitted a phone no longer do. What a row of one line's height
// must not do is WRAP -- the text would grow a second line inside a one-line box and
// spill out of the composer, over the conversation -- so this one refuses to
// (`whitespace-nowrap`) and SHEDS whole cells instead: `GIVE_UP` says in what order,
// and `useShedCells` is how the question is asked and answered.
import {
  Fragment,
  type FC,
  type ReactNode,
  type RefObject,
  useLayoutEffect,
  useRef,
  useState,
} from "react";
import { DatabaseIcon, TimerIcon } from "lucide-react";
import { useTranslation } from "react-i18next";

import { useComposerNumbers } from "@/components/composer-numbers";
import { statsCells } from "@/lib/format";

/// One `·` between two cells of a group. Its own component so the gap on either
/// side is stated once: the separator belongs to the row, not to the cell that
/// happens to follow it.
const Sep: FC = () => <span aria-hidden="true">·</span>;

/// THE ORDER CELLS GO IN when the row runs out of width -- and, by being the whole
/// list, WHICH TWO NEVER GO.
///
/// The speed leaves first: it is a reading about the last call rather than a cost of
/// the session. The session's token total goes next. The turn count goes last. What
/// stays to the end is the model-call count and the cache share -- what this session
/// has cost, and how much of it the vendor already had -- because those are the two
/// a glance at a phone is for.
const GIVE_UP = ["rate", "total", "turns"] as const;

/// A cell that can leave the row.
type Shed = (typeof GIVE_UP)[number];

/// One group's cells, with a `·` BETWEEN two drawn ones and never at an end.
///
/// IT IS HANDED A LIST rather than written out as markup, because the LENGTH of that
/// list is what the give-up rule changes: with the cells written one by one, every
/// separator would have to ask whether the cell before it survived, and that one
/// question would be asked in four places instead of here.
const Group: FC<{ parts: ReactNode[] }> = ({ parts }) => (
  <>
    {parts.map((part, index) => (
      <Fragment key={index}>
        {index > 0 && <Sep />}
        {part}
      </Fragment>
    ))}
  </>
);

/// HOW MANY OF `GIVE_UP` ARE OFF THE ROW AT THIS MOMENT.
///
/// THE ROW REFUSES TO WRAP, and that refusal is what makes the question answerable at
/// all: held to one line, "has it run out of width" is exactly `scrollWidth >
/// clientWidth`, and one cell can be taken away and the question asked again.
///
/// IT IS ASKED AFTER EVERY RENDER -- the second effect below has no dependency list --
/// because the numbers arrive from a fetch: the row's own width does not move when the
/// cells it holds get longer, so a measurement taken only on resize would never be
/// retaken.
///
/// AND IT IS ANSWERED ONCE PER WIDTH, which is the whole of the stability argument.
/// Giving a cell up is what makes the row fit, so a rule that put cells back whenever
/// the row fitted would take them away again on the next pass and never settle. A row
/// that changed WIDTH is the one event that can honestly put them back -- a phone
/// turned, the right pane opened, the sidebar rail collapsed -- so it is the only one
/// that resets the answer.
function useShedCells(row: RefObject<HTMLDivElement | null>): number {
  const [shed, setShed] = useState(0);
  /// The render a resized box owes the effect below. ITS VALUE IS NEVER READ: what is
  /// wanted is the render, because the effect that decides runs after every one.
  const [, setBox] = useState(0);

  /// THE BOX IS WATCHED HERE, the content by the effect below. A resize that did not
  /// change the width is not a new question.
  useLayoutEffect(() => {
    const el = row.current;
    if (el === null || typeof ResizeObserver === "undefined") return;
    let seen = el.clientWidth;
    const observer = new ResizeObserver(() => {
      const width = el.clientWidth;
      if (width === seen) return;
      seen = width;
      setShed(0);
      setBox(width);
    });
    observer.observe(el);
    return () => observer.disconnect();
  }, [row]);

  useLayoutEffect(() => {
    const el = row.current;
    if (el === null) return;
    if (shed < GIVE_UP.length && el.scrollWidth > el.clientWidth) setShed(shed + 1);
  });

  return shed;
}

export const ComposerStats: FC = () => {
  /// The strip's five cells are phrases, so their words come from the `format` face
  /// even though the numbers are the server's: `statsCells` is handed a translator
  /// rather than reaching for one, which is what keeps it a pure function a suite can
  /// call (see `lib/format.ts`).
  const { t } = useTranslation("format");
  /// The numbers `ComposerFrame` fetched, not this component's own.
  const { payload } = useComposerNumbers();

  const cells = statsCells(payload, t);

  /// The box the give-up rule measures, and how much of it has been given up.
  const row = useRef<HTMLDivElement | null>(null);
  const shed = useShedCells(row);
  const gone = new Set<Shed>(GIVE_UP.slice(0, shed));

  /// WHAT THE ROW DRAWS: two lists, because their LENGTH is what the give-up rule
  /// changes (see `Group`). A cell the rule took away is not drawn and takes nothing
  /// with it; a cell the record never had is absent for the reason `statsCells` gives.
  const left: ReactNode[] = [];
  const right: ReactNode[] = [];
  if (cells !== null) {
    if (!gone.has("turns")) left.push(<span data-slot="stats-turns">{cells.turns}</span>);
    if (cells.steps !== null) left.push(<span data-slot="stats-steps">{cells.steps}</span>);
    if (cells.rate !== null && !gone.has("rate")) {
      left.push(<span data-slot="stats-rate">{cells.rate}</span>);
    }
    if (cells.total !== null && !gone.has("total")) {
      right.push(<span data-slot="stats-usage">{cells.total}</span>);
    }
    if (cells.cached !== null) right.push(<span data-slot="stats-cached">{cells.cached}</span>);
  }

  /// THE STRIP HOLDS ITS ROW WHETHER OR NOT THERE IS ANYTHING TO SAY, and that is a
  /// change of decision rather than a detail. It used to return `null` outright while
  /// `cells` was null -- a first read still in flight, or a session that has never run --
  /// and then the whole strip appeared the moment the numbers landed, growing the composer
  /// frame and shoving the docked composer up under the reader's cursor. So the row's
  /// height is FIXED below: it is the same whether cells are drawn into it or not, and
  /// `data-slot="composer-stats"` is always in the DOM. Nothing is invented to fill it --
  /// an empty row says nothing, rather than "0 tok" (see `statsCells`).
  return (
    <div
      data-slot="composer-stats"
      ref={row}
      // `tabular-nums` because the numbers change: without it a digit growing from
      // 9 to 10 shifts the whole row, and a strip that twitches is worse than one
      // that is simply there.
      //
      // `h-5` is ONE LINE of this strip, expressed as a FIXED height rather than a `min-`:
      // the row is reserved from the first paint, so neither a change of font size nor a
      // number arriving late can change how tall the composer is.
      //
      // `whitespace-nowrap` and `overflow-hidden` are the OTHER half of that height being
      // fixed: one line's worth of box will not be grown by the cells, so the cells are
      // what gives (see `useShedCells`, which measures exactly this pair).
      className="text-muted-foreground flex h-5 items-center justify-between gap-4 overflow-hidden px-1.5 pt-0.5 pb-1 text-[10px] whitespace-nowrap tabular-nums"
    >
      {left.length > 0 && (
        <span className="flex items-center gap-1.5">
          {/* The stopwatch introduces the turn count, so it goes when that count does:
              a clock in front of a count of model calls says the wrong thing. */}
          {!gone.has("turns") && <TimerIcon className="size-3.5 shrink-0" />}
          <Group parts={left} />
        </span>
      )}

      {right.length > 0 && (
        <span className="flex items-center gap-1.5">
          <DatabaseIcon className="size-3.5 shrink-0" />
          <Group parts={right} />
        </span>
      )}
    </div>
  );
};
