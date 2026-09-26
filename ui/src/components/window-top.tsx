// WHAT A WINDOW SAYS AT ITS TOP: the control that asks for older history, and the
// sentences it owes when the window had to be rebuilt.
//
// A COMPONENT OF ITS OWN for the reason `components/record-notice.tsx` is one: a
// sentence nothing can render is a sentence that can go missing while every gate stays
// green. It renders in the thread's own viewport, whose scroll container is what the
// prepend has to anchor against (`lib/window-scroll.ts`), so this is also where "show
// earlier" is within reach of the messages it pushes down.
//
// THE BUTTON IS A BUTTON, AND ONLY ONE. It is not drawn at all when the server says
// there is nothing in front (`hasMore`), it is DISABLED while a page is in flight -- so
// a double click cannot put two pages in the air and race them -- and it never loads on
// its own: automatic history loading is the unbounded push ADR 0003 decision 4 refuses,
// pointed the other way.
import type { FC } from "react";
import { useTranslation } from "react-i18next";

import { windowNotice, type WindowNotice } from "@/lib/window";

export type WindowTopProps = {
  /// Whether the server has more in front of the oldest entry this page holds.
  hasMore: boolean;
  /// Whether a page is in flight. One slot: the button is the only way to ask.
  loading: boolean;
  onEarlier: () => void;
  /// What happened to the window, or null when nothing did.
  notice: WindowNotice | null;
};

export const WindowTop: FC<WindowTopProps> = ({ hasMore, loading, onEarlier, notice }) => {
  const { t } = useTranslation();
  const sentence = windowNotice(t, notice);
  return (
    <>
      {sentence !== null && (
        <p
          data-slot="window-notice"
          role="status"
          className="shrink-0 rounded-md border border-border bg-muted px-3 py-1.5 text-xs text-muted-foreground"
        >
          {sentence}
        </p>
      )}
      {hasMore && (
        <div data-slot="window-earlier" className="flex justify-center pb-2">
          <button
            type="button"
            data-slot="window-earlier-button"
            disabled={loading}
            onClick={onEarlier}
            className="rounded-md border border-border px-3 py-1 text-xs text-muted-foreground hover:text-foreground disabled:opacity-50"
          >
            {loading ? t("window.loadingEarlier") : t("window.earlier")}
          </button>
        </div>
      )}
    </>
  );
};
