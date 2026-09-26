// A real-browser walkthrough of the INJECTION CARD in the conversation column.
//
//   node .scratch/context-frames/walkthrough.mjs [ui-url] [project-dir]
//
// Run it against `node scripts/dev.mjs --scripted .scratch/context-frames/evidence/go.json
// --ui-port 5219` (temp homes, a scripted provider, no api-key). It is the layer AGENTS.md
// asks for when `ui/src/` has been touched: the suites can pin what a row SAYS (lib/injections.ts
// as arithmetic) and the backend can pin the frames, and neither can see the card -- whether
// it is drawn at all, where it sits, whether opening it shows the bytes, and whether it comes
// back after a refresh.
//
// WHAT IT ASSERTS, in order:
//
//   1. a run whose session has no project draws NO card (an empty injection is not a card);
//   2. once the session is bound to this repository, the run's own `<instructions>` block is
//      a folded card, drawn BEFORE the assistant's own text (the frame arrives before the answer);
//   3. the run that starts a background job draws a card for the `<job-ended …>` that landed
//      between two calls -- and opening it shows the bytes, `[exit 0]` included;
//   4. after a RELOAD the same cards are back (the rebuild carries them: seed + the record's
//      frames), and
//   5. the client's NEXT request (read off the wire, not off the record) carries none of it --
//      the card is a `data` part and `toAgUiMessages` has no case for one. That is the contract
//      the whole feature rests on, and the one assertion here a suite cannot make.
//
// Screenshots land in .scratch/context-frames/evidence/.
import fs from "node:fs";
import path from "node:path";

import { chromium } from "/Users/zhouteng/.nvm/versions/node/v22.21.1/lib/node_modules/playwright/index.mjs";

const url = process.argv[2] ?? "http://localhost:5219/";
const projectDir = process.argv[3] ?? "/Users/zhouteng/Documents/workspace/clj-harness";

const evidence = path.resolve(".scratch/context-frames/evidence");
fs.mkdirSync(evidence, { recursive: true });

let failures = 0;
function check(label, ok, detail = "") {
  console.log(`${ok ? "ok  " : "RED "} ${label}${detail === "" ? "" : ` -- ${detail}`}`);
  if (!ok) failures += 1;
}

const browser = await chromium.launch();
const page = await browser.newPage({ viewport: { width: 1200, height: 900 } });
page.on("pageerror", (e) => check("no page error", false, e.message));

/// EVERY REQUEST THE PAGE SENDS TO THE RUN ENDPOINT, kept for the last check: what the client
/// hands back is the one fact that decides whether a card is a view or a message, and it is
/// only observable here.
const agentBodies = [];
page.on("request", (request) => {
  if (request.url().includes("/api/agent")) agentBodies.push(request.postData() ?? "");
});

const shot = async (name) => {
  await page.screenshot({ path: path.join(evidence, `${name}.png`) });
  console.log(`     shot ${name}.png`);
};

await page.goto(url, { waitUntil: "load" });
await page.waitForSelector("textarea", { timeout: 20000 });

const threadId = () => page.evaluate(() => window.localStorage.getItem("clj-harness.session"));

/// Poll PREDICATE until it answers true, or give up after TIMEOUT and say so. Used for the
/// facts only this process can see (the requests the page sent), where Playwright's own
/// waits have nothing to stand on.
async function until(predicate, timeout = 30000) {
  const deadline = Date.now() + timeout;
  while (Date.now() < deadline) {
    if (await predicate()) return true;
    await page.waitForTimeout(50);
  }
  return false;
}
/// Send one message and wait for the run it starts to FINISH -- by the page's own state, not
/// by a clock: the composer draws its Cancel button exactly while `thread.isRunning`, so
/// waiting for it to appear and then to go is waiting for the run (the first walkthrough
/// taken at this feature raced a still-streaming run and typed into a busy composer, which
/// is how the wait below is written).
async function send(text) {
  const before = agentBodies.length;
  await page.waitForSelector(".aui-composer-cancel", { state: "detached", timeout: 30000 });
  await page.fill("textarea", text);
  await page.press("textarea", "Enter");
  const started = await until(() => agentBodies.length > before, 30000);
  await page.waitForSelector(".aui-composer-cancel", { timeout: 30000 });
  await page.waitForSelector(".aui-composer-cancel", { state: "detached", timeout: 120000 });
  await unfoldTurns();
  const id = await threadId();
  check("the page is in a session", typeof id === "string" && id !== "", String(id));
  check("and the message it was sent reached the server", agentBodies.length > before, `${agentBodies.length}`);
  return id;
}

