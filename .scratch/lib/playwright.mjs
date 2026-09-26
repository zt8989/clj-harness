// WHERE PLAYWRIGHT IS, in one place, for every walkthrough in this repo:
//
//     import { launchBrowser } from "../lib/playwright.mjs";
//     const browser = await launchBrowser();
//
// WHY IT EXISTS. Every walkthrough here wrote its own two lines --
//
//     const root = execSync("npm root -g", { encoding: "utf8" }).trim();
//     const { chromium } = await import(path.join(root, "playwright", "index.mjs"));
//
// -- and BOTH of them are wrong on this machine now, in ways that took a while to see:
//
//   * `npm root -g` HAS NO PLAYWRIGHT IN IT. The only copy on this machine arrived through
//     `npx @playwright/mcp`, whose declaration lives in `~/.clj-harness/mcp.edn`; npm's `_npx`
//     cache is where that copy is.
//   * AN ABSOLUTE PATH IS NOT A SPECIFIER THE ESM LOADER TAKES on Windows: it has to be a
//     `file://` URL (`ERR_UNSUPPORTED_ESM_URL_SCHEME`, received protocol `c:`).
//
// Ten files failing to start is why a change to `ui/src` had no browser gate at all, which is
// the one layer the suites cannot reach (AGENTS.md). So the search lives here.
//
// AND NOT EVERY INSTALLED COPY HAS ITS BROWSERS: this machine has two copies in the cache and
// one set of browsers between them (`browserType.launch: Executable doesn't exist at
// ...ms-playwright/chromium_headless_shell-1246` is what the other one says), so the copy that
// LAUNCHES is the one that is used, not a path picked in advance.
import fs from "node:fs";
import path from "node:path";
import { execSync } from "node:child_process";
import { fileURLToPath, pathToFileURL } from "node:url";

/// `.scratch/lib/` -- so the repo root is two levels up from here, whichever feature directory
/// the caller lives in.
const ROOT = path.join(path.dirname(fileURLToPath(import.meta.url)), "..", "..");

const entries = () => {
  const candidates = [
    path.join(execSync("npm root -g", { encoding: "utf8" }).trim(), "playwright", "index.mjs"),
  ];
  const npxRoot = path.join(execSync("npm config get cache", { encoding: "utf8" }).trim(), "_npx");
  if (fs.existsSync(npxRoot)) {
    for (const entry of fs.readdirSync(npxRoot)) {
      candidates.push(path.join(npxRoot, entry, "node_modules", "playwright", "index.mjs"));
    }
  }
  candidates.push(path.join(ROOT, "ui", "node_modules", "playwright", "index.mjs"));
  return candidates.filter((candidate) => fs.existsSync(candidate));
};

export async function launchBrowser() {
  const refusals = [];
  for (const entry of entries()) {
    try {
      const { chromium } = await import(pathToFileURL(entry).href);
      return await chromium.launch();
    } catch (error) {
      refusals.push(`${entry}: ${error.message.split("\n")[0]}`);
    }
  }
  throw new Error(`no playwright copy could start a browser:\n${refusals.join("\n")}`);
}
