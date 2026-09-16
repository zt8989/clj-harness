// Driving the MCP panel in a real Chromium, with an isolated browser instance
// (another session owns the shared playwright profile).
import pw from "/Users/zhouteng/.nvm/versions/node/v22.21.1/lib/node_modules/playwright/index.js";
const { chromium } = pw;

// WHY THE CORS CHECK IS OFF. This repo's contract is backend :8080 + UI :5173,
// and BOTH ports belong to other sessions on this machine right now (another
// harness on 8080, another vite on 5173). The backend's allowed origin is baked
// into a private map when harness.http loads, so it cannot be repointed without
// editing code. What is under test here is the PANEL -- its three states and its
// switch -- not the CORS contract, which is asserted by the Clojure suite's
// own edges. So the browser's check is off, and this comment is where that is
// admitted rather than hidden.
const context = await chromium.launchPersistentContext("/tmp/mcp-evidence/chrome", {
  args: ["--disable-web-security"],
  viewport: { width: 1280, height: 900 },
});
const browser = context;
const page = context.pages()[0] ?? (await context.newPage());

// The client hardcodes http://localhost:8080/ (another session's server owns that
// port); this points it at mine, before any app script runs.
await page.addInitScript(() => {
  const real = window.fetch.bind(window);
  window.fetch = (input, init) => {
    const rewrite = (u) => u.replace("localhost:8080", "localhost:8081");
    if (typeof input === "string") return real(rewrite(input), init);
    if (input && input.url) return real(new Request(rewrite(input.url), input));
    return real(input, init);
  };
});

const errors = [];
page.on("pageerror", (e) => errors.push(String(e)));
page.on("console", (m) => { if (m.type() === "error") errors.push(m.text()); });

await page.goto("http://localhost:5199/", { waitUntil: "networkidle" });

// The panel lives in the sidebar's Settings dialog.
await page.getByTitle(/read-only|running on/i).click();
await page.screenshot({ path: "/tmp/mcp-evidence/00-after-click.png" });
await page.waitForTimeout(2000);
console.log("SLOTS:", JSON.stringify(await page.$$eval("[data-slot]", (els) =>
  [...new Set(els.map((e) => e.getAttribute("data-slot")))])));
await page.waitForSelector('[data-slot="mcp-panel"]', { timeout: 15000 });
await page.waitForSelector('[data-slot="mcp-server"][data-status="connected"]', { timeout: 15000 });
await page.screenshot({ path: "/tmp/mcp-evidence/01-ledger.png" });

const read = async () => page.$$eval('[data-slot="mcp-server"]', (rows) =>
  rows.map((r) => ({
    server: r.getAttribute("data-server"),
    status: r.getAttribute("data-status"),
    text: r.innerText.replace(/\s+/g, " ").slice(0, 160),
  })));
console.log("STATES:", JSON.stringify(await read(), null, 1));

// OFF: the switch
await page.$eval('[data-slot="mcp-server"][data-server="workshop"] [data-slot="mcp-server-toggle"]',
  (b) => b.click());
await page.waitForSelector('[data-slot="mcp-server"][data-server="workshop"][data-status="disabled"]',
  { timeout: 15000 });
await page.screenshot({ path: "/tmp/mcp-evidence/02-switched-off.png" });
console.log("AFTER-OFF:", JSON.stringify(await read(), null, 1));

// ON again
await page.$eval('[data-slot="mcp-server"][data-server="workshop"] [data-slot="mcp-server-toggle"]',
  (b) => b.click());
await page.waitForSelector('[data-slot="mcp-server"][data-server="workshop"][data-status="connected"]',
  { timeout: 20000 });
await page.screenshot({ path: "/tmp/mcp-evidence/03-back-on.png" });
console.log("AFTER-ON:", JSON.stringify(await read(), null, 1));

console.log("CONSOLE-ERRORS:", JSON.stringify(errors));
await context.close();