/// The cards on screen, as the row says them: the tag it names, the bytes it counts, and the
/// block's own text (read once opened, so the content is checked and not just the row).
const cardsNow = async () =>
  page.$$eval('[data-slot="injection-trigger"]', (triggers) =>
    triggers.map((trigger) => ({
      label: trigger.textContent?.trim() ?? null,
      title: trigger.querySelector('[data-slot="injection-trigger-title"]')?.textContent?.trim() ?? null,
      size: trigger.querySelector('[data-slot="injection-trigger-size"]')?.textContent?.trim() ?? null,
    })),
  );

const openAllCards = async () => {
  for (const trigger of await page.$$('[data-slot="injection-trigger"]')) await trigger.click();
  await page.waitForTimeout(150);
  return page.$$eval('[data-slot="injection-content"]', (blocks) => blocks.map((b) => b.textContent ?? ""));
};

/// A TURN'S STEPS START FOLDED (`components/turn-steps.tsx`), and a card is one of the steps --
/// so a person reads a card by unfolding the turn, and this walkthrough does the same thing:
/// click every summary line that says it is still folded. Without this the cards are in the
/// DOM but `display:none`, which is what the first pass of this script measured.
const unfoldTurns = async () => {
  for (const trigger of await page.$$('[data-slot="turn-steps-trigger"][aria-expanded="false"]')) {
    await trigger.click();
  }
  await page.waitForTimeout(150);
};

// ---------------------------------------------------------------- 1. an unbound first run

await send("第一轮：还没绑定项目，问一句");
await page.waitForTimeout(300);
check(
  "a run with no project draws no card at all",
  (await page.$$('[data-slot="injection-trigger"]')).length === 0,
  JSON.stringify(await cardsNow()),
);
await shot("t01-01-no-card-unbound");

// ---------------------------------------------------------------- 2. bound: the run's own blocks

const bound = await page.evaluate(
  async ([id, dir]) => {
    const res = await fetch("api/project", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ threadId: id, dir }),
    });
    return res.status;
  },
  [await threadId(), projectDir],
);
check("the session binds to this repository", bound === 200, `status ${bound}`);

await send("绑定之后问一句");
const boundCards = await cardsNow();
check(
  "the run's own <instructions> block is on screen as a card",
  boundCards.some((card) => card.title === "instructions"),
  JSON.stringify(boundCards),
);
check(
  "...saying what it is and how big it is",
  boundCards.every((card) => card.label !== null && card.size !== ""),
  JSON.stringify(boundCards),
);

// THE CARD IS DRAWN BEFORE THE ANSWER: the CUSTOM frame is emitted before the model is called
// at all, so the part lands ahead of the text in the same assistant message.
const order = await page.evaluate(() => {
  const contents = [...document.querySelectorAll('[data-slot="aui_assistant-message-content"]')];
  const holder = contents.find((el) => (el.textContent ?? "").includes("绑定之后这一句"));
  const html = holder?.innerHTML ?? "";
  return { card: html.indexOf("injection-trigger"), text: html.indexOf("绑定之后这一句") };
});
check(
  "it sits before the assistant's own text in that message",
  order.card >= 0 && order.text > order.card,
  JSON.stringify(order),
);
await shot("t02-01-instructions-card-in-the-conversation");

// ---------------------------------------------------------------- 3. the job's ending

