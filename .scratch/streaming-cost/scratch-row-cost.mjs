// WHAT ONE TOKEN COSTS THE ROW -- a diagnostic, not a gate.
//
//   node scripts/dev.mjs --scripted .scratch/streaming-cost/fixture-long.json --ui-port 5394
//   node .scratch/streaming-cost/scratch-row-cost.mjs http://127.0.0.1:5394/ [bytes-per-second]
//
// WHY IT EXISTS. Ticket 01 (`.scratch/streaming-cost/issues/01-...md`) rests on one
// claim: the row's own work per token grows with the thought (an L-character line is
// re-laid-out and re-measured for every token), so a long thought makes the page janky
// and holding a BLOCK of it makes that cost constant. This script measures that claim
// and prints the numbers. It is NOT a criterion: a threshold here would be a threshold
// on the machine, and the walkthrough's criteria are the ones about what a reader sees.
//
// WHAT IT MEASURES. Chrome's own `long-animation-frame` entries -- one per frame long
// enough to matter, each with its total duration and its BLOCKING duration (how long the
// main thread was not free for input), plus the scripts attributed to it. The row's
// effect is a script, so the blocking time of the streaming frames is the number the
// claim is about. And the wall clock, and the frames drawn per second, which is what a
// reader feels.
//
// THE SAME FIXTURE HAS TO BE RUN ON BOTH VERSIONS, and the second argument is how: the
// stream is what makes this measurable at all (`harness.fake` emits the whole thought as
// fast as the socket takes it, which is a burst of renders React batches; a throttle
// makes it arrive spread out, one commit per frame, which is what a real vendor's stream
// does and what the per-token cost is about).
import fs from "node:fs";
import path from "node:path";
import { execSync } from "node:child_process";
import { fileURLToPath, pathToFileURL } from "node:url";

const HERE = path.dirname(fileURLToPath(import.meta.url));
const url = process.argv[2] ?? "http://localhost:5394/";
const throttle = Number(process.argv[3] ?? 0); // 0 = no throttling at all

/// Playwright is wherever npm put it -- the same search `thinking-row-tail/walkthrough.mjs`
/// does, and for the same two reasons (`file://` URLs on Windows, and a copy that is
/// installed without its browsers).
const playwrightEntries = (() => {
  const candidates = [
    path.join(execSync("npm root -g", { encoding: "utf8" }).trim(), "playwright", "index.mjs"),
  ];
  const npxRoot = path.join(execSync("npm config get cache", { encoding: "utf8" }).trim(), "_npx");
  if (fs.existsSync(npxRoot)) {
    for (const entry of fs.readdirSync(npxRoot)) {
      candidates.push(path.join(npxRoot, entry, "node_modules", "playwright", "index.mjs"));
    }
  }
  candidates.push(path.join(HERE, "..", "..", "ui", "node_modules", "playwright", "index.mjs"));
  return candidates.filter((candidate) => fs.existsSync(candidate));
})();

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
page.on("pageerror", (error) => console.log(`page error: ${error.message}`));
await page.goto(url);

if (throttle > 0) {
  const cdp = await page.context().newCDPSession(page);
  await cdp.send("Network.enable");
  await cdp.send("Network.emulateNetworkConditions", {
    offline: false,
    latency: 5,
    downloadThroughput: throttle,
    uploadThroughput: throttle,
  });
}

/// THE FIXTURE, read the same way the walkthrough reads its own: the length is the
/// independent variable of this measurement, so it is printed rather than assumed.
const script = JSON.parse(fs.readFileSync(path.join(HERE, "fixture-long.json"), "utf8"));
const thought = script.turns[0].reasoning.replace(/\s+/g, " ").trim();

const ROW = '[data-slot="reasoning-trigger-tail"]';
await page.waitForSelector("textarea");
await page.evaluate(() => {
  window.__long = [];
  new PerformanceObserver((list) => {
    for (const entry of list.getEntries()) {
      window.__long.push({
        duration: entry.duration,
        blocking: entry.blockingDuration,
        scripts: entry.scripts.map((script) => script.duration),
      });
    }
  }).observe({ type: "long-animation-frame", buffered: true });
  window.__frames = 0;
  const tick = () => {
    window.__frames += 1;
    requestAnimationFrame(tick);
  };
  requestAnimationFrame(tick);
});

