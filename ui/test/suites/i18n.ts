// How a language off the wire narrows to one the page speaks, and the parity the two
// catalogs have to keep.
//
// BOTH CASES ARE PURE. Nothing here boots the harness, and nothing here needs a
// browser: `src/lib/language.ts` imports nothing and `src/lib/catalogs.ts` imports
// only the JSON, so both are reached by relative import -- the same exception
// `vitest.config.ts` describes for `format.ts` and the same shape as the `stats`,
// `attachments` and `turns` suites. The two files are imported by path rather than
// through `@/` because that alias belongs to `vite.config.js`, which this run does
// not load. The ROUTE that carries the server's answer (`lib/languageSetting.ts`)
// does not import them and is not reached here -- its round trip is measured against
// the real server in the Clojure suite and in the browser walkthrough.
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
  SUPPORTED_LANGUAGES,
  asLanguage,
  isLanguage,
} from "../../src/lib/language";

type Catalog = Record<string, unknown>;

/// The suffixes i18next uses for plural forms, which are CLDR's category names.
const PLURAL_FORMS = ["zero", "one", "two", "few", "many", "other"] as const;

/// Split a leaf's dotted path into its base and plural form, when it has one.
///
/// `stats.turns_one` -> base `stats.turns`, form `one`; a leaf with no suffix on the
/// last segment is null. Only the LAST segment is examined: a namespace or a group
/// called `one` would not be a plural form, and reading it as one would invent a key.
function pluralOf(path: string): { base: string; form: string } | null {
  const underscore = path.lastIndexOf("_");
  if (underscore === -1) return null;
  const form = path.slice(underscore + 1);
  if (!(PLURAL_FORMS as readonly string[]).includes(form)) return null;
  return { base: path.slice(0, underscore), form };
}

/// The shape of one language's catalog for one namespace: the keys that stand alone,
/// and the plural bases, each with the forms that language actually wrote.
function shapeOf(catalog: Catalog) {
  const plain: string[] = [];
  const forms = new Map<string, string[]>();
  for (const [path] of leaves(catalog)) {
    const plural = pluralOf(path);
    if (plural === null) {
      plain.push(path);
      continue;
    }
    forms.set(plural.base, [...(forms.get(plural.base) ?? []), plural.form]);
  }
  return { plain: plain.sort(), forms };
}

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

/// Every source file the page is built from, as raw text, minus the catalogs
/// themselves (a catalog must not count as a use of its own keys).
///
/// `import.meta.glob` rather than reading the tree with `node:fs`, because this is the
/// vite pipeline and the glob is its own file list: no second notion of "the source",
/// and it needs no permission to walk a directory.
const SOURCES: readonly string[] = Object.entries(
  import.meta.glob("../../src/**/*.{ts,tsx}", {
    eager: true,
    query: "?raw",
    import: "default",
  }) as Record<string, string>,
)
  .filter(([path]) => !path.includes("/locales/"))
  .map(([, text]) => text);

/// Whether some source file names a key, in the one spelling the discipline allows.
///
/// THE KEY IS WRITTEN LITERALLY AT THE CALL SITE (spec decision 7), which is the whole
/// reason this check can exist: a key built from a template would never be found here,
/// and that is a property worth failing on rather than tolerating. A plural key is
/// looked up by its BASE, because that is what a call site writes -- `t("stats.turns",
/// { count })`, never `stats.turns_one`.
function isNamedSomewhere(key: string): boolean {
  return SOURCES.some(
    (text) => text.includes(`"${key}"`) || text.includes(`'${key}'`) || text.includes(`\`${key}\``),
  );
}

