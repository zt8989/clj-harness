// THE INSTRUMENT the render-cost readings are taken with, as a command
// (`.scratch/conversation-render-cost/`).
//
//   node .scratch/conversation-render-cost/evidence/measure-run.mjs --url http://127.0.0.1:4811 --repeats 3
//
// WHY IT IS A SCRIPT AND NOT A BROWSER TAB SOMEBODY OPENS. The 2026-09-30 reading was taken by
// hand, through a borrowed tab and a CDP console; the numbers in `spec.md` came out of it and are
// the ones this is compared against. What a by-hand run cannot do is repeat: two runs of ONE
// script on ONE page read 29.7 and 48.6 ms/s of script, and a single reading inside that spread
// decides nothing. So the same instrument -- CDP `Performance.getMetrics`, read as a DELTA around
// one stream, plus the frame count off the socket -- is driven from here, N times, in one command.
//
// IT IS NOT A TEST AND NOT PART OF THE BUILD. It needs `playwright` from somewhere, it drives a
// real browser against a running `node scripts/dev.mjs --scripted`, and nothing in `ui/` or the
// Clojure side imports it. It is a walkthrough with a ruler, kept in the repo so the ruler is the
// same one next time.
//
// THE UNITS ARE THE TRAP. Every time the Performance domain reports is in SECONDS; the table in
// `spec.md` is in milliseconds per second. The first cut of this file divided seconds by elapsed
// milliseconds and printed 0.7 ms/s for a stream that had spent 37 seconds in JS.
//
// WHAT ONE RUN MEASURES:
//
//   * `ScriptDuration` / `TaskDuration` -- the cost of keeping up with the stream, which is the
//     number ticket 01 is about. Read as a rate, because the stream runs for tens of seconds and
//     a raw total says nothing about a rate.
//   * `RecalcStyleDuration` / `LayoutDuration` -- the control. `spec.md` says these are low single
//     digit ms/s, and a change that moved JS into either of them has not made anything cheaper.
//   * the IDLE window after the stream -- a second control, and the spec's third row: a page that
//     has stopped receiving frames should cost almost nothing.
//   * the frames the page actually received, off Playwright's `websocket` event rather than from a
//     patched `WebSocket` in the page: the point is to know the stream arrived at all.
//   * `rafPerSecond`, sampled DURING the stream: `lib/coalesce.ts` hands its frames over on an
//     animation frame, so a page drawing sixty times a second and a page drawing once are doing
//     different amounts of work per delivery, and two readings are only comparable if this one is
//     the same. It is sampled while streaming because a fresh document has nothing to composite
//     yet -- sampled after load it read 1/s on a page that streams perfectly well.
//   * with `--profile`, a V8 CPU profile over the same window: self-time by function and by module
//     (the module needs `npx vite build --minify false`, whose `//#region` comments are what a
//     sample's line number is attributed to).
//
// THE STREAM IS ALLOWED TO FINISH ON ITS OWN. The wait is for the page's own "still writing" mark
// to go away, not for a fixed number of seconds, so a slower machine measures a longer stream
// rather than a truncated one -- and the rate is what is compared.
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

import {
  MESSAGES,
  STOP,
  WORKING,
  argOf,
  chromium,
  forgetSession,
} from "./playwright.mjs";

const HERE = path.dirname(fileURLToPath(import.meta.url));

const URL = argOf("--url", "http://127.0.0.1:4811");
const REPEATS = Number(argOf("--repeats", "3"));
const OUT = argOf("--out", "");
const PROMPT = "写一个很长的回答，越详细越好。";
const IDLE_MS = 3000;
const HEADED = process.argv.includes("--headed");
const PROFILE = process.argv.includes("--profile");

const at = (rows, name) => rows.find((r) => r.name === name)?.value ?? 0;

/// WHERE THE TIME WENT, from a V8 CPU profile: self-time per function, biggest first. `samples[i]`
/// names a node and `timeDeltas[i]` (microseconds) is how long it ran before the next sample, so
/// summing the deltas per node gives self-time -- time in that function and no callee.
function topFunctions(profile, limit = 20) {
  const byId = new Map(profile.nodes.map((node) => [node.id, node]));
  const totals = new Map();
  let total = 0;
  profile.samples.forEach((id, index) => {
    const micros = profile.timeDeltas[index] ?? 0;
    total += micros;
    const frame = byId.get(id)?.callFrame;
    if (!frame) return;
    const where = `${(frame.url ?? "").split("/").slice(-2).join("/")}:${frame.lineNumber + 1}`;
    const key = `${frame.functionName || "(anonymous)"}  @ ${where}`;
    totals.set(key, (totals.get(key) ?? 0) + micros);
  });
  const top = [...totals]
    .sort((a, b) => b[1] - a[1])
    .slice(0, limit)
    .map(([name, micros]) => ({ name, ms: Number((micros / 1000).toFixed(1)), share: Number((micros / total).toFixed(3)) }));
  return { sampleMs: Number((total / 1000).toFixed(1)), top };
}

