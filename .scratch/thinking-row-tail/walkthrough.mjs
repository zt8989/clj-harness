// WHAT A THINKING ROW DOES WHILE THE MODEL IS THINKING -- measured in a real
// browser, because none of it is visible to a string.
//
//   node .scratch/thinking-row-tail/walkthrough.mjs [ui-url]
//
// Run it against
//   node scripts/dev.mjs --scripted .scratch/thinking-row-tail/script.json --ui-port 5393
// (temp homes, a scripted provider, no api-key) -- the script next to this file
// is what the model "thinks", and THIS SCRIPT READS THE SAME FILE to know what
// the row should say: the first line at rest, and -- while it is arriving -- the
// whole of what has arrived, flattened to one line.
//
// WHY IT EXISTS. `src/lib/reasoning-preview.ts` decides WHAT the row is handed
// and is tested as a string (`test/suites/reasoning-row.ts`, three cases). The
// rest are properties of the RENDERED page and no suite can see them:
//
//   1. THE ROW NEVER UNFOLDS ITSELF. It used to -- upstream's `streaming`, whose
//      rule is `userOpen ?? streaming` -- and the fix is that the open state is
//      the row's own and starts false. Only a runtime can be asked.
//   2. THE WINDOW KEEPS THE END OF THE LINE IN VIEW: the beginning runs off the
//      left edge, and the right edge always has text under it. That is a layout
//      fact (the window is `overflow: hidden`, the line inside it is dragged).
//   3. THE DRAG IS INTERPOLATED, NOT A JUMP PER TOKEN. This is the one the first
//      cut of this feature got wrong: clipping the left edge moves the text by
//      LAYOUT, one frame per token, and a reader sees a snap. The fix moves it by
//      a transform and gives the transform a duration; the check for it is a
//      sample where the WORDS did not change and the POSITION did.
//   4. THE FIRST LINE COMES BACK when the thought ends (and stays folded when the
//      same conversation is restored from disk).
//
// WHY IT THROTTLES THE NETWORK. `harness.fake` emits a thought in 5-character
// chunks with no pause between them, so the whole scripted stream lands in one
// burst and there is nothing to watch. Chrome's own bandwidth throttling (CDP,
// below) makes the SAME stream arrive spread out over seconds, which is what a
// real vendor's does; no fixture had to learn a new trick, and the bytes are the
// bytes.
//
// WHAT THIS SCRIPT NEEDS OF ITS OWN BROWSER: playwright, resolved out of the
// global npm root rather than a path written for one machine -- the repo's other
// walkthroughs pin an absolute Windows path, which is not a thing this file can
// borrow.
import fs from "node:fs";
import path from "node:path";
import { execSync } from "node:child_process";
import { fileURLToPath } from "node:url";

const HERE = path.dirname(fileURLToPath(import.meta.url));
const EVIDENCE = path.join(HERE, "evidence");

const globalRoot = execSync("npm root -g", { encoding: "utf8" }).trim();
const { chromium } = await import(path.join(globalRoot, "playwright", "index.mjs"));

const url = process.argv[2] ?? "http://localhost:5393/";

// Bytes per second. The thought is ~1,000 characters of text, and a scripted
// frame carries five of them, so the whole stream is ~25 kB of SSE -- at this rate
// it arrives over about four seconds, i.e. ~250 characters a second, which is what
// a fast real vendor looks like. Not an accident of the fixture: the drag is
// capped at a speed (`TAIL_SPEED`, `message-parts.tsx`), and a stream faster than
// that cap is one the window deliberately falls behind rather than teleports.
const THROTTLE = 6 * 1024;

let failures = 0;
function check(label, ok, detail = "") {
  console.log(`${ok ? "ok  " : "RED "} ${label}${detail === "" ? "" : ` -- ${detail}`}`);
  if (!ok) failures += 1;
}

/// WHAT THE MODEL THINKS, read from the script the server was handed. The two
/// expectations below are the rule stated once, here, rather than a copy of the
/// fixture: `firstLine` skips the blank lines a thought may open with, and the
/// live half is the same text flattened to one line (`oneLine`).
const script = JSON.parse(fs.readFileSync(path.join(HERE, "script.json"), "utf8"));
const thought = script.turns[0].reasoning;
const firstLine = thought.split("\n").map((l) => l.trim()).find((l) => l !== "");
const flat = thought.replace(/\s+/g, " ").trim();

