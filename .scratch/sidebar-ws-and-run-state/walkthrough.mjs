// A real-browser walkthrough of ticket 02 of this feature: the sidebar's listing is
// read over HTTP ONCE (mount), and every later change -- a run starting or ending on
// this page or another, a new session's row -- arrives as a PUSH over `events.host`.
// The refresh button stays as the fallback for a socket that cannot connect, not as a
// normal path.
//
//   node .scratch/sidebar-ws-and-run-state/walkthrough.mjs [ui-url]
//
// Run it against
//   node scripts/dev.mjs --scripted .scratch/sidebar-ws-and-run-state/script.json --ui-port 5242
// (a temp home, a scripted provider whose first turn sleeps, so a run is IN FLIGHT for
// seconds rather than milliseconds -- that window is what makes the run-state claims
// assertions instead of races).
//
// WHAT IT ASSERTS:
//
//   1. TWO WINDOWS, and the run's END: page A's run is held open by the script's sleep;
//      page B (loaded while it is in flight) draws the row saying 运行中, and when the
//      run ends the row goes idle IN B -- with ZERO further `GET /api/projects` on B.
//      That is the push carrying a fact that used to need a re-read.
//   2. A SECOND SEND: once the first run has ended, A sends again; B's sidebar hears it
//      over the socket (`events.host` frames increase) and still fetches NOTHING.
//   3. THE REFRESH BUTTON still works (one fetch, the fallback's contract).
//
// WHAT IT DELIBERATELY DOES NOT CLAIM: that a session SWITCH reads nothing. Switching a
// session in B mounts that session's composer, whose directory picker has a listing read
// of its own (`components/composer-chrome.tsx`'s `useRemote(listSidebar)`) -- a different
// component's question, and a later ticket's if it wants the same treatment. This
// walkthrough is about the SIDEBAR.
//
// Screenshots land in .scratch/sidebar-ws-and-run-state/evidence/.
import fs from "node:fs";
import path from "node:path";

// Playwright comes from whatever install this machine has: the global npm root if one is
// there, else PLAYWRIGHT_PATH names the module directly. (This walkthrough is a local
// evidence tool, not a dependency of the tree.)
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

const url = process.argv[2] ?? "http://localhost:5242/";

const evidence = path.resolve(".scratch/sidebar-ws-and-run-state/evidence");
fs.mkdirSync(evidence, { recursive: true });

let failures = 0;
function check(label, ok, detail = "") {
  console.log(`${ok ? "ok  " : "RED "} ${label}${detail === "" ? "" : ` -- ${detail}`}`);
  if (!ok) failures += 1;
}

/// The listing reads a page has made, WITH their times -- read off performance's own
/// entries rather than a fetch wrapper (a wrapper is lost on every navigation, and this
/// file reloads nothing but it does not have to be lucky either).
const projectGets = (page) =>
  page.evaluate(() =>
    performance.getEntriesByType("resource")
      .filter((e) => e.name.includes("/api/projects"))
      .map((e) => Math.round(e.startTime)),
  );

/// HOW MANY LISTING FRAMES THIS PAGE'S `events.host` SOCKET HAS RECEIVED, counted before
/// the page loads (so the opening frame is in the count) -- the direct proof that the
/// sidebar is being PUSHED rather than asking again.
const trackHostFrames = (page) =>
  page.addInitScript(() => {
    window.__hostFrames = 0;
    const OW = window.WebSocket;
    window.WebSocket = function (url, protocols) {
      const ws = protocols === undefined ? new OW(url) : new OW(url, protocols);
      if (String(url).includes("events.host")) {
        ws.addEventListener("message", () => {
          window.__hostFrames += 1;
        });
      }
      return ws;
    };
    window.WebSocket.prototype = OW.prototype;
    window.WebSocket.OPEN = OW.OPEN;
    window.WebSocket.CONNECTING = OW.CONNECTING;
    window.WebSocket.CLOSING = OW.CLOSING;
    window.WebSocket.CLOSED = OW.CLOSED;
  });
const hostFrames = (page) => page.evaluate(() => window.__hostFrames);

/// WHAT THE SIDEBAR DRAWS, as one string.
const sidebarText = (page) =>
  page.evaluate(() => {
    const a = document.querySelectorAll("aside")[0];
    return a ? a.textContent : "";
  });

/// Whether the sidebar says a run is going. The row draws the word 运行中 (the zh shell
/// catalog), and the check is on the SIDEBAR's text rather than the store's, because the
/// sidebar is the thing under test.
const saysRunning = (page) => sidebarText(page).then((text) => text.includes("运行中"));

const TASK = "推送验证:这一行应该自己出现在另一个窗口";

