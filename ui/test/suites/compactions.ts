// The compaction card: what its collapsed row says, and the three conversion facts it shares with
// the injection card.
//
// ALL PURE, and the boundary is the same one the `injections` suite draws: `lib/compactions.ts`
// imports nothing at runtime, so the arithmetic (a preview, two counts) is pinned here over
// literals. HOW THE CARD LOOKS -- where it sits, whether it is folded, what a click opens -- is not
// visible to a string render and is measured in a real browser instead
// (`.scratch/compaction-frames/evidence/`).
//
// THE OTHER THREE CASES ARE ABOUT THE RULE THAT IS SHARED, not about this card's arithmetic: which
// parts count as a card, that a message holding only cards is not a bubble, and that a rebuilt
// conversation gets its parts back. They live in `lib/card-parts.ts` and are pinned here for the
// SECOND name -- the injection suite pins the same rule for the first -- because a name missing from
// that list is a card drawn as an empty bubble that says nothing.
//
// AND THE LAST IS THE CONTRACT THE WHOLE FEATURE RESTS ON, the same one the injection card rests on
// and not about our module at all: `toAgUiMessages` sends text, reasoning and tool calls, and no
// `data` part. It is what makes a card a view rather than a message.
import { expect } from "vitest";
import { toAgUiMessages } from "@assistant-ui/react-ag-ui";

import { type Case, type Suite } from "../e2e";
import { CARD_PARTS, isCardOnly, keepCardParts } from "../../src/lib/card-parts";
import { COMPACTION_PART, compactionText, compactionView } from "../../src/lib/compactions";
import { INJECTION_PART } from "../../src/lib/injections";

/// A card part, as a frame's value becomes one (`{type: "data", name, data}`).
function card(value: unknown) {
  return { type: "data", name: COMPACTION_PART, data: value };
}

