// Pre-tool approval, end to end through the real client. From
// ui/verify-approval.mjs.
//
// The turn that MARKS the session comes from a script like everything else, so
// nothing here depends on a model complying -- which was verify-approval.mjs's
// known weakness (it retried each compliance-dependent turn up to three times and
// printed a `note` when the model did not oblige). Here the script asks for the
// eval call and the write calls on purpose, and every turn lands.
//
// What this proves that the offline suite cannot: the shipped AG-UI client really
// turns our RUN_FINISHED+outcome into a resumable interrupt, and a real `resume`
// array really drives the server's replay. Everything from the park onward is
// what this file is for.
import { HttpAgent } from "@ag-ui/client";
import { expect } from "vitest";

import { type Case, type Suite, content, fileExists, rm, script, threadId, tmpPath, url } from "../e2e";

/// "resolved" approves, "cancelled" vetoes -- the two statuses the AG-UI resume
/// entry is allowed to carry.
type Decision = "resolved" | "cancelled";

function newAgent(tid: string): { agent: HttpAgent; events: string[]; reset: () => void } {
  const agent = new HttpAgent({ url: url(), threadId: tid });
  const events: string[] = [];
  agent.subscribe({
    onRunFinishedEvent: (b) => {
      events.push(b.event.outcome?.type === "interrupt" ? "RUN_FINISHED/INTERRUPT" : "RUN_FINISHED");
    },
    onRunErrorEvent: (b) => {
      events.push(`RUN_ERROR:${b.event.message}`);
    },
    onToolCallStartEvent: (b) => {
      events.push(`tool:${b.event.toolCallName}`);
    },
  });
  // Cleared in place so the closure handed to the subscriber keeps pushing into
  // the same array.
  return { agent, events, reset: () => { events.length = 0; } };
}

function pending(agent: HttpAgent) {
  return agent.pendingInterrupts;
}

async function turn(agent: HttpAgent, prompt: string): Promise<void> {
  agent.addMessage({ id: `u-${Date.now()}-${Math.random()}`, role: "user", content: prompt });
  await agent.runAgent({ runId: `run-${Date.now()}-${Math.random()}`, tools: [], context: [] });
}

/// Answer every parked interrupt with `status` and `payload`, exactly as the UI's
/// approval card does.
async function resume(agent: HttpAgent, status: Decision, payload: unknown): Promise<void> {
  const decisions = agent.pendingInterrupts.map((i) => ({ interruptId: i.id, status, payload }));
  await agent.runAgent({
    runId: `run-${Date.now()}-${Math.random()}`,
    tools: [],
    context: [],
    resume: decisions,
  });
}

function toolResultText(agent: HttpAgent, callId: string): string | undefined {
  const m = agent.messages.find((msg) => msg.role === "tool" && msg.toolCallId === callId);
  return m === undefined ? undefined : content(m);
}

function hasToolMessage(agent: HttpAgent, callId: string): boolean {
  return toolResultText(agent, callId) !== undefined;
}

/// Turn one asks for the eval that marks the session; turn two is the reply to
/// it. Written out because the eval code has to be EXACT -- it is the same call a
/// model would make, and harness.kernel.tools' session API is what it names.
const markedTurn = {
  content: "",
  "tool-calls": [
    {
      id: "mark",
      name: "eval",
      arguments: { code: '(harness.kernel.tools/session-require-approval! harness.kernel.tools/*thread-id* "write")' },
    },
  ],
};

function writeTurn(callId: string, path: string) {
  return { content: "", "tool-calls": [{ id: callId, name: "write", arguments: { path, content: "approved" } }] };
}

const cases: Case[] = [
  {
    name: "a-parked-write-runs-only-after-approval",
    run: async () => {
      const approved = tmpPath("harness-approval-approved.txt");
      const vetoed = tmpPath("harness-approval-vetoed.txt");
      const { agent, events, reset } = newAgent(threadId("approval"));

      for (const p of [approved, vetoed]) rm(p);
      script([
        markedTurn,
        { content: "marked" },
        writeTurn("w1", approved),
        { content: "wrote it" },
        writeTurn("w2", vetoed),
        { content: "done" },
      ]);
      await turn(agent, "mark this session as requiring approval for write");

      // -- the write parks
      expect(events, "the session was marked through an eval call").toContain("tool:eval");
      reset();
      await turn(agent, `write ${approved}`);

      const parked = pending(agent);
      expect(events, "the write run ended on an interrupt, not a finish").toContain("RUN_FINISHED/INTERRUPT");
      expect(parked.length, "exactly one call parked").toBe(1);
      expect(parked[0].reason, "the interrupt says why it parked").toBe("tool-approval");
      expect(parked[0].toolCallId, "and it names the parked call").toBeDefined();
      expect(fileExists(approved), "nothing was written yet").toBe(false);
      expect(hasToolMessage(agent, "w1"), "and the parked call has no answer yet").toBe(false);
      reset();
      await resume(agent, "resolved", { decision: "approved" });

      // -- approved
      expect(fileExists(approved), "the approved call ran on the resume run").toBe(true);
      expect(hasToolMessage(agent, "w1"), "its result came back as the call's answer").toBe(true);
      expect(events, "the resume run finished normally").toContain("RUN_FINISHED");
      reset();
      await turn(agent, `write ${vetoed}`);

      // -- a second write, vetoed
      expect(pending(agent).length, "a second write parks again").toBe(1);
      reset();
      await resume(agent, "cancelled", { reason: "test: exercising the veto path" });

      expect(fileExists(vetoed), "the vetoed call never ran").toBe(false);
      expect(String(toolResultText(agent, "w2")), "the model was told the call was vetoed, reason included").toContain("vetoed by human");
      expect(events, "and the run carried on to a normal end").toContain("RUN_FINISHED");
    },
  },
  {
    name: "a-resume-for-an-unknown-interrupt-is-refused",
    // Guessing an approval is the worst failure mode this feature has, so an id
    // the process never parked is refused rather than defaulted. The refusal is a
    // RUN_ERROR frame -- nothing thrown locally, because the server owns the
    // decision.
    run: async () => {
      const { agent, events } = newAgent(threadId("unknown-interrupt"));
      script([{ content: "no tools here" }]);
      await agent.runAgent({
        tools: [],
        context: [],
        resume: [{ interruptId: "never-parked", status: "resolved" }],
      });

      const refused = events.find((e) => e.startsWith("RUN_ERROR:"));
      expect(refused, "the server refused the unknown interrupt").toBeDefined();
      expect(String(refused), "and said what was wrong").toContain("unknown interrupt");
    },
  },
];

export const approvalSuite: Suite = { name: "approval", cases };
