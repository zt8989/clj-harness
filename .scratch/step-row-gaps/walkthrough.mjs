// A real-browser measurement of the VERTICAL GAP between adjacent step rows in the
// conversation column -- the tool-call row, the reasoning row, and the injected-context
// row.
//
//   node .scratch/step-row-gaps/walkthrough.mjs [ui-url] [project-dir]
//
// Run it against `node scripts/dev.mjs --scripted .scratch/step-row-gaps/go.json
// --ui-port 5311` (temp homes, a scripted provider, no api-key). PROJECT-DIR defaults to
// this script's own `fixture/` -- three configured instruction files, so the session opens
// with a STACK of cards instead of the single one a bare checkout draws; the fixture's
// `.harness/harness.edn` says why.
//
// WHY THIS EXISTS. `ui/src/components/message-parts.tsx` and `.scratch/flat-step-rows/spec.md`
// both state the rule the step rows are built on: adjacent step rows are separated by their
// own `py-1.5` alone -- 12px -- and nothing else. That is a property of the RENDERED PAGE and
// of nothing a suite can reach: the rows are three different components on two different
// message paths (a tool call and a thought are parts of an assistant message, an injected
// card is a `data` part -- or, when the record carries it as its own entry, a whole message
// of its own). So this script measures the pixels, and it is the only thing that can.
//
// WHAT IT MEASURES. Every visible step row on screen, in document order, with the kind of
// row it is and the same message it belongs to. Then, for every adjacent pair, the gap
// between the two boxes (next.top - previous.bottom). The rule is that the gap between two
// rows of the SAME conversation -- a tool row, a thought, a card -- is the same number
// everywhere. Rows separated by a real turn boundary are reported too, because a card that
// arrives as its own message is exactly where that difference would hide.
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

import { chromium } from "file:///C:/Users/zhouteng/scoop/persist/nvm/nodejs/v24.9.0/node_modules/@playwright/cli/node_modules/playwright/index.mjs";

/// WHERE THIS SCRIPT AND ITS EVIDENCE LIVE, taken off the script's own path rather than the
/// process's cwd: the screenshots are this feature's evidence, and a run started from
/// anywhere else must still write them here -- and find the fixture -- instead of
/// scattering them where the reader will not look.
const HERE = path.dirname(fileURLToPath(import.meta.url));
const EVIDENCE = path.join(HERE, "evidence");

const url = process.argv[2] ?? "http://localhost:5311/";
const projectDir = process.argv[3] ?? path.join(HERE, "fixture");

const NEW = 12; // 0.75rem -- what `py-1.5` on each of two adjacent rows sums to.

let failures = 0;
function check(label, ok, detail = "") {
  console.log(`${ok ? "ok  " : "RED "} ${label}${detail === "" ? "" : ` -- ${detail}`}`);
  if (!ok) failures += 1;
}

const browser = await chromium.launch();
const page = await browser.newPage({ viewport: { width: 1200, height: 900 } });
page.on("pageerror", (e) => check("no page error", false, e.message));

const agentBodies = [];
page.on("request", (request) => {
  if (request.url().includes("/api/agent")) agentBodies.push(request.postData() ?? "");
});

async function until(predicate, timeout = 30000) {
  const deadline = Date.now() + timeout;
  while (Date.now() < deadline) {
    if (await predicate()) return true;
    await page.waitForTimeout(50);
  }
  return false;
}

/// A TURN'S STEPS START FOLDED, so a card or a row that is `display:none` measures as
/// nothing. Click every summary line that still says it is folded, the same thing a person
/// does.
async function unfoldTurns() {
  for (const trigger of await page.$$('[data-slot="turn-steps-trigger"][aria-expanded="false"]')) {
    await trigger.click();
  }
  await page.waitForTimeout(150);
}

async function send(text) {
  const before = agentBodies.length;
  await page.waitForSelector(".aui-composer-cancel", { state: "detached", timeout: 30000 });
  await page.fill("textarea", text);
  await page.press("textarea", "Enter");
  await until(() => agentBodies.length > before, 30000);
  await page.waitForSelector(".aui-composer-cancel", { timeout: 30000 });
  await page.waitForSelector(".aui-composer-cancel", { state: "detached", timeout: 120000 });
  await unfoldTurns();
}

/// Every step row on the page, in document order, as the numbers this script is about: the
/// kind of row, the box it occupies, and which message box it sits in (so a gap across a
/// message boundary can be told from one inside a message).
const ROW_SELECTOR =
  '[data-slot="tool-call-trigger"], [data-slot="reasoning-trigger"], [data-slot="injection-trigger"]';

