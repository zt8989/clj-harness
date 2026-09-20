// The page's memory of which session it is in: the key it writes, the one question it
// asks of a listing, and what a storage that refuses to work does to both.
//
// ================================================================ why this file
//
// THE DEFECT IS IN `.scratch/session-after-refresh/`'s ticket 03: a reload used to mint
// a fresh `crypto.randomUUID()` and had nowhere to put it, so "refresh lands you in a
// new, empty conversation" was the only behaviour there was -- and a run still going in
// the process became invisible, because nothing on the page remembered which
// conversation it had been looking at.
//
// WHAT A SUITE CAN REACH HERE, and what it cannot: these are the pure pieces (a key, a
// lookup, a storage that throws), so they are pinned exactly. WHETHER A RELOAD ACTUALLY
// LANDS BACK IN THE SESSION is a browser's question -- it needs a real page, a real
// reload and a real sidebar -- and that is the walkthrough in that ticket's evidence
// file, not this run.
import { expect } from "vitest";

import { type Case, type Suite } from "../e2e";
import {
  browserStorage,
  forgetSession,
  listedSession,
  rememberedSession,
  rememberSession,
  SESSION_KEY,
  type SessionListing,
  type SessionStorage,
} from "../../src/lib/session-memory";

/// A storage that answers, plus a way to read the cell back without going through the
/// module (so a case can tell "forgotten" from "the module would not say").
function stubStorage(initial?: string): SessionStorage & { cell: () => string | null } {
  const cells = new Map<string, string>();
  if (initial !== undefined) cells.set(SESSION_KEY, initial);
  return {
    getItem: (key) => cells.get(key) ?? null,
    setItem: (key, value) => void cells.set(key, value),
    removeItem: (key) => void cells.delete(key),
    cell: () => cells.get(SESSION_KEY) ?? null,
  };
}

/// A storage that refuses every call, the way a browser with site data blocked does --
/// rather than answering null, which is the easy case and not the one that breaks a
/// page.
const refusing: SessionStorage = {
  getItem: () => {
    throw new Error("denied");
  },
  setItem: () => {
    throw new Error("denied");
  },
  removeItem: () => {
    throw new Error("denied");
  },
};

const listing = (sessions: readonly { threadId: string; bytes: number | null }[]): readonly SessionListing[] => [
  { sessions: [{ threadId: "other-1", bytes: 10 }, { threadId: "other-2", bytes: 20 }] },
  { sessions: [...sessions] },
];

const cases: Case[] = [
  {
    name: "the-remembered-session-is-one-key-and-a-refusing-storage-is-not-a-failure",
    run: async () => {
      const storage = stubStorage();

      // NOTHING REMEMBERED IS NULL, not an empty string and not undefined: a page
      // that has never been anywhere has nothing to go back to, and the caller's check
      // is one comparison.
      expect(rememberedSession(storage)).toBe(null);

      rememberSession(storage, "s-1");
      expect(rememberedSession(storage)).toBe("s-1");
      expect(storage.cell(), "the id is written under the module's one key").toBe("s-1");

      // FORGETTING IS CONDITIONAL: a page that decided a stale id was gone must not
      // erase the memory of a session somebody moved to in the meantime -- that window
      // is real (the listing arrives a moment after the switch).
      forgetSession(storage, "someone-else");
      expect(rememberedSession(storage)).toBe("s-1");
      forgetSession(storage, "s-1");
      expect(rememberedSession(storage)).toBe(null);

      // AN EMPTY CELL IS NOT AN ID. It is what a half-written or hand-cleared cell
      // looks like, and adopting it would put the page in a session called "".
      expect(rememberedSession(stubStorage(""))).toBe(null);

      // AND A STORAGE THAT THROWS, in every direction, is the behaviour the page had
      // before this file existed: no memory, and no failure to report.
      expect(rememberedSession(refusing)).toBe(null);
      expect(() => rememberSession(refusing, "s-1")).not.toThrow();
      expect(() => forgetSession(refusing, "s-1")).not.toThrow();
      expect(rememberedSession(null)).toBe(null);
      expect(() => rememberSession(null, "s-1")).not.toThrow();

      // THIS RUN HAS NO BROWSER, so the page's own storage really is absent -- which is
      // the path that says the restore degrades instead of throwing in a page that
      // cannot store anything.
      expect(browserStorage()).toBe(null);
    },
  },
  {
    name: "the-restore-asks-the-listing-for-one-row-and-gets-its-log-size-with-it",
    run: async () => {
      const projects = listing([
        { threadId: "s-1", bytes: 359 },
        { threadId: "s-2", bytes: null },
      ]);

      // THE ROW, NOT A BOOLEAN, because the page needs the second field off it: a
      // listed session with NO LOG is a conversation that is empty by construction (a
      // session made on the sidebar and never run), and the restore opens it empty
      // instead of asking the server to read a file that does not exist.
      expect(listedSession("s-1", projects)).toEqual({ threadId: "s-1", bytes: 359 });
      expect(listedSession("s-2", projects)).toEqual({ threadId: "s-2", bytes: null });

      // THE SECOND PROJECT, not just the first: a remembered session is usually not in
      // whichever project the listing happens to start with.
      expect(listedSession("other-2", projects)).toEqual({ threadId: "other-2", bytes: 20 });

      // NOT THERE IS NULL -- deleted, archived, moved by hand, or never bound. The page
      // says nothing about it and starts fresh.
      expect(listedSession("gone", projects)).toBe(null);
      // BY THE WHOLE ID, not by prefix or by case: ids are uuids and a listing is a
      // fact, not a guess.
      expect(listedSession("s-", projects)).toBe(null);
      expect(listedSession("S-1", projects)).toBe(null);
      expect(listedSession("s-1", [])).toBe(null);
      expect(listedSession("s-1", [{ sessions: [] }])).toBe(null);
    },
  },
];

export const restoreSuite: Suite = { name: "restore", cases };
