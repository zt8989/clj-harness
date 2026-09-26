// A real-browser walkthrough of what a sidebar ROW is called -- the one thing the two
// suites around this feature cannot see.
//
//   node .scratch/session-titles-in-the-store/walkthrough.mjs [ui-url]
//
// Run it against `node scripts/dev.mjs --scripted .scratch/session-titles-in-the-store/evidence/script.json --ui-port 5217`
// (a temp home, a scripted provider, no api-key).
//
// WHAT IT IS HERE FOR. The backend suite drives the store through HTTP and can say that
// `sessions.title` is written once; the UI suite renders one row from a literal
// `SessionSummary` and can say which element says the title. Neither can say that a
// person TYPING A MESSAGE sees the row change, nor that the words survive a reload --
// and those two are the whole feature: the store's copy is what a listing has, and the
// live copy is what the row you just typed into has.
//
// WHAT IT ASSERTS, in order: a brand-new task is drawn as its ID (nothing has been said
// in it); the first message makes the row say the message -- BEFORE any refresh, so it
// is the live copy rather than the listing; the id is still on the row as its hover
// tooltip; a SECOND message does not rename it; and after a RELOAD the row still says
// the first one, which is the store's copy rather than anything this page remembers.
//
// Screenshots land in .scratch/session-titles-in-the-store/evidence/.
import fs from "node:fs";
import path from "node:path";

import { chromium } from "/Users/zhouteng/.nvm/versions/node/v22.21.1/lib/node_modules/playwright/index.mjs";

const url = process.argv[2] ?? "http://localhost:5217/";

const evidence = path.resolve(".scratch/session-titles-in-the-store/evidence");
fs.mkdirSync(evidence, { recursive: true });

let failures = 0;
function check(label, ok, detail = "") {
  console.log(`${ok ? "ok  " : "RED "} ${label}${detail === "" ? "" : ` -- ${detail}`}`);
  if (!ok) failures += 1;
}

/// A first message under the title limit (`TITLE_MAX` is 60 code points) and distinctive
/// enough that finding it by text cannot be a coincidence.
const FIRST = "把侧边栏的标题改成会话标题";
const SECOND = "那顶栏的标题呢";

const browser = await chromium.launch();
const page = await browser.newPage({ viewport: { width: 1100, height: 800 } });
page.on("pageerror", (e) => check("no page error", false, e.message));

await page.goto(url, { waitUntil: "load" });
await page.waitForSelector(
  '[data-slot="sidebar-tasks"], [data-slot="sidebar-project"], [data-slot="sidebar-empty"]',
  { timeout: 15000 },
);

/// Every row in one block, as this walkthrough reads it: WHAT IT SAYS (the first line),
/// the id its tooltip carries, the second line, and whether it is current.
const rowsIn = (slot) =>
  page.$$eval(`[data-slot="${slot}"] [data-slot="thread-list-item"]`, (items) =>
    items.map((li) => ({
      says: li.querySelector('[data-slot="thread-list-item-title"]')?.textContent?.trim() ?? null,
      id: li.querySelector('[data-slot="thread-list-item-trigger"]')?.getAttribute("title") ?? null,
      meta: li.querySelector('[data-slot="thread-list-item-meta"]')?.textContent?.trim() ?? null,
      current: li.hasAttribute("data-current"),
    })),
  );

/// THE ROW, FOUND BY ITS ID -- and it has to be found that way now: the first line is a
/// title a person typed, which two sessions can share, while the tooltip is the thing
/// that identifies one. (That is the same reason the row keeps it.)
const rowById = async (id) => (await rowsIn("sidebar-tasks")).find((r) => r.id === id);

const shot = async (name) => {
  await page.screenshot({ path: path.join(evidence, `${name}.png`) });
  console.log(`     shot ${name}.png`);
};

// ---------------------------------------------------------------- 1. a new task has no name

await page.click('[data-slot="sidebar-new-task"]');
await page.waitForSelector('[data-slot="sidebar-tasks"]', { timeout: 15000 });
let listed = await rowsIn("sidebar-tasks");
const made = listed.find((r) => r.current);
check("'New task' makes a row that is on screen", made !== undefined, JSON.stringify(listed));
const id = made?.id;
if (id === undefined || id === null) {
  console.log("RED no id on the new row -- nothing below can be measured");
  await browser.close();
  process.exit(1);
}
// THE FALLBACK, as the owner asked for it: nothing has been said in this conversation, so
// the row is drawn as its id. (The bar says `New session` for the same state -- see the
// two doc comments in `lib/session-title.ts`; this file pins the row's half.)
check("a task nothing has been said in says its ID", made.says === id, JSON.stringify(made.says));
check("...and nothing else is on that row pretending to be a name", made.meta === "never run · no log yet", made.meta);
check("...and its tooltip is the same id", made.id === id);
await shot("01-new-task-drawn-as-its-id");

// ------------------------------------------------------- 2. the first message names it, live

await page.fill("textarea", FIRST);
await page.press("textarea", "Enter");

// NO REFRESH IS CLICKED BETWEEN THE SEND AND THIS READING, and that is the point of the
// step: the listing still holds the unnamed row it was given, so the words can only come
// from the page's own copy of this session's runtime (`liveTitles` in `app.tsx`).
await page.waitForFunction(
  (wanted) =>
    [...document.querySelectorAll('[data-slot="sidebar-tasks"] [data-slot="thread-list-item"]')].some(
      (li) => li.querySelector('[data-slot="thread-list-item-title"]')?.textContent?.trim() === wanted,
    ),
  FIRST,
  { timeout: 15000 },
);
const named = await rowById(id);
check("the first message makes the row say it, with no refresh in between", named?.says === FIRST, JSON.stringify(named?.says));
check("...and the id is still on the row, as its tooltip", named?.id === id);
check("...and the row is still the same session", named?.says !== id);
await shot("02-the-first-message-names-the-row");

// ------------------------------------------------ 3. a second message does not rename it

await page.fill("textarea", SECOND);
await page.press("textarea", "Enter");
// Wait for the second run to be under way before reading: the row would say the first
// message either way, so the honest waiter is the run itself (the row's spinner).
await page.waitForSelector(`[data-slot="thread-list-item-running"]`, { timeout: 15000 });
await page.waitForFunction(
  () => document.querySelector('[data-slot="thread-list-item-running"]') === null,
  undefined,
  { timeout: 90000 },
);
const twice = await rowById(id);
check("a later message does not rename the row", twice?.says === FIRST, JSON.stringify(twice?.says));
await shot("03-a-later-message-does-not-rename-it");

// ------------------------------------------------------------- 4. the words survive a reload

// THE STORE'S COPY, and the only step that proves it is the store's: a reload throws away
// every runtime the page was holding, so the sidebar's listing is the only thing left
// that could know this conversation's name.
await page.reload({ waitUntil: "load" });
await page.waitForSelector('[data-slot="sidebar-tasks"]', { timeout: 15000 });
const reloaded = await rowById(id);
check("after a reload the row still says the first message", reloaded?.says === FIRST, JSON.stringify(reloaded?.says));
check("...which is the store's copy rather than this page's memory", reloaded?.id === id);
await shot("04-the-store-keeps-the-name-across-a-reload");

console.log(failures === 0 ? "\nall cells ok" : `\n${failures} cell(s) RED`);
await browser.close();
process.exit(failures === 0 ? 0 : 1);
