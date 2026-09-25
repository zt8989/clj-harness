// WHERE ONE COMMIT'S TIME GOES -- a diagnostic, not a gate.
//
//   node scripts/dev.mjs --scripted .scratch/thinking-row-tail/script.json     # note its port
//   # then, in ui/: VITE_AGENT_URL=http://127.0.0.1:<its port> npx vite build --minify false --outDir dist-profile
//   #             npx vite preview --outDir dist-profile --port 5175 --strictPort
//   node .scratch/streaming-cost/scratch-profile.mjs http://localhost:5175/
//
// WHY IT EXISTS. Every measurement so far says this page's cost is PER COMMIT -- one store update
// per frame -- and that it grows with how long the conversation is (a one-message thread: ~10 ms
// a commit; a real session of ~400 messages: ~34 ms). WHAT inside that commit is expensive is
// not something a stopwatch can say, and the last guess (the row's own layout) was wrong by an
// order of magnitude -- so this samples the JavaScript instead: Chrome's own CPU profile over a
// scripted stream, with the page built UNMINIFIED so the function names mean something.
//
// NOT `npm run dev`: that build's `StrictMode` double-invokes every render and would double the
// very number this is trying to place. `--minify false` on a production build keeps React's
// production behaviour and readable stack frames.
//
// A LONG CONVERSATION WAS NOT WORTH THE MACHINERY, and the attempts are worth recording so the
// next person does not repeat them: planting a real 16 MB record on disk does not work any more
// (the sidebar lists the harness's own store of sessions, and a session the harness already
// holds in memory does not re-read its file), and growing one by sending takes ~25 s a turn on an
// unminified build (~6 s on the minified one) because the page -- not the socket -- is what
// drains a burst. The baseline is the part worth seeing first anyway: it is most of the cost even
// when the conversation is long.
import fs from "node:fs";
import path from "node:path";
import { execSync } from "node:child_process";
import { fileURLToPath, pathToFileURL } from "node:url";

const HERE = path.dirname(fileURLToPath(import.meta.url));
const url = process.argv[2] ?? "http://localhost:5175/";

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

const ROW = '[data-slot="reasoning-trigger-tail"]';
const page = await browser.newPage({ viewport: { width: 1400, height: 900 } });
page.on("pageerror", (error) => console.log(`page error: ${error.message}`));
await page.goto(url);
await page.waitForSelector("textarea");
/// A SEND THAT WAITS FOR THE APP: a composer on screen before the catalogs land accepts a message
/// that starts no run at all (measured -- the server wrote a record and the page drew no row).
await page.waitForTimeout(2000);

const cdp = await page.context().newCDPSession(page);
await cdp.send("Profiler.enable");
await cdp.send("Profiler.setSamplingInterval", { interval: 200 });
await cdp.send("Profiler.start");

const started = Date.now();
await page.fill("textarea", "想一下再答。");
await page.press("textarea", "Enter");
await page.waitForSelector(ROW, { timeout: 90000 });
await page.waitForSelector(ROW, { state: "detached", timeout: 300000 });
const elapsed = Date.now() - started;

const { profile } = await cdp.send("Profiler.stop");
await browser.close();

/// WHAT THE SAMPLES SAY, grouped by function: each sample owns the time until the next one, so
/// "self time" is what was on the stack at that instant -- which is what a profile is for. The
/// URL keeps two same-named copies apart (ours and a library's).
const byId = new Map(profile.nodes.map((node) => [node.id, node]));
const cost = new Map();
let sampled = 0;
for (let index = 0; index < profile.samples.length; index += 1) {
  const node = byId.get(profile.samples[index]);
  const micros = profile.timeDeltas[index + 1] ?? 0;
  if (node === undefined) continue;
  const frame = node.callFrame;
  const where = `${frame.functionName || "(anonymous)"}  ${frame.url.replace(/^.*\//, "")}:${frame.lineNumber + 1}`;
  cost.set(where, (cost.get(where) ?? 0) + micros);
  sampled += micros;
}

console.log(`stream          ${elapsed} ms wall clock over one scripted turn`);
console.log(`profile         ${(sampled / 1000).toFixed(0)} ms of samples\n`);
console.log("self time, top 22:");
for (const [where, micros] of [...cost.entries()].sort((a, b) => b[1] - a[1]).slice(0, 22)) {
  console.log(`  ${(micros / 1000).toFixed(1).padStart(8)} ms  ${((micros / sampled) * 100).toFixed(1).padStart(5)}%  ${where}`);
}
