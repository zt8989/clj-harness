// The diagnostic loop for the blank session-row title in the sidebar (a UUID).
//
//   node .scratch/session-title-blank/check.mjs [url]
//
// Red when any row's title line ([data-slot="thread-list-item-id"], the thread
// id) is empty. The row's own `title` attribute IS the id, so the expected text
// is read straight off the row: the page itself says what should be there, and
// the assertion is exactly the user's symptom -- "the row's title is blank".
//
// Kept as the record of how it was found: red (43/43 rows blank) before the fix,
// green after. The assertion now also lives where a suite can reach it --
// `ui/test/suites/sidebar.tsx` renders the row and reads it -- and this file is what
// that suite CANNOT do: it looks at the real page, in a real sidebar, with a real
// stylesheet. Layout is the difference, and it is the reason AGENTS.md keeps the
// scripted walkthrough as a step of its own (see its 测试 section).
import { chromium } from "/Users/zhouteng/.nvm/versions/node/v22.21.1/lib/node_modules/playwright/index.mjs";

const url = process.argv[2] ?? "http://localhost:5173/";
const browser = await chromium.launch();
const page = await browser.newPage();
await page.goto(url, { waitUntil: "load" });
// ATTACHED, not visible: an empty <code> has no line box at all, so the visible
// state is exactly the bug (the element exists, with no box).
await page.waitForSelector('[data-slot="thread-list-item-id"]', { state: "attached", timeout: 15000 });

const rows = await page.$$eval('[data-slot="thread-list-item"]', (items) =>
  items.map((li) => {
    const trigger = li.querySelector('[data-slot="thread-list-item-trigger"]');
    const id = li.querySelector('[data-slot="thread-list-item-id"]');
    return {
      expected: trigger?.getAttribute("title") ?? null,
      shown: id?.textContent ?? null,
    };
  }),
);
await browser.close();

if (rows.length === 0) {
  console.log("RED (no rows at all -- nothing to look at)");
  process.exit(1);
}
const blank = rows.filter((r) => (r.shown ?? "").trim() === "");
console.log(`${rows.length} rows, ${blank.length} with a blank title`);
for (const r of rows.slice(0, 3)) {
  console.log(`  expected ${r.expected}  shown ${JSON.stringify(r.shown)}`);
}
if (blank.length > 0) {
  console.log("RED: the session row's id line renders nothing");
  process.exit(1);
}
const wrong = rows.filter((r) => (r.shown ?? "").trim() !== (r.expected ?? "").trim());
if (wrong.length > 0) {
  console.log("RED: the id line does not match the row's own title");
  process.exit(1);
}
console.log("GREEN: every row shows its id");