const browser = await chromium.launch();
const page = await browser.newPage({ viewport: { width: 1200, height: 900 } });
page.on("pageerror", (e) => check("no page error", false, e.message));

const ROW = '[data-slot="reasoning-trigger"]';
const SUBJECT = '[data-slot="reasoning-trigger-subject"]';
const WINDOW = '[data-slot="reasoning-trigger-tail"]';
const ROOT = '[data-slot="reasoning-root"]';
const PANEL = '[data-slot="reasoning-content"]';

/// The row as the page draws it: the subject's words, whether the live window is
/// there (which is also this script's "still thinking" signal), what the
/// disclosure is doing, and the two boxes that say what is in view -- the WINDOW
/// (the part of the row the text is confined to) and the TRACK (the line itself,
/// as far as layout and the transform have put it).
///
/// THE DISCLOSURE IS READ OFF `data-state` AND NOT OFF THE ELEMENT COUNT, and
/// that is the correction this script needed after its first run: a CLOSED Radix
/// disclosure keeps an empty, zero-height content element mounted, so "one
/// reasoning-content element" is true whether the panel is open or not.
const readRow = () =>
  page.evaluate(
    ([rowSel, subjectSel, windowSel, rootSel, panelSel]) => {
      const row = document.querySelector(rowSel);
      if (!row) return null;
      const subject = row.querySelector(subjectSel);
      const windowEl = row.querySelector(windowSel);
      const track = windowEl?.firstElementChild ?? null;
      const box = windowEl?.getBoundingClientRect() ?? null;
      const line = track?.getBoundingClientRect() ?? null;
      const panel = document.querySelector(panelSel);
      return {
        subject: subject?.textContent ?? null,
        live: windowEl !== null,
        disclosure: document.querySelector(rootSel)?.getAttribute("data-state") ?? null,
        contentState: panel?.getAttribute("data-state") ?? null,
        contentHeight: panel ? Math.round(panel.getBoundingClientRect().height) : null,
        rowHeight: Math.round(row.getBoundingClientRect().height),
        windowLeft: box ? box.left : null,
        windowRight: box ? box.right : null,
        trackLeft: line ? line.left : null,
        trackRight: line ? line.right : null,
        text: track?.textContent ?? null,
      };
    },
    [ROW, SUBJECT, WINDOW, ROOT, PANEL],
  );

const isFolded = (row) => row !== null && row.contentState === "closed" && row.contentHeight === 0;
/// HOW MUCH OF THE LINE IS STILL OFF THE RIGHT EDGE, in pixels. It is 0 when the
/// drag has caught up, and positive while it is travelling -- the newest
/// characters are then a moment away from being in view, which is what a smooth
/// scroll IS (the alternative, no drag at all, is the snap).
const lag = (row) => (row.trackRight === null ? null : Math.round(row.trackRight - row.windowRight));

async function until(predicate, timeout = 60000, step = 40) {
  const deadline = Date.now() + timeout;
  for (;;) {
    const value = await predicate();
    if (value) return value;
    if (Date.now() > deadline) return null;
    await page.waitForTimeout(step);
  }
}

await page.goto(url);

/// THROTTLE, THEN SEND. The page is loaded first because the dev bundle is
/// megabytes and would take minutes at this rate; from here on the only traffic
/// that matters is the stream.
const cdp = await page.context().newCDPSession(page);
await cdp.send("Network.enable");
await cdp.send("Network.emulateNetworkConditions", {
  offline: false,
  latency: 5,
  downloadThroughput: THROTTLE,
  uploadThroughput: THROTTLE,
});

await page.waitForSelector("textarea");
await page.fill("textarea", "想一下再答。");
await page.press("textarea", "Enter");

// ------------------------------------------------------------- 1. it is live
const samples = [];
const live = await until(async () => {
  const row = await readRow();
  if (row?.live !== true) return null;
  samples.push(row);
  return row;
});
check("the row shows a live window while the model is thinking", live !== null);
if (live === null) {
  await page.screenshot({ path: path.join(EVIDENCE, "00-no-live-row.png") });
  console.log("\nthe live window never appeared -- nothing else can be measured");
  await browser.close();
  process.exit(1);
}
await page.screenshot({ path: path.join(EVIDENCE, "01-while-thinking.png") });