const browser = await chromium.launch();
// SEPARATE CONTEXTS, and that is a correctness point rather than tidiness: pages in one
// context share localStorage, so B would restore the session A minted, mount its composer,
// and that composer's directory picker would read the listing -- a read that has nothing to
// do with the sidebar under test. Two windows on this machine are two contexts.
const contextA = await browser.newContext({ viewport: { width: 1100, height: 800 }, locale: "zh-CN" });
const contextB = await browser.newContext({ viewport: { width: 1100, height: 800 }, locale: "zh-CN" });
const pageA = await contextA.newPage();
pageA.on("pageerror", (e) => check("A: no page error", false, e.message));

await pageA.goto(url, { waitUntil: "load" });
await pageA.waitForSelector('[data-slot="sidebar-tasks"], [data-slot="sidebar-empty"]', { timeout: 15000 });
const mountGetsA = (await projectGets(pageA)).length;
check("A: the first read is over HTTP", mountGetsA >= 1, `${mountGetsA} fetch(es) by mount`);

// A mints a task and sends. The script's first turn SLEEPS, so this run is in flight for
// seconds -- long enough for B to join and see the row wearing its spinner.
await pageA.getByRole("button", { name: "新建任务" }).click();
const boxA = pageA.getByRole("textbox", { name: "消息输入框" });
await boxA.fill(TASK);
await boxA.press("Enter");
await pageA.waitForTimeout(1200);

// ------------------------------------------------------------------ page B joins
const pageB = await contextB.newPage();
pageB.on("pageerror", (e) => check("B: no page error", false, e.message));
await trackHostFrames(pageB);
await pageB.goto(url, { waitUntil: "load" });
await pageB.waitForSelector('[data-slot="sidebar-tasks"], [data-slot="sidebar-empty"], [data-slot="sidebar-project"]', { timeout: 15000 });
const mountGetsB = (await projectGets(pageB)).length;
const framesAtMount = await hostFrames(pageB);

check("B: A's row is in B's sidebar", (await sidebarText(pageB)).includes(TASK), `mount gets=${mountGetsB}`);
check("B: the run is still in flight, and B's row says so", await saysRunning(pageB));
await pageB.screenshot({ path: path.join(evidence, "b-during-run.png") });

// --------------------------------------------------- the run's END, arrived by push
// Nothing is clicked in B. The row must go idle, and B must not have read the listing
// again: that is the push carrying a fact that used to require one.
await pageB.waitForFunction(() => {
  const a = document.querySelectorAll("aside")[0];
  return a !== undefined && !a.textContent.includes("运行中");
}, undefined, { timeout: 30000 });

const getsAfterRun = (await projectGets(pageB)).length;
const framesAfterRun = await hostFrames(pageB);
check("B: the run ending moved the row without a read", getsAfterRun === mountGetsB,
  `gets mount=${mountGetsB} after=${getsAfterRun}`);
check("B: the change arrived over events.host", framesAfterRun > framesAtMount,
  `frames ${framesAtMount} -> ${framesAfterRun}`);
await pageB.screenshot({ path: path.join(evidence, "b-after-run.png") });

// ------------------------------------------------------ a SECOND send, still no read
// The first run is over, so this one really starts (a send while a run is going is
// refused by the run edge -- 409 -- which is not this ticket's question). The row's NAME
// does not move (a title is written once, from the first message), so the evidence here is
// the socket and the fetch count, not the words.
const framesBeforeSend = await hostFrames(pageB);
await boxA.fill("推送验证:第二条,窗口 B 不许重新拉取");
await boxA.press("Enter");
// WAIT FOR THE SOCKET, NOT FOR A SPINNER: this run has no sleep in it (only the first turn
// does), so 运行中 can come and go between two samples. The push is the fact being asserted.
await pageB.waitForFunction(
  (before) => window.__hostFrames > before,
  framesBeforeSend,
  { timeout: 30000 },
);

const framesAfterSend = await hostFrames(pageB);
const getsAfterSend = (await projectGets(pageB)).length;
check("B: the second run's start arrived over events.host", framesAfterSend > framesBeforeSend,
  `frames ${framesBeforeSend} -> ${framesAfterSend}`);
check("B: no /api/projects fetch after mount", getsAfterSend === mountGetsB,
  `mount=${mountGetsB} now=${getsAfterSend}`);

// ------------------------------------------------------------------ the fallback
// The button still works: exactly one listing read.
const getsBeforeButton = (await projectGets(pageB)).length;
await pageB.locator('[data-slot="sidebar-refresh"]').click();
await pageB.waitForTimeout(800);
const getsAfterButton = (await projectGets(pageB)).length;
check("B: the refresh button still reads once", getsAfterButton === getsBeforeButton + 1,
  `before=${getsBeforeButton} after=${getsAfterButton}`);

await browser.close();
if (failures > 0) {
  console.log(`\n${failures} check(s) RED`);
  process.exit(1);
}
console.log("\nall checks green");
