// The two controls that fold the sidebar away and bring it back -- one verb, two
// places, and the reason they are a module instead of two scraps of JSX: the
// control that opens the sidebar lives in `app.tsx` (it is the page's floating
// corner, not the sidebar's business) and the control that closes it lives in
// `sidebar.tsx`, and THE PAIR IS ONE CONTRACT -- both name the same element
// through `aria-controls`, and each says which state it is asking for. Split
// across two files with a literal written twice, the contract is a string nobody
// checks; here the id is exported and the suite can read both sides of it.
//
// ------------------------------------------------------------------ the shape
//
// THE OPEN BUTTON HAS TWO PLACES, AND THE WINDOW DECIDES WHICH (`shape`), because a
// folded sidebar is two different things on either side of `lg`:
//
//   * `corner` (the default) IS THE PHONE'S: one control stays behind in the top-left
//     corner of the page, floating over the conversation column, because on a narrow
//     window the folded column is `display: none` -- there is no sidebar to hold it and
//     nothing else on screen to unfold it. It carries `z-40` so it sits above the
//     column it floats over, a border and a translucent background so it stays legible
//     over whatever is behind it, and it is drawn by the PAGE rather than by the
//     sidebar: a hidden subtree cannot draw a control that is meant to be seen. It is
//     `lg:hidden`, because on a wide window the column does not go away -- it becomes
//     the rail, and a floating button beside a sidebar that is already there would be
//     a second answer to a question nobody asked.
//   * `rail` IS THE COLUMN'S OWN: in the rail it lives in the 48px top cell, on top of
//     the mark, and appears when that cell is hovered or focused from the keyboard. It
//     is the same verb in a second place, so it is the same component rather than a
//     second one that could drift -- see `components/sidebar.tsx` for the cell and the
//     `group/brand` the swap hangs off.
//
// THE COLLAPSE BUTTON IS THE SIDEBAR'S BRAND ROW'S, at the trailing end of it, next to
// the mark and the product name -- because that is where a person looks for the way out
// of a panel, and because the row that says what the panel IS is the row an exit belongs
// in. It used to sit at the end of the header (the `New task` / add-project / refresh
// row); the row is still the sidebar's, and this module still owns the control's place
// in it. It is a control and not a drag handle: this product has no width preference to
// remember, so there is nothing for a drag to express that a click does not.
//
// ------------------------------------------------------- what is NOT remembered
//
// THE STATE IS NOT PERSISTED, deliberately, and that is the one decision worth
// arguing for. `lib/session-memory.ts` remembers WHICH SESSION the page was in
// across a reload, because that is a fact about the person's work. Whether the
// sidebar is folded is a fact about how they were looking at the page a moment
// ago, and it is answered by the window they are in -- so restoring it would
// surprise a reader who opened a narrow window after folding the sidebar on a
// wide one. The reference is the same call in the harness this borrows the
// pattern from: its layout store calls these "transient layout preferences".
//
// NOTHING HERE DECIDES HOW THE FOLD IS DRAWN, and the narrow presentation -- the
// sidebar floating over the conversation instead of narrowing it, with a backdrop
// behind it -- is a breakpoint in `sidebar.tsx`'s own classes and `app.tsx`'s. There
// is no resize listener to keep mounted for a fact CSS already has: the one thing
// this module reads the viewport for is the set of decisions CSS CANNOT make, and
// each is read at the moment it is made (`isWideWindow` names all three).
import { PanelLeftCloseIcon, PanelLeftIcon } from "lucide-react";
import { useTranslation } from "react-i18next";
import type { FC } from "react";

import { Button } from "@/components/ui/button";

/// THE ID BOTH CONTROLS POINT AT -- `aria-controls`, so that a screen reader
/// arriving at either button is told WHICH region it folds. It is the sidebar's
/// own element (`sidebar.tsx` sets it), and it is a constant here rather than a
/// literal in two files because a typo in one of them is a broken reference no
/// test in this repo could see (there is no DOM to resolve it against). The suites
/// render the two components and compare the strings instead.
export const SIDEBAR_ID = "app-sidebar";

/// THE WIDTH AT WHICH THE SIDEBAR STOPS BEING A COLUMN, spelled the way the CSS
/// spells it: `lg` in Tailwind v4 is `64rem`, so this query and the `lg:` classes in
/// `sidebar.tsx` / `app.tsx` are the SAME media query -- one breakpoint with two
/// readers, rather than two numbers that happen to agree today.
const WIDE_ENOUGH = "(min-width: 64rem)";

