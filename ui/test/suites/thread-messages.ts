// WHAT A REBUILT CONVERSATION IS HANDED, message by message: the window's `state` decides the
// status of the running TAIL, and the AG-UI adapter's own reading is kept for everything else.
//
// ================================================================ why this file
//
// THE BUG (owner's report, 2026-09-25): after a reload, a `bash` call -- or any tool still in
// flight -- was drawn with an exclamation (待审批) instead of a spinner, while the run was
// plainly still going. The server's word was right there in the window (`state: "running"`) and
// the client dropped it on the floor.
//
// WHERE IT WAS DROPPED, and why holding it takes a suite: `fromAgUiMessages` puts a status on
// every assistant message it converts, and a message whose tool call has no result yet gets
// `requires-action` -- right for a PARKED run (that is the shape the approval card needs) and
// wrong for one that is still writing that call. The page corrects it for the running tail ...
// except that `fromThreadMessageLike` prefers the message's OWN `status` over the fallback it is
// handed (`status: status ?? fallbackStatus`), so the correction was silently ignored.
// `lib/thread-messages.ts` therefore puts the status ON the message, and the cases below pin
// both halves: the running tail wins, and every other reading stays the adapter's.
//
// WHAT THIS SUITE CANNOT SEE: how a row LOOKS. `components/message-parts.tsx` draws a
// `requires-action` tool part as 待审批 and a running one as a spinner; that mapping belongs to
// the `tool-row` suite, and whether a real reload really lands in a running turn is the browser
// walkthrough's (`.scratch/refreshed-turn-keeps-growing/`, which is where this was measured).
import { expect } from "vitest";
import { fromAgUiMessages } from "@assistant-ui/react-ag-ui";

import { type Case, type Suite } from "../e2e";
import { readsOf, toThreadMessages } from "../../src/lib/thread-messages";

/// ONE ACTION'S ROWS AS THE SERVER FOLDS THEM: the question, and a model message whose `bash`
/// call has not come back. This IS the window's shape -- `GET /api/threads/<stem>/page` answers
/// exactly these objects (`{id, role, content, toolCalls}`) -- which is why a literal is the
/// fixture here rather than a hand-built `ThreadMessageLike`.
const entries = [
  { id: "u1", role: "user", content: "run it" },
  {
    id: "m1",
    role: "assistant",
    content: "",
    toolCalls: [
      { id: "c1", type: "function", function: { name: "bash", arguments: '{"command":"sleep 30"}' } },
    ],
  },
];

/// The tool call the runtime ended up holding, or undefined when the import lost it.
const callOf = (messages: ReturnType<typeof toThreadMessages>) => {
  for (const part of messages[messages.length - 1]?.content ?? []) {
    if (part.type === "tool-call") return part;
  }
  return undefined;
};

