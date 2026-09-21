// A real-browser walkthrough of the OPENING CARDS at a session's birth.
//
//   node .scratch/session-opening/walkthrough.mjs [ui-url]
//
// Run it against
//
//   node scripts/dev.mjs --scripted .scratch/session-opening/evidence/go.json --ui-port 5221
//
// (temp homes, a scripted provider, no api-key; the harness picks its own port). It is the
// layer AGENTS.md asks for when `ui/src/` has been touched, and this feature is the reason
// that rule exists: the suites were GREEN while the app drew a person's AGENTS.md as if
// they had typed it -- `injections.ts` had spelled that message `role: "assistant"` and the
// page's real role is `user`, so the arithmetic agreed with itself and the screen was wrong.
// Nothing below is arithmetic; it is what the browser drew.
//
// WHAT IT ASSERTS, in order (the fix's three tickets in `.scratch/session-opening/spec.md`):
//
//   1. a session minted from a PROJECT ROW (`+`) is BOUND BEFORE IT EXISTS, so its first run
//      is its birth -- and that run writes the opening into the conversation AND draws it:
//      two injection rows on screen (the project's AGENTS.md and the skill catalog);
//   2. those two rows are CARDS, not bubbles, AND THEY ARE ALREADY IN THE PERSON'S COLUMN on
//      the birth run itself: the question is the only `user` bubble in the thread, the cards
//      sit in the left-hand injection rows (`aui_user-injection-root`), and they got there
//      without a reload -- the run that wrote them hands the page a `MESSAGES_SNAPSHOT` of
//      the conversation (`harness.edge.ag_ui/conversation-snapshot`), which is where the
//      messages the birth wrote are, cards and text alike;
//   3. the question is drawn ABOVE them (ticket 03: the opening lands behind the ask);
//   4. the SECOND run adds no card -- the opening is history by then, written once;
//   5. after a RELOAD the same two are still cards (the rebuild folds them from the record's
//      entry and its frame under one id, so there is exactly one of each).
//
// Screenshots land in .scratch/session-opening/evidence/.
import fs from "node:fs";
import os from "node:os";
import path from "node:path";

import { chromium } from "/Users/zhouteng/.nvm/versions/node/v22.21.1/lib/node_modules/playwright/index.mjs";

const url = process.argv[2] ?? "http://localhost:5221/";

const evidence = path.resolve(".scratch/session-opening/evidence");
fs.mkdirSync(evidence, { recursive: true });

let failures = 0;
function check(label, ok, detail = "") {
  console.log(`${ok ? "ok  " : "RED "} ${label}${detail === "" ? "" : ` -- ${detail}`}`);
  if (!ok) failures += 1;
}

/// THE PROJECT THIS SESSION BELONGS TO, made here so both halves of the opening exist: the
/// repo's own AGENTS.md would give one card, and the catalog needs a SKILL.md. The scripted
/// dev run's OS home is EMPTY on purpose (a temp home, deleted on exit), so the skill goes
/// where a bound session reads its second root instead -- `<project>/.agents/skills`.
const project = fs.mkdtempSync(path.join(os.tmpdir(), "opening-project-"));
fs.writeFileSync(
  path.join(project, "AGENTS.md"),
  "# 这一页的规则\n\nALPHA-STANDING-RULE：先说结论，再给理由。\n",
);
fs.mkdirSync(path.join(project, ".agents", "skills", "alpha"), { recursive: true });
fs.writeFileSync(
  path.join(project, ".agents", "skills", "alpha", "SKILL.md"),
  "---\nname: alpha\ndescription: alpha does a thing\n---\n\nALPHA BODY\n",
);
console.log(`     project ${project}`);

const browser = await chromium.launch();
const page = await browser.newPage({ viewport: { width: 1200, height: 900 } });
page.on("pageerror", (e) => check("no page error", false, e.message));

/// EVERY REQUEST THE PAGE SENT TO THE RUN ENDPOINT: the wait between turns is written on
/// these, not on a clock (the first walkthrough of this feature raced a still-streaming run).
const agentBodies = [];
page.on("request", (request) => {
  if (request.url().includes("/api/agent")) agentBodies.push(request.postData() ?? "");
});

const shot = async (name) => {
  await page.screenshot({ path: path.join(evidence, `${name}.png`) });
  console.log(`     shot ${name}.png`);
};

async function until(predicate, timeout = 30000) {
  const deadline = Date.now() + timeout;
  while (Date.now() < deadline) {
    if (await predicate()) return true;
    await page.waitForTimeout(50);
  }
  return false;
}

