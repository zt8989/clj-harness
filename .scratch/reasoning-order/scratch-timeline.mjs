// DIAGNOSTIC, NOT A GATE: what the page does, sampled every ~30ms, from the question to
// the end of the turn.
//
//   node .scratch/reasoning-order/scratch-timeline.mjs [ui-url]
//
// Run it against `node scripts/dev.mjs --scripted .scratch/reasoning-order/script.json --ui-port 5397`.
//
// WHY. The walkthrough asks whether the row is still live "while the answer is being
// written" and the answer is no -- which should be impossible: the wire's own order
// (measured in the record) puts the answer's first token BEFORE the late reasoning and
// the model's end. Either the page draws the answer only at the end (batching somewhere)
// or the row settles before the wire says it should. A timeline tells the two apart.
import fs from "node:fs";
import path from "node:path";
import { execSync } from "node:child_process";
import { fileURLToPath } from "node:url";

const HERE = path.dirname(fileURLToPath(import.meta.url));
const globalRoot = execSync("npm root -g", { encoding: "utf8" }).trim();
const { chromium } = await import(path.join(globalRoot, "playwright", "index.mjs"));

const url = process.argv[2] ?? "http://localhost:5397/";
const script = JSON.parse(fs.readFileSync(path.join(HERE, "script.json"), "utf8")).turns[0];
const answerHead = script.content.slice(0, 6);

const browser = await chromium.launch();
const page = await browser.newPage({ viewport: { width: 1200, height: 900 } });
await page.goto(url);
const cdp = await page.context().newCDPSession(page);
await cdp.send("Network.enable");
await cdp.send("Network.emulateNetworkConditions", {
  offline: false,
  latency: 5,
  downloadThroughput: 6 * 1024,
  uploadThroughput: 6 * 1024,
});
await page.waitForSelector("textarea");
await page.fill("textarea", "想一下再答。");
await page.press("textarea", "Enter");

const started = Date.now();
const rows = [];
while (Date.now() - started < 8000) {
  const sample = await page.evaluate(
    ([answer]) => {
      const row = document.querySelector('[data-slot="reasoning-trigger"]');
      const track = row?.querySelector('[data-slot="reasoning-trigger-tail"]')?.firstElementChild ?? null;
      const body = document.body.innerText;
      const at = body.indexOf(answer);
      return {
        row: row !== null,
        live: row?.querySelector('[data-slot="reasoning-trigger-tail"]') !== null,
        subject: (row?.textContent ?? "").slice(0, 24),
        answerChars: at < 0 ? 0 : body.length - at, // crude: how much follows the answer's head
        thinking: body.includes("Thinking"),
      };
    },
    [answerHead],
  );
  rows.push({ ms: Date.now() - started, ...sample });
  await page.waitForTimeout(30);
}

// Print only the transitions: the interesting thing is the order of the changes.
let previous = null;
for (const row of rows) {
  const key = JSON.stringify([row.row, row.live, row.answerChars > 0, row.thinking]);
  if (key !== previous) {
    console.log(
      `${String(row.ms).padStart(5)}ms  row=${row.row} live=${row.live} answerDrawn=${row.answerChars > 0} subject=${JSON.stringify(row.subject)}`,
    );
    previous = key;
  }
}
console.log(`\n${rows.length} samples over 8s`);
await browser.close();