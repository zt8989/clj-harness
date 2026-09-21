// A real-browser walkthrough of the FOLD: a project and the task block draw five rows and
// say how many they are holding back, the row you are reading is never the one that folds
// away, and the archived block is not folded at all.
//
//   node .scratch/sidebar-five-rows/walkthrough.mjs [ui-url]
//
// Run it against
//   node scripts/dev.mjs --scripted .scratch/sidebar-five-rows/evidence/script.json --ui-port 5219
// (a temp home, a scripted provider, no api-key).
//
// WHY A BROWSER. The rule itself is arithmetic and is pinned without one
// (`ui/test/suites/sidebar-rows.ts`: five, the sixth, the current row past the fold). What
// no suite in this repo can reach is the two CALLERS -- `components/sidebar.tsx` cannot be
// imported into the vitest run at all (it reaches `lib/i18n.ts`, which touches `document`)
// -- so "does a project really draw five", "does clicking the control draw the sixth",
// "where does the control's label land", and "is the row being read still on screen after a
// reload" are questions only this file can answer.
//
// EVERY CELL IS KEYED BY SESSION ID AND COMPARED WITH THE STORE, never by counting rows on
// its own: this file is meant to be run twice against the same dev pair, and the second run
// finds the first run's sessions still there. So a block's drawn rows are checked against
// the listing's own order (`GET /api/projects`, read through the same-origin proxy) rather
// than against a literal list -- which is also the stronger claim, since it pins WHICH five
// (the five most recently sent to, in order).
//
// WHAT IT ASSERTS, in order:
//
//   1. a project with six sessions draws five rows and a control saying `还有 1 个`, and the
//      sixth (the oldest) is the one held back;
//   2. the control's label sits at the same x as the rows' titles -- a fold control, not a
//      stray button;
//   3. clicking it draws all six and the control says `收起`; clicking again goes back to
//      five (the control does not disappear when it is used);
//   4. the row being read, when it is the sixth, keeps the block open and there is NO
//      control -- before and after a reload, which is when "the row I am on" is restored
//      from memory into an otherwise folded list;
//   5. the task block follows the same five-row rule, including that same exception;
//   6. the archived block holds every archived row it has (no control at all), which is the
//      decision that block was left out of this feature on purpose for.
//
// Screenshots land in .scratch/sidebar-five-rows/evidence/.
import fs from "node:fs";
import os from "node:os";
import path from "node:path";

import { chromium } from "/Users/zhouteng/.nvm/versions/node/v22.21.1/lib/node_modules/playwright/index.mjs";

const url = process.argv[2] ?? "http://localhost:5219/";

const evidence = path.resolve(".scratch/sidebar-five-rows/evidence");
fs.mkdirSync(evidence, { recursive: true });

let failures = 0;
function check(label, ok, detail = "") {
  console.log(`${ok ? "ok  " : "RED "} ${label}${detail === "" ? "" : ` -- ${detail}`}`);
  if (!ok) failures += 1;
}

/// SIX OF EACH, because five is the fold: one message per session, distinctive enough that
/// reading it back cannot be a coincidence, and short enough for the title limit
/// (`TITLE_MAX` is 60 code points).
const IN_PROJECT = ["项目里第一句", "项目里第二句", "项目里第三句", "项目里第四句", "项目里第五句", "项目里第六句"];
const IN_TASKS = ["任务第一句", "任务第二句", "任务第三句", "任务第四句", "任务第五句", "任务第六句"];

/// THE PROJECT THIS WALKTHROUGH ADDS, made by this process and handed to the server as a
/// path -- the same temporary-directory discipline the suites keep. Created under the OS
/// temp dir rather than inside the repo, so a run that dies leaves nothing behind in the
/// tree. A NEW DIRECTORY EACH RUN is also what keeps the project block's cells exact: no
/// previous run's sessions can be in it.
const projectDir = fs.mkdtempSync(path.join(os.tmpdir(), "sidebar-five-rows-"));

const browser = await chromium.launch();
// THE LANGUAGE IS THE OWNER'S, so the words this file looks for are the Chinese ones -- and
// it is set rather than hoped for: the page's chain is remembered -> browser -> English, and
// a fresh context speaks whatever the browser does.
const page = await browser.newPage({ viewport: { width: 1100, height: 900 }, locale: "zh-CN" });
page.on("pageerror", (e) => check("no page error", false, e.message));

