"use client";

// The card a COMPACTION draws in the conversation column.
//
// THE SECOND CARD, AND THE SAME SHELL. An injected context showed that a `CUSTOM` frame can carry
// something a person should see and the model must never be handed back (`.scratch/context-frames`);
// this is the same frame, used for the other thing that happens to a long conversation without
// saying so -- the harness folding its front into one summary. The server emits it, the adapter
// turns it into a `data` part, and `thread.aui.tsx`'s part switch has had
// `case "data": return part.dataRendererUI;` in it all along, so nothing in that copied file is
// edited for this.
//
// ONE ROW, COLLAPSED, LIKE A TOOL CALL AND LIKE AN INJECTION. Drawn with the same shell
// (`ToolFallbackRoot` / `ToolFallbackContent`) and the same 13px row for the same reason: "what the
// model was handed" and "what was taken away from it" are two answers to one question, and they
// should not look like two different apps. Collapsed by default: a summary is long, and the row is
// what tells a person it is there.
//
// THE ROW SAYS THE THREE THINGS THE FRAME CARRIES: the card's name, the summary's first line, and
// what the folded range was estimated at. The body is the whole summary, which is what the model is
// now reading.
//
// THE CARD IS A VIEW, NOT A MESSAGE. Upstream's outgoing conversion sends text, reasoning and tool
// calls; a `data` part is not among them, so nothing here reaches the model -- and the summary the
// model reads arrives by the one route it always did, the projection of the compaction fact over the
// record (`harness.edge.replay/model-nodes`).
import { type FC } from "react";

import type { DataMessagePartComponent } from "@assistant-ui/react";
import { makeAssistantDataUI } from "@assistant-ui/react";
import { ChevronsUpDownIcon } from "lucide-react";
import { useTranslation } from "react-i18next";

import { CollapsibleTrigger } from "@/components/ui/collapsible";
import {
  ToolFallbackContent,
  ToolFallbackRoot,
} from "@/components/assistant-ui/elements/tool-fallback.aui";
import {
  COMPACTION_PART,
  compactionText,
  compactionView,
  type CompactionValue,
} from "@/lib/compactions";
import { formatTokens } from "@/lib/format";

/// The card itself: one row that opens onto the summary the model reads in the folded range's place.
///
/// A VALUE RATHER THAN A PART, because the `data` part a frame carries is what it is handed -- and
/// with `view === null` (a frame with no summary) it draws NOTHING rather than an empty row: a card
/// that says nothing would claim a compaction nobody can check.
export const CompactionCard: FC<{ value: CompactionValue | undefined }> = ({ value }) => {
  const { t } = useTranslation("thread");
  const view = compactionView(value);
  if (view === null) return null;
  return (
    <ToolFallbackRoot>
      <CollapsibleTrigger
        data-slot="compaction-trigger"
        className="aui-compaction-trigger group/trigger text-muted-foreground hover:text-foreground flex w-full origin-left items-center gap-2 py-1.5 text-[13px] transition-[color,scale] active:scale-[0.98]"
      >
        <ChevronsUpDownIcon
          data-slot="compaction-trigger-icon"
          className="aui-compaction-trigger-icon size-4 shrink-0"
          aria-hidden="true"
        />
        <span
          data-slot="compaction-trigger-label"
          className="aui-compaction-trigger-label min-w-0 flex-1 truncate text-start leading-none"
        >
          <b className="aui-compaction-trigger-name">{t("compaction.name")}</b>
          {/* THE SEPARATOR IS NOT PART OF THE TITLE, for the same reason the injection card's is
              not: `data-slot` is this repo's hook, and a decorative " · " inside the slot would
              make every consumer strip it back off. */}
          <span aria-hidden="true">{" · "}</span>
          <span data-slot="compaction-trigger-title" className="aui-compaction-trigger-title">
            {view.preview}
          </span>
        </span>
        {/* WHAT THE FOLDED RANGE WAS ESTIMATED AT, and NOTHING when the frame did not say it:
            `0 tok` would be a size invented for a range nobody measured. */}
        {view.tokens === null ? null : (
          <span
            data-slot="compaction-trigger-size"
            className="aui-compaction-trigger-size shrink-0 text-xs tabular-nums"
          >
            {t("compaction.tokens", { value: formatTokens(view.tokens) })}
          </span>
        )}
      </CollapsibleTrigger>
      <ToolFallbackContent>
        <div data-slot="compaction-content" className="aui-compaction-content flex flex-col gap-1">
          {view.messages === null ? null : (
            <span
              data-slot="compaction-folded"
              className="aui-compaction-folded text-muted-foreground text-xs"
            >
              {t("compaction.folded", { count: view.messages })}
            </span>
          )}
          <pre
            data-slot="compaction-summary"
            className="aui-compaction-summary text-foreground/90 max-h-96 overflow-auto text-xs leading-relaxed break-words whitespace-pre-wrap"
          >
            {compactionText(value)}
          </pre>
        </div>
      </ToolFallbackContent>
    </ToolFallbackRoot>
  );
};

/// THE PART'S RENDERER, which is what the registration below draws: `data` is the part's value, and
/// the card above is the whole of it.
const CompactionCardPart: DataMessagePartComponent<CompactionValue> = ({ data }) => (
  <CompactionCard value={data} />
);

/// REGISTERED BY BEING MOUNTED, not by a table somewhere: `makeAssistantDataUI` answers a component
/// whose rendering is the registration (see its own d.ts), which is why `app.tsx` draws
/// `<CompactionCards />` inside the runtime provider beside `<ContextCards />` and nothing else has
/// to know this card exists.
export const CompactionCards = makeAssistantDataUI({
  name: COMPACTION_PART,
  render: CompactionCardPart,
});
