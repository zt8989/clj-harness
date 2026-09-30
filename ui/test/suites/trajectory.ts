// The trajectory as a LEDGER: how it is cut into sections, what a folded turn takes with
// it, and what the endpoint really answers.
//
// THE FIRST TWO CASES ARE PURE, and the boundary is the one the `injections` and `turns`
// suites draw: `lib/trajectory.ts` imports nothing at runtime beyond the API base, so the
// CUT (`sectionsOf`) and the READING (`rowsOf`) are pinned here over a hand-written ledger.
// HOW THE ROWS LOOK -- the seam cells, the chevron, where the pane lands -- is not visible
// to a string render and is measured in a real browser instead (ticket 05's walkthrough).
//
// THE THIRD DRIVES THE REAL ENDPOINT through the module the view uses, because the one
// thing a hand-written ledger cannot show is the WIRE: the route answers NDJSON whose
// first line is a header and whose later lines are cell batches spliced at their `from`,
// and `trajectoryFor` is the only place that rule is written down.
import { expect } from "vitest";

import { type Case, type Suite, postRun, script, threadId } from "../e2e";
import {
  type TrajectoryCell,
  type TrajectoryPayload,
  rowsOf,
  sectionsOf,
  trajectoryFor,
} from "../../src/lib/trajectory";

/// A LEDGER OF TWO TURNS, one compaction, one prompt.
///
/// It is hand-written on purpose, and every awkward shape is in it: the prompt stands
/// OUTSIDE turn one (in front of its `turn-start`), the injected context sits inside the
/// turn with the question, a tool call is answered, and the compaction -- which the model
/// never saw -- is BETWEEN the turns.
const ledger: readonly TrajectoryCell[] = [
  { index: 0, kind: "system", turn: null, text: "You are a coding agent.", initial: true },
  { index: 1, kind: "turn-start", turn: 1, calls: [{ index: 0, model: "scripted" }] },
  // THE QUESTION FIRST, THEN WHAT THE RUN WAS HANDED -- the record's order, and the one the
  // acceptance list pins (`轮内 user 在 context 之前`): the question is written when the action
  // arrives, and the injection is derived for the call that answers it.
  { index: 2, kind: "user", turn: 1, text: "读一下 README", id: "u1", at: 10 },
  {
    index: 3,
    kind: "context",
    turn: 1,
    text: "<system-reminder>\nInstructions from: /h/AGENTS.md\n\nrules\n</system-reminder>",
  },
  { index: 4, kind: "message", turn: 1, text: "", call: 0 },
  {
    index: 5,
    kind: "tool",
    turn: 1,
    toolCallId: "c1",
    name: "read",
    argsText: '{"path":"README.md"}',
    result: "# clj-harness",
    executed: true,
    arrivedAt: 12,
    executedAt: 20,
  },
  { index: 6, kind: "turn-end", turn: 1 },
  { index: 7, kind: "compacted", turn: null, text: "folded the first turn", at: 30, tokens: 900, messages: 4 },
  { index: 8, kind: "turn-start", turn: 2 },
  { index: 9, kind: "user", turn: 2, text: "再来一次" },
  { index: 10, kind: "message", turn: 2, text: "好" },
  { index: 11, kind: "turn-end", turn: 2 },
];

