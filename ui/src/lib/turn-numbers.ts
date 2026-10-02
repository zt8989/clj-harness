// THE SERVER'S OWN COUNT FOR THE TURN IT JUST CLOSED, per conversation -- `turn/end`'s `steps`,
// kept where the fold line can ask for it.
//
// WHY KEEP IT AT ALL, when `lib/turns.ts` can count a turn's steps off the conversation: THE TWO
// READINGS ARE NOT THE SAME READING. The read side sees one assistant message per request and
// cannot tell a request the vendor refused for length and this layer sent again from a second
// request -- the record can, because a retry stays INSIDE one step (ADR 0011). There they part,
// and the record is right. `ui/test/suites/turn.ts`'s
// `the-read-side-and-the-server-count-the-same-steps` is the promise that they agree everywhere
// else, measured on a real run.
//
// AND IT IS NOT A HISTORY. Facts are 'about this conversation while you are looking at it'
// (ADR 0006 decision 7): nothing is replayed, so a page that opened AFTER a turn closed has
// nothing here -- and the read side answers, being the only one of the two that can answer for a
// past it did not watch. The RECORD'S OWN ROW is what both roads carry now (ADR 0017): the window
// hands it over with the turn (`lib/turn-rows.ts`), and this store holds the newest one pushed --
// the same fact twice on the wire, so there is no choice left to make.
import type { FactFrame } from "./mux";

export type TurnNumbers = {
  /// The record's own name for the turn (`<thread>-t<line>`), when the sender named it.
  turnId?: string;
  steps: number;
  messages: number;
};

const latest = new Map<string, TurnNumbers>();
const listeners = new Set<() => void>();

/// One fact. ONLY a `turn/end` that carried a step count moves this: everything else in the
/// family leaves the last answer standing, which is exactly what 'the turn that just closed'
/// means. A payload that does not have the shape (`steps` missing) is not an answer either --
/// the sender would rather say nothing than have this side invent a number.
export function noteTurnEnd(threadId: string, fact: FactFrame): void {
  if (fact.type !== "turn/end") return;
  const numbers = fact.numbers as { steps?: unknown; messages?: unknown } | undefined;
  if (numbers === undefined || typeof numbers.steps !== "number") return;
  latest.set(threadId, {
    ...(typeof fact.turnId === "string" ? { turnId: fact.turnId } : {}),
    steps: numbers.steps,
    messages: typeof numbers.messages === "number" ? numbers.messages : 0,
  });
  for (const listener of listeners) listener();
}

export function serverTurnNumbers(threadId: string | null): TurnNumbers | undefined {
  return threadId === null ? undefined : latest.get(threadId);
}

/// The store's subscription, for `useSyncExternalStore` (`components/turn-steps.tsx` reads it
/// through that hook -- a `turn/end` that arrived while the line was on screen has to redraw it).
export function subscribeTurnNumbers(listener: () => void): () => void {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
}

/// Forget one conversation's numbers. Here for the tests: a module-level store outlives a case,
/// the same reason `lib/attachments.ts` and `turn-steps.tsx`'s `opened` set need a way to.
export function forgetTurnNumbers(threadId: string): void {
  latest.delete(threadId);
}
