// Two turns on ONE thread id, which is the shape that used to break. From
// ui/verify-real.mjs.
//
// The bug this pins: the second request of a conversation used to answer 400 --
// the kernel appended the reasoning of turn one to the history in a shape the
// provider rejected. It is covered offline by harness.kernel.loop-test, but the path
// that actually failed ran through the AG-UI edge AND the client, which only this
// level exercises.
//
// With a scripted provider the assertion can be exact: turn two's text is
// reachable only if the server accepted the conversation it holds from turn one --
// reasoning message included -- and the script advanced.
//
// THE HISTORY IS THE SERVER'S NOW, and this suite still sits on the same regression
// (ticket 03 of `.scratch/sessions-live-on-the-server`). Before it, the client sent
// turn one's messages back; now the server folds them out of the record and keeps them
// in memory, and TURN TWO IS THE PROOF THAT FOLD IS INTACT -- a conversation that lost
// the reasoning is one the strict vendor refuses, which arrives here as RUN_ERROR.
import { expect } from "vitest";

import { type Case, type Suite, agentFor, content, script, threadId } from "../e2e";
import { type HarnessAgent } from "@/lib/agent";
import { declaredSet, subscribeFacts } from "@/lib/mux";
import { turnBounds, turnCounts } from "@/lib/turns";

async function newAgent(tid: string): Promise<{ agent: HarnessAgent; failed: () => string | null }> {
  const agent = await agentFor(tid);
  let failed: string | null = null;
  // A failed run arrives as a RUN_ERROR FRAME -- so it is `onRunErrorEvent`
  // that reports it. The hook that sounds like it should, `onRunFailedEvent`,
  // does not exist on this client: a typo in a hook name is silently dropped
  // by its subscriber registry, and the check then never fires. (verify-real.mjs
  // and verify-approval.mjs both had that typo, which means their "did the run
  // fail?" guards were dead code.)
  agent.subscribe({
    onRunErrorEvent: (b) => {
      failed = b.event.message;
    },
  });
  return { agent, failed: () => failed };
}

/// Hand a message to the agent and run it.
///
/// WHAT GOES ON THE WIRE IS THIS ACTION, NOT THE CONVERSATION: the agent is the page's
/// (`HarnessAgent`), so the accumulated `messages` become a trailing `append` and the
/// run id is the server's. What the client keeps is the VIEW of the conversation, which
/// is what the assertions below read.
async function send(agent: HarnessAgent, text: string): Promise<void> {
  agent.addMessage({ id: `u-${Date.now()}-${Math.random()}`, role: "user", content: text });
  await agent.runAgent({ tools: [], context: [] });
}

function hasText(agent: HarnessAgent, text: string): boolean {
  return agent.messages.some((m) => content(m) === text);
}

