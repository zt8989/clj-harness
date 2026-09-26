// The idle guard's card: what a stall frame SAYS, and the one property the whole feature
// rests on.
//
// BOTH CASES ARE PURE, and the boundary is the same one the `injections` suite draws:
// `lib/llm-timeout.ts` imports nothing at runtime, so the arithmetic -- the three numbers
// the row prints, and which of the three endings the sentence is about -- is pinned here
// over literals. HOW THE CARD LOOKS is not visible to a string render and is measured in a
// real browser (`.scratch/llm-idle-timeout/`).
//
// THE SECOND CASE IS NOT ABOUT OUR MODULE AT ALL, exactly like the third case of the
// `injections` suite: it pins the ADAPTER's behaviour for OUR part name. `toAgUiMessages`
// sends text, reasoning and tool calls and no `data` part, and `keepInjectionCards` restores
// only the parts it owns. Together those are what let a stall be SEEN on screen while the
// server keeps it out of the record (`harness.edge.http/wire-only-frame?`): the card is a
// view of a call, never a message of the conversation.
import { expect } from "vitest";
import { toAgUiMessages } from "@assistant-ui/react-ag-ui";

import { type Case, type Suite } from "../e2e";
import { INJECTION_PART, keepInjectionCards } from "../../src/lib/injections";
import {
  TIMEOUT_PART,
  timeoutOutcome,
  timeoutView,
} from "../../src/lib/llm-timeout";

const cases: readonly Case[] = [
  {
    name: "a-timeout-card-reads-the-frame-and-says-which-ending-it-was",
    run: async () => {
      // A RETRIABLE TIMEOUT: the vendor said nothing for 500 ms on the first attempt, and
      // the server is trying again. The row says the three numbers it was handed and the
      // chip says a retry is coming.
      const first = timeoutView({
        idleMs: 500,
        attempt: 1,
        limit: 3,
        retrying: true,
        emitted: false,
      });
      expect(first).toEqual({
        idleMs: 500,
        attempt: 1,
        limit: 3,
        retrying: true,
        emitted: false,
      });
      expect(timeoutOutcome(first!)).toBe("retrying");

      // THE BUDGET IS SPENT: nothing emitted, no retry left, the run is ending. The same
      // numbers, and the sentence is the other one.
      const spent = timeoutView({
        idleMs: 500,
        attempt: 4,
        limit: 3,
        retrying: false,
        emitted: false,
      });
      expect(spent!.retrying).toBe(false);
      expect(timeoutOutcome(spent!)).toBe("exhausted");

      // AND THE CASE THE ORDER OF THE TWO QUESTIONS EXISTS FOR: the call had ALREADY put
      // part of an answer on the wire before it went quiet, so there is no retry no matter
      // what the budget says -- a second attempt would append to the message the first one
      // opened, and the client would read the half-written answer twice.
      const partial = timeoutView({
        idleMs: 500,
        attempt: 2,
        limit: 3,
        retrying: false,
        emitted: true,
      });
      expect(timeoutOutcome(partial!)).toBe("partial");

      // UNREADABLE IS NOT A CARD. The three numbers are what the sentence SAYS, so a frame
      // missing one draws nothing rather than a row claiming something the server never
      // said -- the same answer `injectionView` gives a frame with no text.
      const unreadable: readonly unknown[] = [
        null,
        undefined,
        "llm-timeout",
        42,
        [],
        {},
        { attempt: 1, limit: 3 },
        { idleMs: 500, limit: 3 },
        { idleMs: 500, attempt: 1 },
        { idleMs: 500, attempt: -1, limit: 3 },
        { idleMs: Number.NaN, attempt: 1, limit: 3 },
        { idleMs: 500, attempt: "1", limit: 3 },
      ];
      for (const value of unreadable) {
        expect(timeoutView(value)).toBeNull();
      }

      // THE TWO FLAGS ARE READ LENIENTLY, and the direction matters: anything that is not
      // exactly `true` means 'the server is not retrying', which is the answer that does
      // not promise a second attempt. A frame carrying the flags as strings is a frame
      // from some other version, and it draws with defaults rather than disappearing.
      expect(timeoutView({ idleMs: 500, attempt: 1, limit: 3 })).toEqual({
        idleMs: 500,
        attempt: 1,
        limit: 3,
        retrying: false,
        emitted: false,
      });
      expect(
        timeoutView({ idleMs: 500, attempt: 1, limit: 3, retrying: "yes", emitted: 1 }),
      ).toEqual({ idleMs: 500, attempt: 1, limit: 3, retrying: false, emitted: false });
    },
  },
  {
    name: "the-timeout-frame-is-a-card-the-model-never-sees-and-a-rebuild-never-brings-back",
    run: async () => {
      // ONE NAME PER CARD: the injection card and this one are two `data` parts, and a
      // reader that matched by 'is this a data part' would draw one where the other goes.
      expect(TIMEOUT_PART).not.toBe(INJECTION_PART);

      const card = {
        type: "data" as const,
        name: TIMEOUT_PART,
        data: { idleMs: 500, attempt: 1, limit: 3, retrying: true, emitted: false },
      };

      // THE CONTRACT: a card-only message crosses the wire as NOTHING. This is what makes
      // the whole feature possible -- the stall is visible and is never sent back to the
      // model, so it cannot become part of what the next call reads.
      expect(toAgUiMessages([{ id: "a1", role: "assistant", content: [card] }] as never)).toEqual(
        [],
      );

      // THE USUAL SHAPE ON THE LIVE PATH: the adapter hangs a CUSTOM frame on the message
      // that is streaming at that moment, so the card usually rides along with text. The
      // text goes, the card does not.
      expect(
        toAgUiMessages([
          {
            id: "a1",
            role: "assistant" as const,
            content: [{ type: "text" as const, text: "收到" }, card],
          },
        ] as never),
      ).toEqual([{ id: "a1", role: "assistant", content: "收到" }]);

      // AND A REBUILD BRINGS BACK NOTHING OF IT: `keepInjectionCards` restores the parts it
      // owns, by name, and this is not one of them. The server never records this frame
      // (`harness.edge.http/wire-only-frame?`), so the two halves agree -- what is on disk
      // is the conversation, and a stall was never part of it.
      const rebuilt = [{ id: "a1", role: "assistant", content: [card] }];
      const converted = [
        {
          id: "a1",
          role: "assistant" as const,
          content: [{ type: "text" as const, text: "收到" }],
        },
      ];
      expect(keepInjectionCards(rebuilt, converted)).toEqual(converted);
    },
  },
];

export const timeoutSuite: Suite = { name: "llm-timeout", cases };
