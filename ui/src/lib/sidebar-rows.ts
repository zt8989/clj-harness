// HOW MANY ROWS A BLOCK OF THE SIDEBAR DRAWS, and which ones.
//
// ------------------------------------------------------------ the two rules
//
// A LIST IS FOR FINDING THE ONE YOU WANT, and past a handful of rows the newest ones
// -- which are the ones being looked for, by every account of how this sidebar is used
// -- are pushed off the fold by history nobody asked to see. So a block draws
// `ROWS_BEFORE_FOLD` rows and says how many it is holding back.
//
// ONE EXCEPTION, AND IT IS THE WHOLE REASON THIS IS A FUNCTION RATHER THAN A `.slice`:
// the row being READ must never be the one that got folded away. A project already
// opens itself for the current session (`ProjectSection`), and this is the same rule
// one level in -- if the current session sorts past the fold, that block draws in full.
// `forced` reports that this is WHY it is open, because the disclosure control must not
// be drawn then: a control whose only effect is to hide the row you are reading is
// worse than no control.
//
// ------------------------------------------------------------------ the seam
//
// ZERO IMPORTS, like `lib/relative-time.ts` and `lib/session-title.ts`, and for the
// same reason: `components/sidebar.tsx` cannot be executed in the vitest run at all (it
// reaches `lib/i18n.ts`, which touches `document`), so everything about this rule that
// CAN be pinned has to be pinnable without a render. What is left for the browser is
// the two things a suite cannot see: where the control lands on the line, and whether
// clicking it draws the rows (`.scratch/sidebar-five-rows/walkthrough.mjs`).

/// HOW MANY ROWS A BLOCK DRAWS BEFORE IT FOLDS. Five, because that is the number the
/// owner asked for in so many words ("最多展示 5 个会话"), and because five rows of a
/// 16px-line list is a block you can still compare at a glance.
export const ROWS_BEFORE_FOLD = 5;

/// WHAT TO DRAW, HOW MANY ARE HELD BACK, AND WHY IT IS OPEN.
export type FoldedRows<T> = {
  /// THE ROWS TO DRAW, in the order they were given: the first `ROWS_BEFORE_FOLD`, or
  /// all of them. A new array is not built when nothing is folded -- the caller may
  /// compare identity, and an unchanged list should not look changed.
  readonly drawn: readonly T[];
  /// HOW MANY ARE BEHIND THE FOLD, which is the number the control says out loud
  /// (`还有 N 个`). ZERO WHEN NOTHING IS FOLDED -- so it is not the caller's condition for
  /// drawing the control (an OPEN block folds nothing and still needs its "收起"), which
  /// is what `foldable` is for.
  readonly hidden: number;
  /// WHETHER THIS BLOCK HAS A FOLD AT ALL: more rows than `ROWS_BEFORE_FOLD`. The caller
  /// draws the control when this is true and `forced` is false, and reads `hidden` only for
  /// the label -- the two questions "is there a fold" and "how many are in it right now"
  /// are different, and conflating them leaves an open block with no way to close.
  readonly foldable: boolean;
  /// TRUE WHEN THE CURRENT SESSION SITS PAST THE FOLD: the block is open for it and the
  /// caller must draw NO control (see below). It says nothing about `wantsAll`, which may
  /// be true at the same time.
  readonly forced: boolean;
};

/// THE RULE. `isCurrent` is a predicate rather than an id so this stays free of the
/// listing's shape (a task and a project session are the same row with the same id, and
/// a caller that wants "the row being read" can say so however it holds it).
///
/// `wantsAll` IS THE PERSON'S ANSWER, and the current session outranks it: a block
/// opened by hand stays open, but a block whose current session is past the fold is open
/// regardless — including when the person had ALREADY opened it and then selected a row
/// past the fifth. That case is why `forced` is not conditioned on `!wantsAll`: with a
/// control drawn there, pressing it would fold away the row being read, which is the one
/// thing this function exists to prevent.
export function foldRows<T>(
  rows: readonly T[],
  isCurrent: (row: T) => boolean,
  wantsAll: boolean,
): FoldedRows<T> {
  if (rows.length <= ROWS_BEFORE_FOLD) {
    return { drawn: rows, hidden: 0, foldable: false, forced: false };
  }
  const forced = rows.slice(ROWS_BEFORE_FOLD).some(isCurrent);
  if (forced || wantsAll) {
    return { drawn: rows, hidden: 0, foldable: true, forced };
  }
  return {
    drawn: rows.slice(0, ROWS_BEFORE_FOLD),
    hidden: rows.length - ROWS_BEFORE_FOLD,
    foldable: true,
    forced: false,
  };
}
