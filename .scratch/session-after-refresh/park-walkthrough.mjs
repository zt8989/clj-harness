// A real-browser walkthrough of ticket 06: parked 的那一场，刷新回来还能答.
//
//   node .scratch/session-after-refresh/park-walkthrough.mjs [ui-url]
//
// Run it against
//
//   node scripts/dev.mjs --scripted .scratch/session-after-refresh/evidence/06-go.json --ui-port 5319
//
// THE SETUP IS A REAL PARK, not a drawing of one: the first scripted turn calls
// `session-configure`, which is marked `:requires-approval`, so the run ends on a
// `RUN_FINISHED(outcome.interrupts)` and waits. The page is then reloaded, which is the
// case this ticket is about -- before it, the card was gone and the parked call could
// never be answered.
//
// WHAT IT ASSERTS, in order:
//
//   A. the run parks and the card is on screen; the composer is shut while it waits;
//   B. after a RELOAD the card is STILL there (the bug), the server still says `parked`,
//      and the composer is still shut -- a door with a way through it;
//   C. approving resumes the run, the parked call gets its result, the card goes, and
//      the answer lands in the same conversation.
//
// THE FACT IT READS IS THE SERVER'S: `GET /api/threads/<stem>/page` -> `state`, which is
// `parked` while the run waits. Nothing here is `sleep`-based.
//
// Screenshots land in .scratch/session-after-refresh/evidence/.
import fs from "node:fs";
import path from "node:path";
import { execSync } from "node:child_process";

const globalRoot = execSync("npm root -g", { encoding: "utf8" }).trim();
const { chromium } = await import(path.join(globalRoot, "playwright", "index.mjs"));

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

const drawn = () =>
  page.evaluate(() => {
    const send = document.querySelector(".aui-composer-send");
    return {
      sendPresent: send !== null,
      sendOffered: send !== null && !send.disabled,
      approvalShown: document.querySelector('[data-slot="approval-card"]') !== null,
      approveButton: document.querySelector(".aui-approval-card-approve") !== null,
      assistants: [...document.querySelectorAll('[data-slot="aui_assistant-message-content"]')].map(
        (el) => el.textContent?.trim() ?? "",
      ),
      bubbles: [...document.querySelectorAll('[data-slot="aui_user-message-root"]')].map(
        (el) => el.textContent?.trim() ?? "",
      ),
    };
  });

const serverState = (id) =>
  page.evaluate(async (threadId) => {
    const res = await fetch(`api/threads/${encodeURIComponent(threadId)}/page`);
    if (!res.ok) return `HTTP ${res.status}`;
    return (await res.json()).state ?? null;
  }, id);

const typed = async (text) => {
  await page.fill("textarea", text);
  await page.waitForTimeout(120);
  return drawn();
};

// ------------------------------------------------------------ A. it parks

await page.goto(url, { waitUntil: "load" });
await page.waitForSelector("textarea", { timeout: 20000 });
await page.fill("textarea", "先把这一场的配置改一下。");
await page.press("textarea", "Enter");

const threadId = await until(async () => (await page.evaluate(() => localStorage.getItem("clj-harness.session"))) !== null, 10000)
  ? await page.evaluate(() => localStorage.getItem("clj-harness.session"))
  : null;
check("the send remembers a conversation", threadId !== null, String(threadId));

check(
  "the run parks on the approval (the SERVER says parked)",
  await until(async () => (await serverState(threadId)) === "parked", 30000),
  await serverState(threadId),
);
check(
  "...and the approval card is on screen",
  await until(async () => (await drawn()).approvalShown, 15000),
  JSON.stringify(await drawn()),
);
const atPark = await typed("悬置期间这一句发不出去。");
check(
  "...and the composer is shut while it waits (even with text typed)",
  atPark.sendOffered === false,
  `sendOffered=${atPark.sendOffered} sendPresent=${atPark.sendPresent}`,
);
await shot("06-01-parked-with-the-card");

// ------------------------------------------------------------ B. the reload

await page.reload({ waitUntil: "load" });
await page.waitForSelector("textarea", { timeout: 20000 });
check(
  "the reload lands back in the parked conversation",
  await until(
    async () => (await drawn()).bubbles.some((text) => text.includes("先把这一场")),
    30000,
  ),
);
check(
  "...and the CARD IS STILL THERE (this is the ticket)",
  await until(async () => (await drawn()).approvalShown, 30000),
  JSON.stringify(await drawn()),
);
const stateAfterReload = await serverState(threadId);
check("...and the server still says parked", stateAfterReload === "parked", stateAfterReload);
const afterReload = await typed("刷新回来这一句也发不出去。");
check(
  "...and the composer is still shut -- a door with a way through it",
  afterReload.sendOffered === false,
  `sendOffered=${afterReload.sendOffered}`,
);
await shot("06-02-the-card-survived-the-reload");

// ------------------------------------------------------------ C. decide, and it answers

await page.click(".aui-approval-card-approve");
check(
  "approving resumes the run (the server leaves parked)",
  await until(async () => (await serverState(threadId)) !== "parked", 30000),
  await serverState(threadId),
);
check(
  "...and the parked call gets its result, so the run answers",
  await until(
    async () => (await drawn()).assistants.some((text) => text.includes("批准之后这一轮的回答")),
    60000,
  ),
  JSON.stringify((await drawn()).assistants),
);
const afterResume = await drawn();
check("...and the card is gone", afterResume.approvalShown === false);
check(
  "...and the ask is still in the same conversation",
  afterResume.bubbles.some((text) => text.includes("先把这一场")),
  JSON.stringify(afterResume.bubbles),
);
await shot("06-03-approved-and-answered");

await browser.close();
console.log(failures === 0 ? "\nALL GREEN" : `\n${failures} RED`);
process.exit(failures === 0 ? 0 : 1);
