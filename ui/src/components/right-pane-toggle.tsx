// The two controls that close the right-hand column and bring it back -- THE RIGHT-HAND
// SPELLING OF THE PAIR IN `components/sidebar-toggle.tsx`, and that file's header is where the
// whole argument lives: one verb in two places, both naming the SAME element through
// `aria-controls`, each saying which state it is asking for, `aria-expanded` reading the
// REGION's state rather than the button's intention, and the state deliberately NOT persisted
// because folding a column is a transient layout preference and not a fact about the work.
// None of that is argued a second time here; what follows is only what is different.
//
// ---------------------------------------------------- which way this column leaves
//
// THE COLLAPSE CONTROL IS THE COLUMN'S OWN HEADER'S, at its LEADING edge -- the same rule the
// sidebar's brand row follows (the row that says what the panel IS is the row an exit belongs
// in), and the leading edge rather than the trailing one because that end of this header is
// spoken for: it is where the mirror's way back to the list will sit (ticket 03 of this
// spoken for: it is where the mirror's way back to the list sits (ticket 03), the control
// below. The row's name stays between the two, so it reads `way out · what this is ·
// way back`, and the same verb never stands in it twice.
//
// THE OPEN CONTROL IS THE PAGE'S, floating in the TOP-RIGHT corner over the conversation while
// the column is closed. A closed column is not drawn at all -- there is no subtree to hold the
// control that brings it back -- so the page draws it, exactly as it draws the sidebar's. It is
// `hidden md:flex` rather than the sidebar corner's `lg:hidden` for a reason that is this
// column's own: THE COLUMN DOES NOT EXIST BELOW `md` (see `components/subagent-view.tsx`, whose
// aside is `hidden ... md:flex`), so neither control is drawn there. A control that does nothing
// when pressed is worse than no control.
//
// THE COLUMN IS ONE ELEMENT IN TWO STATES -- the task pane, or a subagent's mirror -- and that is
// why BOTH draw the `id` below: `aria-controls` names the COLUMN, not the view inside it.
import { ArrowLeftIcon, PanelRightCloseIcon, PanelRightIcon } from "lucide-react";
import { useTranslation } from "react-i18next";
import type { FC } from "react";

import { Button } from "@/components/ui/button";

/// THE ID BOTH CONTROLS POINT AT: the right-hand column itself, whichever state it is drawing.
/// The sidebar's pair owns `app-sidebar`; this is the same contract on the other side, and it is
/// a constant here for the same reason: a literal written twice is a reference nothing in this
/// repo could see (there is no DOM to resolve it against), and the suites compare the strings.
export const RIGHT_PANE_ID = "app-right-pane";

/// The control that brings the column back, drawn by the PAGE in the top-right corner while the
/// column is closed. Its accessible name is its `title` and its `sr-only` span, the pattern every
/// icon button in this shell follows (see `components/sidebar-toggle.tsx`).
export const RightPaneOpenButton: FC<{ onOpen: () => void }> = ({ onOpen }) => {
  const { t } = useTranslation();
  return (
    <Button
      type="button"
      variant="ghost"
      size="icon"
      data-slot="right-pane-open"
      aria-controls={RIGHT_PANE_ID}
      // `false`: this control is drawn only while the column is closed, so the region it names is
      // the collapsed one. See the sidebar's open button for why this reports the region and not
      // what pressing the button would do.
      aria-expanded={false}
      onClick={onOpen}
      title={t("rightPane.open")}
      className="border-border bg-background/80 hover:bg-muted absolute end-2 top-2 z-40 hidden size-8 border p-0 shadow-sm backdrop-blur md:flex"
    >
      <PanelRightIcon data-slot="right-pane-open-icon" className="size-4" />
      <span className="sr-only">{t("rightPane.open")}</span>
    </Button>
  );
};

/// The right-hand column's own way out, at the leading edge of its header.
export const RightPaneCollapseButton: FC<{ onCollapse: () => void }> = ({
  onCollapse,
}) => {
  const { t } = useTranslation();
  return (
    <Button
      type="button"
      variant="ghost"
      size="icon"
      data-slot="right-pane-collapse"
      aria-controls={RIGHT_PANE_ID}
      // `true`: the region this names is on screen and expanded (see the open button above for
      // why this is the region's state and not the button's intention).
      aria-expanded={true}
      onClick={onCollapse}
      title={t("rightPane.collapse")}
      className="text-muted-foreground hover:text-foreground size-8 shrink-0 p-0"
    >
      <PanelRightCloseIcon data-slot="right-pane-collapse-icon" className="size-4" />
      <span className="sr-only">{t("rightPane.collapse")}</span>
    </Button>
  );
};

/// THE MIRROR'S WAY BACK TO THE LIST, at the TRAILING end of its header. The mirror is a
/// detail view of the task pane (decision 1: one column, two states), so leaving it is a step
/// back to the list rather than a fold of the column -- and the fold is the LEADING control's
/// verb, which is why this row does not say it twice.
///
/// IT NAMES THE SAME REGION and deliberately carries no `aria-expanded`: the region did not
/// change and stays open, and `aria-expanded` is a disclosure's own statement rather than a
/// label a navigation puts on somebody else's box.
export const RightPaneBackButton: FC<{ onBack: () => void }> = ({ onBack }) => {
  const { t } = useTranslation();
  return (
    <Button
      type="button"
      variant="ghost"
      size="icon"
      data-slot="right-pane-back"
      aria-controls={RIGHT_PANE_ID}
      onClick={onBack}
      title={t("rightPane.back")}
      className="text-muted-foreground hover:text-foreground size-8 shrink-0 p-0"
    >
      <ArrowLeftIcon data-slot="right-pane-back-icon" className="size-4" />
      <span className="sr-only">{t("rightPane.back")}</span>
    </Button>
  );
};
