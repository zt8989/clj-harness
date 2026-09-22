// WHAT A THINKING ROW DOES WHILE THE MODEL IS THINKING -- measured in a real
// browser, because none of it is visible to a string.
//
//   node .scratch/thinking-row-tail/walkthrough.mjs [ui-url]
//
// Run it against
//   node scripts/dev.mjs --scripted .scratch/thinking-row-tail/script.json --ui-port 5391
// (temp homes, a scripted provider, no api-key) -- the script next to this file
// is what the model "thinks", and THIS SCRIPT READS THE SAME FILE to know what
// the row should say: the first line at rest, the last 120 characters of the
// flattened thought while it is arriving. Both are derived here rather than
// copied, so a change to the fixture cannot make the assertions vacuous.
//
// WHY IT EXISTS. The rules this walks were moved into `src/lib/reasoning-preview.ts`
// so that a suite could reach them (`test/suites/reasoning-row.ts`, three cases),
// and that suite is the half that can be read as a string. The rest are
// properties of the RENDERED page and no suite can see them:
//
//   1. THE ROW NEVER UNFOLDS ITSELF. It used to -- upstream's `streaming`, whose
//      rule is `userOpen ?? streaming` -- and the fix is that the open state is
//      the row's own and starts false. Only a runtime can be asked.
//   2. THE LIVE WINDOW CUTS AT ITS LEFT EDGE, so the newest characters stay in
//      view and the older ones run off behind them. That is `direction: rtl` on
//      one element (`styles.css`), which is a fact about boxes.
//   3. IT MOVES. "滚动" is not something a substring assertion can be right
//      about: the window has to be seen sliding while the tokens land.
//   4. THE FIRST LINE COMES BACK when the thought ends (and stays folded when the
//      same conversation is restored from disk).
//
// WHY IT THROTTLES THE NETWORK. `harness.fake` emits a thought in 5-character
// chunks with no pause between them, so the whole scripted stream lands in one
// burst and the live window is over before a sample can be taken -- measured:
// the first run of this script caught a single sample and the row was already
// settled. Chrome's own bandwidth throttling (CDP, below) makes the SAME stream
// arrive spread out over seconds, which is what a real vendor's does; no fixture
// had to learn a new trick, and the bytes are the bytes.
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

const url = process.argv[2] ?? "http://localhost:5391/";

const PREVIEW_LIMIT = 120; // `lib/reasoning-preview.ts`'s own number.
const THROTTLE = 24 * 1024; // bytes/sec; the scripted thought is ~110 kB of SSE.

let failures = 0;
function check(label, ok, detail = "") {
  console.log(`${ok ? "ok  " : "RED "} ${label}${detail === "" ? "" : ` -- ${detail}`}`);
  if (!ok) failures += 1;
}

/// WHAT THE MODEL THINKS, read from the script the server was handed. The two
/// expectations below are the rule stated once, here, rather than a copy of the
/// fixture: `firstLine` skips the blank lines a thought may open with, and the
/// tail is the last PREVIEW_LIMIT characters of the whitespace-flattened text.
const script = JSON.parse(fs.readFileSync(path.join(HERE, "script.json"), "utf8"));
const thought = script.turns[0].reasoning;
const firstLine = thought.split("\n").map((l) => l.trim()).find((l) => l !== "");
const flat = thought.replace(/\s+/g, " ").trim();
const tail = flat.slice(-PREVIEW_LIMIT);

const browser = await chromium.launch();
const page = await browser.newPage({ viewport: { width: 1200, height: 900 } });
page.on("pageerror", (e) => check("no page error", false, e.message));

const ROW = '[data-slot="reasoning-trigger"]';
const SUBJECT = '[data-slot="reasoning-trigger-subject"]';
const TAIL = '[data-slot="reasoning-trigger-tail"]';
const ROOT = '[data-slot="reasoning-root"]';
const PANEL = '[data-slot="reasoning-content"]';

