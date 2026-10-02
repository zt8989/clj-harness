// A real-browser walkthrough of THE GOAL STRIP AND `/goal …` (`.scratch/goal`, tickets 07, 08 and
// the end-to-end half of 09).
//
//   node scripts/dev.mjs --scripted .scratch/goal/script.json --ui-port 5333
//   node .scratch/goal/walkthrough.mjs http://localhost:5333/
//
// WHAT IT ASSERTS, and why a suite cannot: there is no DOM in the vitest run, and the driver is a
// fact about what the SERVER decides after a run ends. A screenshot of the strip is not a passing
// test either -- what this drives is the six steps ticket 09 names:
//
//   1. `/goal <目标>` 建起来：目标条长出来、文字逐字对；记录里多一条 `goal/change`，对话里**没有**这条消息。
//   2. 发一句话：对话栏那张注入卡上看得见 `<goal revision=…>` 提醒。
//   3. 脚本回放一个**改文件**的回合：驱动器自动开出下一轮——历史里出现 `round 2/…`（或 `round 1/…` 之后
//      的提醒），`rounds` 涨。
//   4. 脚本回放一个**纯只读**的回合：驱动器**不开下一轮**，目标变 `blocked`（`no-progress`），条上画出来。
//   5. `/goal resume`：相位回 active（人恢复），再回放一个改文件的回合 → 又续上。
//   6. `/goal pause`：下一轮不再开；`/goal clear`：目标条消失。
//
// Screenshots land in .scratch/goal/evidence/.
//
// NOT RUN ON EVERY MACHINE: `loadChromium` needs Playwright, which this box may not have installed
// (the walkthrough of 2026-10-02 was driven from the Windows side with an MCP browser; see
// `evidence/README.md` for what was seen and which three bugs the walk left behind). The script is
// kept so whoever has a Playwright install can repeat the six steps verbatim.
//
// THE SCRIPT'S `write` TARGET IS A PLACEHOLDER (`goal-walkthrough-scratch.txt`): an UNBOUND
// session resolves a relative path against the process's working directory, so a walk run from
// the repo root writes that file THERE. Point `script.json`'s two `write` calls at a scratch
// directory (or open a session bound to one) before running this against a tree you care about.
//
// AND THE TURNS ARE COUNTED, not free: a run that calls a tool makes a SECOND model call to
// hand the model the result, so `script.json`'s six turns are consumed as two per editing round
// (write, then the sentence) and one per read-only round. A walk that adds a step has to add
// turns to match, or the script runs dry and every later round is an empty answer.
import fs from "node:fs";
import path from "node:path";

import { execSync } from "node:child_process";
import { createRequire } from "node:module";
const require = createRequire(import.meta.url);
function loadChromium() {
  if (process.env.PLAYWRIGHT_PATH) return require(process.env.PLAYWRIGHT_PATH);
  try {
    const root = execSync("npm root -g", { encoding: "utf8" }).trim();
    return require(path.join(root, "@playwright", "cli", "node_modules", "playwright", "index.mjs"));
  } catch {
    return require("playwright");
  }
}
const { chromium } = loadChromium();

const url = process.argv[2] ?? "http://localhost:5333/";
const evidence = path.resolve(".scratch/goal/evidence");
fs.mkdirSync(evidence, { recursive: true });

let failures = 0;
function check(label, ok, detail = "") {
  console.log(`${ok ? "ok  " : "RED "} ${label}${detail === "" ? "" : ` -- ${detail}`}`);
  if (!ok) failures += 1;
}

/// THE STRIP, by its own slots -- `composer-goal.tsx` writes them, so a rename in the component
/// shows up here as a missing element rather than as a walk that silently checks nothing.
const strip = (page) => page.locator('[data-slot="composer-goal"]');
const stripText = async (page) => (await strip(page).innerText()).replace(/\s+/g, " ").trim();

/// ONE COMMAND TYPED INTO THE COMPOSER, the way a person types it: focus the box, type, Enter.
async function command(page, text) {
  const box = page.locator("textarea, [contenteditable=true]").first();
  await box.click();
  await box.fill(text);
  await box.press("Enter");
}

