// WHAT A TURN'S SEVERAL THOUGHT ROWS SAY while the run is live. Measures the
// bug: a live run keeps one assistant message open for the whole turn, and the
// row used to go BLANK for every thought once a tool call was in that message.
//
//   node scripts/dev.mjs --scripted .scratch/thinking-preview-live/script.json --ui-port 5394
//   node .scratch/thinking-preview-live/check.mjs [ui-url]
//
// Each turn in the script reasons and then calls a tool, except the last, which
// answers -- so a correct row says its own first line for THINK 1, THINK 2 and
// THINK 3, and the run is not "live" (an interactive window) for any of them.
import { execSync } from "node:child_process";

const globalRoot = execSync("npm root -g", { encoding: "utf8" }).trim();
const { chromium } = await import(
  new URL(`file://${globalRoot}/playwright/index.mjs`).href
);

const url = process.argv[2] ?? "http://localhost:5394/";

const browser = await chromium.launch();
const page = await browser.newPage({ viewport: { width: 1200, height: 900 } });
await page.goto(url);

await page.waitForSelector("textarea");
await page.fill("textarea", "先想几次再答。");
await page.press("textarea", "Enter");

// Wait for the run's last thought and answer to have landed.
await page.waitForFunction(
  () => document.body.innerText.includes("好了，依赖没变，工作区是干净的。"),
  null,
  { timeout: 60000 },
);
// Let the rows settle.
await page.waitForTimeout(500);

const rows = await page.evaluate(() =>
  Array.from(document.querySelectorAll('[data-slot="reasoning-trigger"]')).map(
    (row) => ({
      label: row
        .querySelector('[data-slot="reasoning-trigger-name"]')
        ?.textContent?.trim(),
      subject:
        row
          .querySelector('[data-slot="reasoning-trigger-subject"]')
          ?.textContent?.trim() ?? "",
      live:
        row.querySelector('[data-slot="reasoning-trigger-tail"]') !== null,
    }),
  ),
);

console.log(JSON.stringify(rows, null, 2));

const empties = rows.filter((row) => row.subject === "");
console.log(`\nrows: ${rows.length}, empty subjects: ${empties.length}`);
await page.screenshot({ path: new URL("./evidence.png", import.meta.url).pathname });
await browser.close();
process.exit(empties.length === 0 && rows.length >= 3 ? 0 : 1);