/// WHERE THE TIME WENT, BY MODULE, which is the question the function list only hints at: even an
/// unminified build answers with the names of leaf functions rather than the package they belong
/// to. The unminified Vite build writes a `//#region <path>` comment before each module, so a
/// sample's line number can be attributed to the module it is standing in.
function regionsOf(bundleText) {
  const bounds = [];
  bundleText.split("\n").forEach((line, index) => {
    const region = /^\/\/#region (.+)$/.exec(line);
    if (region) bounds.push([index, region[1]]);
  });
  return bounds;
}

/// The coarse bucket a region belongs to: which package, or which of our own files.
function packageOf(region) {
  const nodeModules = region.indexOf("node_modules/");
  if (nodeModules !== -1) {
    const parts = region.slice(nodeModules + "node_modules/".length).split("/");
    return parts[0].startsWith("@") ? `${parts[0]}/${parts[1]}` : parts[0];
  }
  const ours = region.match(/src\/(.+)$/);
  return ours ? `ours: ${ours[1]}` : region;
}

function byRegion(profile, bounds, limit = 25) {
  const packageAt = (line) => {
    let low = 0;
    let high = bounds.length - 1;
    let found = null;
    while (low <= high) {
      const mid = (low + high) >> 1;
      if (bounds[mid][0] <= line) {
        found = bounds[mid][1];
        low = mid + 1;
      } else high = mid - 1;
    }
    return found === null ? "(before the first module)" : packageOf(found);
  };
  const byId = new Map(profile.nodes.map((node) => [node.id, node]));
  const totals = new Map();
  let total = 0;
  profile.samples.forEach((id, index) => {
    const micros = profile.timeDeltas[index] ?? 0;
    total += micros;
    const frame = byId.get(id)?.callFrame;
    const region = frame === undefined ? "(native)" : packageAt(frame.lineNumber);
    totals.set(region, (totals.get(region) ?? 0) + micros);
  });
  return [...totals]
    .sort((a, b) => b[1] - a[1])
    .slice(0, limit)
    .map(([name, micros]) => ({ name, ms: Number((micros / 1000).toFixed(1)), share: Number((micros / total).toFixed(3)) }));
}

const browser = await chromium().launch({ headless: !HEADED, channel: "chromium" });
const context = await browser.newContext({ viewport: { width: 1280, height: 720 } });
await forgetSession(context);

const page = await context.newPage();
const cdp = await context.newCDPSession(page);
await cdp.send("Performance.enable");
await cdp.send("Profiler.enable").catch(() => {});
const read = async () => {
  // NODES ARE COUNTED AFTER A COLLECTION, or the metric answers "how much has this renderer
  // allocated since it started" rather than "how big is the page": it grew 827 -> 1536 -> 1777
  // across three runs of one unchanged page, which is the previous runs' detached React trees
  // waiting for a collection nobody asked for.
  await cdp.send("HeapProfiler.collectGarbage").catch(() => {});
  return (await cdp.send("Performance.getMetrics")).metrics;
};

// THE FRAMES COME OFF THE SOCKET THE PAGE ALREADY HAS. The listener is attached BEFORE the first
// navigation, because the app opens its event socket while it is loading and a listener added
// inside `once()` would be watching a socket that already exists -- it counted 0 frames for a
// 19k-character stream, which is a check that can never fail.
let capturing = false;
let frames = 0;
let frameBytes = 0;
page.on("websocket", (ws) => {
  ws.on("framereceived", (payload) => {
    if (!capturing) return;
    frames += 1;
    frameBytes += typeof payload.payload === "string" ? payload.payload.length : 0;
  });
});

