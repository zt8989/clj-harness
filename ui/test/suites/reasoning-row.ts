// WHAT A REASONING ROW IS HANDED, in one line: the first line of a thought that
// has stopped, the whole of one that is still arriving.
//
// PURE, in the shape `relative-time.ts` and `sidebar-rows.ts` established:
// `src/lib/reasoning-preview.ts` imports nothing, so a case is parts in and a
// string out -- no render, no wire, no i18n. The rule lives in that module rather
// than in `message-parts.tsx` for exactly this reason: `message-parts.tsx` cannot
// be called from here at all (it reaches the assistant-ui runtime), so a row's
// WORDS would be unmeasurable if they were written inside it.
//
// WHAT THIS SUITE CANNOT SEE: that the row does not unfold itself, that the
// WHAT THIS SUITE CAN SEE OF IT: the words (`previewOf`) and WHICH BLOCK OF THEM
// THE DOM IS HOLDING (`tailStart`) -- both pure, both a string (or a length) in and
// a string (or a number) out.
//
// WHAT IT CANNOT SEE: that the row does not unfold itself, that the window keeps the
// END of the line in view (characters leaving at the left edge while the new ones
// arrive at the right one), that the sliding is interpolated rather than a jump per
// token, and that LETTING GO OF A BLOCK IS INVISIBLE (the row pays for the block
// with the padding that puts the line back where layout would have left it). Those
// are properties of the RENDERED page, and they are measured in a real browser --
// `.scratch/thinking-row-tail/walkthrough.mjs`, which is also the only place the
// several parts of a group are read through a real runtime.
// RENDERED page, and they are measured in a real browser --
// `.scratch/thinking-row-tail/walkthrough.mjs`, which is also the only place the
// several parts of a group are read through a real runtime.
import { expect } from "vitest";

import { type Case, type Suite } from "../e2e";
import {
  PREVIEW_LIMIT,
  TAIL_DROP,
  TAIL_KEEP,
  previewOf,
  tailStart,
  thoughtAt,
} from "../../src/lib/reasoning-preview";

/// A part list, spelled the way the caller's is: every part carries its type tag,
/// and the ones this rule reads carry text. The others are here because they are
/// what makes a thought span more than the reasoning parts.
type Part = { type: string; text?: string; status?: { type: string } };

/// One message of the thread, as `thoughtAt` reads it.
type Message = { role: string; parts: Part[] };

const said = (text: string, running = false): Part => ({
  type: "reasoning",
  text,
  status: { type: running ? "running" : "complete" },
});
const call: Part = { type: "tool-call" };
const text = (body: string): Part => ({ type: "text", text: body });

/// A run of characters with nothing in it that whitespace could hide behind: the
/// flattening is the next case's subject, and a string that mixes the two would
/// make a failure belong to neither.
const runOf = (n: number): string => "abcdefghij".repeat(Math.ceil(n / 10)).slice(0, n);

