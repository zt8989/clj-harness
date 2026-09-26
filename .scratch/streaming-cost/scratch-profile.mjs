// WHERE ONE COMMIT'S TIME GOES, and what the reasoning rows cost it -- a diagnostic, not a gate.
//
//   node scripts/dev.mjs --scripted .scratch/thinking-row-tail/script.json          # note its port
//   # then, in ui/: VITE_AGENT_URL=http://127.0.0.1:<its port> npx vite build --minify false --outDir dist-profile
//   #             npx vite preview --outDir dist-profile --port 5175 --strictPort
//   node .scratch/streaming-cost/scratch-profile.mjs <harness-url> http://localhost:5175/ [turns]
//
// WHY IT EXISTS. Every measurement so far says this page's cost is PER COMMIT -- one store update
// per frame -- and that it grows with how long the conversation is (a one-message thread: ~10 ms a
// commit; a real session of ~400 messages: ~34 ms, which is 29 fps). WHAT inside that commit is
// expensive is not something a stopwatch can say, and a guess is what this file exists to replace:
// the profile that came out of the FIRST version of this script (one message on screen) said
// "nothing of ours is hot", and that was the fixture talking -- every `ReasoningBlock` row
// subscribed to `s.thread.messages`, so with N rows the page did N whole row re-renders per
// update, and N was ONE in that fixture.
//
// SO THE CONVERSATION HAS TO BE LONG, and it is grown by sending: `harness.fake` replays its
// script once per send, so `turns` sends make a `turns`-turn conversation. Two facts decide HOW:
//
//   * PLANTING A RECORD ON DISK DOES NOT WORK: the sidebar lists the harness's own store of
//     sessions, and a session the harness already holds in memory does not re-read its file (a
//     16 MB record copied over one that had just run came back as the two messages that ran).
//   * GROWING AND PROFILING WANT DIFFERENT BUILDS: the unminified page drains a burst at ~110
//     characters a second against ~500 for the minified one, so the turns are sent through the
//     HARNESS'S OWN page and only the profile runs on the unminified preview. Both look at the
//     same session, because the session lives in the harness.
//
// `turns 0` SKIPS THE GROWING, which is how one grown conversation is measured twice: grow once
// on the build that is to be the "before", profile it, rebuild the preview from the "after", and
// run this again with no growing at all. The newest row the sidebar lists is the one that was
// grown (the profile page starts on a session of its own and does not remember that one).
import fs from "node:fs";
import path from "node:path";
import { execSync } from "node:child_process";
import { fileURLToPath, pathToFileURL } from "node:url";

const HERE = path.dirname(fileURLToPath(import.meta.url));
const [growUrl, profileUrl, turnsArg] = process.argv.slice(2);
if (!growUrl || !profileUrl) {
  console.log("usage: scratch-profile.mjs <harness-url> <unminified-ui-url> [turns]");
  process.exit(1);
}
const turns = Number(turnsArg ?? 16);

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
const MESSAGES = '[data-slot="aui_assistant-message-root"]';

/// A SEND THAT WAITS FOR THE APP, AND INSISTS. A composer on screen before the catalogs land
/// accepts a message that starts no run at all (measured: the server wrote a record and the page
/// drew no row), so every send settles first -- and a send that shows no run in fifteen seconds is
/// settled again and repeated, once. The textbox is found BY ROLE: this page has two of them (the
/// composer and an autosize mirror), and a bare `textarea` selector is a strict-mode violation.
async function send(page) {
  await page.waitForTimeout(2500);
  const before = await page.locator(MESSAGES).count();
  await page.getByRole("textbox", { name: "消息输入框" }).fill("想一下再答。");
  await page.getByRole("textbox", { name: "消息输入框" }).press("Enter");
  /// A MESSAGE IS THE PROOF THE TURN RAN, and the stop control going away is the proof it
  /// finished. Waiting for the STREAMING ROW (what this script's earlier versions did) never
  /// returns for a turn that has no reasoning in it -- and a tool-call turn has none, which is
  /// exactly the turn that gives a conversation its steps.
  await page.waitForFunction(
    (was) => document.querySelectorAll('[data-slot="aui_assistant-message-root"]').length > was,
    before,
    { timeout: 60000 }
  );
  for (let waited = 0; waited < 180000; waited += 250) {
    if ((await page.locator('[data-slot="session-stop"]').count()) === 0) return;
    await page.waitForTimeout(250);
  }
}

