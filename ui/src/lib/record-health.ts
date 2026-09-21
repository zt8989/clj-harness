// What the page says when the record could not be written -- and the wire shape it
// says it from.
//
// ================================================================ why this file
//
// ADR 0002 DECISION 6: writing a session's record may be BEHIND, and it may not be
// SILENT. The server is where the failure is known -- `harness.edge.record` keeps
// the degraded thread, and the two reads the page looks at a conversation through
// carry it back as `record` (absent when there is nothing to say, which is the
// ordinary answer). This module is the page's half: the ONE place that wire shape
// becomes a sentence, so the banner and anything that later reads the same fact
// cannot word it two ways.
//
// IT CARRIES NO TEXT OF ITS OWN. The sentence is the catalog's, like every other
// sentence a person reads (`lib/session-status.ts` makes the same argument), and the
// translator arrives as a PARAMETER because this is not a component -- its caller
// already holds the shell translator.
//
// WHAT THIS FILE DELIBERATELY DOES NOT DECIDE: whether the SESSION may keep running.
// It may -- memory is the authority (ADR 0002) and a record that cannot be written
// is a record that is behind, not a session that has failed. Nothing here disables
// the composer or stops a run; all this decides is what to put on screen.
import type { TFunction } from "i18next";

/// The translator these sentences are worded through: the caller's own, which is the
/// `shell` face (the frame every other face is drawn inside, and where the sidebar's
/// own refusals live).
type Translate = TFunction<"shell">;

/// What a degraded record is, as the server says it: the writer gave up on a line,
/// why, and how many lines are waiting behind the one that failed.
///
/// `state` IS A UNION OF ONE TODAY, on purpose. The server sends the field only when
/// there is something to say, so `"degraded"` is the only value that can arrive -- and
/// spelling that out is what makes the code below exhaustive rather than a truthiness
/// test that would accept any string a future server invented.
export type RecordHealth = {
  state: "degraded";
  reason: string;
  pending: number;
  /// When the writer gave up, epoch milliseconds. Optional: the sentence does not use
  /// it, and a client that has an older server must not break on its absence.
  at?: number;
};

/// The sentence for a record that could not be written, or null when there is nothing
/// to say.
///
/// NULL IS THE COMMON CASE and it is not a failure: a session whose bytes are all on
/// disk has no story, and a page that said "saved" about every conversation would be
/// spending a line of chrome on a fact nobody asked. `count` is the waiting lines and
/// goes through i18next's plural forms -- "1 line is waiting" and "3 lines are
/// waiting" are one key with two spellings, not a sentence with a number glued in.
export const recordNotice = (
  t: Translate,
  record: RecordHealth | null | undefined,
): string | null => {
  if (record == null || record.state !== "degraded") return null;
  return t("record.degraded", { reason: record.reason, count: record.pending });
};
