// What the COMPACTION card says, as arithmetic over the frame the server sent.
//
// WHAT A COMPACTION IS. The harness folds the front of a long conversation into ONE summary, and
// from then on that summary is what the MODEL reads where those messages stood
// (`harness.edge.compaction`, `harness.edge.replay/model-nodes`). Nothing about the conversation a
// person reads back changes -- and until this card existed, nothing on screen said the model's view
// had moved either.
//
// SO THE CARD IS ABOUT THE SURFACE, NOT ABOUT A MESSAGE. The frame's value is not the
// `{role, text}` an injection carries: it is the summary, what the folded range was estimated at,
// and how many nodes went into it. This module is the arithmetic the row and the body draw from.
//
// AND A COMPACTION THAT FAILED IS THE SAME CARD (owner, 2026-10-05). The trigger fires, the summary
// call is made, and the vendor may say no -- an exhausted quota, a refusal for length, a summary that
// came back no smaller than the range it would replace. Every one of those used to leave the page
// showing a full context ring and NOTHING ELSE: measured on a real session, two whole runs at 74% of
// a 1M window with a `HTTP 429` in the log and no card on screen, because the frame was only ever
// built on the success path. So this module reads TWO shapes off one part name, and what tells them
// apart is the server's own `outcome` -- never the absence of a summary, which is also what a
// malformed frame looks like.
// THE OTHER HALF IS SHARED (`lib/card-parts.ts`): that this part is a card, that a message holding
// only cards is not a bubble, and that a rebuilt conversation gets it back by id. Those answers are
// the same for the injection card, so they are written once, over the name list.
//
// RUNTIME-ZERO IMPORTS, like `lib/injections.ts` and for the same reason: the UI suite pins this as
// arithmetic over literals, and the drawing is measured in a real browser.

/// The `data` part's name, on both ends: the server names the frame's part `compacted-context`
/// (`harness.edge.ag_ui/compacted-part-name`) and this module is what looks it up.
export const COMPACTION_PART = "compacted-context";

/// The values the server puts in `outcome`. Spelled as constants because they are compared against,
/// not rendered: the two ends must agree on them (`harness.edge.ag_ui/compacted-pending-frame` and
/// its failed twin write them), and a literal at each comparison site is how a rename lands on one
/// side only.
///
/// `pending` IS THE START ROW, and it has no `summary` of its own -- the result row below it is a
/// SEPARATE frame, because a `data` part can only be appended to the conversation, never updated in
/// place (see `@assistant-ui/react-ag-ui`'s run aggregator). That is the whole reason there are two
/// rows rather than one row that changes: this is what the adapter can do.
const PENDING_OUTCOME = "pending";
const FAILED_OUTCOME = "failed";

/// The frame's value, as the three builders on the server side write it. Every field is `unknown`
/// because this is what ARRIVED, not what we hoped for.
export type CompactionValue = {
  readonly summary?: unknown;
  readonly tokens?: unknown;
  readonly messages?: unknown;
  /// Which of the three this is. ABSENT on a success, and that absence IS the success discriminant:
  /// the folded shape has been on the wire unchanged since the card existed, and a second spelling
  /// of 'it worked' would be one more branch for the two to drift in.
  readonly outcome?: unknown;
  /// Why it failed, as the vendor's own words (`compaction/end`'s `:error`). Null is a failure that
  /// said nothing, which is drawn as the sentence alone rather than as an empty body.
  readonly error?: unknown;
};

/// WHICH OF THE THREE THIS CARD IS. One name, not a boolean, because the row says it in words and a
/// word is what a person reads: the mark, the label and the body all ask this question.
///
/// `pending` IS A STATE, NOT A STAGE OF A PROGRESS BAR, and that is deliberate. The row is folded
/// into the conversation like any other card, so it is STILL THERE after the compaction lands; a
/// spinner on a row that outlives the work would be claiming something false from the moment the
/// result row appears below it. What says 'still going' is the ABSENCE of that result row -- which is
/// how a bash row reads too.
export type CompactionOutcome = "pending" | "folded" | "failed";