// --------------------------------------------------------------- 2. it folds
check(
  "the panel never opens itself while the tokens arrive",
  isFolded(live),
  `state ${JSON.stringify(live.disclosure)}, content ${JSON.stringify(live.contentState)}/${live.contentHeight}px`,
);
check("the row is still ONE line while it runs", live.rowHeight <= 32, `${live.rowHeight}px tall`);

// ---------------------------------------------------------- 3. what it shows
// THE LINE IS THE ARRIVED TEXT, WHOLE. It is not a window cut to its last N
// characters: what has run off the left edge has to stay in the DOM for the drag
// to be able to move it (see `ReasoningTail`). So every sample's line is a PREFIX
// of the thought -- and the first sample is what has arrived after a few
// characters, not the beginning of a cut window.
check(
  "the line is the arrived text, whole -- not a window cut out of it",
  live.text !== null && flat.startsWith(live.text),
  `line: ${live.text?.length ?? 0} characters of ${flat.length}, ends ${JSON.stringify((live.text ?? "").slice(-16))}`,
);

// --------------------------------------------------------------- 4. it moves
// SAMPLES WHILE IT RUNS, and then the three things that make this a WINDOW rather
// than a line of text that happens to be long:
//
//   * the beginning runs off the LEFT edge (`trackLeft < windowLeft`);
//   * the right edge always has text under it (`trackRight >= windowRight`): a
//     drag that lagged forever, or a line that was never dragged at all, would
//     leave the window showing its beginning and a gap after it;
//   * and between two samples the WORDS often stand still while the POSITION
//     moves -- the duration the component sets is what does that, and it is the
//     whole difference between a scroll and the snap this replaced.
const ended = await until(async () => {
  const row = await readRow();
  if (row === null) return true;
  samples.push(row);
  return row.live !== true;
});
const live_samples = samples.filter((s) => s.live);
const texts = live_samples.map((s) => s.text);

check(
  "the line keeps growing while it runs",
  new Set(texts).size > 1,
  `${live_samples.length} live samples, ${new Set(texts).size} distinct lines`,
);
check(
  "the words do NOT change between every pair of samples -- so the position must",
  live_samples.some((s, i) => i > 0 && s.text === live_samples[i - 1].text),
  live_samples.length < 2 ? "fewer than two samples" : "at least one hold",
);

const held = live_samples.filter((s, i) => i > 0 && s.text === live_samples[i - 1].text);
const heldMoving = held.filter((s, i) => s.trackLeft !== held[i - 1]?.trackLeft);
check(
  "while the words hold still the line is still TRAVELLING (interpolated, not a jump)",
  held.length === 0 ? false : heldMoving.length > 0,
  `${heldMoving.length} of ${held.length} holds moved`,
);

const slid = live_samples.map((s) => s.trackLeft).filter((left) => left !== null);
check(
  "the line slides left, so characters leave at the left edge",
  slid.length > 1 && slid[slid.length - 1] < slid[0],
  `track left: ${Math.round(slid[0])} -> ${Math.round(slid[slid.length - 1])} (${slid.length} samples)`,
);
const clipped = live_samples.filter(
  (s) => s.trackLeft !== null && s.windowLeft !== null && s.trackLeft < s.windowLeft,
);
check(
  "the window cuts at its LEFT edge: the beginning of the thought is behind it",
  clipped.length > 0,
  clipped.length === 0
    ? "no sample was clipped"
    : `line starts ${Math.round(clipped[0].trackLeft - clipped[0].windowLeft)}px left of the window`,
);
const full = live_samples.filter((s) => lag(s) !== null && lag(s) >= -1);
const lags = live_samples.map(lag);
const worst = Math.max(...lags);
check(
  "the right edge always has text under it -- the newest characters arrive there",
  full.length === live_samples.length,
  `lag: max ${worst}px, last ${lags[lags.length - 1]}px, ${live_samples.length} samples`,
);
/// AND IT CATCHES UP. The scripted stream arrives in bursts (a throttled socket
/// delivers several frames at once), so a sample taken in the middle of one shows
/// a lag -- and the claim that matters is that the lag is a burst being *travelled*
/// and not a backlog: it comes back to nothing. The LAST live sample is the
/// strongest of those moments, because the stream has stopped by then. A drag that
/// never moved at all would sit at the line's whole overflow (measured: `trackLeft`
/// -10,487px against a 478px window), which no part of this allows.
check(
  "the drag catches up: the newest characters come back into view",
  lags.some((l) => l <= 2) && lags[lags.length - 1] <= 4,
  `lag: max ${worst}px, median ${lags.slice().sort((a, b) => a - b)[Math.floor(lags.length / 2)]}px, last ${lags[lags.length - 1]}px, ${lags.filter((l) => l <= 2).length} of ${lags.length} samples caught up`,
);
const lengths = live_samples.map((s) => (s.text ?? "").length);
check(
  "every sample is the thought so far, and shorter than the thought",
  live_samples.every((s) => flat.startsWith(s.text ?? "\u0000")) &&
    lengths.every((n, i) => i === 0 || n >= lengths[i - 1]) &&
    lengths[lengths.length - 1] < flat.length,
  `line ${lengths[0]} -> ${lengths[lengths.length - 1]} of ${flat.length} characters`,
);

