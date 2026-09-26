// What this page is called, and the mark that says it: the sidebar's top row.
//
// ---------------------------------------------------------------- the shape
//
// ONE ROW, TWO THINGS, AND THE SECOND IS NOT THIS FILE'S: the mark and the name
// here, then -- at the row's end, pushed there by the sidebar -- the control that
// folds this column away. That pairing is the one thing borrowed from the demo this
// header copies (`assistant-ui.com`'s embedded chat: a 3rem row whose leading cell
// holds the mark and the wordmark and whose trailing edge holds the collapse
// button), and it is a statement about where a person looks for the way out of a
// panel: next to the thing that names the panel.
//
// IT IS A ROW AND NOT A LINK. Every other brand mark in the world sits inside an
// `<a href="/">`, and this one must not: this product has exactly one page, so the
// link would go nowhere -- and a control that looks like it navigates and does not
// is worse than a word. There is nothing to click, so there is nothing to draw as
// clickable.
//
// ------------------------------------------------------------------- the mark
//
// TWO ARCS AND A DOT, reading as `( • )`: the host language's own syntax around a
// kernel, which is what this thing is. It is an inline SVG rather than a file
// because the repo has no image pipeline and no `ui/public/` at all -- and because
// `currentColor` means it follows the theme for free, in both directions, with no
// second asset to keep in step.
//
// IT IS `aria-hidden`, AND THE NAME BESIDE IT IS WHY. A mark whose only content is
// "we have a logo" is not information; the `<span>` next to it is the name, read
// once, by the eye and by a screen reader. An `aria-label` on the SVG would make a
// screen reader say the product name twice in a row.
//
// --------------------------------------------------------- why not the catalog
//
// THE NAME IS A CONSTANT AND NOT A `t(...)` KEY, deliberately. `clj-harness` is a
// proper noun: the Chinese catalog would hold the identical string, the i18n suite
// would pin the two files to each other over a value nothing may ever translate, and
// the next translator would have to be told not to touch it. A thing that cannot be
// translated does not belong in a translation table.
//
// THE CONSTANT ITSELF IS NOT DECLARED HERE, because the row's wordmark and the tab's
// tail (`<title> · clj-harness`) are the same word said in two places: it lives in
// `lib/session-title.ts`, which is also the module that spells the tab's title, and
// this file imports it. Two constants would be two things to keep in step.
import type { FC } from "react";

import { PRODUCT_NAME } from "@/lib/session-title";

/// The mark and the name, in that order. The caller decides what sits after them.
///
/// `compact` IS THE RAIL'S FORM: the mark alone, because a 48px-wide column has room for
/// one thing and the mark is the thing that survives being 16px wide (the name would be
/// truncated to a letter). It is a PROP rather than a second component so that there is
/// one drawing of the mark: the rail and the full column differ in whether the wordmark
/// is said, not in what the product looks like.
export const AppBrand: FC<{ compact?: boolean }> = ({ compact = false }) => (
  <>
    <svg
      data-slot="brand-mark"
      aria-hidden="true"
      viewBox="0 0 16 16"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.5"
      strokeLinecap="round"
      className="text-foreground size-4 shrink-0"
    >
      {/* The two arcs, mirrored about the middle: `M6 3.5A5 5 0 0 0 6 12.5` is a
          half-circle opening to the right, and its mirror image opens to the left. */}
      <path d="M6 3.5A5 5 0 0 0 6 12.5" />
      <path d="M10 3.5A5 5 0 0 1 10 12.5" />
      {/* The kernel. */}
      <circle cx="8" cy="8" r="1.6" fill="currentColor" stroke="none" />
    </svg>
    {!compact && (
      <span data-slot="brand-name" className="truncate text-[13px] font-medium">
        {PRODUCT_NAME}
      </span>
    )}
  </>
);
