// THE BACKGROUND-JOB ROWS of the task pane (ticket 02 of `.scratch/right-pane-tasks`).
//
// A ROW IS TWO LINES, because they answer two different questions and one of them is a
// command:
//
//   * the FIRST line is WHICH JOB: its id (`j1`, monospace, so ids line up) and the
//     command flattened to ONE line. The command is flattened rather than wrapped
//     (`lib/jobs.oneLine`) and then clipped by CSS -- a long command must not be what
//     decides how wide the column is, and the whole of it stays in the row's tooltip.
//   * the SECOND line is HOW IT IS GOING: the status, which IS the record's own last
//     line (`[exit 0]` / `[stopped]`) or the server's `[running]` while it runs, and --
//     ONLY for a job that is still going -- how long it has been going.
//
// A FINISHED JOB HAS NO CLOCK, and that is not an omission: "how long has it been going"
// is a question only a running job is the answer to, and a duration frozen at the moment
// the pane last looked would be a number that quietly stops being true. The ending is the
// whole answer to "how long did it take" -- it happened.
//
// THE CLOCK IS THE SERVER'S `startedAt` (`start!` writes it), not a time this side started
// counting: a pane opened onto a job that has already been running for a minute must show
// that minute. `now` is a PARAMETER of the component for the same reason
// `lib/relative-time.ts` takes one -- a duration is a function of two instants, and a
// suite that had to wait for a clock would be a suite about time.
import { useTranslation } from "react-i18next";
import type { FC } from "react";

import { formatMillis } from "@/lib/format";
import { isRunning, oneLine, type JobRow } from "@/lib/jobs";

/// Draw JOBS as the rows of the pane's bottom section. Empty is the caller's business
/// (`components/task-pane.tsx` draws the empty sentence), so this component is only ever
/// asked for rows.
export const JobRows: FC<{ jobs: readonly JobRow[]; now?: number }> = ({ jobs, now = Date.now() }) => {
  const { t } = useTranslation();
  // The duration's words come from the `format` face, because `formatMillis` is the one
  // formatter the tool card, the trajectory and this row share (`lib/format.ts`).
  const { t: tFormat } = useTranslation("format");

  return (
    <ul data-slot="task-pane-jobs-list" className="mt-1 min-h-0 flex-1 space-y-1 overflow-y-auto">
      {jobs.map((job) => (
        // THE PATH RIDES ON THE ROW'S `title`: this pane is a reader, so a reader can
        // find the record a row stands for without the row having to spend a line on it.
        <li key={job.id} data-slot="task-pane-job" title={job.path} className="flex flex-col gap-0.5">
          <div className="flex min-w-0 items-baseline gap-2">
            <span data-slot="task-pane-job-id" className="shrink-0 font-mono text-xs font-medium">
              {job.id}
            </span>
            <span
              data-slot="task-pane-job-command"
              title={job.command}
              className="min-w-0 flex-1 truncate font-mono text-xs"
            >
              {oneLine(job.command)}
            </span>
          </div>
          <div className="text-muted-foreground flex min-w-0 items-baseline gap-2 text-xs">
            <span data-slot="task-pane-job-status" className="shrink-0 font-mono">
              {job.status}
            </span>
            {isRunning(job) && (
              <span data-slot="task-pane-job-duration" className="shrink-0 tabular-nums">
                {t("rightPane.jobElapsed", {
                  time: formatMillis(Math.max(0, now - job.startedAt), tFormat),
                })}
              </span>
            )}
          </div>
        </li>
      ))}
    </ul>
  );
};
