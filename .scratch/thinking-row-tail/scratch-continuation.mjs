// DIAGNOSTIC, NOT A GATE: a real vendor's interleaving, drawn as ONE thought.
//
//   node .scratch/thinking-row-tail/scratch-continuation.mjs <ui-url> <config-home> <project-dir>
//
// Run it against `node scripts/dev.mjs --scripted .scratch/thinking-row-tail/script.json --ui-port 5395`
// -- the two paths it needs are the ones that banner prints (the temp config root,
// and the directory the dev server is bound to).
//
// WHY. A reader saw a rendering error: 思考 · <the thought> before the answer, and a
// SECOND 思考 · in Chinese. at the bottom. That is not invented -- it is one real
// session's frames (`~/.clj-harness/projects/…/24b44253….jsonl`), where the vendor
// streamed `REASONING "Answer briefly"`, started the answer, then sent the last
// token of the SAME thought (`" in Chinese."`) as a second reasoning block, then went
// on with the answer. The scripted provider cannot produce that shape (one reasoning
// block and one text per turn), so this plants the record instead: mint a session,
// write the frames, open it, and count the 思考 rows.
import fs from "node:fs";
import path from "node:path";
import { execSync } from "node:child_process";
import { fileURLToPath } from "node:url";

const HERE = path.dirname(fileURLToPath(import.meta.url));
const globalRoot = execSync("npm root -g", { encoding: "utf8" }).trim();
const { chromium } = await import(path.join(globalRoot, "playwright", "index.mjs"));

const url = process.argv[2] ?? "http://localhost:5395/";
const home = process.argv[3];
const projectDir = process.argv[4];
/// A REAL record to plant byte for byte. Hand-writing the frames was tried first and
/// is NOT faithful: a record carries both the run's frames and the folded `message`
/// line, and a hand-made pair of those makes the page draw TWO assistant messages
/// (which the turn-fold then hides one of) instead of the one message a real run
/// writes. The real file is the only shape worth testing against.
const recordToCopy = process.argv[5];
if (home === undefined || projectDir === undefined) {
  console.error("usage: scratch-continuation.mjs <ui-url> <config-home> <project-dir>");
  process.exit(2);
}

/// The frames, copied in shape from the session that showed it: reasoning, the
/// answer's first token, the SAME thought's last token as its own block, then the
/// rest of the answer.
const QUESTION = "你是谁？";
const THOUGHT = 'The user asks in Chinese: "你是谁？". I should answer concisely.';
const TAIL = " in Chinese.";
const ANSWER_A = "我是跑在";
const ANSWER_B = "你这台机器上的一个编码 agent。";

const lines = [];
const push = (payload, extra = {}) =>
  lines.push(JSON.stringify({ ts: Date.now(), runId: "r1", type: "event", payload, ...extra }));
lines.push(JSON.stringify({ ts: Date.now(), runId: null, type: "message", payload: { role: "user", content: QUESTION } }));
push({ type: "RUN_STARTED" });
push({ type: "MESSAGES_SNAPSHOT", messages: [{ id: "u1", role: "user", content: QUESTION }] });
push({ type: "REASONING_START", messageId: "r1-r0" });
push({ type: "REASONING_MESSAGE_START", messageId: "r1-r0", role: "reasoning" });
push({ type: "REASONING_MESSAGE_CONTENT", messageId: "r1-r0", delta: THOUGHT });
push({ type: "REASONING_MESSAGE_END", messageId: "r1-r0" });
push({ type: "REASONING_END", messageId: "r1-r0" });
push({ type: "TEXT_MESSAGE_START", messageId: "r1-m1", role: "assistant" });
push({ type: "TEXT_MESSAGE_CONTENT", messageId: "r1-m1", delta: ANSWER_A });
push({ type: "REASONING_START", messageId: "r1-r2" });
push({ type: "REASONING_MESSAGE_START", messageId: "r1-r2", role: "reasoning" });
push({ type: "REASONING_MESSAGE_CONTENT", messageId: "r1-r2", delta: TAIL });
push({ type: "REASONING_MESSAGE_END", messageId: "r1-r2" });
push({ type: "REASONING_END", messageId: "r1-r2" });
push({ type: "TEXT_MESSAGE_CONTENT", messageId: "r1-m1", delta: ANSWER_B });
push({ type: "TEXT_MESSAGE_END", messageId: "r1-m1" });
push({ type: "RUN_FINISHED" });
lines.push(
  JSON.stringify({
    ts: Date.now(),
    runId: "r1",
    source: "model",
    type: "message",
    payload: { role: "assistant", content: ANSWER_A + ANSWER_B, reasoning_content: THOUGHT + TAIL },
  }),
);

const minted = await fetch(`${url}api/project`, {
  method: "POST",
  headers: { "Content-Type": "application/json" },
  body: JSON.stringify({ dir: projectDir }),
});
const { threadId } = await minted.json();
const dir = path.join(home, "projects", projectDir.replaceAll("/", "_"));
fs.mkdirSync(dir, { recursive: true });
const record = path.join(dir, `${threadId}.jsonl`);
if (recordToCopy === undefined) {
  fs.writeFileSync(record, `${lines.join("\n")}\n`);
} else {
  fs.copyFileSync(recordToCopy, record);
}
console.log(`planted ${record}${recordToCopy === undefined ? " (synthesised)" : ` (copied from ${recordToCopy})`}`);

const browser = await chromium.launch();
const page = await browser.newPage({ viewport: { width: 1200, height: 900 } });
page.on("pageerror", (e) => console.log("page error:", e.message));
await page.goto(url);

/// The sidebar lists it under the project BY ITS ID: a planted record is a file the
/// server's store knows only as a minted id, so there is no title to click yet
/// (`firstUserText` is null until a run writes one). Clicking the id is what a reader
/// would do for a session that has never run in this page.
await page.getByText(threadId).first().click();
await page.waitForSelector('[data-slot="reasoning-trigger"]', { timeout: 30000, state: "attached" });
await page.waitForTimeout(800);

/// PER MESSAGE, and by size: the turn-fold hides the messages before the last one,
/// and a hidden row is not a row a reader sees.
const read = () =>
  page.evaluate(() => {
    const messages = [...document.querySelectorAll("[data-slot='aui_assistant-message-content']")];
    return {
      messages: messages.map((m) => ({
        drawn: getComputedStyle(m).display !== "none",
        rows: [...m.querySelectorAll('[data-slot="reasoning-trigger"]')].map((el) => ({
          text: el.textContent,
          visible: el.getBoundingClientRect().width > 0,
        })),
        text: (m.innerText || "").replace(/\s+/g, " ").slice(0, 60),
      })),
    };
  });
const first = await read();
for (const [i, m] of first.messages.entries()) {
  console.log(`message ${i}: drawn=${m.drawn} rows=${m.rows.length} text=${JSON.stringify(m.text)}`);
  for (const row of m.rows) console.log(`    visible=${row.visible} ${JSON.stringify(row.text)}`);
}
const drawn = first.messages.filter((m) => m.drawn);
const rowsInDrawn = drawn.flatMap((m) => m.rows.filter((r) => r.visible));
console.log(`visible rows in the drawn transcript: ${rowsInDrawn.length}`);
await page.screenshot({ path: path.join(HERE, "evidence", "scratch-continuation.png") });

await browser.close();
const stray = rowsInDrawn.filter((r) => (r.text || "").includes("in Chinese."));
console.log(
  stray.length === 0
    ? "OK: no stray 思考 carrying the thought's tail after the answer"
    : `WRONG: ${stray.length} stray row(s): ${stray.map((r) => JSON.stringify(r.text)).join(", ")}`,
);