// A real-browser walkthrough of the record notice: a conversation whose record cannot
// be written says so on the page.
//
//   node scripts/dev.mjs --scripted .scratch/sessions-live-on-the-server/evidence/go.json --ui-port 5221
//   # it prints "temp config root <HOME> (a session's record is projects/<workspace>/<thread>.jsonl under it)"
//   node .scratch/sessions-live-on-the-server/evidence/walkthrough.mjs http://localhost:5221/ <HOME>
//
// This is the layer AGENTS.md asks for when `ui/src/` has been touched, and for ticket
// 02 it is the layer that MATTERS: `app.tsx` cannot be rendered in the vitest run (it
// reaches `lib/i18n.ts` and the assistant runtime), so what the suite can pin is the
// sentence and the decision (`ui/test/suites/record.tsx`) -- WHETHER THE SENTENCE
// REACHES A PERSON, on a session somebody is driving, is a browser's question.
//
// IT GOT THE ANSWER WRONG ONCE, WHICH IS WHY IT IS KEPT. The first version of this
// feature only re-read the record on mount and on the poll for a session this page is
// WATCHING -- so a write failure during a run the page is DRIVING reached nobody, and
// the conversation went on unsaved in silence (the thing ADR 0002 decision 6 refuses).
// This script sends that second message and watches for the strip, and it does not
// reload in between: the discovery has to come from the run's own end.
//
// WHAT IT CANNOT SEE: the layout. Whether the strip pushes the conversation somewhere
// ugly is a person's judgement; the screenshot it leaves behind is for that eye.
import fs from "node:fs";
import path from "node:path";

import { chromium } from "/Users/zhouteng/.nvm/versions/node/v22.21.1/lib/node_modules/playwright/index.mjs";

const url = process.argv[2] ?? "http://localhost:5221/";
const home = process.argv[3];
if (!home) {
  console.log("usage: walkthrough.mjs <ui-url> <config-root-printed-by-dev.mjs>");
  process.exit(2);
}

/// The record file for THREAD-ID, wherever under the config root it landed: a task with
/// no project writes into the reserved `.unbound` workspace, and one that HAS a project
/// writes into that project's -- this script does not care which, and looking it up by
/// name is one line instead of a second copy of the rule.
function recordOf(threadId) {
  const stack = [path.join(home, "projects")];
  while (stack.length > 0) {
    const dir = stack.pop();
    for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
      const full = path.join(dir, entry.name);
      if (entry.isDirectory()) stack.push(full);
      else if (entry.name === `${threadId}.jsonl`) return full;
    }
  }
  return null;
}

const browser = await chromium.launch();
const page = await browser.newPage();
page.on("pageerror", (e) => console.log(`  page error: ${e.message}`));
await page.goto(url, { waitUntil: "load" });

/// Send one message and wait for the scripted answer to it. The answer is a turn from
/// `go.json`, consumed one per model call -- so this is also how the run is known to be
/// over: a run that is still streaming has not drawn its text yet.
///
/// THE COMPOSER IS FOUND BY ITS CLASS, not by its words: the accessible name and the
/// placeholder are whichever language this browser asked for, and that is not what is
/// under test here. `ComposerPrimitive.Input` renders a `<textarea>`.
async function turn(text, answer) {
  await page.fill("textarea.aui-composer-input", text);
  await page.keyboard.press("Enter");
  await page.waitForSelector(`text=${answer}`, { timeout: 30000 });
}

/// The notice, as it is on screen: the strip's own element, its text.
const notice = () =>
  page.$eval('[data-slot="record-notice"]', (el) => el.textContent ?? "").catch(() => null);

// ---- 1. a conversation that records itself fine -------------------------------------
await turn("走查：第一轮", "A1（脚本的第一条回答。）");

const threads = await page.evaluate(async () => (await fetch("api/threads")).json());
const threadId = threads[0]?.threadId;
const record = recordOf(threadId);
if (record === null) {
  console.log(`RED: the run left no record under ${home}/projects -- nothing to break`);
  await browser.close();
  process.exit(1);
}
const before = fs.readFileSync(record, "utf8").split("\n").length - 1;
console.log(`record: ${record} (${before} lines)`);
console.log(`notice before: ${JSON.stringify(await notice())}`);
if ((await notice()) !== null) {
  console.log("RED: a conversation that is being saved said it was not");
  await browser.close();
  process.exit(1);
}

// ---- 2. the disk stops taking the record, and the NEXT run says so ------------------
//
// THE FILE ITSELF, not its directory: making the file read-only is the smallest change
// that produces the failure, it is reversible, and it is what a full disk looks like
// from `spit`'s side (a permission error). This is a real write failing, not a stub.
fs.chmodSync(record, 0o444);
await turn("走查：第二轮（记录应当写不进去）", "A2（脚本的第二条回答。）");

// NO RELOAD between the two turns. The page drove both runs; if the strip is here, it
// came from the run's own end.
const said = await page
  .waitForSelector('[data-slot="record-notice"]', { timeout: 15000 })
  .then(() => notice())
  .catch(() => null);
await page.screenshot({ path: path.join(import.meta.dirname, "02-record-notice.png") });

const answer = await page.evaluate(async (id) => {
  const res = await fetch(`api/threads/${id}/sofar`);
  return { status: res.status, body: await res.json().catch(() => null) };
}, threadId);
const after = fs.readFileSync(record, "utf8").split("\n").length - 1;
await browser.close();

console.log(`notice after:  ${JSON.stringify(said)}`);
console.log(`record says:   ${JSON.stringify(answer.body?.record ?? null)}`);
console.log(`lines:         ${before} -> ${after}`);

let red = false;
if (said === null) {
  console.log("RED: the record could not be written and the page said nothing");
  red = true;
}
if (said !== null && !said.includes(path.basename(record))) {
  console.log("RED: the strip does not name the file whose write failed");
  red = true;
}
if (answer.body?.record?.state !== "degraded") {
  console.log("RED: the server does not report the session as degraded");
  red = true;
}
if (after !== before) {
  // NOT a failure of the writer -- the point is the opposite: a line that cannot be
  // written is not skipped, so the file must be exactly as long as it was.
  console.log("RED: the record grew while it was read-only");
  red = true;
}
console.log(
  red
    ? "RED: the walkthrough failed"
    : `GREEN: the page says the record could not be written (${said.length} chars) without being reloaded, and the record is unchanged at ${after} lines`,
);
process.exit(red ? 1 : 0);
