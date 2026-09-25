// A TIMELINE of every /api request the page makes, with the caller's first real frame --
// load, idle, click "+", idle, send, idle.
//
//   node .scratch/new-session-bug/timeline.mjs [ui-url]
import fs from "node:fs";
import os from "node:os";
import path from "node:path";

import { chromium } from "file:///C:/Users/zhouteng/scoop/persist/nvm/nodejs/v24.9.0/node_modules/@playwright/cli/node_modules/playwright/index.mjs";

const url = process.argv[2] ?? "http://localhost:5219/";
const projectDir = fs.mkdtempSync(path.join(os.tmpdir(), "timeline-"));
const SENT = "时间线探针：项目里的第一句";

const browser = await chromium.launch();
const page = await browser.newPage({ viewport: { width: 1100, height: 800 }, locale: "zh-CN" });
await page.addInitScript(() => {
  const real = window.fetch;
  window.__t = [];
  window.__t0 = Date.now();
  window.fetch = async (input, init) => {
    const u = typeof input === "string" ? input : input.url;
    if (u.includes("/api/")) {
      const frames = new Error("here").stack.split("\n").slice(2);
      const mine = frames.find((f) => f.includes("localhost") && f.includes("/src/")) ?? frames[0] ?? "?";
      window.__t.push({
        at: Date.now() - window.__t0,
        what: `${init?.method ?? "GET"} ${u.replace(/^https?:\/\/[^/]+/, "")}`,
        who: mine.trim().replace(/^at\s+/, "").replace(/\?.*$/, ""),
      });
    }
    return real(input, init);
  };
});

const dump = async (tag) => {
  const t = await page.evaluate(() => window.__t.splice(0));
  console.log(`\n===== ${tag}`);
  for (const e of t) console.log(`  ${String(e.at).padStart(6)}ms ${e.what}   <- ${e.who}`);
  if (t.length === 0) console.log("  (nothing)");
};

await page.goto(url, { waitUntil: "load" });
await page.waitForSelector('[data-slot="sidebar"]', { timeout: 15000 });
await page.waitForTimeout(3000);
await dump("the 3s after load");

const added = await page.evaluate(async (dir) => {
  const res = await fetch("/api/projects", { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ dir }) });
  return res.json();
}, projectDir);
await page.click('[data-slot="sidebar-refresh"]');
await page.waitForTimeout(3000);
await dump("after adding the project and refreshing");

await page.waitForTimeout(6000);
await dump("6s with the project listed, nothing touched");

const esc = added.path.replace(/\\/g, "\\\\");
const before = await page.evaluate(() => localStorage.getItem("clj-harness.session"));
await page.click(`[data-slot="sidebar-project"][data-path="${esc}"] [data-slot="sidebar-project-new-session"]`);
await page.waitForTimeout(6000);
await dump("6s after clicking + on the project");
console.log("shown id now:", await page.evaluate(() => localStorage.getItem("clj-harness.session")));

await page.fill("textarea", SENT);
await page.press("textarea", "Enter");
await page.waitForTimeout(20000);
await dump("20s after the send");
console.log("shown id now:", await page.evaluate(() => localStorage.getItem("clj-harness.session")));
console.log("rows:", await page.evaluate(() =>
  [...document.querySelectorAll('[data-slot="thread-list-item"]')].map((li) => ({
    id: (li.querySelector('[data-slot="thread-list-item-trigger"]')?.getAttribute("title") ?? "").split("\n")[0],
    says: li.querySelector('[data-slot="thread-list-item-title"]')?.textContent?.trim(),
    block: li.closest('[data-slot="sidebar-project"]') === null ? "tasks" : "project",
  })),
));
await browser.close();
fs.rmSync(projectDir, { recursive: true, force: true });