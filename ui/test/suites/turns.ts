// A turn, as arithmetic: where it starts and ends, whether it has stopped, and
// what its summary line says.
//
// ALL THREE CASES ARE PURE. Nothing here boots the harness: the fold's decisions
// live in `src/lib/turns.ts`, a module that imports nothing, so the rules that a
// reader can see only as "the steps went away" can be pinned as numbers over a
// literal message list. Same shape as the `attachments` and `stats` suites, and
// for the same reason -- see the note at the top of `vitest.config.ts` about the
// relative import.
//
// WHAT THIS SUITE CANNOT SEE: the fold itself -- which messages are hidden, where
// the summary line is drawn, what happens on a click. No DOM here. Those are
// measured in a real browser instead.
import { expect } from "vitest";

import { type Case, type Suite } from "../e2e";
import { translator } from "../support/locale";
import {
  turnBounds,
  turnCounts,
  turnIsSettled,
  turnSummaryLabel,
  type TurnMessage,
} from "../../src/lib/turns";

/// The two languages the summary line is written in, bound to the REAL catalogs
/// (`test/support/locale.ts` builds them from `lib/catalogs.ts`, which the suite
/// can import and `lib/i18n.ts` cannot). English is the language this line was
/// first written in; Chinese is the wording it had before it had a language at
/// all, and both are pinned so a catalog edit cannot quietly drop one.
const en = translator("en", "thread");
const zh = translator("zh", "thread");

/// One assistant message with `n` tool calls, and a status.
function assistant(status: string, calls = 0): TurnMessage {
  return {
    role: "assistant",
    status: { type: status },
    parts: [
      { type: "reasoning" },
      ...Array.from({ length: calls }, () => ({ type: "tool-call" })),
      { type: "text" },
    ],
  };
}

const user: TurnMessage = { role: "user" };

/// Two turns: a user message, three assistant steps, then a second user message
/// and one answer. The shape every case below reads.
const THREAD: readonly TurnMessage[] = [
  user, // 0
  assistant("complete", 1), // 1  } first turn
  assistant("complete", 2), // 2  }
  assistant("complete"), // 3     } (the answer)
  user, // 4
  assistant("complete"), // 5     second turn, a single message
];

