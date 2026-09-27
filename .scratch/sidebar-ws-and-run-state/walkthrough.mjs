// A real-browser walkthrough of ticket 02 of this feature: the sidebar's listing is
// read over HTTP ONCE (mount), and every later change -- a run starting or ending on
// this page or another, a new session's row, an archive -- arrives as a PUSH over
// `events.host`. The refresh button stays as the fallback for a socket that cannot
// connect, not as a normal path.
//
//   node .scratch/sidebar-ws-and-run-state/walkthrough.mjs [ui-url]
//
// Run it against
//   node scripts/dev.mjs --scripted --ui-port 5242
// (a temp home, a scripted provider, no api-key).
//
// WHAT IT ASSERTS:
//
//   1. TWO WINDOWS: page B's sidebar updates when page A sends -- with ZERO
//      `GET /api/projects` requests on page B after its mount. That is the push
//      working, and it is the half no unit suite can see.
//   2. SWITCHING SESSIONS does not fetch the listing either: the facts a switch used
//      to re-read (size, mtime) no longer exist, and the facts it still needs ride
//      the push.
//   3. THE REFRESH BUTTON still works (one fetch, the fallback's contract).
//
// Screenshots land in .scratch/sidebar-ws-and-run-state/evidence/.
import fs from "node:fs";
import path from "node:path";

// Playwright comes from whatever install this machine has: the global npm root if
// one is there, else PLAYWRIGHT_PATH names the module directly. (This walkthrough is
// a local evidence tool, not a dependency of the tree.)
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

const url = process.argv[2] ?? "http://localhost:5242/";

const evidence = path.resolve(".scratch/sidebar-ws-and-run-state/evidence");
fs.mkdirSync(evidence, { recursive: true });

let failures = 0;
function check(label, ok, detail = "") {
  console.log(`${ok ? "ok  " : "RED "} ${label}${detail === "" ? "" : ` -- ${detail}`}`);
  if (!ok) failures += 1;
}

/// The count of `GET /api/projects` a page makes FROM NOW ON. Installed before the
/// first navigation so the mount read itself is counted, which is what makes "zero
/// AFTER the mount" a computable claim.
/// The listing reads a page has made, WITH their times -- read off performance's own
/// entries rather than a fetch wrapper (a wrapper is lost on every navigation, and
/// this file reloads nothing but it does not have to be lucky either).
const projectGets = (page) =>
  page.evaluate(() =>
    performance.getEntriesByType("resource")
      .filter((e) => e.name.includes("/api/projects"))
      .map((e) => Math.round(e.startTime)),
  );

const TASK = "推送验证:这一行应该自己出现在另一个窗口";

const browser = await chromium.launch();
const pageA = await browser.newPage({ viewport: { width: 1100, height: 800 }, locale: "zh-CN" });
pageA.on("pageerror", (e) => check("A: no page error", false, e.message));

await pageA.goto(url, { waitUntil: "load" });
await pageA.waitForSelector('[data-slot="sidebar-tasks"], [data-slot="sidebar-empty"]', { timeout: 15000 });
const mountGetsA = (await projectGets(pageA)).length;
check("A: the first read is over HTTP", mountGetsA >= 1, `${mountGetsA} fetch(es) by mount`);

// A mints a task and sends. The row appears through the push (its own page), and the
// ask-again rule may fire a couple of listing reads for the minted row -- that rule is
// ticketed separately and still allowed.
await pageA.getByRole("button", { name: "新建任务" }).click();
const box = pageA.getByRole("textbox", { name: "消息输入框" });
await box.fill(TASK);
await box.press("Enter");
await pageA.waitForTimeout(2500);

// ------------------------------------------------------------------ page B joins
const pageB = await browser.newPage({ viewport: { width: 1100, height: 800 }, locale: "zh-CN" });
pageB.on("pageerror", (e) => check("B: no page error", false, e.message));
await pageB.goto(url, { waitUntil: "load" });
await pageB.waitForSelector('[data-slot="sidebar-tasks"], [data-slot="sidebar-empty"], [data-slot="sidebar-project"]', { timeout: 15000 });
const mountGetsB = (await projectGets(pageB)).length;

// B sees the row A created (its own mount read names it -- fine), then A sends again:
// B's sidebar must move with ZERO further reads.
const sidebarText = () =>
  pageB.evaluate(() => {
    const a = document.querySelectorAll("aside")[0];
    return a ? a.textContent : "";
  });
const before = await sidebarText();
check("B: A's first row is in B's sidebar", before.includes(TASK), before.slice(0, 120));

const SECOND = "推送验证:第二条,窗口 B 不许重新拉取";
const boxA = pageA.getByRole("textbox", { name: "消息输入框" });
await boxA.fill(SECOND);
await boxA.press("Enter");
await pageA.waitForTimeout(2500);

const after = await sidebarText();
const nowGets = (await projectGets(pageB)).length;
check("B: A's second send arrived as a push", after.includes(SECOND), `gets=${nowGets}`);
check("B: no /api/projects fetch after mount", nowGets === mountGetsB,
  `mount=${mountGetsB} now=${nowGets}`);
await pageB.screenshot({ path: path.join(evidence, "b-pushed.png") });

// ------------------------------------------------------------------ switch, not fetch
// B switches to the first task's row: no listing fetch, and the row becomes current.
await pageB.getByRole("button", { name: "打开侧边栏" }).click().catch(() => {});
await pageB.getByRole("button", { name: new RegExp(TASK) }).first().click();
await pageB.waitForTimeout(1200);
check("B: switching sessions re-read nothing", (await projectGets(pageB)).length === mountGetsB,
  `gets=${(await projectGets(pageB)).length}`);
check("B: the switch landed on the row", (await pageB.title()).includes(TASK), await pageB.title());

// ------------------------------------------------------------------ the fallback
// The button still works: exactly one listing read.
const getsBeforeButton = (await projectGets(pageB)).length;
await pageB.locator('[data-slot="sidebar-refresh"]').click();
await pageB.waitForTimeout(800);
const getsAfterButton = (await projectGets(pageB)).length;
check("B: the refresh button still reads once", getsAfterButton === getsBeforeButton + 1,
  `before=${getsBeforeButton} after=${getsAfterButton}`);

await browser.close();
if (failures > 0) {
  console.log(`\n${failures} check(s) RED`);
  process.exit(1);
}
console.log("\nall checks green");
