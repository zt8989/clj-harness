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
// END of what has arrived, flattened to one line, and HOW MUCH of it the row may hold
//
// WHY IT EXISTS. `src/lib/reasoning-preview.ts` decides WHAT the row is handed
// (its words, and which BLOCK of them the DOM is holding) and is tested as strings and
// numbers (`test/suites/reasoning-row.ts`). The
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
//   5. THE COPY THE DOM HOLDS IS BOUNDED. A live thought runs to thousands of
//      characters and the row holds a block of it: once the held copy is past
//      `TAIL_KEEP + TAIL_DROP`, a whole `TAIL_DROP` leaves the front (`tailStart`). A
//      row that held the thought instead made every token's work grow with the
//      thought -- which is what this change is for.
//   6. AND LETTING GO OF A BLOCK COSTS NOTHING. The block is paid for in the same
//      frame with a padding, so the line does not move: the sample to look for is one
//      where the line is SHORTER and its left edge is no further right. Without the
//      payment the drag's target follows the width, and the line is pulled thousands
//      of pixels back to the right -- the jump a reader would see.
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
import { fileURLToPath, pathToFileURL } from "node:url";

const HERE = path.dirname(fileURLToPath(import.meta.url));
const EVIDENCE = path.join(HERE, "evidence");

/// WHERE PLAYWRIGHT IS, which is not one place and is never a path written for one
/// machine: the global root first (the shape this file shipped with), then the copies
/// npm's cache holds for `npx` -- which is where `@playwright/mcp` puts one, the
/// declaration in `~/.clj-harness/mcp.edn` -- then this checkout's `ui/node_modules`.
///
/// IT HAS TO BE A `file://` URL: an absolute path is not a specifier the ESM loader
/// takes on Windows, which is how this file failed the first time it was run under
/// Node 24 (`ERR_UNSUPPORTED_ESM_URL_SCHEME`, received protocol `c:`).
///
/// AND THE COPY THAT LAUNCHES IS THE ONE THAT IS USED. A copy can be installed without
/// its browsers -- `browserType.launch: Executable doesn't exist at
/// ...ms-playwright/chromium_headless_shell-1246` is what that looks like -- and this
/// machine has TWO copies in the cache with one set of browsers between them. Which is
/// why the launch happens in a loop rather than at a path picked in advance.
const playwrightEntries = (() => {
  const candidates = [
    path.join(execSync("npm root -g", { encoding: "utf8" }).trim(), "playwright", "index.mjs"),
  ];
  const npxRoot = path.join(
    execSync("npm config get cache", { encoding: "utf8" }).trim(),
    "_npx"
  );
  if (fs.existsSync(npxRoot)) {
    for (const entry of fs.readdirSync(npxRoot)) {
      candidates.push(path.join(npxRoot, entry, "node_modules", "playwright", "index.mjs"));
    }
  }
  candidates.push(path.join(HERE, "..", "..", "ui", "node_modules", "playwright", "index.mjs"));
  return candidates.filter((candidate) => fs.existsSync(candidate));
})();
if (playwrightEntries.length === 0) {
  throw new Error("playwright is not installed anywhere npm can see");
}

const url = process.argv[2] ?? "http://localhost:5393/";

// Bytes per second. The thought is ~2,800 characters of text and a scripted frame
// carries five of them, so the whole stream is ~70 kB of SSE once every frame's JSON
// is counted. MEASURED, NOT ARITHMETIC: at this rate the whole thought arrives over
// about thirty-five seconds, i.e. ~80 characters a second -- a *slow* vendor, and that
// is the honest reason for the number: it is what keeps this walkthrough under a
// minute. Six kilobytes a second was the old rate, right for a thought of a thousand
// characters (~250 characters a second, the original note's arithmetic) and far too
// slow for this one.
// vendor looks like. Not an accident of the fixture: the drag is capped at a speed
// (`TAIL_SPEED`, `message-parts.tsx`), and a stream faster than that cap is one the
// window deliberately falls behind rather than teleports.
//
// AND IT IS WHY THE FIXTURE IS THIS LONG: a thought of a thousand characters never
// fills the row past its bound (`TAIL_KEEP + TAIL_DROP`), so blocks leaving -- what
// section 5 measures -- would never happen, and every criterion about them would be
// green about nothing.
const THROTTLE = 24 * 1024;

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

