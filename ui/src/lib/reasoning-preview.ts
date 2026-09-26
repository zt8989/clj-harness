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
//     text -- keep the end in view, slide it left as it grows, and LET GO of the
//     blocks that have run off it -- is `ReasoningTail` plus the rule in
//     `styles.css`; WHICH block of it the DOM holds is `tailStart` below.
//
// WHY THE LIVE HALF IS NOT CUT HERE, when it used to be cut to the same 120
// characters. The window is made by MOVING the text (a transform), and only a
// transform can be interpolated: a window cut to the last N characters slid by
// LAYOUT -- each token dropped a character at the front, so the whole line shifted
// one character left in one frame, with nothing to interpolate and no frame in
// between. That is a snap per token, and it is exactly what the first cut of this
// feature looked like in a real session (`.scratch/thinking-row-tail/spec.md`,
// 复议一). So CUTTING and MOVING are two different things, and they are done in two
// different places: this module hands the row everything that has arrived, and the
// row holds a BLOCK of it (`tailStart`), paying for what it lets go with the padding
// that puts the line back where layout would have left it (`ReasoningTail`). A block
// leaves once per `TAIL_DROP` characters and never once per token, so nothing a
// reader can see moves when it does. Holding the whole thought instead -- because the
// drag might have to move it -- is what made the row's own work grow with the
// thought: every token re-laid-out and re-measured a line as long as the thought is.
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

/// A STEP PART: the model did something. A tool call, in one of its two part
/// kinds. Steps end a thought; text does not.
function isStepPart(part: ReasoningPart | undefined): boolean {
  return part?.type === "tool-call" || part?.type === "standalone-tool-call";
}

/// Whether ANY part of a message is a step. Used to cross MESSAGES, where the
/// runtime has already split a turn's rounds: a message that carries a call is a
/// thing the model did, and a thought never reads past one for a continuation.
/// THE CURRENT MESSAGE IS READ PART BY PART INSTEAD -- see `thoughtAt` -- because
/// a live run keeps a whole turn's parts (several thoughts and the calls between
/// them) in ONE message.
function isStep(message: ReasoningMessage | undefined): boolean {
  return (message?.parts ?? []).some(isStepPart);
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
///
/// A LIVE RUN'S MESSAGE IS A TURN, NOT A THOUGHT. The adapter's own aggregation keeps
/// one assistant message open for a whole turn and appends every round to it -- the
/// thoughts AND the tool calls between them share one `parts` list. So "this message
/// is a thought" is false while a run streams, and `at` alone cannot say which of the
/// message's several thoughts this row is. `from` is the first part index of THIS
/// row's reasoning group (`GroupPart.indices[0]`), and the current message is read
/// part by part from there: a tool-call part ends the thought, text does not. Later
/// messages are read whole, because the runtime has already split those by round.
export function thoughtAt(
  messages: readonly (ReasoningMessage | undefined)[],
  at: number,
  from = 0,
): Thought {
  const current = messages[at]?.parts ?? [];

  // BACK: an earlier reasoning part makes this row a continuation -- unless a
  // step comes first, which is what makes it a new thought.
  let drawn = true;
  backward: {
    for (let index = from - 1; index >= 0; index -= 1) {
      const part = current[index];
      if (isStepPart(part)) break backward;
      if (part?.type === "reasoning") {
        drawn = false;
        break backward;
      }
    }
    for (let index = at - 1; index >= 0; index -= 1) {
      const earlier = messages[index];
      if (earlier?.role === "user" || isStep(earlier)) break backward;
      if (reasoningOf(earlier).length > 0) {
        drawn = false;
        break backward;
      }
    }
  }

  // FORWARD: this thought's reasoning, up to the next step or turn boundary.
  const parts: ReasoningPart[] = [];
  let running = false;
  forward: {
    for (let index = from; index < current.length; index += 1) {
      const part = current[index];
      if (isStepPart(part)) break forward;
      if (part?.type === "reasoning") {
        parts.push(part);
        if (part.status?.type === "running") running = true;
      }
    }
    for (let index = at + 1; index < messages.length; index += 1) {
      const message = messages[index];
      if (message?.role === "user" || isStep(message)) break forward;
      for (const part of reasoningOf(message)) {
        parts.push(part);
        if (part.status?.type === "running") running = true;
      }
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

/// ------------------------------------------------------- what the row holds
///
/// HOW MUCH OF A LIVE THOUGHT THE ROW HOLDS, and how much of it leaves at a time, in
/// characters.
///
/// THE BOUND IS NOT `PREVIEW_LIMIT`. That 120 is what a STOPPED thought says in its
/// one line; a live thought is dragged through the window, and the drag needs material
/// in front of the window -- the characters leaving at the left edge ARE the motion.
/// The window is tens of characters wide at this size, so `TAIL_KEEP` is many windows'
/// worth, and a block leaves rarely enough that a reader never meets two seams.
export const TAIL_KEEP = 600;
export const TAIL_DROP = 600;

/// WHERE THE DOM'S COPY OF A LIVE THOUGHT BEGINS -- an offset into the text
/// `previewOf` hands over, and it only ever moves FORWARD.
///
/// `held` IS THE LAST ANSWER AND THE TEXT IT WAS COUNTED AGAINST, because the question
/// is not "how much of this should be kept" but "did this line grow, or is it a
/// different line": a thought that is not an EXTENSION of the one the row was holding
/// -- the later part of one the runtime split into a message of its own, a restored
/// conversation, another thought -- shares no offset with it, and the answer is the
/// beginning of the new one.
///
/// A BLOCK LEAVES, WHICH IS WHY THE ANSWER IS QUANTISED. Advancing one character per
/// token would be the snap this feature already shipped once (see the header); the row
/// pays for a block, and that payment is worth making once per `TAIL_DROP`, not once
/// per token. Between two answers here the text only ever GROWS by appending, which is
/// the property the row's drag depends on.
export function tailStart(text: string, held: { text: string; start: number }): number {
  if (!text.startsWith(held.text)) return 0;
  let start = held.start;
  while (text.length - start > TAIL_KEEP + TAIL_DROP) start += TAIL_DROP;
  return start;
}
