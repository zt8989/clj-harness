// THE SIDEBAR'S SECOND ATTEMPT AT A ROW THAT ITS LAST READ DID NOT NAME: how many, how
// soon, and which one.
//
// PURE, in the shape `sidebar-rows.ts` and `relative-time.ts` established:
// `src/lib/sidebar-refetch.ts` imports nothing, so a case is literals in and one answer
// out -- no render, no listing, no timers. That seam is not a preference here either:
// `components/sidebar.tsx` cannot be imported into this run at all (it reaches
// `lib/i18n.ts`, which touches `document`), so the effect that uses this rule can only be
// checked by a browser.
//
// WHAT THIS SUITE CANNOT SEE: that the timer actually fires, that the row ends up ON
// SCREEN, and -- the whole point of the fix -- that it is there while the run that created
// it is STILL GOING. Those are `.scratch/new-session-appears/walkthrough.mjs`, against a
// real Chromium and a real harness.
//
// AND THE THIRD REASON THIS RULE USED TO CARRY IS RETIRED (ticket 02 of
// `.scratch/sidebar-ws-and-run-state`): it asked again about a listed row whose `running`
// this page was not itself running, on the theory that the run had ended and the snapshot
// was stale. The listing is PUSHED now -- `events.host` carries the run's start and its
// end, rung by the run-state writes themselves -- so a `running` that is still `true` is a
// run in flight, and the frame that says it stopped is what updates the row. The cases
// that pinned the old reason are gone with it, and the ones that remain are the two writes
// a minted row is made of.
import { expect } from "vitest";

import { type Case, type Suite } from "../e2e";
import {
  ASK_AGAIN_AFTER_MS,
  ASK_AGAIN_LIMIT,
  countAsk,
  nextAsk,
  type ListedRow,
} from "../../src/lib/sidebar-refetch";

/// THE FACTS A CASE IS MADE OF: the ids this page has a title for, and what the listing
/// says about each id it names. Spelled as collections rather than as the page's objects
/// because that is all the rule may look at.
const titles = (...ids: string[]): string[] => ids;

/// A SEND TIME, for a row the store has caught up with. The value is not the point -- the
/// rule only asks whether there IS one -- so a literal stands in for a clock.
const SENT_AT = 1_790_000_000_000;

/// WHAT THE LISTING SAYS, with the send recorded: the settled row, which is the state
/// every ask is trying to reach.
const listed = (...ids: string[]): Map<string, ListedRow> =>
  new Map(ids.map((id): [string, ListedRow] => [id, { lastSentAt: SENT_AT }]));

/// ...AND FOR THE ROW THAT IS THERE AND STILL EMPTY: the listing names it and records no
/// send, which is a new session's row in the moment between the two writes (the
/// registration creates the row; the name and the time arrive with the run's input). A row
/// in this state drawn on screen still says 还没跑过, which is the second half of the fix.
const listedUnsent = (...ids: string[]): Map<string, ListedRow> =>
  new Map(ids.map((id): [string, ListedRow] => [id, { lastSentAt: null }]));

const noAttempts: ReadonlyMap<string, number> = new Map();

/// THE ATTEMPTS AFTER ASKING ABOUT ID ONCE, which is the state the caller is in before the
/// answer to that ask comes back.
const askedOnce = (id: string): ReadonlyMap<string, number> => countAsk(noAttempts, { id, attempt: 1, after: 0 });