const cases: Case[] = [
  {
    name: "a-thought-that-has-stopped-says-its-first-line",
    run: async () => {
      // THE BOUND IS THE ROW'S OWN NUMBER, and it is asserted rather than
      // repeated: a case that spelled 120 a second time would stay green through
      // a change that moved the bound.
      expect(PREVIEW_LIMIT).toBe(120);

      // A THOUGHT OPENS WITH A NEWLINE, often more than one, and "the first
      // line" would then be blank. The first line WITH something in it is what
      // the row shows, trimmed -- a tool row's subject is trimmed the same way.
      expect(previewOf([said("\n\n  先读 deps.edn，确认依赖。 \n还有第二句。")], false)).toBe(
        "先读 deps.edn，确认依赖。",
      );

      // AT THE BOUND: untouched, and with no `…` -- there is nothing cut to
      // ellipsise. One character past it, the `…` is the only thing that says a
      // reader is looking at part of a line.
      expect(previewOf([said(runOf(120))], false)).toBe(runOf(120));
      const cut = previewOf([said(runOf(121))], false);
      expect(cut).toBe(`${runOf(120)}…`);
      expect(cut).toHaveLength(121);

      // NOTHING TO SAY YET, and nothing in the group at all: the row is drawn
      // with its label alone rather than with a dot and a blank.
      expect(previewOf([said("\n\n")], false)).toBe("");
      expect(previewOf([], false)).toBe("");
      expect(previewOf([call], false)).toBe("");
    },
  },
  {
    name: "a-thought-that-is-still-arriving-is-handed-over-whole",
    run: async () => {
      // WHOLE, NOT WINDOWED -- and this is the case that changed with the
      // rendering. The row no longer cuts the arriving text to its last 120
      // characters: the window shows a line's worth of it and DRAGS the rest
      // (a transform, so the motion can be interpolated), and a drag needs the
      // text it has dragged past to still be there. Cutting it here would put
      // the motion back into layout -- one dropped character per token, which is
      // a snap, and that is what it looked like in a real session.
      const long = runOf(PREVIEW_LIMIT * 3);
      const arriving = previewOf([said(long, true)], true);
      expect(arriving).toBe(long);
      expect(arriving).toHaveLength(PREVIEW_LIMIT * 3);
      expect(arriving.includes("…")).toBe(false);

      // A short thought comes through untouched, and a thought that has only
      // opened with a newline has nothing to hand over yet.
      expect(previewOf([said("还在想。", true)], true)).toBe("还在想。");
      expect(previewOf([said("\n \n", true)], true)).toBe("");
      expect(previewOf([], true)).toBe("");

      // AND IT IS ONE LINE HERE TOO. A newline would collapse to a space on
      // screen anyway (`nowrap`), but it would do so after the window had spent
      // a character position on it -- so the flattening is this module's, and it
      // is the same shape the stopped half gets from `firstLine`.
      expect(previewOf([said("第一句。\n\n   第二句，正在写", true)], true)).toBe("第一句。 第二句，正在写");

      // THE FIRST LINE IS NOT WHAT IS SHOWN WHILE IT RUNS: a row that said it
      // would be a row that stopped moving a second into the thought.
      expect(arriving.startsWith("先读")).toBe(false);
      expect(previewOf([said("先读 deps.edn。\n然后再说这个。", true)], true)).toBe(
        "先读 deps.edn。 然后再说这个。",
      );
    },
  },
  {
    name: "a-thought-is-one-row-per-step-and-the-answer-does-not-end-one",
    run: async () => {
      // THE SHAPE A READER REPORTED, from one real session's frames: the thought,
      // then the answer's first token, then THE SAME THOUGHT's last token as its own
      // block (`REASONING " in Chinese."`). The runtime makes each block a message of
      // its own, so this is three messages and the walk has to cross them.
      const turn: Message[] = [
        { role: "assistant", parts: [said("The user asks in Chinese. Answer briefly")] },
        { role: "assistant", parts: [text("我是跑在")] },
        { role: "assistant", parts: [said(" in Chinese.", true)] },
      ];
      const first = thoughtAt(turn, 0);
      expect(first.drawn, "the row that began the thought draws it").toBe(true);
      expect(first.parts.map((part) => part.text)).toEqual([
        "The user asks in Chinese. Answer briefly",
        " in Chinese.",
      ]);
      // AND IT IS STILL LIVE: the thought goes on arriving after the answer started,
      // and the row that began it is where that is watched.
      expect(first.running).toBe(true);
      expect(previewOf(first.parts, first.running)).toBe("in Chinese.");
      expect(previewOf(first.parts, false)).toBe("The user asks in Chinese. Answer briefly");

      // THE LATER MESSAGE IS NOT A ROW OF ITS OWN -- that is the whole fix: it is the
      // same thought, and the row above already says it.
      expect(thoughtAt(turn, 2).drawn).toBe(false);

      // A TOOL CALL DOES END ONE. The same three messages with a call instead of the
      // answer is `想 → 读 → 再想`, which is three rows in this repo's design: the
      // second thought is a new thought about what the model just did.
      const stepped: Message[] = [
        { role: "assistant", parts: [said("先读一下。")] },
        { role: "assistant", parts: [call] },
        { role: "assistant", parts: [said("再想一次。", true)] },
      ];
      expect(thoughtAt(stepped, 0)).toEqual({
        drawn: true,
        running: false,
        parts: [expect.objectContaining({ text: "先读一下。" })],
      });
      expect(thoughtAt(stepped, 2).drawn).toBe(true);
      expect(thoughtAt(stepped, 2).running).toBe(true);

      // AND A USER MESSAGE ENDS THE TURN: a row never reaches back into the
      // conversation before it, nor forward into the next turn's answer.
      const twoTurns: Message[] = [
        { role: "assistant", parts: [said("上一轮的想法。")] },
        { role: "user", parts: [text("再问一句。")] },
        { role: "assistant", parts: [said("这一轮的想法。")] },
      ];
      expect(thoughtAt(twoTurns, 2).drawn, "the previous turn is not part of this thought").toBe(true);

      // PARTS THAT ARE NOT REASONING ARE PASSED OVER: an injected-context card
      // between two blocks is not a step either.
      const carded: Message[] = [
        { role: "assistant", parts: [said("先看这个。")] },
        { role: "assistant", parts: [{ type: "data" }, text("于是有了这张卡。")] },
        { role: "assistant", parts: [said("接着说。", true)] },
      ];
      expect(thoughtAt(carded, 0).parts).toHaveLength(2);
      expect(thoughtAt(carded, 2).drawn).toBe(false);
    },
  },
  {
    name: "a-live-run-keeps-a-whole-turn-in-one-message",
    run: async () => {
      // THE SHAPE THE ADAPTER ACTUALLY STREAMS: one assistant message per TURN, its
      // parts carrying every round -- here two thoughts and the call between them.
      // There is no message boundary to walk, which is what used to empty every
      // row: the message contained a call, so the whole of it read as a step. The
      // row is told WHICH part it is (`group.indices[0]`) and the message is read
      // from there.
      const live: Message[] = [
        {
          role: "assistant",
          parts: [
            said("先想一下。"),
            text("那我开始了。"),
            call,
            said("再看一眼。", true),
          ],
        },
      ];

      // THE FIRST THOUGHT IS THE MESSAGE'S FIRST PART and it stops at the call --
      // not at the message.
      const first = thoughtAt(live, 0, 0);
      expect(first.drawn).toBe(true);
      expect(first.parts.map((part) => part.text)).toEqual(["先想一下。"]);
      expect(previewOf(first.parts, first.running)).toBe("先想一下。");

      // THE SECOND IS A NEW THOUGHT AFTER THE CALL, read from its own part.
      const second = thoughtAt(live, 0, 3);
      expect(second.drawn).toBe(true);
      expect(second.parts.map((part) => part.text)).toEqual(["再看一眼。"]);
      expect(second.running).toBe(true);
      expect(previewOf(second.parts, second.running)).toBe("再看一眼。");

      // TEXT BETWEEN TWO REASONING PARTS IS STILL ONE THOUGHT: the later part
      // joins the row above and does not draw a row of its own.
      const split: Message[] = [
        {
          role: "assistant",
          parts: [said("开头。"), text("先答一句。"), said("接着想。")],
        },
      ];
      expect(thoughtAt(split, 0, 0).parts).toHaveLength(2);
      expect(thoughtAt(split, 0, 2).drawn).toBe(false);
    },
  },
  {
    name: "the-row-holds-a-block-of-a-live-thought-and-never-a-window",
    run: async () => {
      // WHERE THE DOM'S COPY OF A LIVE THOUGHT BEGINS. The other half of this rule --
      // that a block leaving is INVISIBLE -- is not here: the row pays for the block
      // with a padding, in the same frame, and only the rendered page can be asked
      // (`.scratch/thinking-row-tail/walkthrough.mjs`). What IS here is the shape of
      // the holding, which is what makes that payment worth making.

      // A COPY THE ROW CAN HOLD WHOLE IS HELD WHOLE, so a short thought -- and every
      // thought on its way up to the bound -- is handed over untouched.
      const short = runOf(TAIL_KEEP + TAIL_DROP);
      expect(tailStart(short, { text: short, start: 0 })).toBe(0);

      // PAST THE BOUND A BLOCK LEAVES, and it is a whole `TAIL_DROP` of characters,
      // never one: what is held stays longer than `TAIL_KEEP`, which is the window's
      // own material for the drag.
      const long = runOf(TAIL_KEEP + TAIL_DROP * 3 + 7);
      const held = tailStart(long, { text: long, start: 0 });
      expect(held).toBe(TAIL_DROP * 3);
      expect(long.length - held).toBe(TAIL_KEEP + 7);

      // THE ANSWER ONLY MOVES WHEN THE COPY CROSSES: one more character is not
      // enough, `TAIL_DROP` of them are -- and asking twice about the same text is
      // the same answer, which is what the row needs (its effect runs more than once
      // per frame).
      expect(tailStart(`${long}x`, { text: long, start: held })).toBe(held);
      const crossed = runOf(long.length + TAIL_DROP);
      expect(tailStart(crossed, { text: long, start: held })).toBe(held + TAIL_DROP);
      expect(tailStart(crossed, { text: crossed, start: held + TAIL_DROP })).toBe(
        held + TAIL_DROP,
      );

      // A DIFFERENT LINE IS NOT A GROWN ONE: a thought that arrives in two parts (the
      // vendor's `思考 · 答案 · 思考`, whose tail the runtime makes a message of its
      // own), a restored conversation, another thought -- the offsets of the old line
      // mean nothing, and the answer is the beginning of the new one.
      expect(tailStart(" in Chinese.", { text: long, start: held })).toBe(0);
      expect(tailStart("先读一下。", { text: "", start: 0 })).toBe(0);

      // AND THE COPY THE ROW HOLDS IS BOUNDED, which is the whole point of the rule:
      // over a thought that grows a character at a time, the offset never goes
      // backwards and what the DOM is asked to draw never exceeds
      // `TAIL_KEEP + TAIL_DROP` -- while the thought itself gets as long as the model
      // likes.
      let start = 0;
      let previous = "";
      for (let length = 1; length <= TAIL_KEEP * 8; length += 7) {
        const text = runOf(length);
        const next = tailStart(text, { text: previous, start });
        expect(next).toBeGreaterThanOrEqual(start);
        expect(text.length - next).toBeLessThanOrEqual(TAIL_KEEP + TAIL_DROP);
        if (length > TAIL_KEEP) expect(text.length - next).toBeGreaterThan(TAIL_KEEP);
        start = next;
        previous = text;
      }
    },
  },
];

export const reasoningRowSuite: Suite = { name: "reasoning-row", cases };