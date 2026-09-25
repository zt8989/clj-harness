// WHO ASKS FOR GET /api/projects -- and how often. Patches `window.fetch` before the page
// loads and prints the caller's stack for each projects call.
//
//   node .scratch/new-session-bug/who-asks.mjs [ui-url]
import fs from "node:fs";
import os from "node:os";
import path from "node:path";

import { chromium } from "file:///C:/Users/zhouteng/scoop/persist/nvm/nodejs/v24.9.0/node_modules/@playwright/cli/node_modules/playwright/index.mjs";

const url = process.argv[2] ?? "http://localhost:5219/";
const projectDir = fs.mkdtempSync(path.join(os.tmpdir(), "who-asks-"));

const browser = await chromium.launch();
const page = await browser.newPage({ viewport: { width: 1100, height: 800 }, locale: "zh-CN" });
await page.addInitScript(() => {
  const real = window.fetch;
  window.__asks = [];
  window.fetch = async (input, init) => {
    const u = typeof input === "string" ? input : input.url;
    if (u.includes("/api/projects") && (init?.method ?? "GET") === "GET") {
      window.__asks.push({ at: Date.now(), stack: new Error("here").stack.split("\n").slice(1, 7).join("\n") });
    }
    return real(input, init);
  };
});

await page.goto(url, { waitUntil: "load" });
await page.waitForSelector('[data-slot="sidebar"]', { timeout: 15000 });
const added = await page.evaluate(async (dir) => {
  const res = await fetch("/api/projects", { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ dir }) });
  return res.json();
}, projectDir);
await page.click('[data-slot="sidebar-refresh"]');
await page.waitForTimeout(500);
await page.evaluate(() => { window.__asks.length = 0; });
console.log("--- idle for 8s, no clicks ---");
await page.waitForTimeout(8000);
const asks = await page.evaluate(() => window.__asks);
console.log("GET /api/projects calls in 8s:", asks.length);
console.log("first caller:\n", asks[0]?.stack ?? "(none)");

await page.evaluate(() => { window.__asks.length = 0; });
const esc = added.path.replace(/\\/g, "\\\\");
const before = await page.evaluate(() => localStorage.getItem("clj-harness.session"));
await page.click(`[data-slot="sidebar-project"][data-path="${esc}"] [data-slot="sidebar-project-new-session"]`);
await page.waitForTimeout(6000);
console.log("--- 6s after clicking + on the project ---");
const asks2 = await page.evaluate(() => window.__asks);
console.log("GET /api/projects calls:", asks2.length,
  "at:", asks2.map((a) => a.at - (asks2[0]?.at ?? 0)).join(","));
console.log("first caller:\n", asks2[0]?.stack ?? "(none)");
console.log("page error?", await page.evaluate(() => window.__asks.length));
await browser.close();
fs.rmSync(projectDir, { recursive: true, force: true });