// A real-browser walkthrough of the sidebar's TWO BLOCKS, and of the one archived block
// they share.
//
//   node .scratch/sidebar-tasks/walkthrough.mjs [ui-url] [project-dir]
//
// Run it against `node scripts/dev.mjs --scripted --ui-port 5213` (a temp home, a
// scripted provider, no api-key). It is the layer AGENTS.md asks for when ui/src/ has
// been touched: the suites can render one row and can drive the server, and neither can
// see a sidebar -- which block a conversation is drawn in, whether the page moved when a
// row was filed away, and whether the header's button still needs a project.
//
// WHAT IT ASSERTS, in order: an empty home says so; "New task" makes a TASK -- flat,
// outside every project, on screen, before a word is typed; a run lands in it and it is
// still there after a reload; with a project in the home, the same button still makes a
// TASK and the project's own button still makes a session IN the project; archiving
// either kind puts it in ONE collapsed block at the bottom, and a filed-away project
// session says which project it came from; unarchiving puts it back; and the three
// regions still hold (the page never scrolls, the list does).
//
// Screenshots land in .scratch/sidebar-tasks/evidence/.
import fs from "node:fs";
import path from "node:path";

import { chromium } from "/Users/zhouteng/.nvm/versions/node/v22.21.1/lib/node_modules/playwright/index.mjs";

const url = process.argv[2] ?? "http://localhost:5213/";
const projectDir = process.argv[3] ?? "/tmp/st-tasks-proj";

const evidence = path.resolve(".scratch/sidebar-tasks/evidence");
fs.mkdirSync(evidence, { recursive: true });

let failures = 0;
function check(label, ok, detail = "") {
  console.log(`${ok ? "ok  " : "RED "} ${label}${detail === "" ? "" : ` -- ${detail}`}`);
  if (!ok) failures += 1;
}

const browser = await chromium.launch();
const page = await browser.newPage({ viewport: { width: 1100, height: 800 } });
page.on("pageerror", (e) => check("no page error", false, e.message));

await page.goto(url, { waitUntil: "load" });

// THE LISTING ARRIVES A MOMENT AFTER THE PAGE DOES, and a walkthrough that measured the
// "before" counts in that gap would read an empty home and call every count below wrong.
// So: wait until the sidebar has drawn SOMETHING -- a block, a project, or the line that
// says there is nothing.
await page.waitForFunction(
  () =>
    document.querySelector('[data-slot="sidebar-tasks"]') !== null ||
    document.querySelector('[data-slot="sidebar-project"]') !== null ||
    document.querySelector('[data-slot="sidebar-empty"]') !== null,
  undefined,
  { timeout: 15000 },
);

/// Every row drawn under one slot, as the things this walkthrough reads: the id it
/// says, the second line's words, whether it is the current one, and the label it
/// wears (which the archived block uses to say which project a row came from).
const rowsIn = (slot) =>
  page.$$eval(`[data-slot="${slot}"] [data-slot="thread-list-item"]`, (items) =>
    items.map((li) => ({
      id: li.querySelector('[data-slot="thread-list-item-id"]')?.textContent?.trim() ?? null,
      meta: li.querySelector('[data-slot="thread-list-item-meta"]')?.textContent?.trim() ?? null,
      // FROM THE ROW'S OWN ATTRIBUTE, not from `disabled`: a row is ALSO disabled while
      // any request is in flight, so "disabled" would call every row current for that
      // window -- which is exactly the moment this script does its clicking.
      current: li.hasAttribute("data-current"),
      label: li.querySelector('[data-slot="thread-list-item-label"]')?.textContent?.trim() ?? null,
    })),
  );

const tasks = () => rowsIn("sidebar-tasks");
const archivedRows = () => rowsIn("sidebar-archived-sessions");
const projectSessions = (n = 0) =>
  page.$$eval('[data-slot="sidebar-project"]', (sections, index) => {
    const section = sections[index];
    if (section === undefined) return [];
    return [...section.querySelectorAll('[data-slot="thread-list-item"]')].map((li) => ({
      id: li.querySelector('[data-slot="thread-list-item-id"]')?.textContent?.trim() ?? null,
      current: li.hasAttribute("data-current"),
    }));
  }, n);

const shot = async (name) => {
  await page.screenshot({ path: path.join(evidence, `${name}.png`) });
  console.log(`     shot ${name}.png`);
};

