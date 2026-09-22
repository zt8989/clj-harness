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
//     says its FIRST line, cut to `PREVIEW_LIMIT` with an `…`. That is the row's
//     resting shape, and it is the same shape a tool row has: `icon · name ·
//     subject`.
//   * A THOUGHT THAT IS STILL ARRIVING is handed over WHOLE (one line: blank runs
//     flattened). The disclosure no longer opens itself while tokens arrive (see
//     `ReasoningBlock` in `message-parts.tsx`), so the row is the only place a
//     live thought shows -- and a first line, frozen a second into the run, would
//     be the whole of what a reader could watch. What the row then DOES with that
//     text -- keep the end in view, and slide it left as it grows, so characters
//     leave at the left edge while the new ones arrive at the right one -- is
//     `ReasoningTail` plus the rule in `styles.css`.
//
// WHY THE LIVE HALF IS NOT CUT HERE ANY MORE, when it used to be cut to the same
// 120 characters. The window is made by MOVING the text (a transform), and only a
// transform can be interpolated: a window that instead cut the text to its last
// 120 characters slid by LAYOUT -- each token drops a character at the front, so
// the whole line shifts one character left in one frame, with nothing to
// interpolate and no frame in between. That is a snap per token, and it is
// exactly what the first cut of this feature looked like in a real session. So
// the row is handed what has arrived, and the part that has run off the left edge
// stays in the DOM (one text node) because the transform has to be able to move
// it: dropping it would put the motion back into layout.
//

/// How much of a STOPPED thought the row says, in characters -- the clip the `…`
/// is put behind, and the same shape a tool row's subject gets.
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

/// A thought as the row can draw it: ONE line, so runs of whitespace become a
/// single space, and the ends are trimmed.
///
/// The flattening is this file's and not the browser's. The row is
/// `white-space: nowrap`, so a newline would collapse to a space on screen
/// anyway -- but it would do so after the window had spent a character position
/// on it, and a window whose width is half newlines shows less of the thought
/// than the same width of text would.
export function oneLine(text: string): string {
  return text.replace(/\s+/g, " ").trim();
}

/// One reasoning part, as far as this file reads it: the type tag the parts list
/// carries, the text when there is one, and whether its own tokens are still
/// arriving. Structural rather than the package's `PartState`, because a message's
/// parts are a union of every kind of part there is and what a row needs from them
/// is exactly these fields.
export interface ReasoningPart {
  readonly type: string;
  readonly text?: string;
  readonly status?: { readonly type: string };
}

/// One MESSAGE of the thread, as far as this rule reads it: its role (a `user`
/// message is where a turn ends) and its parts.
export interface ReasoningMessage {
  readonly role?: string;
  readonly parts?: readonly (ReasoningPart | undefined)[];
}

/// ONE THOUGHT, as the row draws it.
export interface Thought {
  /// The reasoning of this thought, in order, up to the next STEP.
  readonly parts: readonly ReasoningPart[];
  /// False when a row ABOVE already draws this thought (see `thoughtAt`).
  readonly drawn: boolean;
  /// True while any of its reasoning is still arriving -- including reasoning that
  /// arrives in a LATER message of the same turn (see below).
  readonly running: boolean;
}

/// A STEP: the model did something. A tool call, in one of its two part kinds.
/// Steps end a thought; text does not.
function isStep(message: ReasoningMessage | undefined): boolean {
  return (message?.parts ?? []).some(
    (part) => part?.type === "tool-call" || part?.type === "standalone-tool-call",
  );
}

/// The reasoning parts of a message, in order.
function reasoningOf(message: ReasoningMessage | undefined): readonly ReasoningPart[] {
  return (message?.parts ?? []).filter(
    (part): part is ReasoningPart => part?.type === "reasoning",
  );
}

/// WHICH PARTS ARE ONE THOUGHT -- walking the THREAD, not one message, and that is
/// the whole of this function.
///
/// A vendor interleaves its thinking with the answer it is writing. Measured, on one
/// real session (`~/.clj-harness/projects/…/24b44253….jsonl`): `REASONING "…Answer
/// briefly"`, `TEXT_MESSAGE_START`, then the last token of the SAME thought as its
/// own block -- `REASONING " in Chinese."` -- then the rest of the answer. The
/// runtime turns each of those blocks into a MESSAGE of its own (one per AG-UI
/// message id), so the page drew what the wire said: a 思考 row before the answer,
/// and a second 思考 · in Chinese. at the bottom, which is what a reader reported as
/// a rendering error.
///
/// A TOOL CALL separates thoughts, and that is the shape this repo wants as several
/// rows: the model thought, did something, and thought again about what it did
/// (`想 → 读 → 再想`). TEXT does not separate them -- an answer being written is not
/// a new thought, it is the same one being finished -- so the row that began a
/// thought keeps it: the later messages join it, they do not get rows of their own.
///
/// The walk is in two directions and both are needed: BACK for `drawn` (if a step
/// comes first, this is a new thought; if reasoning does, this one is already drawn
/// above) and FORWARD for the parts themselves (`running` included, which is why a
/// thought that goes on thinking after the answer has started is STILL live in the
/// row that began it).
export function thoughtAt(
  messages: readonly (ReasoningMessage | undefined)[],
  at: number,
): Thought {
  let drawn = true;
  for (let index = at - 1; index >= 0; index -= 1) {
    const earlier = messages[index];
    if (earlier?.role === "user" || isStep(earlier)) break;
    if (reasoningOf(earlier).length > 0) {
      drawn = false;
      break;
    }
  }

  const parts: ReasoningPart[] = [];
  let running = false;
  for (let index = at; index < messages.length; index += 1) {
    const message = messages[index];
    if (message?.role === "user" || isStep(message)) break;
    for (const part of reasoningOf(message)) {
      parts.push(part);
      if (part.status?.type === "running") running = true;
    }
  }
  return { parts, drawn, running };
}

/// What the row says for a thought, or `""` when it has nothing to say yet (a
/// thought whose first token has not landed).
///
/// The two halves walk the parts in OPPOSITE directions: at rest the FIRST part with
/// a line wins, because joining parts would put a seam in the middle of a sentence;
/// while the thought is arriving the LAST part with text wins, because that is the
/// one the model is still writing. A part with nothing in it yet is skipped by both,
/// so a thought that opens with a newline reads as the part that has something to
/// say.
export function previewOf(
  parts: readonly (ReasoningPart | undefined)[],
  running: boolean,
): string {
  if (running) {
    for (let position = parts.length - 1; position >= 0; position -= 1) {
      const part = parts[position];
      if (part?.type !== "reasoning") continue;
      const arriving = oneLine(part.text ?? "");
      if (arriving !== "") return arriving;
    }
    return "";
  }
  for (const part of parts) {
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
