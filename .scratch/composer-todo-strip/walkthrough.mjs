// A real-browser walkthrough of THE TASK STRIP ABOVE THE COMPOSER (`.scratch/composer-todo-strip`,
// tickets 02 and 03).
//
//   node .scratch/composer-todo-strip/walkthrough.mjs [ui-url]
//
// Run it against
//   node scripts/dev.mjs --scripted .scratch/composer-todo-strip/script.json --ui-port 5322
// (a temp home, a scripted provider whose turns write, update and finally clear a task list).
//
// WHAT IT ASSERTS, and none of it is visible to a suite -- there is no DOM in the vitest run:
//
//   1. THE STRIP APPEARS BY ITSELF after the model writes a list, and the folded line counts it.
//   2. THE DETAIL IS ONE ICON AND ONE LINE PER ITEM, three shapes, the in-progress one spinning,
//      and no status WORD drawn on screen.
//   3. IT FOLDS AND UNFOLDS, and a refresh comes back folded -- the list is the server's row.
//   4. A SECOND WRITE MOVES THE LINE WITHOUT A RELOAD (`model/start` / `turn/end` re-ask).
//   5. NOTHING ASKS ON A CLOCK: the `/todos` request count does not grow while nothing runs.
//   6. AN EMPTY LIST TAKES THE STRIP AWAY.
// Screenshots land in .scratch/composer-todo-strip/evidence/.
//
// NOT RUN ON THIS MACHINE: `loadChromium` needs Playwright, which this WSL has neither locally
// nor globally -- the run that produced the evidence beside this file drove the same steps from
// the MCP browser on the Windows side (see the spec's validation section). The script is kept
// for whoever has a Playwright install.
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
const evidence = path.resolve(".scratch/composer-todo-strip/evidence");
fs.mkdirSync(evidence, { recursive: true });

let failures = 0;
function check(label, ok, detail = "") {
  console.log(`${ok ? "ok  " : "RED "} ${label}${detail === "" ? "" : ` -- ${detail}`}`);
  if (!ok) failures += 1;
}

/// HOW MANY TIMES THE PAGE HAS ASKED THE STRIP'S ROUTE, from the network layer rather than from
/// the app's own bookkeeping -- a timer hidden in a module would still be a request.
const todosReads = (page) =>
  page.evaluate(
    () =>
      performance.getEntriesByType("resource").filter((e) => /\/api\/threads\/[^/]+\/todos/.test(e.name))
        .length,
  );

const summary = (page) =>
  page.evaluate(() => {
    const el = document.querySelector('[data-slot="composer-todos-summary"]');
    return el ? el.textContent.replace(/\s+/g, " ").trim() : null;
  });

const rows = (page) =>
  page.evaluate(() =>
    [...document.querySelectorAll('[data-slot="composer-todos-item"]')].map((el) => ({
      status: el.getAttribute("data-status"),
      text: el.textContent.replace(/\s+/g, " ").trim(),
      sr: el.querySelector(".sr-only")?.textContent?.trim() ?? null,
    })),
  );

const firstMessage = "composer 任务横条走查：先写一份清单";
const secondMessage = "把「写横条」那条标成完成";
const thirdMessage = "算了，清空吧";

const browser = await chromium.launch();
const page = await browser.newPage({ viewport: { width: 1500, height: 900 }, locale: "zh-CN" });
page.on("pageerror", (e) => check("no page error", false, e.message));

await page.goto(url, { waitUntil: "load" });
await page.waitForSelector('[data-slot="sidebar-tasks"], [data-slot="sidebar-empty"]', { timeout: 15000 });

// A session whose first run writes a list: `script.json`'s first turn is a `todo_write`.
await page.getByRole("button", { name: "新建任务" }).click();
const box = page.getByRole("textbox", { name: "消息输入框" });
await box.fill(firstMessage);
await box.press("Enter");

// 1. THE STRIP, WITH NOBODY ASKING FOR IT.
await page.waitForSelector('[data-slot="composer-todos"]', { timeout: 20000 });
check("the strip appears without a reload", true);
check(
  "the folded line counts the list",
  (await summary(page)) === "2 已完成 · 1 进行中 · 2 待处理",
  String(await summary(page)),
);
await page.screenshot({ path: path.join(evidence, "strip-folded.png") });

