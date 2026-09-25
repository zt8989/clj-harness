// A TIGHT probe for "click `+` beside a project, then send": what does the sidebar look
// like, second by second, and what does the store hold?
//
//   node .scratch/new-session-bug/probe.mjs [ui-url]
//
// Runs against a dev pair started with `node scripts/dev.mjs --scripted <script> --ui-port 5219`.
import fs from "node:fs";
import os from "node:os";
import path from "node:path";

import { chromium } from "file:///C:/Users/zhouteng/scoop/persist/nvm/nodejs/v24.9.0/node_modules/@playwright/cli/node_modules/playwright/index.mjs";

const url = process.argv[2] ?? "http://localhost:5219/";
const projectDir = fs.mkdtempSync(path.join(os.tmpdir(), "probe-new-session-"));
const SENT = "项目里发出去的第一句";

const browser = await chromium.launch();
const page = await browser.newPage({ viewport: { width: 1100, height: 800 }, locale: "zh-CN" });
page.on("pageerror", (e) => console.log("PAGE ERROR", e.message));
page.on("request", (r) => {
  const u = r.url();
  if (u.includes("/api/") && !u.includes("/api/threads/")) console.log(`  >> ${r.method()} ${u.replace(/^http:\/\/[^/]+/, "")}`);
});

await page.goto(url, { waitUntil: "load" });
await page.waitForSelector('[data-slot="sidebar-tasks"], [data-slot="sidebar-project"], [data-slot="sidebar-empty"]', { timeout: 15000 });

const added = await page.evaluate(async (dir) => {
  const res = await fetch("/api/projects", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ dir }),
  });
  return res.json();
}, projectDir);
console.log("added project", added);

await page.click('[data-slot="sidebar-refresh"]');
await page.waitForTimeout(300);

const esc = added.path.replace(/\\/g, "\\\\");
await page.waitForSelector(`[data-slot="sidebar-project"][data-path="${esc}"]`, { timeout: 15000 });

/// Everything the list is drawing right now, with which block each row is in.
const rows = () =>
  page.evaluate(() => {
    const out = [];
    for (const li of document.querySelectorAll('[data-slot="thread-list-item"]')) {
      const trigger = li.querySelector('[data-slot="thread-list-item-trigger"]');
      const project = li.closest('[data-slot="sidebar-project"]');
      out.push({
        block: project === null ? "tasks" : project.getAttribute("data-path"),
        id: (trigger?.getAttribute("title") ?? "").split("\n")[0],
        says: li.querySelector('[data-slot="thread-list-item-title"]')?.textContent?.trim() ?? null,
        current: li.hasAttribute("data-current"),
        error: li.querySelector('[data-slot="thread-list-item-error"]')?.textContent?.trim() ?? null,
      });
    }
    return out;
  });

const store = () =>
  page.evaluate(async () => {
    const res = await fetch("/api/projects");
    const b = await res.json();
    return {
      inProject: b.projects.flatMap((p) => p.sessions).map((s) => s.threadId),
      tasks: b.tasks.map((s) => s.threadId),
    };
  });

const shown = () => page.evaluate(() => localStorage.getItem("clj-harness.session"));

console.log("expanded?", await page.$eval(`[data-slot="sidebar-project"][data-path="${esc}"] button`, (b) => b.getAttribute("aria-expanded")));

const before = await shown();
await page.click(`[data-slot="sidebar-project"][data-path="${esc}"] [data-slot="sidebar-project-new-session"]`);
await page.waitForFunction((prev) => localStorage.getItem("clj-harness.session") !== prev, before, { timeout: 10000 });
const minted = await shown();
console.log("minted", minted);
await page.waitForTimeout(500);
console.log("after click: rows", JSON.stringify(await rows()), "store", JSON.stringify(await store()));

await page.fill("textarea", SENT);
await page.press("textarea", "Enter");

for (let i = 0; i < 20; i += 1) {
  await page.waitForTimeout(1000);
  console.log(`t+${i + 1}s rows=${JSON.stringify(await rows())} store=${JSON.stringify(await store())}`);
}

await page.screenshot({ path: path.join(import.meta.dirname, "probe-after-send.png") });
console.log("shown id", await shown());
await browser.close();
fs.rmSync(projectDir, { recursive: true, force: true });