// WHAT THE RECORD SAYS ABOUT TURNS, per conversation: the rows the server folded from its
// `turn/start` / `turn/end` lines (ADR 0017), and which message each one holds.
//
// WHY A STORE OF ITS OWN. The window carries the turns (`WindowFrame.turns`) and the runtime
// carries the messages; neither holds the other. The one question the UI asks is "which turn is
// this message in, and how much did it do" -- and the answer is a RECORD LINE NUMBER compared
// against a turn's own range (`from` .. `to`), never a walk down the message list. That comparison
// needs the offset every entry arrived in, which only the window has (`WindowEntry.seq`), so the two
// are kept here together, keyed by message id.
//
// IT IS NOT A SECOND READER OF THE RECORD. Nothing here counts a step or decides where a turn
// begins: both are written down, and this only remembers them.
import type { TurnRow, WindowEntry } from "./feed";

type Held = {
  readonly turns: readonly TurnRow[];
  /// message id -> the record line that message's entry arrived in
  readonly seqOf: ReadonlyMap<string, number>;
};

/// THE ANSWER FOR A CONVERSATION WITH NO TURNS YET -- one frozen value, because `turnRows` is read
/// through `useSyncExternalStore`: a fresh `[]` on every call would be a NEW SNAPSHOT every render,
/// and React would spin forever re-rendering a window that never changed (measured: error #185).
const NONE: readonly TurnRow[] = [];

const held = new Map<string, Held>();
const listeners = new Set<() => void>();

const sameRow = (a: TurnRow, b: TurnRow): boolean =>
  a.turnId === b.turnId &&
  a.from === b.from &&
  a.to === b.to &&
  a.steps === b.steps &&
  a.messages === b.messages;

const sameTurns = (a: readonly TurnRow[], b: readonly TurnRow[]): boolean =>
  a.length === b.length &&
  a.every((row, index) => {
    const other = b[index];
    return other !== undefined && sameRow(row, other);
  });

const sameSeqOf = (a: ReadonlyMap<string, number>, b: ReadonlyMap<string, number>): boolean => {
  if (a.size !== b.size) return false;
  for (const [id, seq] of a) {
    if (b.get(id) !== seq) return false;
  }
  return true;
};

const seqOfEntries = (entries: readonly WindowEntry[]): ReadonlyMap<string, number> => {
  const seqOf = new Map<string, number>();
  for (const entry of entries) {
    const message = entry.message as { id?: unknown } | null | undefined;
    const id = message?.id;
    if (typeof id === "string" && typeof entry.seq === "number") seqOf.set(id, entry.seq);
  }
  return seqOf;
};

/// ONE WINDOW'S WORTH: the turns the record has, and the offset of every entry this copy holds.
/// Called wherever a window is committed (`app.tsx`), so that the two arrive together.
export function noteTurnRows(
  threadId: string,
  turns: readonly TurnRow[],
  entries: readonly WindowEntry[],
): void {
  const seqOf = seqOfEntries(entries);
  const before = held.get(threadId);
  if (before !== undefined && sameTurns(before.turns, turns) && sameSeqOf(before.seqOf, seqOf)) {
    return;
  }
  held.set(threadId, { turns, seqOf });
  for (const listener of listeners) listener();
}

/// THE TURN ONE MESSAGE BELONGS TO, by message id -- `undefined` when the record has no turn for
/// it. That is not an error: a conversation recorded before ADR 0017 has no `turn/start` rows at
/// all, and a message whose line has not landed yet (`seq: null`) cannot be placed either.
export function turnOfMessage(
  threadId: string | null,
  messageId: string | null,
): TurnRow | undefined {
  if (threadId === null || messageId === null) return undefined;
  const here = held.get(threadId);
  if (here === undefined) return undefined;
  const seq = here.seqOf.get(messageId);
  if (seq === undefined) return undefined;
  return here.turns.find((turn) => seq >= turn.from && (turn.to === undefined || seq < turn.to));
}

/// THE TURNS THE RECORD HAS for one conversation, as the newest frame carried them. The value is
/// stable between frames, which is what lets a component subscribe to it by identity.
export function turnRows(threadId: string | null): readonly TurnRow[] {
  return threadId === null ? NONE : (held.get(threadId)?.turns ?? NONE);
}

export function subscribeTurnRows(listener: () => void): () => void {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
}

/// Forget one conversation's turns. Here for the tests: a module-level store outlives a case.
export function forgetTurnRows(threadId: string): void {
  held.delete(threadId);
}