await send("起一条后台作业，然后去干别的");
await page.waitForTimeout(300);
const jobCards = await cardsNow();
check(
  "the ending of the background job is a card too",
  jobCards.some((card) => card.title === "job-ended"),
  JSON.stringify(jobCards),
);

// AND WHERE IT SITS: the job's ending landed between two calls of that run, so it is drawn
// after the turn's tool cards and before the closing line -- the place it really arrived.
const placement = await page.evaluate(() => {
  const job = [...document.querySelectorAll('[data-slot="injection-trigger"]')].find((trigger) =>
    (trigger.textContent ?? "").includes("job-ended"),
  );
  const answer = [...document.querySelectorAll('[data-slot="aui_assistant-message-content"]')].find((el) =>
    (el.textContent ?? "").includes("我是助手"),
  );
  const tool = document.querySelector(
    '[data-slot="tool-group-trigger"], [data-slot="tool-fallback-trigger"], [data-slot="tool-call-trigger"]',
  );
  const follows = (a, b) => a !== undefined && a !== null && b !== undefined && b !== null &&
    (a.compareDocumentPosition(b) & Node.DOCUMENT_POSITION_FOLLOWING) !== 0;
  return { tool: tool !== null, afterTool: follows(tool, job), beforeAnswer: follows(job, answer) };
});
check(
  "it is drawn after the turn's tool cards and before the closing line",
  placement.afterTool && placement.beforeAnswer,
  JSON.stringify(placement),
);
const opened = await openAllCards();
check(
  "opening the cards shows what the model was handed",
  opened.some((text) => text.includes("<job-ended") && text.includes("[exit 0]")),
  JSON.stringify(opened.map((t) => t.slice(0, 60))),
);
check(
  "...the instruction block among them",
  opened.some((text) => text.includes("<instructions path=")),
  JSON.stringify(opened.map((t) => t.slice(0, 40))),
);
await shot("t03-01-job-ended-card-open");

// ---------------------------------------------------------------- 4. a refresh brings them back

const before = await cardsNow();
await page.reload({ waitUntil: "load" });
// The page remembers which session it was in (lib/session-memory.ts) and rebuilds it from
// the record's frames. THE REBUILT TURNS COME BACK FOLDED -- folding is the view's state and
// not the record's -- so the cards are waited for as ELEMENTS and then the turns are opened,
// which is the same two steps a person takes.
await page.waitForSelector('[data-slot="injection-trigger"]', { state: "attached", timeout: 20000 });
await unfoldTurns();
const after = await cardsNow();
check(
  "after a refresh the same cards are back",
  after.length === before.length && JSON.stringify(after) === JSON.stringify(before),
  `${JSON.stringify(before)} vs ${JSON.stringify(after)}`,
);
const afterOpened = await openAllCards();
check(
  "...with the job's ending among them",
  afterOpened.some((text) => text.includes("<job-ended") && text.includes("[exit 0]")),
  JSON.stringify(afterOpened.map((t) => t.slice(0, 40))),
);
await shot("t04-01-cards-back-after-refresh");

// ---------------------------------------------------------------- 5. and are never sent back

const requestedBefore = agentBodies.length;
await send("刷新之后再说一句");
const last = agentBodies[agentBodies.length - 1] ?? "";
check(
  "the run after the refresh reached the server",
  agentBodies.length === requestedBefore + 1 && last.includes("刷新之后再说一句"),
  `${agentBodies.length} bodies, last is ${last.length} bytes`,
);
check(
  "the client's own history carries NONE of the cards",
  !last.includes("injected-context") &&
    !last.includes("<job-ended") &&
    !last.includes("<instructions path="),
  last.slice(0, 200),
);
check(
  "...while the conversation it does carry is the real one",
  last.includes("我是助手") && last.includes("第一轮的回答"),
);
await shot("t05-01-next-request-is-clean");

await browser.close();
console.log(failures === 0 ? "\nGREEN" : `\n${failures} RED`);
process.exit(failures === 0 ? 0 : 1);
