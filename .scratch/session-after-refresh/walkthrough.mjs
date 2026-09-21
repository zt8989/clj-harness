// A real-browser walkthrough of ticket 04: 那一场没完的时候，输入框不装作能发.
//
//   node .scratch/session-after-refresh/walkthrough.mjs [ui-url]
//
// Run it against
//
//   node scripts/dev.mjs --scripted .scratch/session-after-refresh/evidence/04-go.json --ui-port 5319
//
// (temp homes, a scripted provider, no api-key; the harness picks its own port). It is the
// layer AGENTS.md asks for when `ui/src/` has been touched, and it is the layer this bug
// lived in: the suites are agent-level (no React), so "the composer still offers Send for a
// conversation the SERVER is answering" is not a thing any of them can see.
//
// THE SETUP IS A SERVER FACT AND NOT A CLOCK. The first scripted turn is a `bash` that sleeps
// (`evidence/04-go.json`), and the walkthrough waits until the SERVER says this conversation is
// `running` (the page route's own `state`) BEFORE it reloads. That wait is load-bearing and was
// learned the hard way: the run edge writes the input, then spends ~2 SECONDS assembling the
// system prompt before it registers the run, so a reload-then-send inside that window has the
// gate answer "nothing is running" and starts a second run -- a measurement this walkthrough
// made before it asserted anything (2026-09-21). Nothing here is `sleep`-based.
//
// WHAT IT ASSERTS, in order:
//
//   A. the run is registered and this page is driving it (the server says `running`, and the
//      Cancel button is up) -- the setup, so the reload below lands mid-run;
//   B. after a RELOAD into that still-running conversation, the composer does NOT offer Send
//      (the text is typed first, so `isEmpty` is not what disables it) -- the bug is that it
//      does, and the server then refuses the message with a 409;
//   C. and the page SAYS why: a sentence naming the session as still being answered;
//   D. once the run SETTLES, the composer offers Send again -- a gate that only ever closes is
//      the failure this half exists to catch;
//   E. and sending then lands in the SAME conversation (200, and the answer arrives).
//
// WHEN (B) IS RED IT ALSO PLAYS THE PERSON'S MOVE: it presses Send and records what the server
// answered. That is the second half of the reported bug ("点击发送会提示这个任务正在运行中")
// and it is printed rather than asserted -- after the fix there is no enabled button to press.
//
// Screenshots land in .scratch/session-after-refresh/evidence/.
import fs from "node:fs";
import path from "node:path";

import { chromium } from "file:///C:/Users/zhouteng/scoop/persist/nvm/nodejs/v24.9.0/node_modules/@playwright/cli/node_modules/playwright/index.mjs";

const url = process.argv[2] ?? "http://localhost:5319/";

const evidence = path.resolve(".scratch/session-after-refresh/evidence");
fs.mkdirSync(evidence, { recursive: true });

let failures = 0;
function check(label, ok, detail = "") {
  console.log(`${ok ? "ok  " : "RED "} ${label}${detail === "" ? "" : ` -- ${detail}`}`);
  if (!ok) failures += 1;
}

const browser = await chromium.launch();
const page = await browser.newPage({ viewport: { width: 1200, height: 900 } });
page.on("pageerror", (e) => check("no page error", false, e.message));

