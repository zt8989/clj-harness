// DIAGNOSTIC, NOT A GATE: which of the two things on that row blanks its text --
// the `shimmer` class the row wears while it is running, or the transform the
// window drags the line with?
//
//   node .scratch/thinking-row-tail/scratch-which.mjs [ui-url]
//
// Run it against `node scripts/dev.mjs --scripted .scratch/thinking-row-tail/script.json --ui-port 5394`.
//
// HOW. It waits for the row to SETTLE (a thought that has stopped: no shimmer, no
// drag, and the text is known to be visible), then adds ONE of the two to it and
// counts the ink again. Whatever blanks the text is the one that did it -- measured
// on a row that was legible a moment before.
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

const boxOf = (sel) =>
  page.evaluate((s) => {
    const el = document.querySelector(s);
    if (!el) return null;
    const b = el.getBoundingClientRect();
    return { x: Math.round(b.left), y: Math.round(b.top), width: Math.round(b.width), height: Math.round(b.height) };
  }, sel);

const inkOf = async (clip) => {
  const shot = await page.screenshot({ clip });
  return page.evaluate(async (base64) => {
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
      if (lum < 200) ink += 1;
    }
    return { ink, darkest };
  }, shot.toString("base64"));
};

await page.goto(url);
await page.waitForSelector("textarea");
await page.fill("textarea", "想一下再答。");
await page.press("textarea", "Enter");
await page.waitForSelector(ROW, { timeout: 30000 });
await page.waitForTimeout(3000); // let the thought finish (the scripted stream is short)

const box = await boxOf(ROW);
const report = [];
const step = async (label) => {
  await page.waitForTimeout(250);
  const ink = await inkOf(box);
  report.push(`${label}: ${ink.ink} ink px, darkest ${ink.darkest}`);
};
await step("settled (no shimmer, no drag)");
await page.evaluate((s) => document.querySelector(s)?.classList.add("shimmer"), ROW);
await step("settled + shimmer on the label");
await page.evaluate((s) => document.querySelector(s)?.classList.remove("shimmer"), ROW);
await page.evaluate(
  (s) => {
    const label = document.querySelector(s);
    label.querySelector("b")?.classList.add("shimmer");
  },
  ROW,
);
await step("settled + shimmer on the NAME only");
await page.evaluate(
  (s) => {
    const label = document.querySelector(s);
    label.querySelector("b")?.classList.remove("shimmer");
    const span = document.createElement("span");
    span.style.display = "inline-block";
    span.style.transform = "translateX(-120px)";
    span.textContent = label.textContent;
    label.replaceChildren(span);
  },
  ROW,
);
await step("settled + a plain transform on a span holding the text");
console.log(report.join("\n"));
await browser.close();