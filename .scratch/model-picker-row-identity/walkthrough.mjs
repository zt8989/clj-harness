#!/usr/bin/env node
//
// A real-browser walkthrough of `.scratch/model-picker-row-identity`'s ticket.
//
//   node scripts/dev.mjs --scripted --ui-port 5399
//   node .scratch/model-picker-row-identity/walkthrough.mjs http://localhost:5399 <home-dir>
//
// HOME-DIR is the temp config root dev.mjs prints ("dev.mjs: temp config root …"), and it
// is REQUIRED here: this ticket is about a home that holds TWO VENDORS DECLARING THE SAME
// MODEL ID, so the script seeds exactly that (plus a key for each, and a default tier
// naming the first of them) before the browser opens. A home whose vendors declare
// different ids cannot tell the two behaviours apart, and a walkthrough that ran against
// one would be a green run about nothing.
//
// ------------------------------------------------------------------ what only this sees
//
// THE RULE -- a row is named by its vendor AND its id, and the menu's rows are one pure
// answer -- is pinned by `ui/test/suites/picker.ts` over literals, because `lib/model-rows.ts`
// asks for nothing a browser has. What NO vitest case can see is the WIRE: that a click on a
// row inside one vendor's run sends THAT vendor. So the claim below is read off the request
// body rather than off the screen -- the screen can only say which row was clicked, and the
// bug this ticket is about was in what got sent (the catalog's FIRST vendor declaring that
// id, because the picker re-derived the vendor from the model id).
//
// The seeding takes effect without a restart: `config.edn` and `.env` are read per request
// (there is no cache to invalidate -- see `harness.cap.providers/api-key-source`).
import { execSync } from "node:child_process";
import fs from "node:fs";
import { createRequire } from "node:module";
import path from "node:path";
import { fileURLToPath } from "node:url";

/// PLAYWRIGHT IS RESOLVED, NOT SPELLED OUT. A committed script cannot hold one machine's
/// absolute path -- that is how the previous walkthrough in this repo became a Windows path
/// in a macOS checkout. So: the project's own dependency if it has one, the global install
/// otherwise (`ui/` deliberately does not depend on playwright: the browser is this repo's
/// outer layer, not its test runner's).
const require = createRequire(import.meta.url);
function loadChromium() {
  try {
    return require("playwright").chromium;
  } catch {
    const root = execSync("npm root -g", { encoding: "utf8" }).trim();
    return require(path.join(root, "playwright")).chromium;
  }
}

/// WHERE THIS SCRIPT AND ITS EVIDENCE LIVE, off the script's own path rather than the
/// process's cwd: a run started from anywhere must still write the pictures here.
const HERE = path.dirname(fileURLToPath(import.meta.url));
const EVIDENCE = path.join(HERE, "evidence");

const url = process.argv[2] ?? "http://localhost:5399/";
const homeDir = process.argv[3] ?? "";

if (homeDir === "") {
  console.error(
    "walkthrough.mjs: name the temp config root dev.mjs printed -- this ticket's state is a\n" +
      "  home with two vendors declaring ONE model id, and the script is what seeds it.",
  );
  process.exit(2);
}

let failures = 0;
function check(label, ok, detail = "") {
  console.log(`${ok ? "ok  " : "RED "} ${label}${detail === "" ? "" : ` -- ${detail}`}`);
  if (!ok) failures += 1;
}

// ------------------------------------------------------------------- the seeded home
//
// ONE ID, TWO VENDORS. `qwen` is the default tier's vendor, so the session is served
// `qwen/deepseek-v4.1-flash` before anything is clicked; `workbuddy` serves `glm-5.3-flash`
// and THE SAME `deepseek-v4.1-flash`. Both hold a key, so both are on the menu -- a vendor
// without one is filtered out (`lib/provider-key.ts`) and the twin would not be drawn.
//
// THE BASE URLS ARE DELIBERATELY DEAD (port 9): nothing here runs a model. The two calls
// this page makes -- `/api/choices` and `/api/model` -- are answered from the catalog, and
// the picker's own switch goes to `POST /api/model`, which is memory.
const CONFIG = `{:default {:provider :qwen :model "deepseek-v4.1-flash"}
 :providers
 {:qwen {:protocol :openai-completions
         :base-url "http://127.0.0.1:9/v1"
         :model "deepseek-v4.1-flash"
         :models {"deepseek-v4.1-flash" {:input #{:text :image} :output #{:text}}}}
  :workbuddy {:protocol :openai-completions
              :base-url "http://127.0.0.1:9/v1"
              :model "glm-5.3-flash"
              :models {"glm-5.3-flash" {:input #{:text :image} :output #{:text}}
                       "deepseek-v4.1-flash" {:input #{:text :image} :output #{:text}}}}}}
`;
const ENV =
  "QWEN_API_KEY=sk-walkthrough-not-a-real-key\n" +
  "WORKBUDDY_API_KEY=sk-walkthrough-not-a-real-key\n";

