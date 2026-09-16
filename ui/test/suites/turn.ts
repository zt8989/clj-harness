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
// reachable only if the server accepted the history the client rebuilt from turn
// one -- reasoning message included -- and the script advanced.
import { HttpAgent } from "@ag-ui/client";
import { expect } from "vitest";

import { type Case, type Suite, content, script, threadId, url } from "../e2e";

function newAgent(tid: string): { agent: HttpAgent; failed: () => string | null } {
  const agent = new HttpAgent({ url: url(), threadId: tid });
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

/// Hand a message to the agent and run it. The client owns the history: the next
/// run carries all of it, because prepareRunAgentInput feeds the agent's own
/// `messages` and `threadId` to the wire.
async function send(agent: HttpAgent, text: string): Promise<void> {
  agent.addMessage({ id: `u-${Date.now()}-${Math.random()}`, role: "user", content: text });
  await agent.runAgent({
    runId: `run-${Date.now()}-${Math.random()}`,
    tools: [],
    context: [],
  });
}

function hasText(agent: HttpAgent, text: string): boolean {
  return agent.messages.some((m) => content(m) === text);
}

const cases: Case[] = [
  {
    name: "a-second-turn-continues-the-same-thread",
    run: async () => {
      const tid = threadId("turns");
      const firstText = "第一轮。";
      const secondText = "第二轮。";
      const { agent, failed } = newAgent(tid);

      script([{ reasoning: "先读一下。", content: firstText }, { content: secondText }]);
      await send(agent, "看看这个项目。");

      expect(hasText(agent, firstText), "turn one's text is in the conversation").toBe(true);

      // Hand the conversation to turn two the way the UI does: the client owns
      // the history, and the next run carries all of it.
      await send(agent, "继续。");

      expect(failed(), `the second turn did not fail: ${failed()}`).toBeNull();
      expect(hasText(agent, secondText), "and the script's SECOND turn was served").toBe(true);
      // The history must have grown across both turns, or "it did not 400"
      // could just mean the second run started from scratch.
      expect(agent.messages.length, "the second run carried the first turn's history").toBeGreaterThanOrEqual(4);
    },
  },
  {
    name: "a-reasoning-message-does-not-poison-the-next-turn",
    // The exact regression: turn one produces ONLY reasoning and a tool call, so
    // the message the history gains is one a provider must accept back. A
    // zero-content assistant message carrying reasoning is the shape that 400'd.
    run: async () => {
      const tid = threadId("reasoning-poison");
      const { agent, failed } = newAgent(tid);

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
];

export const turnSuite: Suite = { name: "turn", cases };
