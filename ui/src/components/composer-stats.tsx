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
import { type FC } from "react";
import { DatabaseIcon, TimerIcon } from "lucide-react";
import { useTranslation } from "react-i18next";

import { useComposerNumbers } from "@/components/composer-numbers";
import { statsCells } from "@/lib/format";

/// One `·` between two cells of a group. Its own component so the gap on either
/// side is stated once: the separator belongs to the row, not to the cell that
/// happens to follow it.
const Sep: FC = () => <span aria-hidden="true">·</span>;

export const ComposerStats: FC = () => {
  /// The strip's five cells are phrases, so their words come from the `format` face
  /// even though the numbers are the server's: `statsCells` is handed a translator
  /// rather than reaching for one, which is what keeps it a pure function a suite can
  /// call (see `lib/format.ts`).
  const { t } = useTranslation("format");
  /// The numbers `ComposerFrame` fetched, not this component's own.
  const { payload } = useComposerNumbers();

  const cells = statsCells(payload, t);
  if (cells === null) return null;

  return (
    <div
      data-slot="composer-stats"
      // `tabular-nums` because the numbers change: without it a digit growing from
      // 9 to 10 shifts the whole row, and a strip that twitches is worse than one
      // that is simply there.
      className="text-muted-foreground flex items-center justify-between gap-4 px-1.5 pt-0.5 pb-1 text-xs tabular-nums"
    >
      <span className="flex items-center gap-1.5">
        <TimerIcon className="size-3.5 shrink-0" />
        <span data-slot="stats-turns">{cells.turns}</span>
        {cells.steps !== null && (
          <>
            <Sep />
            <span data-slot="stats-steps">{cells.steps}</span>
          </>
        )}
        {cells.rate !== null && (
          <>
            <Sep />
            <span data-slot="stats-rate">{cells.rate}</span>
          </>
        )}
      </span>

      {(cells.total !== null || cells.cached !== null) && (
        <span className="flex items-center gap-1.5">
          <DatabaseIcon className="size-3.5 shrink-0" />
          {cells.total !== null && <span data-slot="stats-usage">{cells.total}</span>}
          {cells.total !== null && cells.cached !== null && <Sep />}
          {cells.cached !== null && <span data-slot="stats-cached">{cells.cached}</span>}
        </span>
      )}
    </div>
  );
};