/// ---- 1. GROW, on the page the harness serves itself (the fast, minified one).
if (turns > 0) {
  const growing = await browser.newPage({ viewport: { width: 1400, height: 900 } });
  growing.on("pageerror", (error) => console.log(`grow page error: ${error.message}`));
  await growing.goto(growUrl);
  await growing.waitForSelector("textarea");
  const growStarted = Date.now();
  for (let turn = 1; turn <= turns; turn += 1) {
    await send(growing);
    if (turn % 4 === 0) {
      console.log(`grown           ${turn} turns, ${await growing.locator(MESSAGES).count()} assistant messages`);
    }
  }
  console.log(
    `conversation    ${await growing.locator(MESSAGES).count()} assistant messages over ${turns} turns (${Math.round((Date.now() - growStarted) / 1000)} s)`
  );
  await growing.close();
}

/// ---- 2. PROFILE ONE MORE TURN, on the unminified build, in that same conversation.
const page = await browser.newPage({ viewport: { width: 1400, height: 900 } });
page.on("pageerror", (error) => console.log(`page error: ${error.message}`));
await page.goto(profileUrl);
await page.waitForSelector("textarea");
await page.waitForTimeout(1500);
/// THE PAGE REMEMBERS A SESSION OF ITS OWN and that row is the disabled one, so the grown
/// conversation is waited for, and the newest ENABLED row -- the one that was just grown -- is
/// clicked once if it does not arrive by itself.
let messages = await page.locator(MESSAGES).count();
const target = turns > 0 ? Math.max(8, turns) : 8;
for (let waited = 0; waited < 240000 && messages < target; waited += 500) {
  if (waited === 8000) {
    const row = page.locator('[data-slot="thread-list-item-trigger"]:not([disabled])').first();
    if ((await row.count()) > 0) await row.click();
  }
  await page.waitForTimeout(500);
  messages = await page.locator(MESSAGES).count();
}
console.log(`on screen       ${messages} assistant messages (wanted ${target})`);
if (messages < 8) {
  console.log("the grown conversation did not open -- nothing below means anything");
  await browser.close();
  process.exit(1);
}
await page.waitForTimeout(3000); // let the load settle: the profile is about the stream

const cdp = await page.context().newCDPSession(page);
await cdp.send("Profiler.enable");
await cdp.send("Profiler.setSamplingInterval", { interval: 200 });
await cdp.send("Profiler.start");

const started = Date.now();
await send(page);
const elapsed = Date.now() - started;

const { profile } = await cdp.send("Profiler.stop");
await browser.close();

/// WHAT THE SAMPLES SAY, grouped by function: each sample owns the time until the next one, so
/// "self time" is what was on the stack at that instant -- which is what a profile is for. The URL
/// keeps two same-named copies apart (ours and a library's).
const byId = new Map(profile.nodes.map((node) => [node.id, node]));
const cost = new Map();
let sampled = 0;
let idle = 0;
for (let index = 0; index < profile.samples.length; index += 1) {
  const node = byId.get(profile.samples[index]);
  const micros = profile.timeDeltas[index + 1] ?? 0;
  if (node === undefined) continue;
  const frame = node.callFrame;
  const where = `${frame.functionName || "(anonymous)"}  ${frame.url.replace(/^.*\//, "")}:${frame.lineNumber + 1}`;
  cost.set(where, (cost.get(where) ?? 0) + micros);
  sampled += micros;
  if ((frame.functionName || "") === "(idle)") idle += micros;
}

console.log(`stream          ${elapsed} ms wall clock over one scripted turn`);
console.log(
  `profile         ${(sampled / 1000).toFixed(0)} ms sampled, ${(idle / 1000).toFixed(0)} ms idle, ` +
    `${((sampled - idle) / 1000).toFixed(0)} ms the page's own work\n`
);
console.log("self time, top 22:");
for (const [where, micros] of [...cost.entries()].sort((a, b) => b[1] - a[1]).slice(0, 22)) {
  console.log(`  ${(micros / 1000).toFixed(1).padStart(8)} ms  ${((micros / sampled) * 100).toFixed(1).padStart(5)}%  ${where}`);
}