await page.goto(url, { waitUntil: "load" });
await page.waitForSelector(
  '[data-slot="sidebar-tasks"], [data-slot="sidebar-project"], [data-slot="sidebar-empty"]',
  { timeout: 15000 },
);

const TASKS = '[data-slot="sidebar-tasks"]';
const ARCHIVED = '[data-slot="sidebar-archived-sessions"]';
/// THE TWO BLOCKS, by the two selectors each of them needs: the `<ul>` a block's ROWS live
/// in, and the control that folds it. The control is a SIBLING of that list rather than a
/// row inside it (it is not a session, and nothing walking the rows should find it), so the
/// two are named separately -- and the control is named by its EXACT slot rather than by a
/// `-more` suffix, because a project's own menu button is `sidebar-project-more`.
const SESSIONS_IN = (canonicalPath) =>
  `[data-slot="sidebar-project"][data-path="${canonicalPath}"] [data-slot="sidebar-sessions"]`;
const MORE_IN = '[data-slot="sidebar-sessions-more"]';
const TASKS_MORE = '[data-slot="sidebar-tasks-more"]';

/// Every row of one block, as this walkthrough reads it: the id its tooltip carries (the
/// first line of it -- the second is the exact instant), what it says, and whether it is the
/// session on screen.
const rowsIn = (scope) =>
  page.$$eval(`${scope} [data-slot="thread-list-item"]`, (items) =>
    items.map((li) => {
      const trigger = li.querySelector('[data-slot="thread-list-item-trigger"]');
      return {
        id: trigger?.getAttribute("title")?.split("\n")[0] ?? null,
        says: li.querySelector('[data-slot="thread-list-item-title"]')?.textContent?.trim() ?? null,
        current: li.hasAttribute("data-current"),
      };
    }),
  );

/// THE FOLD CONTROL OF ONE BLOCK, as it reads and where its label lands. `null` when the
/// block has no control, which is a state two of the cells below are ABOUT -- so it is
/// reported rather than asserted away.
const foldControl = (selector) =>
  page.evaluate((selector) => {
    const button = document.querySelector(selector);
    if (button === null) return null;
    const label = button.querySelector("span:last-child");
    return {
      slot: button.getAttribute("data-slot"),
      label: label?.textContent?.trim() ?? null,
      expanded: button.getAttribute("aria-expanded"),
      labelX: label === null ? null : label.getBoundingClientRect().x,
      x: button.getBoundingClientRect().x,
    };
  }, selector);

/// WHERE A BLOCK'S ROW TITLES START, for the alignment cell: the control's label has to land
/// on the same x the rows' titles do, or it reads as a different kind of thing.
const titleX = (scope) =>
  page.$eval(
    `${scope} [data-slot="thread-list-item"] [data-slot="thread-list-item-title"]`,
    (el) => el.getBoundingClientRect().x,
  );

const shot = async (name) => {
  await page.screenshot({ path: path.join(evidence, `${name}.png`) });
  console.log(`     shot ${name}.png`);
};

/// THE STORE'S OWN ANSWER, read out of the page through the same-origin proxy: the cells
/// below are about what the LISTING holds (which five of how many), and a render alone
/// cannot say how many rows a block was handed.
const storeListing = () =>
  page.evaluate(async () => {
    const res = await fetch("/api/projects");
    return res.ok ? await res.json() : { error: res.status, projects: [], tasks: [] };
  });

/// ONE BLOCK'S ROWS AS THE STORE ORDERS THEM -- the same rule the server sorts by
/// (`newest-first`: `lastSentAt` descending, never-sent last), applied here to the listing's
/// own payload so the walkthrough does not have to trust the payload's order.
const storeOrder = (sessions) =>
  sessions
    .filter((s) => !s.archived)
    .slice()
    .sort((a, b) => (b.lastSentAt ?? -1) - (a.lastSentAt ?? -1))
    .map((s) => s.threadId);

const storeTasks = async () => storeOrder((await storeListing()).tasks);
const storeProjectSessions = async (projectId) => {
  const listing = await storeListing();
  const project = listing.projects.find((p) => p.projectId === projectId);
  return storeOrder(project?.sessions ?? []);
};

/// SEND: type into the composer and press Enter.
const send = async (text) => {
  await page.fill("textarea", text);
  await page.press("textarea", "Enter");
};