/// The row as the page draws it: the subject's words, whether the live window is
/// there (which is also this script's "still thinking" signal), and what the
/// disclosure is doing.
///
/// THE DISCLOSURE IS READ OFF `data-state` AND NOT OFF THE ELEMENT COUNT, and
/// that is the correction this script needed after its first run: a CLOSED
/// Radix disclosure keeps an empty, zero-height content element mounted, so
/// "one reasoning-content element" is true whether the panel is open or not.
/// Measured on this page while folded: `data-state="closed"`, height 0, and no
/// children rendered at all -- which is also why the thought costs nothing to
/// the page while it is folded.
const readRow = () =>
  page.evaluate(
    ([rowSel, subjectSel, tailSel, rootSel, panelSel]) => {
      const row = document.querySelector(rowSel);
      if (!row) return null;
      const subject = row.querySelector(subjectSel);
      const tail = row.querySelector(tailSel);
      const inner = tail?.firstElementChild ?? null;
      const box = tail?.getBoundingClientRect() ?? null;
      const content = inner?.getBoundingClientRect() ?? null;
      const panel = document.querySelector(panelSel);
      return {
        subject: subject?.textContent ?? null,
        live: tail !== null,
        disclosure: document.querySelector(rootSel)?.getAttribute("data-state") ?? null,
        contentState: panel?.getAttribute("data-state") ?? null,
        contentHeight: panel ? Math.round(panel.getBoundingClientRect().height) : null,
        boxLeft: box ? Math.round(box.left) : null,
        boxWidth: box ? Math.round(box.width) : null,
        innerLeft: content ? Math.round(content.left) : null,
        innerWidth: content ? Math.round(content.width) : null,
        innerText: inner?.textContent ?? null,
        rowHeight: Math.round(row.getBoundingClientRect().height),
      };
    },
    [ROW, SUBJECT, TAIL, ROOT, PANEL],
  );

const isFolded = (row) => row !== null && row.contentState === "closed" && row.contentHeight === 0;

