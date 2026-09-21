// A real-browser walkthrough of ticket 06's UI half: THE PAGE HOLDS A WINDOW, AND IT
// FOLLOWS THE CONVERSATION INSTEAD OF POLLING IT.
//
//   node scripts/dev.mjs --scripted .scratch/sessions-live-on-the-server/evidence/06-go.json --ui-port 5226
//   # it prints "temp config root <HOME> (a session's record is projects/<workspace>/<thread>.jsonl under it)"
//   node .scratch/sessions-live-on-the-server/evidence/06-walkthrough.mjs http://localhost:5226/ <HOME>
//
// This is the layer AGENTS.md asks for when `ui/src/` has been touched, and ticket 06
// names the two scenes it has to cover (judgement 8): refresh back into the same
// conversation, and click "show earlier" once. Both are here, plus the part of the
// ticket-05 acceptance box that was deferred to this ticket ("no `sofar` polling
// anywhere") -- which is a fact about REQUESTS OVER TIME, and therefore a fact only a
// browser can settle.
//
// FOUR SCENES, and what each one is evidence for:
//
//   1. A FRESH PAGE, A RUN, AND A MODEL CHANGED BY HAND. The page asks the server for an
//      id, sends a turn, and the composer's per-session model is switched through the
//      picker -- the thing the acceptance list wants changed "once in a round".
//   2. A REFRESH COMES BACK TO THE SAME CONVERSATION, AND THE WINDOW COMES OUT OF
//      MEMORY. The reload's own `GET /api/threads/<id>/page` answers `"live": true`,
//      which is ADR 0003 judgement 7 exactly: the record is allowed to lag, so a
//      refresh that read the record would show an EARLIER version than the screen it
//      replaced. The chosen model is still the chosen one, because it is the session's
//      and not the page's.
//   3. A SECOND PAGE WATCHES. This is where "no `sofar` polling" is settled: the old
//      watcher read `sofar` every 1200ms, and this page must show a run driven by the
//      OTHER page with ZERO `sofar` requests, one `page` and one `feed` -- the answer
//      arriving over the feed, with nothing asked while nothing happens.
//   4. "SHOW EARLIER" ON A CONVERSATION LONGER THAN A PAGE. The control appears only
//      when the server says there is more in front; one click adds exactly one page
//      (a double click does not put two in the air); the content that was on screen
//      stays where the reader's eye was -- anchoring, which is a layout fact this
//      script is the only place in the repo that can see -- and half-typed text is
//      still in the composer afterwards.
//
// WHAT IT CANNOT SEE: whether the anchoring looks GOOD (the pixel tolerance below is
// generous), and the very long tail of network behaviour (a proxy timing a stream out, a
// server restarting mid-feed). Those are what the feed's reconnect path is for, and the
// unit suites pin the arithmetic behind it.
import fs from "node:fs";
import path from "node:path";

import { chromium } from "/Users/zhouteng/.nvm/versions/node/v22.21.1/lib/node_modules/playwright/index.mjs";

const url = process.argv[2] ?? "http://localhost:5226/";
const home = process.argv[3];
if (!home) {
  console.log("usage: 06-walkthrough.mjs <ui-url> <config-root-printed-by-dev.mjs>");
  process.exit(2);
}

const VIEWPORT = '[data-slot="aui_thread-viewport"]';
const EARLIER = '[data-slot="window-earlier-button"]';
const COMPOSER = "textarea.aui-composer-input";
const MODEL_VALUE = '[data-slot="composer-model-value"]';
const MODEL_TRIGGER = '[data-slot="composer-model-trigger"]';
const MODEL_OPTION = '[data-slot="composer-model-option"]';

let red = false;
const say = (label, value) => console.log(`${label}: ${JSON.stringify(value)}`);
const note = (text) => console.log(`  ${text}`);
const fail = (text) => {
  console.log(`RED: ${text}`);
  red = true;
};

