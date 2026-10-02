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
import { turnBounds, turnConclusion, turnIsSettled, turnSummaryLabel } from "../../src/lib/turns";
import type { TurnMessage, TurnOf } from "../../src/lib/turns";
import { forgetTurnRows, noteTurnRows, turnOfMessage, turnRows } from "../../src/lib/turn-rows";
import type { TurnRow } from "../../src/lib/feed";
import { forgetTurnNumbers, noteTurnEnd, serverTurnNumbers } from "../../src/lib/turn-numbers";
import type { FactFrame } from "../../src/lib/mux";

/// A fact frame with the two fields `lib/turn-numbers.ts` reads, BUILT rather than cast: its
/// `type` is the union from `lib/mux.ts`, so renaming a frame there is a compile error here.
function endFact(type: FactFrame["type"], numbers?: unknown, turnId?: string): FactFrame {
  return turnId === undefined
    ? { threadId: "t-numbers", seq: 1, type, numbers }
    : { threadId: "t-numbers", seq: 1, type, turnId, numbers };
}

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

/// WHAT THE RECORD SAYS ABOUT THE FIXTURE (ADR 0017): two turns, each with the numbers its own
/// `turn/end` row carried. `from` / `to` are RECORD LINE NUMBERS -- that is what `lib/turn-rows.ts`
/// compares an entry's offset against, and it is the whole of the boundary.
const ROWS: readonly TurnRow[] = [
  { turnId: "t1", from: 10, to: 20, steps: 3, messages: 3 },
  { turnId: "t2", from: 30, to: 40, steps: 1, messages: 1 },
];

/// WHICH MESSAGE ID THE RECORD PUTS IN WHICH TURN -- the answer `turnOfMessage` computes from the
/// window's rows and each entry's offset. Spelled out here so the boundary cases below are about
/// the UI's use of the answer, not about the lookup.
const IN_TURN: Readonly<Record<string, string>> = { m1: "t1", m2: "t1", m3: "t1", m5: "t2" };
const turnOf: TurnOf = (message) => IN_TURN[message.id ?? ""];

/// Two turns: a user message, three assistant steps, then a second user message
/// and one answer. The shape every case below reads. THE IDS ARE THE ONES THE WINDOW HANDS THE
/// RUNTIME (`WindowEntry.message.id`), because that is the name `turnOf` is asked with.
const THREAD: readonly TurnMessage[] = [
  { ...user, id: "m0" }, // 0
  { ...assistant("complete", 1), id: "m1" }, // 1  } first turn
  { ...assistant("complete", 2), id: "m2" }, // 2  }
  { ...assistant("complete"), id: "m3" }, // 3     } (the answer)
  { ...user, id: "m4" }, // 4
  { ...assistant("complete"), id: "m5" }, // 5     second turn, a single message
];

