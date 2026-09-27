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
// THE OTHER HALF IS SHARED (`lib/card-parts.ts`): that this part is a card, that a message holding
// only cards is not a bubble, and that a rebuilt conversation gets it back by id. Those answers are
// the same for the injection card, so they are written once, over the name list.
//
// RUNTIME-ZERO IMPORTS, like `lib/injections.ts` and for the same reason: the UI suite pins this as
// arithmetic over literals, and the drawing is measured in a real browser.

/// The `data` part's name, on both ends: the server names the frame's part `compacted-context`
/// (`harness.edge.ag_ui/compacted-part-name`) and this module is what looks it up.
export const COMPACTION_PART = "compacted-context";

/// The frame's value, as `harness.edge.ag_ui/compacted-frame` builds it. Every field is `unknown`
/// because this is what ARRIVED, not what we hoped for.
export type CompactionValue = {
  readonly summary?: unknown;
  readonly tokens?: unknown;
  readonly messages?: unknown;
};

/// What the card draws, once the value has been read.
export type CompactionView = {
  /// The summary's first non-empty line. The row is one line and truncation is the browser's; the
  /// body is where the whole summary is read.
  readonly preview: string;
  /// What the folded range was estimated at, or null when the frame did not say -- and null is
  /// drawn as nothing rather than as a zero (see below).
  readonly tokens: number | null;
  /// How many nodes went into the summary, or null.
  readonly messages: number | null;
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
/// NULL IS AN ANSWER, exactly as `injectionView`'s is: a frame with no summary is a card that would
/// say nothing, and drawing one would claim a compaction nobody can check. The caller renders
/// nothing instead.
///
/// THE SUMMARY IS REQUIRED AND THE TWO NUMBERS ARE NOT, and the asymmetry is deliberate: the summary
/// is what the card IS (the bytes the model now reads in the folded range's place), while the
/// numbers are how much that cost -- worth drawing when the server said, and never worth inventing.
export function compactionView(value: unknown): CompactionView | null {
  if (typeof value !== "object" || value === null) return null;
  const summary = (value as CompactionValue).summary;
  if (typeof summary !== "string" || summary.trim() === "") return null;
  return {
    preview: firstLine(summary),
    tokens: countOrNull((value as CompactionValue).tokens),
    messages: countOrNull((value as CompactionValue).messages),
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
export function compactionText(value: unknown): string {
  if (typeof value !== "object" || value === null) return "";
  const summary = (value as CompactionValue).summary;
  return typeof summary === "string" ? summary : "";
}