/// What the card draws, once the value has been read.
export type CompactionView = {
  /// WHICH HALF OF THE CARD THIS IS, and it is what the row's own name hangs on.
  readonly outcome: CompactionOutcome;
  /// The summary's first non-empty line. The row is one line and truncation is the browser's; the
  /// body is where the whole summary is read.
  readonly preview: string;
  /// What the folded range was estimated at, or null when the frame did not say -- and null is
  /// drawn as nothing rather than as a zero (see below).
  readonly tokens: number | null;
  /// How many nodes went into the summary, or null.
  readonly messages: number | null;
  /// The reason, for a failure. Null on a success, and null for a failure that carried none -- the
  /// body then says what happened without pretending to know why.
  readonly error: string | null;
};

/// A non-negative finite whole number, or null for anything else.
///
/// A FRAME THAT DID NOT SAY MUST NOT BECOME A ZERO: `0 tok` is a claim about the size of the folded
/// range, and a card that invented one over a frame missing the field would be lying about the one
/// thing it exists to report.
function countOrNull(value: unknown): number | null {
  return typeof value === "number" && Number.isInteger(value) && value >= 0 ? value : null;
}

/// A frame's value -> what the row says, or null when there is nothing to draw.
///
/// THREE CARDS, ONE READER, and the discriminant is the server's own `outcome` rather than the
/// absence of a summary: a start row and a failure row both carry no summary, and so does a malformed
/// one, so guessing from a missing field is how a card ends up drawing 'folded' for a compaction that
/// never happened. The server says which it is.
///
/// NULL IS AN ANSWER for a fourth case: an `outcome` WE DO NOT KNOW, or a frame with neither field --
/// drawing a row for either would claim a compaction nobody can check. An unknown outcome is refused
/// rather than read as 'it worked', which is the same discipline as a content part of a type this
/// harness does not carry (`lib/attachments.ts`): a value that ARRIVED is not a value we can draw.
///
/// NEITHER THE START NOR THE FAILURE CARRIES A NUMBER, and that is not a gap: neither attempt got as
/// far as a range, so every number would be invented. A failure's body is the reason, in the vendor's
/// own words.
export function compactionView(value: unknown): CompactionView | null {
  if (typeof value !== "object" || value === null) return null;
  const v = value as CompactionValue;
  // THE START ROW. It carries nothing but its own existence, and the card draws the sentence --
  // there is no preview because there is nothing yet to preview.
  if (v.outcome === PENDING_OUTCOME) {
    return {
      outcome: "pending",
      preview: "",
      tokens: null,
      messages: null,
      error: null,
    };
  }
  // THE FAILURE ROW. The error is what makes the row worth drawing at all, and it is read as text
  // because that is what the server sent (`compaction/end`'s `:error`, verbatim vendor words).
  if (v.outcome === FAILED_OUTCOME) {
    const error = typeof v.error === "string" && v.error.trim() !== "" ? v.error : null;
    return {
      outcome: "failed",
      preview: error ?? "",
      tokens: null,
      messages: null,
      error,
    };
  }
  // AN OUTCOME THAT IS NOT OURS IS NOT A CARD -- asked positively, so a new server-side value
  // stops here instead of being drawn as a fold that did not happen.
  if (v.outcome !== undefined) return null;
  // THE SUCCESS ROW, unchanged in every particular: the summary is what the card IS (the bytes the
  // model now reads in the folded range's place), so a frame without one is not a card, while the
  // two numbers are only how much it cost -- worth drawing when the server said, never invented.
  if (typeof v.summary !== "string" || v.summary.trim() === "") return null;
  return {
    outcome: "folded",
    preview: firstLine(v.summary),
    tokens: countOrNull(v.tokens),
    messages: countOrNull(v.messages),
    error: null,
  };
}

/// The first non-empty line of TEXT, as the row's preview. Empty when there is none.
function firstLine(text: string): string {
  for (const line of text.split("\n")) {
    if (line.trim() !== "") return line.trim();
  }
  return "";
}

/// The whole summary a card's value carries, as the body prints it -- or the empty string, which
/// draws no body at all.
///
/// A FAILURE HAS NO SUMMARY, so this answers the empty string for it too, and the body draws its
/// own sentence and the reason instead (see `compaction-card.tsx`). Kept as one function rather than
/// two so the caller cannot reach past the discriminant: a card that read the summary directly for
/// one outcome and this for the other is two places to forget which is which.
export function compactionText(value: unknown): string {
  if (typeof value !== "object" || value === null) return "";
  const summary = (value as CompactionValue).summary;
  return typeof summary === "string" ? summary : "";
}