/// One stream, measured. Ends when the page says the turn is over.
async function once(index) {
  await page.goto("about:blank");
  await page.goto(URL, { waitUntil: "domcontentloaded" });
  await page.waitForSelector(".aui-composer-input", { timeout: 30_000 });
  await page.waitForTimeout(1500);
  const messagesBefore = await page.locator(MESSAGES).count();
  if (messagesBefore !== 0) {
    throw new Error(`the conversation was not empty (${messagesBefore} messages) -- the reading would be of the wrong page`);
  }

  frames = 0;
  frameBytes = 0;
  capturing = true;

  const before = await read();
  const startedAt = Date.now();

  // ONE PROFILE, from the first run: a profile of the whole stream is tens of megabytes of samples
  // and the shape does not change between runs.
  if (PROFILE && index === 1) await cdp.send("Profiler.start");

  await page.fill(".aui-composer-input", PROMPT);
  await page.press(".aui-composer-input", "Enter");

  // HOW OFTEN THE BROWSER DRAWS, SAMPLED WHILE THE STREAM IS RUNNING -- which is the only time the
  // number means anything.
  const rafSample = page.evaluate(
    () =>
      new Promise((resolve) => {
        let drawn = 0;
        const t0 = performance.now();
        const tick = () => {
          drawn += 1;
          if (performance.now() - t0 < 2000) requestAnimationFrame(tick);
          else resolve(drawn / 2);
        };
        requestAnimationFrame(tick);
      }),
  );

  // Streaming has to have STARTED before its end can be waited for: a wait for the dot to go away
  // that begins before the dot exists passes at once and measures nothing.
  await page.waitForSelector(WORKING, { timeout: 60_000 });
  await page.waitForFunction(
    ([working, stop]) => document.querySelector(working) === null && document.querySelector(stop) === null,
    [WORKING, STOP],
    { timeout: 300_000, polling: 250 },
  );
  const rafPerSecond = await rafSample;

  const streamedMs = Date.now() - startedAt;
  const after = await read();

  let profileSummary = null;
  if (PROFILE && index === 1) {
    const { profile } = await cdp.send("Profiler.stop");
    const bundleUrl = await page.evaluate(() =>
      [...document.scripts].map((script) => script.src).find((src) => src.endsWith(".js")),
    );
    const bundle = await (await fetch(bundleUrl)).text();
    profileSummary = { ...topFunctions(profile), byRegion: byRegion(profile, regionsOf(bundle)) };
  }

  await page.waitForTimeout(IDLE_MS);
  const idle = await read();
  capturing = false;

  const answerChars = await page.evaluate((selector) => {
    const roots = [...document.querySelectorAll(selector)];
    return roots.reduce((sum, root) => sum + (root.textContent ?? "").length, 0);
  }, MESSAGES);

  // SECONDS -> MILLISECONDS PER SECOND OF WALL CLOCK, over the window between two snapshots.
  const rate = (from, to, windowMs, name) =>
    Number((((at(to, name) - at(from, name)) * 1000 * 1000) / windowMs).toFixed(1));

  return {
    run: index,
    rafPerSecond,
    profile: profileSummary,
    streamedSeconds: Number((streamedMs / 1000).toFixed(1)),
    frames,
    framesPerSecond: Number((frames / (streamedMs / 1000)).toFixed(1)),
    frameBytes,
    answerChars,
    streamingMsPerSecond: {
      task: rate(before, after, streamedMs, "TaskDuration"),
      script: rate(before, after, streamedMs, "ScriptDuration"),
      style: rate(before, after, streamedMs, "RecalcStyleDuration"),
      layout: rate(before, after, streamedMs, "LayoutDuration"),
    },
    idleMsPerSecond: {
      task: rate(after, idle, IDLE_MS, "TaskDuration"),
      script: rate(after, idle, IDLE_MS, "ScriptDuration"),
    },
    streamedTotalMs: {
      task: Number((at(after, "TaskDuration") * 1000).toFixed(1)),
      script: Number((at(after, "ScriptDuration") * 1000).toFixed(1)),
    },
    nodes: { before: at(before, "Nodes"), after: at(after, "Nodes") },
    listeners: { before: at(before, "JSEventListeners"), after: at(after, "JSEventListeners") },
  };
}

const runs = [];
for (let index = 1; index <= REPEATS; index += 1) runs.push(await once(index));

await browser.close();

const median = (of) => {
  const sorted = runs.map(of).sort((a, b) => a - b);
  return sorted[Math.floor(sorted.length / 2)];
};
const report = {
  url: URL,
  rafPerSecond: median((r) => r.rafPerSecond),
  median: {
    streamedSeconds: median((r) => r.streamedSeconds),
    scriptMsPerSecond: median((r) => r.streamingMsPerSecond.script),
    taskMsPerSecond: median((r) => r.streamingMsPerSecond.task),
    styleMsPerSecond: median((r) => r.streamingMsPerSecond.style),
    layoutMsPerSecond: median((r) => r.streamingMsPerSecond.layout),
    idleScriptMsPerSecond: median((r) => r.idleMsPerSecond.script),
  },
  runs,
};

console.log(JSON.stringify(report, null, 2));
if (OUT !== "") fs.writeFileSync(path.resolve(HERE, OUT), `${JSON.stringify(report, null, 2)}\n`, "utf8");
