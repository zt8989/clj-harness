// The language chain, and the parity the two catalogs have to keep.
//
// BOTH CASES ARE PURE. Nothing here boots the harness, and nothing here needs a
// browser: `src/lib/language.ts` imports nothing and `src/lib/catalogs.ts` imports
// only the JSON, so both are reached by relative import -- the same exception
// `vitest.config.ts` describes for `format.ts` and the same shape as the `stats`,
// `attachments` and `turns` suites. The two files are imported by path rather than
// through `@/` because that alias belongs to `vite.config.js`, which this run does
// not load.
//
// THE PARITY CASE IS THE ONE THAT MATTERS LONG-TERM. i18next answers a key it does
// not have with the fallback language, so a Chinese entry nobody wrote does not
// fail -- it renders English, on a page that is otherwise Chinese, and nobody sees
// it until a reader does. Making it an assertion instead means a half-translated
// page is a red suite rather than a screen, which is the entire difference between
// this feature being finishable and being a permanent background task.
//
// WHAT THIS SUITE CANNOT SEE: the wiring. That i18next is initialized before the
// first paint, that `<html lang>` follows a switch, that the catalogs actually reach
// the components, and that no key is built from a template at a call site -- all of
// that lives in `src/lib/i18n.ts` and the components, which need a DOM and React, so
// they are measured in a real browser instead (see the walkthrough in `evidence/`).
// The type gate is the other half of that guarantee and runs as `npm run typecheck`.
import { expect } from "vitest";

import { type Case, type Suite } from "../e2e";
import { NAMESPACES, RESOURCES } from "../../src/lib/catalogs";
import {
  FALLBACK_LANGUAGE,
  LANGUAGE_STORAGE_KEY,
  SUPPORTED_LANGUAGES,
  isLanguage,
  languageFromNavigator,
  resolveLanguage,
} from "../../src/lib/language";

type Catalog = Record<string, unknown>;

/// Every leaf in a catalog, as a dotted path and its value: `language.title` is one
/// entry, not two. A nested object is structure, and the thing that has to be
/// translated is always the leaf.
function leaves(catalog: Catalog, prefix = ""): [string, unknown][] {
  return Object.entries(catalog).flatMap(([key, value]) => {
    const path = prefix === "" ? key : `${prefix}.${key}`;
    return typeof value === "object" && value !== null
      ? leaves(value as Catalog, path)
      : [[path, value] as [string, unknown]];
  });
}

