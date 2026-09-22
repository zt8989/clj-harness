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
// window keeps the END of the line in view (characters leaving at the left edge
// while the new ones arrive at the right one), and that the sliding is
// interpolated rather than a jump per token. All three are properties of the
// RENDERED page, and they are measured in a real browser --
// `.scratch/thinking-row-tail/walkthrough.mjs`, which is also the only place the
// several parts of a group are read through a real runtime.
import { expect } from "vitest";

import { type Case, type Suite } from "../e2e";
import { PREVIEW_LIMIT, previewOf } from "../../src/lib/reasoning-preview";

/// A part list, spelled the way the caller's is: every part carries its type tag,
/// and the ones this rule reads carry text. The others are here because they are
/// what makes a group's `indices` span more than the reasoning parts.
type Part = { type: string; text?: string };

const said = (text: string): Part => ({ type: "reasoning", text });
const call: Part = { type: "tool-call" };

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
      expect(previewOf([said("\n\n  先读 deps.edn，确认依赖。 \n还有第二句。")], [0], false)).toBe(
        "先读 deps.edn，确认依赖。",
      );

      // AT THE BOUND: untouched, and with no `…` -- there is nothing cut to
      // ellipsise. One character past it, the `…` is the only thing that says a
      // reader is looking at part of a line.
      expect(previewOf([said(runOf(120))], [0], false)).toBe(runOf(120));
      const cut = previewOf([said(runOf(121))], [0], false);
      expect(cut).toBe(`${runOf(120)}…`);
      expect(cut).toHaveLength(121);

      // NOTHING TO SAY YET, and nothing in the group at all: the row is drawn
      // with its label alone rather than with a dot and a blank.
      expect(previewOf([said("\n\n")], [0], false)).toBe("");
      expect(previewOf([], [], false)).toBe("");
      expect(previewOf([call], [0], false)).toBe("");
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
      const arriving = previewOf([said(long)], [0], true);
      expect(arriving).toBe(long);
      expect(arriving).toHaveLength(PREVIEW_LIMIT * 3);
      expect(arriving.includes("…")).toBe(false);

      // A short thought comes through untouched, and a thought that has only
      // opened with a newline has nothing to hand over yet.
      expect(previewOf([said("还在想。")], [0], true)).toBe("还在想。");
      expect(previewOf([said("\n \n")], [0], true)).toBe("");
      expect(previewOf([], [], true)).toBe("");

      // AND IT IS ONE LINE HERE TOO. A newline would collapse to a space on
      // screen anyway (`nowrap`), but it would do so after the window had spent
      // a character position on it -- so the flattening is this module's, and it
      // is the same shape the stopped half gets from `firstLine`.
      expect(previewOf([said("第一句。\n\n   第二句，正在写")], [0], true)).toBe("第一句。 第二句，正在写");

      // THE FIRST LINE IS NOT WHAT IS SHOWN WHILE IT RUNS: a row that said it
      // would be a row that stopped moving a second into the thought.
      expect(arriving.startsWith("先读")).toBe(false);
      expect(previewOf([said("先读 deps.edn。\n然后再说这个。")], [0], true)).toBe(
        "先读 deps.edn。 然后再说这个。",
      );
    },
  },
  {
    name: "a-group-is-read-from-its-own-end-in-each-state",
    run: async () => {
      // A GROUP CAN HOLD SEVERAL PARTS -- a turn that thinks, reads and thinks
      // again is two of them -- and the two halves of the rule walk the group in
      // OPPOSITE directions. At rest the FIRST part with a line wins, because
      // joining parts would put a seam in the middle of a sentence; while the
      // thought is arriving the LAST one with text wins, because that is the part
      // the model is writing NOW.
      const parts: Part[] = [
        said("第一段：先看看这个项目。"),
        call,
        said("第二段：现在改这一行。"),
      ];
      expect(previewOf(parts, [0, 2], false)).toBe("第一段：先看看这个项目。");
      expect(previewOf(parts, [0, 2], true)).toBe("第二段：现在改这一行。");

      // A PART WITH NOTHING IN IT IS SKIPPED BY BOTH -- the thought that opens
      // with a newline is one part, and a group where the newest part has not
      // received its first token yet is the other. Neither should read as a dot
      // followed by nothing.
      const blank: Part[] = [said("\n  \n"), said("有话说。")];
      expect(previewOf(blank, [0, 1], false)).toBe("有话说。");
      expect(previewOf(blank, [0, 1], true)).toBe("有话说。");

      // THE INDICES ARE THE GROUP'S, so a part it does not cover is not read even
      // when the list holds one -- and an index with nothing behind it (the end
      // of the list, a group rebuilt while its parts were still arriving) is a
      // row with nothing to say rather than a crash.
      expect(previewOf(parts, [0], true)).toBe("第一段：先看看这个项目。");
      expect(previewOf(parts, [3], false)).toBe("");
      expect(previewOf(parts, [3], true)).toBe("");
    },
  },
];

export const reasoningRowSuite: Suite = { name: "reasoning-row", cases };