/// The requests one page made, so a claim about POLLING can be a claim about what the
/// browser actually did. Keyed by what the route is, which is the whole question here:
/// `page` opens a window, `feed` follows it, `sofar` is the read this ticket retired.
function watched(page) {
  const log = { page: [], feed: [], sofar: [] };
  page.on("request", (request) => {
    const path = new global.URL(request.url()).pathname;
    if (request.method() !== "GET") return;
    for (const key of ["page", "feed", "sofar"]) {
      if (path.endsWith(`/${key}`)) log[key].push(request.url());
    }
  });
  return log;
}

/// The conversation as the DOM shows it: each message's text, in order. THE EMPTY
/// ELEMENTS ARE FILTERED OUT -- the message group's first child draws nothing (it is a
/// layout slot, not a message), so leaving it in would make "the first message" an empty
/// string and quietly turn the anchor below into a comparison of two empty strings.
const shown = (page) =>
  page.$$eval('[data-slot="aui_message-group"] > *', (nodes) =>
    nodes.map((node) => node.innerText.replace(/\s+/g, " ").trim()).filter((text) => text !== ""),
  );

/// THE MESSAGE THE READER'S EYE IS ON: the first one in the viewport that has text, as
/// the app's own anchor picks it (`lib/window-scroll.ts`) -- and everything about it that
/// the check below needs.
///
/// IT IS NOT THE FIRST MESSAGE IN THE DOM. A conversation is read from the bottom, so the
/// first message is usually far above the viewport -- and a message with no text at all
/// (a tool call, a reasoning stub) is not something a person can be looking at.
const visibleAnchor = (page) =>
  page.evaluate(() => {
    const viewport = document.querySelector('[data-slot="aui_thread-viewport"]');
    const group = document.querySelector('[data-slot="aui_message-group"]');
    const edge = viewport.getBoundingClientRect().top;
    for (const element of group.children) {
      const text = element.innerText.replace(/\s+/g, " ").trim();
      const box = element.getBoundingClientRect();
      if (text === "" || box.bottom < edge) continue;
      return { text, top: box.top };
    }
    return null;
  });

/// Where that same message sits now, by its own text -- the same fallback the app uses,
/// because an import can replace the DOM node while the message itself is unchanged.
const topOf = (page, text) =>
  page.evaluate(
    ({ selector, needle }) => {
      const found = [...document.querySelectorAll(`${selector} > *`)].find(
        (node) => node.innerText.replace(/\s+/g, " ").trim() === needle,
      );
      return found === undefined ? null : found.getBoundingClientRect().top;
    },
    { selector: '[data-slot="aui_message-group"]', needle: text },
  );

/// Send one message through the composer and wait for the scripted answer to it. The
/// composer is found by its class, not its words: the placeholder is whichever language
/// this browser asked for, and that is not what is under test.
async function turn(page, text, answer) {
  await page.fill(COMPOSER, text);
  await page.keyboard.press("Enter");
  await page.waitForSelector(`text=${answer}`, { timeout: 30000 });
}

/// The session a page is in, as that page remembers it.
const remembered = (page) => page.evaluate(() => localStorage.getItem("clj-harness.session"));

const browser = await chromium.launch();

// ---- 1. a fresh page, a run, and a model changed by hand ----------------------------
const one = await browser.newPage();
one.on("pageerror", (e) => console.log(`  page error (one): ${e.message}`));
const oneLog = watched(one);
await one.goto(url, { waitUntil: "load" });
await one.waitForRequest((r) => r.method() === "POST" && r.url().includes("/api/sessions"), { timeout: 30000 });
let id = null;
for (let i = 0; i < 100 && id === null; i += 1) {
  id = await remembered(one);
  if (id === null) await new Promise((r) => setTimeout(r, 50));
}
say("the page's session (localStorage)", id);
if (id === null || id === "") fail("the page has no session to work in");

await turn(one, "走查 06：第一轮", "A1（脚本的第 1 条回答。）");
// A SESSION THIS PAGE JUST MINTED HAS NO WINDOW TO OPEN (there was no log to read, and
// its own run arrives on the run's own stream), so the feed appears at the reload below
// -- where the conversation exists and the page comes back to it.
say("a fresh session's window requests", { page: oneLog.page.length, feed: oneLog.feed.length });

