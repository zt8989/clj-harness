// TICKET 13: the trajectory is a SESSION VIEW -- one read, then pushes on the open stream.
//
//   node scripts/dev.mjs --scripted .scratch/session-as-kernel/view.json
//   node .scratch/session-as-kernel/walkthrough-view.mjs <the address dev.mjs printed>
//
// WHAT IS ON TRIAL, and why a browser is the place for it:
//
//   THE STREAM IS HELD OPEN. The route answers NDJSON and, for a session this process HOLDS,
//   does not close: it keeps the connection and pushes the turns that finalize later. The
//   first turn arriving proves the first load; the connection being STILL OPEN afterwards is
//   the mechanism the pushes ride, and that is a fact about a real fetch, not about a value.
//   `harness.edge.trajectory-test/a-later-turn-is-pushed-on-the-open-stream` pins the push
//   itself over real HTTP; this pins the page's half -- the view draws what the stream sends
//   and does not hang up on it.
//
// WHAT IT DOES NOT PROVE: that the fold was not read twice. That is the moved-file test's
// business, which needs the filesystem and not a page.
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { execSync } from "node:child_process";

const HERE = path.dirname(fileURLToPath(import.meta.url));
const EVIDENCE = path.join(HERE, "evidence");
fs.mkdirSync(EVIDENCE, { recursive: true });

const globalRoot = execSync("npm root -g", { encoding: "utf8" }).trim();
const { chromium } = await import(path.join(globalRoot, "playwright", "index.mjs"));

const url = process.argv[2] ?? "http://localhost:5300/";

let failures = 0;
const check = (label, ok, detail = "") => {
  console.log(`${ok ? "ok  " : "RED "} ${label}${detail === "" ? "" : ` -- ${detail}`}`);
  if (!ok) failures += 1;
};

const browser = await chromium.launch();
const page = await browser.newPage({ viewport: { width: 1280, height: 900 } });
const pageErrors = [];
page.on("pageerror", (e) => pageErrors.push(e.message));

/// THE TRAJECTORY FETCHES THE PAGE MADE, and whether they are OVER. A pushed stream is one
/// that has NOT finished, which is the whole shape of ticket 13.
const trajectoryOpened = [];
const trajectoryClosed = [];
page.on("request", (r) => {
  if (r.url().includes("/trajectory")) trajectoryOpened.push(r);
});
page.on("requestfinished", (r) => {
  if (r.url().includes("/trajectory")) trajectoryClosed.push(r.url());
});
page.on("requestfailed", (r) => {
  if (r.url().includes("/trajectory")) trajectoryClosed.push(r.url());
});

const until = async (fn, ms = 30000) => {
  const end = Date.now() + ms;
  for (;;) {
    if (await fn()) return true;
    if (Date.now() > end) return false;
    await new Promise((resolve) => setTimeout(resolve, 100));
  }
};

await page.goto(url, { waitUntil: "load" });
await page.waitForSelector("textarea", { timeout: 20000 });
await page.fill("textarea", "第一句。");
await page.press("textarea", "Enter");

check(
  "the run settles and the answer lands",
  await until(async () =>
    (await page.locator('[data-slot="session-stop"]').count()) === 0 &&
    (await page.locator('[data-slot="aui_assistant-message-content"]:visible').allTextContents()).some((text) =>
      text.includes("第一条回答。"),
    ),
  ),
);
check("no trajectory request was made from the conversation tab", trajectoryOpened.length === 0);

await page.click('[data-slot="view-switch-tab"][data-view="trajectory"]');
check(
  "opening the tab draws the first turn -- the first load",
  await until(async () => (await page.locator('[data-slot="trajectory-turn"]').count()) >= 1),
);
await page.screenshot({ path: path.join(EVIDENCE, "view-01-first-load.png") });

// THE CONNECTION IS STILL OPEN: the page holds ONE trajectory stream, and nothing has closed
// it -- that is the channel the later turns are pushed on.
check("exactly one trajectory stream was opened", trajectoryOpened.length === 1, String(trajectoryOpened.length));
check(
  "and it is STILL OPEN (nothing finished it)",
  trajectoryClosed.length === 0,
  trajectoryClosed.join(" ; "),
);

check("no page error", pageErrors.length === 0, pageErrors.join("; "));

await browser.close();
console.log(failures === 0 ? "ALL GREEN" : `${failures} RED`);
process.exit(failures === 0 ? 0 : 1);