async function until(predicate, timeout = 60000, step = 50) {
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
// THE ROW APPEARS, AND ITS LIVE WINDOW WITH IT. The row can be drawn a beat
// before the first token lands (the preview is "" then, and a row with nothing
// to say draws no subject at all), so this waits for the window and not for the
// row.
const samples = [];
const live = await until(async () => {
  const row = await readRow();
  if (row?.live !== true) return null;
  samples.push(row);
  return row;
}, 60000, 25);
check("the row shows a live window while the model is thinking", live !== null);
if (live === null) {
  await page.screenshot({ path: path.join(EVIDENCE, "00-no-live-row.png") });
  console.log("\nthe live window never appeared -- nothing else can be measured");
  await browser.close();
  process.exit(1);
}
await page.screenshot({ path: path.join(EVIDENCE, "01-while-thinking.png") });

// --------------------------------------------------------------- 2. it folds
// THE PANEL DOES NOT OPEN ITSELF, in this state or in any other it passes
// through: this is the claim the change is about, and it is measured on EVERY
// sample rather than once, because the old code unfolded the panel exactly while
// tokens were arriving.
check(
  "the panel never opens itself while the tokens arrive",
  isFolded(live),
  `state ${JSON.stringify(live.disclosure)}, content ${JSON.stringify(live.contentState)}/${live.contentHeight}px`,
);
/// AND IT IS STILL ONE LINE, which is what makes the window a window: the row
/// is a 13px line inside `py-1.5`, i.e. 28px, and a subject that WRAPPED would
/// push every step below it down the page for as long as the model thinks.
/// (32 rather than 28 so that a rounding or a one-pixel border cannot make
/// this red.)
check(
  "the row is still ONE line while it runs",
  live.rowHeight <= 32,
  `${live.rowHeight}px tall`
)

// ------------------------------------------------------- 3. it says the tail
// THE FIRST SAMPLE SHOWS THE THOUGHT'S BEGINNING, and that is the rule working
// rather than failing: the window is the last 120 characters OF WHAT HAS
// ARRIVED, so while less than a windowful has arrived there is nothing to cut
// away yet. What the row must not do -- say the first line and stop moving --
// is measured at the END of the stream, below.
check(
  "the first thing the row says is the beginning of the thought",
  live.subject !== null && flat.startsWith(live.innerText ?? "\u0000"),
  `subject: ${JSON.stringify((live.subject ?? "").slice(-48))}`
);

// --------------------------------------------------------------- 4. it moves
// SAMPLES WHILE IT RUNS: the words keep arriving, and the window slides LEFT as
// they do (that is the scroll -- the newest characters enter at the right edge
// and the older ones leave behind the left one). One sample cannot tell a
// scrolling row from a frozen one.
const ended = await until(async () => {
  const row = await readRow();
  if (row === null) return true;
  samples.push(row);
  return row.live !== true;
}, 120000, 100);

const liveSamples = samples.filter((s) => s.live);
const texts = liveSamples.map((s) => s.innerText);
check(
  "the row's words keep changing while it runs",
  new Set(texts).size > 1,
  `${liveSamples.length} live samples, ${new Set(texts).size} distinct`,
);
const slid = liveSamples.map((s) => s.innerLeft).filter((left) => left !== null);
check(
  "the live window slides left, so the newest characters stay in view",
  slid.length > 1 && slid[slid.length - 1] < slid[0],
  `inner left: ${slid[0]} -> ${slid[slid.length - 1]} (${slid.length} samples)`,
);
const cut = liveSamples.filter((s) => s.innerLeft !== null && s.boxLeft !== null);
const clipped = cut.find((s) => s.innerLeft < s.boxLeft);
check(
  "the window cuts at its LEFT edge: the beginning of the thought is behind it",
  clipped !== undefined,
  clipped === undefined
    ? `no sample was clipped (${cut.length} measured)`
    : `inner ${clipped.innerLeft} vs box ${clipped.boxLeft}; the inner is ${clipped.innerWidth}px wide in a ${clipped.boxWidth}px box`
);
/// THE WINDOW IS A RUN OF THE MODEL'S OWN WORDS, AND IT ADVANCES. What the row
/// is HANDED -- the last 120 characters of what has arrived -- is pinned as a
/// string by the suite; what only the page can say is that the row draws that
/// text and that it follows the stream rather than sitting on a window of its
/// own. So every sample has to be a contiguous run of the thought, and its
/// PLACE in the thought has to move forward from sample to sample: that is what
/// "the newest part" looks like from out here. (The position is found by
/// matching the window in the thought, which the fixture's numbered sentences
/// make unique; a window that appeared twice would make this red, not green.)
/// A WINDOW ONLY MOVES ONCE THERE IS MORE THAN A WINDOWFUL: while less than 120
/// characters have arrived, the row is showing the whole of what the model has
/// written so far -- a prefix of the thought, pinned at its beginning. So the
/// claim about movement is made over the FULL windows (a sample at exactly
/// PREVIEW_LIMIT characters), and every sample, short or full, has to be a run
/// of the model's own words.
const textOf = (s) => s.innerText ?? "\u0000";
const offsets = liveSamples.map((s) => flat.indexOf(textOf(s)));
const stray = liveSamples.findIndex((s) => !flat.includes(textOf(s)));
const full = liveSamples
  .filter((s) => textOf(s).length === PREVIEW_LIMIT)
  .map((s) => flat.indexOf(textOf(s)));
check(
  "every sample is a run of the model's own words",
  stray === -1 && full.length > 1,
  stray === -1
    ? `${liveSamples.length} samples, ${full.length} of them a full window`
    : `sample ${stray}: ${JSON.stringify(liveSamples[stray].innerText)}`
);
check(
  "a full window moves forward through the thought, one sample at a time",
  full.every((at, i) => i === 0 || at > full[i - 1]),
  `window from ${full[0]} to ${full[full.length - 1]} of ${flat.length} characters`
);
check(
  "and the window is never the whole thought",
  liveSamples.some((s) => (s.innerText ?? "").length < flat.length),
  `longest sample ${Math.max(...liveSamples.map((s) => (s.innerText ?? "").length))} chars`
);
check("the thought ends, and the row stops being live", ended === true);
check(
  "the panel is still folded when the thought has stopped",
  isFolded(samples[samples.length - 1] ?? null),
  `state ${JSON.stringify(samples[samples.length - 1]?.disclosure)}`,
);

// The stream is over; the reload below is a page load, and the dev bundle is
// megabytes.
await cdp.send("Network.emulateNetworkConditions", {
  offline: false,
  latency: 0,
  downloadThroughput: -1,
  uploadThroughput: -1,
});

// ------------------------------------------------------- 5. back to line one
// THE FIRST LINE COMES BACK, and the panel is STILL folded: a thought that has
// stopped is a step like any other -- a click away.
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
// A RESTORED CONVERSATION IS NEVER RUNNING, so it arrives as first lines: the
// row is folded and says the first line, with no live window in it.
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