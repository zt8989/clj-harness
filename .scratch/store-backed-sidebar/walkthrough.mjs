// A real-browser walkthrough of the STORE-BACKED sidebar: one line per session, a
// relative send time, an indent that is exactly the spinner's box, and a session that
// does not exist until somebody sends to it.
//
//   node .scratch/store-backed-sidebar/walkthrough.mjs [ui-url]
//
// Run it against
//   node scripts/dev.mjs --scripted .scratch/store-backed-sidebar/evidence/script.json --ui-port 5219
// (a temp home, a scripted provider, no api-key).
//
// WHAT IT IS HERE FOR. Three of this feature's promises are LAYOUT, and no suite in this
// repo can see layout: the rows are rendered to strings by `ui/test/suites/sidebar.tsx`,
// which can say that a slot element exists and that no size is drawn -- not that the
// title's x really is the project name's x, nor that a spinner appearing inside the
// indent leaves that x where it was. And the third promise is a SEQUENCE: "点击新增不立刻
// 会话，发送才新建" is about what is NOT in the list between two clicks, which needs a
// browser, a real backend and a real store.
//
// EVERY CELL IS KEYED BY A SESSION ID, never by counting rows: this file is meant to be run
// twice against the same dev pair (the second run's home already holds the first run's
// sessions), and a walkthrough that only passes on an empty store is a walkthrough nobody
// will re-run. The ids are read off each row's TOOLTIP, which is where the row keeps them --
// and the two blocks are told apart by SCOPING rather than by assuming an order.
//
// WHAT IT ASSERTS, in order:
//
//   1. the listing is store-backed to the eye: no row carries a size, and the refresh
//      button says where the list comes from;
//   2. "New task" writes NOTHING -- no row appears, and the STORE (asked directly) has no
//      new session either;
//   3. the first message creates the session: the row turns up (with no refresh clicked)
//      carrying the message as its title, a relative age as its time, and the store holds
//      that same row -- as a task, with the message as its stored title;
//   4. the indent is the spinner's box: the title sits at the same x as a project's name,
//      and a run's spinner appears INSIDE the strip the row is indented by, leaving that x
//      untouched;
//   5. a session minted inside a project follows the same rule -- nothing until the send,
//      then a row under THAT project, with the store's `project_id` naming it;
//   6. the refresh button still finds it, time and all.
//
// Screenshots land in .scratch/store-backed-sidebar/evidence/.
import fs from "node:fs";
import os from "node:os";
import path from "node:path";

import { chromium } from "/Users/zhouteng/.nvm/versions/node/v22.21.1/lib/node_modules/playwright/index.mjs";

const url = process.argv[2] ?? "http://localhost:5219/";

const evidence = path.resolve(".scratch/store-backed-sidebar/evidence");
fs.mkdirSync(evidence, { recursive: true });

let failures = 0;
function check(label, ok, detail = "") {
  console.log(`${ok ? "ok  " : "RED "} ${label}${detail === "" ? "" : ` -- ${detail}`}`);
  if (!ok) failures += 1;
}

/// A first message, distinctive enough that finding it by text cannot be a coincidence,
/// and inside the title limit (`TITLE_MAX` is 60 code points).
const FIRST = "侧栏只从库里读，一行就够";
const SECOND = "这条是第二次发送";
const IN_PROJECT = "这个项目里的第一句";

/// THE PROJECT THIS WALKTHROUGH ADDS, made by this process and handed to the server as a
/// path -- the same temporary-directory discipline the suites keep. It is created under the
/// OS temp dir rather than inside the repo, so a run that dies leaves nothing behind in the
/// tree.
const projectDir = fs.mkdtempSync(path.join(os.tmpdir(), "store-backed-sidebar-"));

const browser = await chromium.launch();
// THE LANGUAGE IS THE OWNER'S, so the words this file looks for are the Chinese ones -- and
// it is set rather than hoped for: the page's chain is remembered -> browser -> English, and
// a fresh context speaks whatever the browser does.
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