const cases: Case[] = [
  {
    name: "a-title-the-listing-does-not-name-is-asked-about-at-once",
    run: async () => {
      // THE ORDINARY CASE, and the reason the first ask waits for nothing: the row is
      // written by the registration that precedes the run, so by the time a title is on
      // screen it is usually already in the store. A person who has just pressed send
      // should not wait a beat to see their session appear.
      const ask = nextAsk(titles("s1"), listed(), noAttempts);
      expect(ask).toEqual({ id: "s1", attempt: 1, after: 0 });
    },
  },
  {
    name: "an-id-the-listing-already-names-and-has-a-send-time-for-is-never-asked-about",
    run: async () => {
      // THE ROW IS THERE AND FINISHED. Asking again would be a request whose answer cannot
      // change anything -- the state the effect is in for every session that has settled,
      // whatever its `running` (see this file's header on the retired third reason).
      expect(nextAsk(titles("s1"), listed("s1"), noAttempts)).toBeUndefined();
      expect(nextAsk(titles("s1"), listed("s1", "s2"), noAttempts)).toBeUndefined();
    },
  },
  {
    name: "a-row-that-is-there-and-still-empty-is-asked-about-again",
    run: async () => {
      // THE SECOND REASON TO ASK TWICE, and the one a row's PRESENCE cannot answer: the
      // registration creates the row before the run, and the run's own input arrival
      // records the name and the send time. A listing read between the two names the row
      // and still says 还没跑过 -- so "the row is there" is not "the row is finished", and
      // a rule that stopped there would settle a new session on screen with no time and
      // no title until something else happened to refresh.
      const ask = nextAsk(titles("s1"), listedUnsent("s1"), noAttempts);
      expect(ask).toEqual({ id: "s1", attempt: 1, after: 0 });
      // AND IT STOPS THE MOMENT THE SEND IS RECORDED, which is what keeps this from being
      // a poll: the row is asked about while it is HALF written, and not after.
      expect(nextAsk(titles("s1"), listed("s1"), askedOnce("s1"))).toBeUndefined();
    },
  },
  {
    name: "a-listed-row-this-page-did-not-mint-is-never-asked-about",
    run: async () => {
      // THE OTHER HALF OF THE CANDIDATES, and the one that turned this rule into a POLL: a
      // row the listing names with no send time, that THIS page never minted, is not a write
      // this page is waiting for. It is another window's row, a fork's, a subagent's, a
      // conversation nobody ever sent to -- `last_sent_at` is NULL for 126 of the owner's 243
      // rows -- and whatever happens to it arrives by PUSH (`events.host`).
      expect(nextAsk(titles(), listedUnsent("s1"), noAttempts)).toBeUndefined();
      // AND ASKING AGAIN CHANGES NOTHING, which is what makes this a stop rather than a
      // delay: the attempts map is not what was holding the ask back. Each ask that DID go
      // out landed a fresh listing, which re-armed the effect that made it -- so with seven
      // such rows the sidebar read `/api/projects` 36 times in eleven seconds, every 400ms
      // by measurement (2026-09-29).
      expect(nextAsk(titles(), listedUnsent("s1"), new Map([["s1", 4]]))).toBeUndefined();
      // AND A SETTLED ROW THE PAGE DID NOT MINT IS NOTHING TO ASK ABOUT EITHER.
      expect(nextAsk(titles(), listed("s1"), noAttempts)).toBeUndefined();
    },
  },
  {
    name: "nothing-to-ask-about-answers-nothing",
    run: async () => {
      // A PAGE WITH NO MINTED SESSION, which is every page load that opens an old
      // conversation: no titles, so no row can be missing.
      expect(nextAsk(titles(), listed(), noAttempts)).toBeUndefined();
      expect(nextAsk(titles(), listed("s1"), noAttempts)).toBeUndefined();
    },
  },
  {
    name: "an-ask-that-did-not-name-the-row-is-made-again-later-and-not-in-the-same-instant",
    run: async () => {
      // THE BUG THIS FIXES, stated as a case: ONE ask was the whole budget, so a read
      // served before the registration committed spent the id and the row never came
      // (它不出现，刷新才出现). A second ask is now made -- and it WAITS, because reads
      // made in the same instant land in the same window and miss together.
      const ask = nextAsk(titles("s1"), listed(), askedOnce("s1"));
      expect(ask).toEqual({ id: "s1", attempt: 2, after: ASK_AGAIN_AFTER_MS });
      expect(ASK_AGAIN_AFTER_MS).toBeGreaterThan(0);
    },
  },
  {
    name: "the-patience-is-bounded-and-then-the-id-is-left-alone",
    run: async () => {
      // AN ANSWER THAT NEVER COMES STILL STOPS. This is the half that keeps the effect
      // from asking forever -- what "once per id" was protecting, kept without turning
      // one unlucky read into a permanent miss.
      let attempts: ReadonlyMap<string, number> = noAttempts;
      for (let i = 1; i <= ASK_AGAIN_LIMIT; i += 1) {
        const ask = nextAsk(titles("s1"), listed(), attempts);
        expect(ask?.attempt).toBe(i);
        attempts = countAsk(attempts, ask!);
      }
      expect(nextAsk(titles("s1"), listed(), attempts)).toBeUndefined();
      // AND IT IS MORE THAN ONE, or this rule would be the bug it replaces.
      expect(ASK_AGAIN_LIMIT).toBeGreaterThan(1);
    },
  },
  {
    name: "an-id-past-its-limit-gets-out-of-the-way-of-the-next-one",
    run: async () => {
      // SEVERAL MINTED SESSIONS CAN BE WAITING ON ONE PAGE (a send in each, a click made
      // twice). The caller asks about ONE per pass, so an id that has spent its budget
      // must be SKIPPED rather than answered -- otherwise the second session would never
      // be asked about at all, which is the same permanent miss one id over.
      let spent: ReadonlyMap<string, number> = noAttempts;
      for (let i = 0; i < ASK_AGAIN_LIMIT; i += 1) {
        spent = countAsk(spent, nextAsk(titles("s1", "s2"), listed(), spent)!);
      }
      expect(nextAsk(titles("s1", "s2"), listed(), spent)).toEqual({
        id: "s2",
        attempt: 1,
        after: 0,
      });
    },
  },
  {
    name: "counting-an-ask-builds-a-new-map-and-leaves-the-old-one-alone",
    run: async () => {
      // THE CALLER HOLDS ITS ATTEMPTS IN A REF, so a count has to be a NEW map: mutating
      // the one already being held is how a render gets to disagree with what was
      // actually asked, and it is also how React's purity rules get broken.
      const before = new Map([["s1", 1]]);
      const after = countAsk(before, { id: "s1", attempt: 2, after: ASK_AGAIN_AFTER_MS });
      expect(after).not.toBe(before);
      expect(before.get("s1")).toBe(1);
      expect(after.get("s1")).toBe(2);
      // AND AN ID THAT WAS NOT THERE BEFORE IS COUNTED FROM NOTHING.
      expect(countAsk(before, { id: "s9", attempt: 1, after: 0 }).get("s9")).toBe(1);
    },
  },
];

export const sidebarRefetchSuite: Suite = { name: "sidebar-refetch", cases };
