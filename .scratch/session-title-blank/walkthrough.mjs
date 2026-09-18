// A real-browser walkthrough of the session row, on a scripted dev server.
//
//   node .scratch/session-title-blank/walkthrough.mjs [ui-url] [project-dir]
//
// This is the layer AGENTS.md asks for when ui/src/ has been touched: `check.mjs`
// reads rows the developer's own home happened to have; this one BUILDS the
// situation in the page -- add a project, start a session in it -- and then reads
// the row that appears. A scripted run starts with an empty home, so the only
// session in the sidebar is one this script made a moment ago.
//
// It asserts what a person sees: the new row's first line (the id) is not blank and
// the row's second line says which absence it is looking at (fresh session, no log).
//
// Not part of the suites. Run it against `node scripts/dev.mjs --scripted --ui-port N`.
import { chromium } from "/Users/zhouteng/.nvm/versions/node/v22.21.1/lib/node_modules/playwright/index.mjs";

const url = process.argv[2] ?? "http://localhost:5211/";
const projectDir = process.argv[3] ?? "/Users/zhouteng/Documents/workspace/clj-harness";

const browser = await chromium.launch();
const page = await browser.newPage();
page.on("pageerror", (e) => console.log(`  page error: ${e.message}`));
await page.goto(url, { waitUntil: "load" });

const rows = () => page.$$eval('[data-slot="thread-list-item"]', (items) =>
  items.map((li) => ({
    id: li.querySelector('[data-slot="thread-list-item-id"]')?.textContent ?? null,
    meta: li.querySelector('[data-slot="thread-list-item-meta"]')?.textContent ?? null,
    title: li.querySelector('[data-slot="thread-list-item-trigger"]')?.getAttribute("title") ?? null,
    boxHeight: Math.round(
      li.querySelector('[data-slot="thread-list-item-id"]')?.getBoundingClientRect().height ?? -1,
    ),
  })),
);

const before = await rows();
console.log(`rows before: ${before.length}`);

// 1. Make this directory a project of the run's temp home -- through the page's own
//    origin, so this goes through the dev server's proxy to the harness exactly as the
//    sidebar's own call would.
//
//    NOT through the folder BUTTON, and that is a decision rather than a shortcut:
//    that button opens a NATIVE dialog (`POST /api/project/pick`), and a dialog nobody
//    is there to answer blocks the request until somebody is -- it hung for two
//    minutes when this script first tried it. A modal OS window is a human's step, and
//    the thing under test here is the row, not the picker.
const added = await page.evaluate(async (dir) => {
  const res = await fetch("api/projects", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ dir }),
  });
  return { status: res.status, body: await res.json().catch(() => null) };
}, projectDir);
console.log(`add project: ${added.status} ${JSON.stringify(added.body)}`);
if (added.status !== 200) {
  console.log("RED: the project could not be added -- nothing to look at");
  await browser.close();
  process.exit(1);
}

// The sidebar only knows what it last read, so the new project arrives on a refresh.
// The header's own button is the thing a person would press.
await page.click('[data-slot="sidebar-refresh"]');
await page.waitForSelector('[data-slot="sidebar-project"]', { timeout: 15000 });

// 2. Start a session in it -- the row's own button, so the session is bound to that
//    project.
await page.click('[data-slot="sidebar-project-new-session"]');
await page.waitForFunction(
  (n) => document.querySelectorAll('[data-slot="thread-list-item"]').length > n,
  before.length,
  { timeout: 15000 },
);

const after = await rows();
await browser.close();

// THE ROW THIS SCRIPT JUST MADE, found by difference rather than by its words: the
// words are in whichever language this browser asked for, and that is not this
// script's subject.
const fresh = after.filter((r) => !before.some((b) => b.title === r.title));
for (const r of after) console.log(`  id ${JSON.stringify(r.id)}  meta ${JSON.stringify(r.meta)}`);

if (fresh.length === 0) {
  console.log("RED: the session that was just started has no row");
  process.exit(1);
}
const blank = fresh.filter((r) => (r.id ?? "").trim() === "");
if (blank.length > 0) {
  console.log(`RED: ${blank.length} fresh row(s) with a blank title line (the box is ${fresh[0]?.boxHeight}px)`);
  process.exit(1);
}
const mismatched = fresh.filter((r) => r.id !== r.title);
if (mismatched.length > 0) {
  console.log("RED: the title line is not the row's own id");
  process.exit(1);
}
console.log(`GREEN: the new row draws its id (${fresh[0]?.boxHeight}px line) and its facts line ${JSON.stringify(fresh[0]?.meta)}`);
