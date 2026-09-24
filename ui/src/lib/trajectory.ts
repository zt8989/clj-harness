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

/// This session's trajectory, STREAMED. The route answers NDJSON (ticket 06 of
/// `.scratch/events-mux-and-host`): the first line is the header, and every line after
/// it is one turn written as the fold finishes it. ONPROGRESS is handed the payload so
/// far -- the header first, then once per turn -- so a view can draw a long record while
/// the rest of it is still folding; the promise resolves with the whole payload when the
/// stream ends.
///
/// A 404 IS AN ORDINARY ANSWER, for the reason `statsFor` gives: a session that has
/// never run has no log to fold, and "nothing to show yet" is what the view draws by
/// drawing nothing. Any other failure is null too -- the view has nothing useful to say
/// about a broken log, and a red panel is not the place to try.
export async function trajectoryFor(
  threadId: string,
  onProgress?: (payload: TrajectoryPayload) => void,
): Promise<TrajectoryPayload | null> {
  const res = await fetch(`${API_BASE}threads/${encodeURIComponent(threadId)}/trajectory`);
  if (!res.ok || res.body === null) return null;

  const reader = res.body.getReader();
  const decoder = new TextDecoder();
  const turns: TrajectoryTurn[] = [];
  let header: { threadId: string; incomplete: boolean } | null = null;

  /// A HEADER THAT HAS NOT LANDED YET IS NOTHING TO PUBLISH: the payload has a threadId
  /// and a turns array, and inventing them before the first line would be a shape that
  /// is not the server's. The turns that arrive after it are what fills it.
  const publish = (): void => {
    if (header !== null) {
      onProgress?.({ threadId: header.threadId, incomplete: header.incomplete, turns: [...turns] });
    }
  };

  /// ONE LINE, and a line that is not JSON is DROPPED rather than thrown: a stream cut
  /// mid-line is a real thing (a navigation away), and the turns already in hand are the
  /// answer the caller asked for.
  const take = (line: string): void => {
    const text = line.trim();
    if (text === "") return;
    let parsed: unknown;
    try {
      parsed = JSON.parse(text);
    } catch {
      return;
    }
    if (header === null) {
      header = parsed as { threadId: string; incomplete: boolean };
    } else {
      turns.push(parsed as TrajectoryTurn);
    }
    publish();
  };

  let buffer = "";
  for (;;) {
    const { value, done } = await reader.read();
    if (done) break;
    buffer += decoder.decode(value, { stream: true });
    let index = buffer.indexOf("\n");
    while (index >= 0) {
      take(buffer.slice(0, index));
      buffer = buffer.slice(index + 1);
      index = buffer.indexOf("\n");
    }
  }
  take(buffer);
  // A READER FOR THE HEADER, rather than reading `header` directly: `take` writes it from
  // inside a closure, and the compiler narrows the variable to its initial `null` because
  // it cannot see that write. A function with a declared return type carries the real
  // shape to the caller.
  const readHeader = (): { threadId: string; incomplete: boolean } | null => header;
  const landed = readHeader();
  if (landed === null) return null;
  return { threadId: landed.threadId, incomplete: landed.incomplete, turns };
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