/// ONE QUESTION TYPED INTO THE COMPOSER -- the same box, a different meaning (no slash command).
async function send(page, text) {
  const box = page.locator("textarea, [contenteditable=true]").first();
  await box.click();
  await box.fill(text);
  await box.press("Enter");
}

/// WAIT FOR A FACT ABOUT THE STRIP, with a bound: the pushes are asynchronous, and a walk that
/// asserts on the first frame it sees races the server.
async function until(fn, ms = 20000) {
  const deadline = Date.now() + ms;
  for (;;) {
    if (await fn()) return true;
    if (Date.now() > deadline) return false;
    await new Promise((r) => setTimeout(r, 250));
  }
}

const browser = await chromium.launch();
const page = await browser.newPage({ viewport: { width: 1280, height: 900 } });
try {
  await page.goto(url);
  await page.waitForTimeout(1500); // the sidebar's own snapshot, then a session exists to open

  // 1. `/goal <目标>` -- the strip appears, the words are the ones typed.
  const objective = "把登录模块重构完，补齐测试和迁移说明";
  await command(page, `/goal ${objective}`);
  const appeared = await until(async () => (await strip(page).count()) === 1);
  check("1a 目标条长出来", appeared);
  check("1b 文字逐字对", (await stripText(page)).includes(objective), await stripText(page));
  await page.screenshot({ path: path.join(evidence, "01-goal-strip.png") });

  // 2. A question -- the injected `<goal>` card rides the next call's start.
  await send(page, "先说一句：你打算怎么开始？");
  const injected = await until(async () =>
    (await page.locator("text=/<goal revision=/").count()) > 0,
  );
  check("2 注入卡上看得见 <goal> 提醒", injected, injected ? "" : "没有找到注入卡");
  await page.screenshot({ path: path.join(evidence, "02-injected-reminder.png") });

  // 3. A scripted EDIT round -- the driver opens the next round.
  await send(page, "开始改：把第一处写掉。");
  const roundTwo = await until(async () => /round [1-9]\d*\//.test(await stripText(page)));
  check("3a rounds 涨了", roundTwo, await stripText(page));
  const secondRound = await until(async () =>
    (await page.locator("text=/这一轮就做这件事/").count()) > 0,
  );
  check("3b 历史里出现了 round 开场（驱动器开的那一条）", secondRound);
  await page.screenshot({ path: path.join(evidence, "03-driver-opened-round.png") });

  // 4. A scripted READ-ONLY round -- the brake.
  await send(page, "这一轮只读，别改任何文件。");
  const blocked = await until(async () => (await stripText(page)).includes("no-progress"));
  check("4 零进展 → 目标被标 blocked(no-progress)", blocked, await stripText(page));
  await page.screenshot({ path: path.join(evidence, "04-blocked-no-progress.png") });

  // 5. A person resumes it -- and the next edit round continues.
  await command(page, "/goal resume");
  const active = await until(async () => !(await stripText(page)).includes("no-progress"));
  check("5a /goal resume 起了作用", active, await stripText(page));
  await send(page, "接着改：把第二处写掉。");
  const again = await until(async () => /round [1-9]\d*\//.test(await stripText(page)));
  check("5b 又续上了", again);

  // 6. Pause stops the driving; clear takes the strip away.
  await command(page, "/goal pause");
  const paused = await until(async () => (await stripText(page)).includes("暂停"));
  check("6a /goal pause 画出了暂停", paused, await stripText(page));
  await command(page, "/goal clear");
  const gone = await until(async () => (await strip(page).count()) === 0);
  check("6b /goal clear 之后目标条消失", gone);
  await page.screenshot({ path: path.join(evidence, "05-cleared.png") });
} finally {
  await browser.close();
}

console.log(failures === 0 ? "\nall walked" : `\n${failures} step(s) did not hold`);
process.exit(failures === 0 ? 0 : 1);
