// DIAGNOSTIC, NOT A GATE: is there any INK on the reasoning row while the model
// is thinking?
//
//   node .scratch/thinking-row-tail/scratch-ink.mjs [ui-url]
//
// Run it against `node scripts/dev.mjs --scripted .scratch/thinking-row-tail/script.json --ui-port 5394`.
//
// WHY. Everything else this feature measures is geometry or text content: the row
// is 28px tall, the words are the arrived text, the line is dragged, the panel is
// closed. All of that is true of a row whose pixels are BLANK -- and a real reader
// reported exactly that: 空白 while it thinks, and 思考 · <首行> appearing the
// instant the thought ends. So this counts pixels: it screenshots the row's own box
// while the stream is live and again after it settles, and reports how much of the
// box is darker than the page behind it.
import fs from "node:fs";
import path from "node:path";
import { execSync } from "node:child_process";
import { fileURLToPath } from "node:url";

const HERE = path.dirname(fileURLToPath(import.meta.url));
const globalRoot = execSync("npm root -g", { encoding: "utf8" }).trim();
const { chromium } = await import(path.join(globalRoot, "playwright", "index.mjs"));

const url = process.argv[2] ?? "http://localhost:5394/";
const ROW = '[data-slot="reasoning-trigger"]';

const browser = await chromium.launch();
const page = await browser.newPage({ viewport: { width: 1200, height: 900 } });
page.on("pageerror", (e) => console.log("page error:", e.message));

/// INK = pixels of the clip darker than a threshold. The page is white-ish, the
/// row's text is `oklch(0.556 0 0)` (a mid grey), so 200 separates them widely.
const inkOf = async (clip) => {
  const shot = await page.screenshot({ clip });
  const b64 = shot.toString("base64");
  return page.evaluate(
    async ([base64, threshold]) => {
      const img = new Image();
      img.src = `data:image/png;base64,${base64}`;
      await img.decode();
      const canvas = new OffscreenCanvas(img.width, img.height);
      const ctx = canvas.getContext("2d");
      ctx.drawImage(img, 0, 0);
      const { data } = ctx.getImageData(0, 0, img.width, img.height);
      let ink = 0;
      let darkest = 255;
      for (let i = 0; i < data.length; i += 4) {
        const lum = 0.2126 * data[i] + 0.7152 * data[i + 1] + 0.0722 * data[i + 2];
        if (lum < darkest) darkest = Math.round(lum);
        if (lum < threshold) ink += 1;
      }
      return { ink, total: (data.length / 4) | 0, darkest, width: img.width, height: img.height };
    },
    [b64, 200],
  );
};

const boxOf = (sel) =>
  page.evaluate((s) => {
    const el = document.querySelector(s);
    if (!el) return null;
    const b = el.getBoundingClientRect();
    return { x: Math.round(b.left), y: Math.round(b.top), width: Math.round(b.width), height: Math.round(b.height) };
  }, sel);

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
await page.waitForSelector(ROW, { timeout: 30000 });
console.log("row appeared");

// While it thinks: sample the row as often as the browser will let us, keep the
// fattest reading (the run is a few seconds; each sample is a screenshot).
let live = null;
for (let i = 0; i < 40; i += 1) {
  const liveRow = await page.evaluate((s) => document.querySelector(s) !== null, '[data-slot="reasoning-trigger-tail"]');
  if (!liveRow) {
    console.log(`live window gone after ${i} samples`);
    break;
  }
  const box = await boxOf(ROW);
  if (box !== null && box.width > 0) {
    const ink = await inkOf(box);
    if (live === null || ink.ink > live.ink.ink) live = { box, ink, at: i };
  } else {
    console.log(`sample ${i}: no box`);
  }
  await page.waitForTimeout(80);
}
await page.screenshot({ path: path.join(HERE, "evidence", "scratch-ink-live.png") });

// And after it settles (the row says its first line).
await page.waitForSelector('[data-slot="reasoning-trigger-tail"]', { state: "detached", timeout: 30000 }).catch(() => {});
let settledBox = null;
for (let i = 0; i < 40 && settledBox === null; i += 1) {
  settledBox = await boxOf(ROW);
  if (settledBox === null) await page.waitForTimeout(100);
}
const settledInk = settledBox === null ? null : await inkOf(settledBox);
await page.screenshot({ path: path.join(HERE, "evidence", "scratch-ink-settled.png") });

const text = await page.evaluate((s) => document.querySelector(s)?.textContent ?? "", ROW);
console.log("row box:", JSON.stringify(settledBox));
console.log("while thinking:", live === null ? "no live sample" : `${live.ink.ink} ink of ${live.ink.total} px, darkest ${live.ink.darkest}`);
console.log("after it stops:", settledInk === null ? "no row" : `${settledInk.ink} ink of ${settledInk.total} px, darkest ${settledInk.darkest}`);
console.log("row text after:", JSON.stringify(text.slice(0, 40)));

await browser.close();