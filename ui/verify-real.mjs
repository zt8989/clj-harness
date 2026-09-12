import { HttpAgent } from "@ag-ui/client";

const url = process.env.HARNESS_URL ?? "http://localhost:8080/";
const threadId = `it-real-${Date.now()}`;

async function runOnce(messages) {
  const agent = new HttpAgent({ url });
  const events = [];
  agent.subscribe({
    onRunStartedEvent: () => events.push("RUN_STARTED"),
    onRunFinishedEvent: () => events.push("RUN_FINISHED"),
    onRunFailedEvent: (e) => events.push(`RUN_FAILED:${e.event?.message}`),
    onReasoningMessageStartEvent: () => events.push("REASONING_START"),
    onReasoningMessageContentEvent: ({ event }) => events.push(`reasoning:${event.delta.slice(0,30)}`),
    onTextMessageStartEvent: () => events.push("TEXT_START"),
    onTextMessageContentEvent: ({ event }) => events.push(`text:${event.delta.slice(0,30)}`),
    onToolCallStartEvent: ({ event }) => events.push(`tool:${event.toolCallName}`),
    onToolCallResultEvent: () => events.push("TOOL_RESULT"),
  });
  await agent.runAgent({ threadId, runId: `run-${Date.now()}-${Math.random()}`, messages, tools: [], context: [] });
  return { events, messages: agent.messages };
}

console.log("=== first turn: read deps.edn ===");
let res1 = await runOnce([{ id: "u1", role: "user", content: "Read deps.edn with the read tool, then summarize in one sentence." }]);
console.log("events", res1.events.slice(0, 20));
console.log("messages", JSON.stringify(res1.messages, null, 2).slice(0, 2000));

const hasReasoning1 = res1.messages.some(m => m.role === "reasoning");
const hasToolCall1 = res1.messages.some(m => m.role === "assistant" && m.toolCalls?.length);
const hasToolResult1 = res1.messages.some(m => m.role === "tool");
const hasAnswer1 = res1.messages.some(m => m.role === "assistant" && typeof m.content === "string" && m.content.length > 10);
console.log(`first turn: reasoning=${hasReasoning1} toolCall=${hasToolCall1} toolResult=${hasToolResult1} answer=${hasAnswer1}`);

if (!hasToolCall1) {
  console.log("WARN first turn did not produce tool call, trying again with stronger prompt");
  res1 = await runOnce([{ id: "u1", role: "user", content: "You MUST use the read tool to read deps.edn. Do not answer without calling it." }]);
  console.log("retry events", res1.events);
}

// second turn: same thread, full history + new user message
const history = res1.messages;
console.log("\n=== second turn: same thread, should not 400 ===");
const secondMessages = [...history, { id: "u2", role: "user", content: "Now also look at prompt.md and add one more sentence." }];
let res2;
try {
  res2 = await runOnce(secondMessages);
  console.log("events2", res2.events.slice(0, 20));
  console.log("messages2", JSON.stringify(res2.messages, null, 2).slice(0, 2000));
  const hasFailed = res2.events.some(e => e.startsWith("RUN_FAILED"));
  console.log(`second turn failed? ${hasFailed}`);
  const ok = !hasFailed && res2.events.includes("RUN_FINISHED") && !res2.events.some(e => e.includes("CHUNK"));
  console.log(`\nRESULT: ${ok ? "PASS second turn did not 400" : "FAIL second turn"}`);
  process.exitCode = ok && hasToolCall1 ? 0 : 1;
} catch (e) {
  console.error("second turn threw", e);
  process.exitCode = 1;
}