const cases: Case[] = [
  {
    name: "a-tool-call-still-in-flight-is-requires-action-to-the-adapter-and-running-when-the-window-says-so",
    run: async () => {
      // THE ADAPTER'S OWN READING, pinned because the correction below is only meaningful against
      // it -- and because an upgrade that stops making it is something to re-read first (the same
      // rule the `injections` suite states for `toAgUiMessages`).
      const guessed = fromAgUiMessages(entries);
      expect(guessed[guessed.length - 1]?.status?.type).toBe("requires-action");

      // THE SERVER'S WORD WINS for the tail it is about, and the call is still unanswered there.
      const running = toThreadMessages(entries, "running");
      expect(running[running.length - 1]?.status?.type).toBe("running");
      expect(callOf(running)?.result).toBeUndefined();

      // ...AND IT WINS ON THE MESSAGE, which is the half that was missing: handed as a fallback
      // alone, the message's own status is what `fromThreadMessageLike` keeps. This assertion is
      // therefore the difference between "the override is passed" and "the override lands".
      expect(running[1]?.status).toEqual({ type: "running" });

      // THE PARKED RUN IS UNTOUCHED: its `requires-action` is the approval card's, and correcting
      // it away would shut the only door out of a parked turn.
      expect(toThreadMessages(entries, "parked")[1]?.status?.type).toBe("requires-action");

      // AND NOTHING ELSE IS OVERRIDDEN EITHER: a settled window, and the door that has no window
      // at all (`rebuild`, a session this page just minted), keep the adapter's reading.
      expect(toThreadMessages(entries, "settled")[1]?.status?.type).toBe("requires-action");
      expect(toThreadMessages(entries, null)[1]?.status?.type).toBe("requires-action");
    },
  },
  {
    name: "how-far-along-a-conversation-is-only-ever-one-of-the-four-words",
    run: async () => {
      // `readsOf` is the door between the wire's string and the reading the rule above takes, so
      // a server that grows a fifth word has to read as "not running" rather than as a claim.
      for (const word of ["running", "parked", "settled", "unfinished"]) {
        expect(readsOf(word)).toBe(word);
      }
      for (const other of [null, undefined, "", "RUNNING", "something-else"]) {
        expect(readsOf(other)).toBeNull();
      }
    },
  },
  {
    name: "a-park-stays-the-last-assistant-message-when-the-thinking-arrived-after-it",
    run: async () => {
      // THE SHAPE A PAGE FOLLOWING A RUN IS LEFT HOLDING (owner's report, 2026-09-30, sessions
      // `b92dfd61` / `62f30024`). The server's fold has the pair the other way round
      // (`[... reasoning(r59), assistant(m60, interrupts)]`) and a reload imports exactly that.
      // But `lib/window.ts`'s `merged` updates an entry it knows IN PLACE and APPENDS every entry
      // that is new to it, so a page that already held the question -- from the run's own frames,
      // which never carried the reasoning -- gets the thinking added AFTER it. Upstream finds a
      // park with `findLast(m => m.role === "assistant")`, so this order is a card that draws
      // nowhere (and `submitInterruptResponses` throws "no pending interrupts on this thread").
      const held = [
        { id: "u1", role: "user", content: "参考 dsh 的 goal 功能帮我实现一套" },
        {
          id: "m60",
          role: "assistant",
          content: "动手前有四件事只有你能定，一次问完：",
          toolCalls: [
            { id: "c1", type: "function", function: { name: "ask", arguments: '{"questions":[]}' } },
          ],
          metadata: {
            custom: {
              agui: {
                interrupts: [
                  { id: "i1", reason: "elicitation", message: "四件事", toolCallId: "c1" },
                ],
              },
            },
          },
        },
        { id: "r59", role: "reasoning", content: "I have a good picture now." },
      ];

      const interruptsOf = (message: unknown) =>
        (message as { metadata?: { custom?: Record<string, { interrupts?: readonly unknown[] }> } })
          .metadata?.custom?.agui?.interrupts;

      const messages = toThreadMessages(held, null);
      const last = messages[messages.length - 1];

      // THE PARK IS LAST, WEARING THE READING THE CARD IS DRAWN FROM.
      expect(last?.role).toBe("assistant");
      expect(interruptsOf(last)).toHaveLength(1);
      expect(last?.status?.type).toBe("requires-action");
      expect((last?.status as { reason?: string } | undefined)?.reason).toBe("interrupt");
      // ...and it is the LAST ASSISTANT, which is the question upstream actually asks.
      expect([...messages].reverse().find((m) => m.role === "assistant")).toBe(last);

      // THE THINKING IS STILL THERE, moved in front of the question the way the server folds it:
      // nothing is dropped to make room for the card.
      expect(messages).toHaveLength(held.length);
      expect(messages[held.length - 2]?.id).toBe("r59");
      expect(
        (messages[held.length - 2]?.content as unknown as { type?: string }[])[0]?.type,
      ).toBe("reasoning");

      // A CONVERSATION WITH NO PARK COMES BACK UNTOUCHED (the common shape costs nothing)...
      expect(toThreadMessages(entries, null).map((m) => m.id)).toEqual(["u1", "m1"]);
      // ...and so does one whose park is already last, which is every reload.
      const alreadyLast = [held[0], held[2], held[1]];
      expect(toThreadMessages(alreadyLast, null).map((m) => m.id)).toEqual(["u1", "r59", "m60"]);
    },
  },
  {
    name: "a-window-that-opens-on-a-tool-result-drops-it-instead-of-inventing-a-tool",
    run: async () => {
      // THE TAIL PAGE'S FIRST ENTRY CAN BE A RESULT (owner's report, 2026-10-02, session
      // `4f1f48d5`): `GET /api/threads/<stem>/page` cuts at an ARRIVAL boundary, so the call this
      // result answers sits on the page in front of it, and 显示更早 is what would fetch that page.
      // Handed to the adapter, the result is paired with nothing and the adapter INVENTS the
      // call -- named the literal "tool", because a wire tool message carries no name at all
      // (`harness.edge.ag_ui`'s `TOOL_CALL_RESULT` frame is `{messageId, toolCallId, content,
      // role}`). The page draws `TOOL_LABELS[toolName] ?? toolName`, so that invention is a card
      // for a tool that does not exist. Both halves are pinned here: the invention upstream
      // really makes, and what this module hands over instead.
      const headless = [
        {
          id: "t1",
          role: "tool",
          toolCallId: "c1",
          content: "`2816` is not an anchor, so NOTHING was written.",
        },
        { id: "r1", role: "reasoning", content: "I used the line number instead of an anchor." },
        { id: "m2", role: "assistant", content: "Reading that region to get the anchor." },
      ];

      /// The call names a converted window holds, whether or not they came off the wire.
      const toolNamesIn = (messages: readonly unknown[]): (string | undefined)[] =>
        messages.flatMap((message) => {
          const content = (message as { content?: unknown } | null)?.content;
          if (!Array.isArray(content)) return [];
          return content
            .filter((part) => (part as { type?: unknown })?.type === "tool-call")
            .map((part) => (part as { toolName?: string }).toolName);
        });

      // WHAT THE CONVERTER MAKES OF IT UNPROMPTED: a call, and the name it wears.
      expect(toolNamesIn(fromAgUiMessages(headless))).toEqual(["tool"]);

      // THE HEADLESS RESULT IS NOT HANDED OVER AT ALL -- no card, and nothing else moved.
      const messages = toThreadMessages(headless, "settled");
      expect(messages.map((m) => m.id)).toEqual(["r1", "m2"]);
      expect(toolNamesIn(messages)).toEqual([]);

      // A RESULT THE WINDOW CAN PAIR IS UNTOUCHED: it lands on the call it answers, as always.
      const paired = [...entries, { id: "t1", role: "tool", toolCallId: "c1", content: "ok" }];
      const kept = toThreadMessages(paired, null);
      expect(kept.map((m) => m.id)).toEqual(["u1", "m1"]);
      expect(callOf(kept)?.result).toBe("ok");

      // ...AND THE COMMON WINDOW, one that opens on the person's own message, is untouched too.
      expect(toThreadMessages(entries, null).map((m) => m.id)).toEqual(["u1", "m1"]);
    },
  },
];

export const threadMessagesSuite: Suite = { name: "thread-messages", cases };
