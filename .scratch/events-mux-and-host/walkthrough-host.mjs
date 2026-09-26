// A TWO-WINDOW check of the HOST DOWNLINK (`events.host`, ADR 0004; ticket 02 of
// `.scratch/events-mux-and-host`): a row one window creates appears in the OTHER window's
// sidebar without a refresh.
//
//   node scripts/dev.mjs --scripted .scratch/events-mux-and-host/slow.json --ui-port 5319
//   node .scratch/events-mux-and-host/walkthrough-host.mjs http://localhost:5319/
//
// WHAT IS ON TRIAL. Both pages open FIRST, so neither has a host for the session the other
// is about to create. When page A sends, the server writes the row and rings the host bus;
// page B must draw that row -- and its running spinner -- with no refresh click and no
// reload. The project half pins the same door with a listing change nothing on the page
// asked for.
//
// WHAT IT DOES NOT PROVE: a socket that never connects falls back to the refresh button
// (that button is untouched and the mount still reads the listing), and nothing here says
// anything about the conversation downlink (`events.mux` -- that has its own walkthrough,
// the reload-mid-run one).
import fs from "node:fs";
import os from "node:os";
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
const until = async (fn, ms = 20000) => {
  const end = Date.now() + ms;
  for (;;) {
    if (await fn()) return true;
    if (Date.now() > end) return false;
    await new Promise((resolve) => setTimeout(resolve, 100));
  }
};

const browser = await chromium.launch();
const context = await browser.newContext({ viewport: { width: 1280, height: 900 } });
const pageErrors = [];
context.on("page", (page) => page.on("pageerror", (e) => pageErrors.push(e.message)));

const openPage = async () => {
  const page = await context.newPage();
  await page.goto(url, { waitUntil: "load" });
  await page.waitForSelector("textarea", { timeout: 20000 });
  await page.waitForSelector('[data-slot="sidebar"]', { timeout: 20000 });
  return page;
};

const rowTexts = (page) =>
  page.$$eval('[data-slot="thread-list-item"]', (els) => els.map((el) => el.textContent ?? ""));
const runningRows = (page) => page.$$eval('[data-slot="thread-list-item-running"]', (els) => els.length);
const projectNames = (page) =>
  page.$$eval('[data-slot="sidebar-project-name"]', (els) => els.map((el) => el.textContent ?? ""));

const B = await openPage(); // the WATCHER, opened first
const A = await openPage(); // the WRITER

check("the watcher starts with no rows of its own", (await rowTexts(B)).length === 0, JSON.stringify(await rowTexts(B)));

// ---- a session the OTHER window creates.
await A.fill("textarea", "跨窗口的一行");
await A.press("textarea", "Enter");

check(
  "the watcher's sidebar shows the row the writer just created, with no refresh",
  await until(async () => (await rowTexts(B)).some((text) => text.includes("跨窗口的一行"))),
  JSON.stringify(await rowTexts(B)),
);
check(
  "...and the row says it is running -- the server's registry, pushed",
  await until(async () => (await runningRows(B)) > 0),
  `runningRows=${await runningRows(B)}`,
);
check(
  "...and the spinner clears on its own when that run ends",
  await until(async () => (await runningRows(B)) === 0, 30000),
  `runningRows=${await runningRows(B)}`,
);

// ---- a project another writer adds.
const dir = fs.mkdtempSync(path.join(os.tmpdir(), "mux-project-"));
const basename = path.basename(dir);
await B.evaluate(async (target) => {
  await fetch("api/projects", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ dir: target }),
  });
}, dir);
check(
  "the watcher's sidebar shows a project another writer added, with no refresh",
  await until(async () => (await projectNames(B)).some((name) => name.includes(basename))),
  `${basename} -- ${JSON.stringify(await projectNames(B))}`,
);

await B.screenshot({ path: path.join(evidence, "host-01-two-windows.png") });
check("no page error", pageErrors.length === 0, pageErrors.join("; "));

await browser.close();
console.log(failures === 0 ? "ALL GREEN" : `${failures} RED`);
process.exit(failures === 0 ? 0 : 1);
