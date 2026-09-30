// WHERE PLAYWRIGHT IS, for the walkthrough scripts beside this file.
//
// It is not a dependency of this repo -- `ui/package.json` has none, and the rule is that the page
// is a browser's business -- so it is FOUND rather than installed: `--playwright`, then
// `PLAYWRIGHT_PATH`, then the `npx` cache the Playwright MCP leaves behind. Within that cache the
// FIRST Playwright whose Chromium is actually downloaded wins: the cache holds several, and the
// newest asks for a revision this machine has never installed, which fails at launch a long way
// from the reason.
import { createRequire } from "node:module";
import fs from "node:fs";
import path from "node:path";

export function playwrightPath() {
  const args = process.argv.slice(2);
  const flag = args.indexOf("--playwright");
  if (flag !== -1) return args[flag + 1];
  if (process.env.PLAYWRIGHT_PATH) return process.env.PLAYWRIGHT_PATH;

  const local = process.env.LOCALAPPDATA ?? "";
  const cache = path.join(local, "npm-cache", "_npx");
  const browsers = path.join(local, "ms-playwright");
  if (!fs.existsSync(cache) || !fs.existsSync(browsers)) return "playwright";
  const installed = new Set(fs.readdirSync(browsers));

  let fallback = null;
  for (const entry of fs.readdirSync(cache)) {
    const candidate = path.join(cache, entry, "node_modules", "playwright");
    if (!fs.existsSync(candidate)) continue;
    fallback ??= candidate;
    const core = path.join(path.dirname(candidate), "playwright-core", "browsers.json");
    if (!fs.existsSync(core)) continue;
    const chromium = JSON.parse(fs.readFileSync(core, "utf8")).browsers.find((b) => b.name === "chromium");
    if (installed.has(`chromium-${chromium?.revision}`)) return candidate;
  }
  return fallback ?? "playwright";
}

/// The browser these scripts drive: the FULL Chromium (`channel`), not Playwright's default
/// `chrome-headless-shell`. The shell is about four times busier on the same stream and the same
/// script, and the readings it produces are not comparable with `spec.md`'s -- which were taken in
/// the owner's Chrome 153, and chromium-1243 here IS 153.0.8010.12.
export function chromium() {
  const require = createRequire(import.meta.url);
  return require(playwrightPath()).chromium;
}

export function argOf(name, fallback) {
  const args = process.argv.slice(2);
  const at = args.indexOf(name);
  return at === -1 ? fallback : args[at + 1];
}

/// THE PAGE'S OWN WORDS FOR "A TURN IS ARRIVING" and "IT IS OVER": the working dot the footer
/// draws while a turn is still being written, and the stop button that replaces send.
export const WORKING = '[data-slot="aui_assistant-message-indicator"]';
export const STOP = ".aui-composer-stop";
export const MESSAGES = '[data-slot="aui_assistant-message-root"]';
export const REMEMBERED = "clj-harness.session";

/// A page that has been told to forget the conversation it was in, so a reading starts from an
/// empty one. `addInitScript` runs before any page script on every document, which is the only
/// place this can be done without racing the app for the key (`ui/src/lib/session-memory.ts`).
export async function forgetSession(context) {
  await context.addInitScript((key) => {
    try {
      window.localStorage.removeItem(key);
      window.sessionStorage.removeItem(key);
    } catch {
      // A browser with site data blocked remembers nothing anyway -- the state this wants.
    }
  }, REMEMBERED);
}