/// THE ROW'S OWN MEASUREMENT, attributed: `getBoundingClientRect` is what forces the
/// line to be laid out, so patching it and timing the reads that come from inside the
/// row's window is the row's layout cost -- and nothing else's. It is patched from
/// HERE rather than instrumented in the source, so the two versions being compared run
/// the same product code.
await page.evaluate((rowSelector) => {
  window.__row = { count: 0, ms: 0, lengths: [], costs: [] };
  const rect = Element.prototype.getBoundingClientRect;
  Element.prototype.getBoundingClientRect = function () {
    const row =
      typeof this.closest === "function" ? this.closest(rowSelector) : null;
    if (row === null) return rect.call(this);
    const before = performance.now();
    const out = rect.call(this);
    const cost = performance.now() - before;
    const track = row.firstElementChild;
    window.__row.count += 1;
    window.__row.ms += cost;
    window.__row.lengths.push(track === null ? 0 : (track.textContent ?? "").length);
    window.__row.costs.push(cost);
    return out;
  };
}, ROW);

const started = Date.now();
await page.fill("textarea", "想一下再答。");
await page.press("textarea", "Enter");
/// THE ROW IS LIVE EXACTLY WHILE THE TOKENS ARRIVE (a thought that has stopped draws a
/// plain first line and no tail element at all), so its disappearance is the end of what
/// is being measured -- not a clock someone guessed.
await page.waitForSelector(ROW, { timeout: 60000 });
await page.waitForSelector(ROW, { state: "detached", timeout: 900000 });
const elapsed = Date.now() - started;

const measured = await page.evaluate(() => {
  const long = window.__long;
  const row = window.__row;
  const sum = (of) => long.reduce((total, entry) => total + of(entry), 0);
  /// THE ROW'S COST, AT THE SHORT END AND AT THE LONG END. The claim is that it grows
  /// with the line, so the mean cost of the SHORTEST quarter of the reads is compared
  /// with the mean of the LONGEST quarter -- a mean over the whole run would hide it.
  const sorted = row.lengths.map((length, index) => ({ length, cost: row.costs[index] }));
  sorted.sort((a, b) => a.length - b.length);
  const quarter = Math.max(1, Math.floor(sorted.length / 4));
  const mean = (slice) =>
    slice.length === 0 ? 0 : slice.reduce((total, one) => total + one.cost, 0) / slice.length;
  return {
    frames: window.__frames,
    count: long.length,
    duration: sum((entry) => entry.duration),
    blocking: sum((entry) => entry.blocking),
    worst: Math.max(0, ...long.map((entry) => entry.blocking)),
    scripts: sum((entry) => entry.scripts.reduce((total, one) => total + one, 0)),
    row: {
      count: row.count,
      ms: row.ms,
      shortest: { length: sorted[0]?.length ?? 0, cost: mean(sorted.slice(0, quarter)) },
      longest: {
        length: sorted[sorted.length - 1]?.length ?? 0,
        cost: mean(sorted.slice(-quarter)),
      },
    },
  };
});

const round = (n) => Math.round(n);
const ms = (n) => `${n.toFixed(1)} ms`;
console.log(`thought                ${thought.length} characters`);
console.log(`throttle               ${throttle === 0 ? "none (a burst)" : `${throttle} bytes/s`}`);
console.log(`wall clock             ${elapsed} ms`);
console.log(`frames drawn           ${measured.frames} (${(measured.frames / (elapsed / 1000)).toFixed(1)}/s)`);
console.log("the row's own measurements (the patched getBoundingClientRect):");
console.log(`  reads                ${measured.row.count}`);
console.log(`  time in them         ${round(measured.row.ms)} ms total, ${ms(measured.row.ms / Math.max(1, measured.row.count))} each`);
console.log(
  `  at a line of         ${measured.row.shortest.length} chars: ${ms(measured.row.shortest.cost)} each`
);
console.log(
  `  at a line of         ${measured.row.longest.length} chars: ${ms(measured.row.longest.cost)} each`
);
console.log(`long frames            ${measured.count}`);
console.log(`  duration             ${round(measured.duration)} ms total`);
console.log(`  blocking             ${round(measured.blocking)} ms total, worst ${round(measured.worst)} ms`);
console.log(`  script time in them  ${round(measured.scripts)} ms`);
await browser.close();
