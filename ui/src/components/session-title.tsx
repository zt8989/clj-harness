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
// FROM THIS SESSION'S OWN MESSAGES, read here because this component is rendered
// inside the runtime provider this host owns -- and rendered ONLY while this host is
// the session on screen (`SessionHost`'s `{visible ? children : null}`). Both facts
// are load-bearing: the first is why there is no plumbing through `App`, and the
// second is why there is no per-session title registry. The module that decides what
// the title IS is `lib/session-title.ts`; this one decides where it is drawn and that
// the browser tab follows it.
//
// THE SELECTOR RETURNS A STRING, and that is deliberate rather than incidental:
// `useAuiState` compares what a selector returns BY VALUE, so a session whose
// assistant message is streaming token by token does not re-render this row (or write
// `document.title`) on every token -- the string only changes when the first thing
// the user said changes, which is once. `components/message-parts.tsx` argues the same
// point for a reasoning preview.
//
// THE EMPTY CASE IS A WORD, NOT AN ID: `session.untitled` ("New session" / "新会话"),
// from the shell catalog like every other sentence a person reads. See
// `lib/session-title.ts` for why the fallback is a parameter there and not a key.
import { useAuiState } from "@assistant-ui/react";
import { useTranslation } from "react-i18next";
import type { FC } from "react";

import { useDocumentTitle } from "@/hooks/use-document-title";
import { firstUserText } from "@/lib/session-title";

export const SessionTitle: FC = () => {
  const { t } = useTranslation();
  const said = useAuiState((s) => firstUserText(s.thread.messages));
  const title = said ?? t("session.untitled");
  // The tab follows the session on screen, and this component is that session's.
  useDocumentTitle(title);
  return (
    <span
      data-slot="session-title"
      // The full title on hover, because the line truncates before the browser does: a
      // first message can be longer than the window, the tab has no ellipsis of its own,
      // and `lib/session-title.ts` clips at 60 characters -- so a title clipped by CSS
      // has no other way to be read.
      title={title}
      className="min-w-0 truncate text-[13px] font-medium"
    >
      {title}
    </span>
  );
};
