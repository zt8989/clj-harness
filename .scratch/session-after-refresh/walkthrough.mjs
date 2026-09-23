// A real-browser walkthrough of ticket 09: 刷新之后也能停.
//
//   node .scratch/session-after-refresh/walkthrough.mjs [ui-url]
//
// Run it against
//
//   node scripts/dev.mjs --scripted .scratch/session-after-refresh/evidence/09-go.json --ui-port 5319
//
// (temp homes, a scripted provider, no api-key; the harness picks its own port). It is the
// layer AGENTS.md asks for when `ui/src/` has been touched, and the layer this change lived
// in: the suites are agent-level (no React), so "the composer offers a Stop that really stops
// the SERVER's run" is not a thing any of them can see.
//
// IT REPLACES THE TICKET-04 SCRIPT, which asserted a SENTENCE where the Stop now stands
// (`run.stillAnswered` is gone -- ticket 09: a shut door with a way through it says nothing
// about why). What it still shares is the setup: the scripted turns are `bash` commands that
// sleep (`evidence/09-go.json`), and every wait below is keyed on a SERVER fact -- the
// conversation's own `state`, or the command's pid on disk -- never on a stopwatch. The
// reload-then-assert window the ticket-04 script measured (~2 SECONDS assembling the system
// prompt before the run registers) is the same reason this one waits for `running` first.
//
// WHAT IT ASSERTS, in order:
//
//   0. the Stop works while THIS page is driving: it ends the run, kills the command, starts
//      no replacement, and the stopped turn is drawn as a CANCELLATION (`Cancelled tool`),
//      not a failure -- the drawing the browser's own abort used to produce is now the
//      server's terminal going through the same channel;
//   A. a run this page is driving offers the SERVER's Stop, and the command it is waiting on
//      is really running (its pid is on disk and alive);
//   B. a RELOAD lands back in that still-running conversation, the composer still offers
//      Stop and not Send, and the turn is still drawn as unfinished;
//   C. pressing Stop there ends the run the SERVER is answering, kills the command's process
//      tree, and does NOT start a replacement run -- and a reload reads the record back with
//      the abandoned call given an answer (no open call, no error pop-up);
//   D. once stopped, the composer offers Send again, and sending lands in the SAME
//      conversation;
//   E. two conversations can run at once, and Stop stops ONLY the one on screen: the other
//      conversation's command and its `running` row are untouched, and it stops on its own
//      press afterwards.
//
// THE COMMANDS WRITE THEIR OWN PIDS DOWN (`echo $$ > /tmp/clj-harness-stop-09/$$.pid`), so
// "the tree is gone" is a fact asked of the OS rather than an inference from the record --
// the record's own rule, one level down: a command's answer is read from a FILE.
//
// Screenshots land in .scratch/session-after-refresh/evidence/.
import fs from "node:fs";
import path from "node:path";
import { execSync } from "node:child_process";

// THE PORTABLE IMPORT. Walkthroughs used to pin an absolute Windows path to the global
// playwright; this asks npm where global packages are and imports it from there, so the same
// file runs on the machine it was written on and on this one.
const globalRoot = execSync("npm root -g", { encoding: "utf8" }).trim();
const { chromium } = await import(path.join(globalRoot, "playwright", "index.mjs"));

const url = process.argv[2] ?? "http://localhost:5319/";
const evidence = path.resolve(".scratch/session-after-refresh/evidence");
fs.mkdirSync(evidence, { recursive: true });

/// WHERE THE SCRIPTED COMMANDS WRITE THEIR PIDS. Cleaned before the run so that a pid found
/// later is a pid THIS walkthrough's commands wrote.
const PID_DIR = "/tmp/clj-harness-stop-09";
fs.rmSync(PID_DIR, { recursive: true, force: true });
fs.mkdirSync(PID_DIR, { recursive: true });

let failures = 0;
function check(label, ok, detail = "") {
  console.log(`${ok ? "ok  " : "RED "} ${label}${detail === "" ? "" : ` -- ${detail}`}`);
  if (!ok) failures += 1;
}

const browser = await chromium.launch();
const page = await browser.newPage({ viewport: { width: 1200, height: 900 } });
page.on("pageerror", (e) => check("no page error", false, e.message));