// 2. THE DETAIL: one icon, one line, three shapes.
await page.click('[data-slot="composer-todos-toggle"]');
await page.waitForSelector('[data-slot="composer-todos-list"]', { state: "visible", timeout: 5000 });
await page.waitForSelector('[data-slot="composer-todos-item"]', { state: "visible", timeout: 5000 });
const items = await rows(page);
check("the detail has one row per item", items.length === 5, `rows=${items.length}`);
check(
  "and each row is the model's own words",
  items.some((r) => r.text.includes("读一遍 ComposerFrame")) &&
    items.some((r) => r.text.includes("写 composer 之上那条横条")) &&
    items.some((r) => r.text.includes("开浏览器走一遍")),
  JSON.stringify(items.map((r) => r.text)),
);
check(
  "every row carries its status where a reader can find it",
  items.map((r) => r.status).join(",") === "completed,completed,in_progress,pending,pending",
  items.map((r) => r.status).join(","),
);
check(
  "and every row hands a status word to the screen reader",
  items.every((r) => r.sr !== null && r.sr.length > 0),
  JSON.stringify(items.map((r) => r.sr)),
);
const spin = await page.evaluate(() => {
  const svg = document.querySelector('[data-slot="composer-todos-item"][data-status="in_progress"] svg');
  return svg ? svg.getAttribute("class") : null;
});
check(
  "the in-progress row spins, with the reduced-motion escape hatch",
  spin !== null && spin.includes("animate-spin") && spin.includes("motion-reduce:animate-none"),
  String(spin),
);
await page.screenshot({ path: path.join(evidence, "strip-open.png") });

// 3. FOLD IT AGAIN, then refresh: the server's row, folded.
await page.click('[data-slot="composer-todos-toggle"]');
await page.waitForSelector('[data-slot="composer-todos-list"]', { state: "hidden", timeout: 5000 });
check("clicking again folds it back", true);

await page.reload({ waitUntil: "load" });
await page.waitForSelector('[data-slot="composer-todos-summary"]', { timeout: 15000 });
check(
  "a refresh draws the strip from the store",
  (await summary(page)) === "2 已完成 · 1 进行中 · 2 待处理",
  String(await summary(page)),
);
check(
  "and it comes back folded",
  await page.evaluate(() => {
    const list = document.querySelector('[data-slot="composer-todos-list"]');
    return list === null || list.hidden;
  }),
);

// 4. A SECOND WRITE MOVES THE LINE, with no reload and no direct read by anybody.
const before = await todosReads(page);
await box.fill(secondMessage);
await box.press("Enter");
await page.waitForFunction(
  () => document.querySelector('[data-slot="composer-todos-summary"]')?.textContent?.includes("3 已完成"),
  null,
  { timeout: 20000 },
);
check(
  "the line moves when the model writes again",
  (await summary(page)) === "3 已完成 · 1 进行中 · 1 待处理",
  String(await summary(page)),
);
await page.screenshot({ path: path.join(evidence, "strip-updated.png") });

// 5. NOBODY ASKS ON A CLOCK: a run is over, and the count does not grow.
const quiet = await todosReads(page);
await page.waitForTimeout(3000);
check("nothing asks the route on a clock", (await todosReads(page)) === quiet, `${quiet} -> ${await todosReads(page)}`);
check(
  "and the run asked it a handful of times, not once a second",
  quiet - before <= 4,
  `${before} -> ${quiet}`,
);

// 6. AN EMPTY LIST TAKES THE STRIP AWAY.
await box.fill(thirdMessage);
await box.press("Enter");
await page.waitForSelector('[data-slot="composer-todos"]', { state: "detached", timeout: 20000 });
check("an empty list draws nothing at all", true);
await page.screenshot({ path: path.join(evidence, "strip-cleared.png") });

await browser.close();
console.log(failures === 0 ? "\nALL GREEN" : `\n${failures} RED`);
process.exit(failures === 0 ? 0 : 1);
