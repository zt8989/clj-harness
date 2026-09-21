// A real-browser walkthrough of ticket 03's UI half: THE PAGE SENDS ACTIONS, NOT THE
// CONVERSATION -- and it can no longer open a session out of thin air.
//
//   node scripts/dev.mjs --scripted .scratch/sessions-live-on-the-server/evidence/03-go.json --ui-port 5222
//   # it prints "temp config root <HOME> (a session's record is projects/<workspace>/<thread>.jsonl under it)"
//   node .scratch/sessions-live-on-the-server/evidence/03-walkthrough.mjs http://localhost:5222/ <HOME>
//
// This is the layer AGENTS.md asks for when `ui/src/` has been touched. What the vitest
// suite can pin is the client's own code (`ui/test/suites/client.ts` reads the body a run
// posts); WHAT A BROWSER ACTUALLY PUTS ON THE WIRE -- from a page that has just loaded,
// through the composer, across a reload -- is a browser's question, and that is this
// script.
//
// THREE THINGS IT LOOKS FOR, and each is a decision of ticket 03:
//
//   1. THE PAGE ASKS THE SERVER TO NAME A CONVERSATION. A fresh page (no memory in
//      localStorage) posts to `/api/sessions` with a body that names no thread, and the
//      id it uses from then on is the one that came back -- the page mints nothing.
//   2. NO RUN CARRIES THE CONVERSATION. Every body posted to `/api/agent` has
//      `threadId`, `append` and `tools` -- no `messages`, no `runId` (the server names
//      the run) -- and the SECOND send's `append` holds only the second question, while
//      the page plainly still shows the first turn.
//   3. A RELOAD COMES BACK TO THE SAME SESSION, and the send after it is again an
//      action: one entry in `append`, the accumulated history nowhere on the wire.
//
// IT ALSO ASKS THE DOOR DIRECTLY, from inside the page: a run for an id this home has
// never heard of is refused BY NAME (404, "POST /api/sessions"). That is the other half
// of "the client can no longer open a session out of thin air" -- the run edge used to
// quietly register whatever arrived.
//
// WHAT IT CANNOT SEE: the layout, and whether the pictures are pretty. The screenshot it
// leaves behind is for that eye; the bodies it prints are the evidence.
import fs from "node:fs";
import path from "node:path";

import { chromium } from "/Users/zhouteng/.nvm/versions/node/v22.21.1/lib/node_modules/playwright/index.mjs";

const url = process.argv[2] ?? "http://localhost:5222/";
const home = process.argv[3];
if (!home) {
  console.log("usage: 03-walkthrough.mjs <ui-url> <config-root-printed-by-dev.mjs>");
  process.exit(2);
}

/// The bodies the page posted to /api/agent, in order -- what this walkthrough is
/// about. Taken from the browser's own requests rather than from the server's log: the
/// log records what arrived (`input` rows), and the question here is what the PAGE SENT.
const bodies = [];
/// The answers the page got from /api/sessions, in order.
const minted = [];
/// The run edge's ABSOLUTE address, as the page itself posts to it (`VITE_AGENT_URL`,
/// which is what the dev loop hands the bundle). Kept so the door can be asked from
/// inside the page without this script knowing the harness's port.
let agentEndpoint = null;

const browser = await chromium.launch();
const page = await browser.newPage();
page.on("pageerror", (e) => console.log(`  page error: ${e.message}`));
page.on("request", (request) => {
  if (request.method() !== "POST") return;
  const body = request.postData();
  if (body == null) return;
  const parsed = JSON.parse(body);
  // THE DOOR PROBE BELOW IS THIS SCRIPT'S OWN REQUEST, not the page's, and it is skipped
  // by the id it names -- a custom header would make the browser preflight it, and the
  // harness's CORS allowance has no business answering for this script's conveniences.
  if (parsed.threadId === UNKNOWN_ID) return;
  if (request.url().includes("/api/agent")) {
    agentEndpoint = request.url();
    bodies.push(parsed);
  }
  if (request.url().includes("/api/sessions")) minted.push(parsed);
});

/// The id the door probe asks for: one this home has never heard of, which is the point.
const UNKNOWN_ID = "walkthrough-never-registered";

/// Send one message through the composer and wait for the scripted answer to it. The
/// composer is found by its class, not its words: the placeholder is whichever language
/// this browser asked for, and that is not what is under test here.
async function turn(text, answer) {
  await page.fill("textarea.aui-composer-input", text);
  await page.keyboard.press("Enter");
  await page.waitForSelector(`text=${answer}`, { timeout: 30000 });
}

/// The session this page is in, as the page itself remembers it.
const remembered = () => page.evaluate(() => localStorage.getItem("clj-harness.session"));

let red = false;
const say = (label, value) => console.log(`${label}: ${JSON.stringify(value)}`);

// ---- 1. a fresh page asks the server for a session ----------------------------------
await page.goto(url, { waitUntil: "load" });
// The ask happens when the first listing lands (App's `onListed`), so this waits for the
// REQUEST rather than for a fixed moment.
await page.waitForRequest((r) => r.method() === "POST" && r.url().includes("/api/sessions"), { timeout: 30000 });
let id = null;
for (let i = 0; i < 100 && id === null; i += 1) {
  id = await remembered();
  if (id === null) await new Promise((r) => setTimeout(r, 50));
}

console.log(`page url: ${url}`);
say("POST /api/sessions body", minted[0] ?? null);
say("the page's session (localStorage)", id);
if (minted.length !== 1 || Object.keys(minted[0] ?? {}).length !== 0) {
  console.log("RED: the page did not ask the server for an id -- it named one itself, or asked twice");
  red = true;
}
if (id === null || id === "") {
  console.log("RED: the page has no session to send anything in");
  red = true;
}

