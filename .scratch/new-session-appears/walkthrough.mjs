// A real-browser walkthrough of WHEN A NEW SESSION'S ROW APPEARS, and of what a directory
// pick may write before anybody has sent anything.
//
//   node .scratch/new-session-appears/walkthrough.mjs [ui-url]
//
// Run it against
//   node scripts/dev.mjs --scripted .scratch/new-session-appears/script.json --ui-port 5221
// (a temp home, a scripted provider, no api-key).
//
// WHAT IT IS HERE FOR. Two promises, and neither can be seen by a suite:
//
//   1. A PICK OF A DIRECTORY WRITES NOTHING until somebody sends. "点击新增不立刻会话，
//      发送才新建" was implemented for the sidebar's two buttons, and the composer's own
//      directory picker was the door it was left in: `POST /api/project` is a
//      find-or-create, so ONE pick wrote a session row and a log whose only line is the
//      `project/bound` audit -- a thread id with no conversation, which every later
//      refresh lists as a session nobody has sent to (seen in the owner's own home:
//      four such rows, one of them with no run in it at all).
//   2. THE ROW IS THERE WHILE THE RUN IS STILL GOING. The row is written by the
//      registration that runs BEFORE the run (`app.tsx`'s `registerPending`), so the
//      sidebar has no business waiting for the run to finish -- and holding the ask until
//      `running` went false is exactly the reported bug: 发送之后左侧不出现，刷新才出现,
//      because a manual refresh read the store while the page was still waiting.
//      THE SCRIPT IS WHAT MAKES THIS MEASURABLE: its first turn is a `bash` call that
//      sleeps, so the run is in flight for seconds rather than milliseconds, and the cell
//      can insist that the row arrived WITH its spinner.
//
// EVERY CELL IS KEYED BY SESSION ID, never by counting rows (a store that already holds
// the previous run's sessions is a normal store), and the project it adds is a temp
// directory of this process's own.
//
// WHAT IT ASSERTS, in order:
//
//   1. the project is added through the real edge, and the sidebar draws it;
//   2. "New session" in that project writes nothing: no store row, no log (the stats route
//      is the probe -- it answers 404 for an id with no file), no row drawn;
//   3. PICKING that project in the composer's directory picker still writes nothing --
//      the store has no row and the log is still missing -- and the bar now names it;
//   4. the first send creates the session AND the row is drawn WITHOUT a refresh, UNDER
//      THAT PROJECT, while the run is still in flight (the spinner is in the row at the
//      moment it appears);
//   5. and the send bound it to the directory that was REMEMBERED, not to none.
//
// Screenshots land in .scratch/new-session-appears/evidence/.
import fs from "node:fs";
import os from "node:os";
import path from "node:path";

import { chromium } from "/Users/zhouteng/.nvm/versions/node/v22.21.1/lib/node_modules/playwright/index.mjs";

const url = process.argv[2] ?? "http://localhost:5221/";

const evidence = path.resolve(".scratch/new-session-appears/evidence");
fs.mkdirSync(evidence, { recursive: true });

let failures = 0;
function check(label, ok, detail = "") {
  console.log(`${ok ? "ok  " : "RED "} ${label}${detail === "" ? "" : ` -- ${detail}`}`);
  if (!ok) failures += 1;
}

/// The first message, distinctive enough that finding it by text cannot be a coincidence.
const FIRST = "项目目录里选完之后的第一句";

/// THE PROJECT THIS WALKTHROUGH ADDS, under the OS temp dir rather than in the tree. It is
/// NOT removed before the send: a bind to a directory that is gone is refused (400), which
/// is a different walkthrough's question.
const projectDir = fs.mkdtempSync(path.join(os.tmpdir(), "new-session-appears-"));

const browser = await chromium.launch();
const page = await browser.newPage({
  viewport: { width: 1100, height: 800 },
  locale: "zh-CN",
});
page.on("pageerror", (e) => check("no page error", false, e.message));

await page.goto(url, { waitUntil: "load" });
await page.waitForSelector(
  '[data-slot="sidebar-tasks"], [data-slot="sidebar-project"], [data-slot="sidebar-empty"]',
  { timeout: 15000 },
);

const TASKS = '[data-slot="sidebar-tasks"]';
const SESSIONS_IN = (canonicalPath) =>
  `[data-slot="sidebar-project"][data-path="${canonicalPath}"] [data-slot="sidebar-sessions"]`;

