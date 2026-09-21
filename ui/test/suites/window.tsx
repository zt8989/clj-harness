// THE WINDOW: what a page holds of a conversation, what the server's frames do to it,
// and what it says when the answer is not simply "more messages".
//
// ================================================================ why this file
//
// Ticket 06 of `.scratch/sessions-live-on-the-server` moved the page off polling the
// record and onto a WINDOW: a tail page, then every entry as it lands, then pages in
// front on request. The rules that turn frames into a copy are pure by construction
// (`src/lib/window.ts`) and this is where they are pinned -- including the three
// answers that are not "append", because those are the ones a reader would otherwise
// never see going wrong:
//
//   a hole      -- a frame that does not continue from what we hold. The repair is the
//                  tail page, merged, and the reader's place is kept.
//   a reopen    -- the window is OVER (the conversation was put away, taken over, or
//                  the server refuses this generation). Nothing can be merged, and the
//                  page says so rather than quietly showing a different conversation.
//   ahead       -- this copy holds entries the conversation no longer has. It cannot
//                  happen any more (this side stopped being the author in ticket 03),
//                  which is exactly why it is asserted: silence here is the failure
//                  mode, so the check that fires and the sentence it produces are both
//                  under test.
//
// WHAT THIS FILE CANNOT SEE, and where each is pinned instead: `app.tsx`'s wiring of
// the feed (it reaches the assistant runtime and `lib/i18n.ts` and cannot be rendered
// in this run), the SSE reader against a real connection (`http.clj`'s feed and
// `test/harness/edge/http_test.clj`'s raw-socket case), and LAYOUT -- whether a prepend
// actually keeps the reader's place in a real viewport. The arithmetic is here
// (`restoredTop`), and the browser walkthrough (`scripts/dev.mjs --scripted`) is what
// proves a browser agrees.
import { renderToStaticMarkup } from "react-dom/server";
import { I18nextProvider } from "react-i18next";
import { expect } from "vitest";

import { type Case, type Suite } from "../e2e";
import { renderI18n, translator } from "../support/locale";
import { WindowTop, type WindowTopProps } from "../../src/components/window-top";
import type { Language } from "../../src/lib/language";
import { feedFrames, type WindowEntry, type WindowFrame } from "../../src/lib/feed";
import { correctedTop, measure, restoredTop } from "../../src/lib/window-scroll";
import {
  aheadOf,
  aligned,
  applied,
  prepended,
  windowFrom,
  windowNotice,
  type Window,
} from "../../src/lib/window";

/// An entry on the wire: the message, and the record offset it landed at. `id` is the
/// identity everything dedupes by, and `seq` is null while the line is still queued --
/// which is a case the client has to survive, because the server mints that number when
/// the line lands and never predicts it.
const entry = (id: string, seq: number | null = null): WindowEntry => ({
  seq,
  message: { id, role: "assistant", content: id },
});

/// A window as a page holds one: the tail page the read opened.
const held = (entries: WindowEntry[], base: number, hasMore: boolean, cursor: number): Window => ({
  entries,
  baseSeq: base,
  hasMore,
  cursor,
  generation: "gen-1",
  state: "running",
  revision: 1,
});

/// AN APPEND FRAME AS THE SERVER SENDS ONE: the entries that landed after the reader's
/// cursor, and the cursor they end at. `baseSeq` is THE READER'S OWN CURSOR on a delta
/// (`stream-feed!` passes the cursor it is holding), which is what makes "a frame that
/// begins after our cursor" detectable at all.
const appended = (entries: WindowEntry[], base: number, cursor: number): WindowFrame => ({
  type: "append",
  entries,
  baseSeq: base,
  cursor,
});

/// THE WINDOW'S TOP AS A PERSON READS IT, rendered inside a real i18n instance the way
/// the column renders it. The `data-slot` hooks are what a browser walkthrough keys on,
/// so the markup is returned as well as the text.
function drawn(props: Partial<WindowTopProps>, language: Language) {
  const full: WindowTopProps = {
    hasMore: true,
    loading: false,
    onEarlier: () => {},
    notice: null,
    ...props,
  };
  const html = renderToStaticMarkup(
    <I18nextProvider i18n={renderI18n(language)}>
      <WindowTop {...full} />
    </I18nextProvider>,
  );
  return { html, text: html.replace(/<[^>]*>/g, " ").replace(/\s+/g, " ").trim() };
}