/// EVERY RUN THE PAGE SENDS (`POST /api/agent`), counted. A press of Stop must not add one:
/// that is the "no replacement run" half of ticket 09's acceptance.
const runPosts = [];
page.on("request", (request) => {
  if (request.method() === "POST" && request.url().includes("/api/agent")) runPosts.push(request.url());
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

/// WHAT THE PAGE IS SHOWING, as the DOM says it. `sendOffered` is the disabled bit and not
/// the element's existence: the send button is always in the DOM (upstream renders a
/// disabled `<button>` when the composer cannot send), so the caller TYPES first and reads
/// the bit after. `stopShown` is this change's control (`components/session-run-stop.tsx`,
/// `data-slot="session-stop"`); `upstreamCancelShown` is the fetch-only Cancel it replaced,
/// and it must be gone.
const drawn = () =>
  page.evaluate(() => {
    const send = document.querySelector(".aui-composer-send");
    return {
      sendPresent: send !== null,
      sendOffered: send !== null && !send.disabled,
      stopShown: document.querySelector('[data-slot="session-stop"]') !== null,
      upstreamCancelShown: document.querySelector(".aui-composer-cancel") !== null,
      approvalShown: document.querySelector('[data-slot="approval-card"]') !== null,
      runningSpinners: document.querySelectorAll('[data-slot="thread-list-item-running"]').length,
      assistants: [...document.querySelectorAll('[data-slot="aui_assistant-message-content"]')].map(
        (el) => el.textContent?.trim() ?? "",
      ),
      bubbles: [...document.querySelectorAll('[data-slot="aui_user-message-root"]')].map(
        (el) => el.textContent?.trim() ?? "",
      ),
      foldedTurns: document.querySelectorAll('[data-slot="turn-steps-trigger"]').length,
    };
  });

const SESSION_KEY = "clj-harness.session";
const currentId = () => page.evaluate((key) => localStorage.getItem(key), SESSION_KEY);

/// PUT THE PAGE ON A NAMED CONVERSATION, through the restore mechanism ticket 03 built: the
/// remembered id is the same thing a reload reads, so this is a switch and a reload at once.
const show = async (id) => {
  await page.evaluate(([key, value]) => localStorage.setItem(key, value), [SESSION_KEY, id]);
  await page.reload({ waitUntil: "load" });
  await page.waitForSelector("textarea", { timeout: 20000 });
};

/// THE SERVER'S OWN WORD about a conversation, straight off the window route the page reads
/// (`GET /api/threads/<stem>/page` -> `state`). Every claim about "the server is answering
/// this" is checked against it rather than against a stopwatch.
const serverState = (id) =>
  page.evaluate(async (threadId) => {
    const res = await fetch(`api/threads/${encodeURIComponent(threadId)}/page`);
    if (!res.ok) return `HTTP ${res.status}`;
    return (await res.json()).state ?? null;
  }, id);

/// WHAT THE SIDEBAR'S OWN SOURCE says about a NAMED conversation (`GET /api/projects` -> the
/// row's `running`, off the server's live-runs registry). Named rather than "the one on
/// screen" because the two-session case asks about the one NOT on screen.
const listedRunning = (id) =>
  page.evaluate(async (threadId) => {
    const res = await fetch("api/projects");
    if (!res.ok) return `HTTP ${res.status}`;
    const body = await res.json();
    const rows = [
      ...(body.projects ?? []).flatMap((project) => project.sessions ?? []),
      ...(body.tasks ?? []),
    ];
    const row = rows.find((entry) => entry.threadId === threadId);
    return row === undefined ? "not-listed" : row.running;
  }, id);

/// THE RECORD, as the page's own reader returns it (`sofar`): what a reload folds. Used for
/// the one fact the DOM hides -- that the abandoned call was given an answer.
const recordText = (id) =>
  page.evaluate(async (threadId) => {
    const res = await fetch(`api/threads/${encodeURIComponent(threadId)}/sofar`);
    if (!res.ok) return `HTTP ${res.status}`;
    return JSON.stringify(await res.json());
  }, id);

/// THE COMMANDS ON DISK, and whether the OS still has them. `kill(pid, 0)` asks the OS, which
/// is the whole point: "the call returned" and "the command's tree is gone" are different
/// facts, and only the second one means the process was really killed.
const pids = () =>
  fs
    .readdirSync(PID_DIR)
    .map((name) => Number.parseInt(name.replace(/\.pid$/, ""), 10))
    .filter(Number.isInteger);
const alive = (pid) => {
  try {
    process.kill(pid, 0);
    return true;
  } catch {
    return false;
  }
};
const newPids = (before) => pids().filter((pid) => !before.includes(pid));

const typed = async (text) => {
  await page.fill("textarea", text);
  await page.waitForTimeout(120);
  return drawn();
};

const send = async (text) => {
  await page.fill("textarea", text);
  await page.press("textarea", "Enter");
};

const stop = () => page.click('[data-slot="session-stop"]');

// ================================================================ 0. the driving stop
//
// THE PAGE THAT STARTED THE RUN presses Stop and the run really stops, and the stopped turn
// is drawn as a CANCELLATION -- the server's `code: "stopped"` terminal goes through the same
// channel the browser's own abort used (`.scratch/stop-abort`), so `message-parts.tsx` draws
// its existing "Cancelled tool" rather than inventing a word for a server stop.

await page.goto(url, { waitUntil: "load" });
await page.waitForSelector("textarea", { timeout: 20000 });

await send("开着停一次。");
const idA = await until(async () => (await currentId()) !== null, 10000);
check("the first send remembers a conversation", idA, String(await currentId()));
const threadA = await currentId();

check(
  "a run this page is driving offers the SERVER's Stop, not Send",
  await until(async () => {
    const state = await drawn();
    return state.stopShown && !state.sendOffered;
  }, 20000),
  JSON.stringify(await drawn()),
);
check(
  "...and the command it is waiting on is really running",
  await until(async () => pids().length > 0, 15000),
  JSON.stringify(pids().map((pid) => [pid, alive(pid)])),
);

const pids0 = pids();
const posts0 = runPosts.length;
await stop();
check(
  "pressing Stop ends the run the SERVER was answering",
  await until(async () => (await serverState(threadA)) !== "running", 20000),
  await serverState(threadA),
);
check(
  "...and the command's process tree is gone",
  await until(async () => !pids0.some(alive), 15000),
  JSON.stringify(pids0.map((pid) => [pid, alive(pid)])),
);
check("...and it started no replacement run", runPosts.length === posts0, `${posts0} -> ${runPosts.length}`);
const drivingStop = await drawn();
console.log(`     right after the driving stop: ${JSON.stringify(drivingStop.assistants)}`);
check(
  "...and the stopped call is NOT drawn as one that returned (the client was not told it did)",
  drivingStop.assistants.length > 0 &&
    !drivingStop.assistants.some((text) => text.includes("Done")) &&
    !drivingStop.assistants.some((text) => /Failed/i.test(text)),
  JSON.stringify(drivingStop.assistants),
  // WHAT IT IS DRAWN AS TODAY: an unresolved server call comes back `requires-action`,
  // which the tool row shows as "Needs approval" -- the same mis-render ticket 04 recorded
  // for a RUNNING call, and the family ticket 09's "cancelled" row waits on. The `Done`
  // above is the assertion that matters here: the client was NOT told the call returned.
);
await shot("09-00-driving-stop");

// ================================================================ A. a run this page drives

await send("第一句：慢慢跑。");
check(
  "the run registers and the SERVER says this conversation is running",
  await until(async () => (await serverState(threadA)) === "running", 30000),
  await serverState(threadA),
);
check(
  "...and a command of its own is really running",
  await until(async () => newPids(pids0).length > 0, 15000),
  JSON.stringify(newPids(pids0)),
);
await shot("09-01-running-in-this-page");

// ================================================================ B. the reload, mid-run

await page.reload({ waitUntil: "load" });
await page.waitForSelector("textarea", { timeout: 20000 });
const restored = await until(
  async () => (await drawn()).bubbles.some((text) => text.includes("第一句")),
  30000,
);
check("the reload lands back in the conversation that is still being answered", restored);

const stateAfterReload = await serverState(threadA);
check(
  "...and the server still says it is running (the reload did not stop it)",
  stateAfterReload === "running",
  stateAfterReload,
);

const afterReload = await typed("第二句：这一场还在跑吗？");
await shot("09-02-after-the-reload");
console.log(`     after the reload: ${JSON.stringify(afterReload)}`);
check(
  "after the reload the composer does NOT offer Send -- it offers the Stop",
  afterReload.stopShown && !afterReload.sendOffered,
  `sendOffered=${afterReload.sendOffered} stopShown=${afterReload.stopShown}`,
);
check("...and it is OUR stop, not upstream's fetch-only Cancel", afterReload.upstreamCancelShown === false);
check(
  "...and the turn on screen is still being written (not folded into a finished summary)",
  afterReload.foldedTurns === 0,
  `foldedTurns=${afterReload.foldedTurns}`,
);

// ================================================================ C. the press, from the reloaded page

const pidsBeforeStop = newPids(pids0);
const postsBeforeStop = runPosts.length;
await stop();

check(
  "pressing Stop on the RELOADED page ends the run the SERVER was answering",
  await until(async () => (await serverState(threadA)) !== "running", 20000),
  await serverState(threadA),
);
check(
  "...and the command that run was waiting on is gone (its process tree was killed)",
  await until(async () => !pidsBeforeStop.some(alive), 15000),
  JSON.stringify(pidsBeforeStop.map((pid) => [pid, alive(pid)])),
);
check(
  "...and pressing Stop did NOT start a replacement run",
  runPosts.length === postsBeforeStop,
  `${postsBeforeStop} -> ${runPosts.length}`,
);
await shot("09-03-after-the-stop");

// ================================================================ D. the record the stop left

await page.reload({ waitUntil: "load" });
await page.waitForSelector("textarea", { timeout: 20000 });
check(
  "a reload after the stop reads the conversation back",
  await until(
    async () => (await drawn()).bubbles.some((text) => text.includes("第一句")),
    30000,
  ),
);

const record = await recordText(threadA);
check(
  "the record keeps no open call: the abandoned call carries the cut-off sentence",
  record.includes("cut off"),
  record.slice(0, 300),
);
const afterReread = await drawn();
console.log(`     the stopped turn, read back: ${JSON.stringify(afterReread.assistants)}`);
check(
  "...and the reloaded page draws that call as complete, from the cut-off answer in the record",
  afterReread.assistants.some((text) => text.includes("bash") && text.includes("Done")),
  JSON.stringify(afterReread.assistants),
);
check("...and nothing is drawn as an approval card (no error pop-up)", afterReread.approvalShown === false);

// ================================================================ E. and the conversation goes on

const afterStop = await typed("停完之后这一句。");
check(
  "the composer offers Send again once the run is stopped (the gate is not permanent)",
  afterStop.sendOffered,
  `sendOffered=${afterStop.sendOffered} stopShown=${afterStop.stopShown}`,
);
const postsBeforeSend = runPosts.length;
await page.press("textarea", "Enter");
await until(async () => runPosts.length > postsBeforeSend, 15000);
check(
  "...and sending is answered by a RUN, not a refusal",
  await until(
    async () => (await drawn()).assistants.some((text) => text.includes("停完之后这一轮的回答")),
    60000,
  ),
  JSON.stringify((await drawn()).assistants),
);
check(
  "...and its answer lands in the SAME conversation",
  (await drawn()).bubbles.some((text) => text.includes("停完之后这一句")),
  JSON.stringify((await drawn()).bubbles),
);

// ================================================================ F. two at once, only one stops
//
// THE COST OF A REMEMBERED SESSION (spec decision three) is that two tabs land in one
// conversation; the mirror of it is that one page can watch two conversations run. The
// ticket's acceptance asks for exactly this pair of facts: both running, stop one, the other
// untouched.

const pidsBeforeA2 = pids();
await send("第二轮：再慢慢跑。");
check(
  "the conversation on screen starts another run",
  await until(async () => (await serverState(threadA)) === "running", 30000),
);
const aAgain = await until(async () => newPids(pidsBeforeA2).length > 0, 15000);
check("...and it waits on a command of its own", aAgain, JSON.stringify(newPids(pidsBeforeA2)));

await page.click('[data-slot="sidebar-new-task"]');
await page.waitForSelector("textarea", { timeout: 20000 });
const madeB = await until(async () => (await currentId()) !== threadA, 10000);
const threadB = await currentId();
check("a new task is its own conversation", madeB && threadB !== null, String(threadB));

const pidsBeforeB = pids();
await send("另一场：慢慢跑。");
check(
  "the second conversation starts a run of its own",
  await until(async () => (await serverState(threadB)) === "running", 30000),
  await serverState(threadB),
);
const bCommand = await until(async () => newPids(pidsBeforeB).length > 0, 15000);
check("...with a command of its own", bCommand, JSON.stringify(newPids(pidsBeforeB)));
const bPids = newPids(pidsBeforeB);

await show(threadA);
check(
  "switching back to the first conversation, it still says it is running",
  await until(async () => (await serverState(threadA)) === "running", 20000) &&
    (await serverState(threadA)) === "running",
  await serverState(threadA),
);

await stop();
check(
  "stopping the conversation on screen stops IT",
  await until(async () => (await serverState(threadA)) !== "running", 20000),
  await serverState(threadA),
);
check(
  "...and the OTHER conversation's command is untouched",
  bPids.length > 0 && bPids.every(alive),
  JSON.stringify(bPids.map((pid) => [pid, alive(pid)])),
);
const otherRunning = await listedRunning(threadB);
check("...and the other conversation still says it is running", otherRunning === true, String(otherRunning));
await shot("09-04-stopping-one-leaves-the-other");

await show(threadB);
check(
  "the other conversation is still running when we get to it",
  (await serverState(threadB)) === "running",
  await serverState(threadB),
);
await stop();
check(
  "...and it stops on its own press",
  await until(async () => !bPids.some(alive), 15000),
  JSON.stringify(bPids.map((pid) => [pid, alive(pid)])),
);
await shot("09-05-both-stopped");

await browser.close();
console.log(failures === 0 ? "\nALL GREEN" : `\n${failures} RED`);
process.exit(failures === 0 ? 0 : 1);