/// EVERY RUN REQUEST AND ITS ANSWER, so "the server refused the message" is a fact and not a
/// guess about a sentence on screen.
const runs = [];
/// AND THE BODY OF THE FIRST ONE, kept whole: the diagnostic below replays exactly the request
/// this page sent (same thread, same message) instead of pressing Send again. REPLAYING IT OUT
/// OF BAND IS WHAT KEEPS THE REST OF THE WALKTHROUGH CLEAN -- a refused run does not reach the
/// runtime this way, so the conversation on screen is not left holding a question that never
/// ran (that interference cost one red run of this file on 2026-09-21).
const runBodies = [];
page.on("request", (request) => {
  if (request.url().includes("/api/agent")) runBodies.push(request.postData() ?? "");
});
page.on("response", (res) => {
  if (!res.url().includes("/api/agent")) return;
  runs.push(
    res
      .text()
      .catch(() => "")
      .then((body) => ({ status: res.status(), body })),
  );
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

/// WHAT THE PAGE IS SHOWING, as the DOM says it. Every field here is a thing a person can
/// see, which is the whole point -- the suites could not see any of it.
///
/// `sendOffered` IS THE DISABLED BIT AND NOT THE ELEMENT'S EXISTENCE: the send button is
/// always in the DOM (the primitive renders a disabled `<button>` when the composer cannot
/// send), and an empty composer disables it too -- so the walkthrough TYPES first and reads
/// the bit after.
const drawn = () =>
  page.evaluate(() => {
    const send = document.querySelector(".aui-composer-send");
    return {
      sendPresent: send !== null,
      sendOffered: send !== null && !send.disabled,
      cancelShown: document.querySelector(".aui-composer-cancel") !== null,
      approvalShown: document.querySelector('[data-slot="approval-card"]') !== null,
      runningSpinners: document.querySelectorAll('[data-slot="thread-list-item-running"]')
        .length,
      notice: document.querySelector('[data-slot="session-running"]')?.textContent?.trim() ?? null,
      assistants: [...document.querySelectorAll('[data-slot="aui_assistant-message-content"]')].map(
        (el) => el.textContent?.trim() ?? "",
      ),
      bubbles: [...document.querySelectorAll('[data-slot="aui_user-message-root"]')].map(
        (el) => el.textContent?.trim() ?? "",
      ),
      foldedTurns: document.querySelectorAll('[data-slot="turn-steps-trigger"]').length,
    };
  });

/// THE SERVER'S OWN WORD about the conversation on screen, straight off the window route the
/// page itself reads (`GET /api/threads/<stem>/page` -> `state`). It is the ONE fact the whole
/// walkthrough is keyed on: every claim about "the server is still answering this" is checked
/// against it rather than against a stopwatch. The route is reached through vite's `/api`
/// proxy, which is a plain JSON GET -- the SSE defect in that forwarder is not in play.
const serverState = () =>
  page.evaluate(async () => {
    const id = localStorage.getItem("clj-harness.session");
    if (id === null) return "no-session";
    const res = await fetch(`api/threads/${encodeURIComponent(id)}/page`);
    if (!res.ok) return `HTTP ${res.status}`;
    return (await res.json()).state ?? null;
  });

/// AND WHAT THE SIDEBAR'S OWN SOURCE SAYS about this session (`GET /api/projects` -> the row's
/// `running`, which comes off the server's live-runs registry). Printed rather than asserted:
/// the row is a source the page already has, and the walkthrough records whether it agreed.
const listedRunning = () =>
  page.evaluate(async () => {
    const id = localStorage.getItem("clj-harness.session");
    const res = await fetch("api/projects");
    if (!res.ok) return `HTTP ${res.status}`;
    const body = await res.json();
    const rows = [
      ...(body.projects ?? []).flatMap((project) => project.sessions ?? []),
      ...(body.tasks ?? []),
    ];
    const row = rows.find((entry) => entry.threadId === id);
    return row === undefined ? "not-listed" : row.running;
  });

const typed = async (text) => {
  await page.fill("textarea", text);
  await page.waitForTimeout(120);
  return drawn();
};

// ------------------------------------------------------- A. a run this page is driving

await page.goto(url, { waitUntil: "load" });
await page.waitForSelector("textarea", { timeout: 20000 });

await page.fill("textarea", "第一句：慢慢跑。");
await page.press("textarea", "Enter");

const registered = await until(async () => (await serverState()) === "running", 30000);
check(
  "the run registers and the SERVER says this conversation is running",
  registered,
  await serverState(),
);
const stillDriving = await until(async () => (await drawn()).cancelShown, 10000);
check("...and this page is driving it (Cancel is up)", stillDriving);
console.log(`     while running: sidebar listing says running=${await listedRunning()}`);
await shot("04-01-running-in-this-page");

// ------------------------------------------------------ B/C. the reload, mid-run

await page.reload({ waitUntil: "load" });
await page.waitForSelector("textarea", { timeout: 20000 });
// The conversation is back when the question is: the window (and the feed) follow from there.
const restored = await until(
  async () => (await drawn()).bubbles.some((text) => text.includes("第一句")),
  30000,
);
check("the reload lands back in the conversation that is still being answered", restored);

const stateAfterReload = await serverState();
check(
  "...and the server still says it is running (the reload did not stop it)",
  stateAfterReload === "running",
  stateAfterReload,
);

const afterReload = await typed("第二句：这一场还在跑吗？");
await shot("04-02-after-the-reload");
console.log(
  `     after the reload: ${JSON.stringify(afterReload)} sidebar running=${await listedRunning()}`,
);

check(
  "after the reload the composer does NOT offer Send: the server is still answering",
  afterReload.sendOffered === false,
  `sendPresent=${afterReload.sendPresent} sendOffered=${afterReload.sendOffered}`,
);
check(
  "...and the page SAYS so, in a sentence of its own",
  afterReload.notice !== null,
  JSON.stringify(afterReload.notice),
);
check(
  "...and the turn on screen is still being written (not folded into a finished summary)",
  afterReload.foldedTurns === 0,
  `foldedTurns=${afterReload.foldedTurns}`,
);

// THE PERSON'S MOVE, PLAYED ONLY WHEN THE BUG IS THERE: aim the question this page sent at the
// run edge again and see what the server answers. This is the reported symptom's second half
// ("点击发送会提示这个任务正在运行中"), it is printed rather than asserted -- after the fix the
// button is not there to press -- and it goes out as a raw request so that the refusal does not
// land in this page's runtime.
if (afterReload.sendOffered) {
  const refused = await page.evaluate(async (body) => {
    const res = await fetch("api/agent", {
      method: "POST",
      headers: { "Content-Type": "application/json", Accept: "text/event-stream" },
      body,
    });
    return { status: res.status, body: await res.text() };
  }, runBodies[0] ?? "");
  console.log(
    `     [the reported symptom] a second POST /api/agent -> ${refused.status}: ` +
      refused.body.slice(0, 300),
  );
  await shot("04-03-what-the-server-answers");
}

// ------------------------------------------------------ D/E. once it settles, back to normal

const settled = await until(async () => (await serverState()) !== "running", 120000);
check("the run settles on its own", settled, await serverState());
check(
  "...and its answer is on screen",
  await until(async () => (await drawn()).assistants.length >= 1, 30000),
  JSON.stringify((await drawn()).assistants),
);

const afterSettle = await typed("第三句：跑完了就可以发。");
check(
  "once it settles the composer offers Send again (the gate is not permanent)",
  afterSettle.sendOffered === true,
  `sendOffered=${afterSettle.sendOffered} notice=${JSON.stringify(afterSettle.notice)}`,
);
await shot("04-04-settled-send-is-back");

const answersBefore = afterSettle.assistants.length;
const sentBefore = runs.length;
await page.press("textarea", "Enter");
await until(async () => runs.length > sentBefore, 15000);
const sent = await runs[runs.length - 1];
check("and sending is answered by a RUN, not a refusal", sent?.status === 200, String(sent?.status));

const landed = await until(async () => (await drawn()).assistants.length > answersBefore, 60000);
check("...and its answer lands in the same conversation", landed);
check(
  "...as the third question of it",
  (await drawn()).bubbles.some((text) => text.includes("第三句")),
  JSON.stringify((await drawn()).bubbles),
);

await browser.close();
console.log(failures === 0 ? "\nALL GREEN" : `\n${failures} RED`);
process.exit(failures === 0 ? 0 : 1);