/// THE SESSION THE PAGE IS ON, read from the page's OWN MEMORY (`clj-harness.session`,
/// written by `app.tsx` on every change of what is shown) rather than from the list: a
/// session minted by "New task"/"New session" has no row until somebody sends.
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

/// WAIT FOR A ROW, BY ID, IN ONE BLOCK.
const waitForRow = async (scope, id) => {
  await page.waitForFunction(
    ({ scope, id }) =>
      [...document.querySelectorAll(`${scope} [data-slot="thread-list-item"]`)].some((li) =>
        li
          .querySelector('[data-slot="thread-list-item-trigger"]')
          ?.getAttribute("title")
          ?.startsWith(id),
      ),
    { scope, id },
    { timeout: 20000 },
  );
};

/// THE IDLE WAIT: the scripted provider answers immediately, and the sidebar disables its
/// "new" buttons while a run is in flight -- so every seeding cycle waits for the spinner to
/// leave before it mints the next session.
const waitForIdle = () =>
  page.waitForFunction(
    () => document.querySelector('[data-slot="thread-list-item-running"]') === null,
    undefined,
    { timeout: 60000 },
  );

/// ONE SEEDING CYCLE: mint a session, send its first message, wait for the row (the
/// sidebar's own once-per-id refetch puts it there -- no refresh is clicked anywhere in this
/// file), then wait for the run to settle.
const seed = async (mint, scope, text) => {
  const before = await shownId();
  await page.click(mint);
  const id = await waitForNewShownId(before);
  await send(text);
  await waitForRow(scope, id);
  await waitForIdle();
  return id;
};

// ------------------------------------------------------------- the project, seeded

const added = await page.evaluate(async (dir) => {
  const res = await fetch("/api/projects", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ dir }),
  });
  return { status: res.status, body: await res.json().catch(() => null) };
}, projectDir);
check("the project is added through the real edge", added.status === 200, JSON.stringify(added));
// THE SERVER'S CANONICAL PATH, which is what the row is keyed by -- `project/bind!` resolves
// symlinks, and on macOS the temp dir handed over (`/var/...`) is really `/private/var/...`.
const projectPath = added.body.path;
const projectId = added.body.projectId;
await page.click('[data-slot="sidebar-refresh"]');
await page.waitForSelector(`[data-slot="sidebar-project"][data-path="${projectPath}"]`, { timeout: 15000 });
const inProject = SESSIONS_IN(projectPath);
const mintInProject = `[data-slot="sidebar-project"][data-path="${projectPath}"] [data-slot="sidebar-project-new-session"]`;

for (const text of IN_PROJECT) {
  await seed(mintInProject, inProject, text);
}

// ------------------------------------------- 1. five rows, and the sixth is held back

const projectStoreIds = await storeProjectSessions(projectId);
check(
  "the project really holds six sessions",
  projectStoreIds.length === IN_PROJECT.length,
  `${projectStoreIds.length}`,
);
const foldedProject = await rowsIn(inProject);
check(
  "a project with six sessions draws five rows",
  foldedProject.length === 5,
  JSON.stringify(foldedProject.map((r) => r.says)),
);
check(
  "...and they are the FIVE MOST RECENTLY SENT TO, in the store's own order",
  JSON.stringify(foldedProject.map((r) => r.id)) === JSON.stringify(projectStoreIds.slice(0, 5)),
  JSON.stringify(foldedProject.map((r) => r.id)),
);
check(
  "...so the one held back is the oldest",
  !foldedProject.some((r) => r.id === projectStoreIds[5]),
  `${projectStoreIds[5]}`,
);
const projectFold = await foldControl(MORE_IN);
check("...and the block says so, in a control of its own", projectFold !== null, JSON.stringify(projectFold));
check(
  "...which counts the rows behind the fold, not the total",
  projectFold?.label === "还有 1 个",
  String(projectFold?.label),
);
check("...and reports itself collapsed", projectFold?.expanded === "false", String(projectFold?.expanded));
await shot("01-a-project-draws-five-rows");

// ---------------------------------------------- 2. the control is on the rows' own x

const rowsX = await titleX(inProject);
check(
  "the fold control's label sits on the rows' own x",
  Math.abs((projectFold?.labelX ?? -1) - rowsX) < 1.5,
  `rows ${rowsX} vs control ${projectFold?.labelX}`,
);

// --------------------------------------- 3. opening draws the sixth, and it can close