const rowsNow = () =>
  page.evaluate((selector) => {
    const kindOf = (el) => el.getAttribute("data-slot").replace("-trigger", "");
    const all = [...document.querySelectorAll(selector)].filter((el) => {
      const r = el.getBoundingClientRect();
      return r.height > 0 && r.width > 0;
    });
    return all.map((el) => {
      const r = el.getBoundingClientRect();
      const owner = el.closest(
        '[data-slot="aui_assistant-message-root"], [data-slot="aui_user-injection-root"], [data-slot="aui_user-message-root"]',
      );
      return {
        kind: kindOf(el),
        text: (el.textContent ?? "").trim().slice(0, 40),
        top: Math.round(r.top),
        bottom: Math.round(r.bottom),
        height: Math.round(r.height),
        owner: owner?.getAttribute("data-slot") ?? "(none)",
        ownerIndex: [
          ...document.querySelectorAll(
            'body [data-slot="aui_assistant-message-root"], body [data-slot="aui_user-injection-root"], body [data-slot="aui_user-message-root"]',
          ),
        ].indexOf(owner),
      };
    });
  }, ROW_SELECTOR);

function gaps(rows) {
  const out = [];
  for (let i = 1; i < rows.length; i += 1) {
    const previous = rows[i - 1];
    const current = rows[i];
    out.push({
      from: previous.kind,
      to: current.kind,
      gap: current.top - previous.bottom,
      sameMessage: previous.ownerIndex === current.ownerIndex,
      // HOW MANY MESSAGES THE TWO ROWS ARE APART. 1 means the two rows are neighbours in
      // the column and nothing but another step sits between them; more than 1 means a
      // PERSON's message does, and that boundary is the one gap that is not a step's.
      messageSpan: current.ownerIndex - previous.ownerIndex,
    });
  }
  return out;
}

await page.goto(url, { waitUntil: "load" });

// ---------------------------------------------------------------- drive a real session
// THE SESSION IS OPENED THE WAY THE SIDEBAR OPENS ONE: a PROJECT session, minted and
// bound in one action (`POST /api/project` with no thread id) -- so the conversation's
// birth already knows where it lives and writes its OPENING ENTRIES. That is the shape a
// person's own session has, and the one this script is about: the opening blocks arrive as
// messages of their own, not as parts of an assistant message.
const opened = await page.evaluate(async (dir) => {
  const res = await fetch("api/project", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ dir }),
  });
  return { status: res.status, body: await res.json() };
}, projectDir);
check("a project session is minted and bound", opened.status === 200, JSON.stringify(opened));

await page.evaluate((threadId) => window.localStorage.setItem("clj-harness.session", threadId), opened.body.threadId);
await page.reload({ waitUntil: "load" });
await page.waitForTimeout(500);

await send("第一句：这个会话一生下来就绑在这个仓库上");
await page.waitForTimeout(500);

await send("第二句：起一条后台作业，然后去干别的");
await page.waitForTimeout(500);
await unfoldTurns();

const rows = await rowsNow();
console.log("\nrows, in document order:");
for (const [i, row] of rows.entries()) {
  console.log(
    `  ${String(i).padStart(2)}  ${row.kind.padEnd(16)} h=${String(row.height).padStart(3)}  ` +
      `msg#${String(row.ownerIndex).padStart(2)} ${row.owner.padEnd(28)} ${row.text}`,
  );
}

const measured = gaps(rows);
console.log("\ngaps between adjacent rows (next.top - previous.bottom):");
for (const g of measured) {
  console.log(
    `  ${g.from.padEnd(16)} -> ${g.to.padEnd(16)} ${String(g.gap).padStart(4)}px  ` +
      (g.messageSpan === 0
        ? "the same message (the rows' own padding is the whole gap)"
        : g.messageSpan === 1
          ? "neighbouring messages"
          : `${g.messageSpan} messages apart (a person's message between them)`),
  );
}

const kinds = new Set(rows.map((r) => r.kind));
check(
  "the run drew all three kinds of step row -- a tool call, a thought, and an injected card",
  kinds.has("tool-call") && kinds.has("reasoning") && kinds.has("injection"),
  JSON.stringify([...kinds]),
);

// A STACK OF CARDS IS THE CASE THIS SCRIPT EXISTS FOR. A session's birth writes one message
// per opening block, so without the step spacing the top of a conversation is a stack of rows
// a turn's 24px apart while the rows below them sit 12px apart.
const cardsInARow = measured.filter((g) => g.from === "injection" && g.to === "injection");
check(
  "the opening drew more than one card, one message each",
  cardsInARow.length >= 2,
  JSON.stringify(cardsInARow),
);