// THE MODEL, CHANGED BY HAND (the acceptance list's "one round, one model change").
// A SCRIPTED HOME MAY OFFER ONLY ONE MODEL, and that is not a failure of this
// walkthrough: what is being checked is that a choice made here is the SESSION's, so it
// is still there after the reload below.
const modelBefore = (await one.textContent(MODEL_VALUE))?.trim() ?? null;
let modelAfter = modelBefore;
if ((await one.$(MODEL_TRIGGER)) !== null) {
  await one.click(MODEL_TRIGGER);
  await one.waitForSelector(MODEL_OPTION, { timeout: 10000 });
  const options = await one.$$eval(MODEL_OPTION, (nodes) =>
    nodes.map((node) => node.innerText.replace(/\s+/g, " ").trim()),
  );
  const index = options.findIndex((label) => label !== modelBefore);
  if (index === -1) {
    note(`the picker offers only ${JSON.stringify(options)} -- nothing to switch to`);
  } else {
    await one.locator(MODEL_OPTION).nth(index).click();
    await one.waitForTimeout(200);
    modelAfter = (await one.textContent(MODEL_VALUE))?.trim() ?? null;
  }
  await one.keyboard.press("Escape");
}
say("composer model", { before: modelBefore, after: modelAfter });

// ---- 2. a refresh comes back to the same conversation, out of memory -----------------
const pageAnswers = [];
one.on("response", async (response) => {
  if (response.request().method() !== "GET") return;
  if (!new global.URL(response.url()).pathname.endsWith("/page")) return;
  try {
    pageAnswers.push(await response.json());
  } catch {
    /* a response body already consumed or not JSON: this tally is evidence, not a gate */
  }
});
await one.reload({ waitUntil: "load" });
for (let i = 0; i < 100; i += 1) {
  if (await one.$("text=A1（脚本的第 1 条回答。）")) break;
  await new Promise((r) => setTimeout(r, 100));
}
if ((await remembered(one)) !== id) fail("the reload did not come back to the session the server named");
if (!(await one.$("text=A1（脚本的第 1 条回答。）"))) fail("the reloaded page has no conversation on screen");
if ((await one.textContent(MODEL_VALUE))?.trim() !== modelAfter) {
  fail("the reloaded page forgot the model this session was set to");
}
const liveAnswers = pageAnswers.filter((answer) => answer.live === true);
say("the reload's GET /page answers", pageAnswers.map((a) => ({ live: a.live, baseSeq: a.baseSeq, hasMore: a.hasMore })));
if (liveAnswers.length === 0) {
  fail("the refresh read the RECORD -- a window is supposed to open out of memory (judgement 7)");
}
say("the reload's window", { page: oneLog.page.length, feed: oneLog.feed.length });
if (oneLog.feed.length !== 1) fail(`the reloaded page did not open a feed: ${oneLog.feed.length} connections`);

// ---- 3. a second page watches, and the answer arrives over the feed ------------------
const two = await browser.newPage();
two.on("pageerror", (e) => console.log(`  page error (two): ${e.message}`));
const twoLog = watched(two);
await two.goto(url, { waitUntil: "load" });
await two.evaluate((session) => localStorage.setItem("clj-harness.session", session), id);
await two.reload({ waitUntil: "load" });
for (let i = 0; i < 100; i += 1) {
  if (await two.$("text=A1（脚本的第 1 条回答。）")) break;
  await new Promise((r) => setTimeout(r, 100));
}
say("the watcher's window", { page: twoLog.page.length, feed: twoLog.feed.length, sofar: twoLog.sofar.length });
const watchedBefore = { page: twoLog.page.length, feed: twoLog.feed.length, sofar: twoLog.sofar.length };

await turn(one, "走查 06：第二轮（旁观者看着）", "A2（脚本的第 2 条回答。）");
const seenByWatcher = await two
  .waitForSelector("text=A2（脚本的第 2 条回答。）", { timeout: 15000 })
  .then(() => true)
  .catch(() => false);
