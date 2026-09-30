// THE WALKTHROUGH: a page, a message, and an answer WATCHED while it arrives
// (`.scratch/conversation-render-cost/`).
//
//   node .scratch/conversation-render-cost/evidence/walkthrough.mjs --url http://127.0.0.1:4811
//
// WHY THIS EXISTS NEXT TO THE RULER. `measure-run.mjs` says what the stream COSTS; it says nothing
// about what a reader SEES, and AGENTS.md is explicit that the machine gates cannot see layout -- a
// green run once shipped a sidebar with no titles. Ticket 01 changes how often the text of a
// message still being written is rebuilt, and the two things that could go wrong are invisible to
// every suite:
//
//   * the answer stops ARRIVING -- the interval is a window, and a window that swallowed values, or
//     one wider than the whole turn, would show the reader nothing until the turn was over;
//   * the markdown stops being markdown -- bold and inline code are the shapes a reader notices
//     missing, so the finished answer is checked for the elements they become.
//
// SO IT WATCHES THE DOM, not the clock. A `MutationObserver` on the conversation records
// `(time, characters on screen)` at every change to the answer, which is the same stream of updates
// a reader's eye is given. From those: how many updates there were, how far apart (the interval the
// page actually achieved), how much text arrived in each (the step a reader sees), and whether any
// of them went BACKWARDS -- which is what a dropped or reordered commit looks like.
//
// THE WATCHER IS NOT FREE, and it is not on trial: its callback re-reads the conversation's text
// on every mutation, so `chatSeconds` here is a page doing MORE work than it would without it --
// it is not the number to compare across builds. What is comparable is the shape of the update
// stream: the gap between updates, the step of text each one carries, and whether any went
// backwards. `measure-run.mjs` is where the cost lives.
import { mkdirSync, writeFileSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

import { MESSAGES, STOP, WORKING, argOf, chromium, forgetSession } from "./playwright.mjs";

const HERE = path.dirname(fileURLToPath(import.meta.url));
const URL = argOf("--url", "http://127.0.0.1:4811");
const PROMPT = "写一个很长的回答，越详细越好。";
const SHOTS = argOf("--shots", path.join(HERE, "shots"));
const OUT = argOf("--out", "walkthrough.json");

const browser = await chromium().launch({ headless: true, channel: "chromium" });
const context = await browser.newContext({ viewport: { width: 1280, height: 720 } });
await forgetSession(context);
const page = await context.newPage();

await page.goto(URL, { waitUntil: "domcontentloaded" });
await page.waitForSelector(".aui-composer-input", { timeout: 30_000 });
await page.waitForTimeout(1500);
const openedWith = await page.locator(MESSAGES).count();
if (openedWith !== 0) throw new Error(`the conversation was not empty (${openedWith} messages)`);

/// THE WATCHER, installed before the message is sent so nothing is missed: every change anywhere in
/// the conversation records how many characters of the assistant's answer are on the page, and
/// when.
await page.evaluate((selector) => {
  window.__updates = [];
  const record = () => {
    const roots = [...document.querySelectorAll(selector)];
    window.__updates.push([
      performance.now(),
      roots.reduce((sum, root) => sum + (root.textContent ?? "").length, 0),
    ]);
  };
  window.__watcher = new MutationObserver(record);
  window.__watcher.observe(document.body, { subtree: true, childList: true, characterData: true });
  record();
}, MESSAGES);

await page.fill(".aui-composer-input", PROMPT);
await page.press(".aui-composer-input", "Enter");
await page.waitForSelector(WORKING, { timeout: 60_000 });

// A SCREENSHOT OF THE ANSWER ARRIVING, taken once there is enough of it to look at, and one of the
// finished turn. They are the "a person opened it and looked" half of the acceptance; the numbers
// below are the other half.
mkdirSync(SHOTS, { recursive: true });
let shot = false;
for (;;) {
  const chars = await page.evaluate(
    (selector) =>
      [...document.querySelectorAll(selector)].reduce((sum, root) => sum + (root.textContent ?? "").length, 0),
    MESSAGES,
  );
  if (!shot && chars > 4000) {
    await page.screenshot({ path: path.join(SHOTS, "mid-stream.png") });
    shot = true;
  }
  const writing = (await page.locator(WORKING).count()) > 0 || (await page.locator(STOP).count()) > 0;
  if (!writing) break;
  await page.waitForTimeout(250);
}
await page.screenshot({ path: path.join(SHOTS, "finished.png") });

const updates = await page.evaluate(() => {
  window.__watcher.disconnect();
  return window.__updates;
});

/// WHAT THE FINISHED ANSWER IS MADE OF. The scripted body is prose with `**bold**` and `inline
/// code`, so those two are the shapes a reader would notice going missing; a paragraph element is
/// the markdown having been parsed at all rather than dumped as one text node. `.aui-md` is the
/// markdown container itself.
const elements = await page.evaluate(
  (selector) => {
    const root = document.querySelector(selector);
    return {
      paragraphs: root?.querySelectorAll("p").length ?? 0,
      bold: root?.querySelectorAll("strong").length ?? 0,
      code: root?.querySelectorAll("code").length ?? 0,
      markdownContainers: root?.querySelectorAll(".aui-md").length ?? 0,
    };
  },
  MESSAGES,
);

// ONLY THE CHANGES: the observer also fires for the sidebar, the composer and the working dot, and
// a sample whose character count did not move is not an update a reader saw.
const distinct = updates.filter((update, index) => index === 0 || update[1] !== updates[index - 1][1]);
const gaps = distinct.slice(1).map((update, index) => update[0] - distinct[index][0]);
const steps = distinct.slice(1).map((update, index) => update[1] - distinct[index][1]);
const median = (numbers) => (numbers.length === 0 ? 0 : [...numbers].sort((a, b) => a - b)[Math.floor(numbers.length / 2)]);
const round = (n) => Number(n.toFixed(1));

const report = {
  updates: distinct.length,
  chatSeconds: round((distinct[distinct.length - 1][0] - distinct[0][0]) / 1000),
  updatesPerSecond: round(distinct.length / ((distinct[distinct.length - 1][0] - distinct[0][0]) / 1000)),
  medianGapMs: round(median(gaps)),
  largestGapMs: round(Math.max(...gaps)),
  medianStepChars: median(steps),
  largestStepChars: Math.max(...steps),
  stepsBackwards: steps.filter((step) => step < 0).length,
  finalChars: distinct[distinct.length - 1][1],
  elements,
  screenshots: SHOTS,
};

console.log(JSON.stringify(report, null, 2));
writeFileSync(path.join(HERE, OUT), `${JSON.stringify(report, null, 2)}\n`, "utf8");
await browser.close();