const shot = async (name) => {
  await page.screenshot({ path: path.join(evidence, `${name}.png`) });
  console.log(`     shot ${name}.png`);
};

/// THE STORE'S OWN ANSWER, read out of the page through the same-origin proxy: "库里也没有
/// 新行" is not visible in a list that happens to be stale, so the listing is asked for
/// directly as well as rendered.
const storeSessions = async () => {
  const listing = await page.evaluate(async () => {
    const res = await fetch("/api/projects");
    return res.ok ? await res.json() : { error: res.status };
  });
  return [
    ...listing.projects.flatMap((p) => p.sessions.map((s) => ({ ...s, projectId: p.projectId }))),
    ...listing.tasks.map((s) => ({ ...s, projectId: null })),
  ];
};

/// WHETHER THIS HOME HAS A LOG FOR AN ID -- the second half of "the session does not exist
/// yet", and the half a store row cannot answer. `/api/threads/<id>/stats` reads the
/// record, so it refuses (404, "no log for thread ...") exactly while there is no file;
/// after the send there is one.
const hasLog = async (id) => {
  const status = await page.evaluate(async (threadId) => {
    const res = await fetch(`/api/threads/${encodeURIComponent(threadId)}/stats`);
    return res.status;
  }, id);
  return status === 200;
};

/// THE SESSION THE PAGE IS ON, from the page's OWN MEMORY (`clj-harness.session`, written
/// by `app.tsx` on every change of what is shown) rather than from the list: a session
/// minted a moment ago has NO row, so the list cannot name it.
const shownId = () => page.evaluate(() => localStorage.getItem("clj-harness.session"));
const waitForNewShownId = async (previous) => {
  await page.waitForFunction(
    (prev) => {
      const id = localStorage.getItem("clj-harness.session");
      return id !== null && id !== prev;
    },
    previous,
    { timeout: 10000 },
  );
  return shownId();
};

/// WHETHER A ROW FOR ID IS DRAWN IN ONE BLOCK, and whether that row is carrying the run's
/// spinner at this instant -- read in ONE evaluate so the two cannot be a moment apart.
const rowOf = (scope, id) =>
  page.evaluate(
    ({ scope, id }) => {
      const li = [...document.querySelectorAll(`${scope} [data-slot="thread-list-item"]`)].find((el) =>
        el
          .querySelector('[data-slot="thread-list-item-trigger"]')
          ?.getAttribute("title")
          ?.startsWith(id),
      );
      if (li === undefined) return null;
      return {
        says: li.querySelector('[data-slot="thread-list-item-title"]')?.textContent?.trim() ?? null,
        running: li.querySelector('[data-slot="thread-list-item-running"]') !== null,
        error: li.querySelector('[data-slot="thread-list-item-error"]')?.textContent?.trim() ?? null,
      };
    },
    { scope, id },
  );

/// SEND: type into the composer and press Enter. Nothing else is clicked afterwards -- the
/// whole point of the cell below is that the sidebar asks by itself.
const send = async (text) => {
  await page.fill("textarea", text);
  await page.press("textarea", "Enter");
};

// ---------------------------------------------------------------- 1. a project to start in

const added = await page.evaluate(async (dir) => {
  const res = await fetch("/api/projects", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ dir }),
  });
  return { status: res.status, body: await res.json().catch(() => null) };
}, projectDir);
check("the project is added through the real edge", added.status === 200, JSON.stringify(added));
// THE SERVER'S CANONICAL PATH, which is what the row is keyed by (`project/bind!` resolves
// symlinks, and on macOS the temp dir handed over is really `/private/var/...`).
const projectPath = added.body.path;
await page.click('[data-slot="sidebar-refresh"]');
await page.waitForSelector(`[data-slot="sidebar-project"][data-path="${projectPath}"]`, { timeout: 15000 });
const projectName = await page.$eval(
  `[data-slot="sidebar-project"][data-path="${projectPath}"] [data-slot="sidebar-project-name"]`,
  (el) => el.textContent.trim(),
);
check("the project row is listed", projectName.length > 0, projectName);
const inProjectScope = SESSIONS_IN(projectPath);

// ------------------------------------- 2. "New session" in it: nothing exists yet, anywhere

