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

/// What the frame carried: the message's role and its bytes.
export type InjectionValue = {
  readonly role?: string;
  readonly text?: string;
};

/// What the collapsed row says. Every field is a string the row can print as-is.
export type InjectionView = {
  /// The tag the block arrived with -- `instructions`, `skill`, `job-ended` -- or the first line
  /// when it does not open with one.
  title: string;
  /// The first line, clipped: the row is one line and truncation is the browser's.
  preview: string;
  /// The bytes of the whole block, not of the preview.
  bytes: number;
};

/// The tag name a block opens with, or null when it does not open with a tag.
///
/// `<skill name="tdd">` -> `skill`. Deliberately shallow: the tags are the server's own frame for
/// the first line (see `harness.cap.skills/skill-message`), and a parser here would be this side's
/// second opinion about a shape it does not own.
function tagOf(text: string): string | null {
  const match = /^<([a-z][a-z0-9-]*)[\s>]/.exec(text.trimStart());
  return match?.[1] ?? null;
}

/// The first non-empty line, as the row's preview.
function firstLine(text: string): string {
  for (const line of text.split("\n")) {
    if (line.trim() !== "") return line.trim();
  }
  return "";
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
  const line = firstLine(text);
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