/// A TURN'S STEPS START FOLDED; clicking every still-folded summary is what a person does to
/// read them (see `components/turn-steps.tsx`).
const unfoldTurns = async () => {
  for (const trigger of await page.$$('[data-slot="turn-steps-trigger"][aria-expanded="false"]')) {
    await trigger.click();
  }
  await page.waitForTimeout(150);
};

async function send(text, { unfold = true } = {}) {
  const before = agentBodies.length;
  await page.waitForSelector(".aui-composer-cancel", { state: "detached", timeout: 30000 });
  await page.fill("textarea", text);
  await page.press("textarea", "Enter");
  await until(() => agentBodies.length > before, 30000);
  await page.waitForSelector(".aui-composer-cancel", { timeout: 30000 });
  await page.waitForSelector(".aui-composer-cancel", { state: "detached", timeout: 120000 });
  if (unfold) await unfoldTurns();
}

/// WHAT IS ON SCREEN, as the DOM says it: how many CARDS (the injection row the fix draws
/// for a card-only message) and how many BUBBLES (an ordinary message of the person's).
const drawn = async () =>
  page.evaluate(() => {
    const cards = [...document.querySelectorAll('[data-slot="aui_user-injection-root"]')];
    const bubbles = [...document.querySelectorAll('[data-slot="aui_user-message-root"]')];
    const box = (el) => {
      const r = el.getBoundingClientRect();
      return { top: Math.round(r.top), left: Math.round(r.left) };
    };
    return {
      cards: cards.map((el) => ({
        ...box(el),
        titles: [...el.querySelectorAll('[data-slot="injection-trigger-title"]')].map(
          (t) => t.textContent?.trim() ?? "",
        ),
        sizes: [...el.querySelectorAll('[data-slot="injection-trigger-size"]')].map(
          (t) => t.textContent?.trim() ?? "",
        ),
      })),
      bubbles: bubbles.map((el) => ({ ...box(el), text: el.textContent?.trim() ?? "" })),
      // WHERE THE CARDS SIT RELATIVE TO THE PERSON'S OWN FIRST MESSAGE: on screen, top to
      // bottom -- the order a reader sees, not the order a list holds.
      order: [...document.querySelectorAll('[data-slot^="aui_user-"]')]
        .filter((el) =>
          el.matches(
            '[data-slot="aui_user-injection-root"], [data-slot="aui_user-message-root"]',
          ),
        )
        .map((el) => el.getAttribute("data-slot")),
    };
  });

/// The bytes behind the cards, read once opened -- so the row is checked to CARRY the block
/// and not merely to say its name.
const openCards = async () => {
  for (const trigger of await page.$$('[data-slot="injection-trigger"]')) await trigger.click();
  await page.waitForTimeout(200);
  return page.$$eval('[data-slot="injection-content"]', (blocks) =>
    blocks.map((b) => b.textContent ?? ""),
  );
};

// ---------------------------------------------------------------- 0. this project exists

await page.goto(url, { waitUntil: "load" });
await page.waitForSelector("textarea", { timeout: 20000 });

const added = await page.evaluate(async (dir) => {
  const res = await fetch("api/projects", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ dir }),
  });
  return { status: res.status, body: await res.text() };
}, project);
check("the sidebar is given a project to mint a session in", added.status === 200, added.body);

await page.reload({ waitUntil: "load" });
await page.waitForSelector("textarea", { timeout: 20000 });

const row = await page.evaluate((name) => {
  const sections = [...document.querySelectorAll('[data-slot="sidebar-project"]')];
  const mine = sections.find(
    (section) =>
      section.querySelector('[data-slot="sidebar-project-name"]')?.textContent?.trim() === name,
  );
  if (mine === undefined) return null;
  mine.querySelector('[data-slot="sidebar-project-new-session"]')?.click();
  return true;
}, path.basename(project));
check("its row's + mints a session that belongs to it", row === true, String(row));
await page.waitForTimeout(300);

// ---------------------------------------------------------------- 1-3. the birth run

await send("第一句：这个项目里，开场该在哪儿？", { unfold: false });