await page.click(MORE_IN);
const openedProject = await rowsIn(inProject);
check(
  "clicking it draws every session the project has",
  JSON.stringify(openedProject.map((r) => r.id)) === JSON.stringify(projectStoreIds),
  JSON.stringify(openedProject.map((r) => r.says)),
);
const openFold = await foldControl(MORE_IN);
check(
  "...and the control is still there, now saying how to go back",
  openFold !== null && openFold.label === "收起",
  JSON.stringify(openFold),
);
check("...and reports itself expanded", openFold?.expanded === "true", String(openFold?.expanded));
await shot("02-the-project-opened-to-all-six");

await page.click(MORE_IN);
const refolded = await rowsIn(inProject);
check(
  "clicking again folds it back to five",
  refolded.length === 5 &&
    JSON.stringify(refolded.map((r) => r.id)) === JSON.stringify(projectStoreIds.slice(0, 5)),
  JSON.stringify(refolded.map((r) => r.says)),
);
check(
  "...with the count back",
  (await foldControl(MORE_IN))?.label === "还有 1 个",
  String((await foldControl(MORE_IN))?.label),
);

// ------------------------- 4. the row being read keeps the block open, reload included

// READ THE SIXTH: it is only reachable through the control, which is the point -- the fold
// is a way to reach history, not a way to lose it.
const oldest = projectStoreIds[5];
await page.click(MORE_IN);
await page.click(
  `${inProject} [data-slot="thread-list-item"]:has([data-slot="thread-list-item-trigger"][title^="${oldest}"]) [data-slot="thread-list-item-trigger"]`,
);
await page.waitForFunction(
  (id) =>
    [...document.querySelectorAll('[data-slot="thread-list-item"]')].some(
      (li) =>
        li
          .querySelector('[data-slot="thread-list-item-trigger"]')
          ?.getAttribute("title")
          ?.startsWith(id) && li.hasAttribute("data-current"),
    ),
  oldest,
  { timeout: 20000 },
);
// AND NOW THE RELOAD, which is the case this rule exists for: the page comes back on that
// session with the block folded in memory, and the row it is reading has to survive it.
await page.reload({ waitUntil: "load" });
await page.waitForSelector(`${inProject} [data-slot="thread-list-item"]`, { timeout: 20000 });
const afterReload = await rowsIn(inProject);
check(
  "the session being read is still on screen after a reload (the block opens for it)",
  afterReload.length === projectStoreIds.length,
  JSON.stringify(afterReload.map((r) => r.says)),
);
check(
  "...and it is still the current one",
  afterReload.find((r) => r.id === oldest)?.current === true,
  JSON.stringify(afterReload.filter((r) => r.current)),
);
check(
  "...and there is NO control to fold it away again while that is why it is open",
  (await foldControl(MORE_IN)) === null,
  JSON.stringify(await foldControl(MORE_IN)),
);
await shot("03-the-row-being-read-keeps-the-block-open");

// ------------------------------------------------------------- the task block, same rule

for (const text of IN_TASKS) {
  await seed('[data-slot="sidebar-new-task"]', TASKS, text);
}
await page.click('[data-slot="sidebar-refresh"]');

const taskStoreIds = await storeTasks();
const foldedTasks = await rowsIn(TASKS);
check(
  "the task block holds more than five sessions (this run made six)",
  taskStoreIds.length >= IN_TASKS.length,
  `${taskStoreIds.length}`,
);
check(
  "the task block draws five rows",
  foldedTasks.length === 5,
  JSON.stringify(foldedTasks.map((r) => r.says)),
);
check(
  "...the five most recently sent to, in the store's own order",
  JSON.stringify(foldedTasks.map((r) => r.id)) === JSON.stringify(taskStoreIds.slice(0, 5)),
  JSON.stringify(foldedTasks.map((r) => r.id)),
);
const taskFold = await foldControl(TASKS_MORE);
check(
  "...and it says how many it is holding back",
  taskFold?.label === `还有 ${taskStoreIds.length - 5} 个`,
  `${taskFold?.label} (store holds ${taskStoreIds.length})`,
);
await shot("04-the-task-block-draws-five-rows");

await page.click('[data-slot="sidebar-tasks-more"]');
const openedTasks = await rowsIn(TASKS);
check(
  "clicking it draws every task the store has",
  JSON.stringify(openedTasks.map((r) => r.id)) === JSON.stringify(taskStoreIds),
  `${openedTasks.length} of ${taskStoreIds.length}`,
);
check(
  "...and it can fold back",
  (await foldControl(TASKS_MORE))?.label === "收起",
  String((await foldControl(TASKS_MORE))?.label),
);

