// What a TURN is, as arithmetic: which messages are one, whether it has stopped,
// what its conclusion is, how much it did, and what its summary line says.
// A turn is a run of adjacent ASSISTANT messages -- the steps of one answer. The
// AG-UI adapter opens a new assistant message for every LLM round, so a turn that
// thought, read and then answered is several messages in a row, and the user
// message that starts the next turn is what ends it. `thread.aui.tsx` asks the
// same question for the action bar (`isTurnEnd` / `isTurnContinuation`); this
// module answers it for the fold, which needs the same boundary plus the counts.
//
// RUNTIME-ZERO IMPORTS, like `stats.ts` and `attachment-rules.ts`: the one import
// below is a TYPE (`import type { TFunction }`), which the compiler erases, so the
// UI suite can still test this as arithmetic over a literal message list instead of
// through a rendered thread (see test/suites/turns.ts).
import type { TFunction } from "i18next";

/// The shape this module reads a message through: enough to walk a turn, count a
/// tool call, and ask whether the message is still being written. Structural on
/// purpose -- the runtime's `MessageState` satisfies it, and so does a literal in
/// a test.
export type TurnMessage = {
  readonly role: string;
  readonly status?: { readonly type: string } | undefined;
  readonly parts?: readonly { readonly type: string; readonly text?: string }[] | undefined;
};

/// The run of adjacent assistant messages `index` sits in, as first/last indices.
///
/// Both ends are found by walking outwards while the neighbour is an assistant
/// message, which is what makes the boundary a fact about the LIST rather than a
/// field somebody has to keep in step. A message that is not an assistant's -- a
/// user's, or an index past the end -- is its own bounds: only assistant messages
/// ask, but returning a neighbour's turn for one would be a silent lie.
export function turnBounds(
  messages: readonly TurnMessage[],
  index: number,
): { first: number; last: number } {
  if (messages[index]?.role !== "assistant") return { first: index, last: index };
  let first = index;
  while (first > 0 && messages[first - 1]?.role === "assistant") first -= 1;
  let last = index;
  while (messages[last + 1]?.role === "assistant") last += 1;
  return { first, last };
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

/// What the turn did, as the fold line asks it: HOW MANY STEPS it took.
///
/// ONE MODEL REQUEST IS ONE STEP, and on the read side one request is one assistant message --
/// so the turn's steps are the run of assistant messages `turnBounds` found, and the user
/// message that started the turn is not one of them (it is already on screen above the line,
/// and counting it would make every turn one longer than the reader can see).
///
/// THE RECORD WRITES THAT BOUNDARY DOWN NOW (`step/start` rows, `.scratch/step-events`, ADR
/// 0011) and for a settled turn the two readings agree. They part in exactly one place: a
/// request the vendor refused for length and made us send again is ONE step in the record and
/// TWO messages here, because the client sees two messages and cannot see that the vendor was
/// asked twice. Where they part the record is right -- which is what `turn/end` carrying
/// `steps` is for.
export function turnCounts(
  messages: readonly TurnMessage[],
  first: number,
  last: number,
): { steps: number } {
  let steps = 0;
  for (let index = first; index <= last; index += 1) {
    if (messages[index]?.role === "assistant") steps += 1;
  }
  return { steps };
}

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
/// half (one request, one message: see `turnCounts`), and a line reading `3 步 · 3 条消息`
/// tells nobody anything; the tool-call half said less than the step count does -- three
/// requests, one of which may have run five tools.
///
/// THE COUNT GOES THROUGH i18next's `count`, so English's singular form lives in the catalog
/// (`1 step`) rather than in a hand-rolled rule -- which is what this line did before it had
/// a language.
export function turnSummaryLabel(steps: number, t: Translate): string {
  return t("summary.steps", { count: steps });
}
