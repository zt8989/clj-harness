// THE BACKGROUND-JOB ROWS of the task pane (tickets 02 and 04 of `.scratch/right-pane-tasks`).
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
//
// ============================================================== the stop control (ticket 04)
//
// A ROW WHOSE JOB IS STILL RUNNING ENDS IN A ■, and it is drawn ONLY there: a job that is
// over has nothing to stop, and a control whose press changed nothing would be a control
// that lies about what it does. Pressing it asks the SERVER (`lib/jobs.stopJob`) -- the
// process that started the command is the only one that can stop it -- and what the server
// does is the same stop `job_kill` performs through a SECOND INITIATOR: the model is not
// there when a person presses, so the ending is not claimed as told and the next model
// call is handed a block saying a person stopped it (`harness.cap.jobs/stop!`).
//
// THE IN-FLIGHT STATE IS `components/session-run-stop.tsx`'s, for the reason that file
// gives: between the press and the answer the row has to say "I heard you", or a slow
// stop reads as a press that did nothing. Where the composer's stop has the window's feed
// to put its state back, this one has the pane's own TICK: the server has already written
// `[stopped]` into the record when it answers, so the next read -- within
// `TASK_PANE_POLL_MS` (`hooks/use-task-pane.ts`) -- replaces the row, and the effect below
// drops the press the moment the row stops being a running one. NO SECOND READ IS STARTED
// FOR THE SAKE OF ONE PRESS: the pane's read is the pane's, and a refresh of our own would
// be a second clock beside the one ticket 02 built (the same rule that made the poll one
// thing for both sections).
//
// A REFUSAL IS SAID, NEVER SWALLOWED (the composer's stop makes the same promise): the
// likely one is a job whose process is already gone -- the server answers its own
// `unknown job: …` -- and a button that stayed pressed over it would be the lie this row
// exists to avoid. The words come from the `errors` face, because the sentence is the
// server's; there is no second wording for one refusal here.
import { type FC, useEffect, useState } from "react";
import { useTranslation } from "react-i18next";
import { SquareIcon } from "lucide-react";

import { Button } from "@/components/ui/button";
import { formatMillis } from "@/lib/format";
import { isRunning, oneLine, stopJob, type JobRow } from "@/lib/jobs";

/// ONE ROW, drawn as a component because A PRESS BELONGS TO A ROW: `j1` pressed is not
/// `j2` pressed, and a `pressing` bit on the list would say the whole section is stopping.
const JobRowItem: FC<{ job: JobRow; threadId: string; now: number }> = ({ job, threadId, now }) => {
  const { t } = useTranslation();
  const { t: tFormat } = useTranslation("format");
  // The failure's words are the `errors` face's, the same translator the composer's stop
  // hands its own call (`components/session-run-stop.tsx`).
  const { t: tErrors } = useTranslation("errors");
  const [pressing, setPressing] = useState(false);
  const [failure, setFailure] = useState<string | null>(null);
  const running = isRunning(job);

  // THE PRESS ENDS WHEN THE ROW DOES. The tick is what carries `[stopped]` back (the server
  // wrote it before it answered), so the moment this row stops being a running one is the
  // moment there is nothing left to press -- and that is the whole of what this component
  // needs the tick to tell it. A refusal is cleared with it: the sentence belongs to the
  // press that failed, not to the row's next state.
  useEffect(() => {
    if (!running) {
      setPressing(false);
      setFailure(null);
    }
  }, [running]);

  const press = (): void => {
    if (pressing) return;
    setPressing(true);
    setFailure(null);
    void stopJob(threadId, job.id, tErrors).catch((error: unknown) => {
      // AN ANSWER THAT DID NOT STOP IT IS NOT A FAILURE (`stopped? false` is what a job that
      // ended on its own answers), so only a refusal or a broken connection lands here --
      // and either way the press has to end and the row has to say why.
      setFailure(error instanceof Error ? error.message : String(error));
      setPressing(false);
    });
  };

  return (
    // THE PATH RIDES ON THE ROW'S `title`: this pane is a reader, so a reader can find the
    // record a row stands for without the row having to spend a line on it.
    <li data-slot="task-pane-job" title={job.path} className="flex items-start gap-2">
      <div className="flex min-w-0 flex-1 flex-col gap-0.5">
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
          {running && (
            <span data-slot="task-pane-job-duration" className="shrink-0 tabular-nums">
              {t("rightPane.jobElapsed", {
                time: formatMillis(Math.max(0, now - job.startedAt), tFormat),
              })}
            </span>
          )}
        </div>
        {failure !== null && (
          <p role="status" data-slot="task-pane-job-stop-refusal" className="text-destructive text-xs">
            {failure}
          </p>
        )}
      </div>
      {/* THE CONTROL EXISTS ONLY ON A RUNNING ROW -- see the header. It is `ghost` because
          it is an errand on a row rather than the row's own action, and it is disabled
          while the answer is on its way, which is the whole of its in-flight state. */}
      {running && (
        <Button
          type="button"
          variant="ghost"
          size="icon-xs"
          className="mt-0.5 shrink-0"
          aria-label={t("rightPane.jobStop")}
          title={t("rightPane.jobStop")}
          data-slot="task-pane-job-stop"
          disabled={pressing}
          onClick={press}
        >
          <SquareIcon className="size-3 fill-current" />
        </Button>
      )}
    </li>
  );
};

/// Draw JOBS as the rows of the pane's bottom section. Empty is the caller's business
/// (`components/task-pane.tsx` draws the empty sentence), so this component is only ever
/// asked for rows.
export const JobRows: FC<{ jobs: readonly JobRow[]; threadId: string; now?: number }> = ({
  jobs,
  threadId,
  now = Date.now(),
}) => (
  <ul data-slot="task-pane-jobs-list" className="mt-1 min-h-0 flex-1 space-y-1 overflow-y-auto">
    {jobs.map((job) => (
      <JobRowItem key={job.id} job={job} threadId={threadId} now={now} />
    ))}
  </ul>
);