/// Every row of one block, as this walkthrough reads it: what it SAYS, the id its tooltip
/// carries, its relative time, and whether it is the current session.
const rowsIn = (scope) =>
  page.$$eval(`${scope} [data-slot="thread-list-item"]`, (items) =>
    items.map((li) => {
      const trigger = li.querySelector('[data-slot="thread-list-item-trigger"]');
      return {
        says: li.querySelector('[data-slot="thread-list-item-title"]')?.textContent?.trim() ?? null,
        id: trigger?.getAttribute("title") ?? null,
        time: li.querySelector('[data-slot="thread-list-item-time"]')?.textContent?.trim() ?? null,
        current: li.hasAttribute("data-current"),
        // THE ONE THING THAT MUST NOT BE THERE ANY MORE (the owner's "去除文件大小"): a size,
        // or the second line that used to hold it.
        size: li.querySelector('[data-slot="thread-list-item-meta"]')?.textContent?.trim() ?? null,
        // A REFUSAL THE PAGE LANDED ON THIS ROW -- today, the bind at first send. It is
        // read on every row so that a red sentence under a row is a FAILED CELL rather than
        // something only a human looking at the screenshot would notice.
        error: li.querySelector('[data-slot="thread-list-item-error"]')?.textContent?.trim() ?? null,
        markup: li.innerHTML,
      };
    }),
  );

/// THE ROW'S ID IS THE FIRST LINE OF ITS TOOLTIP (the second is the exact instant). It is
/// read rather than guessed because everything below is keyed by it -- a store-backed list
/// can hold two sessions with the same words in them, and nothing here can tell them apart
/// except this.
const idOf = (row) => row.id?.split("\n")[0] ?? null;

const shot = async (name) => {
  await page.screenshot({ path: path.join(evidence, `${name}.png`) });
  console.log(`     shot ${name}.png`);
};

/// THE STORE'S OWN ANSWER, read out of the page through the same-origin proxy. Several
/// cells below are about what the STORE holds rather than what a row draws -- "库里也没有新
/// 行" is not visible in a list that happens to be stale -- so the listing is asked for
/// directly as well as rendered.
const storeListing = () =>
  page.evaluate(async () => {
    const res = await fetch("/api/projects");
    return res.ok ? await res.json() : { error: res.status };
  });

const storeSessions = (listing) => [
  ...listing.projects.flatMap((p) => p.sessions.map((s) => ({ ...s, projectId: p.projectId }))),
  ...listing.tasks.map((s) => ({ ...s, projectId: null })),
];

const storeIds = async () => new Set(storeSessions(await storeListing()).map((s) => s.threadId));

const clickRefresh = async () => {
  await page.click('[data-slot="sidebar-refresh"]');
  // The only waiter this file can give the refresh button: it has no "done" event, and the
  // read is a local SELECT now, so a short settle is enough. The cells that matter re-read
  // through `waitForFunction` instead.
  await page.waitForTimeout(150);
};

/// SEND: type into the composer and press Enter.
const send = async (text) => {
  await page.fill("textarea", text);
  await page.press("textarea", "Enter");
};

/// WAIT FOR A ROW, BY ID, IN ONE BLOCK. The sidebar's own refetch (once per minted id) is
/// what puts a brand-new session's row there; no refresh is clicked anywhere near this, on
/// purpose.
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

/// THE SESSION THE PAGE IS ON, read from the page's OWN MEMORY (`clj-harness.session`,
/// written by `app.tsx` on every change of what is shown) rather than from the list.
///
/// IT CANNOT BE THE CURRENT ROW ANY MORE, and that is the feature: a session minted by "New
/// task" has no row -- the store does not know it and will not until somebody sends -- so the
/// `data-current` row this used to read is exactly the one that is absent. The memory is the
/// page's own answer to "which session am I in", which is what the cells below need.
const shownId = () => page.evaluate(() => localStorage.getItem("clj-harness.session"));

/// ...AND IT CHANGES WHEN A NEW SESSION IS MINTED, which is how the two "new session" cells
/// learn the id they are about. Waited for rather than read: the write is an effect.
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

/// THE ROW'S GEOMETRY, read in one evaluate so the run cannot end between two readings.
/// `null` when the spinner is not in the DOM -- which is how the idle reading and the
/// in-flight one are told apart.
const rowGeometry = (id, wantSpinner) =>
  page.evaluate(
    ({ id, wantSpinner }) => {
      const li = [...document.querySelectorAll('[data-slot="thread-list-item"]')].find((el) =>
        el
          .querySelector('[data-slot="thread-list-item-trigger"]')
          ?.getAttribute("title")
          ?.startsWith(id),
      );
      if (li === undefined) return null;
      const spinner = li.querySelector('[data-slot="thread-list-item-running"]');
      if (wantSpinner && spinner === null) return null;
      const box = (el) => {
        const r = el.getBoundingClientRect();
        return { x: r.x, w: r.width, h: r.height, y: r.y };
      };
      return {
        slot: box(li.querySelector('[data-slot="thread-list-item-slot"]')),
        title: box(li.querySelector('[data-slot="thread-list-item-title"]')),
        spinner: spinner === null ? null : box(spinner),
      };
    },
    { id, wantSpinner },
  );

