// The ring beside the model: how full this session's context window is, and -- once
// you click it -- what filled it.
//
// ------------------------------------------------------------- what a ring means
//
// A RING IS A FRACTION, so this draws one only when a fraction exists: a vendor's
// `prompt_tokens` and a window the catalog declared for THAT call (see
// harness.edge.context). No numerator, no denominator, or no session at all (a
// session that never ran has no log) and there is nothing here -- the same discipline
// as the strip under the composer, which is also not drawn when it has no numbers.
// `contextCells` returns null for all three, and this component draws nothing.
//
// ---------------------------------------------------------- it is the same three
//
// THE RING IS THE PANEL, WRAPPED AROUND. Its filled part is split into the same three
// buckets the panel lists -- system prompt, tool definitions, conversation -- so the
// circle already says what the click will spell out, and the two cannot disagree:
// both read one `parts` vector, from one payload. A payload whose buckets are missing
// (the run that produced the numbers is still being written) draws the share in one
// colour instead; the share is a measured fact and the split is not there yet.
//
// THE COLOURS ARE THE TRAJECTORY'S, WHEREVER THE THING IS THE SAME (see
// components/trajectory-colors.ts): the system message is `primary` there and here, a
// tool is amber there and here, and the conversation takes the user hue, because it is
// where a person's own messages land. The classes are written out rather than built
// from the key -- Tailwind scans source for literal class strings.
//
// ------------------------------------------------------------ where it is, and why
//
// LEFT OF THE MODEL, in the composer's action row, inside the same `<div>` as the
// picker: the window belongs to the model that is selected, so the two belong
// together. It is a sibling of the picker rather than something inside it, because
// clicking the ring must not open the model menu.
//
// IT DOES NOT FETCH. The numbers are whatever `ComposerFrame` fetched for the whole
// composer (components/composer-numbers.tsx), so this and the strip can never show two
// moments of one log.
import { type FC, useState } from "react";
import { useTranslation } from "react-i18next";

import { useComposerNumbers } from "@/components/composer-numbers";
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover";
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip";
import { type ContextCells, type ContextPart, contextCells } from "@/lib/format";

/// One hue per bucket, by the key the record reports. A key with no hue is not drawn
/// -- `contextCells` has already dropped the ones the catalog has no name for.
const PART_HUES: Record<string, { stroke: string; bar: string }> = {
  system: { stroke: "text-primary", bar: "bg-primary/70" },
  tools: { stroke: "text-amber-500", bar: "bg-amber-500/70" },
  conversation: { stroke: "text-sky-500", bar: "bg-sky-500/70" },
};

/// The hue of a bucket the table does not know. It cannot happen -- `contextCells`
/// drops those -- and a colour with no key is the one answer that is never wrong.
const UNKNOWN_HUE = { stroke: "text-foreground", bar: "bg-foreground/70" };

/// The viewBox the arc lengths are expressed in: `pathLength` 100 on every circle, so
/// a length IS a percentage of the circumference and the geometry stays out of the
/// arithmetic.
const BOX = 20;
const RADIUS = 8;
const THICKNESS = 2.5;

/// One arc, from OFFSET per cent around the circle. The dash is the arc and the gap is
/// the rest of the circle, which is what makes a partial ring out of a full circle.
const Arc: FC<{ length: number; offset: number; className: string }> = ({
  length,
  offset,
  className,
}) => (
  <circle
    className={className}
    cx={BOX / 2}
    cy={BOX / 2}
    r={RADIUS}
    fill="none"
    stroke="currentColor"
    strokeWidth={THICKNESS}
    pathLength={100}
    strokeDasharray={`${length} ${100 - length}`}
    strokeDashoffset={-offset}
  />
);

/// The buckets as arcs: each one's share of the prompt, times how full the window is.
/// Empty when the record has no buckets, which is not the same as no fraction -- the
/// caller decides what to draw then.
function arcsOf(parts: ContextPart[], percent: number): { key: string; length: number; offset: number }[] {
  const total = parts.reduce((sum, part) => sum + part.tokens, 0);
  if (total <= 0) return [];
  const filled = Math.min(100, Math.max(0, percent));
  let offset = 0;
  return parts.map((part) => {
    const length = (filled * part.tokens) / total;
    const arc = { key: part.key, length, offset };
    offset += length;
    return arc;
  });
}

