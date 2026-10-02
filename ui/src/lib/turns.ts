// What a TURN is, as arithmetic: which messages are one, whether it has stopped, what its
// conclusion is, and what its summary line says.
//
// THE TURN ITSELF IS NOT ARITHMETIC ANY MORE (ADR 0017). It used to be "a run of adjacent
// assistant messages" -- a fact about the message list -- and it is now a fact about the RECORD:
// the `turn/start` / `turn/end` rows say where a turn begins and what it did, and the window
// carries those rows to the page (`feed.ts`'s `TurnRow`, remembered by `lib/turn-rows.ts`). What
// is still arithmetic here is what a turn MEANS to the reader: whether it has stopped, and which
// of its messages is the answer.
//
// `thread.aui.tsx` asks the same boundary question for the action bar (`isTurnEnd` /
// `isTurnContinuation`); this module answers it for the fold.
//
// RUNTIME-ZERO IMPORTS, like `stats.ts` and `attachment-rules.ts`: the one import
// below is a TYPE (`import type { TFunction }`), which the compiler erases, so the
// UI suite can still test this as arithmetic over a literal message list instead of
// through a rendered thread (see test/suites/turns.ts).
import type { TFunction } from "i18next";

/// The shape this module reads a message through: enough to place it in a turn and to ask
/// whether that turn is still being written. Structural on purpose -- the runtime's `MessageState`
/// satisfies it, and so does a literal in a test.
export type TurnMessage = {
  readonly id?: string | undefined;
  readonly role: string;
  readonly status?: { readonly type: string } | undefined;
  readonly parts?: readonly { readonly type: string; readonly text?: string }[] | undefined;
};

/// WHAT TURN THE RECORD PUTS ONE MESSAGE IN, by that message -- `undefined` when it puts it in
/// none. `ui/src/lib/turn-rows.ts` is the one implementation: it compares the record line the
/// message arrived in against each turn's own range.
export type TurnOf = (message: TurnMessage) => string | undefined;

/// The run of adjacent messages `index` sits in, as first/last indices -- THE TURN THE RECORD PUT
/// IT IN, found by asking `turnOf` for each neighbour's turn id.
///
/// THE MESSAGE LIST IS NOT THE AUTHORITY (ADR 0017, and the reason this takes `turnOf`). It used
/// to group adjacent ASSISTANT messages, which is a fact about the list and a second place the
/// boundary was decided; the record writes it down and the window carries it. A message the record
/// places in no turn is its own bounds -- there is nothing it could be grouped with.
export function turnBounds(
  messages: readonly TurnMessage[],
  index: number,
  turnOf: TurnOf,
): { first: number; last: number } {
  const mine = turnOf(messages[index] ?? {});
  if (mine === undefined) return { first: index, last: index };
  let first = index;
  while (first > 0 && turnOf(messages[first - 1] ?? {}) === mine) first -= 1;
  let last = index;
  while (last + 1 < messages.length && turnOf(messages[last + 1] ?? {}) === mine) last += 1;
  return { first, last };
}

/// THE STEPS OF A TURN: the messages of `turnBounds` that the FOLD is about -- the assistant's own.
///
/// A TURN ALSO HOLDS THE PERSON'S MESSAGE. The record's boundary stands in FRONT of what it opens
/// (ADR 0017), so the turn `turnBounds` answers with begins at the question; that message is never
/// folded -- it is what the reader asked -- and a summary line drawn by it would be a header in
/// front of the wrong thing. This is the run the summary line and the hiding are about, and the
/// first of it is the message that draws the line.
export function turnStepBounds(
  messages: readonly TurnMessage[],
  index: number,
  turnOf: TurnOf,
): { first: number; last: number } {
  const { first, last } = turnBounds(messages, index, turnOf);
  let head = first;
  while (head <= last && messages[head]?.role !== "assistant") head += 1;
  let tail = last;
  while (tail >= head && messages[tail]?.role !== "assistant") tail -= 1;
  return head > tail ? { first: index, last: index } : { first: head, last: tail };
}