// THE SAME EXCEPTION, one block up: the OLDEST task, read, and the page reloaded on it.
const oldestTask = taskStoreIds[taskStoreIds.length - 1];
await page.click(
  `${TASKS} [data-slot="thread-list-item"]:has([data-slot="thread-list-item-trigger"][title^="${oldestTask}"]) [data-slot="thread-list-item-trigger"]`,
);
await page.waitForFunction(
  (id) =>
    [...document.querySelectorAll('[data-slot="thread-list-item"]')].some(
      (li) =>
        li
          .querySelector('[data-slot="thread-list-item-trigger"]')
          ?.getAttribute("title")
          ?.startsWith(id) && li.hasAttribute("data-current"),
    ),
  oldestTask,
  { timeout: 20000 },
);
await page.reload({ waitUntil: "load" });
await page.waitForSelector(`${TASKS} [data-slot="thread-list-item"]`, { timeout: 20000 });
const tasksAfterReload = await rowsIn(TASKS);
check(
  "the task being read is on screen after a reload too -- the whole block is drawn",
  tasksAfterReload.length === (await storeTasks()).length,
  `${tasksAfterReload.length}`,
);
check(
  "...it is the current one",
  tasksAfterReload.find((r) => r.id === oldestTask)?.current === true,
  JSON.stringify(tasksAfterReload.filter((r) => r.current)),
);
check(
  "...and no control is offered while that is why it is open",
  (await foldControl(TASKS_MORE)) === null,
  JSON.stringify(await foldControl(TASKS_MORE)),
);
await shot("05-the-task-being-read-keeps-the-block-open");

// ------------------------------------------- 6. the archived block is NOT folded

// ARCHIVE THE PROJECT'S SIX: the archived block is the one place this rule was left out of
// (the owner's call), so it has to be seen holding more than five rows with no control.
// The archive buttons are revealed on hover (`lib/reveal.ts`), so each row is hovered first.
for (const id of projectStoreIds) {
  const row = `${inProject} [data-slot="thread-list-item"]:has([data-slot="thread-list-item-trigger"][title^="${id}"])`;
  if ((await page.$(row)) === null) {
    // It is behind the fold: draw everything before taking one away.
    await page.click(MORE_IN);
  }
  await page.hover(`${row} [data-slot="thread-list-item-trigger"]`);
  await page.click(`${row} [data-slot="thread-list-item-archive"]`);
  await page.waitForFunction(
    ({ scope, id }) =>
      ![...document.querySelectorAll(`${scope} [data-slot="thread-list-item"]`)].some((li) =>
        li
          .querySelector('[data-slot="thread-list-item-trigger"]')
          ?.getAttribute("title")
          ?.startsWith(id),
      ),
    { scope: inProject, id },
    { timeout: 20000 },
  );
}
const projectRowsAfterArchiving = await rowsIn(inProject);
check(
  "the project is empty once its sessions are filed away",
  projectRowsAfterArchiving.length === 0,
  JSON.stringify(projectRowsAfterArchiving),
);

await page.click('[data-slot="sidebar-archived-trigger"]');
const archivedListing = await storeListing();
const archivedInStore = [
  ...archivedListing.projects.flatMap((p) => p.sessions.filter((s) => s.archived)),
  ...archivedListing.tasks.filter((s) => s.archived),
];
const archivedDrawn = await rowsIn(ARCHIVED);
check(
  "the archived block holds more than five rows (this run filed six away, plus any earlier)",
  archivedInStore.length > 5,
  `${archivedInStore.length}`,
);
check(
  "...and draws every one of them -- no fold, by decision",
  archivedDrawn.length === archivedInStore.length,
  `${archivedDrawn.length} of ${archivedInStore.length}`,
);
check(
  "...with no fold control of its own",
  (await foldControl('[data-slot="sidebar-archived-more"]')) === null,
  "an archived block draws what it holds",
);
await shot("06-archived-is-not-folded");

console.log(failures === 0 ? "\nall cells ok" : `\n${failures} cell(s) RED`);
await browser.close();
fs.rmSync(projectDir, { recursive: true, force: true });
process.exit(failures === 0 ? 0 : 1);