/// HOW MUCH THE ROW HOLDS, taken from the module that decides it rather than copied:
/// the bound is one of the things this script measures, and a second 600 in here would
/// stay 600 through a change that moved it.
const MODULE = path.join(HERE, "..", "..", "ui", "src", "lib", "reasoning-preview.ts");
const moduleSource = fs.readFileSync(MODULE, "utf8");
const constOf = (name) => {
  const found = moduleSource.match(new RegExp(`export const ${name} = (\\d+);`));
  if (found === null) throw new Error(`${name} is not an exported number in ${MODULE}`);
  return Number(found[1]);
};
const TAIL_KEEP = constOf("TAIL_KEEP");
const TAIL_DROP = constOf("TAIL_DROP");
/// The fixture appends five characters per frame (`harness.fake`), and a sample can
/// land one frame after the copy crossed the bound: what the DOM holds is the module's
/// number plus that.
const CHUNK_SLACK = 64;

/// THE FIXTURE HAS TO BE LONG ENOUGH TO EMPTY THE ROW, or the criteria below are
/// green about nothing: a thought the row can hold whole never lets a block go.
if (flat.length <= TAIL_KEEP + TAIL_DROP * 2) {
  console.log(
    `\nthe scripted thought is ${flat.length} characters -- too short to fill the row and empty it (more than ${TAIL_KEEP + TAIL_DROP * 2} is what a block needs)`
  );
  process.exit(1);
}

const browser = await (async () => {
  const refusals = [];
  for (const entry of playwrightEntries) {
    try {
      const { chromium } = await import(pathToFileURL(entry).href);
      return await chromium.launch();
    } catch (error) {
      refusals.push(`${entry}: ${error.message.split("\n")[0]}`);
    }
  }
  throw new Error(`no playwright copy could start a browser:\n${refusals.join("\n")}`);
})();
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

/// HOW MUCH OF THE ROW IS ACTUALLY PAINTED, in pixels. Geometry and text content
/// cannot tell a legible row from a blank one -- this feature shipped a version
/// whose row was BLANK while the thought arrived while every other check here was
/// green, because `shimmer` (the still-going sweep) paints through a mask taken
/// from the text's LAYOUT and the live line is drawn by a TRANSFORM (see
/// `message-parts.tsx`). So the row's own box is screenshotted and its dark pixels
/// counted: a mask that moved the glyphs out from under themselves, a colour that
/// went transparent, a line dragged off its window -- all of them land here.
const inkOf = async (box) => {
  const shot = await page.screenshot({ clip: box });
  return page.evaluate(async ([base64, threshold]) => {
    const img = new Image();
    img.src = `data:image/png;base64,${base64}`;
    await img.decode();
    const canvas = new OffscreenCanvas(img.width, img.height);
    const ctx = canvas.getContext("2d");
    ctx.drawImage(img, 0, 0);
    const { data } = ctx.getImageData(0, 0, img.width, img.height);
    let ink = 0;
    for (let i = 0; i < data.length; i += 4) {
      const lum = 0.2126 * data[i] + 0.7152 * data[i + 1] + 0.0722 * data[i + 2];
      if (lum < threshold) ink += 1;
    }
    return ink;
  }, [shot.toString("base64"), 200]);
};

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

// ---------------------------------------------------------------- 2. it paints
// THE ROW IS DRAWN, not merely present. The sample above is taken the moment the
// window appears, which can be with only a few characters arrived; this one waits
// until the line is long enough to be dragged (so the window is showing a slice of
// it) and then asks the PIXELS -- see `inkOf` for what green-but-blank looks like.
await until(async () => {
  const row = await readRow();
  return row !== null && row.trackLeft !== null && row.windowLeft !== null
    ? row.trackLeft < row.windowLeft - 40
    : false;
});
const drawnBox = await page.evaluate((sel) => {
  const el = document.querySelector(sel);
  if (el === null) return null;
  const b = el.getBoundingClientRect();
  return { x: Math.round(b.left), y: Math.round(b.top), width: Math.round(b.width), height: Math.round(b.height) };
}, ROW);
const inkWhileRunning = drawnBox === null ? 0 : await inkOf(drawnBox);
check(
  "the row is PAINTED while the thought arrives (ink, not just geometry)",
  inkWhileRunning >= 300,
  `${inkWhileRunning} ink pixels in the row's own ${drawnBox?.width}x${drawnBox?.height} box`,
);

// --------------------------------------------------------------- 3. it folds
check(
  "the panel never opens itself while the tokens arrive",
  isFolded(live),
  `state ${JSON.stringify(live.disclosure)}, content ${JSON.stringify(live.contentState)}/${live.contentHeight}px`,
);
check("the row is still ONE line while it runs", live.rowHeight <= 32, `${live.rowHeight}px tall`);