const cases: readonly Case[] = [
  {
    name: "the-prompt-is-not-inside-turn-one",
    run: async () => {
      const sections = sectionsOf(ledger);
      // FOUR SECTIONS, IN ORDER: the prompt, turn 1, the compaction, turn 2.
      expect(sections.map((s) => s.kind)).toEqual(["prompt", "turn", "between", "turn"]);

      const [prompt, first, between, second] = sections;
      expect(prompt.kind === "prompt" && prompt.cell.kind === "system").toBe(true);

      // TURN ONE HOLDS THE QUESTION, THE INJECTION AND THE ANSWER -- AND NOT THE PROMPT.
      const turn = first.kind === "turn" ? first.turn : null;
      expect(turn?.index).toBe(1);
      expect(turn?.cells.map((cell) => cell.kind)).toEqual(["user", "context", "message", "tool"]);
      expect(turn?.cells.some((cell) => cell.kind === "system")).toBe(false);
      expect(turn?.calls?.map((call) => call.model)).toEqual(["scripted"]);

      // THE COMPACTION IS IN NO TURN AT ALL -- it is a fact about the record, not
      // something the model was handed.
      expect(between.kind === "between" && between.cells[0]?.kind).toBe("compacted");
      expect(between.kind === "between" && between.cells[0]?.turn).toBe(null);

      // A TURN STILL OPEN IS STILL A TURN: the route writes no `turn-end` for it, and the cut
      // may not lose the whole turn because of that.
      const writing = sectionsOf([
        { index: 0, kind: "system", turn: null, text: "S" },
        { index: 1, kind: "turn-start", turn: 1 },
        { index: 2, kind: "user", turn: 1, text: "hi" },
      ]);
      expect(writing.map((s) => s.kind)).toEqual(["prompt", "turn"]);
      expect(writing[1].kind === "turn" && writing[1].turn.cells.map((c) => c.kind)).toEqual(["user"]);

      // AND THE SECOND TURN IS CUT THE SAME WAY.
      expect(second.kind === "turn" && second.turn.index).toBe(2);
      expect(second.kind === "turn" && second.turn.cells.map((cell) => cell.kind)).toEqual([
        "user",
        "message",
      ]);
    },
  },
  {
    name: "a-folded-turn-takes-its-injected-context-with-it",
    run: async () => {
      const sections = sectionsOf(ledger);
      const all = rowsOf(sections);
      // EVERY ROW: the prompt, turn 1's four, the compaction, turn 2's two.
      expect(all).toHaveLength(8);
      expect(all.some((row) => row.item === ledger[3])).toBe(true);

      const folded = rowsOf(sections, new Set([1]));
      // NOTHING OF TURN ONE IS LEFT -- the injected context included, which is the
      // owner's rule: an injection is material for the turn it was handed to.
      expect(folded.some((row) => row.turn === 1)).toBe(false);
      expect(folded).toHaveLength(4);
      // AND THE TWO THINGS THAT ARE IN NO TURN ARE STILL THERE: the prompt and the
      // compaction are not something a turn owns, so no fold can take them.
      expect(folded.some((row) => row.item.kind === "system")).toBe(true);
      expect(folded.some((row) => row.item.kind === "compacted")).toBe(true);
      expect(folded.some((row) => row.turn === 2)).toBe(true);

      // FOLDING BOTH TURNS still leaves those two, which is the whole point of the cut
      // being by turns rather than by the list.
      const both = rowsOf(sections, new Set([1, 2]));
      expect(both.map((row) => row.item.kind)).toEqual(["system", "compacted"]);
    },
  },
  {
    name: "the-endpoint-answers-a-ledger-whose-turn-is-bracketed",
    run: async () => {
      const tid = threadId("trajectory");
      script([{ content: "ok" }]);
      const resp = await postRun(tid, [{ id: "u1", role: "user", content: "hi" }]);
      expect(resp.status).toBe(200);
      await resp.text(); // drain: the run is over when the body is

      /// WAITED FOR, NOT READ ONCE (the same reason `stats`' case waits): the record's
      /// last lines land just after the response does, and the fold reads the file.
      let payload: TrajectoryPayload | null = null;
      for (let i = 0; i < 20; i += 1) {
        payload = await trajectoryFor(tid);
        if (payload !== null && !payload.incomplete) break;
        await new Promise((resolve) => setTimeout(resolve, 50));
      }
      expect(payload).not.toBeNull();
      const cells = (payload as TrajectoryPayload).cells;
      expect((payload as TrajectoryPayload).threadId).toBe(tid);
      expect((payload as TrajectoryPayload).incomplete).toBe(false);

      // THE LEDGER IS ONE ORDER, NUMBERED BY POSITION: a splice at `from` is what keeps
      // these contiguous across every batch of the stream.
      expect(cells.map((cell) => cell.index)).toEqual(cells.map((_, i) => i));
      // THE PROMPT IS FIRST AND IN NO TURN.
      expect(cells[0]?.kind).toBe("system");
      expect(cells[0]?.turn).toBe(null);
      // AND THE TURN IS BRACKETED, with the question inside it.
      const start = cells.findIndex((cell) => cell.kind === "turn-start");
      const end = cells.findIndex((cell) => cell.kind === "turn-end");
      expect(start).toBeGreaterThan(0);
      expect(end).toBeGreaterThan(start);
      expect(cells.slice(start + 1, end).some((cell) => cell.kind === "user")).toBe(true);
    },
  },
];

export const trajectorySuite: Suite = { name: "trajectory", cases };
