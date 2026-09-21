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
//
// THE FOURTH IS THE CONVERSATION'S OPENING (`.scratch/session-opening`), where a card
// and the text the model read sit in the SAME message: it pins that the card wins on
// screen, and -- because the entry's id is stable -- that a copy still carrying the text
// crosses the wire under that id, which is what lets the server drop the repeat instead
// of writing the opening into the conversation a second time.
//
// AND THE FIFTH IS THE OTHER HALF OF THE SAME FEATURE, added when the opening started
// arriving as `role: "user"` entries: the SHAPE the thread draws them in. Before it, the
// person's AGENTS.md was drawn as a message they had typed -- right-aligned, in a bubble,
// with an Edit pencil. `isCardOnly` is the test that stops that, and it is pure, so it is
// pinned over literals here like everything else in this file.
import { expect } from "vitest";
import { toAgUiMessages } from "@assistant-ui/react-ag-ui";

import { type Case, type Suite } from "../e2e";
import {
  INJECTION_PART,
  injectionView,
  isCardOnly,
  isOpeningEntryId,
  keepInjectionCards,
  textOfParts,
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
  {
    name: "an-opening-entry-keeps-its-card-and-not-the-text-beside-it",
    run: async () => {
      // THE CONVERSATION'S OPENING IS ONE MESSAGE WITH TWO READINGS
      // (`.scratch/session-opening`): a `data` part that is the card a person sees and
      // a text part that is what the model read. The adapter keeps the text (it has no
      // case for the part), so without this the rebuilt message would draw the block
      // twice -- as prose and as the card. `keepInjectionCards` replaces the whole
      // content with the card, so the block is read once, in the shape it arrived in.
      //
      // THE ROLE IS `user` AND THAT IS THE POINT (ticket 02): these entries are user
      // messages to the provider -- `harness.edge.ag_ui/opening-entries` keeps the role
      // the instruction blocks arrived with -- so what a rebuild hands the thread is a
      // USER message holding a card. The literal here said `assistant` until this
      // ticket, which is why a machine gate stayed green while the app drew the opening
      // as something the person had typed.
      const opening = [
        {
          id: "session-opening-0",
          role: "user" as const,
          content: [
            {
              type: "data" as const,
              name: INJECTION_PART,
              data: { role: "user", text: "<instructions>STANDING RULE</instructions>" },
            },
            { type: "text" as const, text: "<instructions>STANDING RULE</instructions>" },
          ],
        },
      ];
      const converted = [
        {
          id: "session-opening-0",
          role: "user" as const,
          content: "<instructions>STANDING RULE</instructions>",
        },
      ];
      const kept = keepInjectionCards(opening, converted);
      expect(kept[0]?.content).toEqual([
        {
          type: "data",
          name: INJECTION_PART,
          data: { role: "user", text: "<instructions>STANDING RULE</instructions>" },
        },
      ]);
      // AND IT IS NOW A CARD AND NOTHING ELSE, which is what keeps the bubble away.
      expect(isCardOnly(kept[0]?.content as readonly unknown[])).toBe(true);

      // AND A COPY THAT STILL CARRIES THE TEXT CROSSES THE WIRE AS THE TEXT -- the other
      // half of the same fact: the entry's id is STABLE, so the server recognises the
      // repeat and drops it (`harness.edge.sessions/append!`). Note the two spellings
      // this pins: the card never goes back, and the words do.
      const sent = toAgUiMessages([
        {
          id: "session-opening-0",
          role: "user" as const,
          content: [
            { type: "text" as const, text: "<instructions>STANDING RULE</instructions>" },
            {
              type: "data" as const,
              name: INJECTION_PART,
              data: { role: "user", text: "<instructions>STANDING RULE</instructions>" },
            },
          ],
        },
      ] as never);
      expect(JSON.stringify(sent)).not.toContain(INJECTION_PART);
      expect(JSON.stringify(sent)).toContain("STANDING RULE");

      // WHAT THE THREAD ACTUALLY HOLDS IS THE CARD ALONE (the `kept` message above), and
      // an assistant message of that shape is not sent at all -- but a USER one IS, as an
      // EMPTY message under the entry's id. That is the shape a real client sends, and it
      // is the sharpest statement of why the id is stable: what crosses is a husk, and the
      // session recognises it as a message it already holds and drops it
      // (`harness.edge.sessions/append!`). Without the id this would be a brand-new empty
      // turn entering the conversation on every run.
      expect(toAgUiMessages([kept[0]] as never)).toEqual([
        { id: "session-opening-0", role: "user", content: "" },
      ]);
    },
  },
  {
    name: "an-opening-the-snapshot-brought-is-still-a-card",
    run: async () => {
      // THE SECOND WAY AN OPENING REACHES A PAGE (2026-09-21). The run that BIRTHS a
      // conversation hands the messages it wrote to the page that minted it, as an AG-UI
      // `MESSAGES_SNAPSHOT` (`harness.edge.ag_ui/conversation-snapshot`) -- and upstream's
      // snapshot conversion keeps a user message's id and TEXT and drops its `data` part.
      // So the thread holds a user message of words, under the entry's own id, and
      // drawing it as a bubble would be the bug ticket 02 fixed: the person's AGENTS.md
      // shown as something they typed.
      expect(isOpeningEntryId("session-opening-0")).toBe(true);
      expect(isOpeningEntryId("session-opening-12")).toBe(true);
      // NOT AN ID THIS MODULE OWNS: a person's message, a run's own derived card
      // (`<run-id>-ctx1`), the birth context, and anything that merely starts with the
      // prefix -- a suffix that is not a number is some other id that borrowed it.
      expect(isOpeningEntryId("u1")).toBe(false);
      expect(isOpeningEntryId("session-context")).toBe(false);
      expect(isOpeningEntryId("r1-ctx1")).toBe(false);
      expect(isOpeningEntryId("session-opening-x")).toBe(false);
      expect(isOpeningEntryId(undefined)).toBe(false);

      // WHAT THE CARD SAYS IS THE MESSAGE'S OWN TEXT, so the row and its numbers are the
      // same ones the `data` part carries on the other path (`opening-entries` builds
      // both from the one block).
      const parts = [
        { type: "text", text: "<instructions>\nSTANDING RULE\n</instructions>" },
      ];
      expect(injectionView({ role: "user", text: textOfParts(parts) })).toEqual({
        title: "instructions",
        preview: "<instructions>",
        bytes: 44,
      });

      // SEVERAL TEXT PARTS ARE ONE MESSAGE, in order, one line apart -- the same join the
      // card's value uses on the server (`injection-value`).
      expect(textOfParts([{ type: "text", text: "a" }, { type: "text", text: "b" }])).toBe("a\nb");

      // AND NOTHING ELSE IS TEXT: an attachment or a data part beside it is not words,
      // and a message with no text at all is a message this rule has nothing to draw.
      expect(textOfParts([{ type: "file", name: "f" }])).toBe("");
      expect(textOfParts([])).toBe("");
      expect(injectionView({ role: "user", text: textOfParts([]) })).toBeNull();
    },
  },
  {
    name: "a-card-only-message-is-not-a-bubble",
    run: async () => {
      // THE SHAPE THE THREAD DRAWS AN OPENING ENTRY IN (ticket 02). The test is on the
      // PARTS, not on the role, because the role cannot tell the two apart: an opening
      // entry is `user`, exactly like the words a person typed.
      const card = {
        type: "data",
        name: INJECTION_PART,
        data: { role: "user", text: "<instructions>STANDING RULE</instructions>" },
      };

      expect(isCardOnly([card])).toBe(true);

      // WORDS BESIDE THE CARD ARE WORDS: somebody's message that happens to carry a card
      // still belongs in a bubble, and `keepInjectionCards` is what turns the opening
      // into the card-only shape above.
      expect(isCardOnly([{ type: "text", text: "你好" }, card])).toBe(false);
      expect(isCardOnly([{ type: "text", text: "你好" }])).toBe(false);

      // AN EMPTY MESSAGE IS NOT A CARD EITHER: the rebuild hands back an empty assistant
      // message for a card it dropped, and a row drawn for that would claim an injection
      // nobody can check.
      expect(isCardOnly([])).toBe(false);

      // AND SOMEONE ELSE'S `data` PART IS NOT OURS: this module owns one name.
      expect(isCardOnly([{ type: "data", name: "something-else", data: {} }])).toBe(false);

      // A FILE OR AN IMAGE IS THE SAME KIND OF PART AND A DIFFERENT KIND OF THING: both
      // would be swallowed by a test that only asked "is this a data part".
      expect(isCardOnly([{ type: "file", name: INJECTION_PART }])).toBe(false);
      expect(isCardOnly([{ type: "image", name: INJECTION_PART }])).toBe(false);
    },
  },
];

export const injectionSuite: Suite = { name: "injections", cases };