/// THE CARDS HAVE TO ARRIVE WITHOUT ANYBODY RELOADING, and they arrive ON THE RUN: the birth
/// writes the opening into the conversation, and the same run hands this page the messages it
/// wrote, as a `MESSAGES_SNAPSHOT` (`harness.edge.ag_ui/conversation-snapshot`). A `CUSTOM`
/// frame could not do it -- a frame is a PART, and the adapter hangs it on the message being
/// STREAMED (`RunAggregator`'s CUSTOM branch ignores `messageId`;
/// `node_modules/@assistant-ui/react-ag-ui/dist/runtime/adapter/run-aggregator.js:215`), so a
/// card whose message the client never held lands under the answer instead of in the column
/// the record puts it in. This is that wait, and it is the whole of the proof: nothing below
/// reads the record, so what is asserted here came off the run's own stream.
const placed = await until(async () => (await drawn()).cards.length === 2, 15000);
check("the birth run's cards land without a reload (ticket 05)", placed, "waited 15s for two rows");
await page.waitForTimeout(300);

await shot("t01-birth-as-it-lands");
const folded = await page.$$eval('[data-slot="turn-steps-trigger"]', (els) =>
  els.map((el) => el.getAttribute("aria-expanded")),
);
console.log(`     the birth turn's step folds: ${JSON.stringify(folded)}`);

await unfoldTurns();
await shot("t01-birth-cards");

let seen = await drawn();
check(
  "the birth run drew the OPENING at all: the project's AGENTS.md and the catalog",
  (await page.$$('[data-slot="injection-trigger"]')).length === 2,
  JSON.stringify(seen.cards),
);
check(
  "...one row per block, each naming itself and how big it is",
  JSON.stringify(
    await page.$$eval('[data-slot="injection-trigger-title"]', (t) =>
      t.map((x) => x.textContent?.trim()).sort(),
    ),
  ) === JSON.stringify(["instructions", "skills"]),
  JSON.stringify(seen.cards),
);
check(
  "and the person's own message is the ONLY bubble -- the opening is not drawn as one",
  seen.bubbles.length === 1 && seen.bubbles[0].text.includes("第一句"),
  JSON.stringify(seen.bubbles),
);
check(
  "...and they sit in the PERSON's column, in the record's order: the ask, then the blocks",
  seen.cards.length === 2 &&
    seen.order.join(",") ===
      "aui_user-message-root,aui_user-injection-root,aui_user-injection-root",
  `user-side rows ${seen.cards.length}, order ${seen.order.join(",")}`,
);

await page.$$eval('[data-slot="injection-trigger"]', (triggers) => triggers.forEach((t) => t.click()));
await page.waitForTimeout(200);
const blocks = await page.$$eval('[data-slot="injection-content"]', (b) =>
  b.map((x) => x.textContent ?? ""),
);
check(
  "...and opening them shows the bytes the model read",
  blocks.some((b) => b.includes('path="') && b.includes("ALPHA-STANDING-RULE")) &&
    blocks.some((b) => b.includes("<skills>") && b.includes("- alpha:")),
  JSON.stringify(blocks.map((b) => b.slice(0, 60))),
);
await shot("t02-cards-open-and-question-above");

// ---------------------------------------------------------------- 4. the second run adds none

await send("第二句：开场块还会再来一遍吗？");
await page.waitForTimeout(500);
seen = await drawn();
const triggers = (await page.$$('[data-slot="injection-trigger"]')).length;
check(
  "the second run adds NO card -- the opening was written once",
  triggers === 2 && seen.bubbles.length === 2,
  JSON.stringify({ cards: triggers, bubbles: seen.bubbles.length }),
);

// ---------------------------------------------------------------- 5. after a reload

await page.reload({ waitUntil: "load" });
await page.waitForSelector("textarea", { timeout: 20000 });
await until(async () => (await drawn()).bubbles.length === 2, 30000);
await unfoldTurns();
seen = await drawn();
check(
  "after a reload the opening is in the PERSON's column: two rows, not two bubbles",
  seen.cards.length === 2 && seen.bubbles.length === 2,
  JSON.stringify({ cards: seen.cards.length, bubbles: seen.bubbles.length }),
);
check(
  "...and they are drawn ABOVE the answer and BELOW the ask that caused them (ticket 03)",
  seen.order.join(",") ===
    "aui_user-message-root,aui_user-injection-root,aui_user-injection-root,aui_user-message-root",
  seen.order.join(","),
);
await shot("t03-cards-back-after-a-reload");

await browser.close();
fs.rmSync(project, { recursive: true, force: true });
console.log(failures === 0 ? "\nALL GREEN" : `\n${failures} RED`);
process.exit(failures === 0 ? 0 : 1);
