// Ticket 04/09 of `.scratch/session-after-refresh`: a conversation the SERVER is still
// answering must not offer Send -- and, since ticket 09, must offer the STOP instead.
//
// ================================================================ why this file
//
// THE BUG THIS PINS was two facts disagreeing about one session. A run belongs to the
// PROCESS, so a page that reloaded into a running conversation is WATCHING it -- nothing in
// that page started it, so the runtime's own `isRunning` is false -- while the server's run
// edge knew perfectly well that a run was going. The composer therefore offered Send, and
// the only reply was the 409 this repo words as "this session already has a run in this
// process". Found in a browser (`.scratch/session-after-refresh/walkthrough.mjs`, which is
// where the 409 is on the record); this file holds the two rules that fix rests on, both of
// them things no suite could see before.
//
// WHAT CHANGED IN TICKET 09: the shut door used to carry a SENTENCE ("this conversation is
// still being answered; wait for it to settle"). It carries a STOP now -- the server has a
// cancel verb, so a conversation somebody else is answering is a conversation a person can
// end -- and the sentence is gone with the state it explained.
//
// WHAT THIS FILE CANNOT SEE: `app.tsx` and `thread.aui.tsx`, which is where the server's
// word is read off the window (`useWindowFeed`'s `onState`), where the action row chooses
// between Send, Cancel and the Stop (on the server's word) and where the sentence and the
// stop are reachable at all. Both files reach the assistant runtime and cannot be rendered
// in this run. The wiring is the walkthrough's; the RULES are here.
//
// ================================================================ the two cases
//
// 1. `statusOf` -- the arithmetic, pure, both ways round: this page's own reading ORed with
//    the server's word, and which words do NOT mean "in flight".
// 2. the STOP, RENDERED in both languages and read back -- for the reason
//    `suites/record.tsx` gives about its own: a control that reaches the screen is the one
//    thing a green tree cannot see, and `session-title-blank` is what that costs.
import { renderToStaticMarkup } from "react-dom/server";
import { I18nextProvider } from "react-i18next";
import { expect } from "vitest";

import { type Case, type Suite } from "../e2e";
import { renderI18n } from "../support/locale";
import { SessionRunStop } from "../../src/components/session-run-stop";
import { IDLE, statusOf, type SessionStatus } from "../../src/lib/session-status";
import type { Language } from "../../src/lib/language";

/// THE BUTTON AS A PERSON MEETS IT: the stop inside a real i18n instance, rendered to a
/// string. The provider is what the page supplies (`App`), and the thread id is what the
/// page hands down (`ComposerStop`), so this is the button's own rendering and nothing of
/// the composer's furniture.
function drawn(language: Language): string {
  return renderToStaticMarkup(
    <I18nextProvider i18n={renderI18n(language)}>
      <SessionRunStop threadId="t-1" />
    </I18nextProvider>,
  );
}

/// Whether the stop drew an element at all. A label with no element would be a word
/// nothing renders -- the failure this half of the suite exists for.
const drew = (language: Language): boolean => drawn(language).includes('data-slot="session-stop"');

const cases: Case[] = [
  {
    name: "a-run-the-server-is-answering-counts-as-in-flight",
    run: async () => {
      // THE BUG, AS ARITHMETIC: nothing in this page is running, and the server says the
      // conversation is. The answer has to be `running`, because that is what draws the
      // Stop in the composer and lights the sidebar row.
      expect(statusOf(IDLE, "running")).toEqual({ running: true, parked: false });

      // AND THE OTHER WAY ROUND, which is why it is an OR and not a replacement: a run this
      // page just sent is `running` in the runtime BEFORE the window has said anything about
      // it (the frame that names the state is still in the writer's queue), so a union that
      // let the server's silence win would open the composer in the middle of a run.
      expect(statusOf({ running: true, parked: false }, null)).toEqual({
        running: true,
        parked: false,
      });

      // THE WORDS THAT ARE NOT "IN FLIGHT". A parked run has ENDED on its interrupt, a
      // settled one answered, and a conversation with no window at all has nothing to say --
      // none of them may draw the Stop, or the composer would offer to end a run that is not
      // going.
      //
      // AND `parked` IS A STATE NOW (ticket 06): the server's word counts, because the CARD
      // that answers a parked run comes back on a rebuilt conversation -- before this, taking
      // the server's `parked` would have made a door with no way through it. It is still not
      // `running`, so it draws no Stop; what it draws is the sidebar's `waiting on you` and
      // the archive refusal.
      expect(statusOf(IDLE, "parked"), "the server's parked counts as parked").toEqual({
        running: false,
        parked: true,
      });
      for (const state of ["settled", "unfinished", null]) {
        expect(statusOf(IDLE, state), `"${state}" is not a run of any kind`).toEqual(IDLE);
      }
      expect(statusOf(IDLE, "something-else")).toEqual(IDLE);

      // AND THIS PAGE'S OWN PARKED READING SURVIVES THE UNION -- it is the one that owns
      // the approval gate, and a server that has moved on says nothing about it.
      const parked: SessionStatus = { running: false, parked: true };
      expect(statusOf(parked, "settled")).toEqual(parked);
    },
  },
  {
    name: "the-stop-is-drawn-and-said-in-both-languages",
    run: async () => {
      // THE CONTROL THAT REPLACED THE SENTENCE. What it has to carry is what a person acts
      // on: that this press STOPS the conversation, said as copy (the `aria-label` and the
      // tooltip are the same word -- a control's name is copy, and this page follows the
      // language).
      const english = drawn("en");
      expect(drew("en")).toBe(true);
      expect(english).toContain("Stop");

      // AND THE OTHER LANGUAGE SAYS IT TOO. Chinese has no fallback that would make this
      // fail -- a missing entry renders English on an otherwise Chinese page, which is the
      // failure a paraphrase would hide. The two are compared so that a catalog edited into
      // the same word twice is a failure as well (the Chinese side IS a translation).
      const chinese = drawn("zh");
      expect(chinese).toContain("停止");
      expect(chinese).not.toBe(english);
    },
  },
];

export const runningSuite: Suite = { name: "running", cases };