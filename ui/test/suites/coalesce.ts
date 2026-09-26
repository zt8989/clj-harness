// THE BATCHER: which frames wait for a frame, which do not, and what order they come out
// in. `lib/coalesce.ts` is the rule; the browser half -- that a real run's frames really do
// arrive in one render per frame -- is a walkthrough's question, not a suite's.
//
// THE CLOCK IS INJECTED, and that is what makes this testable at all: the rule is about
// WHEN, and no suite can wait for a browser's animation frame. So the cases below hold the
// scheduler in their hand and decide when a frame has passed.
import { expect } from "vitest";

import { type Case, type Suite } from "../e2e";
import { HOLD_LIMIT, createBatch } from "../../src/lib/coalesce";

/// A batch to drive by hand: the ticks it asked for, the batches that came out, and how
/// many times it asked. `terminal` is a property of the frame in every case here, the way
/// it is a property of the frame's TYPE on the socket.
function harness(terminal = (frame: string) => frame === "end") {
  const ticks: (() => void)[] = [];
  const batches: string[][] = [];
  let scheduled = 0;
  const batch = createBatch<string>({
    deliver: (frames) => batches.push([...frames]),
    terminal,
    schedule: (flush) => {
      scheduled += 1;
      ticks.push(flush);
    },
  });
  return {
    batch,
    batches,
    /// Run the flush the batcher asked for, if one is waiting.
    tick: () => ticks.shift()?.(),
    asked: () => scheduled,
  };
}

const cases: Case[] = [
  {
    name: "frames-wait-for-a-frame-and-then-go-out-together",
    run: async () => {
      const { batch, batches, tick, asked } = harness();

      // NOTHING IS DELIVERED YET, and a flush was asked for exactly ONCE: ten frames inside
      // one task are one render, which is the whole of this change.
      batch.push("t-1");
      batch.push("t-2");
      batch.push("t-3");
      expect(batches).toEqual([]);
      expect(asked()).toBe(1);

      tick();
      expect(batches).toEqual([["t-1", "t-2", "t-3"]]);

      // AND A TICK WITH NOTHING WAITING DELIVERS NOTHING -- which is what a quiet page's
      // animation frame finds, and it must not "deliver" an empty batch.
      batch.flush();
      tick();
      expect(batches).toHaveLength(1);

      // THE MARK WAS CLEARED BY THE FLUSH, so the next frame asks for a new one rather than
      // riding on a tick that has already been spent.
      batch.push("t-4");
      expect(asked()).toBe(2);
      tick();
      expect(batches[1]).toEqual(["t-4"]);
    },
  },
  {
    name: "a-terminal-frame-does-not-wait-and-what-waited-goes-first",
    run: async () => {
      const { batch, batches, tick } = harness();

      // THE ORDER IS ARRIVAL ORDER, across the two paths: two frames were held, the terminal
      // one arrived after them, and it does not overtake them. A reader's copy of the
      // conversation is built in the order it happened.
      batch.push("t-1");
      batch.push("t-2");
      batch.push("end");
      expect(batches).toEqual([["t-1", "t-2"], ["end"]]);

      // AND THE TICK THAT WAS ALREADY ASKED FOR FINDS NOTHING: the flush that the terminal
      // frame performed is the one that handed them over.
      tick();
      expect(batches).toHaveLength(2);

      // A terminal frame with nothing in front of it is delivered alone, immediately.
      batch.push("end");
      expect(batches[2]).toEqual(["end"]);
    },
  },
  {
    name: "the-hold-has-a-limit-so-a-page-that-does-not-draw-still-drains",
    run: async () => {
      const { batch, batches, asked, tick } = harness();

      // A HIDDEN TAB DOES NOT DRAW, so `requestAnimationFrame` stops coming. Past
      // `HOLD_LIMIT` frames the batch goes out anyway: delivery is never later than a
      // frame, and never lost to a tab nobody is looking at.
      for (let n = 0; n < HOLD_LIMIT; n += 1) batch.push(`t-${n}`);
      expect(batches).toHaveLength(1);
      expect(batches[0]).toHaveLength(HOLD_LIMIT);
      expect(batches[0][0]).toBe("t-0");
      expect(batches[0][HOLD_LIMIT - 1]).toBe(`t-${HOLD_LIMIT - 1}`);

      // THE ASK IS SPENT, so the next frame asks again rather than waiting for a tick that
      // has already run.
      tick();
      const before = asked();
      batch.push("t-after");
      expect(asked()).toBe(before + 1);
      tick();
      expect(batches[1]).toEqual(["t-after"]);
    },
  },
  {
    name: "a-flush-hand-over-everything-waiting-and-nothing-twice",
    run: async () => {
      const { batch, batches, tick } = harness();

      // WHAT A SOCKET'S CLOSE DOES (`lib/mux.ts`): the frames that were held are handed over
      // before the repair is asked for, so the repair reads from a position the readers have
      // actually been given.
      batch.push("t-1");
      batch.push("t-2");
      batch.flush();
      expect(batches).toEqual([["t-1", "t-2"]]);

      // AND NOTHING COMES OUT TWICE: the tick that was asked for before the flush runs
      // afterwards and finds an empty queue.
      tick();
      expect(batches).toHaveLength(1);
    },
  },
];

export const coalesceSuite: Suite = { name: "coalesce", cases };
