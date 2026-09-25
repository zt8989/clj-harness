// HOW MANY TIMES THE PAGE IS RE-RENDERED PER DELTA -- a diagnostic, not a gate.
//
//   node scripts/dev.mjs --scripted .scratch/streaming-cost/fixture-long.json
//   node .scratch/streaming-cost/scratch-render-cost.mjs http://127.0.0.1:<port>/
//
// WHY IT EXISTS. Ticket 02 (`.scratch/streaming-cost/issues/02-...md`) holds one claim: each
// WebSocket message is its own task, and a React update raised outside React's own handlers
// is batched only within its task, so a vendor streaming faster than the browser draws costs
// ONE WHOLE RE-RENDER PER TOKEN -- of the entire conversation. `lib/coalesce.ts` holds the
// frames and hands them over once per animation frame instead. This script counts the
// re-renders on both sides of that change.
//
// HOW A RE-RENDER IS COUNTED, and why it is a proxy rather than a number: React writes to
// the DOM synchronously when it commits, and a `MutationObserver`'s callback runs once per
// microtask checkpoint AFTER those writes -- so ONE CALLBACK IS ONE COMMIT. What the records
// say is not read: a commit that touches anything at all (the streaming message, a spinner,
// a stripped timer) counts once, which is the same on both sides of this measurement.
//
// AND THE CLOCK. The fixture is run with NO throttle: `harness.fake` emits the whole thought
// as fast as the socket takes it, which is what a vendor's burst after a network stall looks
// like, and the only shape in which the page is ever behind. (With a slow stream the frames
// arrive further apart than a frame and there is nothing to fold -- the win is exactly the
// ratio between the stream's rate and the display's.)
import fs from "node:fs";
import path from "node:path";
import { execSync } from "node:child_process";
import { fileURLToPath, pathToFileURL } from "node:url";

const HERE = path.dirname(fileURLToPath(import.meta.url));
const url = process.argv[2] ?? "http://localhost:5394/";
const throttle = Number(process.argv[3] ?? 0); // 0 = the burst

/// Playwright is wherever npm put it: the same search the walkthroughs do, and for the same
/// two reasons (`file://` URLs on Windows, and a copy installed without its browsers).
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

/// THE FIXTURE, read the same way the walkthrough reads its own: the DELTA COUNT is what the
/// re-render count is measured against, and it is computed rather than assumed -- the
/// scripted provider emits five characters per frame (`thinking-row-tail/spec.md`).
const script = JSON.parse(fs.readFileSync(path.join(HERE, "fixture-long.json"), "utf8"));
const thought = script.turns[0].reasoning.replace(/\s+/g, " ").trim();
const deltas = Math.ceil(thought.length / 5);

const ROW = '[data-slot="reasoning-trigger-tail"]';
await page.waitForSelector("textarea");
await page.evaluate(() => {
  window.__commits = 0;
  new MutationObserver(() => {
    window.__commits += 1;
  }).observe(document.body, { childList: true, subtree: true, characterData: true });
  window.__long = [];
  new PerformanceObserver((list) => {
    for (const entry of list.getEntries()) {
      window.__long.push({
        duration: entry.duration,
        blocking: entry.blockingDuration,
        scripts: entry.scripts.map((script) => script.duration),
      });
    }
  }).observe({ type: "long-animation-frame", buffered: false });
});

const started = Date.now();
await page.fill("textarea", "想一下再答。");
await page.press("textarea", "Enter");
await page.waitForSelector(ROW, { timeout: 60000 });
await page.waitForSelector(ROW, { state: "detached", timeout: 900000 });
const elapsed = Date.now() - started;

const measured = await page.evaluate(() => {
  const long = window.__long;
  const sum = (of) => long.reduce((total, entry) => total + of(entry), 0);
  return {
    commits: window.__commits,
    count: long.length,
    blocking: sum((entry) => entry.blocking),
    worst: Math.max(0, ...long.map((entry) => entry.blocking)),
    scripts: sum((entry) => entry.scripts.reduce((total, one) => total + one, 0)),
  };
});

console.log(`thought                ${thought.length} characters, ${deltas} deltas`);
console.log(`throttle               ${throttle === 0 ? "none (a burst)" : `${throttle} bytes/s`}`);
console.log(`wall clock             ${elapsed} ms`);
console.log(`re-renders             ${measured.commits}  (${(measured.commits / deltas).toFixed(2)} per delta, ${(measured.commits / (elapsed / 1000)).toFixed(1)}/s)`);
console.log(`long frames            ${measured.count}, blocking ${Math.round(measured.blocking)} ms total, worst ${Math.round(measured.worst)} ms`);
console.log(`script time in them    ${Math.round(measured.scripts)} ms`);

await browser.close();