const cases: Case[] = [
  {
    name: "a-second-turn-continues-the-same-thread",
    run: async () => {
      const tid = threadId("turns");
      const firstText = "第一轮。";
      const secondText = "第二轮。";
      const { agent, failed } = await newAgent(tid);

      script([{ reasoning: "先读一下。", content: firstText }, { content: secondText }]);
      await send(agent, "看看这个项目。");

      expect(hasText(agent, firstText), "turn one's text is in the conversation").toBe(true);

      // Hand the conversation to turn two the way the UI does: the client adds its
      // question and nothing else, and the server continues from the turns it holds.
      await send(agent, "继续。");

      expect(failed(), `the second turn did not fail: ${failed()}`).toBeNull();
      expect(hasText(agent, secondText), "and the script's SECOND turn was served").toBe(true);
      // The history must have grown across both turns, or "it did not 400"
      // could just mean the second run started from scratch.
      expect(agent.messages.length, "the view still holds both turns -- the frames built it").toBeGreaterThanOrEqual(4);
    },
  },
  {
    name: "a-reasoning-message-does-not-poison-the-next-turn",
    // The exact regression: turn one produces ONLY reasoning and a tool call, so
    // the message the history gains is one a provider must accept back. A
    // zero-content assistant message carrying reasoning is the shape that 400'd.
    run: async () => {
      const tid = threadId("reasoning-poison");
      const { agent, failed } = await newAgent(tid);

      script([
        {
          reasoning: "只有推理。",
          content: "",
          "tool-calls": [{ id: "c1", name: "read", arguments: { path: "deps.edn" } }],
        },
        { reasoning: "再想一次。", content: "好了。" },
      ]);
      await send(agent, "read deps.edn");

      expect(agent.messages.some((m) => m.role === "reasoning"), "turn one left a reasoning message in the history").toBe(true);

      await send(agent, "再来。");

      expect(failed(), `resending a reasoning message is accepted: ${failed()}`).toBeNull();
      expect(hasText(agent, "好了。"), "and the run continued into turn two").toBe(true);
    },
  },
  {
    name: "the-read-side-and-the-server-count-the-same-steps",
    // THE SHARED CASE TABLE TICKET 05 ASKED FOR: ONE REAL RUN, the same turn counted twice --
    // by the record's own `step/start` rows (what the `turn/end` fact carries) and by the
    // conversation's own run of assistant messages (`lib/turns.ts`, what the fold line draws
    // when it has no event). THEY MUST AGREE, because the fold line chooses between them by**who
    // owns the turn**, never by which one it trusts (`turnStepsFrom`).
    run: async () => {
      const tid = threadId("steps-two-ways");
      const { agent } = await newAgent(tid);
      // THE PAGE'S OWN SUBSCRIPTION, not a raw socket: what arrives here is what a page has.
      const ended: unknown[] = [];
      const { unsubscribe } = subscribeFacts(tid, (fact) => {
        if (fact.type === "turn/end") ended.push(fact.numbers);
      });
      script([
        {
          reasoning: "先看一眼。",
          content: "",
          "tool-calls": [{ id: "c1", name: "read", arguments: { path: "deps.edn" } }],
        },
        { content: "看完了。" },
      ]);
      await send(agent, "看看这个项目。");
      // THE CLOSING FACT ARRIVES AFTER THE RUN'S OWN TERMINAL FRAME: `turn/end` goes out in the
      // edge's `:run/done` branch, one kernel event after `:run/end` (`../e2e.ts`'s `readRun`
      // learned this the hard way). So this waits for the FACT, not for the run to be over.
      for (let tries = 0; tries < 100 && ended.length === 0; tries += 1) {
        await new Promise((resolve) => setTimeout(resolve, 20));
      }
      unsubscribe();

      // TWO STEPS: the request that asked for `read`, and the one that answered with it in hand.
      const server = (ended.at(-1) as { steps?: number } | undefined)?.steps;
      expect(server, "the server closed a turn and said how many steps it took").toBe(2);

      // THE TURN AS THE FOLD SEES IT. The raw AG-UI list carries a tool RESULT as an entry of its
      // own (`role: "tool"`, measured here: `[user, reasoning, assistant, tool, assistant]`), and
      // that entry BREAKS the run of assistant messages `turnBounds` walks -- the page's own view
      // does not carry it (the result is a part of the assistant message: the browser walkthrough
      // draws this very shape of turn as "2 步", from this same read side). So the shared case
      // table is read over the shape the fold is handed, not over the transport's.
      const foldedView = agent.messages.filter((m) => m.role !== "tool");
      const { first, last } = turnBounds(foldedView, foldedView.length - 1);
      expect(turnCounts(foldedView, first, last).steps, "and the view counts the same two").toBe(server);
    },
  },
  {
    name: "a-reconnect-would-ask-for-the-gap-and-the-step-frames-are-in-it",
    // TICKET 04: THE CLIENT'S HALF OF 'A DROPPED SOCKET IS REPAIRED'. The server's half has its
    // own case (`harness.edge.mux-test/the-step-family-rides-the-same-cursor`: `facts-after`
    // hands back exactly the gap). This half is the question the PAGE answers -- HOW FAR IT GOT.
    run: async () => {
      const tid = threadId("facts-cursor");
      const { agent } = await newAgent(tid);
      const facts: { type: string; seq: number | null }[] = [];
      const { unsubscribe } = subscribeFacts(tid, (fact) => {
        facts.push({ type: fact.type, seq: fact.seq });
      });
      script([
        {
          reasoning: "先看一眼。",
          content: "",
          "tool-calls": [{ id: "c1", name: "read", arguments: { path: "deps.edn" } }],
        },
        { content: "看完了。" },
      ]);
      await send(agent, "看看这个项目。");
      for (let tries = 0; tries < 100 && !facts.some((f) => f.type === "turn/end"); tries += 1) {
        await new Promise((resolve) => setTimeout(resolve, 20));
      }

      // THE FAMILY'S SHAPE, MEASURED -- one turn, two requests, one tool: TEN facts. The turn's
      // own ends, and per request a step pair around the model pair.
      //
      // IT IS ALSO THE RING'S ARITHMETIC (`harness.edge.mux/fact-buffer-size` is 1024 frames):
      // about a hundred turns of this shape fit, and a reconnect gap is a second long by nature --
      // so the bound stays a bound on MEMORY rather than a promise to a reader that was away for a
      // whole turn. Before the step family the same turn cost six.
      expect(facts.map((f) => f.type)).toEqual([
        "turn/start",
        "step/start", "model/start", "model/end", "step/end",
        "step/start", "model/start", "model/end", "step/end",
        "turn/end",
      ]);
      // AND EVERY ONE CARRIES A RECORD LINE -- the step pair included, which is the whole reason
      // ADR 0011 writes them into the record: it is what aligns the pushed half with the window
      // half (ADR 0006 decision 5).
      expect(facts.every((f) => typeof f.seq === "number")).toBe(true);

      // SO THE GAP A RECONNECT ASKS FOR STARTS AT THE LAST ONE IT SAW. `declaredSet` is what the
      // handshake URL and every re-declare spell (`lib/mux.ts`), so this is the `factSince` a
      // socket comes back with after a drop.
      const declared = declaredSet().find((d) => d.threadId === tid);
      expect(declared?.factSince).toBe(Math.max(...facts.map((f) => f.seq as number)));
      // (AND THE CLAIM IS DROPPED LAST: `declaredSet` is 'what this page still wants', so reading
      // it for a thread nobody is claiming any more answers nothing -- which is the other half of
      // the same rule.)
      unsubscribe();
    },
  },
];

export const turnSuite: Suite = { name: "turn", cases };
