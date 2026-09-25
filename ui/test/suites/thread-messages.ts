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
];

export const threadMessagesSuite: Suite = { name: "thread-messages", cases };
