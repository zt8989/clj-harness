#!/usr/bin/env node
//
// A real-browser walkthrough of `.scratch/provider-availability`'s three tickets.
//
//   node scripts/dev.mjs --scripted --ui-port 5399
//   node .scratch/provider-availability/walkthrough.mjs http://localhost:5399 <home-dir>
//
// HOME-DIR is the temp config root dev.mjs prints ("dev.mjs: temp config root …").
// Passing it makes this script SEED the state tickets 02 and 03 are about -- one provider
// with a key, one without, and a session served by the UNKEYED one -- because a home
// with no keys at all draws a different page (and the tickets ask about the both-states
// page). Omit it to measure whatever home the server already has; the script says which
// providers it found either way, so a run cannot quietly measure the wrong thing.
//
// ---------------------------------------------------------------- what only this sees
//
// The three tickets are all claims about what is DRAWN, and none of them is reachable
// from `npm test`:
//
//   * ticket 01's PARSER is pinned by `providers_test.clj`, but the WIRING -- button ->
//     route -> real HTTP -> the parser -> the checkbox list -- is not, and the edge
//     suite stubs the one call that leaves the machine. So this script points the form
//     at a real (local, offline) provider endpoint and reads the list it draws.
//   * tickets 02 and 03 are two components, and the vitest run cannot import either
//     (they want a DOM; `vitest.config.ts` says so). The RULE they share is pinned
//     there over literals (`lib/provider-key.ts`, `suites/picker.ts`) -- this is the
//     half that proves the page APPLIES it.
//
// THE PROVIDER IS LOCAL AND OFFLINE, started by this script on a port the OS picks, and
// it answers the provider shape on purpose -- including a row whose `id` is not a string,
// which ticket 01 says must be skipped rather than fail the list.
//
// FOUR SCREENSHOTS, into `evidence/`, one per claim a reader might disbelieve. The
// claims are also printed as ok/RED and a red one exits non-zero, so this is a gate and
// not a screenshot machine.
import { execSync } from "node:child_process";
import fs from "node:fs";
import http from "node:http";
import { createRequire } from "node:module";
import path from "node:path";
import { fileURLToPath } from "node:url";

/// PLAYWRIGHT IS RESOLVED, NOT SPELLED OUT. A committed script cannot hold one
/// machine's absolute path, and the previous walkthrough in this repo did -- which is
/// why it is now a Windows path in a macOS checkout. So: the project's own dependency
/// if it has one, the global install otherwise (`ui/` deliberately does not depend on
/// playwright: the browser is this repo's outer layer, not its test runner's).
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

let failures = 0;
function check(label, ok, detail = "") {
  console.log(`${ok ? "ok  " : "RED "} ${label}${detail === "" ? "" : ` -- ${detail}`}`);
  if (!ok) failures += 1;
}

// ------------------------------------------------------------------ the seeded home
//
// WRITTEN BEFORE THE BROWSER OPENS, and it takes effect without a restart because the
// server reads config.edn and .env PER REQUEST (there is no cache to invalidate -- see
// `providers/registry-report` and `api-key-source`). The session is pointed at OLLAMA,
// which has no key, so the composer has a current model whose provider is filtered out --
// the case ticket 03 says must still be drawn.
if (homeDir !== "") {
  fs.writeFileSync(
    path.join(homeDir, ".env"),
    "DEEPSEEK_API_KEY=sk-walkthrough-not-a-real-key\n",
    "utf8",
  );
  fs.writeFileSync(
    path.join(homeDir, "config.edn"),
    '{:default {:provider :ollama :model "qwen3"}}\n',
    "utf8",
  );
  console.log(`seeded ${homeDir}: deepseek keyed; session on ollama/qwen3 (no key)`);
}

