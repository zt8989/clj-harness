// THE SUBAGENT ROWS of the task pane (ticket 03 of `.scratch/right-pane-tasks`).
//
// A ROW IS A DOOR AND TWO LINES. The door is the whole row: clicking it opens THAT
// delegation's mirror -- the same state the transcript's `agent` card writes -- through a
// callback `app.tsx` wires down, and it opens the row's OWN `threadId` (`mirrorOf` in
// `lib/subagents-runs.ts`), never one guessed from where the row happens to sit.
//
//   * the FIRST line is WHO: the subagent's name, and its description clipped to the one
//     line it is (`truncate`, the whole of it in the row's `title`). A name no definition
//     carries any more draws the name and no description -- an ordinary row, not an error.
//   * the SECOND line is HOW IT IS GOING: `运行中` / `已结束`, the server's ONE boolean said
//     in the two words the catalogs have (there is no third state to invent), and when it
//     was delegated (`:delegatedAt`) when the server has one.
//
// AND IT IS A TIME, NOT A DURATION. A finished delegation's "3 min ago" is still true
// tomorrow, while "3 min so far" would be a number that quietly stopped being true the
// moment it ended -- which is exactly why ticket 02's job row keeps its clock to a running
// job only, and this line does not keep one at all.
//
// `now` IS A PARAMETER for the reason that row gives: a relative age is a function of two
// instants, and a suite that had to wait for a clock would be a suite about time.
import { useTranslation } from "react-i18next";
import type { FC } from "react";

import { type SubagentView } from "@/components/subagent-view-context";
import { formatTime } from "@/lib/format";
import { oneLine } from "@/lib/jobs";
import { asLanguage } from "@/lib/language";
import { relativeAge } from "@/lib/relative-time";
import { mirrorOf, type SubagentTaskRow } from "@/lib/subagents-runs";

/// THE SHELL'S OWN WORDS FOR THE RELATIVE BUCKETS -- the same three keys the session rows
/// read (`thread-list.aui.tsx`), so a delegation's clock and a session's clock cannot drift
/// into two vocabularies. The buckets themselves are `lib/relative-time.ts`'s.
const AGO_KEY = {
  minutes: "session.minutesAgo",
  hours: "session.hoursAgo",
  days: "session.daysAgo",
} as const;

/// Draw ROWS as the pane's top section, each one a door. Empty is the caller's business
/// (`components/task-pane.tsx` draws the empty sentence), so this is only ever asked for
/// rows.
export const SubagentRows: FC<{
  rows: readonly SubagentTaskRow[];
  onOpen: (view: SubagentView) => void;
  now?: number;
}> = ({ rows, onOpen, now = Date.now() }) => {
  const { t, i18n } = useTranslation();
  const locale = asLanguage(i18n.language);

  /// WHEN THIS DELEGATION STARTED, as a person reads it: a bucket, not a formatted date
  /// (see `lib/relative-time.ts`), with the exact instant one hover away in the `title`.
  const started = (delegatedAt: number): string => {
    const age = relativeAge(delegatedAt, now);
    if (age.kind === "justNow") return t("session.justNow");
    if (age.kind === "date") {
      return new Date(delegatedAt).toLocaleDateString(locale, { month: "numeric", day: "numeric" });
    }
    return t(AGO_KEY[age.kind], { count: age.count });
  };

  return (
    <ul
      data-slot="task-pane-subagents-list"
      className="mt-1 min-h-0 flex-1 space-y-1 overflow-y-auto"
    >
      {rows.map((row) => (
        <li key={row.threadId} data-slot="task-pane-subagent">
          <button
            type="button"
            data-slot="task-pane-subagent-open"
            // THE DOOR IS THE ROW, and the door it opens is `mirrorOf(row)` -- the row's own
            // `threadId`, spelled once in `lib/subagents-runs.ts`.
            onClick={() => onOpen(mirrorOf(row))}
            className="hover:bg-muted/60 flex w-full min-w-0 flex-col gap-0.5 rounded-md px-1 py-0.5 text-left"
          >
            <div className="flex min-w-0 items-baseline gap-2">
              <span data-slot="task-pane-subagent-name" className="shrink-0 text-xs font-medium">
                {row.name}
              </span>
              {row.description !== null && (
                // THE WHOLE DESCRIPTION IS THE `title`; the line is CSS-clipped, so a long one
                // cannot be what decides the column's width. `oneLine` because a description
                // written over several lines is `lib/jobs.ts`'s one flattening, not a second.
                <span
                  data-slot="task-pane-subagent-description"
                  title={row.description}
                  className="text-muted-foreground min-w-0 flex-1 truncate text-xs"
                >
                  {oneLine(row.description)}
                </span>
              )}
            </div>
            <div className="text-muted-foreground flex min-w-0 items-baseline gap-2 text-xs">
              <span data-slot="task-pane-subagent-status" className="shrink-0">
                {row.running ? t("rightPane.subagentRunning") : t("rightPane.subagentSettled")}
              </span>
              {row.delegatedAt !== null && (
                <span
                  data-slot="task-pane-subagent-started"
                  title={formatTime(row.delegatedAt, locale)}
                  className="shrink-0"
                >
                  {t("rightPane.subagentStarted", { time: started(row.delegatedAt) })}
                </span>
              )}
            </div>
          </button>
        </li>
      ))}
    </ul>
  );
};
