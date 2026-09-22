// What a picker's list does with what was typed into it.
//
// BOTH CASES ARE PURE. Nothing here boots the harness or touches a DOM: the
// filtering and the grouping live in `src/lib/picker.ts`, a module that imports
// nothing, so the rules a reader sees only as "the list got shorter" can be pinned
// as lists over literal options. Same shape as the `turns` and `attachments`
// suites, and for the same reason -- see the note at the top of `vitest.config.ts`
// about the relative import.
//
// WHAT THIS SUITE CANNOT SEE: the popover itself -- the search box, the highlight,
// the arrow keys, the `aria-*`. No DOM here. Those are measured in a real browser
// instead.
import { expect } from "vitest";

import { type Case, type Suite } from "../e2e";
import {
  filterOptions,
  groupOptions,
  matchesOption,
  type PickerOption,
} from "../../src/lib/picker";
import { hasKey, splitByKey, type KeyFact } from "../../src/lib/provider-key";

/// A row carrying the shared key fact, in the shape BOTH faces hand it around: the
/// settings row and the picker's choices row are different objects that agree on
/// `key`, and this is the smallest thing that stands for either.
type KeyedRow = KeyFact & { name: string; models: string[] };

/// The little catalog every case below reads: two vendors with two models each,
/// one model the catalog does not list, and one project whose label is only its
/// last path segment.
const OPTIONS: readonly PickerOption[] = [
  { value: "", label: "default" },
  { value: "seeded", label: "seeded", hint: "not in the catalog" },
  { value: "deepseek-flash", label: "deepseek-flash", group: "deepseek" },
  { value: "deepseek-v4-pro", label: "deepseek-v4-pro", group: "deepseek" },
  { value: "qwen3", label: "qwen3", group: "ollama" },
  { value: "/Users/me/dev/clj-harness", label: "clj-harness", hint: "/Users/me/dev/clj-harness" },
];

