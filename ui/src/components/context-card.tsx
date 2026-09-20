"use client";

// The card an injected context draws in the conversation column.
//
// THE OTHER COLUMN IS NOT THE ONLY PLACE ANY MORE. Injections -- the instruction
// blocks, skill bodies, and the ending of a background job -- used to be visible only
// in the trajectory view, because the client never held them and they produced no
// AG-UI frame. The server now emits each one as a `CUSTOM` frame, the adapter turns it
// into a `data` part, and this file is the renderer that part was always waiting for:
// `thread.aui.tsx`'s part switch has had `case "data": return part.dataRendererUI;` in
// it all along, so nothing in that copied file is edited for this.
//
// ONE ROW, COLLAPSED, LIKE A TOOL CALL. It is drawn with the same shell the tool card
// uses (`ToolFallbackRoot` / `ToolFallbackContent`, the disclosure the copied kit
// provides) and the same 13px row, because "what came back from a call" and "what the
// model was handed" are two answers to the same question and should not look like two
// different apps. Collapsed by default for the same reason a tool call is: a
// conversation with twenty injections in it is unreadable if every one of them is open.
//
// THE CARD IS A VIEW, NOT A MESSAGE. Upstream's outgoing conversion sends text,
// reasoning and tool calls; a `data` part is not among them, so nothing here reaches
// the model -- the client shows what the server injected without ever holding it.
import type { DataMessagePartComponent } from "@assistant-ui/react";
import { makeAssistantDataUI } from "@assistant-ui/react";
import { ChevronsUpDownIcon } from "lucide-react";
import { useTranslation } from "react-i18next";

import { CollapsibleTrigger } from "@/components/ui/collapsible";
import {
  ToolFallbackContent,
  ToolFallbackRoot,
} from "@/components/assistant-ui/elements/tool-fallback.aui";
import { formatBytes } from "@/lib/format";
import { INJECTION_PART, injectionView, type InjectionValue } from "@/lib/injections";

/// The card itself: one row that opens onto the bytes the model was handed.
const InjectionCard: DataMessagePartComponent<InjectionValue> = ({ data }) => {
  const { t } = useTranslation("thread");
  const view = injectionView(data);
  // A frame with nothing to say draws nothing: an empty card would claim an injection
  // nobody can check. See `injectionView`'s own note.
  if (view === null) return null;
  return (
    <ToolFallbackRoot>
      <CollapsibleTrigger
        data-slot="injection-trigger"
        className="aui-injection-trigger group/trigger text-muted-foreground hover:text-foreground flex w-full origin-left items-center gap-2 py-1.5 text-[13px] transition-[color,scale] active:scale-[0.98]"
      >
        <ChevronsUpDownIcon
          data-slot="injection-trigger-icon"
          className="aui-injection-trigger-icon size-4 shrink-0"
          aria-hidden="true"
        />
        <span
          data-slot="injection-trigger-label"
          className="aui-injection-trigger-label min-w-0 flex-1 truncate text-start leading-none"
        >
          <b className="aui-injection-trigger-name">{t("injection.name")}</b>
          {/* THE SEPARATOR IS NOT PART OF THE TITLE. It sits outside the slot because the
              slot is read (`data-slot` is this repo's hook) and a decorative " · " inside it
              would make every consumer strip it back off. */}
          <span aria-hidden="true">{" · "}</span>
          <span data-slot="injection-trigger-title" className="aui-injection-trigger-title">
            {view.title}
          </span>
        </span>
        <span
          data-slot="injection-trigger-size"
          className="aui-injection-trigger-size shrink-0 text-xs tabular-nums"
        >
          {formatBytes(view.bytes)}
        </span>
      </CollapsibleTrigger>
      <ToolFallbackContent>
        <pre
          data-slot="injection-content"
          className="aui-injection-content text-foreground/90 max-h-96 overflow-auto text-xs leading-relaxed break-words whitespace-pre-wrap"
        >
          {data.text}
        </pre>
      </ToolFallbackContent>
    </ToolFallbackRoot>
  );
};

/// REGISTERED BY BEING MOUNTED, not by a table somewhere: `makeAssistantDataUI`
/// answers a component whose rendering is the registration (see its own d.ts), which
/// is why `app.tsx` draws `<ContextCards />` inside the runtime provider and nothing
/// else has to know this card exists.
export const ContextCards = makeAssistantDataUI({
  name: INJECTION_PART,
  render: InjectionCard,
});
