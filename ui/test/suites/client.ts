// The real @ag-ui/client -- the same HttpAgent CopilotKit runs on underneath --
// driving a live harness. From ui/verify.mjs.
//
// What this proves that an offline test structurally cannot: the frames the
// server writes are frames a REAL client can consume. A run can be generated,
// logged and even schema-valid and still fail here, because the client
// materialises messages by applying frames in order -- reasoning, tool calls,
// tool results -- and any of that can come out wrong while every individual frame
// looks fine.
//
// The script is ours, so the reasoning text and the final answer are known
// exactly, and the byte-for-byte comparison stays meaningful without a model.
import { HttpAgent } from "@ag-ui/client";
import { expect } from "vitest";

import { type Case, type Suite, content, script, threadId, url } from "../e2e";

const reasoning = "用户想看这个项目。先读 deps.edn 确认依赖。";
const answer = "这是一个 Clojure 项目，只有 4 个依赖。";

/// An agent whose thread id is fixed up front (the agent is the owner of it --
/// prepareRunAgentInput reads `this.threadId`, not a per-run argument), plus the
/// ordered list of hook names the run fires.
function newAgent(tid: string): { agent: HttpAgent; events: string[] } {
  const agent = new HttpAgent({ url: url(), threadId: tid });
  const events: string[] = [];
  agent.subscribe({
    onReasoningMessageStartEvent: () => {
      events.push("REASONING_MESSAGE_START");
    },
    onReasoningMessageContentEvent: (b) => {
      events.push(`reasoning:${b.event.delta}`);
    },
    onReasoningMessageEndEvent: () => {
      events.push("REASONING_MESSAGE_END");
    },
    onTextMessageContentEvent: (b) => {
      events.push(`text:${b.event.delta}`);
    },
    onToolCallStartEvent: (b) => {
      events.push(`tool:${b.event.toolCallName}`);
    },
    onToolCallResultEvent: () => {
      events.push("TOOL_CALL_RESULT");
    },
    onRunFinishedEvent: () => {
      events.push("RUN_FINISHED");
    },
  });
  return { agent, events };
}

const cases: Case[] = [
  {
    name: "reasoning-and-tool-calls-survive-the-wire",
    run: async () => {
      const { agent, events } = newAgent(threadId("client"));
      script([
        {
          reasoning,
          content: "",
          "tool-calls": [{ id: "c1", name: "read", arguments: { path: "deps.edn" } }],
        },
        { content: answer },
      ]);
      await agent.runAgent({ tools: [], context: [] });

      const messages = agent.messages;
      const byRole = (role: string) => messages.filter((m) => m.role === role);

      expect(byRole("reasoning").length, "the client materialised a reasoning message").toBeGreaterThan(0);
      expect(byRole("reasoning").some((m) => content(m) === reasoning), "and its text survived the wire byte for byte").toBe(true);
      expect(
        messages.some((m) => m.role === "assistant" && (m.toolCalls?.length ?? 0) > 0),
        "an assistant message carries the tool call",
      ).toBe(true);
      expect(byRole("tool").length, "the tool result came back as a message").toBeGreaterThan(0);
      expect(messages.some((m) => content(m) === answer), "the final answer arrived intact").toBe(true);
      expect(events.some((e) => e.includes("reasoning:")), "the reasoning HOOKS fired, not merely the message list").toBe(true);
      expect(events.some((e) => e.includes("CHUNK")), "no chunk event ever reached the client").toBe(false);
      expect(events, "and the run finished").toContain("RUN_FINISHED");
    },
  },
  {
    name: "a-tool-round-costs-two-llm-calls",
    // The scripted provider hands out ONE TURN PER CALL, so a tool round has to
    // consume exactly two turns: the call, then the reply to its result. If the
    // kernel called the model once and reused the answer, the first turn's tool
    // call would come back as the final message and the second turn's text would
    // never appear -- which is what this asserts, rather than trusting a count.
    run: async () => {
      const { agent } = newAgent(threadId("twocalls"));
      script([
        { content: "", "tool-calls": [{ id: "c1", name: "read", arguments: { path: "deps.edn" } }] },
        { content: "第二轮。" },
      ]);
      await agent.runAgent({ tools: [], context: [] });

      const messages = agent.messages;
      expect(messages.some((m) => content(m) === "第二轮。"), "the SECOND turn's text is in the conversation").toBe(true);
      expect(messages.filter((m) => m.role === "tool").length, "exactly one tool message, from the one call").toBe(1);
    },
  },
];

export const clientSuite: Suite = { name: "client", cases };