const cases: Case[] = [
  {
    name: "a-query-keeps-the-options-it-matches",
    run: async () => {
      // An untouched box keeps everything, in the order it was given.
      expect(filterOptions(OPTIONS, "")).toEqual([...OPTIONS]);
      expect(filterOptions(OPTIONS, "   ")).toEqual([...OPTIONS]);

      // THE LABEL, which is what is on screen.
      expect(filterOptions(OPTIONS, "qwen3").map((o) => o.value)).toEqual(["qwen3"]);

      // CASE DOES NOT MATTER, and neither does a partial word: nobody types the
      // whole id of a model.
      expect(filterOptions(OPTIONS, "QWEN").map((o) => o.value)).toEqual(["qwen3"]);
      expect(filterOptions(OPTIONS, "v4").map((o) => o.value)).toEqual(["deepseek-v4-pro"]);

      // THE HINT MATCHES TOO, and this is the case that matters for the project
      // picker: the label is a last path segment, so a person who remembers the
      // parent directory would otherwise find nothing at all.
      expect(filterOptions(OPTIONS, "Users/me/dev").map((o) => o.value)).toEqual([
        "/Users/me/dev/clj-harness",
      ]);
      expect(filterOptions(OPTIONS, "not in the catalog").map((o) => o.value)).toEqual(["seeded"]);

      // AND SO DOES THE GROUP, which is the one that matters for the model picker:
      // a model's id does not name its vendor, so "deepseek" has to find every
      // model the vendor declares -- and the ids that happen to start with it.
      expect(filterOptions(OPTIONS, "ollama").map((o) => o.value)).toEqual(["qwen3"]);
      expect(filterOptions(OPTIONS, "deepseek").map((o) => o.value)).toEqual([
        "deepseek-flash",
        "deepseek-v4-pro",
      ]);

      // Nothing matching is an empty list, not the whole list: a search that
      // quietly fell back to everything would look like it worked.
      expect(filterOptions(OPTIONS, "nothing-like-this")).toEqual([]);
      expect(matchesOption(OPTIONS[0]!, "default")).toBe(true);
      expect(matchesOption(OPTIONS[0]!, "qwen")).toBe(false);

      // Surrounding whitespace is not a needle (a pasted id arrives with a
      // newline at the end more often than not).
      expect(filterOptions(OPTIONS, "  qwen3\n").map((o) => o.value)).toEqual(["qwen3"]);
    },
  },
  {
    name: "runs-of-one-group-stay-where-they-were",
    run: async () => {
      // Consecutive options of one group become one run, and the caller's order is
      // never rearranged: the vendor's own order is the order its models are meant
      // to be read in. The two groupless options at the head are ONE run -- a run
      // of options that belong to nobody, which the caller draws without a heading.
      const runs = groupOptions(OPTIONS);
      expect(runs.map((run) => run.group)).toEqual([
        undefined, // `default` and `seeded`, together
        "deepseek",
        "ollama",
        undefined, // the project, which comes after them
      ]);
      expect(runs[0]!.options.map((o) => o.value)).toEqual(["", "seeded"]);
      expect(runs[1]!.options.map((o) => o.value)).toEqual(["deepseek-flash", "deepseek-v4-pro"]);

      // The same group appearing twice stays TWO runs. A map would have merged
      // them and moved the second one up into the first, which is the one thing a
      // grouped list must not do silently.
      const apart: readonly PickerOption[] = [
        { value: "a", label: "a", group: "one" },
        { value: "b", label: "b", group: "two" },
        { value: "c", label: "c", group: "one" },
      ];
      expect(groupOptions(apart).map((run) => run.group)).toEqual(["one", "two", "one"]);

      // Options with no group are a run of their own, and the caller decides
      // whether to draw a heading: this is what lets the current model ride at the
      // top of the model list without pretending to be a vendor.
      expect(groupOptions([{ value: "x", label: "x" }])).toEqual([
        { group: undefined, options: [{ value: "x", label: "x" }] },
      ]);

      // The two are the same question asked twice, so filtering then grouping is
      // how a query changes what the headings say.
      expect(groupOptions(filterOptions(OPTIONS, "deepseek")).map((run) => run.group)).toEqual([
        "deepseek",
      ]);
      expect(groupOptions(filterOptions(OPTIONS, "zzz"))).toEqual([]);
    },
  },
  {
    name: "a-provider-is-offered-only-when-this-home-holds-a-key-for-it",
    run: async () => {
      // ONE RULE, TWO FACES: the settings page shows a provider when this home holds a
      // key pointing at it, and the composer's model picker offers that provider's
      // models. The rule lives in `lib/provider-key.ts`, which imports nothing, so
      // both faces are pinned here over literal rows -- the two components that apply
      // it cannot be imported without a DOM, and what they DRAW is the browser
      // walkthrough's question.
      //
      // THE FIXTURES ARE ANNOTATED RATHER THAN INFERRED, and that is the one thing
      // tying them to the wire: `KeyedRow` is the shared fact intersected with the
      // fields the two shapes carry it in, so a change to `ProviderKey` stops this file
      // COMPILING instead of letting the literals drift into agreement with nothing.
      const keyed: KeyedRow = {
        name: "deepseek",
        models: ["deepseek-flash"],
        key: { "present?": true, source: "env-file", name: "DEEPSEEK_API_KEY" },
      };
      const unkeyed: KeyedRow = {
        name: "ollama",
        models: ["qwen3"],
        key: { "present?": false, source: null, name: "OLLAMA_API_KEY" },
      };
      const all: KeyedRow[] = [
        keyed,
        unkeyed,
        { name: "gamma", models: ["g"], key: { "present?": true, source: "environment" } },
      ];

      // PRESENCE IS THE WHOLE FACT, and it is not a source: a key in the home's .env
      // and one in the real environment answer the same here, because the server has
      // exactly one rule for which of them wins -- a second rule on this side is how
      // a home with its key in `.env` would see no providers at all.
      expect(hasKey(keyed)).toBe(true);
      expect(hasKey(unkeyed)).toBe(false);
      expect(all.every((row) => typeof hasKey(row) === "boolean")).toBe(true);

      // BOTH FACES READ THE SAME RULE, each in the shape its own drawing wants: the
      // settings page needs the two halves APART -- the list, and the rest behind a
      // sentence -- and calls `splitByKey`, while the picker only ever asks "may this
      // one be offered" and filters with `hasKey`. One predicate underneath, which is
      // the point; a partition that SORTED would be a second opinion about a provider's
      // order, so the order given is the order kept.
      const { keyed: withKeys, unkeyed: without } = splitByKey(all);
      expect(withKeys.map((provider) => provider.name)).toEqual(["deepseek", "gamma"]);
      expect(without.map((provider) => provider.name)).toEqual(["ollama"]);
      expect(splitByKey([]).keyed).toEqual([]);
      expect(splitByKey([]).unkeyed).toEqual([]);
      // ...and the picker's filter is the same cut: what it keeps is exactly the half
      // the settings page lists.
      expect(all.filter(hasKey).map((provider) => provider.name)).toEqual(
        withKeys.map((provider) => provider.name),
      );

      // AND THE PICKER'S CONSEQUENCE: only the keyed providers' models reach the menu,
      // in the providers' own order. The session's CURRENT model is not this module's
      // question -- it is drawn even when its provider has no key, and that drawing is
      // the walkthrough's.
      expect(withKeys.flatMap((provider) => provider.models)).toEqual(["deepseek-flash", "g"]);
    },
  },
];

export const pickerSuite: Suite = { name: "picker", cases };
