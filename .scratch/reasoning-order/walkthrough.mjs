// THE WIRE'S OWN ORDER, on the page: a vendor that thinks AGAIN after the answer has
// started gets ONE 思考 row, in the place the model began it.
//
//   node .scratch/reasoning-order/walkthrough.mjs [ui-url]
//
// Run it against
//   node scripts/dev.mjs --scripted .scratch/reasoning-order/script.json --ui-port 5397
//
// WHY IT EXISTS. `harness.edge.ag-ui` keeps one reasoning message open across the answer
// and closes it at the end of the MODEL CALL; `ui/test/suites/client.ts` proves with the
// real client that such a stream arrives as ONE reasoning message, above the assistant
// one. What neither can see is the PAGE: that the row is drawn once, in the place the
// model began it, live while the thought arrives, and saying its first line once it stops.
//
// WHAT IT CANNOT SEE, and it is worth writing down rather than pretending otherwise (found
// by `scratch-timeline.mjs`, which samples the page every 30ms): A SCRIPTED TURN ARRIVES IN
// A BURST. The scripted provider emits a whole turn synchronously, so the server flushes
// its frames in one write, the throttle spreads only the BYTES, and the page commits the
// answer and the end of the thought in the same render (measured: the row settles at
// 3273ms and the answer is drawn at 3305ms). So "the row is still live WHILE the answer is
// being written" -- which is what the wire implies and what a person watching a real vendor
// sees -- is not an interval this harness has. What pins it instead: the frames themselves
// (`harness.edge.ag-ui-test`, from the record) and the client's own message list
// (`ui/test/suites/client.ts`).
//
// The script's turn is the vendor shape measured on a real session (2026-09-22):
// `:reasoning` -> `:content` -> `:reasoning-after` (`harness.fake` plays exactly that).
import fs from "node:fs";
import path from "node:path";
import { execSync } from "node:child_process";
import { fileURLToPath } from "node:url";

const HERE = path.dirname(fileURLToPath(import.meta.url));
const EVIDENCE = path.join(HERE, "evidence");
const globalRoot = execSync("npm root -g", { encoding: "utf8" }).trim();
const { chromium } = await import(path.join(globalRoot, "playwright", "index.mjs"));

const url = process.argv[2] ?? "http://localhost:5397/";
const THROTTLE = 6 * 1024; // bytes/sec, so the stream lasts seconds instead of one burst

let failures = 0;
function check(label, ok, detail = "") {
  console.log(`${ok ? "ok  " : "RED "} ${label}${detail === "" ? "" : ` -- ${detail}`}`);
  if (!ok) failures += 1;
}

const script = JSON.parse(fs.readFileSync(path.join(HERE, "script.json"), "utf8")).turns[0];
const firstLine = script.reasoning.split("\n").map((l) => l.trim()).find((l) => l !== "");
const answerHead = script.content.slice(0, 8);

const browser = await chromium.launch();
const page = await browser.newPage({ viewport: { width: 1200, height: 900 } });
page.on("pageerror", (e) => check("no page error", false, e.message));

const ROW = '[data-slot="reasoning-trigger"]';
const TAIL = '[data-slot="reasoning-trigger-tail"]';

/// The page as a reader has it: the rows (with where they sit), the live window, and
/// whether the answer has started being drawn.
const readPage = () =>
  page.evaluate(
    ([rowSel, tailSel, answer]) => {
      const rows = [...document.querySelectorAll(rowSel)].map((el) => {
        const b = el.getBoundingClientRect();
        const track = el.querySelector(tailSel)?.firstElementChild ?? null;
        return {
          text: el.textContent,
          top: Math.round(b.top),
          live: el.querySelector(tailSel) !== null,
          liveText: track?.textContent ?? null,
        };
      });
      const body = document.body.innerText;
      return {
        rows,
        answerDrawn: body.includes(answer),
        body: body.replace(/\s+/g, " ").slice(0, 200),
      };
    },
    [ROW, TAIL, answerHead],
  );

async function until(predicate, timeout = 60000, step = 40) {
  const deadline = Date.now() + timeout;
  for (;;) {
    const value = await predicate();
    if (value) return value;
    if (Date.now() > deadline) return null;
    await page.waitForTimeout(step);
  }
}

await page.goto(url);
const cdp = await page.context().newCDPSession(page);
await cdp.send("Network.enable");
await cdp.send("Network.emulateNetworkConditions", {
  offline: false,
  latency: 5,
  downloadThroughput: THROTTLE,
  uploadThroughput: THROTTLE,
});

await page.waitForSelector("textarea");
await page.fill("textarea", "想一下再答。");
await page.press("textarea", "Enter");

// ---------------------------------------------------------------- 1. one row, live
// AND THE ROW IS THE THOUGHT'S OWN WINDOW: the late reasoning is inside it (not a second
// message), which the two suites above pin on the wire; here what is asked is that the page
// draws ONE row at all.
const first = await until(async () => {
  const page_ = await readPage();
  return page_.rows.length > 0 ? page_ : null;
});
check("a 思考 row is drawn", first !== null && first.rows.length === 1, `${first?.rows.length ?? 0} rows`);
check("and it is live while the thought arrives", first?.rows[0]?.live === true);

// ------------------------------------------------------ 3. settled, and only once
const settled = await until(
  async () => {
    const page_ = await readPage();
    return page_.rows.length > 0 && !page_.rows[0].live ? page_ : null;
  },
  120000,
  50,
);
check("the thought ends and the row settles", settled !== null);
// THE LABEL IS THE PAGE'S LANGUAGE ("Thinking" here) -- the ROW is what this checks, so it
// is the subject after the separator that has to be the first line.
check(
  "exactly ONE row, saying its first line",
  settled?.rows.length === 1 && settled.rows[0].text.endsWith(` · ${firstLine}`),
  `rows=${settled?.rows.length}, text=${JSON.stringify(settled?.rows[0]?.text ?? "")}`,
);
check(
  "and nothing 思考-shaped is drawn under the answer",
  settled !== null && settled.rows.length === 1 && settled.rows[0].top < 900,
  settled?.body,
);
await page.screenshot({ path: path.join(EVIDENCE, "02-settled.png") });

await cdp.send("Network.emulateNetworkConditions", {
  offline: false,
  latency: 0,
  downloadThroughput: -1,
  uploadThroughput: -1,
});
await browser.close();
console.log(`\n${failures === 0 ? "GREEN" : `RED (${failures})`} -- screenshots in ${EVIDENCE}`);
process.exit(failures === 0 ? 0 : 1);