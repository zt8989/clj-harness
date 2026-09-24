// The trajectory, typed thin: what the model actually saw, per turn.
//
// It reads the RECORD, not the conversation. The client holds the conversation, and
// that is exactly why this module exists: the SYSTEM MESSAGE's bytes, the instruction
// files and skill bodies spliced in beside it, and each call's tool table are things
// the client never had and the AG-UI frames never carried. The server folds them out of
// the session's jsonl log (GET /api/threads/<stem>/trajectory) -- the same file
// `lib/stats.ts` reads for its numbers, read for its content instead.
//
// EVERY FIELD IS OPTIONAL WHERE THE RECORD CANNOT ANSWER, and that is the module's
// whole discipline (see harness.edge.trajectory). A record written before the
// model-call lines has no `calls`; a call whose request carried no tool table has no
// `tools` key; a call the vendor reported nothing for has no `usage`; a tool call that
// never ran has no `startedAt`. NONE of those may be rendered as a zero or filled in
// from what the session has today -- the absent field is the answer.
import { API_BASE } from "@/lib/threads";

/// One thing in a turn, in the order the model had it.
export type TrajectoryItem =
  /// The table the run SERVED, off the system row's envelope (`:tools`) -- so the item
  /// is self-contained and the pane never pulls a second record. Absent for a record
  /// written before the table moved to the envelope.
  | { kind: "system"; text: string; initial?: boolean; tools?: readonly unknown[] }
  | { kind: "context"; text: string; call?: number }
  | { kind: "user"; text: string; id?: string; at?: number }
  | { kind: "assistant"; text: string; reasoning?: string; call?: number }
  | {
      kind: "tool";
      toolCallId: string;
      name?: string;
      argsText?: string;
      result: string;
      executed: boolean;
      call?: number;
      outcome?: string;
      error?: string;
      /// FOUR MARKS, and their names are the point: `arrivedAt` is the call reaching the
      /// seam, `resumedAt` the end of a park a human decided (`tools/pre-execute` again),
      /// `executedAt` the call LEAVING execution -- i.e. when the tool FINISHED, which is
      /// not when it started -- and `closedAt` the seam being done with it. The span a
      /// tool occupied is `arrivedAt` -> `executedAt`; reading `executedAt` as a start is
      /// what makes every tool look instantaneous. A vetoed call has no `executedAt`.
      arrivedAt?: number;
      resumedAt?: number;
      executedAt?: number;
      closedAt?: number;
    };

/// One model call: who it went to, what it carried, what came back.
export type TrajectoryCall = {
  index: number;
  model?: string;
  /// THE TOOL TABLE'S SIGNATURE, not the table (ticket 04): the NAME set as a hash and
  /// how many tools it held. Absent -- not empty -- when the request carried no table.
  toolsNamesHash?: string;
  toolsCount?: number;
  startedAt?: number;
  endedAt?: number;
  /// The vendor's own usage map, its own key names intact, and the total derived from
  /// it (`tokens`) -- absent when the call reported nothing.
  usage?: Record<string, unknown>;
  tokens?: number;
  finishReason?: string;
};

export type TrajectoryTurn = {
  index: number;
  items: readonly TrajectoryItem[];
  /// Absent -- not empty -- when the record predates the model-call lines.
  calls?: readonly TrajectoryCall[];
};

export type TrajectoryPayload = {
  threadId: string;
  /// True while the last run has not finished: the view says so rather than pretending
  /// the turn is over.
  incomplete: boolean;
  turns: readonly TrajectoryTurn[];
};

/// This session's trajectory.
///
/// A 404 IS AN ORDINARY ANSWER, for the reason `statsFor` gives: a session that has
/// never run has no log to fold, and "nothing to show yet" is what the view draws by
/// drawing nothing. Any other failure is null too -- the view has nothing useful to say
/// about a broken log, and a red panel is not the place to try.
export async function trajectoryFor(threadId: string): Promise<TrajectoryPayload | null> {
  const res = await fetch(`${API_BASE}threads/${encodeURIComponent(threadId)}/trajectory`);
  if (!res.ok) return null;
  return (await res.json()) as TrajectoryPayload;
}

// A NOTE ON THE TOOL MARKS, because their names are not the record's names and someone
// reading the log will look for the other spelling:
//
//   the log says    `tools/pre-execute`  -> `arrivedAt`      the call reached the seam
//                   `tools/pre-execute`  -> `resumedAt`      again, after a human decided
//                   `tools/execute`      -> `executedAt`     it LEFT execution = finished
//                   `tools/post-execute` -> `closedAt`       the seam is done with it
//
// `execute` really does mean "left execution" (see harness.kernel.event/tool-executed):
// reading it as a start is what draws every tool as instantaneous and pushes the real
// running time into the "waiting for a human" span. The span a tool occupied is
// `arrivedAt` -> `executedAt`.
