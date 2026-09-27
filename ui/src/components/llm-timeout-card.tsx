"use client";

// The card A MODEL CALL THAT WENT QUIET draws in the conversation column.
//
// THE SECOND `data` PART THIS APP OWNS, after the injection card, and it is the same
// machinery for a different subject: the server emits a CUSTOM frame, the adapter turns it
// into a `data` part, and `makeAssistantDataUI` is what draws it. Nothing in the copied
// `thread.aui.tsx` is edited for this -- `case "data": return part.dataRendererUI;` was
// always there.
//
// A ROW, COLLAPSED, LIKE A TOOL CALL AND AN INJECTION, and for the same reason: a vendor
// that stalled four times in a row must not be four walls of prose. The row says the pair a
// person acts on -- how long the silence was, and whether the server is trying again -- and
// the sentence it opens onto says what happened and what it means.
//
// NOTHING HERE IS PART OF THE CONVERSATION. The frame is never recorded server-side (see
// `harness.edge.http/wire-only-frame?`), so a reload does not bring this card back; and no
// part of it is ever sent to the model, because `toAgUiMessages` has no case for a `data`
// part (the `injections` suite pins that, and this card rests on it the same way).
import { type FC } from "react";

import type { DataMessagePartComponent } from "@assistant-ui/react";
import { makeAssistantDataUI } from "@assistant-ui/react";
import { TriangleAlertIcon } from "lucide-react";
import { useTranslation } from "react-i18next";

import { CollapsibleTrigger } from "@/components/ui/collapsible";
import {
  ToolFallbackContent,
  ToolFallbackRoot,
} from "@/components/assistant-ui/elements/tool-fallback.aui";
import {
  TIMEOUT_PART,
  timeoutOutcome,
  timeoutView,
  type TimeoutValue,
  type TimeoutView,
} from "@/lib/llm-timeout";

/// The card itself: one row that opens onto the sentence about the call.
const TimeoutCard: FC<{ view: TimeoutView }> = ({ view }) => {
  const { t } = useTranslation("thread");
  const outcome = timeoutOutcome(view);
  return (
    <ToolFallbackRoot>
      <CollapsibleTrigger
        data-slot="timeout-trigger"
        className="aui-timeout-trigger group/trigger text-muted-foreground hover:text-foreground flex w-full origin-left items-center gap-2 py-1.5 text-[13px] transition-[color,scale] active:scale-[0.98]"
      >
        <TriangleAlertIcon
          data-slot="timeout-trigger-icon"
          className="aui-timeout-trigger-icon size-4 shrink-0"
          aria-hidden="true"
        />
        <span
          data-slot="timeout-trigger-label"
          className="aui-timeout-trigger-label min-w-0 flex-1 truncate text-start leading-none"
        >
          <b className="aui-timeout-trigger-name">{t("timeout.name")}</b>
          {/* THE SEPARATOR IS NOT PART OF THE TITLE, for the reason the injection row
              says the same thing: `data-slot` is this repo's hook, and a decorative
              " · " inside it would make every consumer strip it back off. */}
          <span aria-hidden="true">{" · "}</span>
          <span data-slot="timeout-trigger-title" className="aui-timeout-trigger-title">
            {t("timeout.idle", { ms: view.idleMs })}
          </span>
        </span>
        <span
          data-slot="timeout-trigger-chip"
          className="aui-timeout-trigger-chip shrink-0 text-xs tabular-nums"
        >
          {view.retrying
            ? t("timeout.retry", { attempt: view.attempt, limit: view.limit })
            : t("timeout.stopped")}
        </span>
      </CollapsibleTrigger>
      <ToolFallbackContent>
        <p
          data-slot="timeout-content"
          className="aui-timeout-content text-foreground/90 text-xs leading-relaxed"
        >
          {/* THE THREE ENDINGS, each a LITERAL key: i18next is typed from the English
              catalogs, so a key assembled at run time is a compile error on purpose
              (`lib/llm-timeout.ts` answers a discriminant for exactly this reason). */}
          {outcome === "partial"
            ? t("timeout.reason.partial", { ms: view.idleMs })
            : outcome === "retrying"
              ? t("timeout.reason.retrying", {
                  ms: view.idleMs,
                  attempt: view.attempt,
                  limit: view.limit,
                })
              : t("timeout.reason.exhausted", {
                  ms: view.idleMs,
                  attempt: view.attempt,
                  limit: view.limit,
                })}
        </p>
      </ToolFallbackContent>
    </ToolFallbackRoot>
  );
};

/// THE PART'S RENDERER: `data` is the frame's value, and `timeoutView` is what reads it. A
/// value this module cannot read draws nothing rather than a card inventing a reading.
const TimeoutCardPart: DataMessagePartComponent<TimeoutValue> = ({ data }) => {
  const view = timeoutView(data);
  if (view === null) return null;
  return <TimeoutCard view={view} />;
};

/// REGISTERED BY BEING MOUNTED, not by a table somewhere: `makeAssistantDataUI` answers a
/// component whose rendering IS the registration, which is why `app.tsx` draws
/// `<TimeoutCards />` beside `<ContextCards />` inside the runtime provider and nothing else
/// has to know this card exists.
export const TimeoutCards = makeAssistantDataUI({
  name: TIMEOUT_PART,
  render: TimeoutCardPart,
});
