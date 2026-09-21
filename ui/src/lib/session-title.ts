// A SESSION'S TITLE: how much of what somebody said fits in a name, and nothing else.
//
// --------------------------------------------------------- what a title is here
//
// THE FIRST THING THE USER SAID, and not any of the things a title usually is. It is
// also NOT a rename: this product still has no verb for that.
//
// IT COMES FROM TWO PLACES, WHICH IS THE ONE THING TO KNOW ABOUT THIS FILE.
// `sessions.title` holds it -- written once, by the first run that arrives, so a
// session that has been spoken to is named for every browser, every machine and every
// reload (`harness.infra.db/sessions-remember-their-title` has the whole argument for
// why a store that may not hold conversation content holds this). The sidebar's rows
// read that copy. The OTHER copy is the live one this module derives from a
// conversation the runtime is holding (`firstUserText`), which is what the TOP BAR
// uses -- `components/session-title.tsx` has the runtime, so it asks the runtime --
// and what a row uses while this page is the thing holding that session, so the first
// message shows up on the row it was typed into rather than after the next listing.
//
// BOTH GO THROUGH `titleOf`, so there is still exactly ONE clip, ONE tidy and ONE
// idea of when a title is absent -- which is what the two sources cost and what
// keeps them from drifting apart in what a person actually reads.
//
// (THIS FILE USED TO ARGUE THE OPPOSITE -- 'the title is stored nowhere, and not
// storing it is the point'. The owner reversed that on 2026-09-21 after seeing both
// prices; the reversal and its reasons are in the migration step and in
// `.scratch/session-titles-in-the-store/spec.md`.)
//
// ------------------------------------------------------------- runtime-zero
//
// NO IMPORTS BUT TYPES, like `lib/turns.ts` and `lib/injections.ts`: the suite pins
// this as arithmetic over literal message lists (`test/suites/session-title.ts`)
// instead of through a rendered thread, and the one import below is a TYPE the
// compiler erases. The drawing is a browser's question (`.scratch/brand-header/`).

/// The product's name, as a CONSTANT AND NOT A `t(...)` KEY: a proper noun cannot be
/// translated, so it does not live in a translation table -- see
/// `components/app-brand.tsx`, which draws the same word in the sidebar's brand row
/// and imports it from here rather than spelling it twice.
export const PRODUCT_NAME = "clj-harness";

/// HOW MUCH OF THE FIRST SENTENCE A TITLE HOLDS, in CODE POINTS.
///
/// The clip is not cosmetic, and it is not the CSS one. A browser tab has no
/// ellipsis: the string put in `<title>` is the string the tab shows, so a title
/// derived from a pasted stack trace would be a title with no end. The bar in the
/// conversation column truncates again (`truncate`, for a narrow window), but the
/// bound that matters is this one, and it has to be applied to the VALUE -- which is
/// also what keeps the row from re-rendering for every token of a growing message
/// (`useAuiState` compares a selector's answer by value; a string is the answer).
export const TITLE_MAX = 60;

/// The part shape this module reads: the text part of a user message. Structural on
/// purpose -- the runtime's `PartState` satisfies it, and so does a literal in a test.
type TitledMessage = {
  readonly role: string;
  readonly parts?: readonly { readonly type: string; readonly text?: string }[] | undefined;
};

/// A RAW THING SOMEBODY SAID -> THE TITLE TO DRAW FROM IT, or null when it says
/// nothing (`titleOf(null)` is null, and so is a message that is all whitespace).
///
/// THE ONE PLACE THE RULE LIVES, and it has two callers on purpose: the server's
/// stored copy (`SessionSummary.firstUserText`, raw and up to 200 code points) and
/// the live conversation in a runtime (`firstUserText` below). Both are just text
/// somebody typed, so both are tidied and clipped here rather than in the row that
/// happens to draw one of them.
export function titleOf(said: string | null | undefined): string | null {
  const text = tidy(said ?? "");
  return text === "" ? null : clip(text);
}

/// THE FIRST THING THIS PERSON SAID, cleaned up, or null when they have said nothing.
///
/// THE FIRST *TEXT* PART, AND NOT THE FIRST USER MESSAGE. A user message can be an
/// image and nothing else -- the composer takes pasted pictures -- and stopping at it
/// would leave a conversation that has been going for an hour looking untitled. So
/// the walk is over every user message in order, and the first one carrying a
/// non-blank text part is the answer. Injected context does not interfere: it arrives
/// as a `data` part (`lib/injections.ts`), which is not text and is skipped by the
/// same test. Assistant and system messages are skipped by role.
///
/// WHY null IS A SEPARATE ANSWER FROM THE FALLBACK: the caller has to be able to tell
/// "this conversation has no title yet" from "its title is the word New session" --
/// the first is a fact about the session, the second is a sentence in a language, and
/// only the caller holds the translator. So this returns null and `sessionTitle`
/// below takes the words.
export function firstUserText(messages: readonly TitledMessage[]): string | null {
  for (const message of messages) {
    if (message.role !== "user") continue;
    for (const part of message.parts ?? []) {
      if (part.type !== "text") continue;
      const title = titleOf(part.text);
      if (title !== null) return title;
    }
  }
  return null;
}

/// The title to draw, and the one case where a word is invented rather than quoted.
///
/// `untitled` IS A PARAMETER AND NOT A KEY READ HERE for the reason `firstUserText`
/// returns null: this module imports nothing but types, so the sentence a person
/// reads comes from the caller's translator (`t("session.untitled")`) -- the same
/// shape `lib/session-status.ts` uses for its refusals.
///
/// A SESSION WITH NO WORDS IS `New session` RATHER THAN ITS ID. The id is a
/// 36-character address, not a name, and it says nothing about what this column is
/// for; the word the shell catalog already has for this state says everything.
///
/// THE ROW IS THE OTHER WAY ROUND (`thread-list.aui.tsx` falls back to the ID), and
/// the difference is which question each one is answering: the bar names the
/// conversation you are looking at, and a row has to identify one session among
/// forty. A row reading `New session` twenty times identifies nothing.
export function sessionTitle(messages: readonly TitledMessage[], untitled: string): string {
  return firstUserText(messages) ?? untitled;
}

/// WHAT GOES IN THE TAB: the session's title, then whose page this is.
///
/// THE TAIL IS THE PRODUCT, and it is there because a browser window is where several
/// pages sit side by side: a tab reading only "帮我看看这个仓库" says nothing about
/// which application it belongs to, and a person with three of them open cannot tell
/// them apart. The separator is a middle dot rather than a dash: a title like
/// "hello - a note on dashes" would otherwise read as if the product were named
/// "a note on dashes".
export function documentTitle(title: string): string {
  return `${title} · ${PRODUCT_NAME}`;
}

/// Whitespace, collapsed and trimmed: what a title does with a message typed by a
/// person. A first line that opens with two newlines is ordinary, and a title that
/// opens with them is a title with no text in it as far as a tab is concerned.
function tidy(text: string): string {
  return text.replace(/\s+/gu, " ").trim();
}

/// The clip, BY CODE POINT rather than by UTF-16 unit, and that is the whole reason
/// this is a function instead of a `slice`.
///
/// `"😀".length` is 2 and `"好".length` is 1: slicing a string at 60 units can land
/// in the middle of a surrogate pair, and half a pair is a replacement character in
/// the tab -- a title that ends in a black diamond. `Array.from` walks the string the
/// way a reader does, one character at a time.
function clip(text: string): string {
  const points = Array.from(text);
  return points.length > TITLE_MAX ? `${points.slice(0, TITLE_MAX).join("")}…` : text;
}
