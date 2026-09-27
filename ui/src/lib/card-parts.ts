// The CARD PARTS this app draws in the conversation column, and the three questions asked about
// them -- asked of a PART and not of one card's name.
//
// WHAT A CARD IS. Two things the server wants a person to see travel as an AG-UI `CUSTOM` frame,
// which the adapter turns into a `data` part: an INJECTED CONTEXT -- the instruction blocks, a
// skill body, the ending of a background job (`lib/injections.ts`) -- and a COMPACTION -- the
// summary that now stands where a folded range stood (`lib/compactions.ts`). Neither is a message
// the model was handed as a turn of its own, and `toAgUiMessages` has no case for a `data` part, so
// a card is visible in the conversation and is never sent back to the server.
//
// WHY THE NAMES LIVE IN ONE PLACE. Three rules are about CARDS rather than about either kind:
//
//   `isCardPart`     is this part one of ours?
//   `isCardOnly`     is this message ONLY cards, so the thread must not draw it as a bubble?
//   `keepCardParts`  put the parts back into a rebuilt conversation, by id.
//
// Each of them, written once per name, would be a rule that can disagree with itself -- and the app
// draws a card-only message through the ONE renderer the part's name selects, so a name missing
// from this list is a card drawn as an empty bubble that says nothing (which is exactly the bug
// this module's list exists to make impossible).
//
// RUNTIME-ZERO IMPORTS, like the two modules that own the names: the UI suite pins all of this as
// arithmetic over literals, and the drawing itself is measured in a real browser.
import type { ThreadMessageLike } from "@assistant-ui/react";

import { COMPACTION_PART } from "./compactions";
import { INJECTION_PART } from "./injections";

/// The part shape this module puts back: a `data` part by name (assistant-ui's `DataMessagePart`,
/// spelled here so the module needs no runtime import).
type DataPart = {
  readonly type: "data";
  readonly name: string;
  readonly data: unknown;
};

/// THE NAMES, and membership in this list is the whole test for "is this part a card".
///
/// BOTH ENDS SPELL THEM, and the server's copy is the authority: `harness.edge.ag_ui`'s
/// `injected-part-name` / `compacted-part-name` are imported by the two modules above rather than
/// retyped, so the two ends cannot drift about a name.
export const CARD_PARTS: readonly string[] = [INJECTION_PART, COMPACTION_PART];

/// Is this one of the cards this module owns? The NAME is the whole test.
export function isCardPart(part: unknown): boolean {
  return (
    typeof part === "object" &&
    part !== null &&
    (part as { type?: unknown }).type === "data" &&
    CARD_PARTS.includes((part as { name?: unknown }).name as string)
  );
}

/// Is this message the card and NOTHING ELSE?
///
/// THE QUESTION ROLE CANNOT ANSWER. The opening's entries are `role: "user"` -- user messages to the
/// provider, and the record says so -- so the thread would otherwise draw them the way it draws
/// anything a person typed: right-aligned, in a grey bubble, with an Edit pencil beside them.
/// Nobody typed them; the server wrote them at the session's birth. What separates the two is that
/// this message is a card and no words, which is what this answers.
///
/// NOT `keepCardParts`'s JOB, and the two are easy to confuse: that one puts back a card the rebuild
/// dropped, this one decides how the message HOLDING a card is drawn. A message with a card AND
/// text beside it is not this -- the text is somebody's words and belongs in a bubble.
export function isCardOnly(parts: readonly unknown[]): boolean {
  return parts.length > 0 && parts.every(isCardPart);
}

/// The `data` parts a rebuilt message carries, by the id of the message holding them. Only the
/// parts this module owns count.
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

/// The rebuilt messages, with the cards PUT BACK.
///
/// `fromAgUiMessages` (upstream's, quoted in `app.tsx`) rebuilds text, reasoning and tool calls --
/// and drops a `data` part on the floor, because `toAssistantSnapshotMessage` has no case for it.
/// So a card message comes out of a refresh EMPTY: the row is gone, and the conversation looks like
/// it never carried anything. This restores exactly that part, matched BY ID, and leaves every
/// other message alone.
///
/// THE TWO ROLES A CARD COMES BACK AS, and the reader below should not assume either: a card a run
/// DERIVED for itself comes back as an `assistant` message (it is a frame of that run -- an
/// injection, or a compaction), and the OPENING's cards come back as `user` ones (they are entries
/// the birth wrote, `harness.edge.ag_ui/opening-entries`). Whichever it is, the part was dropped
/// the same way and `isCardOnly` decides how the message that holds it is drawn.
///
/// BY ID RATHER THAN BY INDEX, because the conversion is not one-message-in-one-out: a tool result
/// is patched into the assistant message before it rather than pushed, so an index carry-over would
/// put one turn's card on another turn's message.
export function keepCardParts(
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
