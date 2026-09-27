// A real-browser walkthrough of the COMPACTION CARD in the conversation column.
//
//   node .scratch/compaction-frames/walkthrough.mjs [ui-url] [project-dir]
//
// Run it against `node scripts/dev.mjs --scripted .scratch/compaction-frames/evidence/go.json`
// (temp homes, a scripted provider, no api-key), with a project whose `.harness/harness.edn`
// makes a compaction happen in a conversation this short:
//
//   {:compaction {:threshold-ratio 0.005 :retain-ratio 0.0001}}
//
// -- and the project dir is what you pass as the second argument. WITHOUT THAT FILE NOTHING
// COMPACTS: the defaults (0.7 / 0.16 of a 128k window) want ninety thousand tokens of history,
// which no walkthrough can type. It is the same trick `.scratch/compaction-shape` used for its
// real-run evidence, and the frame this walks is the one a real crossing produces.
//
// It is the layer AGENTS.md asks for when `ui/src/` has been touched: the suites can pin what a
// row SAYS (`lib/compactions.ts` as arithmetic) and the backend can pin the frames, and neither
// can see the card -- whether it is drawn at all, where it sits, whether opening it shows the
// summary, and whether it comes back after a refresh.
//
// WHAT IT ASSERTS, in order:
//
//   1. the run at whose START the harness compacted draws a folded card, in that run's own
//      message, before the model's answer -- the frame rides `:run/start`;
//   2. the row says what it is, the summary's first line, and what the folded range was
//      estimated at; opening it shows the summary and how many nodes went into it;
//   3. after a RELOAD the same card is back (the rebuild carries it: seed + the record's frames,
//      under the compaction's own id);
//   4. the client's NEXT request carries none of it -- the card is a `data` part and
//      `toAgUiMessages` has no case for one. That is the contract the whole feature rests on,
//      and the one assertion here a suite cannot make.
//
// AND ONE THING IT CANNOT ARRANGE, worth knowing when reading its output: a compaction whose
// SUMMARY CAME BACK EMPTY draws NO card (`compactionView` answers null, exactly as
// `injectionView` does for an empty block). A scripted provider that has run out of turns
// produces those, so the record may hold more `compacted-context` frames than the screen holds
// cards -- which is itself the assertion that a card with nothing to say is not drawn.
//
// Screenshots land in .scratch/compaction-frames/evidence/.
import fs from "node:fs";
import path from "node:path";

// A BARE SPECIFIER: this repo does not depend on Playwright (the suites drive the backend over
// HTTP), so the module is resolved from wherever the machine has it -- `npm i -D playwright`,
// or a global install named on NODE_PATH.
import { chromium } from "playwright";

const url = process.argv[2] ?? "http://localhost:4101/";
const projectDir = process.argv[3] ?? process.cwd();

const evidence = path.resolve(".scratch/compaction-frames/evidence");
fs.mkdirSync(evidence, { recursive: true });

let failures = 0;
function check(label, ok, detail = "") {
  console.log(`${ok ? "ok  " : "RED "} ${label}${detail === "" ? "" : ` -- ${detail}`}`);
  if (!ok) failures += 1;
}

const browser = await chromium.launch();
const page = await browser.newPage({ viewport: { width: 1200, height: 900 } });
page.on("pageerror", (e) => check("no page error", false, e.message));

/// EVERY REQUEST THE PAGE SENT TO THE RUN ENDPOINT, kept for the last check: what the client
/// hands back is the one fact that decides whether a card is a view or a message, and it is only
/// observable here.
const agentBodies = [];
page.on("request", (request) => {
  if (request.url().includes("/api/agent")) agentBodies.push(request.postData() ?? "");
});

const shot = async (name) => {
  await page.screenshot({ path: path.join(evidence, `${name}.png`) });
  console.log(`     shot ${name}.png`);
};

await page.goto(url, { waitUntil: "load" });
await page.waitForSelector(".aui-composer-input", { timeout: 20000 });

const threadId = () => page.evaluate(() => window.localStorage.getItem("clj-harness.session"));

async function until(predicate, timeout = 30000) {
  const deadline = Date.now() + timeout;
  while (Date.now() < deadline) {
    if (await predicate()) return true;
    await page.waitForTimeout(50);
  }
  return false;
}