/// Click a row's own button -- hover first, because the row's verbs are drawn on hover
/// (and the click would otherwise land on a 0-opacity button, which Playwright refuses).
const clickRowAction = async (slot, rowIndex, action) => {
  const row = page.locator(`[data-slot="${slot}"] [data-slot="thread-list-item"]`).nth(rowIndex);
  await row.scrollIntoViewIfNeeded();
  await row.hover();
  await row.locator(`[data-slot="${action}"]`).click();
};

// ---------------------------------------------------------------- 1. an empty home

// WHAT THE HOME ALREADY HELD, so every count below is a DELTA. A walkthrough that only
// works against a home nobody has touched would pass once and lie afterwards -- and this
// one is meant to be run again after every change to the sidebar.
const tasksBefore = (await tasks()).length;
const projectsBefore = (await page.$$('[data-slot="sidebar-project"]')).length;
if (tasksBefore === 0 && projectsBefore === 0) {
  const empty = await page.textContent('[data-slot="sidebar-empty"]').catch(() => null);
  check(
    "an empty home says so in words that do not promise a project",
    empty !== null && !/project.*first|先添加一个项目/i.test(empty),
    JSON.stringify(empty),
  );
  check("no tasks block is drawn when there are no tasks", tasksBefore === 0);
} else {
  console.log(`     (this home already holds ${tasksBefore} task(s) and ${projectsBefore} project(s): counting deltas)`);
}

// ---------------------------------------------------------------- 2. New task

await page.click('[data-slot="sidebar-new-task"]');
await page.waitForSelector('[data-slot="sidebar-tasks"]', { timeout: 15000 });
let listed = await tasks();
check(
  "'New task' makes a TASK, flat and outside every project",
  listed.length === tasksBefore + 1,
  JSON.stringify(listed),
);
// THE ONE IT JUST MADE is the row that is current -- and it is the newest among equals,
// because a task that has never run sorts first (the server's own rule).
const made = listed.find((r) => r.current);
check("...and it is the conversation on screen", made !== undefined, JSON.stringify(listed));
check(
  "...with no disk facts yet, which is not a zero-byte file",
  made?.meta === "never run · no log yet",
  made?.meta,
);
const taskId = made?.id;
check("...and it is drawn outside every project", (await page.$$('[data-slot="sidebar-project"]')).length === projectsBefore);
await shot("t02-01-new-task-flat-no-project");

// ---------------------------------------------------------------- 3. a run lands in it

// ONE SCRIPTED TURN: two model calls (a tool round, then the answer). The composer's
// textarea is assistant-ui's; Enter submits.
await page.fill("textarea", "look at this project");
await page.press("textarea", "Enter");
// THE ROW'S SPINNER IS THE RUN'S OWN SIGNAL -- it comes from the page's registry rather
// than from the listing (which is a snapshot and says so; see the sidebar's header), so
// waiting on it and then pressing Refresh is the honest way to watch a run land.
await page.waitForSelector('[data-slot="sidebar-tasks"] [data-slot="thread-list-item-running"]', {
  timeout: 15000,
});
await page.waitForFunction(
  () => document.querySelector('[data-slot="sidebar-tasks"] [data-slot="thread-list-item-running"]') === null,
  undefined,
  { timeout: 90000 },
);
await page.click('[data-slot="sidebar-refresh"]');
// BY ID, NOT "the first task row": a home this walkthrough has been run against before
// holds other tasks, and one of those is exactly what a positional read would pick up.
await page.waitForFunction(
  (id) =>
    [...document.querySelectorAll('[data-slot="sidebar-tasks"] [data-slot="thread-list-item"]')].some(
      (li) =>
        li.querySelector('[data-slot="thread-list-item-id"]')?.textContent?.trim() === id &&
        !/never run|还没跑过/.test(
          li.querySelector('[data-slot="thread-list-item-meta"]')?.textContent ?? "never run",
        ),
    ),
  taskId,
  { timeout: 15000 },
);
const afterRun = (await tasks()).find((r) => r.id === taskId);
check("the task's row now has the log's own numbers", /B$/.test(afterRun?.meta ?? ""), afterRun?.meta);

// ---------------------------------------------------------------- 4. it survives a reload

await page.reload({ waitUntil: "load" });
await page.waitForSelector('[data-slot="sidebar-tasks"]', { timeout: 15000 });
const restored = (await tasks()).find((r) => r.id === taskId);
check("after a reload the task is still listed", restored?.id === taskId, JSON.stringify(restored));
check("...and the page came back to it", restored?.current === true);
await shot("t02-02-task-after-reload");

