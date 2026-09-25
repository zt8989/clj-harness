// WATCH THE LIST: mint a project session, send, and read the sidebar's own rows every
// 100ms -- so a row that appears with the THREAD ID as its name (before the store has a
// title) is caught with the instant it happened.
//
//   node .scratch/new-session-bug/watch-rows.mjs [ui-url]
import fs from "node:fs";
import os from "node:os";
import path from "node:path";

import { chromium } from "file:///C:/Users/zhouteng/scoop/persist/nvm/nodejs/v24.9.0/node_modules/@playwright/cli/node_modules/playwright/index.mjs";

const url = process.argv[2] ?? "http://localhost:5219/";
const projectDir = fs.mkdtempSync(path.join(os.tmpdir(), "watch-rows-"));
const SENT = "看行探针：项目里的第一句";

const browser = await chromium.launch();
const page = await browser.newPage({ viewport: { width: 1100, height: 800 }, locale: "zh-CN" });
await page.goto(url, { waitUntil: "load" });
await page.waitForSelector('[data-slot="sidebar"]', { timeout: 15000 });

const added = await page.evaluate(async (dir) => {
  const res = await fetch("/api/projects", { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ dir }) });
  return res.json();
}, projectDir);
await page.click('[data-slot="sidebar-refresh"]');
await page.waitForTimeout(400);

// IN THE PAGE: a 100ms sampler that keeps every DISTINCT reading of the list.
await page.evaluate(() => {
  window.__seen = [];
  setInterval(() => {
    const rows = [...document.querySelectorAll('[data-slot="thread-list-item"]')].map((li) => ({
      id: (li.querySelector('[data-slot="thread-list-item-trigger"]')?.getAttribute("title") ?? "").split("\n")[0],
      says: li.querySelector('[data-slot="thread-list-item-title"]')?.textContent?.trim() ?? null,
      block: li.closest('[data-slot="sidebar-project"]') === null ? "tasks" : "project",
    }));
    const key = JSON.stringify(rows);
    const last = window.__seen[window.__seen.length - 1];
    if (last === undefined || last.key !== key) window.__seen.push({ key, at: Date.now() - window.__t0, rows });
  }, 100);
  window.__t0 = Date.now();
});

const esc = added.path.replace(/\\/g, "\\\\");
const before = await page.evaluate(() => localStorage.getItem("clj-harness.session"));
await page.click(`[data-slot="sidebar-project"][data-path="${esc}"] [data-slot="sidebar-project-new-session"]`);
await page.waitForFunction((prev) => localStorage.getItem("clj-harness.session") !== prev, before, { timeout: 10000 });
const minted = await page.evaluate(() => localStorage.getItem("clj-harness.session"));
console.log("minted:", minted);
await page.waitForTimeout(1500);

await page.fill("textarea", SENT);
await page.press("textarea", "Enter");
console.log("sent at t=0");
await page.waitForTimeout(15000);

const seen = await page.evaluate(() => window.__seen);
console.log(`\n${seen.length} distinct reading(s) of the list:`);
for (const s of seen) {
  const mine = s.rows.filter((r) => r.id === minted);
  console.log(`  t+${String(s.at).padStart(6)}ms  rows=${s.rows.length}  mine=${JSON.stringify(mine)}`);
}
const uuidNamed = seen.filter((s) => s.rows.some((r) => r.id === minted && r.says === minted));
console.log(uuidNamed.length === 0 ? "\nVERDICT: the row never read as its thread id" : `\nVERDICT: ${uuidNamed.length} reading(s) named the row by its THREAD ID`);
await browser.close();
fs.rmSync(projectDir, { recursive: true, force: true });