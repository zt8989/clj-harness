// What a TURN is, as arithmetic: which messages are one, whether it has stopped,
// how much it did, and what its summary line says.
//
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
  readonly parts?: readonly { readonly type: string }[] | undefined;
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

/// What the turn did: its tool calls, and how many assistant messages it is.
///
/// The second number counts the turn's OWN assistant messages -- the steps and
/// the answer -- not the user message that started it: that one is already on
/// screen above the summary line, and counting it would make every turn one
/// longer than the reader can see.
export function turnCounts(
  messages: readonly TurnMessage[],
  first: number,
  last: number,
): { calls: number; messages: number } {
  let calls = 0;
  for (let index = first; index <= last; index += 1) {
    for (const part of messages[index]?.parts ?? []) {
      if (part.type === "tool-call") calls += 1;
    }
  }
  return { calls, messages: last - first + 1 };
}

/// The translator `turnSummaryLabel` takes, PINNED TO THE FACE THAT DRAWS IT.
///
/// i18next's `TFunction` is branded with the namespace it was bound to, so a bare
/// `TFunction` here would mean "whatever the default namespace is" and would accept
/// a shell translator by mistake, while a `string` key would lose the key check in
/// this file entirely. Naming `thread` keeps both -- the same choice `format.ts`
/// makes for its own face.
type Translate = TFunction<"thread">;

/// The summary line's text: `72 tool calls · 25 messages`, or -- in Chinese --
/// `72 次工具调用 · 25 条消息`.
///
/// The tool-call half is dropped when there were none: a turn of pure thought is
/// the common case for a short answer, and `0 tool calls · 2 messages` states a
/// fact nobody asked for. A turn with no tool calls and one message never reaches
/// this function -- there is nothing folded to label (see `useStepFold`).
///
/// THE SEPARATOR IS NOT IN THE CATALOG. ` · ` sits between two phrases that each
/// carry their own plural rule, and i18next's `count` resolves one plural per key
/// -- so the two halves are translated separately and the punctuation joins them.
/// That is the same line `subjectOf` draws around its ` → ` and `…`.
export function turnSummaryLabel(calls: number, messages: number, t: Translate): string {
  const messagesText = t("summary.messages", { count: messages });
  if (calls === 0) return messagesText;
  return `${t("summary.calls", { count: calls })} · ${messagesText}`;
}