if (!seenByWatcher) fail("the watching page never saw the other page's run -- the feed did not carry it");
say("the watcher's requests after the other page's run", {
  page: twoLog.page.length - watchedBefore.page,
  feed: twoLog.feed.length - watchedBefore.feed,
  sofar: twoLog.sofar.length - watchedBefore.sofar,
});
if (twoLog.sofar.length !== 0) {
  fail(`the watching page polled sofar ${twoLog.sofar.length} times -- ticket 06 replaced that poll with the feed`);
}
if (twoLog.page.length !== 1) fail(`the watcher opened ${twoLog.page.length} windows, expected exactly one`);
if (twoLog.feed.length !== 1) fail(`the watcher opened ${twoLog.feed.length} feeds, expected exactly one`);

// ---- 4. "show earlier" on a conversation longer than a page --------------------------
// THE WINDOW ONLY HAS SOMETHING IN FRONT OF IT WHEN IT IS OPENED ON A CONVERSATION THAT
// IS ALREADY LONGER THAN A PAGE: a page that watched the conversation grow holds every
// frame it was pushed, so there is nothing in front of it to fetch. That is why this
// scene drives turns and then RELOADS -- the reload is what opens a tail page -- and what
// the acceptance list means by "open a long conversation".
let turns = 2;
let more = false;
for (let round = 0; round < 8 && !more; round += 1) {
  for (let i = 0; i < 5; i += 1) {
    turns += 1;
    await turn(one, `走查 06：第 ${turns} 轮`, `A${turns}（脚本的第 ${turns} 条回答。）`);
  }
  await one.reload({ waitUntil: "load" });
  for (let i = 0; i < 100; i += 1) {
    if (await one.$(`text=A${turns}（脚本的第 ${turns} 条回答。）`)) break;
    await new Promise((r) => setTimeout(r, 100));
  }
  more = (await one.$(EARLIER)) !== null;
}
say("turns driven before the window had more in front", turns);
if (!more) {
  fail(`after ${turns} turns and a reload the page still says there is nothing in front`);
} else {
  // THE READER'S PLACE, and half-typed text -- both of which "show earlier" must not
  // disturb (judgement 6).
  // THE READER IS AT THE TOP, WHICH IS WHERE A PERSON HAS TO BE TO SEE THE CONTROL: it
  // is drawn at the top of the conversation, so it is off-screen from the bottom, and a
  // reader who has just opened a long conversation and wants older history scrolls up to
  // it. (It is also the case the anchoring has to get right: at the bottom,
  // assistant-ui's own auto-scroll is holding the newest messages and would fight the
  // correction.)
  // A REAL WHEEL, not a programmatic scroll: the conversation sits at the bottom after a
  // reload and assistant-ui keeps it there for as long as it believes the reader has not
  // moved -- which is exactly what a wheel tells it, and what `scrollTo` does not. (This
  // was measured: the programmatic scroll was silently undone by the next layout.)
  await one.mouse.move(700, 300);
  for (let i = 0; i < 12; i += 1) {
    const at = await one.$eval(VIEWPORT, (node) => node.scrollTop);
    if (at < 4) break;
    await one.mouse.wheel(0, -1200);
    await one.waitForTimeout(60);
  }
  await one.waitForTimeout(300);
  if ((await one.$eval(VIEWPORT, (node) => node.scrollTop)) > 200) {
    fail("could not scroll the conversation to the top -- the control is not reachable");
  }
  const before = await shown(one);
  const beforeKids = await one.$$eval('[data-slot="aui_message-group"] > *', (n) => n.length);
  const anchor = await visibleAnchor(one);
  const anchorTop = anchor?.top ?? null;
  const scrollBefore = await one.$eval(VIEWPORT, (node) => node.scrollTop);
  await one.fill(COMPOSER, "走查 06：补页时打了一半的字");
  const pageAnswersBefore = oneLog.page.length;
  // TWO CLICKS, IN ONE TASK, AS FAST AS A BROWSER CAN PUT THEM: one page in the air at a
  // time is the claim, so the second must not reach the wire. They are dispatched from
  // inside the page rather than through two `click` calls, because a local page answer
  // arrives in a couple of milliseconds and the button would be enabled again between
  // them -- the guard that has to hold is the one in the handler (a ref, set
  // synchronously), not the disabled attribute React has not re-rendered yet.
  await one.evaluate((selector) => {
    const button = document.querySelector(selector);
    button.click();
    button.click();
  }, EARLIER);
  for (let i = 0; i < 100; i += 1) {
    const now = await shown(one);
    if (now.length > before.length) break;
    await new Promise((r) => setTimeout(r, 100));
  }
  const after = await shown(one);
  const afterKids = await one.$$eval('[data-slot="aui_message-group"] > *', (n) => n.length);
  const anchorAfter = anchor === null ? null : await topOf(one, anchor.text);
  const scrollAfter = await one.$eval(VIEWPORT, (node) => node.scrollTop);
  const pages = pageAnswers.slice(-2).map((a) => ({ live: a.live, baseSeq: a.baseSeq, hasMore: a.hasMore }));
  say("messages on screen", { before: before.length, after: after.length });
  say("the anchor message", { text: anchor?.text?.slice(0, 24) ?? null, before: anchorTop, after: anchorAfter });
  say("the viewport's scrollTop", { before: scrollBefore, after: scrollAfter });
  say("the pages involved", pages);
  // THE PAGE THAT ARRIVED IS THE ONE IN FRONT: its `baseSeq` is behind the tail the page
  // was holding (the acceptance's "the page that arrives is the adjacent one" -- the
  // adjacency itself is pinned by the suites, which can see the answer's numbers).
  if (afterKids <= beforeKids) fail("clicking 'show earlier' added nothing to the conversation");
  if (pages.length !== 2 || !(pages[1].baseSeq < pages[0].baseSeq)) {
    fail(`the page that arrived is not in front of the one on screen: ${JSON.stringify(pages)}`);
  }
  if (oneLog.page.length - pageAnswersBefore !== 1) {
    fail(`two clicks put ${oneLog.page.length - pageAnswersBefore} page requests on the wire -- one page at a time`);
  }
  if (anchor === null || anchorTop === null || anchorAfter === null || Math.abs(anchorAfter - anchorTop) > 4) {
    fail(`the message being read moved ${anchorTop} -> ${anchorAfter} -- a prepend must not move the reader's eye`);
  }
  if ((await one.inputValue(COMPOSER)) !== "走查 06：补页时打了一半的字") {
    fail("the half-typed text did not survive the prepend");
  }
  await one.screenshot({ path: path.join(import.meta.dirname, "06-window-earlier.png"), fullPage: true });
}