const cases: Case[] = [
  {
    name: "the-language-chain-falls-through-what-it-cannot-use",
    run: async () => {
      // WHAT THIS BROWSER WAS TOLD LAST TIME WINS, both ways round: a remembered
      // choice is a choice somebody made, and it outranks a default.
      expect(resolveLanguage("zh", "en-US")).toBe("zh");
      expect(resolveLanguage("en", "zh-CN")).toBe("en");

      // A REMEMBERED TAG WITH A REGION STILL RESOLVES. Nobody writes this by hand --
      // it is what an older build of the page may have stored -- and the base subtag
      // is what decides, so it lands on its language rather than being discarded.
      expect(resolveLanguage("zh-CN", "en-US")).toBe("zh");

      // FIRST VISIT: the browser's answer, in all the spellings a browser uses.
      // `zh-TW` and `zh_TW` are the same tag said two ways, and there is one Chinese
      // here, so all three are the same answer.
      expect(resolveLanguage(null, "zh")).toBe("zh");
      expect(resolveLanguage(null, "zh-CN")).toBe("zh");
      expect(resolveLanguage(null, "zh-TW")).toBe("zh");
      expect(resolveLanguage(null, "zh_TW")).toBe("zh");
      expect(resolveLanguage(null, "en-US")).toBe("en");

      // A LANGUAGE THE PAGE DOES NOT SPEAK IS THE FALLBACK, and a REMEMBERED value
      // that no longer resolves falls THROUGH rather than sticking -- here both are
      // unusable, so the answer is English rather than the remembered `fr`.
      expect(resolveLanguage(null, "fr")).toBe("en");
      expect(resolveLanguage("fr", "de-DE")).toBe("en");
      expect(languageFromNavigator("ja")).toBe("en");

      // AND IT DOES NOT THROW ON GARBAGE. These values come out of localStorage,
      // which anything on this origin can write and a person can edit by hand; a
      // preference that cannot be understood is the first-visit case, not an error
      // that takes the page down.
      expect(resolveLanguage("", "zh")).toBe("zh");
      expect(resolveLanguage("   ", "zh")).toBe("zh");
      expect(resolveLanguage("{}", "zh")).toBe("zh");
      expect(resolveLanguage(null, "")).toBe("en");
      expect(resolveLanguage(undefined, undefined)).toBe("en");
      expect(resolveLanguage(null, null)).toBe("en");

      // The DOM hands a switch its value as `string`, which is where a closed list
      // normally stops being closed: the guard answers "is this one of ours", and
      // deliberately says NO to a tag with a region -- the select never produces one,
      // and a value that is not in the list is not a language to switch to.
      expect(isLanguage("zh")).toBe(true);
      expect(isLanguage("en")).toBe(true);
      expect(isLanguage("zh-CN")).toBe(false);
      expect(isLanguage("")).toBe(false);
      expect(isLanguage(null)).toBe(false);

      // THE STORAGE KEY IS A CONTRACT WITH EVERY BROWSER THAT HAS ALREADY CHOSEN:
      // renaming it silently puts everybody back on their browser's language. Pinned
      // so that renaming is a deliberate act with a failing test in front of it.
      expect(LANGUAGE_STORAGE_KEY).toBe("clj-harness.language");
      expect(FALLBACK_LANGUAGE).toBe("en");
    },
  },
  {
    name: "both-catalogs-say-the-same-things",
    run: async () => {
      // A LANGUAGE WITH NO CATALOG would make every key a fallback -- the page would
      // be drawn entirely in English with no sign that anything was missing.
      expect(Object.keys(RESOURCES).sort()).toEqual([...SUPPORTED_LANGUAGES].sort());

      // THE NAMESPACES THE PAGE LOADS ARE THE NAMESPACES THE TABLES HAVE, in both
      // languages: a namespace added to one language only is the same failure one
      // level up.
      expect(Object.keys(RESOURCES.zh).sort()).toEqual(NAMESPACES.slice().sort());

      let checked = 0;
      for (const namespace of NAMESPACES) {
        const en = leaves(RESOURCES.en[namespace] as Catalog);
        const zh = leaves(RESOURCES.zh[namespace] as Catalog);

        // THE SAME KEYS, in the same language-independent sense: the key is an
        // address, and an address that exists in one language only is a call site
        // that renders English (or Chinese) wherever the reader happens to be.
        expect(
          zh.map(([key]) => key).sort(),
          `namespace "${namespace}" has keys in one language only`,
        ).toEqual(en.map(([key]) => key).sort());

        // AND EVERY VALUE IS A NON-EMPTY STRING. An empty one is worse than a missing
        // one: it renders as nothing at all, so the page loses a label and gives no
        // hint that it ever had one.
        for (const [language, catalog] of [
          ["en", en],
          ["zh", zh],
        ] as const) {
          for (const [key, value] of catalog) {
            expect(
              typeof value,
              `${language}/${namespace}: "${key}" is not a string`,
            ).toBe("string");
            expect(
              (value as string).trim().length,
              `${language}/${namespace}: "${key}" is empty`,
            ).toBeGreaterThan(0);
            checked += 1;
          }
        }
      }

      // A GREEN RUN OVER ZERO ENTRIES PROVES NOTHING, which is the same guard the
      // driver puts on a suite contributing no cases: if the tables were ever emptied
      // or the walk stopped descending, the loop above would pass silently.
      expect(checked).toBeGreaterThan(0);
    },
  },
];

export const i18nSuite: Suite = { name: "i18n", cases };