/// Whether the turn has stopped being written.
///
/// The status of its LAST message is what says so: `running` is still arriving,
/// `requires-action` is parked on a human (a card inside those steps has to stay
/// reachable), and both `complete` and `incomplete` are over -- an aborted turn
/// is as finished as one that answered.
///
/// `isRunning` is consulted as well, and that is not belt-and-braces: the runtime
/// finalises each message as the next one opens, so the last step's status reads
/// `complete` for a beat while the TURN is still going. Without the thread-level
/// check the same turn would fold and unfold in the middle of a run.
export function turnIsSettled(
  messages: readonly TurnMessage[],
  last: number,
  isRunning: boolean,
): boolean {
  if (isRunning && last === messages.length - 1) return false;
  const type = messages[last]?.status?.type;
  return type === "complete" || type === "incomplete";
}

/// The turn's CONCLUSION: the last of its messages that carries a non-empty text body,
/// or `undefined` when the turn never answered.
///
/// A TURN IS NOT ITS ANSWER. A ReAct turn is several messages -- it thought, read,
/// thought again -- and only one of them speaks to the reader. The steps carry
/// `reasoning` and `tool-call` parts; a message with a `text` part is what was said
/// out loud. Whether such a message exists is what the fold has to know: a turn that
/// stopped with no text at all (a crash, an abort mid-thought, a run that only ever
/// called tools) has NO conclusion, and folding it must put the whole turn away rather
/// than leave the last thought or tool call on screen pretending to be an answer.
///
/// THE LAST ONE WINS, NOT THE FIRST: a model may speak, call a tool, and speak again;
/// the turn's answer is what it said last, and anything after it is a step of the same
/// turn. `undefined` -- never `last` -- is the 'no answer' answer, so no caller can
/// mistake 'the tail happens to be text-free' for 'there is nothing to keep'.
///
/// EMPTINESS COUNTS AS NOTHING SAID: a message whose text part is blank has not
/// answered, and treating it as the conclusion would keep a blank line instead of
/// folding the turn -- which is the very leak this predicate exists to close.
export function turnConclusion(
  messages: readonly TurnMessage[],
  first: number,
  last: number,
): number | undefined {
  for (let index = last; index >= first; index -= 1) {
    const parts = messages[index]?.parts ?? [];
    if (parts.some((part) => part.type === "text" && (part.text ?? "").trim() !== "")) return index;
  }
  return undefined;
}

/// HOW MANY STEPS A TURN TOOK IS NOT ANSWERED HERE ANY MORE (ADR 0017). It is the number the
/// turn's own `turn/end` row carries (`steps`), the window hands it over with the turn, and the
/// summary line reads it there -- one number, written down once, instead of a count of messages
/// that agreed with it except when the vendor made us send a request twice.
/// WHICH OF TWO READINGS THE SUMMARY LINE PRINTS IS NOT A QUESTION ANY MORE (ADR 0017). The
/// steps are the record's own number wherever it came from -- the `turn/end` row in the window, or
/// the same row pushed the moment it was written (`lib/turn-numbers.ts`). They are one fact said
/// twice on the wire, never two readings that could disagree.
/// The translator `turnSummaryLabel` takes, PINNED TO THE FACE THAT DRAWS IT.
///
/// i18next's `TFunction` is branded with the namespace it was bound to, so a bare
/// `TFunction` here would mean "whatever the default namespace is" and would accept
/// a shell translator by mistake, while a `string` key would lose the key check in
/// this file entirely. Naming `thread` keeps both -- the same choice `format.ts`
/// makes for its own face.
type Translate = TFunction<"thread">;

/// The summary line's text: `3 步`, or in English `3 steps`.
///
/// IT USED TO BE TWO NUMBERS -- `72 tool calls · 25 messages` -- AND BOTH ARE GONE
/// (`.scratch/step-events`, ticket 05). The message half was the same number as the step
/// half (one request, one message), and a line reading `3 步 · 3 条消息`
/// tells nobody anything; the tool-call half said less than the step count does -- three
/// requests, one of which may have run five tools.
///
/// THE COUNT GOES THROUGH i18next's `count`, so English's singular form lives in the catalog
/// (`1 step`) rather than in a hand-rolled rule -- which is what this line did before it had
/// a language.
export function turnSummaryLabel(steps: number, t: Translate): string {
  return t("summary.steps", { count: steps });
}
