// HOW MANY ROWS A SIDEBAR BLOCK DRAWS: the arithmetic behind "最多 5 个，其他折叠", and the
// one exception that keeps the row you are reading from being folded away.
//
// PURE, in the shape `relative-time.ts` and `session-title.ts` established:
// `src/lib/sidebar-rows.ts` imports nothing, so a case is literals in and three fields
// out -- no render, no listing, no i18n. That seam is not a preference here, it is the
// only one available: `components/sidebar.tsx` cannot be imported into this run at all
// (it reaches `lib/i18n.ts`, which touches `document`), which is why the two callers can
// only be checked by a browser.
//
// WHAT THIS SUITE CANNOT SEE: where the control lands on the line, whether pressing it
// draws the rows, and whether "已归档" stayed unlimited. Those are
// `.scratch/sidebar-five-rows/walkthrough.mjs` -- which is also where the two callers
// (the tasks block and a project) are actually exercised.
import { expect } from "vitest";

import { type Case, type Suite } from "../e2e";
import { ROWS_BEFORE_FOLD, foldRows } from "../../src/lib/sidebar-rows";

/// A ROW WITH AN IDENTITY, because the rule is stated in terms of "the row being read":
/// ids are what the sidebar compares (`threadId`), so rows here carry one.
type Row = { id: string };

const rows = (n: number): Row[] => Array.from({ length: n }, (_, i) => ({ id: `s${i + 1}` }));
const idsOf = (drawn: readonly Row[]): string[] => drawn.map((row) => row.id);
const isCurrent = (id: string) => (row: Row) => row.id === id;
const none = () => false;

const cases: Case[] = [
  {
    name: "a-block-draws-five-rows-and-says-how-many-it-is-holding-back",
    run: async () => {
      // THE FOLD IS AT FIVE, and it is the constant rather than a literal repeated here:
      // the owner asked for five, and a suite that spells "5" a second time would go green
      // on a change that altered the product's behaviour.
      expect(ROWS_BEFORE_FOLD).toBe(5);

      // UP TO AND INCLUDING FIVE: nothing is held back, so there is nothing to say and no
      // control to draw (`hidden === 0` is the caller's whole condition).
      for (const n of [0, 1, 4, 5]) {
        const folded = foldRows(rows(n), none, false);
        expect(folded).toEqual({ drawn: rows(n), hidden: 0, foldable: false, forced: false });
        expect(idsOf(folded.drawn)).toEqual(rows(n).map((row) => row.id));
      }

      // SIX IS THE FIRST ROW THAT FOLDS: five drawn, ONE held back -- and the count is the
      // rows behind the fold, not the total the block holds.
      const six = foldRows(rows(6), none, false);
      expect(idsOf(six.drawn)).toEqual(["s1", "s2", "s3", "s4", "s5"]);
      expect(six.hidden).toBe(1);
      expect(six.foldable).toBe(true);
      expect(six.forced).toBe(false);

      // AND IT IS THE SAME NUMBER FORTY ROWS LATER: the control says 35, and the row that
      // fell off the end is the one that had been there longest.
      const many = foldRows(rows(40), none, false);
      expect(many.drawn).toHaveLength(ROWS_BEFORE_FOLD);
      expect(many.hidden).toBe(35);
      expect(idsOf(many.drawn)).not.toContain("s40");
    },
  },
  {
    name: "the-row-being-read-is-never-the-one-that-folds-away",
    run: async () => {
      // INSIDE THE FIRST FIVE: nothing is forced. The row is already drawn, so a block has
      // no reason to be open for it -- and a block that opened anyway would make the last
      // five rows of a project impossible to fold, since one of them is always current.
      for (const id of ["s1", "s3", "s5"]) {
        const folded = foldRows(rows(9), isCurrent(id), false);
        expect(folded.forced).toBe(false);
        expect(folded.hidden).toBe(4);
      }

      // PAST THE FOLD: the block draws in full and says WHY (`forced`), which is the flag
      // the caller uses to leave the control out. The two boundary rows are the whole
      // point of the case: the fifth is inside, the sixth is not.
      const sixth = foldRows(rows(9), isCurrent("s6"), false);
      expect(idsOf(sixth.drawn)).toEqual(rows(9).map((row) => row.id));
      expect(sixth.hidden).toBe(0);
      expect(sixth.forced).toBe(true);

      const last = foldRows(rows(9), isCurrent("s9"), false);
      expect(last.forced).toBe(true);
      expect(last.drawn).toHaveLength(9);

      // AND A ROW THAT IS NOT IN THIS BLOCK FORCES NOTHING -- the current session is a
      // task while some project is being listed, which is the ordinary case and must not
      // unfold every project on the page.
      expect(foldRows(rows(9), isCurrent("elsewhere"), false).forced).toBe(false);
    },
  },
  {
    name: "a-block-opened-by-hand-stays-open-and-the-current-row-still-outranks-it",
    run: async () => {
      // OPENED BY HAND: every row, nothing held back, and NOT forced -- so the control is
      // still drawn and it says "收起" (the caller reads `open` from its own state, which
      // this function deliberately does not own). THAT IS `foldable`, and it is why the
      // caller cannot use `hidden > 0` as its condition: an open block folds nothing, and a
      // control that vanished the moment it was used could never be used again.
      const opened = foldRows(rows(12), none, true);
      expect(idsOf(opened.drawn)).toEqual(rows(12).map((row) => row.id));
      expect(opened.hidden).toBe(0);
      expect(opened.foldable).toBe(true);
      expect(opened.forced).toBe(false);

      // THE CASE THAT DECIDED THE SHAPE OF THIS FUNCTION: the block was opened by hand AND
      // the session being read is past the fold. Drawing a control here would offer a
      // "收起" whose only effect is to hide the row you are reading -- so `forced` is true
      // even though `wantsAll` is, and the caller draws nothing.
      const both = foldRows(rows(12), isCurrent("s8"), true);
      expect(both.forced).toBe(true);
      expect(both.foldable).toBe(true);
      expect(both.drawn).toHaveLength(12);

      // AND THE PERSON'S ANSWER IS NOT THROWN AWAY WHILE IT IS OVERRIDDEN: once the reader
      // moves back into the first five, the block is still open (their click is not
      // undone) and the control comes back.
      const back = foldRows(rows(12), isCurrent("s2"), true);
      expect(back.hidden).toBe(0);
      expect(back.forced).toBe(false);
    },
  },
  {
    name: "nothing-is-copied-when-nothing-is-folded",
    run: async () => {
      // IDENTITY IS PRESERVED on the two paths that draw everything: `slice` would build a
      // new array each render, and a caller that compares its rows by identity (or a memo
      // upstream) would see a new list every time for no reason. The fold path is the only
      // one that builds a new array, and it is also the only one that drops rows.
      const five = rows(5);
      expect(foldRows(five, none, false).drawn).toBe(five);
      const many = rows(9);
      expect(foldRows(many, none, true).drawn).toBe(many);
      expect(foldRows(many, isCurrent("s7"), false).drawn).toBe(many);
      expect(foldRows(many, none, false).drawn).not.toBe(many);
    },
  },
];

export const sidebarRowsSuite: Suite = { name: "sidebar-rows", cases };
