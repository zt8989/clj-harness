// The trajectory, typed thin: what the model actually saw, as a flat ledger of cells.
//
// It reads the RECORD, not the conversation. The client holds the conversation, and
// that is exactly why this module exists: the SYSTEM MESSAGE's bytes, the instruction
// files and skill bodies spliced in beside it, and each call's tool table are things
// the client never had and the AG-UI frames never carried. The server folds them out of
// the session's jsonl log (GET /api/threads/<stem>/trajectory) -- the same file
// `lib/stats.ts` reads for its numbers, read for its content instead.
//
// THE LEDGER IS FLAT, and that is the shape dsh draws (`TrajectoryCellKind`): one entry
// per thing the model had, or per row that BOUNDS it, in the record's order. A turn is
// bracketed by `turn-start` / `turn-end`, NOT wrapped around its content -- which is how
// the session's system prompt can stand OUTSIDE every turn (it is what the turn was
// handed, not something the turn said) and how a compaction, which the model never saw
// at all, can sit BETWEEN turns. `:turn` nil means exactly that: no turn.
//
// EVERY FIELD IS OPTIONAL WHERE THE RECORD CANNOT ANSWER, and that is the module's
// whole discipline (see harness.edge.trajectory). A record written before the
// model-call lines has no `calls`; a call whose request carried no tool table has no
// `toolsNamesHash`; a call the vendor reported nothing for has no `usage`; a tool call
// that never ran has no `executedAt`. NONE of those may be rendered as a zero or filled
// in from what the session has today -- the absent field is the answer.
import { apiBase } from "@/lib/threads";

