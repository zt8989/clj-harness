// The conversation column's title: what this session is, in one line, at the top.
//
// ---------------------------------------------------------------- where it sits
//
// IN THE COLUMN'S OWN TOP BAR, ON THE FIRST OF ITS TWO LINES -- this title here, the
// view switch (Conversation / Trajectory) on the line underneath. That bar is
// `data-slot="view-switch"` in `app.tsx`; the name is kept deliberately even though the
// block now says more than which view is showing, because `.scratch/sidebar-fold`'s
// field notes quote it against the inset that clears the floating unfold button, and a
// rename would leave that record pointing at an element that no longer exists.
//
// THE NAME ON TOP, THE CONTROL UNDER IT, and that is the one deliberate departure from
// the demo this feature copies (its 3rem header is a single row: the conversation's name
// on the left, its actions on the right). A sentence and two buttons do not share one
// strip without the sentence losing, so the tabs get a line of their own -- and the bar
// stays 48px, which is both the demo's `3rem` and the sidebar brand row's own height, so
// the two `border-b` lines land on one y and read as a single line across the app.
//
// THE CLEARANCE FOR THE FLOATING UNFOLD BUTTON IS THE BAR'S, NOT THIS COMPONENT'S: while
// the sidebar is folded the whole block is inset, so both lines begin to the right of the
// button without either of them knowing it exists. `app.tsx` carries the arithmetic --
// and it is 48px rather than the 44px a single line of text used to need, because the tab
// row's own box (not its text) is what must clear the button.
//
// --------------------------------------------------------- where the words come from
//
// FROM THE `projects` LISTING -- THE STORE'S OWN COPY (owner, 2026-09-27). The title is
// `sessions.title`, written once by the first run that arrives, and it is the copy every
// sidebar row draws. The top bar draws THAT one now, so the two cannot disagree -- and a
// session that was FORKED keeps the name the fork gave it (`[fork] …`) instead of the first
// user message it happens to share with the parent it was forked from.
//
// IT IS A PROP RATHER THAN A READ, and the reason is what being a READ would mean: this
// component is rendered inside the runtime provider, so it COULD ask the runtime for the
// first message -- it did, until 2026-09-27 -- but the runtime's messages are a WINDOW, and
// a window opened in the middle does not hold the first thing anybody said. The row beside
// it read the store's copy, this read the window's, and the fork is what exposed the
// difference. The listing belongs to the page (the sidebar is its one reader and hands each
// one up), so the page is what keeps the titles and hands them down.
//
// THE EMPTY CASE IS A WORD, NOT AN ID: `session.untitled` ("New session" / "新会话"), from
// the shell catalog like every other sentence a person reads.
import { useTranslation } from "react-i18next";
import type { FC } from "react";

import { useDocumentTitle } from "@/hooks/use-document-title";

export const SessionTitle: FC<{ title: string | null }> = ({ title }) => {
  const { t } = useTranslation();
  const shown = title ?? t("session.untitled");
  // The tab follows the session on screen, and this component is that session's.
  useDocumentTitle(shown);
  return (
    <span
      data-slot="session-title"
      // The full title on hover, because the line truncates before the browser does: a
      // first message can be longer than the window, the tab has no ellipsis of its own,
      // and `lib/session-title.ts` clips at 60 characters -- so a title clipped by CSS
      // has no other way to be read.
      title={shown}
      className="min-w-0 truncate text-[13px] font-medium"
    >
      {shown}
    </span>
  );
};
