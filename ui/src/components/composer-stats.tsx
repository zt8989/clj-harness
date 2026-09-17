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
// copy of the assistant-ui element kept byte-comparable with upstream, and the two
// LOCAL: insertion points in composer-chrome.tsx are the whole reason this file can
// exist without editing it.
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
// ON MOUNT, WHEN THE SESSION CHANGES, AND WHEN A MODEL CALL ENDS. A call's numbers
// only exist once its `model/end` line is written, so that is the natural boundary
// to ask at -- and the client can see it without any new protocol: one ReAct round
// is one assistant message on this side, so a rise in that count is a call that just
// finished. The run's own end is the same trigger one last time (a run that ends on
// an error has no assistant message to count).
//
// THERE IS NO POLLING, deliberately. A long call streams for minutes; the strip
// stands still for the length of it and then moves. That is honest rather than
// laggy: the number it would show mid-call does not exist yet, and inventing one
// would be the same mistake as estimating the tokens.
import { type FC, useEffect, useState } from "react";
import { useAuiState } from "@assistant-ui/react";
import { DatabaseIcon, TimerIcon } from "lucide-react";
import { useTranslation } from "react-i18next";

import { type StatsPayload, statsCells } from "@/lib/format";
import { statsFor } from "@/lib/stats";

/// One `·` between two cells of a group. Its own component so the gap on either
/// side is stated once: the separator belongs to the row, not to the cell that
/// happens to follow it.
const Sep: FC = () => <span aria-hidden="true">·</span>;

export const ComposerStats: FC<{ threadId: string }> = ({ threadId }) => {
  /// The strip's five cells are phrases, so their words come from the `format` face
  /// even though the numbers are the server's: `statsCells` is handed a translator
  /// rather than reaching for one, which is what keeps it a pure function a suite can
  /// call (see `lib/format.ts`).
  const { t } = useTranslation("format");
  /// A COUNT of assistant messages, not the messages: `useAuiState` compares what
  /// the selector returns, and a selector handing back a fresh array would re-render
  /// on every token.
  const assistantCount = useAuiState(
    (s) => s.thread.messages.filter((m) => m.role === "assistant").length,
  );
  const isRunning = useAuiState((s) => s.thread.isRunning);
  const [stats, setStats] = useState<StatsPayload | null>(null);

  useEffect(() => {
    let live = true;
    void statsFor(threadId).then((next) => {
      // A late answer from a previous session must not land on this one.
      if (live) setStats(next);
    });
    return () => {
      live = false;
    };
  }, [threadId, assistantCount, isRunning]);

  const cells = statsCells(stats, t);
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