/// One thing the model had, in the order it had it.
export type TrajectoryItem =
  /// The table the run SERVED, off the system row's envelope (`:tools`) -- so the item
  /// is self-contained and the pane never pulls a second record. Absent for a record
  /// written before the table moved to the envelope.
  | { index: number; kind: "system"; turn: number | null; text: string; initial?: boolean; tools?: readonly unknown[] }
  /// A COMPACTION: a fact ABOUT the record rather than something the model saw. It
  /// belongs to no turn (`turn: null`) and the pane shows the summary that came back.
  | { index: number; kind: "compacted"; turn: number | null; text: string; id?: string; at?: number; tokens?: number; messages?: number }
  | { index: number; kind: "context"; turn: number; text: string; call?: number }
  | { index: number; kind: "user"; turn: number; text: string; id?: string; at?: number }
  /// The model's own words. `message` is the record's word for this cell (dsh's too);
  /// the pane still calls it the assistant's.
  | { index: number; kind: "message"; turn: number; text: string; reasoning?: string; call?: number }
  | {
      index: number;
      kind: "tool";
      turn: number;
      toolCallId: string;
      name?: string;
      argsText?: string;
      /// ABSENT WHILE A RUN IN FLIGHT HAS NOT ANSWERED THE CALL YET: the result is a tool
      /// message, and the kernel writes those at `:run/done`, one beat after the terminal
      /// frame (see `harness.edge.trajectory/pending-tool-items`). Absent -- never an empty
      /// string -- so a reader can say 'not yet' rather than draw an answer nobody gave.
      result?: string;
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
  /// THE TOOL TABLE'S SIGNATURE, not the table: the NAME set as a hash and how many
  /// tools it held. Absent -- not empty -- when the request carried no table.
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

/// A boundary rather than content: the two cells that bracket a turn. They carry no chip
/// and no preview -- the view draws them as the seam they are.
export type TrajectoryBoundary =
  /// The turn's head, and where its MODEL CALLS live: a call is a fact about the turn as a
  /// whole ('what went out, what came back'), and an item's `call` is a pointer into this
  /// vector. Absent -- not empty -- when the record predates the model-call lines.
  | { index: number; kind: "turn-start"; turn: number; calls?: readonly TrajectoryCall[] }
  | { index: number; kind: "turn-end"; turn: number };

/// One entry of the ledger.
export type TrajectoryCell = TrajectoryItem | TrajectoryBoundary;

/// A turn as the VIEW wants it back: its number, the cells inside it (the boundaries are
/// its brackets, not its content) and the calls its head carries.
export type TrajectoryTurn = {
  index: number;
  cells: readonly TrajectoryItem[];
  calls?: readonly TrajectoryCall[];
};

/// The ledger cut into what a reader scrolls through: a turn, a run of cells that belong
/// to no turn (`Between turns`), or a system prompt, which stands outside both.
export type TrajectorySection =
  | { kind: "prompt"; cell: TrajectoryItem }
  | { kind: "turn"; turn: TrajectoryTurn }
  | { kind: "between"; cells: readonly TrajectoryItem[] };

/// THE LEDGER, CUT INTO SECTIONS, in one pass -- the reading the list and the strip both
/// want, kept here so the two cannot disagree about which cell is in which turn. A cell
/// between two turns IS 'between turns' (`:turn` nil); a system cell out there is the
/// prompt, which is drawn above every turn rather than under a `Between turns` heading.
export function sectionsOf(cells: readonly TrajectoryCell[]): TrajectorySection[] {
  const sections: TrajectorySection[] = [];
  let open: { index: number; cells: TrajectoryItem[]; calls?: readonly TrajectoryCall[] } | null = null;
  let between: TrajectoryItem[] = [];
  const flushBetween = (): void => {
    if (between.length > 0) sections.push({ kind: "between", cells: between });
    between = [];
  };
  for (const cell of cells) {
    if (cell.kind === "turn-start") {
      flushBetween();
      open = { index: cell.turn, cells: [], calls: cell.calls };
      continue;
    }
    if (cell.kind === "turn-end") {
      if (open !== null) sections.push({ kind: "turn", turn: open });
      open = null;
      continue;
    }
    if (open !== null) {
      open.cells.push(cell);
      continue;
    }
    if (cell.kind === "system") {
      flushBetween();
      sections.push({ kind: "prompt", cell });
      continue;
    }
    between.push(cell);
  }
  /// A TURN STILL OPEN IS STILL A TURN: the last one has no `turn-end` while its run is
  /// writing (the ledger only writes that cell when the turn really is over), and it is still
  /// a section a reader scrolls through -- failing to cut it would drop the whole turn.
  if (open !== null) sections.push({ kind: "turn", turn: open });
  flushBetween();
  return sections;
}

/// THE TURNS of a cut ledger, in order -- what the strip lays its lanes out over and what the
/// list's `Collapse turns` acts on. Both call it rather than each filtering the sections
/// themselves: two filters are two answers, and a mark has to point at a row that is really
/// where it says.
export function turnsOf(sections: readonly TrajectorySection[]): TrajectoryTurn[] {
  return sections.flatMap((section) => (section.kind === "turn" ? [section.turn] : []));
}

/// ONE ROW A READER CAN SEE: the cell, the turn it is in (nil for a cell in none) and that
/// turn's calls, which is where an item's `call` pointer resolves.
export type TrajectoryRow = { item: TrajectoryItem; turn: number | null; calls?: readonly TrajectoryCall[] };

/// THE ROWS A READER IS LOOKING AT, given the turns they have folded -- the list's own
/// reading, kept pure so it can be asserted without a DOM (`test/suites/trajectory.ts`
/// imports no React either).
///
/// A FOLDED TURN CONTRIBUTES NO ROWS AT ALL, and that is the whole point: the injected
/// `context` cells inside it are material for that turn, so they go away with it. A system
/// prompt and a `Between turns` run are in NO turn, so no fold can touch them -- which is
/// the same statement as 'the prompt is not inside turn one'.
export function rowsOf(
  sections: readonly TrajectorySection[],
  collapsed: ReadonlySet<number> = new Set(),
): TrajectoryRow[] {
  return sections.flatMap((section): TrajectoryRow[] => {
    if (section.kind === "prompt") return [{ item: section.cell, turn: null }];
    if (section.kind === "between") return section.cells.map((cell) => ({ item: cell, turn: null }));
    if (collapsed.has(section.turn.index)) return [];
    return section.turn.cells.map((cell) => ({
      item: cell,
      turn: section.turn.index,
      calls: section.turn.calls,
    }));
  });
}

export type TrajectoryPayload = {
  threadId: string;
  /// True while the last run has not finished: the view says so rather than pretending
  /// the turn is over.
  incomplete: boolean;
  cells: readonly TrajectoryCell[];
};

/// This session's trajectory, STREAMED. The route answers NDJSON (ticket 06 of
/// `.scratch/events-mux-and-host`): the first line is the header, and every line after it
/// is ONE BATCH -- `{from, cells}`, the cells that became FINAL since the reader last
/// asked, then the whole OPEN TAIL again. A batch is spliced at its `from`, so a batch
/// that arrives twice leaves one copy of it: that is what makes re-sending the open turn
/// -- whose cells really do change, a call drawn without its result and answered later --
/// cost nothing. ONPROGRESS is handed the payload so far, once per batch, so a view can
/// draw a long record while the rest of it is still folding; the promise resolves with
/// the whole payload when the stream ends.
///
/// A 404 IS AN ORDINARY ANSWER, for the reason `statsFor` gives: a session that has
/// never run has no log to fold, and "nothing to show yet" is what the view draws by
/// drawing nothing. Any other failure is null too -- the view has nothing useful to say
/// about a broken log, and a red panel is not the place to try.
export async function trajectoryFor(
  threadId: string,
  onProgress?: (payload: TrajectoryPayload) => void,
  signal?: AbortSignal,
): Promise<TrajectoryPayload | null> {
  /// THE STREAM IS LONG-LIVED (ticket 13 of `.scratch/session-as-kernel`): the route keeps
  /// the connection open for a held session and PUSHES later cells, so this promise settles
  /// only when the stream ends -- and the caller aborts it when the view goes away.
  const res = await fetch(`${apiBase()}threads/${encodeURIComponent(threadId)}/trajectory`, { signal });
  if (!res.ok || res.body === null) return null;

  const reader = res.body.getReader();
  const decoder = new TextDecoder();
  const cells: TrajectoryCell[] = [];
  let header: { threadId: string; incomplete: boolean } | null = null;

  /// A HEADER THAT HAS NOT LANDED YET IS NOTHING TO PUBLISH: the payload has a threadId
  /// and a cells array, and inventing them before the first line would be a shape that
  /// is not the server's. The cells that arrive after it are what fills it.
  const publish = (): void => {
    if (header !== null) {
      onProgress?.({ threadId: header.threadId, incomplete: header.incomplete, cells: [...cells] });
    }
  };

  /// ONE LINE, and a line that is not JSON is DROPPED rather than thrown: a stream cut
  /// mid-line is a real thing (a navigation away), and the cells already in hand are the
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
      /// SPLICED AT ITS `from`: everything from there on is what the server is saying NOW,
      /// so the open tail is replaced rather than appended to and the same tail twice is
      /// still one copy of it.
      const batch = parsed as { from: number; cells: readonly TrajectoryCell[] };
      cells.length = batch.from;
      cells.push(...batch.cells);
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
  return { threadId: landed.threadId, incomplete: landed.incomplete, cells };
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
