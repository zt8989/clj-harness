// TICKET 04: a delegation panel reads its child over the page's ONE downlink.
//
//   node scripts/dev.mjs --scripted .scratch/subagents/walkthrough-script.json --ui-port 5319
//   node .scratch/events-mux-and-host/walkthrough-follow.mjs http://localhost:5319/
//
// WHAT IS ON TRIAL: the panel's agent no longer holds a channel of its own. It reads the
// record's replay over HTTP (`GET /api/threads/<stem>/frames`) and the live tail over
// `events.mux`, joined by the frame's own `:seq`. The task it was handed is the frame
// NOTHING else can rebuild (it is a `message` row, not a frame), so seeing it on screen is
// the replay half working; the same socket is then the live half.
//
// WHAT IT DOES NOT PROVE: nothing here forces the child to still be writing when the panel
// opens, so the LIVE tail is exercised but not pinned -- the frame-level boundary is the
// follow-route suite's business (`the-frames-route-answers-the-replay-as-json-and-keeps-the-number`).
import fs from "node:fs";
import path from "node:path";
import { execSync } from "node:child_process";

const globalRoot = execSync("npm root -g", { encoding: "utf8" }).trim();
const { chromium } = await import(path.join(globalRoot, "playwright", "index.mjs"));

const url = process.argv[2] ?? "http://localhost:5319/";
const evidence = path.resolve(".scratch/events-mux-and-host/evidence");
fs.mkdirSync(evidence, { recursive: true });

let failures = 0;
const check = (label, ok, detail = "") => {
  console.log(`${ok ? "ok  " : "RED "} ${label}${detail === "" ? "" : ` -- ${detail}`}`);
  if (!ok) failures += 1;
};
const until = async (fn, ms = 30000) => {
  const end = Date.now() + ms;
  for (;;) {
    if (await fn()) return true;
    if (Date.now() > end) return false;
    await new Promise((resolve) => setTimeout(resolve, 150));
  }
};

const browser = await chromium.launch();
const page = await browser.newPage({ viewport: { width: 1280, height: 900 } });
const pageErrors = [];
page.on("pageerror", (e) => pageErrors.push(e.message));

await page.goto(url, { waitUntil: "load" });
await page.waitForSelector("textarea", { timeout: 20000 });
await page.fill("textarea", "这一段交给探索子agent：它能读，不能写。");
await page.press("textarea", "Enter");

check(
  "the delegation leaves a door to open",
  await until(async () => (await page.$$('[data-slot="tool-call-trigger-door"]')).length > 0),
);
// THE DOOR IS IN THE DOM BUT THE ROW MAY BE COLLAPSED, so dispatch the click straight at it
// rather than asking Playwright for a hit-test the disclosure would refuse.
await page.$eval('[data-slot="tool-call-trigger-door"]', (el) => el.click());

check(
  "the panel opens",
  await until(async () => (await page.$$('[data-slot="subagent-view"]')).length > 0),
);

const panelText = async () =>
  page.$eval('[data-slot="subagent-view"]', (el) => el.textContent ?? "").catch(() => "");

check(
  "...and shows the task the child was handed -- the frame only the record replay can rebuild",
  await until(async () => (await panelText()).includes("src/harness/cap")),
  (await panelText()).slice(0, 160),
);
check(
  "...and draws no error",
  (await page.$$('[data-slot="subagent-view-error"]')).length === 0,
);

await page.screenshot({ path: path.join(evidence, "follow-01-panel-over-the-downlink.png") });
check("no page error", pageErrors.length === 0, pageErrors.join("; "));

await browser.close();
console.log(failures === 0 ? "ALL GREEN" : `${failures} RED`);
process.exit(failures === 0 ? 0 : 1);