// ---------------------------------------------------------- 4. what it shows
// ---------------------------------------------------------- 4. what it holds
// THE LINE BEGINS WHERE THE THOUGHT BEGINS. This sample is the first moment the window
// is drawn, which is a few characters into the thought -- nothing has left the front by
// then -- so the line is a PREFIX of the thought and not a window cut out of the
// middle of it. What happens to that front LATER is section 5's: a block leaves once
// the held copy is past `TAIL_KEEP + TAIL_DROP`, and it leaves for nothing.
check(
  "the line starts at the beginning of the thought, not in the middle of one",
  live.text !== null && flat.startsWith(live.text),
  `line: ${live.text?.length ?? 0} characters of ${flat.length}, ends ${JSON.stringify((live.text ?? "").slice(-16))}`,
);

// --------------------------------------------------------------- 5. it moves
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
/// THE LINE IS A RUN OF THE THOUGHT, AND THE COPY THE DOM HOLDS IS BOUNDED. Nothing
/// is drawn that the model did not say, no seam shows between two blocks -- and the
/// DOM never holds more than the module's bound (plus the chunk a frame appends),
/// while the thought itself is thousands of characters. THE BOUND IS THE POINT: the
/// row used to hold the thought, and every token's work grew with it.
const heldMax = Math.max(...lengths);
check(
  "the line is a run of the thought, and the copy the DOM holds is bounded",
  live_samples.every((s) => (s.text ?? "") === "" || flat.includes(s.text ?? "\u0000")) &&
    lengths.every((n) => n <= TAIL_KEEP + TAIL_DROP + CHUNK_SLACK) &&
    lengths[lengths.length - 1] < flat.length,
  `line ${lengths[0]} -> ${lengths[lengths.length - 1]} of ${flat.length} characters, longest ${heldMax} (bound ${TAIL_KEEP + TAIL_DROP + CHUNK_SLACK})`,
);

/// AND A BLOCK LEFT, MORE THAN ONCE. The stream only ever APPENDS, so a sample whose
/// line is shorter than the one before it can only be a block going.
const left = live_samples
  .map((s, i) => (i > 0 && lengths[i] < lengths[i - 1] ? i : -1))
  .filter((i) => i >= 0);
check(
  "a block leaves the front of the line more than once",
  left.length >= 2,
  `${left.length} blocks left, at ${left.map((i) => `${lengths[i - 1]}->${lengths[i]}`).join(", ")}`
);

/// AND LETTING GO OF ONE COSTS NOTHING -- the criterion this whole change exists for.
/// The block is paid for in the same frame with a padding, so the line does not move:
/// the pair to look for is one where the line is SHORTER and its LEFT EDGE IS NO
/// FURTHER RIGHT. Without the payment, the drag's target follows the width, so the line
/// is dragged thousands of pixels back to the right -- over up to 400ms, which is a
/// reader watching the thought rewind.
const jumped = left.filter(
  (i) => (live_samples[i].trackLeft ?? 0) > (live_samples[i - 1].trackLeft ?? 0) + 1
);
check(
  "a block leaving does not move the line: it is paid for, not jumped over",
  left.length > 0 && jumped.length === 0,
  jumped.length === 0
    ? `${left.length} blocks left, none moved the line`
    : `${jumped.length} of ${left.length} moved the line right, worst ${Math.round(Math.max(...jumped.map((i) => (live_samples[i].trackLeft ?? 0) - (live_samples[i - 1].trackLeft ?? 0))))}px`
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

// ------------------------------------------------------- 6. back to line one
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
/// THE SAME ROW, NOW LEGIBLE FOR SURE (a thought that has stopped draws a plain
/// first line, no mask, no drag). It is the control for the number above: both
/// should be in the same league, and the blank version was two orders of magnitude
/// away from it.
const settledInk = (await page.evaluate((sel) => {
  const el = document.querySelector(sel);
  const b = el?.getBoundingClientRect();
  return b ? { x: Math.round(b.left), y: Math.round(b.top), width: Math.round(b.width), height: Math.round(b.height) } : null;
}, ROW));
const inkSettled = settledInk === null ? 0 : await inkOf(settledInk);
check(
  "and it is painted then too (the control for the number above)",
  inkSettled >= 300,
  `${inkSettled} ink pixels settled vs ${inkWhileRunning} while running`,
);

await page.screenshot({ path: path.join(EVIDENCE, "02-after-thinking.png") });

// ------------------------------------------------------------ 7. still opens
// THE READER'S CLICK IS STILL THE ONE THING THAT OPENS IT, and it opens the same
// disclosure it always did -- the fix removed the automatic half, not the panel.
await page.click(ROW);
const opened = await until(async () => {
  const row = await readRow();
  // THE PANEL ANIMATES OPEN, so "not folded" is true for a frame BEFORE there is any
  // height in it: what to wait for is the open state WITH its height, or the criterion
  // below is a race with the animation.
  return row !== null && row.contentState === "open" && row.contentHeight > 0 ? row : null;
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

// ------------------------------------------------------------ 8. after reload
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