fs.writeFileSync(path.join(homeDir, "config.edn"), CONFIG, "utf8");
fs.writeFileSync(path.join(homeDir, ".env"), ENV, "utf8");
console.log("seeded: qwen + workbuddy both serving deepseek-v4.1-flash, both keyed");
console.log("seeded: the default tier serves qwen/deepseek-v4.1-flash");

fs.mkdirSync(EVIDENCE, { recursive: true });

const browser = await loadChromium().launch();
const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
const shot = (name) => page.screenshot({ path: path.join(EVIDENCE, name) });

/// A ROW IS FOUND BY ITS HEADING AND ITS LABEL, which is what a person does: the run is the
/// one whose `aria-label` is that vendor, and the row inside it is the one saying that id.
/// Scoping it this way is the assertion's integrity -- an unscoped search for the id would
/// find one of the twins and prove nothing about which is which.
const row = (vendor, label) =>
  page
    .locator(`[role="group"][aria-label="${vendor}"] [data-slot="composer-model-option"]`)
    .filter({ hasText: label });

try {
  await page.goto(url, { waitUntil: "domcontentloaded" });

  // -------------------------------------------------------------- the menu as it opens
  const trigger = page.locator('[data-slot="composer-model-trigger"]');
  await trigger.waitFor({ timeout: 30_000 });
  await trigger.click();
  await page.locator('[data-slot="composer-model-popover"]').waitFor({ timeout: 10_000 });

  const groups = await page.locator('[data-slot="composer-model-group"]').allInnerTexts();
  check("both vendors head a run of the menu", groups.join("|") === "qwen|workbuddy", JSON.stringify(groups));

  // THE SAME ID TWICE, and each under its own heading: this is the state the ticket exists
  // for. One row here and the menu has quietly swallowed a vendor.
  check("the shared id is drawn once under each vendor", (await row("qwen", "deepseek-v4.1-flash").count()) === 1 && (await row("workbuddy", "deepseek-v4.1-flash").count()) === 1);

  // THE SESSION'S OWN ROW IS QWEN'S, and the trigger says so -- the vendor is not a
  // decoration on this control, it is half of what the session is being served by.
  check(
    "the session is served by qwen's row, and the trigger says so",
    (await trigger.getAttribute("title")) === "qwen / deepseek-v4.1-flash",
    String(await trigger.getAttribute("title")),
  );
  await shot("01-the-same-id-under-two-vendors.png");

  // ------------------------------------------------- the pick: what actually goes out
  //
  // THE REQUEST IS THE CLAIM. Before this ticket's fix the body named `qwen` here, because
  // the picker re-derived the vendor by asking the catalog which vendor declares this id
  // FIRST -- and the catalog sorts by name.
  const posted = page.waitForRequest(
    (request) => request.method() === "POST" && request.url().includes("/api/model"),
    { timeout: 15_000 },
  );
  await row("workbuddy", "deepseek-v4.1-flash").click();
  const body = (await posted).postDataJSON();
  check(
    "picking workbuddy's row sends workbuddy and that id",
    body.provider === "workbuddy" && body.model === "deepseek-v4.1-flash",
    JSON.stringify(body),
  );

  // AND THE SESSION MOVED, which is the same fact read off the screen: the trigger follows
  // the answer `/api/choices` gives after the switch. POLLED RATHER THAN AWAITED, so that a
  // switch that did not happen is a named RED like every other claim here instead of the
  // stack trace an uncaught `waitFor` would leave behind -- a walkthrough that dies on its
  // fourth check cannot report the fifth, and the last one is the direction a fix is most
  // likely to have got backwards.
  const moved = await page
    .locator('[data-slot="composer-model-trigger"][title="workbuddy / deepseek-v4.1-flash"]')
    .waitFor({ timeout: 30_000 })
    .then(() => true)
    .catch(() => false);
  check(
    "and the session is now served by workbuddy's row",
    moved,
    String(await trigger.getAttribute("title")),
  );
  await shot("02-after-picking-the-workbuddy-row.png");

  // ------------------------------------------------- the other direction, same pair
  //
  // PICKING THE TWIN BACK IS THE SAME QUESTION ASKED THE OTHER WAY, and it is the half a
  // first-vendor bug hides in: a fix that happened to send workbuddy because it was LAST
  // would pass the check above and fail this one.
  await trigger.click();
  await page.locator('[data-slot="composer-model-popover"]').waitFor({ timeout: 10_000 });
  const back = page.waitForRequest(
    (request) => request.method() === "POST" && request.url().includes("/api/model"),
    { timeout: 15_000 },
  );
  await row("qwen", "deepseek-v4.1-flash").click();
  const backBody = (await back).postDataJSON();
  check(
    "and picking qwen's twin sends qwen",
    backBody.provider === "qwen" && backBody.model === "deepseek-v4.1-flash",
    JSON.stringify(backBody),
  );
} finally {
  await browser.close();
}

console.log(
  failures === 0
    ? `\nall checks passed; screenshots in ${path.relative(process.cwd(), EVIDENCE)}`
    : `\n${failures} check(s) FAILED`,
);
process.exit(failures === 0 ? 0 : 1);
