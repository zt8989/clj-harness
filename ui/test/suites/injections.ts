// The injection card: what its collapsed row says, and the two conversion facts it
// depends on.
//
// ALL PURE, and the boundary is the same one the `turns` and `context` suites draw:
// `lib/injections.ts` imports nothing at runtime, so the arithmetic (a title, a
// preview, a byte count; and putting a card back into a rebuilt conversation) is
// pinned here over literals. HOW THE CARD LOOKS -- where it sits, whether it is
// folded, what a click opens -- is not visible to a string render and is measured in
// a real browser instead (`.scratch/context-frames/evidence/`).
//
// THE THIRD CASE IS THE ONE THAT KEEPS THE FEATURE HONEST, and it is not about our
// module at all: it pins the ADAPTER's behaviour -- `toAgUiMessages` sends text,
// reasoning and tool calls, and no `data` part. Everything else rests on it: it is
// what makes the card a view rather than a message, so an injection can be visible in
// the conversation without being part of it. When the adapter is upgraded, this is
// the case that has to be re-read first.
import { expect } from "vitest";
import { toAgUiMessages } from "@assistant-ui/react-ag-ui";

import { type Case, type Suite } from "../e2e";
import {
  INJECTION_PART,
  injectionView,
  keepInjectionCards,
} from "../../src/lib/injections";

/// A rebuilt message list as the server's fold writes one: the seed's user message,
/// then a card message for the injection (an assistant message whose content is the
/// one data part -- see harness.kernel.frames/apply-frames).
function rebuiltWithCard(text: string) {
  return [
    { id: "u1", role: "user", content: "你好" },
    {
      id: "r1-ctx1",
      role: "assistant",
      content: [{ type: "data", name: INJECTION_PART, data: { role: "user", text } }],
    },
    { id: "a1", role: "assistant", content: "收到" },
  ];
}

const cases: readonly Case[] = [
  {
    name: "a-card-says-which-tag-it-came-with-and-how-many-bytes",
    run: async () => {
      // A skill body: the title is the TAG, not the whole first line, because the
      // tags are the server's own frame for these blocks and the row has one line.
      const skill = injectionView({
        role: "user",
        text: '<skill name="tdd">\nred green refactor\n</skill>',
      });
      expect(skill).toEqual({
        title: "skill",
        preview: '<skill name="tdd">',
        bytes: 46,
      });

      // A job's ending, which is what a background command's notice looks like.
      const ending = injectionView({
        role: "user",
        text: '<job-ended id="j1" path="/tmp/j1.log">[exit 0]</job-ended>',
      });
      expect(ending?.title).toBe("job-ended");
      expect(ending?.preview).toBe('<job-ended id="j1" path="/tmp/j1.log">[exit 0]</job-ended>');

      // BYTES, NOT CHARACTERS: a Chinese instruction block is three bytes a
      // character in UTF-8, and bytes are what the model is paying for. Six
      // characters here, eighteen bytes.
      expect(injectionView({ role: "user", text: "注入的上下文" })?.bytes).toBe(18);

      // AND A BLOCK WITH NOTHING IN IT IS NOT A CARD. A row drawn for it would claim
      // an injection nobody can check, so the answer is null and the caller draws
      // nothing.
      expect(injectionView({ role: "user", text: "   \n\n" })).toBeNull();
      expect(injectionView({ role: "user" })).toBeNull();
      expect(injectionView(null)).toBeNull();
      expect(injectionView("not a value")).toBeNull();

      // A block that does not open with a tag still gets a row: the first non-empty
      // line is the title, and leading blank lines are not the title.
      expect(injectionView({ role: "user", text: "\n\n<project>abc</project>" })?.title).toBe(
        "project",
      );
      expect(injectionView({ role: "user", text: "plain words" })?.title).toBe("plain words");
    },
  },
  {
    name: "a-rebuilt-conversation-gets-its-cards-back",
    run: async () => {
      // What the ADAPTER hands back for a rebuild: the card message survives with its
      // id but with EMPTY content -- `toAssistantSnapshotMessage` keeps text and tool
      // calls only. This is the state `keepInjectionCards` is written for.
      const converted = [
        { id: "u1", role: "user" as const, content: "你好" },
        { id: "r1-ctx1", role: "assistant" as const, content: "" },
        { id: "a1", role: "assistant" as const, content: "收到" },
      ];
      const kept = keepInjectionCards(rebuiltWithCard("<skills>a catalog</skills>"), converted);
      expect(kept[1]?.content).toEqual([
        {
          type: "data",
          name: INJECTION_PART,
          data: { role: "user", text: "<skills>a catalog</skills>" },
        },
      ]);

      // MATCHED BY ID, NOT BY INDEX, and this case is why: a rebuild whose tool result
      // patched into the assistant message before it (which is what the adapter does)
      // shifts every later index, and an index carry-over would hang one turn's card on
      // another turn's message.
      const shifted = [
        { id: "u1", role: "user" as const, content: "你好" },
        { id: "a1", role: "assistant" as const, content: "" },
        { id: "r1-ctx1", role: "assistant" as const, content: "" },
      ];
      const byId = keepInjectionCards(rebuiltWithCard("<instructions>x</instructions>"), shifted);
      expect(byId[1]?.content).toBe("");
      expect(byId[2]?.content).toEqual([
        {
          type: "data",
          name: INJECTION_PART,
          data: { role: "user", text: "<instructions>x</instructions>" },
        },
      ]);

      // NOTHING TO PUT BACK IS NOTHING CHANGED: a conversation recorded before this
      // feature (or one with no injections in it) comes through untouched, byte for
      // byte -- the same list instance is not reused, but every message is identical.
      const plain = [{ id: "u1", role: "user" as const, content: "你好" }];
      expect(keepInjectionCards([], plain)).toEqual(plain);

      // A `data` part of SOMEONE ELSE'S name is left alone: this module owns one name.
      const foreign = [
        {
          id: "x1",
          role: "assistant",
          content: [{ type: "data", name: "something-else", data: {} }],
        },
      ];
      const convertedForeign = [{ id: "x1", role: "assistant" as const, content: "" }];
      expect(keepInjectionCards(foreign, convertedForeign)[0]?.content).toBe("");
    },
  },
  {
    name: "the-card-is-never-sent-back-to-the-server",
    run: async () => {
      // THE CONTRACT THE WHOLE FEATURE RESTS ON. A card is a `data` part, and the
      // outgoing conversion has no case for one: text, reasoning and tool calls are
      // what go back. So the client can display an injection without holding it, and
      // the next run's history is byte for byte what it was before the card existed.
      const withCard = [
        {
          id: "r1-ctx1",
          role: "assistant" as const,
          content: [{ type: "data" as const, name: INJECTION_PART, data: { text: "<skills>x</skills>" } }],
        },
      ];
      expect(toAgUiMessages(withCard as never)).toEqual([]);

      // AND A MESSAGE WITH BOTH: the text goes, the card does not. This is the shape
      // the live path actually produces -- the adapter pushes the data part into the
      // message that is streaming at that moment, so a card usually rides along with
      // an assistant message that also has text.
      const mixed = [
        {
          id: "a1",
          role: "assistant" as const,
          content: [
            { type: "text" as const, text: "收到" },
            { type: "data" as const, name: INJECTION_PART, data: { text: "<skills>x</skills>" } },
          ],
        },
      ];
      const sent = toAgUiMessages(mixed as never);
      expect(sent).toHaveLength(1);
      expect(JSON.stringify(sent)).not.toContain(INJECTION_PART);
    },
  },
];

export const injectionSuite: Suite = { name: "injections", cases };