// ------------------------------------------------- 1. nothing on a row is a disk fact

await clickRefresh();
const tasksAtStart = await rowsIn(TASKS);
check(
  "no row carries a size (there is no second line left to carry one)",
  tasksAtStart.every((r) => r.size === null && !/ \d+ (B|KB|MB)/.test(r.markup)),
  JSON.stringify(tasksAtStart.map((r) => r.size)),
);
const refreshTitle = await page.$eval('[data-slot="sidebar-refresh"]', (el) => el.getAttribute("title"));
check(
  "the refresh button says the list comes from the store",
  refreshTitle.includes("库") && !refreshTitle.includes("磁盘"),
  refreshTitle,
);
await shot("01-store-backed-listing");

// ------------------------------------------------------ 2. a new task writes nothing

const tasksBefore = (await rowsIn(TASKS)).map(idOf);
const storeBefore = await storeIds();
const shownBefore = await shownId();
await page.click('[data-slot="sidebar-new-task"]');
const freshId = await waitForNewShownId(shownBefore);
await page.waitForTimeout(300);
const tasksAfterNewTask = (await rowsIn(TASKS)).map(idOf);
check(
  "'New task' adds no row -- the session does not exist yet",
  JSON.stringify(tasksAfterNewTask) === JSON.stringify(tasksBefore),
  `${JSON.stringify(tasksBefore)} -> ${JSON.stringify(tasksAfterNewTask)}`,
);
// AND THE STORE AGREES, which is the acceptance this cell actually has to keep ("库里也没有
// 新行"): a sidebar that merely had not refreshed yet would pass the cell above.
const storeAfterNewTask = await storeIds();
check(
  "...and the store has no new session for it either",
  storeAfterNewTask.size === storeBefore.size &&
    [...storeAfterNewTask].every((id) => storeBefore.has(id)),
  `${storeBefore.size} -> ${storeAfterNewTask.size}`,
);
// THE PAGE REALLY IS ON A MINTED SESSION, though -- otherwise both cells above would also
// pass for a button that did nothing at all.
check("...but the page is on a session that has an id", /^[0-9a-f-]{36}$/.test(freshId), freshId);
check("...which the store does not know yet", !storeAfterNewTask.has(freshId));
await shot("02-new-task-writes-nothing");

// ------------------------------------------- 3. the first send creates the row, at the top

await send(FIRST);
await waitForRow(TASKS, freshId);
const created = await rowsIn(TASKS);
const mine = created.find((r) => idOf(r) === freshId);
check(
  "the first message makes the session exist, with no refresh clicked",
  mine !== undefined,
  JSON.stringify(created.length),
);
check(
  "...and the row is the TOP one (the listing sorts by send time)",
  idOf(created[0]) === freshId,
  JSON.stringify(created.map((r) => [idOf(r), r.says])),
);
check("...and its time is a relative age rather than a timestamp", mine?.time === "刚刚", String(mine?.time));
check("...and the row carries no size", mine?.size === null);
check("...and the tooltip is the id plus the exact instant", (mine?.id ?? "").includes("\n"), JSON.stringify(mine?.id));
check("...and nothing refused it (no sentence under the row)", mine?.error === null, String(mine?.error));
// THE STORE'S COPY OF THE SAME ROW: the title it kept, the send time it stamped, and the
// fact that it is a TASK (no project) -- none of which the render can distinguish from a
// page-local guess.
const firstStoreRow = storeSessions(await storeListing()).find((s) => s.threadId === freshId);
check("...and the store holds that row itself", firstStoreRow !== undefined, JSON.stringify(firstStoreRow));
check("...with the message as its stored title", firstStoreRow?.firstUserText === FIRST, String(firstStoreRow?.firstUserText));
check("...and a send time of its own", typeof firstStoreRow?.lastSentAt === "number", String(firstStoreRow?.lastSentAt));
check("...as a task, since no project was pending", firstStoreRow?.projectId === null, String(firstStoreRow?.projectId));
await shot("03-the-first-send-creates-the-row");

