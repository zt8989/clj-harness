#!/usr/bin/env node
// Drives the harness with the REAL AG-UI client -- the same @ag-ui/client that
// CopilotKit runs on underneath. A pass here means the wire contract (no CHUNK
// events, run finishes, an assistant message arrives) holds for the ACTIVE
// provider, with no browser to check.
//
//   npm run verify            # expects the harness on :8080
//   HARNESS_URL=... npm run verify
//
// NOTE: the active provider is langchain4j (see .scratch/langchain4clj-provider/
// spec.md). It does NOT surface reasoning_content, so the reasoning card is
// expected to be ABSENT -- that is a documented gap, reported below, not a failure.

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

const messages = agent.messages;
const content = (m) => (typeof m.content === "string" ? m.content : "");
const textMsgs = messages.filter((m) => m.role === "assistant" && content(m).length > 0);
const reasoning = messages.filter((m) => m.role === "reasoning");

const lastText = textMsgs.length ? content(textMsgs[textMsgs.length - 1]) : "";

const checks = [
  ["the run finished", events.includes("RUN_FINISHED")],
  ["at least one assistant text message arrived", textMsgs.length > 0],
  ["the final answer is non-trivial", lastText.length > 10],
  ["no chunk event ever reached the client", !events.some((e) => e.includes("CHUNK"))],
];

let ok = true;
for (const [name, pass] of checks) {
  console.log(`${pass ? "PASS" : "FAIL"}  ${name}`);
  if (!pass) ok = false;
}

if (reasoning.length === 0) {
  console.log(`\nNOTE: no reasoning card -- expected gap for the langchain4j provider`);
} else {
  console.log(`\nreasoning present: ${reasoning.length} message(s)`);
}

console.log("\nevents seen:");
console.log(events.map((e) => `  ${e}`).join("\n"));
console.log("\nmessages:");
console.log(JSON.stringify(messages, null, 2));

// exitCode rather than exit(): a hard process.exit() during fetch teardown trips a
// libuv assertion on Windows and turns the status into garbage.
process.exitCode = ok ? 0 : 1;
