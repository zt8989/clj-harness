// THE COMPOSER'S DIRECTORY PICKER on a freshly minted project session: the strip offers the
// project, the session has no store row yet, and picking it is one click away from the "+".
// Does that pick WRITE a session (点击新增不立刻会话)?
//
//   node .scratch/new-session-bug/picker.mjs [ui-url]
import fs from "node:fs";
import os from "node:os";
import path from "node:path";

import { chromium } from "file:///C:/Users/zhouteng/scoop/persist/nvm/nodejs/v24.9.0/node_modules/@playwright/cli/node_modules/playwright/index.mjs";

const url = process.argv[2] ?? "http://localhost:5219/";
const projectDir = fs.mkdtempSync(path.join(os.tmpdir(), "picker-"));

const browser = await chromium.launch();
const page = await browser.newPage({ viewport: { width: 1100, height: 800 }, locale: "zh-CN" });
page.on("request", (r) => {
  const u = r.url();
  if (u.includes("/api/") && !u.includes("threads/")) console.log(`   >> ${r.method()} ${u.replace(/^https?:\/\/[^/]+/, "")}`);
});

const store = () =>
  page.evaluate(async () => {
    const b = await (await fetch("/api/projects")).json();
    return {
      inProject: b.projects.flatMap((p) => p.sessions.map((s) => [s.threadId, s.firstUserText, s.lastSentAt])),
      tasks: b.tasks.map((s) => [s.threadId, s.firstUserText, s.lastSentAt]),
    };
  });
const rows = () =>
  page.evaluate(() =>
    [...document.querySelectorAll('[data-slot="thread-list-item"]')].map((li) => ({
      id: (li.querySelector('[data-slot="thread-list-item-trigger"]')?.getAttribute("title") ?? "").split("\n")[0],
      says: li.querySelector('[data-slot="thread-list-item-title"]')?.textContent?.trim() ?? null,
      block: li.closest('[data-slot="sidebar-project"]') === null ? "tasks" : "project",
    })),
  );

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
await page.waitForTimeout(1200);
console.log("minted:", minted);

// WHAT THE STRIP SAYS, before the pick: is the project already shown, or is the strip empty
// for a session that is destined for it?
const strip = await page.evaluate(() => {
  const el = document.querySelector('[data-slot="composer-directory-trigger"]');
  const value = document.querySelector('[data-slot="composer-directory-value"]');
  if (el === null) return null;
  return { text: el.textContent.trim(), value: value?.textContent?.trim() ?? null, title: el.getAttribute("title"), html: el.outerHTML.slice(0, 300) };
});
console.log("composer directory strip:", JSON.stringify(strip, null, 1));
console.log("store before the pick:", JSON.stringify(await store()));

console.log("\n--- open the directory picker ---");
await page.click('[data-slot="composer-directory-trigger"]');
await page.waitForTimeout(400);
const options = await page.evaluate(() =>
  [...document.querySelectorAll('[data-slot="composer-directory-option"]')].map((el) => el.textContent.trim()),
);
console.log("options:", JSON.stringify(options));

console.log("\n--- pick the project ---");
const option = page.locator('[data-slot="composer-directory-option"]').first();
await option.click();
await page.waitForTimeout(2000);
console.log("store after the pick:", JSON.stringify(await store()));
await page.click('[data-slot="sidebar-refresh"]');
await page.waitForTimeout(800);
console.log("rows after a refresh:", JSON.stringify(await rows(), null, 1));
const withId = (await rows()).find((r) => r.id === minted);
console.log(withId === undefined ? "\nVERDICT: the pick did not put a row in the list" : `\nVERDICT: the pick put a row in the list named ${JSON.stringify(withId.says)}`);
await browser.close();
fs.rmSync(projectDir, { recursive: true, force: true });