/// The panel: the same fraction, written out. It is drawn INSIDE the popover, so it
/// has no trigger of its own -- and it renders nothing but the buckets when the record
/// has none, which is what a run in flight looks like (see `contextCells`).
const Panel: FC<{ cells: ContextCells }> = ({ cells }) => {
  const total = cells.parts.reduce((sum, part) => sum + part.tokens, 0);
  return (
    <div data-slot="composer-context-panel" className="flex flex-col gap-2">
      <div className="flex items-baseline justify-between gap-4">
        <span data-slot="context-panel-share">{cells.label}</span>
        {/* THE SIZES ARE WHAT THEY ARE: the window is the model's own declaration and the
            other number is the vendor's count of the prompt, so neither is written with
            the `~` its buckets carry. */}
        <span data-slot="context-panel-sizes" className="text-muted-foreground tabular-nums">
          {cells.used} / {cells.window}
        </span>
      </div>
      {cells.parts.length > 0 && (
        <>
          {/* One bar, three bands: the buckets add up to the whole prompt (the server's
              own guarantee), so the bar always reaches its end and needs no fourth band
              for 'the rest'. */}
          <div data-slot="context-panel-bar" className="bg-muted flex h-1.5 w-full overflow-hidden rounded-full">
            {cells.parts.map((part) => (
              <span
                key={part.key}
                className={(PART_HUES[part.key] ?? UNKNOWN_HUE).bar}
                style={{ width: `${(part.tokens / total) * 100}%` }}
              />
            ))}
          </div>
          <ul data-slot="context-panel-parts" className="flex flex-col">
            {cells.parts.map((part) => (
              <li key={part.key} className="flex items-center justify-between gap-4 text-xs">
                <span className="flex items-center gap-2">
                  <span
                    aria-hidden="true"
                    className={`size-2.5 shrink-0 rounded-xs ${(PART_HUES[part.key] ?? UNKNOWN_HUE).bar}`}
                  />
                  {part.label}
                </span>
                <span className="text-muted-foreground tabular-nums">{part.value}</span>
              </li>
            ))}
          </ul>
        </>
      )}
    </div>
  );
};

export const ContextRing: FC = () => {
  const { t } = useTranslation("format");
  const numbers = useComposerNumbers();
  const cells = contextCells(numbers.payload, t);
  // THE HOVER AND THE PANEL ARE TWO SURFACES OF ONE ANSWER, so the hover is suppressed
  // while the panel is up: they would otherwise sit in the same place, saying the same
  // sentence twice, one over the other.
  const [panelOpen, setPanelOpen] = useState(false);
  const [hovered, setHovered] = useState(false);
  if (cells === null) return null;

  const arcs = arcsOf(cells.parts, cells.percent);
  const filled = Math.min(100, Math.max(0, cells.percent));

  return (
    <Popover
      open={panelOpen}
      // THE CLICK IS THE QUESTION: opening the panel asks once more, because the last
      // ask may have been before the record's writer caught up with the run (see
      // components/composer-numbers.tsx).
      onOpenChange={(open) => {
        setPanelOpen(open);
        if (open) numbers.reload();
      }}
    >
      <Tooltip open={!panelOpen && hovered} onOpenChange={setHovered}>
        <TooltipTrigger asChild>
          <PopoverTrigger asChild>
            <button
              type="button"
              data-slot="composer-context-usage"
              aria-label={cells.label}
              className="text-muted-foreground/25 shrink-0 rounded-full focus-visible:ring-ring focus-visible:ring-2 focus-visible:outline-none"
            >
              <svg viewBox={`0 0 ${BOX} ${BOX}`} className="size-4 -rotate-90" aria-hidden="true">
                <Arc length={100} offset={0} className="text-muted-foreground/25" />
                {arcs.length === 0 ? (
                  <Arc length={filled} offset={0} className={UNKNOWN_HUE.stroke} />
                ) : (
                  arcs.map((arc) => (
                    <Arc
                      key={arc.key}
                      length={arc.length}
                      offset={arc.offset}
                      className={(PART_HUES[arc.key] ?? UNKNOWN_HUE).stroke}
                    />
                  ))
                )}
              </svg>
            </button>
          </PopoverTrigger>
        </TooltipTrigger>
        <TooltipContent side="top">{cells.label}</TooltipContent>
      </Tooltip>
      <PopoverContent side="top" align="start" className="w-64">
        <Panel cells={cells} />
      </PopoverContent>
    </Popover>
  );
};
