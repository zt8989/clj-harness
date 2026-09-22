// DIAGNOSTIC, NOT A GATE: draw the reasoning row as ASCII, so a human -- or an
// agent that cannot look at a PNG -- can SEE what is painted on it.
//
//   node .scratch/thinking-row-tail/scratch-ascii.mjs [ui-url]
//
// Run it against `node scripts/dev.mjs --scripted .scratch/thinking-row-tail/script.json --ui-port 5394`.
//
// WHY. The ink count came back IDENTICAL while the row was live and after it had
// settled (1097 px of 18,368, darkest 114) -- which is the signature of a row whose
// pixels do not depend on its text at all. Geometry and DOM text cannot tell that
// apart from a row that is drawing correctly; a luminance map can.
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

/// The clip, as text: one character per 2x2 block, from `.` (blank) to `#` (dark).
const asciiOf = async (clip) => {
  const shot = await page.screenshot({ clip });
  return page.evaluate(
    async (base64) => {
      const img = new Image();
      img.src = `data:image/png;base64,${base64}`;
      await img.decode();
      const canvas = new OffscreenCanvas(img.width, img.height);
      const ctx = canvas.getContext("2d");
      ctx.drawImage(img, 0, 0);
      const { data } = ctx.getImageData(0, 0, img.width, img.height);
      const ramp = " .:-=+*#%@";
      const rows = [];
      for (let y = 0; y < img.height; y += 2) {
        let line = "";
        for (let x = 0; x < img.width; x += 2) {
          // The darkest pixel of the block: a glyph is thin, an average would
          // wash it out at this size.
          let darkest = 255;
          for (let dy = 0; dy < 2 && y + dy < img.height; dy += 1) {
            for (let dx = 0; dx < 2 && x + dx < img.width; dx += 1) {
              const i = ((y + dy) * img.width + (x + dx)) * 4;
              const lum = 0.2126 * data[i] + 0.7152 * data[i + 1] + 0.0722 * data[i + 2];
              if (lum < darkest) darkest = lum;
            }
          }
          line += ramp[Math.min(ramp.length - 1, Math.floor(((255 - darkest) / 255) * ramp.length))];
        }
        rows.push(line);
      }
      return rows.join("\n");
    },
    shot.toString("base64"),
  );
};

const boxOf = (sel) =>
  page.evaluate((s) => {
    const el = document.querySelector(s);
    if (!el) return null;
    const b = el.getBoundingClientRect();
    return { x: Math.round(b.left), y: Math.round(b.top), width: Math.round(b.width), height: Math.round(b.height) };
  }, sel);

const caption = async () => {
  const row = await page.evaluate(
    (s) => {
      const el = document.querySelector(s);
      return {
        text: el?.textContent ?? "",
        live: el?.querySelector('[data-slot="reasoning-trigger-tail"]') !== null,
      };
    },
    ROW,
  );
  return row;
};

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

// Wait for the line to be long enough to be dragged (i.e. more than a windowful).
await page.waitForFunction(
  (s) => {
    const win = document.querySelector(s);
    const track = win?.firstElementChild;
    if (win === null || track === null) return false;
    return track.getBoundingClientRect().width > win.getBoundingClientRect().width + 40;
  },
  '[data-slot="reasoning-trigger-tail"]',
  { timeout: 30000 },
);

const liveBox = await boxOf(ROW);
const liveCaption = await caption();
const liveArt = liveBox === null ? "(no row)" : await asciiOf(liveBox);
await page.screenshot({ path: path.join(HERE, "evidence", "scratch-ascii-live.png") });

// THE SAME LIVE ROW, WITH THE SHIMMER NEUTRALISED. `shimmer` paints the text
// through a mask (`-webkit-mask-clip: text`) and sweeps a white band over it, so
// it is a candidate for "the row is blank"; neutralising it IN THE LIVE ROW is what
// tells the two candidates apart -- the class comes back on the next token's
// re-render, and by then these two maps have already been taken.
await page.addStyleTag({
  content: `[data-slot="reasoning-trigger"] .shimmer {
    -webkit-mask-clip: border-box !important;
    -webkit-mask-image: none !important;
    -webkit-text-fill-color: currentColor !important;
    background: none !important;
  }
  [data-slot="reasoning-trigger"] .shimmer::before { display: none !important; }`,
});
await page.waitForTimeout(200);
const plainCaption = await caption();
const plainArt = await asciiOf(liveBox);
await page.screenshot({ path: path.join(HERE, "evidence", "scratch-ascii-live-noshimmer.png") });

await page.waitForSelector('[data-slot="reasoning-trigger-tail"]', { state: "detached", timeout: 30000 }).catch(() => {});
await page.waitForTimeout(300);
const settledBox = (await boxOf(ROW)) ?? liveBox;
const settledCaption = await caption();
const settledArt = await asciiOf(settledBox);
await page.screenshot({ path: path.join(HERE, "evidence", "scratch-ascii-settled.png") });

console.log(`row box ${JSON.stringify(liveBox)}`);
console.log(`\n=== WHILE THINKING (live window: ${liveCaption.live}) ===`);
console.log(`text: ${JSON.stringify(liveCaption.text.slice(0, 60))}`);
console.log(liveArt);
console.log(`\n=== SAME LIVE ROW, SHIMMER NEUTRALISED (live window: ${plainCaption.live}) ===`);
console.log(plainArt);
console.log(`\n=== AFTER IT STOPS (live window: ${settledCaption.live}) ===`);
console.log(`text: ${JSON.stringify(settledCaption.text.slice(0, 60))}`);
console.log(settledArt);

await browser.close();