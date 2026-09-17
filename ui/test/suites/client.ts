// The real @ag-ui/client -- the same HttpAgent the runtime drives underneath --
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

import fs from "node:fs";
import path from "node:path";

import { type Case, type Suite, content, homeDir, runUrl, script, threadId } from "../e2e";

const reasoning = "用户想看这个项目。先读 deps.edn 确认依赖。";
const answer = "这是一个 Clojure 项目，只有 4 个依赖。";

/// An agent whose thread id is fixed up front (the agent is the owner of it --
/// prepareRunAgentInput reads `this.threadId`, not a per-run argument), plus the
/// ordered list of hook names the run fires.
function newAgent(tid: string): { agent: HttpAgent; events: string[] } {
  const agent = new HttpAgent({ url: runUrl(), threadId: tid });
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

  {
    name: "a-second-turn-echoes-its-reasoning-back-to-a-thinking-mode-vendor",
    // THE COVERAGE GAP THIS EXISTS FOR: the case above runs ONE turn and looks at
    // what the client materialised. The defect that bit a real provider only shows
    // up on the SECOND request -- the one that carries the first turn's assistant
    // messages in its history -- and that request is built from what the CLIENT sends
    // back. So: two turns on one thread, against a strict vendor (`script(..,
    // {thinking: true})`), which answers 400 whenever an assistant message arrives
    // without `reasoning_content`.
    //
    // A PAD WOULD HIDE A BROKEN FOLD, so this reads the SESSION LOG rather than
    // trusting the run's success: the tool-call round must reach the vendor with the
    // reasoning TEXT the model produced, not with the empty string the server pads
    // when a round had none.
    run: async () => {
      const tid = threadId("thinkturn");
      const first = "第一轮：先看一眼 deps.edn。";
      const second = "第二轮的想法。";
      const { agent } = newAgent(tid);
      // ONE script for the whole conversation: the file is re-read per NEW thread id,
      // and a tool round costs two LLM calls (the call, then the reply to its result).
      script(
        [
          { reasoning: first, content: "", "tool-calls": [{ id: "c1", name: "read", arguments: { path: "deps.edn" } }] },
          { content: "第一轮的回答。" },
          { reasoning: second, content: "第二轮的回答。" },
        ],
        { thinking: true },
      );

      await agent.runAgent({ tools: [], context: [] });
      // The SAME agent runs again: `@ag-ui/client` sends `this.messages`, so this is
      // exactly the "client resends the whole history" path.
      await agent.runAgent({ tools: [], context: [] });

      const messages = agent.messages;
      expect(
        messages.some((m) => content(m) === "第二轮的回答。"),
        "the second turn's answer arrived -- so the vendor accepted the second request",
      ).toBe(true);

      // The session log, polled, AND SLICED BY RUN. The log records two different
      // things about a run -- the messages the request CARRIED (logged when the run
      // starts) and the ones the vendor RETURNED (logged when it ends) -- and the
      // second would satisfy a naive check on its own: the scripted vendor's replies
      // carry the reasoning too. The question here is what the SECOND REQUEST carried,
      // so the rows are taken from the last `input` marker onwards.
      const logPath = path.join(homeDir(), "projects", ".unbound", `${tid}.jsonl`);
      const secondRequestAssistants = async () => {
        for (let i = 0; i < 100; i += 1) {
          try {
            const rows = fs
              .readFileSync(logPath, "utf8")
              .split("\n")
              .filter((line) => line.trim() !== "")
              .map((line) => JSON.parse(line) as { kind?: string; payload?: { role?: string; reasoning_content?: unknown } });
            const lastInput = rows.map((r) => r.kind).lastIndexOf("input");
            if (lastInput >= 0 && rows.length > lastInput + 2) {
              const carried = rows
                .slice(lastInput)
                // The guard IS the narrowing: a row that got here has an assistant
                // payload, and saying so once is what keeps the two probes below from
                // asking again -- which is what `npm run build` was failing on.
                .filter(
                  (r): r is { payload: { role?: string; reasoning_content?: unknown } } =>
                    r.kind === "message" && r.payload?.role === "assistant",
                )
                .map((r) => r.payload);
              if (carried.length >= 2) return carried;   // both rounds of the second request
            }
          } catch {
            // the file may not exist yet on the first polls
          }
          await new Promise((r) => setTimeout(r, 50));
        }
        return [];
      };

      const carried = await secondRequestAssistants();
      expect(carried.length, "the second request's assistant messages reached the log").toBeGreaterThan(0);
      expect(
        carried.some((m) => m.reasoning_content === first),
        "the FIRST turn's reasoning reached the vendor as TEXT on the second request -- the round trip through the client and back",
      ).toBe(true);
      expect(
        carried.some((m) => m.reasoning_content === ""),
        "and the round that produced none is padded with an empty string, not invented text",
      ).toBe(true);
    },
  },
];

export const clientSuite: Suite = { name: "client", cases };