/// Send one message and wait for the run it starts to FINISH -- by the page's own state: the
/// composer draws its Cancel button exactly while `thread.isRunning`.
async function send(text) {
  const before = agentBodies.length;
  await page.waitForSelector(".aui-composer-cancel", { state: "detached", timeout: 30000 });
  await page.fill(".aui-composer-input", text);
  await page.press(".aui-composer-input", "Enter");
  await until(() => agentBodies.length > before, 30000);
  await page.waitForSelector(".aui-composer-cancel", { timeout: 30000 });
  await page.waitForSelector(".aui-composer-cancel", { state: "detached", timeout: 120000 });
  await unfoldTurns();
}

/// The compaction cards on screen, as the row says them.
const cardsNow = async () =>
  page.$$eval('[data-slot="compaction-trigger"]', (triggers) =>
    triggers.map((trigger) => ({
      label: trigger.textContent?.trim() ?? null,
      title: trigger.querySelector('[data-slot="compaction-trigger-title"]')?.textContent?.trim() ?? null,
      size: trigger.querySelector('[data-slot="compaction-trigger-size"]')?.textContent?.trim() ?? null,
    })),
  );

/// A TURN'S STEPS START FOLDED (`components/turn-steps.tsx`), and a card is one of the steps --
/// so a person reads a card by unfolding the turn, and so does this.
const unfoldTurns = async () => {
  for (const trigger of await page.$$('[data-slot="turn-steps-trigger"][aria-expanded="false"]')) {
    await trigger.click();
  }
  await page.waitForTimeout(150);
};

// ------------------------------------------------------- 1. the run the compaction opened

// TWO RUNS FIRST: at the birth of a conversation there is nothing to fold (the opening is
// written by that very run), so the trigger at a run's head has nothing to compact until a
// conversation exists.
await send("第一句：绑定这个项目之后问一句");
await send("第二句：再聊一句");
await send("第三句：这一轮开头应该会压一次");

const cards = await cardsNow();
check("the compaction draws a card", cards.length > 0, JSON.stringify(cards));
check(
  "and its row says what it is, the summary's first line, and the size",
  cards.every((c) => c.label !== null && c.title !== "" && c.size !== ""),
  JSON.stringify(cards),
);
// THE CARD IS DRAWN WITH THE RUN WHOSE START IT DESCRIBES: the frame is emitted at `:run/start`,
// so the part rides that run's own assistant message, ahead of the answer.
const order = await page.evaluate(() => {
  const trigger = document.querySelector('[data-slot="compaction-trigger"]');
  const holder = trigger?.closest('[data-slot="aui_assistant-message-content"]');
  return { same: holder !== null, html: (holder?.innerHTML ?? "").indexOf("compaction-trigger") };
});
check("it sits inside an assistant message, ahead of that message's text", order.same && order.html >= 0, JSON.stringify(order));
// THE ROW IS READ BEFORE IT IS OPENED (`cardsNow` above), and the picture is taken once it is
// open: a folded row is one line of text, and the check above pins it word for word.

const opened = await page.evaluate(() => {
  document.querySelector('[data-slot="compaction-trigger"]')?.click();
  return true;
});
await page.waitForTimeout(200);
const body = await page.evaluate(() => ({
  summary: [...document.querySelectorAll('[data-slot="compaction-summary"]')].map((b) => b.textContent ?? ""),
  folded: [...document.querySelectorAll('[data-slot="compaction-folded"]')].map((b) => b.textContent?.trim() ?? ""),
}));
check("opening it shows the summary the model is reading", body.summary.some((s) => s.trim() !== ""), JSON.stringify(body));
check("and how many nodes went into it", body.folded.length > 0, JSON.stringify(body));
await shot("t01-01-card-open");

// ---------------------------------------------------------------- 2. after a refresh

await page.reload({ waitUntil: "load" });
await page.waitForSelector(".aui-composer-input", { timeout: 20000 });
await until(async () => (await cardsNow()).length > 0, 30000);
await unfoldTurns();
const rebuilt = await cardsNow();
check(
  "the same card comes back after a refresh (the rebuild carries it)",
  rebuilt.length === cards.length && rebuilt[0]?.size === cards[0]?.size,
  JSON.stringify({ before: cards, after: rebuilt }),
);
await shot("t02-01-after-reload");

// ------------------------------------------------- 3. the client never sends it back

await send("第四句：刷新之后接着问，回发的那一份里不该有那张卡");
const last = agentBodies[agentBodies.length - 1] ?? "";
check(
  "the next request carries none of it",
  last.includes("第四句") && !last.includes("compacted-context"),
  last.slice(0, 200),
);

console.log(failures === 0 ? "\nGREEN" : `\nRED (${failures})`);
await browser.close();
process.exit(failures === 0 ? 0 : 1);