// -------------------------------------------------------------------- the fake provider
//
// THE ROW WITH `{"id": 42}` IS THE POINT OF THE BODY: ticket 01 asks that a row whose id
// is not a string be SKIPPED rather than fail the whole listing, and a body of only
// well-formed rows could not tell those two behaviours apart.
const PROVIDER_BODY = JSON.stringify({
  data: [{ id: "walkthrough-alpha" }, { id: "walkthrough-beta" }, { id: 42 }, { "no-id": true }],
});
const providerHits = [];
const provider = http.createServer((req, res) => {
  providerHits.push(req.url ?? "");
  res.writeHead(200, { "Content-Type": "application/json" });
  res.end(PROVIDER_BODY);
});
await new Promise((done) => provider.listen(0, "127.0.0.1", done));
const providerBase = `http://127.0.0.1:${provider.address().port}/v1`;
fs.mkdirSync(EVIDENCE, { recursive: true });

const browser = await loadChromium().launch();
const page = await browser.newPage({ viewport: { width: 1440, height: 1200 } });
const shot = (name) => page.screenshot({ path: path.join(EVIDENCE, name) });

/// THE TWO READS THIS PAGE MAKES, so "opening the sentence is layout, not a fetch" can
/// be COUNTED rather than argued (ticket 02 asks for exactly that). Only these two are
/// counted: the run's own SSE traffic and any sidebar refresh are other people's
/// requests, and a number that moved because of one of those would prove nothing.
const apiCalls = [];
page.on("request", (request) => {
  const asked = request.url();
  if (asked.includes("/api/providers") || asked.includes("/api/settings")) apiCalls.push(asked);
});

