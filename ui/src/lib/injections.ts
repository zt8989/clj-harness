// The card an INJECTED CONTEXT draws in the conversation column, as arithmetic.
//
// WHAT AN INJECTION IS. The session's pre-LLM step puts messages into the history
// that the client never sent and never holds: the instruction blocks, a skill body,
// the ending of a background job. The server emits each one as an AG-UI `CUSTOM`
// frame, the adapter turns it into a `data` part (`dataRendererUI` draws it), and
// -- the fact this whole feature rests on -- `toAgUiMessages` has NO case for a data
// part, so the card is visible and is never sent back to the server. The
// conversation stays the client's; the card is a view of what the model was handed.
//
// TWO JOBS, AND BOTH ARE PURE:
//
//   `injectionView`  what the collapsed row says -- a title (the tag it arrived
//                    with), a one-line preview, and how many BYTES it is. Bytes
//                    rather than characters, because that is what the model pays:
//                    a Chinese instruction block is three bytes a character.
//   `keepInjectionCards`  puts the cards back into a rebuilt conversation. The
//                    adapter's `fromAgUiMessages` keeps text and tool calls only,
//                    so a card message comes out of the rebuild EMPTY -- this
//                    restores the part it dropped, by id, and nothing else.
//
// RUNTIME-ZERO IMPORTS, like `lib/turns.ts` and for the same reason: the UI suite
// pins both as arithmetic over literals instead of through a rendered thread, and
// the drawing itself is measured in a real browser (`.scratch/context-frames/`).
import type { ThreadMessageLike } from "@assistant-ui/react";

/// The part shape this module puts back: a `data` part by name (assistant-ui's
/// `DataMessagePart`, spelled here so the module needs no runtime import).
type DataPart = {
  readonly type: "data";
  readonly name: string;
  readonly data: unknown;
};

/// The `data` part's name, on both ends: the server names the frame's part
/// `injected-context` (`harness.edge.ag_ui`) and this module is what looks it up.
export const INJECTION_PART = "injected-context";

/// What the frame carried: the message's role and its bytes.
export type InjectionValue = {
  readonly role?: string;
  readonly text?: string;
};

/// What the collapsed row says. Every field is a string the row can print as-is.
export type InjectionView = {
  /// The tag the block arrived with -- `instructions`, `skill`, `job-ended` -- or
  /// the first line when it does not open with one.
  title: string;
  /// The first line, clipped: the row is one line and truncation is the browser's.
  preview: string;
  /// The bytes of the whole block, not of the preview.
  bytes: number;
};

/// The tag name a block opens with, or null when it does not open with a tag.
///
/// `<skill name="tdd">` -> `skill`. Deliberately shallow: the tags are the
/// server's own frame for the first line (see `harness.cap.skills/skill-message`),
/// and a parser here would be this side's second opinion about a shape it does not
/// own.
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
/// NULL IS AN ANSWER: a frame with no text is a card that would say nothing, and a
/// row drawn for it would claim an injection nobody can check. The caller renders
/// nothing instead.
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

/// Is this one of the cards this module owns? The NAME is the whole test: the server
/// names the part (`injected-context`) and nothing else in this app uses that name.
export function isCardPart(part: unknown): boolean {
  return (
    typeof part === "object" &&
    part !== null &&
    (part as { type?: unknown }).type === "data" &&
    (part as { name?: unknown }).name === INJECTION_PART
  );
}

/// Is this message the card and NOTHING ELSE?
///
/// THE QUESTION ROLE CANNOT ANSWER. The opening's entries are `role: "user"` -- user
/// messages to the provider, and the record says so -- so the thread draws them the way
/// it draws anything a person typed: right-aligned, in a grey bubble, with an Edit
/// pencil beside them. Nobody typed them; the server wrote them at the session's birth.
/// What separates the two is that this message is a card and no words, which is what
/// this answers.
///
/// NOT `keepInjectionCards`'s JOB, and the two are easy to confuse: that one puts back a
/// card the rebuild dropped, this one decides how the message HOLDING a card is drawn. A
/// message with a card AND text beside it is not this -- the text is somebody's words and
/// belongs in a bubble.
export function isCardOnly(parts: readonly unknown[]): boolean {
  return parts.length > 0 && parts.every(isCardPart);
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

/// The `data` parts a rebuilt message carries, by the id of the message holding
/// them. Only the parts this module owns count.
function cardsById(messages: readonly unknown[]): Map<string, DataPart> {
  const found = new Map<string, DataPart>();
  for (const message of messages) {
    if (typeof message !== "object" || message === null) continue;
    const { id, content } = message as { id?: unknown; content?: unknown };
    if (typeof id !== "string" || !Array.isArray(content)) continue;
    const part = content.find(isCardPart);
    if (part !== undefined) found.set(id, part as DataPart);
  }
  return found;
}

/// The rebuilt messages, with the injection cards PUT BACK.
///
/// `fromAgUiMessages` (upstream's, quoted in `app.tsx`) rebuilds text, reasoning and
/// tool calls -- and drops a `data` part on the floor, because `toAssistantSnapshotMessage`
/// has no case for it. So a card message comes out of a refresh EMPTY: the row is gone,
/// and the conversation looks like it never carried anything. This restores exactly that
/// part, matched BY ID, and leaves every other message alone.
///
/// THE TWO ROLES A CARD COMES BACK AS, and the reader below should not assume either: a
/// card this run DERIVED for itself comes back as an `assistant` message (it is a frame
/// of that run), and the OPENING's cards come back as `user` ones (they are entries the
/// birth wrote, `harness.edge.ag_ui/opening-entries`). Whichever it is, the part was
/// dropped the same way and `isCardOnly` decides how the message that holds it is drawn.
///
/// BY ID RATHER THAN BY INDEX, because the conversion is not one-message-in-one-out:
/// a tool result is patched into the assistant message before it rather than pushed,
/// so an index carry-over would put one turn's card on another turn's message.
export function keepInjectionCards(
  rebuilt: readonly unknown[],
  converted: readonly ThreadMessageLike[],
): ThreadMessageLike[] {
  const cards = cardsById(rebuilt);
  if (cards.size === 0) return [...converted];
  return converted.map((message) => {
    const card = message.id === undefined ? undefined : cards.get(message.id);
    return card === undefined ? message : ({ ...message, content: [card] } as ThreadMessageLike);
  });
}
