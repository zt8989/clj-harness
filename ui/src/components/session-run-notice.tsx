// A CONVERSATION THE SERVER IS STILL ANSWERING, and the sentence the composer owes for it.
// Ticket 04 of `.scratch/session-after-refresh`.
//
// ============================================================== why it is its own file
//
// Because a sentence that reaches the screen is the one thing a green tree cannot see
// (`session-title-blank` is what that costs), so the sentence is a COMPONENT and it is
// rendered in a suite (`test/suites/running.tsx`) and read back. That is only possible from
// a module the suite can import, and `components/composer-chrome.tsx` -- the frame that
// actually draws this -- cannot be one: it reaches `lib/attachments.ts`, which reaches
// `lib/i18n.ts`, which touches `document` as it loads, and this run has no DOM. So the
// sentence lives next to the state it is about, and the frame imports it.
//
// ============================================================== what it is for
//
// A RUN BELONGS TO THE PROCESS, NOT TO THE TAB THAT STARTED IT. A page that reloaded into a
// conversation somebody is still answering is WATCHING that run: nothing in it started the
// run, so the runtime's own `isRunning` is false, and the server's run edge is the only
// thing that knows. `App`'s `SessionHost` reads that fact off the window it follows
// (`useWindowFeed`'s `onState`) and does two things with it -- it closes Send
// (`isSendDisabled`) and it supplies this context. Before that, the composer offered Send
// for a conversation the server was still answering, and the only reply was the 409 this
// repo words as "this session already has a run in this process".
//
// WHY A SENTENCE AND NOT JUST A SHUT DOOR: a control that will not press says nothing about
// why, and "the button is broken" is the wrong thing for a person to conclude. The
// alternative that was there before was worse than either -- Send worked, and the answer
// was a refusal.
import { createContext, useContext, type FC } from "react";
import { useTranslation } from "react-i18next";

/// WHAT THE SERVER SAID THIS SESSION'S RUN IS DOING, or null while there is nothing to say:
/// `running` / `parked` / `settled` / `unfinished`, straight off the window the page
/// follows.
///
/// IT IS A CONTEXT AND NOT A PROP because the composer lives INSIDE the copied element
/// (`<Thread/>`), which `App` renders as `children` and so cannot hand anything to -- the
/// same reason the thread id travels through one (see `composer-chrome.tsx`).
export const SessionRunContext = createContext<string | null>(null);

/// THE SENTENCE, drawn when -- and only when -- the server says a run of this conversation
/// is going.
export const SessionRunNotice: FC = () => {
  const { t } = useTranslation("composer");
  const runState = useContext(SessionRunContext);
  // `running` ONLY, and deliberately: `parked` and `unfinished` are conversations NO run is
  // going in, so the sentence would be false about them -- and the composer is not shut for
  // them either (see `App`'s `isSendDisabled`, and `lib/session-status.ts`'s `statusOf` for
  // the half of that decision which is still open).
  if (runState !== "running") return null;
  return (
    <p
      role="status"
      data-slot="session-running"
      className="text-muted-foreground px-1.5 pt-1 text-xs"
    >
      {t("run.stillAnswered")}
    </p>
  );
};
