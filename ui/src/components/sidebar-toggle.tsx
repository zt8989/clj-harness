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
// THE OPEN BUTTON IS THE PHONE'S. When the sidebar is folded away, one control
// stays behind in the top-left corner of the page, floating over the conversation
// column -- because the thing it opens is not there to hold it, and a control that
// scrolls away with the conversation is one people have to go looking for. It
// carries `z-40` so it sits above the column it floats over, a border and a
// translucent background so it stays legible over whatever is behind it, and it is
// drawn by the PAGE rather than by the sidebar: a component cannot draw its own
// way back after it has been unmounted.
//
// THE COLLAPSE BUTTON IS THE SIDEBAR'S HEADER'S, next to "New task" and the two
// icon buttons, because that is where a person looks for the way out of a panel.
// It is a control and not a drag handle: this product has no width preference to
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
// is no resize listener to keep mounted for a fact CSS already has: what this module
// reads the viewport for is the one thing a media query cannot answer, which is what
// the state should be INITIALIZED to (see `sidebarStartsOpen`).
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

/// WHETHER THE SIDEBAR STARTS OPEN ON THIS PAGE -- the one thing about the fold that
/// CSS cannot answer, because a media query can say how the column is DRAWN and the
/// fold is STATE, and state has to be initialized before anything is drawn.
///
/// READ ONCE, AT MOUNT, and never again: there is no resize listener. A window dragged
/// narrower keeps the sidebar where the person left it -- the CSS turns it into the
/// floating panel, and the backdrop gives it a way out -- so this decides only the
/// FIRST answer. On a wide window that answer is "there", and on a phone-sized one the
/// page opens on the conversation with the floating control in the corner, which is the
/// whole of the phone's shape: the list is one tap away instead of being the thing you
/// have to dismiss first.
///
/// NO WINDOW (this suite's node process) ANSWERS `true`, which is the state that needs
/// no viewport to be the right answer.
export function sidebarStartsOpen(): boolean {
  return typeof window === "undefined" ? true : window.matchMedia(WIDE_ENOUGH).matches;
}

/// The floating corner control: bring the sidebar back.
///
/// ITS ACCESSIBLE NAME IS ITS `title` AND ITS `sr-only` SPAN, which is the pattern
/// every icon button in this shell already follows (see `sidebar.tsx`): the tooltip
/// is for the eye, the span is what a screen reader reads, and the suite reads the
/// same span because text is the one thing rendering to a string can assert.
export const SidebarOpenButton: FC<{ onOpen: () => void }> = ({ onOpen }) => {
  const { t } = useTranslation();
  return (
    <Button
      type="button"
      variant="ghost"
      size="icon"
      data-slot="sidebar-open"
      aria-controls={SIDEBAR_ID}
      // The button EXISTS only in the folded state -- `app.tsx` draws it when the
      // sidebar is away -- so the state it reports is the one it is asking for, not
      // a state it is in: pressing it expands.
      aria-expanded={false}
      onClick={onOpen}
      title={t("sidebar.open")}
      className="border-border bg-background/80 hover:bg-muted absolute start-2 top-2 z-40 size-8 border p-0 shadow-sm backdrop-blur"
    >
      <PanelLeftIcon data-slot="sidebar-open-icon" className="size-4" />
      <span className="sr-only">{t("sidebar.open")}</span>
    </Button>
  );
};

/// The sidebar's own way out, at the end of its header.
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
      // Drawn only while the sidebar is open, so this one reports the expanded
      // state for the same reason the open button reports the folded one.
      aria-expanded={true}
      onClick={onCollapse}
      title={t("sidebar.collapse")}
      className="text-muted-foreground hover:text-foreground size-8 shrink-0 p-0"
    >
      <PanelLeftCloseIcon data-slot="sidebar-collapse-icon" className="size-4" />
      <span className="sr-only">{t("sidebar.collapse")}</span>
    </Button>
  );
};
