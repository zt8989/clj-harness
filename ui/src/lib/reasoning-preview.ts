// The words on a reasoning row: the row's SUBJECT, in one line, the way
// `subjectOf` in `message-parts.tsx` is a tool call's subject.
//
// WHY THE RULE IS A MODULE OF ITS OWN. It has two halves and they are the two
// states of one thought, so a test that pins what the row SAYS has to reach both
// of them without a DOM -- `test/suites/reasoning-row.ts` does exactly that, and
// what the row LOOKS like is the browser walkthrough's half
// (`.scratch/thinking-row-tail/`).
//
//   * A THOUGHT THAT HAS STOPPED -- or one that arrived whole out of history --
//     says its FIRST line. That is the row's resting shape, and it is the same
//     shape a tool row has: `type icon · name · subject`.
//   * A THOUGHT THAT IS STILL ARRIVING says its TAIL: the newest window of it.
//     The disclosure no longer opens itself while tokens arrive (see
//     `ReasoningBlock` in `message-parts.tsx`), so the row is the only place a
//     live thought shows -- and a first line, frozen a second into the run, would
//     be the whole of what a reader could watch. The thing worth watching is
//     where the model is NOW. The row cuts that window at its LEFT edge
//     (`.aui-reasoning-trigger-tail`, `styles.css`), so the newest characters
//     stay in view and the older ones run off behind them; when the thought
//     stops, the row goes back to the first line.
//

/// How much of a thought the row is given, in characters.
///
/// Two reasons for a number at all, and they are not the same reason:
///
/// 1. THE CLIP AT REST IS THE ROW'S OWN SHAPE. 120 characters plus the `…` the
///    caller adds is what a tool row's subject gets, and a thought that has
///    stopped should read like one.
/// 2. WHILE THE THOUGHT IS ARRIVING IT IS A BOUND RATHER THAN A CLIP. The row's
///    element cuts what the line cannot hold, so the tail needs no `…` of its
///    own -- the cut lands off-screen, and a mark about a part nobody can see
///    would be a mark about nothing. What the bound buys is the size of a
///    re-render: the caller is `useAuiState`, which compares a selector's result
///    BY VALUE, and a live tail changes value on every token. Handing the row the
///    whole thought instead would keep every token of a long one in the DOM, for
///    a line that shows about sixty characters of it.
export const PREVIEW_LIMIT = 120;

/// The first line that is not blank, trimmed. Models open a thought -- and often
/// a command -- with a newline, and "the first line" would then be nothing.
export function firstLine(text: string): string {
  for (const line of text.split("\n")) {
    const trimmed = line.trim();
    if (trimmed !== "") return trimmed;
  }
  return "";
}

/// The newest `PREVIEW_LIMIT` characters of a thought, with runs of whitespace
/// flattened to one space.
///
/// The flattening is this file's and not the browser's: the row is ONE line
/// (`white-space: nowrap`), so a newline would collapse to a space on screen
/// anyway -- but it would do so after this function had spent the window on it,
/// and a tail whose window is half newlines is a window that shows nothing.
/// Flattening first means the 120 characters are 120 characters of the thought.
export function tail(text: string): string {
  const flat = text.replace(/\s+/g, " ").trim();
  return flat.length > PREVIEW_LIMIT ? flat.slice(-PREVIEW_LIMIT) : flat;
}

/// One reasoning part, as far as this file reads it: the type tag the parts list
/// carries, and the text when there is one. Structural rather than the package's
/// `PartState`, because the parts list is a union of every kind of part there is
/// and what a row needs from them is exactly these two fields.
export interface ReasoningPart {
  readonly type: string;
  readonly text?: string;
}

/// What the row says for a run of reasoning parts, or `""` when it has nothing
/// to say yet (a thought whose first token has not landed).
///
/// The group knows which parts it covers (`indices`) and nothing else, so the
/// parts are read here. A group can hold several parts, and the two halves walk
/// it in opposite directions: at rest the FIRST part with a line wins, because
/// joining parts would put a seam in the middle of a sentence; while running the
/// LAST part with text wins, because that is the one the model is still writing.
/// A blank part is skipped by both, so a thought that opens with a newline reads
/// as the part that has something to say.
export function previewOf(
  parts: readonly (ReasoningPart | undefined)[],
  indices: readonly number[],
  running: boolean,
): string {
  if (running) {
    for (let position = indices.length - 1; position >= 0; position -= 1) {
      const part = parts[indices[position]!];
      if (part?.type !== "reasoning") continue;
      const newest = tail(part.text ?? "");
      if (newest !== "") return newest;
    }
    return "";
  }
  for (const index of indices) {
    const part = parts[index];
    if (part?.type !== "reasoning") continue;
    const line = firstLine(part.text ?? "");
    if (line !== "") return clip(line);
  }
  return "";
}

/// A line cut to `PREVIEW_LIMIT` characters, `…` at the end where it was cut.
function clip(text: string): string {
  return text.length > PREVIEW_LIMIT
    ? `${text.slice(0, PREVIEW_LIMIT).trimEnd()}…`
    : text;
}