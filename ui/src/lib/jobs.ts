// WHAT THIS SESSION HAS RUNNING IN THE BACKGROUND, as the task pane draws it.
//
// THE ROUTE IS `GET /api/threads/<stem>/jobs`, and its answer is the server PROCESS's
// own registry read as a list: `{threadId, jobs: [{id, command, status, startedAt,
// path}]}` (see harness.edge.http/jobs-get). IT IS NEITHER A LOG READ NOR PERSISTED: a
// restart answers `[]`, which is an ordinary answer rather than a refusal, so `[]`
// here means "nothing is running" and is never an error.
//
// THE STATUS IS THE RECORD'S OWN LAST LINE (`[exit 0]` / `[stopped]` / `[exit ?]`), or
// the server's `[running]` while no such line has been written. THIS MODULE DOES NOT
// TRANSLATE IT AND MUST NOT: the vocabulary is the record's (the same words a
// `job_output` receipt leads with), and a second wording here -- "running", "done",
// "failed" -- would be a second enum over one fact, which is exactly what the spec
// forbids (`.scratch/right-pane-tasks`, decisions 5 and 7).
//
// ONE READER, AND THE SAME NULL DISCIPLINE AS `lib/stats.ts`: a read that could not be
// made answers null so its caller can tell "nothing is running" from "we could not ask"
// -- the pane keeps what it had rather than blinking to empty on one dropped request.
import { API_BASE } from "@/lib/threads";

/// One background job, as the route states it.
export type JobRow = {
  id: string;
  /// The command line that was run. May hold newlines; the row flattens it (`oneLine`).
  command: string;
  /// `[running]` while it is not over, and the record's OWN ending line once it is.
  status: string;
  /// When the server started it, in epoch milliseconds.
  startedAt: number;
  /// Where its record is. This pane is a READER, which is why the path is allowed on
  /// the row where `job`/`job_kill` receipts name no path (`.scratch/job-receipt-no-path`).
  path: string;
};

/// The one status a job has while it is not over. THE SERVER WRITES THIS WORD and this
/// is the only place that compares against it: anything else on a row is an ending the
/// record wrote, and it is not this module's to spell.
export const RUNNING_STATUS = "[running]";

/// Whether JOB is still going -- asked of the status line, never of a separate flag.
export function isRunning(job: JobRow): boolean {
  return job.status === RUNNING_STATUS;
}

/// A command as ONE line: every run of whitespace -- the newlines a multi-line command
/// is written with -- becomes a single space. A row must not be broken into three by a
/// command that was written over three, and CSS cannot mop up a newline the way it can
/// clip a long one.
///
/// BEING LONG IS LEFT TO CSS (`truncate`), deliberately: the same string is what the
/// row's tooltip shows, and a string clipped here would be clipped in the tooltip too.
export function oneLine(command: string): string {
  return command.replace(/\s+/g, " ").trim();
}

/// This session's jobs, or null when the question could not be asked.
///
/// A NON-OK ANSWER IS NULL AND NOT `[]`: 500, or a dropped connection, is "we do not
/// know", while `[]` is "there is nothing", and drawing one as the other would either
/// invent an empty pane or keep rows alive that are gone.
export async function jobsFor(threadId: string, signal?: AbortSignal): Promise<JobRow[] | null> {
  try {
    const res = await fetch(`${API_BASE}threads/${encodeURIComponent(threadId)}/jobs`, { signal });
    if (!res.ok) return null;
    const answer = (await res.json()) as { jobs?: readonly JobRow[] };
    return [...(answer.jobs ?? [])];
  } catch {
    // AN ABORT IS NOT A FAILURE -- it is this reader's own pane going away. Either way
    // the caller gets null, and null means "keep what you had".
    return null;
  }
}