const cases: Case[] = [
  {
    name: "a-turn-is-the-run-the-record-puts-a-message-in",
    run: async () => {
      // Every index of a turn answers with the same boundaries, and they come from the RECORD:
      // `turnOf` is what the window's `turn/start` / `turn/end` rows say, and the list is only
      // asked which messages are in it.
      expect(turnBounds(THREAD, 1, turnOf)).toEqual({ first: 1, last: 3 });
      expect(turnBounds(THREAD, 2, turnOf)).toEqual({ first: 1, last: 3 });
      expect(turnBounds(THREAD, 3, turnOf)).toEqual({ first: 1, last: 3 });

      // A turn of one message is one message: there is nothing to fold behind a
      // header, which is what keeps a plain answer plain.
      expect(turnBounds(THREAD, 5, turnOf)).toEqual({ first: 5, last: 5 });

      // A message the record puts in NO turn is its own bounds -- the fold never sees one
      // (only assistant messages call these), but handing it a neighbour's turn would be a
      // silent lie about where the record put it.
      expect(turnBounds(THREAD, 4, turnOf)).toEqual({ first: 4, last: 4 });
      expect(turnBounds(THREAD, 0, turnOf)).toEqual({ first: 0, last: 0 });

      // Out of range does not throw: the runtime can render a message whose
      // neighbour has just been evicted, and a crash there would take the page.
      expect(turnBounds(THREAD, 99, turnOf)).toEqual({ first: 99, last: 99 });
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
    name: "the-summary-line-prints-the-records-own-step-count",
    run: async () => {
      // THE NUMBER IS THE RECORD'S (ADR 0017). A turn's `turn/end` row carries `steps`, the window
      // hands it over with the turn (`TurnRow.steps`), and the line prints it. There is no count on
      // this side any more -- which is the point of the decision: the client used to count adjacent
      // assistant messages and agreed with the record everywhere except a request the vendor made
      // us send twice.
      expect(ROWS[0]?.steps, "what the record said the first turn did").toBe(3);
      expect(ROWS[1]?.steps).toBe(1);

      // The line itself is just that count (`.scratch/step-events`, ticket 05): the message half
      // said this same number, and the tool-call half said less. It goes through i18next's `count`,
      // so English's singular form is pinned here too -- a hand-rolled rule would have said
      // `1 steps`.
      expect(turnSummaryLabel(72, en)).toBe("72 steps");
      expect(turnSummaryLabel(1, en)).toBe("1 step");

      // THE SAME LINE IN THE OTHER LANGUAGE -- and this is what makes the catalog the thing
      // under test rather than a decoration: the same number through the same function has
      // to come out in Chinese, where there is no singular form.
      expect(turnSummaryLabel(72, zh)).toBe("72 步");
      expect(turnSummaryLabel(1, zh)).toBe("1 步");
    },
  },
  {
    name: "a-turns-conclusion-is-the-last-thing-it-said",
    run: async () => {
      // A MESSAGE THAT SAID NOTHING IS A STEP. The tool calls and the thinking are
      // how the turn got where it got; only a message with a non-empty `text` part
      // is the answer, and the fold keeps that one and hides the rest.
      const thought: TurnMessage = {
        role: "assistant",
        status: { type: "complete" },
        parts: [{ type: "reasoning" }, { type: "tool-call" }],
      };
      const said = (text: string): TurnMessage => ({
        role: "assistant",
        status: { type: "complete" },
        parts: [{ type: "reasoning" }, { type: "text", text }],
      });

      // THE LAST TEXT WINS, and it need not be the tail: a model may speak, call a
      // tool, and speak again -- the answer is what it said last, and anything after
      // it is a step of the same turn.
      expect(turnConclusion([user, thought, said("here it is"), thought], 1, 3)).toBe(2);

      // A TURN THAT NEVER SPOKE HAS NO CONCLUSION -- undefined, not the tail. This is
      // the case the fold exists to get right: `useStepFold` answers `"step"` for
      // every message of it, so the last thought or tool call is NOT left on screen
      // pretending to be an answer.
      expect(turnConclusion([user, thought, thought], 1, 2)).toBeUndefined();

      // AN EMPTY (OR BLANK) TEXT PART IS NOT AN ANSWER either. A turn whose last
      // message carries a text part that is still blank has not said anything yet.
      const bare: TurnMessage = { role: "assistant", status: { type: "complete" }, parts: [{ type: "text" }] };
      const blank: TurnMessage = {
        role: "assistant",
        status: { type: "complete" },
        parts: [{ type: "text", text: "   " }],
      };
      expect(turnConclusion([user, bare], 1, 1)).toBeUndefined();
      expect(turnConclusion([user, blank], 1, 1)).toBeUndefined();

      // A USER MESSAGE HAS NO PARTS AT ALL -- and the fold never asks about one --
      // but returning a neighbour's answer from an index that is not an assistant
      // message would be the same silent lie `turnBounds` refuses.
      expect(turnConclusion(THREAD, 0, 0)).toBeUndefined();
    },
  },
  {
    name: "the-record-says-which-turn-a-message-is-in",
    run: async () => {
      // THE READER EVERY SELECTOR ASKS (ADR 0017): a message is placed by the RECORD LINE its entry
      // arrived in, against the `from` .. `to` range of a `turn/start` / `turn/end` pair -- never
      // by what its neighbours look like.
      forgetTurnRows("t-rows");
      noteTurnRows("t-rows", ROWS, [
        { seq: 11, message: { id: "m1" } },
        { seq: 12, message: { id: "m2" } },
        { seq: 31, message: { id: "m5" } },
      ]);
      expect(turnOfMessage("t-rows", "m1")?.turnId).toBe("t1");
      expect(turnOfMessage("t-rows", "m5")?.turnId).toBe("t2");
      expect(turnOfMessage("t-rows", "m5")?.steps, "the numbers ride the row too").toBe(1);

      // A MESSAGE THE RECORD PLACES IN NO TURN GETS NO TURN. A conversation recorded before the
      // decision has no rows at all; a message whose line has not landed has nothing to compare;
      // and an id this window is not holding is not placed by guessing.
      expect(turnOfMessage("t-rows", "m0"), "the record put it in none").toBeUndefined();
      expect(turnOfMessage("t-rows", "m9"), "an id this window does not hold").toBeUndefined();
      noteTurnRows("t-rows", [], [{ seq: 11, message: { id: "m1" } }]);
      expect(turnOfMessage("t-rows", "m1"), "no rows, no turn").toBeUndefined();
      noteTurnRows("t-rows", ROWS, [{ seq: null, message: { id: "m1" } }]);
      expect(turnOfMessage("t-rows", "m1"), "a line still in the writer's queue").toBeUndefined();

      forgetTurnRows("t-rows");
      expect(turnRows("t-rows"), "and forgetting is forgetting").toEqual([]);
    },
  },
  {
    name: "the-turn-numbers-store-keeps-the-last-end-and-nothing-else",
    run: async () => {
      // THE STORE THE FOLD LINE READS (`.scratch/step-events`): one `turn/end` per conversation,
      // and everything else in the family leaves it standing.
      forgetTurnNumbers("t-numbers");
      expect(serverTurnNumbers("t-numbers"), "nothing heard yet").toBeUndefined();
      noteTurnEnd("t-numbers", endFact("turn/end", { steps: 1, messages: 2 }, "t-numbers-t9"));
      expect(serverTurnNumbers("t-numbers")).toEqual({ turnId: "t-numbers-t9", steps: 1, messages: 2 });

      // A SECOND CONVERSATION IS A SECOND ANSWER: one page holds more than one thread at a time.
      expect(serverTurnNumbers("t-other")).toBeUndefined();

      // THE REST OF THE FAMILY IS NOT AN ANSWER: a start opens a turn and knows no count.
      noteTurnEnd("t-numbers", endFact("turn/start", undefined));
      expect(serverTurnNumbers("t-numbers")?.steps, "a start does not overwrite the last end").toBe(1);

      // AND A PAYLOAD WITHOUT A COUNT IS NOT ONE EITHER -- the sender would rather say nothing than
      // have this side invent a number, and a frame is allowed to carry no `numbers` at all.
      noteTurnEnd("t-numbers", endFact("turn/end", { messages: 2 }));
      expect(serverTurnNumbers("t-numbers")?.steps).toBe(1);

      forgetTurnNumbers("t-numbers");
      expect(serverTurnNumbers("t-numbers"), "and forgetting is forgetting").toBeUndefined();
    },
  },
];

export const turnsSuite: Suite = { name: "turns", cases };
