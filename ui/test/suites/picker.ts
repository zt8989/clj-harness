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
import {
  modelMenu,
  modelRowFromKey,
  modelRowKey,
  type ModelRow,
} from "../../src/lib/model-rows";

/// A row carrying the shared key fact, in the shape BOTH faces hand it around: the
/// settings row and the picker's choices row are different objects that agree on
/// `key`, and this is the smallest thing that stands for either.
type KeyedRow = KeyFact & { name: string; models: string[] };

/// A vendor as the model picker's menu reads it: the shared key fact, the ids it declares,
/// and the label it may carry. `lib/model-rows.ts` names exactly these fields -- and names
/// them structurally, for the reason above -- so a change to either wire shape stops this
/// file COMPILING rather than letting the literals drift into agreement with nothing.
type MenuVendor = KeyedRow & { "display-name"?: string };

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
  {
    name: "a-model-row-is-named-by-its-vendor-and-its-id",
    run: async () => {
      // THE BUG THIS PINS. A model id does NOT name a row: two vendors may declare the
      // same id, and a home really does hold both (`qwen` and `workbuddy` both serving
      // `deepseek-v4.1-flash` is one such home). A row used to be identified by the id
      // ALONE -- the vendor was "implied by the heading" -- so two rows shared one
      // identity: both were drawn as the current one, and a pick under EITHER heading
      // resolved to whichever vendor the catalog lists first (`qwen`, because the server
      // sorts vendors by name). The person picked workbuddy; the run went to qwen.
      //
      // `lib/model-rows.ts` is where a row's identity lives now, and it is pure --
      // literals in, options out -- so what a pick would SEND is pinned right here.
      const qwen: MenuVendor = {
        name: "qwen",
        models: ["deepseek-v4.1-flash"],
        key: { "present?": true, source: "env-file", name: "QWEN_API_KEY" },
      };
      const workbuddy: MenuVendor = {
        name: "workbuddy",
        // A LABEL IS NOT AN IDENTITY: the heading says this, the key below says
        // `workbuddy`, and the ID is what the server is sent (see the comment on
        // `:display-name` in `lib/composer.ts`).
        "display-name": "WorkBuddy",
        models: ["glm-5.3-flash", "deepseek-v4.1-flash"],
        key: { "present?": true, source: "env-file", name: "WORKBUDDY_API_KEY" },
      };
      const menu = modelMenu(
        [qwen, workbuddy],
        { provider: "workbuddy", model: "deepseek-v4.1-flash" },
        "not in the catalog",
      );

      // TWO ROWS, NOT ONE: the menu is grouped by vendor, so both headings are drawn,
      // each with its own row of that same id under it.
      const twins = menu.options.filter((option) => option.label === "deepseek-v4.1-flash");
      expect(twins).toHaveLength(2);
      expect(twins.map((option) => option.group)).toEqual(["qwen", "WorkBuddy"]);

      // AND EVERY ROW HAS AN IDENTITY OF ITS OWN. One identity per row is the whole
      // claim: two rows sharing one would be a React key the list cannot tell apart, a
      // `selected` comparison that marks both, and a `find` that answers the first.
      expect(new Set(menu.options.map((option) => option.value)).size).toBe(menu.options.length);

      // THE SESSION'S OWN ROW IS WORKBUDDY'S -- not the first vendor declaring that id.
      expect(menu.current).toBe("workbuddy/deepseek-v4.1-flash");
      expect(menu.options.find((option) => option.value === menu.current)).toBe(twins[1]);

      // AND A PICK HANDS BACK THAT PAIR, which is what the composer sends: the vendor is
      // what sits before the FIRST separator (a model id may hold one too -- openrouter
      // serves `anthropic/claude-sonnet-4.5`).
      expect(modelRowFromKey(menu.current)).toEqual({
        provider: "workbuddy",
        model: "deepseek-v4.1-flash",
      });
      expect(modelRowFromKey("openrouter/anthropic/claude-sonnet-4.5")).toEqual({
        provider: "openrouter",
        model: "anthropic/claude-sonnet-4.5",
      });

      // A ROW WITH NO VENDOR IS NOT A VENDOR WITH AN EMPTY NAME. A provider described
      // inline in config.edn has no id to send, so its row is keyed by an EMPTY vendor --
      // and that is what keeps it from colliding with a named vendor's twin, which an id
      // alone could never promise.
      expect(modelRowKey({ model: "anthropic/claude-sonnet-4.5" })).toBe(
        "/anthropic/claude-sonnet-4.5",
      );
      expect(modelRowFromKey("/anthropic/claude-sonnet-4.5")).toEqual({
        model: "anthropic/claude-sonnet-4.5",
      });
    },
  },
  {
    name: "a-row-the-menu-cannot-offer-comes-back-named-by-its-own-vendor",
    run: async () => {
      // THE VENDOR'S KEY FILTERS THE MENU (`lib/provider-key.ts`), and a session being
      // SERVED by a vendor without one is not erased by that: its own row rides at the
      // top. What changed is WHICH row rides -- the pair, so the row that comes back is
      // the vendor serving this session rather than a twin that happens to share the id.
      const keyed: MenuVendor = {
        name: "workbuddy",
        models: ["deepseek-v4.1-flash"],
        key: { "present?": true, source: "env-file", name: "WORKBUDDY_API_KEY" },
      };
      const unkeyed: MenuVendor = {
        name: "qwen",
        models: ["deepseek-v4.1-flash"],
        key: { "present?": false, source: null, name: "QWEN_API_KEY" },
      };
      const served: ModelRow = { provider: "qwen", model: "deepseek-v4.1-flash" };
      const menu = modelMenu([unkeyed, keyed], served, "not in the catalog");
      expect(menu.options.map((option) => option.value)).toEqual([
        "qwen/deepseek-v4.1-flash",
        "workbuddy/deepseek-v4.1-flash",
      ]);
      expect(menu.options[0]).toEqual({
        value: "qwen/deepseek-v4.1-flash",
        label: "deepseek-v4.1-flash",
        hint: "not in the catalog",
      });
      expect(menu.current).toBe("qwen/deepseek-v4.1-flash");

      // AN INLINE PROVIDER (no id at all) gets the same treatment, and its row is a
      // DIFFERENT row from a named vendor's twin of the same id -- which is the whole
      // reason the vendor-less key keeps its empty half.
      const inline = modelMenu([keyed], { model: "deepseek-v4.1-flash" }, "not in the catalog");
      expect(inline.current).toBe("/deepseek-v4.1-flash");
      expect(inline.options.map((option) => option.value)).toEqual([
        "/deepseek-v4.1-flash",
        "workbuddy/deepseek-v4.1-flash",
      ]);

      // AND WHEN NO TIER NAMED A MODEL there is no row of the session's own to mark: the
      // menu marks its FIRST row, which is what this picker has always shown.
      const unnamed = modelMenu([keyed], {}, "not in the catalog");
      expect(unnamed.options.map((option) => option.value)).toEqual([
        "workbuddy/deepseek-v4.1-flash",
      ]);
      expect(unnamed.current).toBe("workbuddy/deepseek-v4.1-flash");
    },
  },
];

export const pickerSuite: Suite = { name: "picker", cases };
