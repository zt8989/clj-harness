#!/usr/bin/env node
// Drives the harness with the REAL AG-UI client -- the same @ag-ui/client that
// CopilotKit runs on underneath. A pass here means the reasoning card in the browser
// is a foregone conclusion rather than a hope, and it needs no browser to check.
//
//   npm run verify            # expects the harness on :8080
//   HARNESS_URL=... npm run verify

import { HttpAgent } from "@ag-ui/client";

const url = process.env.HARNESS_URL ?? "http://localhost:8080/";
const agent = new HttpAgent({ url });
const events = [];

// Every hook receives { event, ...buffer }. The payload is nested under `event`.
agent.subscribe({
  onReasoningMessageStartEvent: () => events.push("REASONING_MESSAGE_START"),
  onReasoningMessageContentEvent: ({ event }) => events.push(`reasoning:${event.delta}`),
  onReasoningMessageEndEvent: () => events.push("REASONING_MESSAGE_END"),
  onTextMessageContentEvent: ({ event }) => events.push(`text:${event.delta}`),
  onToolCallStartEvent: ({ event }) => events.push(`tool:${event.toolCallName}`),
  onToolCallResultEvent: () => events.push("TOOL_CALL_RESULT"),
  onRunFinishedEvent: () => events.push("RUN_FINISHED"),
});

await agent.runAgent({ tools: [], context: [] });

// Written with escapes so the assertion cannot be fooled by a console that renders
// UTF-8 as GBK -- which this machine's console does.
const REASONING = "\u7528\u6237\u60f3\u770b\u8fd9\u4e2a\u9879\u76ee\u3002\u5148\u8bfb deps.edn \u786e\u8ba4\u4f9d\u8d56\u3002";
const ANSWER = "\u8fd9\u662f\u4e00\u4e2a Clojure \u9879\u76ee\uff0c\u53ea\u6709 4 \u4e2a\u4f9d\u8d56\u3002";

const messages = agent.messages;
const content = (m) => (typeof m.content === "string" ? m.content : "");
const reasoning = messages.filter((m) => m.role === "reasoning");

const checks = [
  ["a reasoning message was materialised by the client", reasoning.length > 0],
  ["the reasoning text survived the wire byte for byte", reasoning.some((m) => content(m) === REASONING)],
  ["an assistant message carries the tool call", messages.some((m) => m.role === "assistant" && (m.toolCalls?.length ?? 0) > 0)],
  ["the tool result came back", messages.some((m) => m.role === "tool")],
  ["the final answer arrived intact", messages.some((m) => content(m) === ANSWER)],
  ["the reasoning hooks fired, not merely the message list", events.some((e) => e.startsWith("reasoning:"))],
  ["no chunk event ever reached the client", !events.some((e) => e.includes("CHUNK"))],
];

let ok = true;
for (const [name, pass] of checks) {
  console.log(`${pass ? "PASS" : "FAIL"}  ${name}`);
  if (!pass) ok = false;
}

console.log("\nevents seen:");
console.log(events.map((e) => `  ${e}`).join("\n"));
console.log("\nmessages:");
console.log(JSON.stringify(messages, null, 2));

// exitCode rather than exit(): a hard process.exit() during fetch teardown trips a
// libuv assertion on Windows and turns the status into garbage.
process.exitCode = ok ? 0 : 1;
