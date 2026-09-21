// Ticket 04 of `.scratch/session-after-refresh`: a conversation the SERVER is still
// answering must not offer Send, and must SAY why.
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
// WHAT THIS FILE CANNOT SEE: `app.tsx`, which is where the server's word is read off the
// window (`useWindowFeed`'s `onState`), where the composer's gate is closed
// (`isSendDisabled`) and where the sentence is reachable at all (it is a context supplied
// there). That file reaches the assistant runtime and cannot be rendered in this run. The
// wiring is the walkthrough's; the RULES are here.
//
// ================================================================ the two cases
//
// 1. `statusOf` -- the arithmetic, pure, both ways round: this page's own reading ORed with
//    the server's word, and which words do NOT mean "in flight".
// 2. the sentence, RENDERED in both languages and read back -- for the reason
//    `suites/record.tsx` gives about its own: a sentence that reaches the screen is the one
//    thing a green tree cannot see, and `session-title-blank` is what that costs.
import { renderToStaticMarkup } from "react-dom/server";
import { I18nextProvider } from "react-i18next";
import { expect } from "vitest";

import { type Case, type Suite } from "../e2e";
import { renderI18n } from "../support/locale";
import { SessionRunContext, SessionRunNotice } from "../../src/components/session-run-notice";
import { IDLE, statusOf, type SessionStatus } from "../../src/lib/session-status";
import type { Language } from "../../src/lib/language";

/// THE SENTENCE AS A PERSON READS IT: the notice inside a real i18n instance and a context
/// saying what the server said, rendered to a string. Both wrappers are what the page
/// supplies -- the instance is the page's, and the context is `SessionHost`'s.
function shown(state: string | null, language: Language): string {
  return renderToStaticMarkup(
    <I18nextProvider i18n={renderI18n(language)}>
      <SessionRunContext.Provider value={state}>
        <SessionRunNotice />
      </SessionRunContext.Provider>
    </I18nextProvider>,
  );
}

/// Whether the notice drew an element at all. A sentence with no element would be a
/// sentence nothing renders -- the failure this half of the suite exists for.
const drew = (state: string | null): boolean =>
  shown(state, "en").includes('data-slot="session-running"');

const cases: Case[] = [
  {
    name: "a-run-the-server-is-answering-counts-as-in-flight",
    run: async () => {
      // THE BUG, AS ARITHMETIC: nothing in this page is running, and the server says the
      // conversation is. The answer has to be `running`, because that is what closes the
      // composer and lights the sidebar row.
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
      // none of them may shut the composer, or the gate would be one a person cannot get out
      // of. `parked` IS DELIBERATELY NOT TAKEN FROM THE SERVER YET: the card that answers a
      // parked run comes back through ticket 06, and until it does, closing the composer on
      // the server's `parked` would be a door with no way through it. Pinned so that
      // changing it is a deliberate act with a failing case in front of it.
      for (const state of ["settled", "unfinished", "parked", null]) {
        expect(statusOf(IDLE, state), `"${state}" is not a run in flight`).toEqual(IDLE);
      }
      expect(statusOf(IDLE, "something-else")).toEqual(IDLE);

      // AND THIS PAGE'S OWN PARKED READING SURVIVES THE UNION -- it is the one that owns
      // the approval gate, and a server that has moved on says nothing about it.
      const parked: SessionStatus = { running: false, parked: true };
      expect(statusOf(parked, "settled")).toEqual(parked);
    },
  },
  {
    name: "a-conversation-the-server-is-answering-is-said-in-both-languages",
    run: async () => {
      // THE SENTENCE, RENDERED. What it has to carry is why the button will not press:
      // that THIS conversation is still being answered, and that sending is for later. A
      // shut door with no sentence beside it is indistinguishable from a broken one.
      const english = shown("running", "en");
      expect(drew("running")).toBe(true);
      expect(english).toContain("still being answered");
      expect(english).toContain("send again");

      // AND THE OTHER LANGUAGE SAYS IT TOO. Chinese has no fallback that would make this
      // fail -- a missing entry renders English on an otherwise Chinese page, which is the
      // failure a paraphrase would hide. The two are compared so that a catalog edited into
      // the same sentence twice is a failure as well (the Chinese side IS a translation).
      const chinese = shown("running", "zh");
      expect(chinese).toContain("还在跑");
      expect(chinese).not.toBe(english);

      // AND THE STATES WHERE THERE IS NOTHING TO SAY DRAW NOTHING -- not an empty element,
      // which is a border around no words. The window says one of four words, and only one
      // of them is a run that is going.
      for (const state of ["settled", "unfinished", "parked", null]) {
        expect(drew(state), `nothing is drawn for "${state}"`).toBe(false);
      }
      // A WORD THIS CLIENT DOES NOT KNOW IS ALSO SILENCE rather than the wire value on
      // screen -- the same judgement `recordNotice` makes about a state it has no sentence
      // for.
      expect(drew("something-else")).toBe(false);
    },
  },
];

export const runningSuite: Suite = { name: "running", cases };