// ---------------------------------------------------------------- 5. a project, and both blocks

const added = await page.evaluate(async (dir) => {
  const res = await fetch("api/projects", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ dir }),
  });
  return res.status;
}, projectDir);
check("a directory becomes a project", added === 200, `status ${added}`);
await page.click('[data-slot="sidebar-refresh"]');
await page.waitForSelector('[data-slot="sidebar-project"]', { timeout: 15000 });

// The project row's own button -- the verb that still starts a session IN a project.
// The project the script added is the LAST one (a fresh home lists what it has in
// creation order), and its session list starts empty.
const projectIndex = (await page.$$('[data-slot="sidebar-project"]')).length - 1;
// A DELTA, not "exactly one": this project may already hold sessions from an earlier run
// of this walkthrough (they are adopted back when the same directory is added again).
const sessionsBefore = (await projectSessions(projectIndex)).length;
await page.locator('[data-slot="sidebar-project-new-session"]').nth(projectIndex).click();
await page.waitForFunction(
  ([index, want]) =>
    document.querySelectorAll('[data-slot="sidebar-project"]')[index]?.querySelectorAll(
      '[data-slot="thread-list-item"]',
    ).length === want,
  [projectIndex, sessionsBefore + 1],
  { timeout: 15000 },
);
check(
  "the project's own button starts a session IN the project",
  (await projectSessions(projectIndex)).length === sessionsBefore + 1,
);
check(
  "...while the task list is untouched: one block, one conversation each",
  (await tasks()).length === tasksBefore + 1,
  JSON.stringify(await tasks()),
);
check("...and the project's session is the one on screen", (await projectSessions(projectIndex))[0]?.current === true);
await shot("t02-03-two-blocks");

// THE HEADER'S BUTTON, WITH A PROJECT IN THE HOME, still makes a TASK -- it does not
// land in the project that happens to be selected.
await page.click('[data-slot="sidebar-new-task"]');
await page.waitForFunction(
  (want) => document.querySelectorAll('[data-slot="sidebar-tasks"] [data-slot="thread-list-item"]').length === want,
  tasksBefore + 2,
  { timeout: 15000 },
);
check("with a project present, 'New task' still makes a TASK", (await tasks()).length === tasksBefore + 2);
check(
  "...and the project's session list did not grow",
  (await projectSessions(projectIndex)).length === sessionsBefore + 1,
);
check("...and the new task is the one on screen", (await tasks()).some((r) => r.current === true));
await shot("t02-04-new-task-lands-in-the-task-block");

// ---------------------------------------------------------------- 6. archiving, both kinds, one block

// A task that is NOT the one on screen: filing it away must not move the page.
const otherTask = (await tasks()).find((r) => !r.current);
const otherIndex = (await tasks()).findIndex((r) => r.id === otherTask?.id);
check("there is a task that is not the one on screen to file away", otherIndex >= 0);
await clickRowAction("sidebar-tasks", otherIndex, "thread-list-item-archive");
await page.waitForSelector('[data-slot="sidebar-archived-trigger"]', { timeout: 15000 });
check("archiving a task draws the archived block", (await page.$$('[data-slot="sidebar-archived"]')).length === 1);
check("...collapsed, so it does not take the room the row did", (await archivedRows()).length === 0);
check("...and the page stayed where it was", (await tasks()).some((r) => r.current === true));
await shot("t03-01-one-archived-block-collapsed");

// The project's session goes into the SAME block, and says where it came from.
await clickRowAction("sidebar-project", projectIndex, "thread-list-item-archive");
await page.click('[data-slot="sidebar-archived-trigger"]');
await page.waitForSelector('[data-slot="sidebar-archived-sessions"]', { timeout: 15000 });
check(
  "...and there is exactly ONE such block on the page",
  (await page.$$('[data-slot="sidebar-archived"]')).length === 1,
  "a project that kept a group of its own would draw a second one",
);
const both = await archivedRows();
// BOTH KINDS IN ONE BLOCK, asserted by what each row IS rather than by how many rows
// there are: a home this script has run against before holds archived rows of its own.
check("the archived block holds a TASK", both.some((r) => r.id === otherTask?.id && r.label === null), JSON.stringify(both));
const archivedProjectRow = both.find((r) => r.label === "st-tasks-proj");
check("...and a session from a PROJECT, wearing its project's name", archivedProjectRow !== undefined, JSON.stringify(both));
await shot("t03-02-archived-both-kinds-with-project-name");