// -------------------------------------- 4. the indent is the spinner's box, and it holds

const idleGeometry = await rowGeometry(freshId, false);
check(
  "the gap between the slot and the title is the row's `gap-1.5` (6px)",
  Math.abs(idleGeometry.title.x - (idleGeometry.slot.x + idleGeometry.slot.w) - 6) < 1,
  JSON.stringify(idleGeometry),
);
check(
  "the slot is exactly the spinner's size (14px = `size-3.5`)",
  Math.abs(idleGeometry.slot.w - 14) < 1 && Math.abs(idleGeometry.slot.h - 14) < 1,
  JSON.stringify(idleGeometry.slot),
);

// AND NOW WITH A RUN IN FLIGHT. The scripted provider is fast, so the spinner is WAITED FOR
// rather than slept on: `rowGeometry(id, true)` is polled until the icon is really in the
// DOM, and the geometry and the icon are read together.
await send(SECOND);
let busyGeometry = null;
try {
  await page.waitForFunction(
    (id) =>
      [...document.querySelectorAll('[data-slot="thread-list-item"]')].some(
        (el) =>
          el
            .querySelector('[data-slot="thread-list-item-trigger"]')
            ?.getAttribute("title")
            ?.startsWith(id) && el.querySelector('[data-slot="thread-list-item-running"]') !== null,
      ),
    freshId,
    { timeout: 10000 },
  );
  busyGeometry = await rowGeometry(freshId, true);
} catch {
  console.log("     (the scripted run finished before the spinner could be read)");
}
if (busyGeometry !== null) {
  check(
    "a run puts the spinner INSIDE the indent's strip",
    busyGeometry.spinner.x >= busyGeometry.slot.x - 1 &&
      busyGeometry.spinner.x + busyGeometry.spinner.w <= busyGeometry.slot.x + busyGeometry.slot.w + 1,
    JSON.stringify({ slot: busyGeometry.slot, spinner: busyGeometry.spinner }),
  );
  check(
    "...and the title does not move by a pixel when it appears",
    Math.abs(busyGeometry.title.x - idleGeometry.title.x) < 1,
    `${idleGeometry.title.x} -> ${busyGeometry.title.x}`,
  );
  await shot("04-the-spinner-fits-the-indent");
}
await page.waitForFunction(() => document.querySelector('[data-slot="thread-list-item-running"]') === null, undefined, { timeout: 60000 });

// --------------------------------- 5. a project's session: nothing until the send, then HERE

// THE PROJECT ROW IS MADE THROUGH THE REAL EDGE, by the page itself: the folder PICKER is a
// native dialog and this run has no human to answer it, so the walkthrough does what the
// sidebar's typed-path escape hatch would do -- one POST through the same-origin proxy, no
// second server and no test-only route.
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
await clickRefresh();
await page.waitForSelector(`[data-slot="sidebar-project"][data-path="${projectPath}"]`, { timeout: 15000 });
const projectName = await page.$eval(
  `[data-slot="sidebar-project"][data-path="${projectPath}"] [data-slot="sidebar-project-name"]`,
  (el) => el.textContent.trim(),
);
check("the project row is listed", projectName.length > 0, projectName);
const inProjectScope = SESSIONS_IN(projectPath);

const projectSessionsBefore = (await rowsIn(inProjectScope)).map(idOf);
const shownBeforeProject = await shownId();
await page.click(
  `[data-slot="sidebar-project"][data-path="${projectPath}"] [data-slot="sidebar-project-new-session"]`,
);
const inProjectId = await waitForNewShownId(shownBeforeProject);
await page.waitForTimeout(300);
const projectSessionsAfter = (await rowsIn(inProjectScope)).map(idOf);
check(
  "'New session' in a project adds no row either -- same rule, one block down",
  JSON.stringify(projectSessionsAfter) === JSON.stringify(projectSessionsBefore),
  `${JSON.stringify(projectSessionsBefore)} -> ${JSON.stringify(projectSessionsAfter)}`,
);
const storeBeforeProjectSend = await storeIds();
check("...and the store has no new session for it either", !storeBeforeProjectSend.has(inProjectId), inProjectId);
await shot("05-project-new-session-writes-nothing");

