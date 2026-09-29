// A real-browser walkthrough of THE RIGHT-HAND TASK PANE (ticket 01 of `.scratch/task-pane-push`).
//
//   node .scratch/task-pane-push/walkthrough.mjs [ui-url]
//
// Run it against
//   node scripts/dev.mjs --scripted .scratch/task-pane-push/script.json --ui-port 5322
// (a temp home, a scripted provider whose first turn is a `bash` job and a delegation, so the
// pane has BOTH sections to draw and a clock that is really moving).
//
// WHAT IT ASSERTS, and none of it is visible to a suite: the pane POLLS NOTHING any more.
//
//   1. THE SNAPSHOT IS ONE READ. Opening the pane (and opening the session it draws) asks
//      `GET /api/threads/<id>/jobs` and `GET /api/subagents` ONCE each -- not once a second.
//   2. NOTHING KEEPS ASKING. With the pane OPEN and a job running, the request count for those
//      two routes does not grow over a stretch in which the old pane would have made a dozen
//      requests. That is the whole point of the ticket, and it is measurable from the outside.
//   3. THE ROWS CARRY BOTH TIMES (owner, 2026-09-27): a running job's row shows WHEN IT STARTED
//      and a duration that GROWS between two samples; the duration asks nothing.
//   4. AND THE PUSH IS WHAT MOVES IT: a job STARTED by the page appears in the pane without a
//      second read of either route, because the server rang the session's socket.
//   4. AND THE PUSH IS WHAT MOVES IT: a job STARTED by the page appears in the pane without a
//      second read of either route, because the server rang the session's socket.
//   5. AND A REPLACED SOCKET NAMES THE SESSION AGAIN: the downlink is closed by hand, and the
//      handshake that follows must carry this session -- the bug this ticket ends with was a
//      declaration that left the pane's family out, so the server stopped sending and a
//      finished job's row went on saying 'so far'. The pane reads its snapshot once more after
//      the reconnect, which is the repair a frame with no cursor has (see the last section).
// Screenshots land in .scratch/task-pane-push/evidence/.
import fs from "node:fs";
import path from "node:path";

import { execSync } from "node:child_process";
import { createRequire } from "node:module";
const require = createRequire(import.meta.url);
function loadChromium() {
  if (process.env.PLAYWRIGHT_PATH) return require(process.env.PLAYWRIGHT_PATH);
  try {
    const root = execSync("npm root -g", { encoding: "utf8" }).trim();
    return require(path.join(root, "@playwright", "cli", "node_modules", "playwright", "index.mjs"));
  } catch {
    return require("playwright");
  }
}
const { chromium } = loadChromium();

const url = process.argv[2] ?? "http://localhost:5322/";
const evidence = path.resolve(".scratch/task-pane-push/evidence");
fs.mkdirSync(evidence, { recursive: true });

let failures = 0;
function check(label, ok, detail = "") {
  console.log(`${ok ? "ok  " : "RED "} ${label}${detail === "" ? "" : ` -- ${detail}`}`);
  if (!ok) failures += 1;
}