// THE ID IS READ BEFORE THE CLICK, because the click is what changes it: reading it
// afterwards would compare the new session with itself and wait forever.
const beforeMint = await shownId();
await page.click(
  `[data-slot="sidebar-project"][data-path="${projectPath}"] [data-slot="sidebar-project-new-session"]`,
);
const held = await waitForNewShownId(beforeMint);
await page.waitForTimeout(300);
const sessionsAfterMint = await storeSessions();
check(
  "'New session' in a project writes no row",
  !sessionsAfterMint.some((s) => s.threadId === held),
  held,
);
check("...and no log for it either", !(await hasLog(held)), held);
check("...and it is not drawn", (await rowOf(inProjectScope, held)) === null, held);
await shot("01-a-new-session-exists-nowhere");

// ---------------------------- 3. naming that directory in the COMPOSER still writes nothing

await page.click('[data-slot="composer-directory-trigger"]');
await page.waitForSelector('[data-slot="composer-directory-option"]', { timeout: 10000 });
await page.click(`[data-slot="composer-directory-option"][title="${projectPath}"]`);
await page.waitForTimeout(400);
const afterPick = await storeSessions();
check(
  "picking that project in the composer writes no row either",
  !afterPick.some((s) => s.threadId === held),
  JSON.stringify(afterPick.map((s) => s.threadId)),
);
check("...and still no log for it", !(await hasLog(held)), held);
const shownDir = await page.$eval('[data-slot="composer-directory-value"]', (el) =>
  el.textContent.trim(),
);
check("...and the bar names the directory it is now holding", shownDir === projectName, shownDir);
await shot("02-the-pick-holds-the-directory-and-writes-nothing");

// ---------------- 4. the first send: the row arrives on its own, while the run is in flight

await send(FIRST);
// THE RUN THIS SESSION STARTS SLEEPS FOR SECONDS (the script's own first turn), so a row
// that arrives with its spinner is a row that did NOT wait for the run to finish -- which
// is the whole claim. `waitForFunction` polls as fast as it can, so the reading is taken
// as close to the appearance as the page allows.
await page.waitForFunction(
  ({ scope, id }) =>
    [...document.querySelectorAll(`${scope} [data-slot="thread-list-item"]`)].some((el) =>
      el
        .querySelector('[data-slot="thread-list-item-trigger"]')
        ?.getAttribute("title")
        ?.startsWith(id),
    ),
  { scope: inProjectScope, id: held },
  { timeout: 20000, polling: 20 },
);
const atArrival = await rowOf(inProjectScope, held);
check("the first send makes the row appear, with no refresh clicked", atArrival !== null, held);
check(
  "...and it arrived WHILE THE RUN WAS STILL GOING (the row carries its spinner)",
  atArrival?.running === true,
  JSON.stringify(atArrival),
);
check("...with the words that were sent", atArrival?.says === FIRST, String(atArrival?.says));
check("...and nothing refused it", atArrival?.error === null, String(atArrival?.error));
await shot("03-the-row-is-there-while-the-run-is-still-going");

// THE SEND DID NOT MARK IT AS A TASK: the remembered directory is what the bind used, and
// the store is where that shows.
const afterSend = await storeSessions();
const bound = afterSend.find((s) => s.threadId === held);
check(
  "...and the store put it in the project the pick remembered",
  bound?.projectId === added.body.projectId,
  JSON.stringify(bound),
);
check("...and it has a log now", await hasLog(held), held);
check(
  "...and it is NOT in the task block",
  (await page.$$eval(`${TASKS} [data-slot="thread-list-item"]`, (els) =>
    els.map((el) => el.querySelector('[data-slot="thread-list-item-trigger"]')?.getAttribute("title")),
  )).every((title) => !title?.startsWith(held)),
  held,
);

// The run is a `sleep 6`; let it finish before the browser goes away, so the record is
// closed and the screenshots are of a settled page rather than of a half-written one.
await page.waitForFunction(
  () => document.querySelector('[data-slot="thread-list-item-running"]') === null,
  undefined,
  { timeout: 60000 },
);
await shot("04-the-settled-row");

console.log(failures === 0 ? "\nall cells ok" : `\n${failures} cell(s) RED`);
await browser.close();
fs.rmSync(projectDir, { recursive: true, force: true });
process.exit(failures === 0 ? 0 : 1);