// Bringing it back puts it where it came from -- BY ID, in both blocks, because that is
// the claim: the row moved between two lists rather than being drawn in both.
const archivedProjectId = archivedProjectRow?.id;
await clickRowAction(
  "sidebar-archived-sessions",
  both.findIndex((r) => r.id === archivedProjectId),
  "thread-list-item-archive",
);
await page.waitForFunction(
  ([id, index]) =>
    ![...document.querySelectorAll('[data-slot="sidebar-archived-sessions"] [data-slot="thread-list-item"]')].some(
      (li) => li.querySelector('[data-slot="thread-list-item-id"]')?.textContent?.trim() === id,
    ) &&
    document.querySelectorAll('[data-slot="sidebar-project"]')[index]?.textContent?.includes(id),
  [archivedProjectId, projectIndex],
  { timeout: 15000 },
);
check("unarchiving returns it to its project", (await projectSessions(projectIndex)).some((r) => r.id === archivedProjectId));
check("...and it leaves the archived block", !(await archivedRows()).some((r) => r.id === archivedProjectId));
await shot("t03-03-unarchived-back-in-its-project");

// ---------------------------------------------------------------- 6b. filed away from ELSEWHERE

// The block opens ITSELF when the conversation on screen is inside it -- the case a
// person cannot trigger from the sidebar (archiving what you are reading moves you off
// it on purpose), and which happens for real when the archiving was done by another
// window or by the API. So this one is done the way it happens: through the route.
const currentId =
  (await tasks()).find((r) => r.current)?.id ??
  (await projectSessions(projectIndex)).find((r) => r.current)?.id;
check("the page is on a session that can be filed away by somebody else", typeof currentId === "string" && currentId !== "", JSON.stringify(currentId));
const archivedElsewhere = await page.evaluate(async (id) => {
  const res = await fetch(`api/threads/${id}/archive`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ archived: true }),
  });
  return res.status;
}, currentId);
check("a conversation can be filed away by another client", archivedElsewhere === 200, `status ${archivedElsewhere}`);
await page.click('[data-slot="sidebar-refresh"]');
// WAIT FOR THE ROW ITSELF, not for the block: the block may already be expanded from the
// step above, so waiting for it would race the refreshed listing.
await page.waitForFunction(
  (id) =>
    [...document.querySelectorAll('[data-slot="sidebar-archived-sessions"] [data-slot="thread-list-item"]')].some(
      (li) => li.querySelector('[data-slot="thread-list-item-id"]')?.textContent?.trim() === id,
    ),
  currentId,
  { timeout: 15000 },
);
check("...and the block opens itself for the session on screen", (await archivedRows()).some((r) => r.id === currentId));
await shot("t03-04-block-opens-for-what-you-are-reading");

// Put it back, so the state this leaves behind is the one the run started from.
await page.evaluate(async (id) => {
  await fetch(`api/threads/${id}/archive`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ archived: false }),
  });
}, currentId);
await page.click('[data-slot="sidebar-refresh"]');

// ---------------------------------------------------------------- 7. the three regions

await page.setViewportSize({ width: 1100, height: 420 });
await page.waitForTimeout(200);
const layout = await page.evaluate(() => ({
  docScrolls: document.documentElement.scrollHeight > document.documentElement.clientHeight,
  headerTop: Math.round(document.querySelector('[data-slot="sidebar-header"]').getBoundingClientRect().top),
  footerBottom: Math.round(
    document.querySelector('[data-slot="sidebar-footer"]').getBoundingClientRect().bottom,
  ),
  viewport: document.documentElement.clientHeight,
}));
check("no whole-page scroll with the viewport squeezed", layout.docScrolls === false, JSON.stringify(layout));
check(
  "...and both ends are still pinned: the header at the top, the footer at the bottom",
  layout.headerTop === 0 && Math.abs(layout.footerBottom - layout.viewport) < 2,
  JSON.stringify(layout),
);
await shot("t02-05-three-regions-still-hold");

await browser.close();
console.log(failures === 0 ? "\nGREEN" : `\n${failures} RED`);
process.exit(failures === 0 ? 0 : 1);
