import { HttpAgent } from "@ag-ui/client";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";

/**
 * Pre-tool approval, end to end against a live harness.
 *
 *   node ui/verify-approval.mjs        (harness on 8080, real provider)
 *
 * The approval switch is per session and off by default, so the first turn asks
 * the model to flip it through eval -- that is how a session is configured, and
 * it exercises the introspection path too. Then two writes: one approved, one
 * vetoed. What this proves that the offline suite cannot: the shipped AG-UI
 * client really turns our RUN_FINISHED+outcome into a resumable interrupt, and
 * a real `resume` array really drives the server's replay.
 *
 * KNOWN WEAKNESS: every turn here depends on the model complying, and a weak
 * model sometimes answers a "call the tool" turn with reasoning alone -- no
 * tool call, no text. So each compliance-dependent turn is retried until the
 * tool call actually lands; if it never does, the script reports FAIL with the
 * frames it saw rather than pretending the machinery was exercised. Everything
 * after the park is model-independent, and that is the half worth trusting.
 */

const url = process.env.HARNESS_URL ?? "http://localhost:8080/";
const threadId = `it-approval-${Date.now()}`;
const approvedPath = path.join(os.tmpdir(), "harness-approval-approved.txt");
const vetoedPath = path.join(os.tmpdir(), "harness-approval-vetoed.txt");
for (const p of [approvedPath, vetoedPath]) fs.rmSync(p, { force: true });

const agent = new HttpAgent({ url });
let events = [];
agent.subscribe({
  onRunStartedEvent: () => events.push("RUN_STARTED"),
  onRunFinishedEvent: ({ event }) =>
    events.push(event.outcome?.type === "interrupt" ? "RUN_FINISHED/INTERRUPT" : "RUN_FINISHED"),
  onRunFailedEvent: ({ event }) => events.push(`RUN_FAILED:${event?.message}`),
  onToolCallStartEvent: ({ event }) => events.push(`tool:${event.toolCallName}`),
  onToolCallResultEvent: ({ event }) => events.push(`result:${String(event.content).slice(0, 40)}`),
  onTextMessageContentEvent: () => events.push("text"),
});

const pending = () => agent.pendingInterrupts ?? [];

async function turn(prompt, opts = {}) {
  events = [];
  agent.addMessage({ id: `u-${Date.now()}-${Math.random()}`, role: "user", content: prompt });
  await agent.runAgent({
    threadId,
    runId: `run-${Date.now()}-${Math.random()}`,
    messages: agent.messages,
    tools: [],
    context: [],
    ...opts,
  });
  return events;
}

/**
 * Run a turn, retrying while the model fails to comply. `landed` is the frame
 * the turn had to produce; a turn that parks also produces it, so a parked
 * interrupt is never retried away.
 */
async function turnUntil(prompt, { landed, label }, attempts = 3) {
  const seen = [];
  for (let i = 1; i <= attempts; i++) {
    const nudge = i === 1 ? "" : "\n\nCall the tool. Do not answer in prose.";
    seen.push(await turn(prompt + nudge));
    if (seen.at(-1).some(landed)) return seen.at(-1);
    console.log(`note  attempt ${i} did not produce ${label}; retrying`);
  }
  return seen.at(-1);
}

async function resume(status, payload) {
  const decisions = pending().map((i) => ({ interruptId: i.id, status, payload }));
  events = [];
  await agent.runAgent({
    threadId,
    runId: `run-${Date.now()}-${Math.random()}`,
    messages: agent.messages,
    tools: [],
    context: [],
    resume: decisions,
  });
  return events;
}

const toolMessageFor = (toolCallId) =>
  agent.messages.find((m) => m.role === "tool" && m.toolCallId === toolCallId);

const failures = [];
const check = (label, ok, detail = "") => {
  console.log(`${ok ? "ok  " : "FAIL"}  ${label}${detail ? `  -- ${detail}` : ""}`);
  if (!ok) failures.push(label);
};

// ---------------------------------------------------------------- 1) mark the session

await turnUntil(
  "Call the eval tool exactly once, with this code and nothing else: " +
    '(harness.memory/session-require-approval! harness.memory/*thread-id* "write")' +
    " Then reply OK.",
  { landed: (e) => e === "tool:eval", label: "an eval call" },
);
check("the session was marked as needing approval for write", events.includes("tool:eval"),
      `events=${events.join(",")}`);

// ---------------------------------------------------------------- 2) a write parks

await turnUntil(
  `Use the write tool to create ${approvedPath} with the content "approved".`,
  { landed: (e) => e === "tool:write", label: "a write call" },
);
const parked = pending();
check(
  "the write run ended on an interrupt",
  events.includes("RUN_FINISHED/INTERRUPT") && parked.length === 1,
  `events=${events.join(",")} pending=${parked.length}`,
);
const parkedCallId = parked[0]?.toolCallId;
check("the interrupt names the parked call and says why",
      Boolean(parkedCallId) && parked[0]?.reason === "tool-approval",
      JSON.stringify(parked[0] ?? null));
check("the parked call was NOT answered", !toolMessageFor(parkedCallId));
check("and nothing was written yet", !fs.existsSync(approvedPath));

// ---------------------------------------------------------------- 3) approve it

await resume("resolved", { decision: "approved" });
const approvedResult = toolMessageFor(parkedCallId);
check("the approved call ran on the resume run", fs.existsSync(approvedPath));
check("its result came back as the call's answer", Boolean(approvedResult), String(approvedResult?.content));
check("the resume run finished normally", events.includes("RUN_FINISHED"), `events=${events.join(",")}`);

// ---------------------------------------------------------------- 4) a second write, vetoed

await turnUntil(
  `Use the write tool to create ${vetoedPath} with the content "approved".`,
  { landed: (e) => e === "tool:write", label: "a write call" },
);
const parkedAgain = pending();
check("a second write parks again", parkedAgain.length === 1, `events=${events.join(",")}`);
const vetoCallId = parkedAgain[0]?.toolCallId;

await resume("cancelled", { reason: "verify-approval: exercising the veto path" });
const vetoResult = toolMessageFor(vetoCallId);
check("the vetoed call never ran", !fs.existsSync(vetoedPath));
check(
  "the model was told the call was vetoed, reason included",
  Boolean(vetoResult) && String(vetoResult.content).includes("vetoed by human"),
  String(vetoResult?.content),
);
check("the run carried on to a normal end", events.includes("RUN_FINISHED"), `events=${events.join(",")}`);

console.log(`\nRESULT: ${failures.length === 0 ? "PASS" : `FAIL (${failures.join("; ")})`}`);
process.exitCode = failures.length === 0 ? 0 : 1;