// THE RULE: two adjacent steps are separated by their own `py-1.5` and nothing else -- 12px,
// whatever kind of step they are (a tool call, a thought, an injected card) and whichever
// message each one landed in. The box gap is 0 because the 12px IS the rows' padding.
//
// EVERY PAIR THAT IS NOT ACROSS A PERSON'S MESSAGE counts, `messageSpan` 0 included: a
// thought and the call it belongs to share one message, and so does a card that a run
// injected beside its own rows -- which is exactly what the live path draws (the job-ended
// card above) and the case an `=== 1` filter would have let pass unasserted. `> 1` is the
// only span that is not a step's, and it is checked on its own two lines below.
const STEP_GAP_BOX = 0;
const steps = measured.filter((g) => g.messageSpan <= 1);
check(
  "every pair of neighbouring steps is the same distance apart",
  steps.length >= 3 && steps.every((g) => g.gap === steps[0].gap),
  JSON.stringify(steps),
);
check(
  "...and that distance is the two rows' own padding, not a turn's gap",
  steps.every((g) => g.gap === STEP_GAP_BOX),
  JSON.stringify(steps),
);
check(
  "...a card among them included",
  steps.some((g) => g.from === "injection" || g.to === "injection"),
  JSON.stringify(steps),
);

// AND THE TURN BOUNDARY IS STILL A TURN BOUNDARY, which is the other half of the rule: the two
// gaps that are NOT steps -- the card stack to the person's question, and the question to the
// answer -- keep the message group's own 24px. A negative margin that leaked onto the FIRST
// message would show up as the stack rising above its own container.
const groupTop = await page.evaluate(() => {
  const group = document.querySelector('[data-slot="aui_message-group"]');
  return group === null ? null : Math.round(group.getBoundingClientRect().top);
});
check(
  "the first card did not eat the room above it",
  groupTop !== null && rows[0].top >= groupTop,
  `first row top ${rows[0].top}, message group top ${groupTop}`
);
check(
  "the person's question keeps a turn's gap",
  measured.some((g) => g.messageSpan > 1 && g.gap > NEW),
  JSON.stringify(measured.filter((g) => g.messageSpan > 1))
);

// ------------------------------------------------- 2. the same conversation, REBUILT
//
// A refresh is a second drawing of the same conversation, and the card is drawn from a
// DIFFERENT path there: a run's own injection comes back as a message of its own (the rebuild
// folds one card message per CUSTOM frame) rather than as a part beside the rows it landed
// with. The spacing has to be the same number on both paths, or the reader sees the
// conversation move when they reload it.
//
// AND BOTH PATHS GET A PICTURE, one before this reload and one after: two screenshots of the
// same conversation drawn twice is what a reader can compare by eye when a number is
// disputed. Taken here rather than both at the end, which is how they came out byte for byte
// identical -- the second one overwriting the first one's meaning, not its bytes.
await page.screenshot({ path: path.join(EVIDENCE, "gaps.png"), fullPage: true });
await page.reload({ waitUntil: "load" });
await page.waitForSelector('[data-slot="injection-trigger"]', { state: "attached", timeout: 20000 });
await unfoldTurns();
await page.waitForTimeout(300);

const rebuiltRows = await rowsNow();
const rebuilt = gaps(rebuiltRows);
console.log("\nafter a refresh:");
for (const [i, row] of rebuiltRows.entries()) {
  console.log(`  ${String(i).padStart(2)}  ${row.kind.padEnd(16)} msg#${String(row.ownerIndex).padStart(2)} ${row.owner.padEnd(28)} ${row.text}`);
}
for (const g of rebuilt) {
  console.log(`  ${g.from.padEnd(16)} -> ${g.to.padEnd(16)} ${String(g.gap).padStart(4)}px  span ${g.messageSpan}`);
}

const rebuiltSteps = rebuilt.filter((g) => g.messageSpan <= 1);
check(
  "the rebuilt conversation spaces its steps the same way",
  rebuiltSteps.length >= 3 && rebuiltSteps.every((g) => g.gap === rebuiltSteps[0].gap),
  JSON.stringify(rebuiltSteps),
);
check(
  "...and the card is one of them",
  rebuiltSteps.some((g) => g.from === "injection" || g.to === "injection"),
  JSON.stringify(rebuiltSteps),
);
await page.screenshot({ path: path.join(EVIDENCE, "gaps-rebuilt.png"), fullPage: true });
await browser.close();

console.log(`\n${failures === 0 ? "all checks passed" : `${failures} check(s) failed`}`);
process.exit(failures === 0 ? 0 : 1);