// The INJECTION card's arithmetic: what its collapsed row says, over the frame the server sent.
//
// WHAT AN INJECTION IS. The session's pre-LLM step puts messages into the history that the client
// never sent and never holds: the instruction blocks, a skill body, the ending of a background job.
// The server emits each one as an AG-UI `CUSTOM` frame, the adapter turns it into a `data` part
// (`dataRendererUI` draws it), and -- the fact this whole feature rests on -- `toAgUiMessages` has
// NO case for a data part, so the card is visible and is never sent back to the server. The
// conversation stays the client's; the card is a view of what the model was handed.
//
// THE OTHER HALF IS SHARED AND LIVES ELSEWHERE (`lib/card-parts.ts`): whether a message is a card
// and nothing else, and putting the parts back into a rebuilt conversation. Those are questions
// about CARDS rather than about injections -- a compaction is the second card, and both answers are
// the same for it -- so they are written once, over the name list, instead of once per name.
//
// RUNTIME-ZERO IMPORTS, like `lib/turns.ts` and for the same reason: the UI suite pins this as
// arithmetic over literals instead of through a rendered thread, and the drawing itself is measured
// in a real browser (`.scratch/context-frames/`).

/// The `data` part's name, on both ends: the server names the frame's part `injected-context`
/// (`harness.edge.ag_ui`) and this module is what looks it up.
export const INJECTION_PART = "injected-context";

/// The frame every injection wears (`harness.cap.reminder`), on both ends. Spelled here because
/// the label line a card's title comes from is the first line INSIDE this frame.
const REMINDER_OPEN = "<system-reminder>";
const REMINDER_CLOSE = "</system-reminder>";

/// What the frame carried: the message's role and its bytes.
export type InjectionValue = {
  readonly role?: string;
  readonly text?: string;
};

/// What the collapsed row says. Every field is a string the row can print as-is.
export type InjectionView = {
  /// The block's LABEL LINE -- `Instructions from: …`, `Skill tdd`, `Background job j1
  /// ended: …` -- or, for a record written before that shape existed, the tag it opens
  /// with (`skill`).
  title: string;
  /// The label line, clipped: the row is one line and truncation is the browser's.
  preview: string;
  /// The bytes of the whole block, not of the preview.
  bytes: number;
};

/// The tag name a block opens with, or null when it does not open with a tag.
///
/// `<skill name="tdd">` -> `skill`. Deliberately shallow, and now a FALLBACK to the shape an old
/// record was written in: the tags were the server's own frame for the first line, and a parser
/// here would be this side's second opinion about a shape it does not own.
function tagOf(text: string): string | null {
  const match = /^<([a-z][a-z0-9-]*)[\s>]/.exec(text.trimStart());
  return match?.[1] ?? null;
}

/// THE LABEL LINES, as prefixes, in the order a reader tries them: every injection opens with
/// one of these, and which one it is says what the block IS. KEPT IN STEP WITH
/// `harness.cap.reminder/labels` -- one table on the other end of the same wire, for the same
/// reason `REMINDER_OPEN` is spelled here: the two ends have to agree about the bytes and
/// neither can import the other's table.
const LABEL_PREFIXES = [
  "Instructions from: ",
  "Available skills",
  "Skill ",
  "Background job ",
  "Session context",
  // The task list's reminder (`harness.cap.todos/reminder-text`): the harness saying that work
  // a session planned for itself is not finished. An injection like the row above.
  "Task list reminder:",
];

/// The line inside a block that says what it is.
///
/// EVERY INJECTION WEARS ONE FRAME NOW (`harness.cap.reminder`), `<system-reminder>` holding
/// plain text, and the label is the first line INSIDE it that the table names. That is the
/// whole reason this is not `tagOf(text)` any more: the frame is the same for a skill body
/// and a job's ending, so reading the first line of the whole block would title every card
/// `system-reminder`.
///
/// NOT SIMPLY THE FIRST LINE, and the instruction block is why: it is ONE block holding
/// several files, and it opens with dsh's intro SENTENCE before its first `Instructions from:`
/// section. Titling the card with that paragraph is what this table is here to prevent.
/// A block that names none of these (an old record) keeps its own first line, which is its tag.
function labelLine(text: string): string {
  const lines = text.split("\n");
  const start = lines[0]?.trim() === REMINDER_OPEN ? 1 : 0;
  const body: string[] = [];
  for (let i = start; i < lines.length; i += 1) {
    const line = lines[i]?.trim() ?? "";
    if (line !== "" && line !== REMINDER_CLOSE) body.push(line);
  }
  return body.find((line) => LABEL_PREFIXES.some((prefix) => line.startsWith(prefix))) ?? body[0] ?? "";
}

/// The bytes of TEXT as UTF-8 -- what the model was actually handed.
function utf8Bytes(text: string): number {
  return new TextEncoder().encode(text).length;
}

/// A frame's value -> what the row says, or null when there is nothing to draw.
///
/// NULL IS AN ANSWER: a frame with no text is a card that would say nothing, and a row drawn for it
/// would claim an injection nobody can check. The caller renders nothing instead.
export function injectionView(value: unknown): InjectionView | null {
  if (typeof value !== "object" || value === null) return null;
  const text = (value as InjectionValue).text;
  if (typeof text !== "string" || text.trim() === "") return null;
  const line = labelLine(text);
  /// A FRAME WITH NOTHING IN IT IS THE SAME ANSWER as no text at all: the block said
  /// nothing, so a row drawn for it would claim an injection nobody can check.
  if (line === "") return null;
  return {
    title: tagOf(line) ?? line,
    preview: line,
    bytes: utf8Bytes(text),
  };
}

/// The ids the SERVER gives the messages it wrote into a conversation itself: the
/// opening blocks are `session-opening-0`, `session-opening-1`, ... (the record's
/// `harness.edge.ag_ui/opening-entry-prefix`). Nobody typed them, and the record says so
/// by id -- which is what `isCardOnly` cannot answer on its own once the card part is
/// gone.
export function isOpeningEntryId(id: unknown): boolean {
  return typeof id === "string" && /^session-opening-\d+$/.test(id);
}

/// The text a message holds, as one string: what a card with NO `data` part reads
/// instead. A message the adapter imported from a `MESSAGES_SNAPSHOT` keeps its text and
/// its id and loses the card part (`fromAgUiMessages` has no case for a `data` part), and
/// the text is the same bytes the part carried -- the server builds both from the one
/// block (`harness.edge.ag_ui/opening-entries`).
export function textOfParts(parts: readonly unknown[]): string {
  return parts
    .filter(
      (part): part is { readonly type: "text"; readonly text: string } =>
        typeof part === "object" &&
        part !== null &&
        (part as { type?: unknown }).type === "text" &&
        typeof (part as { text?: unknown }).text === "string",
    )
    .map((part) => part.text)
    .join("\n");
}