// ---- what is on disk, since the temp homes are deleted on exit -----------------------
say("the owner page's window requests", {
  page: oneLog.page.length,
  feed: oneLog.feed.length,
  sofar: oneLog.sofar.length,
  turns,
});
const threads = await one.evaluate(async () => (await fetch("api/threads")).json());
const mine = (threads ?? []).filter((t) => t.threadId === id);
say("GET /api/threads", { mine: mine.length, listed: (threads ?? []).length });
if (mine.length !== 1) fail(`this home lists ${mine.length} conversations for the page's id, expected 1`);
const record = (() => {
  const stack = [path.join(home, "projects")];
  while (stack.length > 0) {
    const dir = stack.pop();
    for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
      const full = path.join(dir, entry.name);
      if (entry.isDirectory()) stack.push(full);
      else if (entry.name === `${id}.jsonl`) return full;
    }
  }
  return null;
})();
say("the record", { path: record, lines: record === null ? 0 : fs.readFileSync(record, "utf8").split("\n").filter((l) => l.trim() !== "").length });
if (record === null) fail("this conversation has no record at all");

await browser.close();

console.log(
  red
    ? "RED: the walkthrough failed"
    : `GREEN: the window opened out of memory after a reload (${liveAnswers.length} live page reads), a second page watched a run with no sofar polling at all, and "show earlier" added one page, kept the reader's place and left the half-typed text alone`,
);
process.exit(red ? 1 : 0);