await send(IN_PROJECT);
await waitForRow(inProjectScope, inProjectId);
const projectRows = await rowsIn(inProjectScope);
const inProject = projectRows.find((r) => idOf(r) === inProjectId);
check(
  "the first send in a project creates its row UNDER THAT PROJECT",
  inProject !== undefined,
  JSON.stringify(projectRows.map((r) => [idOf(r), r.says])),
);
check("...and the words are the message that was sent", inProject?.says === IN_PROJECT, String(inProject?.says));
check("...and NOT in the task block", (await rowsIn(TASKS)).every((r) => idOf(r) !== inProjectId));
check("...with a relative time of its own", inProject?.time === "刚刚", String(inProject?.time));
// THE BIND ITSELF DID NOT FAIL, which is a different claim from "the store has the row": the
// session's log has to be carried from the reserved workspace into the project's, and a
// refusal there lands as a sentence under the row (see `move-log!` in `edge/http.clj`).
check("...and the bind was not refused (no sentence under the row)", inProject?.error === null, String(inProject?.error));
// AND THE STORE PUT IT IN THAT PROJECT -- the acceptance's "库里那行 project_id 正确", which is
// the half of this feature the BIND at send time is responsible for: the row was created by
// the run and moved into the project by one POST (see `app.tsx`, `reportTitle`).
const boundRow = storeSessions(await storeListing()).find((s) => s.threadId === inProjectId);
check("...and the store's row names that project", boundRow?.projectId === added.body.projectId, JSON.stringify(boundRow));
check("...with the message as its stored title", boundRow?.firstUserText === IN_PROJECT, String(boundRow?.firstUserText));

// THE ALIGNMENT, which is the owner's "项目和会话要有明显的缩进": a session row starts where
// the project's NAME starts, not where its folder icon does.
const projectGeometry = await page.evaluate(
  ({ projectPath, id }) => {
    const project = document.querySelector(`[data-slot="sidebar-project"][data-path="${projectPath}"]`);
    const name = project.querySelector('[data-slot="sidebar-project-name"]').getBoundingClientRect();
    const icon = project.querySelector('[data-slot="sidebar-project-icon"]').getBoundingClientRect();
    const li = [
      ...project.querySelectorAll('[data-slot="sidebar-sessions"] [data-slot="thread-list-item"]'),
    ].find((el) =>
      el
        .querySelector('[data-slot="thread-list-item-trigger"]')
        ?.getAttribute("title")
        ?.startsWith(id),
    );
    const title = li.querySelector('[data-slot="thread-list-item-title"]').getBoundingClientRect();
    return { nameX: name.x, titleX: title.x, iconX: icon.x, iconW: icon.width };
  },
  { projectPath, id: inProjectId },
);
check(
  "the session row is indented to the project's NAME (not to its folder icon)",
  Math.abs(projectGeometry.titleX - projectGeometry.nameX) < 1.5,
  JSON.stringify(projectGeometry),
);
check(
  "...which is the icon plus the row's own gap -- a real indent, not a nudge",
  projectGeometry.nameX - projectGeometry.iconX >= projectGeometry.iconW,
  JSON.stringify(projectGeometry),
);
await shot("06-a-project-session-is-indented-and-bound");

// ------------------------------------------------------ 6. the refresh still finds them

await clickRefresh();
const afterRefresh = await rowsIn(inProjectScope);
const stillThere = afterRefresh.find((r) => idOf(r) === inProjectId);
check(
  "after a refresh the project's session is still listed",
  stillThere !== undefined,
  JSON.stringify(afterRefresh.map((r) => [idOf(r), r.says])),
);
check("...still with a relative age", stillThere?.time === "刚刚", String(stillThere?.time));
const tasksAfter = await rowsIn(TASKS);
check(
  "...and the task made earlier is still in the task block",
  tasksAfter.some((r) => idOf(r) === freshId),
  JSON.stringify(tasksAfter.map((r) => [idOf(r), r.says])),
);
await shot("07-refresh-keeps-both");

console.log(failures === 0 ? "\nall cells ok" : `\n${failures} cell(s) RED`);
await browser.close();
fs.rmSync(projectDir, { recursive: true, force: true });
process.exit(failures === 0 ? 0 : 1);