// ---- 2. what a run carries ----------------------------------------------------------
await turn("走查：第一轮", "A1（脚本的第一条回答。）");
const firstBody = bodies[0];
say("run 1 body", firstBody);
if (firstBody?.threadId !== id) {
  console.log("RED: the run's threadId is not the id the server named");
  red = true;
}
if (firstBody !== undefined && ("messages" in firstBody || "runId" in firstBody)) {
  console.log("RED: the run carried the conversation, or named its own run");
  red = true;
}
if (firstBody?.append?.length !== 1) {
  console.log("RED: the first run's append is not exactly the one question");
  red = true;
}

await turn("走查：第二轮", "A2（脚本的第二条回答。）");
const secondBody = bodies[1];
say("run 2 body", secondBody);
if (secondBody !== undefined && ("messages" in secondBody || "runId" in secondBody)) {
  console.log("RED: the SECOND run carried the conversation, or named its own run");
  red = true;
}
if (secondBody?.append?.length !== 1) {
  console.log(`RED: the second run sent ${secondBody?.append?.length} entries -- the conversation went back`);
  red = true;
}
if (!(await page.$("text=走查：第一轮"))) {
  console.log("RED: the first turn is not on screen, so 'it sent only the new question' proves nothing");
  red = true;
}

// ---- 3. a reload comes back to the same session -------------------------------------
await page.reload({ waitUntil: "load" });
for (let i = 0; i < 100; i += 1) {
  const back = await page.$("text=A1（脚本的第一条回答。）");
  if (back !== null) break;
  await new Promise((r) => setTimeout(r, 100));
}
say("after reload, the page's session", await remembered());
if ((await remembered()) !== id) {
  console.log("RED: the reload did not come back to the session the server named");
  red = true;
}
if (!(await page.$("text=A1（脚本的第一条回答。）"))) {
  console.log("RED: the reloaded page has no conversation on screen");
  red = true;
}
if (minted.length !== 1) {
  console.log("RED: the reloaded page asked for a NEW session instead of restoring the one it remembers");
  red = true;
}

await turn("走查：第三轮（刷新之后）", "A3（脚本的第三条回答。）");
const thirdBody = bodies[2];
say("run 3 body (after the reload)", thirdBody);
if (thirdBody?.threadId !== id) {
  console.log("RED: the run after the reload went to a different session");
  red = true;
}
if (thirdBody?.append?.length !== 1) {
  console.log(`RED: the run after the reload sent ${thirdBody?.append?.length} entries`);
  red = true;
}
if (thirdBody !== undefined && ("messages" in thirdBody || "runId" in thirdBody)) {
  console.log("RED: the run after the reload carried the conversation, or named its own run");
  red = true;
}

// ---- 4. the door, asked from inside the page ----------------------------------------
const refusal = await page.evaluate(async ({ runUrl, unknownId }) => {
  const res = await fetch(runUrl, {
    method: "POST",
    headers: { "Content-Type": "application/json", Accept: "text/event-stream" },
    body: JSON.stringify({ threadId: unknownId, append: [], tools: [] }),
  });
  return { status: res.status, body: await res.text() };
}, { runUrl: agentEndpoint, unknownId: UNKNOWN_ID });
say("a run for an unknown id", refusal);
if (refusal.status !== 404 || !refusal.body.includes(UNKNOWN_ID)) {
  console.log("RED: a run for an id this home does not know was not refused by name");
  red = true;
}

// ---- 5. one conversation on disk, and its record ------------------------------------
const threads = await page.evaluate(async () => (await fetch("api/threads")).json());
say("GET /api/threads", (threads ?? []).map((t) => t.threadId));
const mine = (threads ?? []).filter((t) => t.threadId === id);
if (mine.length !== 1) {
  console.log(`RED: this home lists ${mine.length} conversations for the page's id, expected 1`);
  red = true;
}
const record = (() => {
  const stack = [path.join(home, "projects")];
  while (stack.length > 0) {
    const dir = stack.pop();
    for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
      const full = path.join(dir, entry.name);
      if (entry.isDirectory()) stack.push(full);
      else if (entry.name === `${id}.jsonl`) return full;
    }
  }
  return null;
})();
say("the record", record);
if (record === null) {
  console.log("RED: three runs and no record for the page's session");
  red = true;
} else {
  const rows = fs
    .readFileSync(record, "utf8")
    .split("\n")
    .filter((line) => line.trim() !== "")
    .map((line) => JSON.parse(line));
  const inputs = rows.filter((row) => row.kind === "input");
  say("input rows", inputs.map((row) => ({ runId: row.runId, append: (row.payload?.append ?? []).map((m) => m.id).join(",") })));
  if (inputs.length !== 3) {
    console.log(`RED: the record has ${inputs.length} input rows, expected 3`);
    red = true;
  }
  if (inputs.some((row) => "messages" in (row.payload ?? {}))) {
    console.log("RED: an input row carries the retired `messages` field");
    red = true;
  }
}

await page.screenshot({ path: path.join(import.meta.dirname, "03-action-body.png"), fullPage: true });
await browser.close();

console.log(
  red
    ? "RED: the walkthrough failed"
    : `GREEN: the page asked the server to name its session (${id}), sent three runs that carried only their own question (${bodies
        .map((b) => b.append?.length ?? 0)
        .join(", ")} entries each), refused an unknown id by name, and came back to the same session after a reload`,
);
process.exit(red ? 1 : 0);