const cases: readonly Case[] = [
  {
    name: "a-compaction-card-says-what-was-folded-and-what-it-cost",
    run: async () => {
      // THE ROW IS ONE LINE, so the preview is the summary's first non-empty line and the body is
      // where the whole thing is read. The two numbers are what the folded range cost: how many
      // tokens it was estimated at, and how many nodes went into the summary.
      const view = compactionView({
        summary: "folded: the first twenty turns\nand the two decisions they made",
        tokens: 12345,
        messages: 21,
      });
      expect(view).toEqual({
        preview: "folded: the first twenty turns",
        tokens: 12345,
        messages: 21,
      });
      // AND THE BODY IS THE WHOLE SUMMARY, not the row's one line -- including the leading blank
      // lines the preview skipped.
      expect(compactionText({ summary: "\n\n  the whole thing  \n" })).toBe("\n\n  the whole thing  \n");

      // A FRAME THAT DID NOT SAY MUST NOT BECOME A ZERO. `0 tok` is a claim about the size of the
      // folded range, and a card that invented one would be lying about the one thing it reports.
      expect(compactionView({ summary: "S" })).toEqual({
        preview: "S",
        tokens: null,
        messages: null,
      });
      expect(compactionView({ summary: "S", tokens: -1, messages: 1.5 })).toEqual({
        preview: "S",
        tokens: null,
        messages: null,
      });

      // AND A FRAME WITH NOTHING TO SAY IS NOT A CARD: a row drawn for it would claim a compaction
      // nobody can check, so the answer is null and the caller draws nothing.
      expect(compactionView({ summary: "   \n\n" })).toBeNull();
      expect(compactionView({ tokens: 12, messages: 2 })).toBeNull();
      expect(compactionView(null)).toBeNull();
      expect(compactionView("not a value")).toBeNull();
      expect(compactionText(null)).toBe("");
      expect(compactionText({ summary: 42 })).toBe("");
    },
  },
  {
    name: "a-compaction-card-is-a-card-and-not-a-bubble",
    run: async () => {
      // BOTH NAMES ARE CARDS (`lib/card-parts.ts`), and this is the rule the move exists for: a
      // card-only message is drawn by the renderer its part's name selects, and the thread must not
      // put a bubble around it -- for either kind.
      expect(isCardOnly([card({ summary: "S" })])).toBe(true);
      expect(isCardOnly([{ type: "data", name: INJECTION_PART, data: { text: "<skills>x</skills>" } }])).toBe(
        true,
      );
      expect(CARD_PARTS).toEqual([INJECTION_PART, COMPACTION_PART]);

      // WORDS BESIDE THE CARD ARE WORDS: an assistant message that also answered still belongs in a
      // bubble, and a card is not something a person typed.
      expect(isCardOnly([{ type: "text", text: "收到" }, card({ summary: "S" })])).toBe(false);
      // AN EMPTY MESSAGE IS NOT A CARD EITHER: the rebuild hands back an empty assistant message for
      // a card it dropped, and a row drawn for that would claim a compaction nobody can check.
      expect(isCardOnly([])).toBe(false);
      // AND SOMEONE ELSE'S `data` PART IS NOT OURS.
      expect(isCardOnly([{ type: "data", name: "something-else", data: {} }])).toBe(false);
      // A FILE OR AN IMAGE IS THE SAME KIND OF PART AND A DIFFERENT KIND OF THING.
      expect(isCardOnly([{ type: "file", name: COMPACTION_PART }])).toBe(false);
      expect(isCardOnly([{ type: "image", name: COMPACTION_PART }])).toBe(false);
    },
  },
  {
    name: "a-rebuilt-conversation-gets-its-compaction-card-back",
    run: async () => {
      // THE REBUILD'S OWN SHAPE: the server's fold makes one card-only assistant message per frame
      // (`harness.kernel.frames/apply-frames`), and the adapter's `toAssistantSnapshotMessage`
      // keeps text and tool calls only -- so the card message arrives with EMPTY content, which is
      // the state `keepCardParts` is written for.
      const rebuilt = [
        { id: "u1", role: "user", content: "你好" },
        {
          id: "c-1",
          role: "assistant",
          content: [card({ summary: "the head", tokens: 1200, messages: 4 })],
        },
        { id: "a1", role: "assistant", content: "收到" },
      ];
      const converted = [
        { id: "u1", role: "user" as const, content: "你好" },
        { id: "c-1", role: "assistant" as const, content: "" },
        { id: "a1", role: "assistant" as const, content: "收到" },
      ];
      const kept = keepCardParts(rebuilt, converted);
      expect(kept[1]?.content).toEqual([card({ summary: "the head", tokens: 1200, messages: 4 })]);

      // MATCHED BY ID AND NOT BY INDEX, and this is why: a rebuild whose tool result patched into
      // the assistant message before it shifts every later index, and an index carry-over would hang
      // one turn's card on another turn's message.
      const shifted = [
        { id: "u1", role: "user" as const, content: "你好" },
        { id: "a1", role: "assistant" as const, content: "" },
        { id: "c-1", role: "assistant" as const, content: "" },
      ];
      const byId = keepCardParts(rebuilt, shifted);
      expect(byId[1]?.content).toBe("");
      expect(byId[2]?.content).toEqual([card({ summary: "the head", tokens: 1200, messages: 4 })]);

      // BOTH CARDS COME BACK IN ONE PASS: the list is over names rather than over a name, so a
      // conversation that was compacted AND had something injected gets both parts restored.
      const both = [
        { id: "u1", role: "user", content: "你好" },
        {
          id: "r1-ctx1",
          role: "assistant",
          content: [{ type: "data", name: INJECTION_PART, data: { role: "user", text: "<skills>x</skills>" } }],
        },
        { id: "c-1", role: "assistant", content: [card({ summary: "the head" })] },
      ];
      const stripped = [
        { id: "u1", role: "user" as const, content: "你好" },
        { id: "r1-ctx1", role: "assistant" as const, content: "" },
        { id: "c-1", role: "assistant" as const, content: "" },
      ];
      const back = keepCardParts(both, stripped);
      expect(back[1]?.content).toEqual([
        { type: "data", name: INJECTION_PART, data: { role: "user", text: "<skills>x</skills>" } },
      ]);
      expect(back[2]?.content).toEqual([card({ summary: "the head" })]);

      // NOTHING TO PUT BACK IS NOTHING CHANGED, byte for byte.
      const plain = [{ id: "u1", role: "user" as const, content: "你好" }];
      expect(keepCardParts([], plain)).toEqual(plain);
      // AND A `data` PART OF SOMEONE ELSE'S NAME IS LEFT ALONE.
      expect(
        keepCardParts(
          [{ id: "x1", role: "assistant", content: [{ type: "data", name: "other", data: {} }] }],
          [{ id: "x1", role: "assistant" as const, content: "" }],
        )[0]?.content,
      ).toBe("");
    },
  },
  {
    name: "the-compaction-card-is-never-sent-back-to-the-server",
    run: async () => {
      // THE CONTRACT THE FEATURE RESTS ON, and it is the adapter's behaviour rather than our
      // module's: a card is a `data` part, and the outgoing conversion has no case for one. So the
      // next request's history is byte for byte what it was before the card existed -- the summary
      // the model reads arrives by the projection of the compaction fact, never by this card.
      const withCard = [
        {
          id: "c-1",
          role: "assistant" as const,
          content: [{ type: "data" as const, name: COMPACTION_PART, data: { summary: "the head" } }],
        },
      ];
      expect(toAgUiMessages(withCard as never)).toEqual([]);

      // AND A MESSAGE WITH BOTH: the text goes, the card does not. This is the shape the live path
      // produces -- the adapter pushes the data part into the message that is streaming at that
      // moment, so a card usually rides along with an assistant message that also has text.
      const mixed = [
        {
          id: "a1",
          role: "assistant" as const,
          content: [
            { type: "text" as const, text: "收到" },
            { type: "data" as const, name: COMPACTION_PART, data: { summary: "the head" } },
          ],
        },
      ];
      const sent = toAgUiMessages(mixed as never);
      expect(sent).toHaveLength(1);
      expect(JSON.stringify(sent)).not.toContain(COMPACTION_PART);
    },
  },
];

export const compactionSuite: Suite = { name: "compactions", cases };