/// WHICH SHAPE THIS WINDOW HAS ROOM FOR: `true` beside the conversation (a column),
/// `false` over it (a drawer).
///
/// READ ONE SHOT, AT THE MOMENT SOMETHING NEEDS TO KNOW -- the first render, a pick, an
/// Escape -- so the answer is always this window's NOW and there is no listener to keep
/// mounted for a fact CSS already has. The DRAWING of the two shapes is still the `lg:`
/// classes and nothing here: both readers spell `64rem`, so there is one breakpoint.
///
/// THREE THINGS ASK, and each has to, because each is a thing that is right in one shape
/// and wrong in the other:
///
///   * the INITIAL state of the fold (`app.tsx`): a phone-sized window opens on the
///     conversation with the floating control in the corner, which is the whole of the
///     phone's shape -- the list is one tap away instead of being the thing you have to
///     dismiss first;
///   * whether a PICK has to close the panel it was made in (there is nothing to close
///     when the sidebar is a column beside the conversation);
///   * whether ESCAPE closes it, for the same reason.
///
/// NO WINDOW (this suite's node process) ANSWERS `true`: the answer that needs no viewport
/// to be the right one.
export function isWideWindow(): boolean {
  return typeof window === "undefined" ? true : window.matchMedia(WIDE_ENOUGH).matches;
}

/// The control that brings the sidebar back -- in the corner on a narrow window, in the
/// rail's top cell on a wide one.
///
/// ITS ACCESSIBLE NAME IS ITS `title` AND ITS `sr-only` SPAN, which is the pattern
/// every icon button in this shell already follows (see `sidebar.tsx`): the tooltip
/// is for the eye, the span is what a screen reader reads, and the suite reads the
/// same span because text is the one thing rendering to a string can assert.
///
/// BOTH SHAPES ARE ALWAYS IN THE ACCESSIBILITY TREE WHERE THEY ARE DRAWN, including the
/// rail's while it is invisible: the button is faded with `opacity`, not hidden, so a
/// keyboard arriving at it shows it (`focus-visible:opacity-100`) instead of leaving the
/// tab stop on something nobody can see. On a wide window the corpus of visible controls
/// is unchanged -- the pointer hovers the cell and the control is there.
export const SidebarOpenButton: FC<{
  onOpen: () => void;
  shape?: "corner" | "rail";
}> = ({ onOpen, shape = "corner" }) => {
  const { t } = useTranslation();
  return (
    <Button
      type="button"
      variant="ghost"
      size="icon"
      data-slot="sidebar-open"
      aria-controls={SIDEBAR_ID}
      // `aria-expanded` REPORTS THE STATE OF THE REGION IT NAMES -- not what pressing the
      // button does. This control is drawn only while the sidebar is folded, so the
      // region is collapsed and the honest reading is `false`; a screen reader then says
      // "collapsed, button", which is exactly what the person is looking at.
      aria-expanded={false}
      onClick={onOpen}
      title={t("sidebar.open")}
      className={
        shape === "corner"
          ? "border-border bg-background/80 hover:bg-muted absolute start-2 top-2 z-40 size-8 border p-0 shadow-sm backdrop-blur lg:hidden"
          : "text-muted-foreground hover:text-foreground absolute inset-0 m-auto size-8 p-0 opacity-0 transition-opacity group-hover/brand:opacity-100 focus-visible:opacity-100"
      }
    >
      <PanelLeftIcon data-slot="sidebar-open-icon" className="size-4" />
      <span className="sr-only">{t("sidebar.open")}</span>
    </Button>
  );
};

/// The sidebar's own way out, at the end of the sidebar's BRAND ROW.
///
/// IT IS THE TRAILING END OF THAT ROW (`ms-auto`), and the auto margin is here rather
/// than at the call site for the same reason the open button's corner is here: this
/// module owns WHERE the pair sits, and the pair is one contract. The row it lands in
/// holds a mark and a product wordmark and nothing that grows, so without this the
/// button would sit against the end of the name instead of the end of the row.
///
/// IT MOVED OUT OF THE HEADER (the `New task` / add-project / refresh row) -- see
/// `components/sidebar.tsx` for the arrangement and why, and `components/app-brand.tsx`
/// for what it now sits beside.
export const SidebarCollapseButton: FC<{ onCollapse: () => void }> = ({
  onCollapse,
}) => {
  const { t } = useTranslation();
  return (
    <Button
      type="button"
      variant="ghost"
      size="icon"
      data-slot="sidebar-collapse"
      aria-controls={SIDEBAR_ID}
      // `true`: the region this names is on screen and expanded (see the open button for
      // why this is the region's state and not the button's intention).
      aria-expanded={true}
      onClick={onCollapse}
      title={t("sidebar.collapse")}
      className="text-muted-foreground hover:text-foreground ms-auto size-8 shrink-0 p-0"
    >
      <PanelLeftCloseIcon data-slot="sidebar-collapse-icon" className="size-4" />
      <span className="sr-only">{t("sidebar.collapse")}</span>
    </Button>
  );
};