const cases: Case[] = [
  {
    name: "a-turn-is-a-run-of-adjacent-assistant-messages",
    run: async () => {
      // Every index of a turn answers with the same boundaries, and they are
      // found by walking the LIST: the user messages are what end a turn, so a
      // turn cannot be defined by a field somebody has to keep in step.
      expect(turnBounds(THREAD, 1)).toEqual({ first: 1, last: 3 });
      expect(turnBounds(THREAD, 2)).toEqual({ first: 1, last: 3 });
      expect(turnBounds(THREAD, 3)).toEqual({ first: 1, last: 3 });

      // A turn of one message is one message: there is nothing to fold behind a
      // header, which is what keeps a plain answer plain.
      expect(turnBounds(THREAD, 5)).toEqual({ first: 5, last: 5 });

      // A user message is its own bounds -- the fold never sees one (only
      // assistant messages call these), but returning a neighbour's turn from an
      // index that is not an assistant message would be a silent lie.
      expect(turnBounds(THREAD, 4)).toEqual({ first: 4, last: 4 });
      expect(turnBounds(THREAD, 0)).toEqual({ first: 0, last: 0 });

      // Out of range does not throw: the runtime can render a message whose
      // neighbour has just been evicted, and a crash there would take the page.
      expect(turnBounds(THREAD, 99)).toEqual({ first: 99, last: 99 });
    },
  },
  {
    name: "a-turn-is-settled-only-once-its-last-message-is-written",
    run: async () => {
      // The status of the LAST message is the answer, not of any step: the
      // runtime finalises each step as the next one opens.
      expect(turnIsSettled([user, assistant("running")], 1, true)).toBe(false);
      expect(turnIsSettled([user, assistant("complete")], 1, false)).toBe(true);

      // AN ABORTED TURN IS AS FINISHED AS ONE THAT ANSWERED. `incomplete` is
      // every way a run can stop early -- cancelled, an error, a length cap --
      // and folding it would leave a reader with a summary line and no way to
      // see what went wrong except by clicking.
      expect(turnIsSettled([user, assistant("incomplete")], 1, false)).toBe(true);

      // A PARKED TURN IS NOT SETTLED. `requires-action` means a human has to
      // answer, and the card that asks lives inside a step: folding would put the
      // thing the run is waiting on out of reach.
      expect(turnIsSettled([user, assistant("requires-action")], 1, true)).toBe(false);

      // THE RUNNING TURN IS NOT FOLDED EVEN FOR A BEAT. The thread is running and
      // this turn is the last one, so a step status that already reads `complete`
      // (the runtime finalised it before the next message opened) is not enough.
      const running: readonly TurnMessage[] = [
        user,
        assistant("complete", 1),
        assistant("complete", 2),
        assistant("complete"),
      ];
      expect(turnIsSettled(running, 3, true)).toBe(false);
      // ... and the same thread with the run over IS settled: the guard is about
      // the run, not about the status being suspect.
      expect(turnIsSettled(running, 3, false)).toBe(true);

      // AND THE OTHER HALF OF THAT RULE: an EARLIER turn stays settled while a
      // later run is in flight. Without it every turn would unfold the moment
      // somebody asked a new question.
      const withNewRun: readonly TurnMessage[] = [...running, user, assistant("running")];
      expect(turnIsSettled(withNewRun, 3, true)).toBe(true);
      expect(turnIsSettled(withNewRun, 5, true)).toBe(false); // the last message runs
    },
  },
  {
    name: "the-summary-line-counts-the-turns-own-calls-and-messages",
    run: async () => {
      // Tool calls are counted across the whole turn, and the message count is
      // the turn's own assistant messages -- not the user message that started
      // it, which is already on screen above the line.
      expect(turnCounts(THREAD, 1, 3)).toEqual({ calls: 3, messages: 3 });
      expect(turnCounts(THREAD, 5, 5)).toEqual({ calls: 0, messages: 1 });

      // A COUNTED CALL IS A `tool-call` PART, whatever else the message holds:
      // reasoning and text are steps the same turn did, and they are not calls.
      const parts: TurnMessage = {
        role: "assistant",
        status: { type: "complete" },
        parts: [{ type: "reasoning" }, { type: "text" }, { type: "tool-call" }],
      };
      expect(turnCounts([user, parts], 1, 1)).toEqual({ calls: 1, messages: 1 });

      // A message with no parts at all (a step whose parts were evicted, an
      // answer with nothing in it) counts as a message and no call.
      const bare: TurnMessage = { role: "assistant", status: { type: "complete" } };
      expect(turnCounts([user, bare], 1, 1)).toEqual({ calls: 0, messages: 1 });

      // The line itself, in both shapes: the tool-call half goes away when there
      // were none -- `0 tool calls · 2 messages` is a fact nobody asked for -- and
      // the message count is never dropped. The two counts go through i18next's
      // `count`, so English's singular form is pinned here too (a hand-rolled rule
      // would have said `1 tool calls`).
      expect(turnSummaryLabel(72, 25, en)).toBe("72 tool calls · 25 messages");
      expect(turnSummaryLabel(1, 2, en)).toBe("1 tool call · 2 messages");
      expect(turnSummaryLabel(0, 4, en)).toBe("4 messages");

      // THE SAME LINE IN THE OTHER LANGUAGE -- and this is what makes the catalog
      // the thing under test rather than a decoration: the same two numbers through
      // the same function have to come out in Chinese, where there is no singular
      // form, and the wording is TODAY'S (the one that used to be hard-coded in
      // `lib/turns.ts`, before the line had a language).
      expect(turnSummaryLabel(72, 25, zh)).toBe("72 次工具调用 · 25 条消息");
      expect(turnSummaryLabel(1, 2, zh)).toBe("1 次工具调用 · 2 条消息");
      expect(turnSummaryLabel(0, 4, zh)).toBe("4 条消息");
    },
  },
];

export const turnsSuite: Suite = { name: "turns", cases };