const cases: Case[] = [
  {
    name: "a-language-off-the-wire-narrows-to-one-the-page-speaks",
    run: async () => {
      // THE SERVER OWNS THE ANSWER -- config.edn's :ui :language, resolved with its
      // fallbacks on the server (harness.infra.language) -- so the page only has to
      // understand it. `zh-CN`, `zh_TW` and `zh-Hans-CN` are the same tag said several
      // ways, and there is one Chinese here, so all of them are Chinese.
      expect(asLanguage("zh")).toBe("zh");
      expect(asLanguage("en")).toBe("en");
      expect(asLanguage("zh-CN")).toBe("zh");
      expect(asLanguage("zh_TW")).toBe("zh");
      expect(asLanguage("zh-Hans-CN")).toBe("zh");

      // ANYTHING THE PAGE CANNOT UNDERSTAND IS THE FALLBACK, and a route that answered
      // nothing is the first-load case rather than an error worth a blank page.
      expect(asLanguage("fr")).toBe("en");
      expect(asLanguage("")).toBe("en");
      expect(asLanguage("   ")).toBe("en");
      expect(asLanguage("{}")).toBe("en");
      expect(asLanguage(null)).toBe("en");
      expect(asLanguage(undefined)).toBe("en");
      expect(asLanguage(42)).toBe("en");

      // The DOM hands a switch its value as `string`, which is where a closed list
      // normally stops being closed: the guard answers "is this one of ours", and
      // deliberately says NO to a tag with a region -- the select never produces one,
      // and a value that is not in the list is not a language to switch to.
      expect(isLanguage("zh")).toBe(true);
      expect(isLanguage("en")).toBe(true);
      expect(isLanguage("zh-CN")).toBe(false);
      expect(isLanguage("")).toBe(false);
      expect(isLanguage(null)).toBe(false);

      // ENGLISH IS THE FLOOR, on this side as on the server's.
      expect(FALLBACK_LANGUAGE).toBe("en");
      expect(SUPPORTED_LANGUAGES).toEqual(["en", "zh"]);
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

        // THE SAME KEYS, IN THE SAME LANGUAGE-INDEPENDENT SENSE: the key is an
        // address, and an address that exists in one language only is a call site
        // that renders English (or Chinese) wherever the reader happens to be.
        //
        // PLURAL FORMS ARE THE ONE EXCEPTION, and it is the language's, not ours:
        // English has a singular form and Chinese does not, so `x_one` in one file
        // and not the other is correct. The comparison is therefore between the keys
        // that stand alone, plus the plural BASES -- and each base's forms are then
        // checked against the language's own CLDR categories, which is what makes a
        // misspelled suffix (`x_ones`) a failure rather than a key that quietly never
        // gets used.
        const enShape = shapeOf(RESOURCES.en[namespace] as Catalog);
        const zhShape = shapeOf(RESOURCES.zh[namespace] as Catalog);

        expect(
          zhShape.plain,
          `namespace "${namespace}" has keys in one language only`,
        ).toEqual(enShape.plain);

        expect(
          [...zhShape.forms.keys()].sort(),
          `namespace "${namespace}" has plural keys in one language only`,
        ).toEqual([...enShape.forms.keys()].sort());

        for (const [language, shape] of [
          ["en", enShape],
          ["zh", zhShape],
        ] as const) {
          const categories = new Intl.PluralRules(language).resolvedOptions().pluralCategories;
          for (const [base, forms] of shape.forms) {
            expect(
              [...forms].sort(),
              `"${language}/${namespace}: ${base}" has the wrong plural forms`,
            ).toEqual([...categories].sort());
          }
        }

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
  {
    name: "every-catalog-entry-is-named-by-something",
    run: async () => {
      // THE OTHER DIRECTION OF THE PARITY CHECK. That one asks "is every key in both
      // languages"; this asks "does anything actually name it" -- and the failure it
      // catches is the quiet one: a call site whose key was mistyped and then added to
      // the catalog to make the types pass, or an entry left behind after the row that
      // used it was deleted. Neither shows up on a screen; both accumulate.
      //
      // It is a GREP, and it is honest about what that costs: the sources are searched
      // as text, so a key named only inside a comment counts as used. That is the
      // direction to be wrong in -- a false "used" is a missed warning, while a false
      // "unused" would fail a green tree for a reason nobody could act on.
      const orphans: string[] = [];
      let seen = 0;
      for (const language of ["en", "zh"] as const) {
        for (const namespace of NAMESPACES) {
          for (const [path] of leaves(RESOURCES[language][namespace] as Catalog)) {
            seen += 1;
            const plural = pluralOf(path);
            const base = plural === null ? path : plural.base;
            if (!isNamedSomewhere(base)) orphans.push(`${language}/${namespace}: ${base}`);
          }
        }
      }

      expect(orphans, "catalog entries nothing names").toEqual([]);

      // And the scan saw the whole set, so a broken glob cannot make this pass.
      expect(seen).toBeGreaterThan(300);
    },
  },
];

export const i18nSuite: Suite = { name: "i18n", cases };