check("the thought ends, and the row stops being live", ended === true);
check(
  "the panel is still folded when the thought has stopped",
  isFolded(samples[samples.length - 1] ?? null),
  `state ${JSON.stringify(samples[samples.length - 1]?.disclosure)}`,
);

// The stream is over; what follows is a page load, and the dev bundle is
// megabytes.
await cdp.send("Network.emulateNetworkConditions", {
  offline: false,
  latency: 0,
  downloadThroughput: -1,
  uploadThroughput: -1,
});

// ------------------------------------------------------- 5. back to line one
const settled = await until(async () => {
  const row = await readRow();
  return row !== null && !row.live ? row : null;
}, 30000, 50);
check("the row is still there when the thought has stopped", settled !== null);
check(
  "it says the FIRST line again",
  settled !== null && settled.subject === ` · ${firstLine}`,
  `subject: ${JSON.stringify(settled?.subject ?? "")}`,
);
check("and it is still folded", isFolded(settled));

await page.screenshot({ path: path.join(EVIDENCE, "02-after-thinking.png") });

// ------------------------------------------------------------ 6. still opens
// THE READER'S CLICK IS STILL THE ONE THING THAT OPENS IT, and it opens the same
// disclosure it always did -- the fix removed the automatic half, not the panel.
await page.click(ROW);
const opened = await until(async () => {
  const row = await readRow();
  return isFolded(row) ? null : row;
}, 10000, 25);
check(
  "a click opens the thought",
  opened !== null && opened.contentState === "open" && opened.contentHeight > 0,
  `content ${JSON.stringify(opened?.contentState)}/${opened?.contentHeight}px`,
);
const whole = await page.evaluate(
  (sel) => document.querySelector(sel)?.textContent?.length ?? 0,
  PANEL,
);
check("and the opened panel holds the whole thought", whole >= thought.length, `${whole} chars`);
await page.screenshot({ path: path.join(EVIDENCE, "03-opened-by-hand.png") });
await page.click(ROW);
check(
  "a second click closes it again",
  (await until(async () => isFolded(await readRow()), 10000, 25)) === true,
);

// ------------------------------------------------------------ 7. after reload
// A RESTORED CONVERSATION IS NEVER RUNNING, so it arrives as first lines: the row
// is folded and says the first line, with no live window in it.
await page.reload();
const restored = await until(() => readRow(), 60000, 100);
check("the conversation comes back after a reload", restored !== null);
check(
  "a restored thought arrives folded, saying its first line",
  restored !== null &&
    !restored.live &&
    isFolded(restored) &&
    restored.subject === ` · ${firstLine}`,
  `subject: ${JSON.stringify(restored?.subject ?? "")}, live: ${restored?.live}, content: ${JSON.stringify(restored?.contentState)}`,
);
await page.screenshot({ path: path.join(EVIDENCE, "04-restored.png") });

await browser.close();
console.log(`\n${failures === 0 ? "GREEN" : `RED (${failures})`} -- screenshots in ${EVIDENCE}`);
process.exit(failures === 0 ? 0 : 1);