try {
  await page.goto(url, { waitUntil: "domcontentloaded" });

  // ------------------------------------------------------------------ ticket 03
  //
  // The composer's model picker. `deepseek` holds the home's only key, so the menu may
  // offer its models and nobody else's; the session is being served `qwen3` by ollama,
  // which is filtered out, so that one row has to be drawn anyway, at the top, with the
  // existing 'not in the catalog' expression rather than a second one.
  await page.locator('[data-slot="composer-model-trigger"]').waitFor({ timeout: 30_000 });
  await page.locator('[data-slot="composer-model-trigger"]').click();
  await page.locator('[data-slot="composer-model-popover"]').waitFor({ timeout: 10_000 });

  const optionTexts = await page.locator('[data-slot="composer-model-option"]').allInnerTexts();
  const groupTexts = await page.locator('[data-slot="composer-model-group"]').allInnerTexts();
  const flat = optionTexts.join(" | ");

  check("03 the menu lists the keyed provider's models", flat.includes("deepseek-flash"), flat);
  // EVERY OPTION BELOW THE FIRST BELONGS TO THE KEYED PROVIDER: the strong form of "only
  // the keyed provider is offered", and the one a group heading alone would not prove.
  check(
    "03 everything under it belongs to that provider",
    optionTexts.slice(1).length > 0 && optionTexts.slice(1).every((t) => t.includes("deepseek")),
    JSON.stringify(optionTexts),
  );
  check(
    "03 the unkeyed provider's models are NOT on the menu",
    !flat.includes("qwen3-vl") && !groupTexts.includes("ollama"),
    `groups: ${JSON.stringify(groupTexts)}`,
  );
  check(
    "03 the session's own model is still drawn",
    flat.includes("qwen3"),
    `first option: ${optionTexts[0] ?? "(none)"}`,
  );
  check(
    "03 and it says why it does not fit, in the existing words",
    /not in the catalog|不在模型目录里/.test(optionTexts[0] ?? ""),
    `first option: ${optionTexts[0] ?? "(none)"}`,
  );
  check(
    "03 only the keyed provider heads a group",
    groupTexts.length > 0 && groupTexts.every((g) => g.includes("deepseek")),
    `groups: ${JSON.stringify(groupTexts)}`,
  );
  await shot("03-model-picker.png");
  await page.keyboard.press("Escape");

  // ------------------------------------------------------------------ ticket 02
  await page.locator('[data-slot="sidebar-settings"]').click();
  await page.locator('[data-slot="settings-nav-models"]').click();
  await page.locator('[data-slot="settings-providers"]').waitFor({ timeout: 30_000 });

  // SCOPED TO THE LIST ITSELF, and the scoping is the assertion's whole integrity: the
  // providers with no key are in the DOM too (a collapsed `<details>` HIDES them; it does
  // not remove them), so an unscoped `[data-slot=settings-provider-row]` query reads all
  // three rows and "not in that list" would be measuring the wrong container -- it passed
  // that way once, on nothing but a line-break accident, which is why it is spelled out.
  const keyedBox = page.locator('[data-slot="settings-providers"]');
  const keyedText = await keyedBox.innerText();
  const keyedCount = await keyedBox.locator('[data-slot="settings-provider-row"]').count();
  const summary = page.locator('[data-slot="settings-providers-unkeyed"] summary');
  const summaryText = (await summary.count()) > 0 ? (await summary.innerText()).trim() : "";

  check("02 the keyed provider is listed", keyedText.includes("deepseek"), keyedText.replace(/\n/g, " / "));
  check(
    "02 and it is the only provider in that list",
    keyedCount === 1,
    `${keyedCount} row(s): ${keyedText.replace(/\n/g, " / ")}`,
  );
  check(
    "02 the unkeyed providers are not in that list",
    !keyedText.includes("ollama") && !keyedText.includes("openrouter"),
    keyedText.replace(/\n/g, " / "),
  );
  check(
    "02 and one sentence says how many are missing and how to get them back",
    /\b2\b/.test(summaryText) && summaryText.length > 0,
    summaryText,
  );
  await shot("02-models-default.png");

  // OPENING IT IS LAYOUT, NOT A FETCH: the rows are already in the DOM (a `<details>`
  // hides the box; it does not remove it), so nothing is requested to look at them --
  // which is also why the assertions below read the SAME container the summary governs.
  const readsBefore = apiCalls.length;
  await summary.click();
  const unkeyedBox = page.locator('[data-slot="settings-providers-without-keys"]');
  const unkeyedText = await unkeyedBox.innerText();
  const unkeyedCount = await unkeyedBox.locator('[data-slot="settings-provider-row"]').count();
  check(
    "02 opening it gives the providers with no key",
    unkeyedText.includes("ollama") && unkeyedText.includes("openrouter") && unkeyedCount === 2,
    `${unkeyedCount} row(s): ${unkeyedText.replace(/\n/g, " / ")}`,
  );
  check(
    "02 each is still today's row -- its address, its catalog, and 'no key'",
    unkeyedText.includes("localhost:11434") && /no key/.test(unkeyedText),
    unkeyedText.replace(/\n/g, " / "),
  );
  await shot("02-models-expanded.png");

  // AND CLOSING AND OPENING IT ASKS THE SERVER FOR NOTHING -- counted, not argued: the
  // two reads this page makes are on the listener above, so a disclosure triangle that
  // caused one would show up here as a number that moved.
  await summary.click();
  await summary.click();
  check(
    "02 and opening and closing it reads nothing from the server",
    apiCalls.length === readsBefore,
    `${apiCalls.length - readsBefore} read(s) went out: ${JSON.stringify(apiCalls.slice(readsBefore))}`,
  );

  // CLICKABLE, AND STILL THE EDITOR: the row opens the same form, which is how a person
  // gives a provider a key. The address it opens ON is the assertion worth making -- a row
  // that opened some other provider's form would look identical in a screenshot.
  await page
    .locator('[data-slot="settings-providers-without-keys"] [data-slot="settings-provider-row"]')
    .first()
    .click();
  check(
    "02 and it opens the editor, on that very provider",
    (await page.locator('[data-slot="settings-provider-form"]').count()) > 0 &&
      (await page.locator('[data-slot="settings-provider-base-url"] input').inputValue()) ===
        "http://localhost:11434/v1",
    await page.locator('[data-slot="settings-provider-base-url"] input').inputValue(),
  );
  await shot("02-models-unkeyed-row-opens-the-editor.png");
  // BACK OUT THE WAY THE PAGE OFFERS, so the next ticket starts from the list.
  await page.locator('[data-slot="settings-provider-back"]').click();

  // ------------------------------------------------------------------ ticket 01
  //
  // The form's "Fetch available models", against the local provider endpoint. This is the
  // path the stub in the edge suite replaces: a real request, a real body, the real
  // parser, and the checkbox list a person actually sees.
  await page.locator('[data-slot="settings-provider-add"]').click();
  await page.locator('[data-slot="settings-provider-id"] input').fill("walkthrough-provider");
  await page.locator('[data-slot="settings-provider-base-url"] input').fill(providerBase);
  const proto = page.locator('[data-slot="settings-provider-protocol"] select');
  if ((await proto.count()) > 0) await proto.selectOption("openai-completions").catch(() => {});
  await page.locator('[data-slot="settings-provider-model-fetch"]').click();
  await page.locator('[data-slot="settings-provider-model-offered"]').waitFor({ timeout: 30_000 });

  const offered = await page.locator('[data-slot="settings-provider-model-offered"]').innerText();
  check("01 the provider was really asked", providerHits.some((h) => h.includes("/models")), JSON.stringify(providerHits));
  check("01 the list is what the provider listed", offered.includes("walkthrough-alpha"), offered.replace(/\n/g, " / "));
  check("01 both well-formed ids came back", offered.includes("walkthrough-beta"), offered.replace(/\n/g, " / "));
  check(
    "01 the row whose id is not a string is skipped, not fatal",
    !/^\s*42\s*$/m.test(offered),
    offered.replace(/\n/g, " / "),
  );
  check(
    "01 and it is a list, not the empty answer that shipped",
    !/listed no models/i.test(offered),
    offered.replace(/\n/g, " / "),
  );
  // THE OFFERED LIST IS BELOW THE FORM'S FOLD, and a screenshot of the address field
  // alone would not evidence the ticket at all -- so it is scrolled to before the shot,
  // and the shot is a picture of the thing the ticket is about.
  await page.locator('[data-slot="settings-provider-model-offered"]').scrollIntoViewIfNeeded();
  await shot("01-fetch-returns-what-the-provider-lists.png");

  // TICKING ONE PUTS IT IN THE CATALOG, DECLARING TEXT ONLY -- the last half of the
  // ticket, and the one a screenshot alone would not settle.
  await page
    .locator('[data-slot="settings-provider-model-offered"] input[type="checkbox"]')
    .first()
    .check();
  await page.locator('[data-slot="settings-provider-model-take"]').click();
  const catalogRow = page.locator('[data-slot="settings-provider-model"]').first();
  const modals = await catalogRow.locator('input[type="checkbox"]').evaluateAll((nodes) =>
    nodes.map((n) => ({ label: n.getAttribute("aria-label"), checked: n.checked })),
  );
  check(
    "01 a ticked model lands in the catalog declaring text only",
    modals.some((m) => /accepts text/i.test(m.label ?? "") && m.checked) &&
      modals.every((m) => !/accepts image/i.test(m.label ?? "") || !m.checked),
    JSON.stringify(modals),
  );
  check("01 and the model id is the one the provider listed",
    (await catalogRow.locator('input:not([type="checkbox"])').first().inputValue()) ===
      "walkthrough-alpha",
    await catalogRow.locator('input:not([type="checkbox"])').first().inputValue());
  // ------------------------------------------------- a home with no keys at all
  //
  // THE OTHER END OF BOTH TICKETS, and neither is done without it: 02 asks that a home
  // with no keys show a sentence instead of a blank page (and keep the Add button), and
  // 03 asks that the picker still draw the model the session is being served by. Both
  // are the states where the filter removes EVERYTHING -- which is exactly where a
  // filter that also swallowed the current row would hide.
  //
  // AND IT RUNS 02's WRITE -> RE-READ LOOP, the half no screenshot can settle: the key
  // goes in through the same form a person uses, the page re-reads with the refresh it
  // already had (ticket 02 says that one is enough, and this is that claim executed),
  // and the provider moves back up into the list.
  if (homeDir !== "") {
    fs.writeFileSync(path.join(homeDir, ".env"), "", "utf8");
    await page.goto(url, { waitUntil: "domcontentloaded" });

    await page.locator('[data-slot="composer-model-trigger"]').click();
    await page.locator('[data-slot="composer-model-popover"]').waitFor({ timeout: 30_000 });
    const onlyCurrent = await page.locator('[data-slot="composer-model-option"]').allInnerTexts();
    check(
      "03 with no key anywhere the menu is not empty -- the current model is still in it",
      onlyCurrent.length === 1 && onlyCurrent[0].includes("qwen3"),
      JSON.stringify(onlyCurrent),
    );
    check(
      "03 and it still says why it sits alone",
      /not in the catalog|不在模型目录里/.test(onlyCurrent[0] ?? ""),
      onlyCurrent[0] ?? "(none)",
    );
    await shot("03-model-picker-when-no-provider-has-a-key.png");
    await page.keyboard.press("Escape");

    await page.locator('[data-slot="sidebar-settings"]').click();
    await page.locator('[data-slot="settings-nav-models"]').click();
    await page.locator('[data-slot="settings-page-models"]').waitFor({ timeout: 30_000 });
    const emptyKeyed = await page
      .locator('[data-slot="settings-providers"] [data-slot="settings-provider-row"]')
      .count();
    const allUnkeyed = await page
      .locator('[data-slot="settings-providers-without-keys"] [data-slot="settings-provider-row"]')
      .count();
    const addButton = page.locator('[data-slot="settings-provider-add"]');
    check(
      "02 a home with no keys is not a blank page: nothing in the list, everything behind the sentence",
      emptyKeyed === 0 && allUnkeyed === 3,
      `${emptyKeyed} listed, ${allUnkeyed} behind the sentence`,
    );
    check(
      "02 and 'Add provider' is still there and still clickable",
      (await addButton.isVisible()) && (await addButton.isEnabled()),
    );
    await shot("02-models-no-keys-at-all.png");

    // THE KEY GOES IN THROUGH THE FORM A PERSON USES, so this is the whole loop and not
    // a restatement of the filter: write, re-read, and the provider changes sections.
    await page.locator('[data-slot="settings-providers-unkeyed"] summary').click();
    await page
      .locator('[data-slot="settings-providers-without-keys"] [data-slot="settings-provider-row"]')
      .filter({ hasText: "openrouter" })
      .first()
      .click();
    await page
      .locator('[data-slot="settings-provider-key"] input')
      .fill("sk-walkthrough-not-a-real-key-either");
    await page.locator('[data-slot="settings-provider-submit"]').click();
    const movedBack = await page
      .locator('[data-slot="settings-providers"] [data-slot="settings-provider-row"]')
      .filter({ hasText: "openrouter" })
      .first()
      .waitFor({ timeout: 30_000 })
      .then(() => true)
      .catch(() => false);
    const backText = await page.locator('[data-slot="settings-providers"]').innerText();
    check(
      "02 a key saved through the form moves the provider back into the list",
      movedBack,
      backText.replace(/\n/g, " / "),
    );
    check(
      "02 and it comes back wearing its key, not merely present",
      /key ✓/.test(backText),
      backText.replace(/\n/g, " / "),
    );
    await shot("02-key-saved-moves-back.png");
  }
} finally {
  await browser.close();
  provider.close();
}

console.log(
  failures === 0
    ? `\nall checks passed; screenshots in ${path.relative(process.cwd(), EVIDENCE)}`
    : `\n${failures} check(s) FAILED`,
);
process.exit(failures === 0 ? 0 : 1);
