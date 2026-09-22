// WHAT A REASONING ROW SAYS, in one line: the first line of a thought that has
// stopped, the newest window of one that is still arriving.
//
// PURE, in the shape `relative-time.ts` and `sidebar-rows.ts` established:
// `src/lib/reasoning-preview.ts` imports nothing, so a case is parts in and a
// string out -- no render, no wire, no i18n. The rule lives in that module rather
// than in `message-parts.tsx` for exactly this reason: `message-parts.tsx` cannot
// be called from here at all (it reaches the assistant-ui runtime), so a row's
// WORDS would be unmeasurable if they were written inside it.
//
// WHAT THIS SUITE CANNOT SEE: that the row does not unfold itself, that the live
// window cuts at its LEFT edge (so the newest characters are the ones in view),
// and that the row gives the first line back when the thought ends. All three are
// properties of the RENDERED page, and they are measured in a real browser --
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

/// The whole thought, as a string of `n` characters with no whitespace in it: the
/// window boundary is a character count, and an assertion that has to think about
/// how many spaces a model put where is an assertion that can be wrong about the
/// rule while being right about the string.
const runOf = (n: number): string => "abcdefghij".repeat(Math.ceil(n / 10)).slice(0, n);

const cases: Case[] = [
  {
    name: "a-thought-that-has-stopped-says-its-first-line",
    run: async () => {
      // THE BOUND IS THE ROW'S OWN NUMBER, and it is asserted rather than
      // repeated: the clip and the window are the same count, so a case that
      // spelled 120 a second time would stay green through a change that moved
      // both and an assertion that spelled it here would pin the wrong thing.
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
    name: "a-thought-that-is-still-arriving-says-its-newest-window",
    run: async () => {
      // THE TAIL, NOT THE HEAD: the character at the END of the thought is the
      // one that just arrived, and it is the one a reader watching the row is
      // waiting for. A thought short enough for the row is handed over whole.
      expect(previewOf([said("还在想。")], [0], true)).toBe("还在想。");
      expect(previewOf([said(runOf(50))], [0], true)).toBe(runOf(50));

      // PAST THE BOUND it is the LAST 120 characters, and there is deliberately
      // NO `…`: the row's element cuts that window at its left edge, so the cut
      // lands off-screen. A mark about a part nobody can see would be a mark
      // about nothing -- and at the START of the string it would show up as an
      // ellipsis in the middle of the live text once the line had scrolled past
      // it.
      const long = `${runOf(80)}${"Z".repeat(80)}`;
      const tail = previewOf([said(long)], [0], true);
      expect(tail).toBe(long.slice(-PREVIEW_LIMIT));
      expect(tail).toHaveLength(120);
      expect(tail.endsWith("Z")).toBe(true);
      expect(tail.includes("…")).toBe(false);

      // WHITESPACE IS FLATTENED HERE AND NOT BY THE BROWSER. The row is one line
      // (`nowrap`), so a newline would collapse to a space on screen anyway --
      // but the window would have spent itself on newlines first, and a window
      // that is half newlines shows nothing.
      expect(previewOf([said("第一句。\n\n   第二句，正在写")], [0], true)).toBe("第一句。 第二句，正在写");
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