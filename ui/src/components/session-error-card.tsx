// THE BLOCK ABOVE THE COMPOSER that says this session failed, folded to the error's
// own sentence and one click away from the whole thing as JSON.
//
// WHY IT IS HERE AND NOT ON THE SIDEBAR ROW ANY MORE (owner, 2026-10-01). A session
// that failed used to be DROPPED -- the page removed its host and put the sentence
// under its row in the left column -- so the conversation you were reading vanished and
// the reason for it appeared somewhere you were no longer looking. The failure is a
// fact about the conversation in front of you, so it is drawn in front of you.
//
// A COMPONENT RATHER THAN JSX INSIDE `composer-chrome.tsx`, for the reason
// `record-notice.tsx` and `composer-todos.tsx` give: the composer lives inside the
// copied element, which reaches the assistant runtime and touches `document`, so
// nothing there can be rendered in the test run -- and a sentence nothing can render
// is a sentence that can go missing while every gate stays green. This file has no
// runtime in it, so `test/suites/session-error.tsx` renders it and reads back what it
// says, in both languages.
//
// FOLDED IS THE DEFAULT (the same transient-layout rule the todo strip follows): the
// DETAIL is what is folded away, and the sentence somebody has to read is what is left
// on the line.
import { type FC, useState } from "react";

import { ChevronUpIcon, TriangleAlertIcon } from "lucide-react";
import { useTranslation } from "react-i18next";

import {
  Collapsible,
  CollapsibleContent,
  CollapsibleTrigger,
} from "@/components/ui/collapsible";
import type { SessionFailure } from "@/lib/session-error";

/// THE DETAIL ON ITS OWN, split from the card for the reason `TodoRows` is split from the
/// todo strip: the test run has no DOM, so the fold cannot be opened there and the only
/// thing that can be rendered to a string is the detail itself. It is also the honest shape
/// -- this is what the fold reveals, and nothing else in the card reaches it.
export const SessionErrorDetail: FC<{ failure: SessionFailure }> = ({ failure }) => (
  <pre
    data-slot="session-error-json"
    className="font-mono text-[11px] leading-relaxed whitespace-pre-wrap break-words"
  >
    {failure.detail}
  </pre>
);
export const SessionErrorCard: FC<{
  /// WHAT WENT WRONG, in the two pieces `lib/session-error.ts` builds: the error's own
  /// sentence (drawn on the folded line) and the error as JSON (drawn on the detail).
  failure: SessionFailure;
}> = ({ failure }) => {
  const { t } = useTranslation("composer");
  const [open, setOpen] = useState(false);

  return (
    <Collapsible
      data-slot="session-error"
      open={open}
      onOpenChange={setOpen}
      // A BLOCK OF ITS OWN, and that is what keeps it off the composer's box: the
      // composer's shell is the bordered element BELOW this one (`.aui_composer-shell`),
      // and this carries its own border and background so the two read as a card and the
      // thing it is about rather than one shape. `mb-1.5` is the BLANK GAP between them --
      // the same one the todo strip below carries for the same reason, and the same 6px the
      // frame pads itself with (owner, 2026-10-01).
      className="border-destructive/40 bg-destructive/10 text-destructive mb-1.5 flex flex-col rounded-(--composer-radius) border"
    >
      <CollapsibleTrigger
        data-slot="session-error-toggle"
        className="focus-visible:ring-ring hover:bg-destructive/10 flex w-full items-start gap-2 rounded-(--composer-radius) px-2 py-1.5 text-start text-xs transition-colors focus-visible:ring-2 focus-visible:outline-none"
      >
        <TriangleAlertIcon
          data-slot="session-error-icon"
          aria-hidden
          className="mt-0.5 size-4 shrink-0"
        />
        {/* THE MESSAGE IS WHAT A PERSON SEES WITHOUT OPENING ANYTHING, and it is drawn
            as the error wrote it -- wrapping rather than truncating, because a refusal
            cut in half is a refusal nobody can act on. */}
        <span
          data-slot="session-error-message"
          className="min-w-0 flex-1 whitespace-pre-wrap break-words"
        >
          {failure.message}
        </span>
        <span
          data-slot="session-error-details"
          className="mt-0.5 shrink-0 font-medium underline-offset-2 hover:underline"
        >
          {t("error.details")}
        </span>
        {/* THE ARROW TURNS OVER RATHER THAN BEING SWAPPED, exactly as the todo strip's
            does: the point is that the control is the same one. */}
        <ChevronUpIcon
          data-slot="session-error-chevron"
          aria-hidden
          className={
            open
              ? "mt-0.5 size-4 shrink-0 rotate-180 transition-transform"
              : "mt-0.5 size-4 shrink-0 transition-transform"
          }
        />
      </CollapsibleTrigger>
      {/* THE DETAIL SCROLLS ITSELF: a stack trace is taller than the screen, and one
          that pushed the composer down would be the card taking over the page. */}
      <CollapsibleContent
        data-slot="session-error-detail"
        className="max-h-60 overflow-y-auto px-2 pb-1.5"
      >
        <SessionErrorDetail failure={failure} />
      </CollapsibleContent>
    </Collapsible>
  );
};