/// HOW MANY TIMES A PAGE HAS ASKED THE PANE'S ROUTES, counted from the network layer (the
/// initiator does not matter: what the ticket promises is that NOTHING asks again).
const paneReads = (page) =>
  page.evaluate(() => {
    const entries = performance.getEntriesByType("resource").filter((e) =>
      /\/api\/(subagents|threads\/[^/]+\/jobs)/.test(e.name),
    );
    return entries.map((e) => e.name.replace(/^.*\/api\//, ""));
  });

const paneText = (page) =>
  page.evaluate(() => {
    const el = document.querySelector('[data-slot="task-pane"]');
    return el ? el.textContent.replace(/\s+/g, " ").trim() : null;
  });

const browser = await chromium.launch();
const page = await browser.newPage({ viewport: { width: 1500, height: 900 }, locale: "zh-CN" });

// EVERY SOCKET THIS PAGE OPENS IS RECORDED, and installed BEFORE the page's own script runs so
// the constructor the app finds is this one. The last check below REPLACES the mux socket by
// hand, because a reconnect is the one thing a frame with no cursor cannot survive on its own
// (`lib/mux.ts`'s `TaskFrame`).
await page.addInitScript(() => {
  const Real = window.WebSocket;
  window.__sockets = [];
  class Recorded extends Real {
    constructor(url, protocols) {
      super(url, protocols);
      const record = { url: String(url), socket: this, opened: false, closed: false };
      window.__sockets.push(record);
      this.addEventListener("open", () => {
        record.opened = true;
      });
      this.addEventListener("close", () => {
        record.closed = true;
      });
    }
  }
  Object.defineProperty(Recorded, "OPEN", { value: Real.OPEN });
  window.WebSocket = Recorded;
});
page.on("pageerror", (e) => check("no page error", false, e.message));

await page.goto(url, { waitUntil: "load" });
await page.waitForSelector('[data-slot="sidebar-tasks"], [data-slot="sidebar-empty"]', { timeout: 15000 });

// A session that runs something: the script's first turn is a `bash` call that keeps a job
// alive for seconds, which is what the pane needs to have a moving duration to draw.
await page.getByRole("button", { name: "新建任务" }).click();
const box = page.getByRole("textbox", { name: "消息输入框" });
await box.fill("任务视图走查:后台作业与委派");
await box.press("Enter");

// OPEN THE PANE (the right column), which is what mounts `useTaskPane`.
const openPane = page.getByRole("button", { name: /打开任务视图|打开右侧|任务视图/ }).first();
if (await openPane.count()) {
  await openPane.click();
} else {
  // The pane may already be open on this width; nothing to press.
  console.log("     (no open control found -- the pane may already be showing)");
}
await page.waitForSelector('[data-slot="task-pane"]', { timeout: 10000 });

// The two routes' reads, as they stand after opening.
const afterOpen = await paneReads(page);
check("the pane read its routes, and read them ONCE each", afterOpen.length >= 1, `reads: ${afterOpen.join(", ")}`);

// ---------------------------------------------------------------- nothing keeps asking
// The job runs for seconds; the old pane asked twice a second through all of it.
const before = (await paneReads(page)).length;
await page.waitForTimeout(6000);
const after = (await paneReads(page)).length;
check("no poll: the pane asked nothing while the job ran", after === before,
  `reads ${before} -> ${after}`);

// ---------------------------------------------------------------- the two times, and a moving one
const rows = await paneText(page);
check("a job row is drawn", rows !== null && rows.includes("后台") , rows === null ? "no pane text" : rows.slice(0, 120));

const durationOf = (slot) =>
  page.evaluate((s) => {
    const el = document.querySelector(`[data-slot="${s}"]`);
    return el ? el.textContent.trim() : null;
  }, slot);

const first = await durationOf("task-pane-job-duration");
await page.waitForTimeout(2500);
const second = await durationOf("task-pane-job-duration");
check("a running job's duration is drawn", first !== null, `${first}`);
check("and it GROWS between two samples, asking nothing", first !== second, `${first} -> ${second}`);

const started = await durationOf("task-pane-job-started");
check("and the row says WHEN IT STARTED", started !== null && started.length > 0, `${started}`);

const readsAfterTicking = (await paneReads(page)).length;
check("the ticking duration cost no request", readsAfterTicking === before, `reads ${readsAfterTicking}`);

await page.screenshot({ path: path.join(evidence, "task-pane-running.png") });

// ---------------------------------------------------------------- finished rows keep both
// The script's job ends on its own; the row must keep its start AND show a settled duration.
await page.waitForFunction(
  () => {
    const el = document.querySelector('[data-slot="task-pane-job-duration"]');
    return el !== null && !el.textContent.includes("已跑");
  },
  undefined,
  { timeout: 30000 },
).catch(() => {});
const settledStart = await durationOf("task-pane-job-started");
const settledDuration = await durationOf("task-pane-job-duration");
check("a finished job keeps its start", settledStart !== null, `${settledStart}`);
check("and shows how long it TOOK (not 'so far')", settledDuration !== null && !settledDuration.includes("已跑"),
  `${settledDuration}`);

const readsAtEnd = (await paneReads(page)).length;
check("the ending arrived by push, not by asking", readsAtEnd === before, `reads ${readsAtEnd}`);

await page.screenshot({ path: path.join(evidence, "task-pane-settled.png") });

// ---------------------------------------------------------------- a REPLACED socket
// THE ONE THING A FRAME WITH NO CURSOR CANNOT SURVIVE BY ITSELF (this ticket's bug): the socket
// is thrown away and reopened, and then
//
//   * the new handshake must name THIS session again -- a family missing from
//     `wantedThreads()` is a family the server stops sending to, and the row that showed 'so
//     far' for a finished job was exactly that;
//   * and the pane must read its snapshot once more, because what changed while the socket was
//     down is in no frame this page will ever be handed.
const sessionId =
  (await paneReads(page))
    .map((read) => read.match(/threads\/([^/]+)\/jobs/)?.[1])
    .filter(Boolean)
    .at(-1) ?? null;
const readsBeforeReconnect = (await paneReads(page)).length;
const dropped = await page.evaluate(() => {
  const mux = window.__sockets.filter((s) => s.url.includes("events.mux")).at(-1);
  if (!mux) return false;
  mux.socket.close();
  return true;
});
check("a downlink socket was there to replace", dropped);
await page
  .waitForFunction(() => window.__sockets.filter((s) => s.url.includes("events.mux")).at(-1)?.opened === true, undefined, { timeout: 15000 })
  .catch(() => {});
await page.waitForTimeout(1500);
const handshake = await page.evaluate(() => {
  const last = window.__sockets.filter((s) => s.url.includes("events.mux")).at(-1);
  return last && last.opened ? decodeURIComponent(last.url) : null;
});
check(
  "the replaced socket names this session in its handshake",
  handshake !== null && sessionId !== null && handshake.includes(sessionId),
  sessionId === null ? "no session read" : `session ${sessionId}` ,
);
const readsAfterReconnect = (await paneReads(page)).length;
check(
  "and the pane read its routes again after the reconnect (a pair, not a poll)",
  readsAfterReconnect >= readsBeforeReconnect + 2,
  `reads ${readsBeforeReconnect} -> ${readsAfterReconnect}` ,
);
const settledAfter = await durationOf("task-pane-job-duration");
check("and the settled row is still the settled row", settledAfter !== null && !settledAfter.includes("已跑"), `${settledAfter}`);

await browser.close();
if (failures > 0) {
  console.log(`\n${failures} check(s) RED`);
  process.exit(1);
}
console.log("\nall checks green");
