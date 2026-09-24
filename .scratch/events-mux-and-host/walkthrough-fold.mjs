// TICKET 06: a settled turn keeps only its ANSWER, and the trajectory is loaded when
// it is asked for.
//
//   node scripts/dev.mjs --scripted .scratch/events-mux-and-host/fold.json --ui-port 5394
//   node .scratch/events-mux-and-host/walkthrough-fold.mjs http://localhost:5394/
//
// WHAT IS ON TRIAL, and why it needs a browser rather than a suite:
//
//   1. A TURN THAT HAS STOPPED IS FOLDED TO ITS ANSWER. `lib/turns.ts` decides WHICH
//      message is the conclusion (pure, tested in `test/suites/turns.ts`), but "what
//      is on screen" is a property of the rendered page: the last thought and the
//      tool row must NOT be there, and the answer must be. The fixture is chosen so
//      the ANSWER message itself carries reasoning -- which is exactly the leak the
//      ticket closes (before it, the tail was drawn whole and its thinking row showed).
//
//   2. OPENING THE TURN BRINGS THE STEPS BACK, so the fold is a door and not a loss.
//
//   3. THE TRAJECTORY IS LOADED ONLY WHEN THE `Trajectory` TAB IS OPEN, and it is
//      streamed (NDJSON) -- the downlink carries the conversation and nothing else.
//
// WHAT IT DOES NOT PROVE: that the fold is re-decided correctly for every record --
// that arithmetic is the suite's. This is the pixels.
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { execSync } from "node:child_process";

const HERE = path.dirname(fileURLToPath(import.meta.url));
const EVIDENCE = path.join(HERE, "evidence");
fs.mkdirSync(EVIDENCE, { recursive: true });

const globalRoot = execSync("npm root -g", { encoding: "utf8" }).trim();
const { chromium } = await import(path.join(globalRoot, "playwright", "index.mjs"));

const url = process.argv[2] ?? "http://localhost:5394/";

/// THE WORDS THE PAGE SHOULD AND SHOULD NOT SHOW, read from the same script the server
/// replays rather than copied here -- the answer came from turn 2's `content`, and the
/// two thinking rows are the two `reasoning` strings.
const script = JSON.parse(fs.readFileSync(path.join(HERE, "fold.json"), "utf8"));
const answer = script.turns[1].content;
const thoughts = script.turns.flatMap((t) => [t.reasoning, t["reasoning-after"]].filter(Boolean));

let failures = 0;
const check = (label, ok, detail = "") => {
  console.log(`${ok ? "ok  " : "RED "} ${label}${detail === "" ? "" : ` -- ${detail}`}`);
  if (!ok) failures += 1;
};

const browser = await chromium.launch();
const page = await browser.newPage({ viewport: { width: 1280, height: 900 } });
const pageErrors = [];
page.on("pageerror", (e) => pageErrors.push(e.message));

const until = async (fn, ms = 30000) => {
  const end = Date.now() + ms;
  for (;;) {
    if (await fn()) return true;
    if (Date.now() > end) return false;
    await new Promise((resolve) => setTimeout(resolve, 100));
  }
};

const visible = (selector) => page.locator(`${selector}:visible`).count();

await page.goto(url, { waitUntil: "load" });
await page.waitForSelector("textarea", { timeout: 20000 });
await page.fill("textarea", "读一下 deps.edn。");
await page.press("textarea", "Enter");

// THE RUN IS OVER when Stop is gone and the answer is on screen: we do not time it.
check(
  "the run settles and the answer lands",
  await until(async () =>
    (await page.locator('[data-slot="session-stop"]').count()) === 0 &&
    (await page.locator('[data-slot="aui_assistant-message-content"]:visible').allTextContents()).some((text) =>
      text.includes(answer),
    ),
  ),
);

// ------------------------------------------------- 1. folded: the answer and nothing else
check(
  "the settled turn is folded to its summary line",
  (await page.locator('[data-slot="turn-steps-trigger"]').count()) === 1,
);

const visibleContent = await page.locator('[data-slot="aui_assistant-message-content"]:visible').allTextContents();
check(
  "the answer is on screen",
  visibleContent.some((text) => text.includes(answer)),
  visibleContent.map((t) => t.slice(0, 40)).join(" | "),
);
check(
  "the last thought is NOT on screen",
  (await visible('[data-slot="reasoning-trigger"]')) === 0,
);
check("the tool row is NOT on screen", (await visible('[data-slot="tool-call-trigger"]')) === 0);
await page.screenshot({ path: path.join(EVIDENCE, "fold-01-settled.png") });

// --------------------------------------------------------------- 2. opening the turn
await page.click('[data-slot="turn-steps-trigger"]');
check("opening the turn brings its steps back", await until(async () => (await visible('[data-slot="tool-call-trigger"]')) === 1));
check(
  "...and every thought with them",
  await until(async () => {
    const subjects = await page.locator('[data-slot="reasoning-trigger"]:visible').allTextContents();
    return thoughts.every((thought) => subjects.some((subject) => subject.includes(thought.slice(0, 12))));
  }),
);
await page.screenshot({ path: path.join(EVIDENCE, "fold-02-opened.png") });

// ----------------------------------------------------------- 3. the trajectory, asked for
await page.click('[data-slot="view-switch-tab"][data-view="trajectory"]');
check(
  "the trajectory loads when its tab is opened",
  await until(async () => (await page.locator('[data-slot="trajectory-turn"]').count()) >= 1),
);
check(
  "...and its turn carries the tool call the model made",
  await until(async () => (await page.locator('[data-slot="trajectory-item"][data-kind="tool"]').count()) >= 1),
);
await page.screenshot({ path: path.join(EVIDENCE, "fold-03-trajectory.png") });

check("no page error", pageErrors.length === 0, pageErrors.join("; "));

await browser.close();
console.log(failures === 0 ? "ALL GREEN" : `${failures} RED`);
process.exit(failures === 0 ? 0 : 1);