const cases: Case[] = [
  {
    name: "an-append-continues-the-window-and-a-repeat-is-not-a-second-entry",
    run: async () => {
      const start = held([entry("m1", 0), entry("m2", 1)], 0, true, 1);

      // THE ORDINARY FRAME: two entries, the cursor moves to the newest one this copy
      // has been told about, and the revision -- the client's own count of how many
      // times its window changed -- goes up by one.
      const first = applied(start, appended([entry("m3", 2), entry("m4", 3)], 1, 3));
      expect(first.effect).toEqual({ kind: "none" });
      expect(first.window.entries.map((e) => (e.message as { id: string }).id)).toEqual([
        "m1",
        "m2",
        "m3",
        "m4",
      ]);
      expect(first.window.cursor).toBe(3);
      expect(first.window.revision).toBe(2);
      expect(first.window.baseSeq).toBe(0);

      // A REPEAT IS NOT A SECOND ENTRY. This is not hypothetical: an entry whose line
      // has not landed has no `seq`, so the cursor cannot move past it and the next
      // frame reaches it again. The identity is the message's own id, the same one the
      // server and the record fold by.
      const again = applied(first.window, appended([entry("m3", 2), entry("m4", 3)], 1, 3));
      expect(again.window.entries).toHaveLength(4);
      expect(again.effect).toEqual({ kind: "none" });

      // AN ENTRY WITH NO ID IS KEPT rather than deduped: guessing that two unnamed
      // messages are the same one would be inventing an identity this side has no
      // licence to invent, and dropping a message is the worse failure.
      const unnamed = applied(first.window, appended([{ seq: 4, message: { role: "user" } }], 3, 4));
      expect(unnamed.window.entries).toHaveLength(5);

      // THE CURSOR ONLY MOVES FORWARD. A frame naming a number behind the one this copy
      // holds is a server with an older view of the same conversation; stepping back
      // would make the next reconnect re-read entries this page already has.
      const behind = applied(first.window, appended([entry("m4", 3)], 3, 3));
      expect(behind.window.cursor).toBe(3);

      // AND A FRAME WITH NOTHING NEW AND NOTHING TO SAY IS NOT A CHANGE: the window
      // comes back by identity, so there is nothing to import and nothing to re-render.
      // A feed pushes plenty of these -- a keep-alive, a repeat of entries the writer had
      // not landed yet -- and counting them would make the client's own counter
      // meaningless.
      const quiet = applied(first.window, appended([entry("m4", 3)], 3, 3));
      expect(quiet.window).toBe(first.window);
      expect(quiet.window.revision).toBe(first.window.revision);
    },
  },
  {
    name: "a-frame-that-does-not-continue-is-a-hole-and-the-tail-repair-keeps-what-the-reader-has",
    run: async () => {
      const start = held([entry("m1", 0), entry("m2", 1)], 0, true, 1);

      // A FRAME THAT BEGINS AFTER OUR CURSOR: entries between the two were never seen.
      // The rule does not guess what they were; it says ALIGN, and the host pulls the
      // tail page.
      const hole = applied(start, appended([entry("m5", 4)], 4, 4));
      expect(hole.effect).toEqual({ kind: "align" });

      // AND WHAT THE READER IS HOLDING SURVIVES THE REPAIR, which is the whole reason
      // this is not a reopen: the page continues from our cursor, so its entries are
      // appended and `baseSeq` stays ours (this copy still holds entries in front of
      // the page's first one). A reader who scrolled up is not thrown to the bottom.
      const merged = aligned(held([entry("m1", 0)], 0, true, 0), {
        type: "tail",
        entries: [entry("m1", 0), entry("m2", 1), entry("m3", 2)],
        baseSeq: 0,
        cursor: 2,
        hasMore: true,
        state: "settled",
      });
      expect(merged.effect).toEqual({ kind: "none" });
      expect(merged.window.entries.map((e) => (e.message as { id: string }).id)).toEqual([
        "m1",
        "m2",
        "m3",
      ]);
      expect(merged.window.baseSeq).toBe(0);
      expect(merged.window.cursor).toBe(2);
      // The state rides on every answer, so a window that is now settled stops looking
      // like a run in flight -- the one fact in a window that is not about position.
      expect(merged.window.state).toBe("settled");

      // A PAGE THAT DOES NOT REACH BACK TO WHAT WE HOLD IS THE OTHER CASE, and it is
      // reported rather than smoothed over: there is conversation this copy can neither
      // show nor fetch, so it is rebuilt from the tail and the entries that fall outside
      // it are COUNTED. A count of zero would mean nothing was lost.
      const rebuilt = aligned(start, {
        type: "tail",
        entries: [entry("m7", 6)],
        baseSeq: 6,
        cursor: 6,
        hasMore: true,
        state: "settled",
      });
      expect(rebuilt.effect).toEqual({ kind: "rebuilt", dropped: 2 });
      expect(rebuilt.window.entries.map((e) => (e.message as { id: string }).id)).toEqual(["m7"]);
      expect(rebuilt.window.baseSeq).toBe(6);
    },
  },
  {
    name: "the-end-of-a-window-and-its-generation-both-mean-reopen",
    run: async () => {
      const start = held([entry("m1", 0)], 0, true, 0);

      // `end` IS THE SERVER SAYING THE WINDOW IS OVER -- the conversation was put away
      // (idle-swept, archived), or somebody else is serving it now. The reason travels
      // for a log; the client's answer is the same either way: open the tail again, and
      // SAY SO, because there is nothing to merge and the alternative is a page quietly
      // showing a conversation that is no longer being served.
      expect(applied(start, { type: "end", reason: "put-away" }).effect).toEqual({
        kind: "reopen",
        reason: "put-away",
      });

      // A FRAME FROM ANOTHER GENERATION IS NOT A CONTINUATION OF THIS WINDOW, even when
      // its numbers happen to line up: the conversation is being served under a new
      // claim (ADR 0003 decision 6), so everything this copy knows about position was
      // about a window that no longer exists.
      expect(applied(start, { ...appended([entry("m2", 1)], 1, 1), generation: "gen-2" }).effect).toEqual(
        { kind: "reopen", reason: "generation" },
      );

      // AND A WINDOW FRAME REPLACES THE WINDOW RATHER THAN APPENDING TO IT: the feed
      // opens with one when the reader has no cursor, and a page route answers one when
      // asked for the tail. Treating it as an append would leave the old entries behind
      // a window that says it is the whole answer.
      const fresh = applied(start, {
        type: "window",
        entries: [entry("m9", 8)],
        baseSeq: 8,
        cursor: 8,
        hasMore: true,
        state: "running",
      });
      expect(fresh.window.entries.map((e) => (e.message as { id: string }).id)).toEqual(["m9"]);
      expect(fresh.window.baseSeq).toBe(8);
      // AND IT IS COUNTED, not silently substituted: the entry this copy was holding
      // (`m1`) is not in the window the server just described, and the reader is told
      // how many went with it.
      expect(fresh.effect).toEqual({ kind: "rebuilt", dropped: 1 });
    },
  },
  {
    name: "an-earlier-page-goes-in-front-and-does-not-move-the-newest-end",
    run: async () => {
      const start = held([entry("m5", 4), entry("m6", 5)], 4, true, 5);

      // "SHOW EARLIER" ASKS FOR THE PAGE IN FRONT OF THE OLDEST ENTRY THIS COPY HOLDS
      // (`?beforeSeq=4`), and the answer goes IN FRONT. `baseSeq` follows the page; the
      // cursor does not move at all -- the newest entry is still the newest entry, which
      // is why the feed stays open and nothing has to be re-read.
      const older = prepended(start, {
        type: "page",
        entries: [entry("m1", 0), entry("m2", 1), entry("m3", 2), entry("m4", 3)],
        baseSeq: 0,
        hasMore: false,
        cursor: 3,
      });
      expect(older.entries.map((e) => (e.message as { id: string }).id)).toEqual([
        "m1",
        "m2",
        "m3",
        "m4",
        "m5",
        "m6",
      ]);
      expect(older.baseSeq).toBe(0);
      expect(older.cursor).toBe(5);
      expect(older.generation).toBe("gen-1");
      expect(older.revision).toBe(2);

      // `hasMore` COMES FROM THE ANSWER, and the server is the one that knows: a page at
      // the front of the conversation says there is nothing in front of it, which is
      // what makes the control disappear instead of asking again forever.
      expect(older.hasMore).toBe(false);

      // THE TAIL PAGE ANSWERS THE SAME SHAPE, and the state it carries is not a position
      // -- a window has to be able to say the run that was going has stopped, or the
      // last message on screen stays "being written" for as long as the page is open.
      expect(windowFrom({ type: "tail", entries: [entry("m1", 0)], baseSeq: 0, cursor: 0, state: "parked" }).state).toBe(
        "parked",
      );
    },
  },
  {
    name: "only-what-the-answer-covers-can-be-called-gone",
    run: async () => {
      const start = held([entry("m1", 0), entry("m2", 1), entry("m3", 2)], 0, true, 2);

      // NOTHING IS GONE WHEN EVERYTHING WE HOLD IS IN THE ANSWER.
      expect(aheadOf(start, appended([entry("m3", 2)], 2, 2))).toEqual([]);

      // AND NOTHING IS GONE WHEN THE ANSWER DOES NOT COVER IT. This is the subtlety the
      // check has to get right: a page is a WINDOW, not the conversation. `m1` and `m2`
      // are below the answer's `baseSeq`, so the server was never asked about them --
      // calling them missing would make every reopen of every long conversation announce
      // that its history had been deleted.
      const tail: WindowFrame = {
        type: "tail",
        entries: [entry("m9", 8)],
        baseSeq: 8,
        cursor: 8,
      };
      expect(aheadOf(start, tail)).toEqual([]);

      // AN ENTRY THE SERVER'S ANSWER DOES COVER, THAT THE ANSWER DOES NOT HAVE, IS THE
      // CASE THE SENTENCE EXISTS FOR: this copy drew something the conversation does not
      // contain. It cannot happen now that the client is not an author (ticket 03), and
      // that is why it is asserted rather than assumed -- the failure it guards is
      // SILENCE, and silence is not something a test can notice later.
      const contradicts: WindowFrame = { type: "tail", entries: [entry("mX", 2)], baseSeq: 2, cursor: 2 };
      expect(aheadOf(start, contradicts)).toEqual(["m3"]);

      // AN ENTRY WHOSE LINE HAS NOT LANDED HAS NO `seq` AND IS OUTSIDE EVERY RANGE: the
      // server has never been asked about it, and it will be in the next frame anyway.
      const queued = held([entry("m1", null)], 0, true, 0);
      expect(aheadOf(queued, { type: "tail", entries: [], baseSeq: 8, cursor: 8 })).toEqual([]);
    },
  },
  {
    name: "the-top-of-a-window-is-drawn-only-when-there-is-more-in-front",
    run: async () => {
      // THE CONTROL IS NOT DRAWN WHEN THERE IS NOTHING IN FRONT. A greyed-out button
      // would be chrome promising something the conversation does not have.
      const short = drawn({ hasMore: false }, "en");
      expect(short.html).not.toContain('data-slot="window-earlier"');

      const long = drawn({ hasMore: true }, "en");
      expect(long.html).toContain('data-slot="window-earlier-button"');
      expect(long.text).toContain("Show earlier");

      // ONE PAGE IN THE AIR AT A TIME: while a page is loading the button is disabled,
      // so a second click cannot race the first and leave the window spliced twice.
      const loading = drawn({ hasMore: true, loading: true }, "en");
      expect(loading.html).toContain("disabled");
      expect(loading.text).toContain("Loading earlier messages");

      // AND WITH NO WINDOW AT ALL THE COLUMN DRAWS NOTHING -- which is the page's job
      // (`window === null` never reaches this component), so what is asserted here is
      // the shape it is handed: no more to fetch and no notice means no markup at all.
      expect(drawn({ hasMore: false, notice: null }, "en").html).toBe("");
    },
  },
  {
    name: "every-sentence-the-window-owes-is-on-screen-in-both-languages",
    run: async () => {
      // A REOPEN IS SAID. The reader was looking at a window that ended; whatever
      // replaced it is not the same copy, and the page says what happened instead of
      // letting the content shift under them unexplained.
      const reopened = windowNotice(translator("en", "shell"), { kind: "reopened" });
      expect(reopened).toContain("reopened");
      expect(drawn({ notice: { kind: "reopened" } }, "en").text).toContain("reopened");

      // THE COUNT IS A FORM, NOT A NUMBER GLUED TO A NOUN: one dropped entry and three
      // are one key with two spellings.
      expect(windowNotice(translator("en", "shell"), { kind: "rebuilt", dropped: 1 })).toContain(
        "1 entry was dropped",
      );
      expect(windowNotice(translator("en", "shell"), { kind: "rebuilt", dropped: 3 })).toContain(
        "3 entries were dropped",
      );
      expect(windowNotice(translator("en", "shell"), { kind: "ahead", count: 2 })).toContain("2 entries");

      // A FAILED READ IS THE SERVER'S OWN SENTENCE, passed through whole: it is the only
      // diagnostic anybody gets, and a paraphrase would be one more thing to distrust.
      expect(windowNotice(translator("en", "shell"), { kind: "failed", message: "HTTP 503" })).toBe(
        "HTTP 503",
      );

      // AND THE OTHER LANGUAGE HAS A SENTENCE AT ALL -- which is the failure a fallback
      // hides: a missing entry renders English on an otherwise Chinese page and nobody
      // sees it until a reader does.
      const chinese = drawn({ notice: { kind: "rebuilt", dropped: 2 } }, "zh");
      expect(chinese.text).toContain("重建");
      expect(chinese.text).toContain("2");
      expect(chinese.text).not.toContain("rebuilt");
      expect(drawn({ hasMore: true }, "zh").text).toContain("显示更早");

      // NOTHING TO SAY IS NOT AN EMPTY SENTENCE: the strip is not drawn at all.
      expect(windowNotice(translator("en", "shell"), null)).toBeNull();
      expect(drawn({ notice: null }, "en").html).not.toContain('data-slot="window-notice"');
    },
  },
  {
    name: "a-frame-the-reader-cannot-parse-does-not-close-the-window",
    run: async () => {
      // FRAMES END AT A BLANK LINE, AND A CHUNK CAN STOP IN THE MIDDLE OF ONE. What is
      // left over is handed back rather than thrown away -- that is the whole of the
      // reader's boundary handling, and getting it wrong shows up as a message that
      // never appears.
      const split = feedFrames('data: {"type":"append","cursor":1}\n\ndata: {"type":"appen');
      expect(split.frames).toEqual([{ type: "append", cursor: 1 }]);
      expect(split.rest).toBe('data: {"type":"appen');

      // A FRAME THIS CLIENT CANNOT READ IS DROPPED AND THE STREAM STAYS OPEN: a
      // malformed frame is one entry nobody can show, and tearing the window down over
      // it would take the rest of the conversation with it. The next frame's numbers
      // still say where the conversation is.
      const odd = feedFrames('data: {"type":"append"}\n\ndata: not json\n\ndata: {"type":"end"}\n\n');
      expect(odd.frames).toEqual([{ type: "append" }, { type: "end" }]);
      expect(odd.rest).toBe("");

      // A BLOCK WITH NO `data:` LINE IS NOT A FRAME (a comment or a keep-alive), and it
      // must not stop the frames after it from being read.
      expect(feedFrames(": keep-alive\n\ndata: {\"type\":\"tail\"}\n\n").frames).toEqual([{ type: "tail" }]);
    },
  },
  {
    name: "the-reader-stays-where-they-were-when-a-page-is-added-above",
    run: async () => {
      // A PREPEND PUSHES EVERYTHING DOWN, so the reader's offset has to move by exactly
      // what the container grew by. In jsdom `scrollHeight` is 0 and this could not be
      // rendered; here the arithmetic is the thing under test, and a browser walkthrough
      // is what proves the wiring.
      const before = measure({ scrollTop: 120, scrollHeight: 2000 });
      expect(before).toEqual({ top: 120, height: 2000 });
      expect(restoredTop(before!, { scrollTop: 120, scrollHeight: 2600 })).toBe(720);

      // NOTHING ADDED, NOTHING MOVED.
      expect(restoredTop(before!, { scrollTop: 120, scrollHeight: 2000 })).toBe(120);

      // AND NEVER ABOVE THE TOP: a container that shrank (a page removed, a re-render)
      // would otherwise ask for a negative offset, which the browser clamps but which
      // would also make the next measurement nonsense.
      expect(restoredTop(before!, { scrollTop: 120, scrollHeight: 100 })).toBe(0);

      // THE CORRECTION BY A MESSAGE ON SCREEN, which is the mechanism the walkthrough
      // made necessary: the container's arithmetic is wrong by whatever changed ABOVE the
      // messages (the last page in front turns `hasMore` false, the control disappears,
      // and the messages move up by a row that is none of their business). A message the
      // reader is looking at cannot be wrong that way.
      expect(correctedTop(-100, -100, 500)).toBe(500);
      expect(correctedTop(-100, 200, 500)).toBe(800);
      expect(correctedTop(-100, -400, 500)).toBe(200);

      // AND NEVER ABOVE THE TOP, for the same reason the container arithmetic is clamped:
      // a negative offset is a number the browser silently changes.
      expect(correctedTop(-100, -700, 100)).toBe(0);

      // NO VIEWPORT (a load in flight, the trajectory view) IS NOT A CRASH: the helper
      // measures nothing and restores nothing.
      expect(measure(null)).toBeNull();
    },
  },
];

export const windowSuite: Suite = { name: "window", cases };
