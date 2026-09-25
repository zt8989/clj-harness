// CLICK, THEN RELOAD: mint a session from a project's "+", reload the page, and see what
// the store and the list hold afterwards -- does the thread id get created by itself?
//
//   node .scratch/new-session-bug/reload.mjs [ui-url]
import fs from "node:fs";
import os from "node:os";
import path from "node:path";

import { chromium } from "file:///C:/Users/zhouteng/scoop/persist/nvm/nodejs/v24.9.0/node_modules/@playwright/cli/node_modules/playwright/index.mjs";

const url = process.argv[2] ?? "http://localhost:5219/";
const projectDir = fs.mkdtempSync(path.join(os.tmpdir(), "reload-"));

const browser = await chromium.launch();
const page = await browser.newPage({ viewport: { width: 1100, height: 800 }, locale: "zh-CN" });
page.on("request", (r) => {
  const u = r.url();
  if (u.includes("/api/") && !u.includes("threads/")) console.log(`   >> ${r.method()} ${u.replace(/^https?:\/\/[^/]+/, "")}`);
});
page.on("pageerror", (e) => console.log("   PAGE ERROR", e.message));

const rows = () =>
  page.evaluate(() =>
    [...document.querySelectorAll('[data-slot="thread-list-item"]')].map((li) => ({
      id: (li.querySelector('[data-slot="thread-list-item-trigger"]')?.getAttribute("title") ?? "").split("\n")[0],
      says: li.querySelector('[data-slot="thread-list-item-title"]')?.textContent?.trim() ?? null,
      block: li.closest('[data-slot="sidebar-project"]') === null ? "tasks" : "project",
    })),
  );
const store = () =>
  page.evaluate(async () => {
    const b = await (await fetch("/api/projects")).json();
    return { inProject: b.projects.flatMap((p) => p.sessions.map((s) => [s.threadId, s.firstUserText])), tasks: b.tasks.map((s) => [s.threadId, s.firstUserText]) };
  });

await page.goto(url, { waitUntil: "load" });
await page.waitForSelector('[data-slot="sidebar"]', { timeout: 15000 });
const added = await page.evaluate(async (dir) => {
  const res = await fetch("/api/projects", { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ dir }) });
  return res.json();
}, projectDir);
await page.click('[data-slot="sidebar-refresh"]');
await page.waitForTimeout(400);
const esc = added.path.replace(/\\/g, "\\\\");

const before = await page.evaluate(() => localStorage.getItem("clj-harness.session"));
await page.click(`[data-slot="sidebar-project"][data-path="${esc}"] [data-slot="sidebar-project-new-session"]`);
await page.waitForFunction((prev) => localStorage.getItem("clj-harness.session") !== prev, before, { timeout: 10000 });
const minted = await page.evaluate(() => localStorage.getItem("clj-harness.session"));
console.log("minted:", minted, "-> memory:", await page.evaluate(() => localStorage.getItem("clj-harness.session")));
console.log("store before reload:", JSON.stringify(await store()));
console.log("rows before reload:", JSON.stringify(await rows()));

console.log("\n--- RELOAD ---");
await page.reload({ waitUntil: "load" });
await page.waitForSelector('[data-slot="sidebar"]', { timeout: 15000 });
await page.waitForTimeout(3000);
console.log("memory after reload:", await page.evaluate(() => localStorage.getItem("clj-harness.session")));
console.log("store after reload:", JSON.stringify(await store()));
console.log("rows after reload:", JSON.stringify(await rows()));

await page.click('[data-slot="sidebar-refresh"]');
await page.waitForTimeout(1500);
console.log("\n--- after a manual refresh ---");
console.log("memory:", await page.evaluate(() => localStorage.getItem("clj-harness.session")));
console.log("store:", JSON.stringify(await store()));
console.log("rows:", JSON.stringify(await rows()));
await page.screenshot({ path: path.join(import.meta.dirname, "reload-after.png") });
await browser.close();
fs.rmSync(projectDir, { recursive: true, force: true });