// The subagent rows, RENDERED: the definitions group, the delegation records, and the
// roster the settings form opens a row from.
//
// ================================================================ why this file
//
// These three groups are read on two screens (the sidebar's block and the settings
// page) and they are the only place a person ever sees what a subagent IS. Both draw
// their text through `components/subagent-list.tsx`, so what is pinned here is the
// text of the rows themselves -- which is the thing a wrong word would be wrong in,
// and the thing a screenshot is not needed for.
//
// IT NEEDS NO HARNESS AND NO BROWSER. Every case but the first renders to a STRING
// through `react-dom/server`; the first talks to the real endpoint over HTTP. Neither
// mounts a document, which is why `subagent-list.tsx` is a file of its own -- it must
// not reach `lib/i18n.ts` (that one reads `document` as it loads) for this run to
// work at all. See vitest.config.ts for what that seam costs.
//
// WHAT THIS FILE DOES NOT COVER, AND WHERE IT IS COVERED:
//
//   * THE CLICK. A row that opens a subagent's own conversation is a `onClick` on a
//     button, and a static render drops handlers -- so "the id it opens is not the
//     parent's" is not assertable here. It is a walkthrough item instead
//     (`node scripts/dev.mjs --scripted`), which is also the only layer that can see
//     whether the row is reachable, scrollable, or ellipsized into uselessness.
//   * THE EXPAND. The sidebar's block fetches in an effect, and an effect does not run
//     during a server render, so the container is not rendered here at all -- only the
//     rows it draws, with the listing handed to them directly. Same walkthrough.
import { renderToStaticMarkup } from "react-dom/server";
import { I18nextProvider } from "react-i18next";
import type { ReactElement } from "react";
import { expect } from "vitest";

import { type Case, type Suite, url } from "../e2e";
import { renderI18n } from "../support/locale";
import {
  DefinitionButtons,
  DefinitionRows,
  RunRows,
} from "../../src/components/subagent-list";
import type { Language } from "../../src/lib/language";
import type { SubagentDefinition, SubagentRun } from "../../src/lib/subagents";

/// The instant a delegation's timestamp is read from. FIXED, and a literal date string
/// is what a case must NOT assert: `formatTime` draws the machine's timezone, so a
/// pinned string would pass on one laptop and fail on the next. The expectation reads
/// the same instant back through the same locale instead.
const AT = Date.UTC(2026, 8, 18, 2, 15);

/// The session that delegated, and two sessions that were delegated TO. Three
/// different ids, because "which one does this row open" is the question the ids are
/// there to answer.
const PARENT = "9b0a4b0e-6f8e-4a02-9d3d-1c2b3a4d5e6f";
const FIRST = "1f2e3d4c-5b6a-4798-8c9d-0e1f2a3b4c5d";
const SECOND = "2a3b4c5d-6e7f-4809-9a1b-2c3d4e5f6a7b";

/// The two built-ins as the ENDPOINT sends them -- the same four fields plus
/// `builtin`, spelled the way JSON has them (`baseline` as a string, not a keyword).
/// The first case pins the endpoint against these, so a change to what the server
/// says cannot leave this file quietly testing a shape nobody sends.
const GENERAL: SubagentDefinition = {
  name: "general",
  description: "A second pair of hands with everything this session has.",
  baseline: "all",
  exclude: [],
  builtin: true,
};
const EXPLORE: SubagentDefinition = {
  name: "explore",
  description: "Read-only: it finds things and reads them.",
  baseline: "read-only",
  exclude: [],
  builtin: true,
};
const AUDITOR: SubagentDefinition = {
  name: "auditor",
  description: "Reads and reports.",
  baseline: "read-only",
  exclude: ["read", "glob"],
  builtin: false,
};

/// One delegation. `running` is the server's in-memory table sampled at the moment the
/// listing was answered, which is why it is a field of the row and not a subscription.
function run(overrides: Partial<SubagentRun> = {}): SubagentRun {
  return {
    threadId: FIRST,
    parent: PARENT,
    subagent: "explore",
    project: null,
    delegatedAt: AT,
    running: false,
    ...overrides,
  };
}

/// Render ONE component inside a real i18n instance, the way a screen would.
function inline(node: ReactElement, language: Language): string {
  return renderToStaticMarkup(<I18nextProvider i18n={renderI18n(language)}>{node}</I18nextProvider>);
}

/// EVERY element carrying one `data-slot`, as its attributes and its stripped text.
///
/// ALL OF THEM, NOT THE FIRST, and that is the case that needs it: "one delegation is
/// in flight and the other is not" is a claim about two rows, and a helper that
/// answered only for the first could not tell the two apart.
///
/// The trailing quote in the pattern is what keeps `subagent-run` from matching
/// `subagent-run-trigger`. `\1` closes the tag that was opened, so an element holding
/// nested spans reads as one thing rather than stopping at the first inner `</span>`.
/// There is no parser in this run (no DOM) and one element does not need one.
function allWith(html: string, slot: string): { attrs: string; inner: string }[] {
  const pattern = new RegExp(
    `<([a-z]+)([^>]*data-slot="${slot}"[^>]*)>([\\s\\S]*?)</\\1>`,
    "g",
  );
  return [...html.matchAll(pattern)].map((match) => ({
    attrs: match[2]!,
    inner: match[3]!.replace(/<[^>]*>/g, ""),
  }));
}

function oneWith(html: string, slot: string): { attrs: string; inner: string } {
  const found = allWith(html, slot);
  if (found.length === 0) {
    throw new Error(`no [data-slot="${slot}"] rendered: ${html}`);
  }
  return found[0]!;
}

const textOf = (html: string, slot: string): string => oneWith(html, slot).inner;

const textsOf = (html: string, slot: string): string[] =>
  allWith(html, slot).map((element) => element.inner);

/// Whether one element carries a bare `data-` attribute. React writes an empty string
/// for one set to `""` and omits one set to `undefined`, so presence IS the boolean --
/// which is exactly the contract the rows rely on.
const carries = (element: { attrs: string }, attribute: string): boolean =>
  element.attrs.includes(attribute);

const cases: Case[] = [
  {
    name: "the-endpoint-answers-in-the-shape-both-screens-read",
    run: async () => {
      // ONE CALL ANSWERS BOTH SCREENS, so this is the one case that pins the WIRE: the
      // two built-ins, in the order they are offered, each with the four fields a row
      // draws and the `builtin` flag a button is decided from.
      const res = await fetch(`${url()}api/subagents`);
      expect(res.status).toBe(200);
      const body = (await res.json()) as {
        subagents: SubagentDefinition[];
        problem: string | null;
        path: string | null;
        runs: SubagentRun[];
      };

      expect(body.subagents.map((s) => s.name)).toEqual(["general", "explore"]);
      expect(body.subagents.map((s) => s.baseline)).toEqual(["all", "read-only"]);
      expect(body.subagents.every((s) => s.builtin)).toBe(true);
      // A DESCRIPTION THAT IS EMPTY RENDERS AS A ROW WITH NO SECOND LINE -- which is
      // the failure the two built-ins' copy exists to avoid, so it is asserted rather
      // than left to the catalog.
      expect(body.subagents.every((s) => s.description.length > 0)).toBe(true);
      expect(body.subagents.every((s) => Array.isArray(s.exclude))).toBe(true);

      // THE PROBLEM IS PART OF A 200, not an error state: a home whose harness.edn has
      // a typo in its :subagents block still runs, and nil here is the assertion that
      // this home's file is not the case that proves it.
      expect(body.problem).toBeNull();
      // The file a save would write, named -- the form's note prints it, and a null
      // here would print an empty name at the one moment it matters most.
      expect(body.path?.endsWith("harness.edn")).toBe(true);
      expect(Array.isArray(body.runs)).toBe(true);
    },
  },
  {
    name: "a-delegation-row-names-the-subagent-and-the-parent-session",
    run: async () => {
      const html = inline(<RunRows runs={[run()]} busy={false} onOpen={() => {}} />, "en");

      // WHICH SUBAGENT IT WENT TO, and the three facts are in the order a person asks
      // them. The parent's id is drawn TRUNCATED in a real column, so its whole value
      // rides in `title` -- asserted because a row whose only id line is ellipsized
      // away is a row nobody can match against anything.
      expect(textOf(html, "subagent-run-subagent")).toBe("explore");
      expect(textOf(html, "subagent-run-parent")).toBe(PARENT);
      expect(oneWith(html, "subagent-run-parent").attrs).toContain(`title="${PARENT}"`);
      expect(textOf(html, "subagent-run-at")).toBe(new Date(AT).toLocaleString("en"));

      // NOT RUNNING, and the absence is asserted in BOTH of the places the state is
      // written -- the attribute a stylesheet keys off, and the word a person reads.
      // Either one alone would let the other drift.
      expect(carries(oneWith(html, "subagent-run"), "data-running")).toBe(false);
      expect(() => oneWith(html, "subagent-run-state")).toThrow();
    },
  },
  {
    name: "a-delegation-still-in-flight-says-so-in-its-own-row-and-only-its-own",
    run: async () => {
      // TWO ROWS IN ONE LIST, which is the only arrangement that can catch a state
      // drawn from the wrong source: a component that read a prop, a constant, or the
      // list's own length would mark both or neither.
      const html = inline(
        <RunRows
          runs={[run({ running: true, subagent: "general" }), run({ threadId: SECOND })]}
          busy={false}
          onOpen={() => {}}
        />,
        "en",
      );

      const rows = allWith(html, "subagent-run");
      expect(rows.length).toBe(2);
      expect(carries(rows[0]!, "data-running")).toBe(true);
      expect(carries(rows[1]!, "data-running")).toBe(false);

      // AND THE WORD IS IN ONE ROW, NOT THE LIST. Same claim, from the side a person
      // reads: a badge hoisted out of the row would say "running" about a list.
      expect(textsOf(html, "subagent-run-state").map((word) => word.trim())).toEqual([
        "running",
      ]);

      // The row it is true of is still the one that says WHICH subagent -- so the two
      // facts are on one row rather than the state being attached to the list.
      expect(textsOf(html, "subagent-run-subagent")).toEqual(["general", "explore"]);
    },
  },
  {
    name: "the-definitions-group-draws-the-built-ins-and-says-where-they-are-edited",
    run: async () => {
      const html = inline(<DefinitionRows definitions={[GENERAL, EXPLORE]} />, "en");

      // THE GROUP IS NEVER EMPTY -- the two built-ins ARE the empty configuration --
      // so "no custom subagents" has to be a LINE rather than a blank panel, and this
      // is the case that pins it.
      expect(textsOf(html, "subagent-definition-name")).toEqual(["general", "explore"]);
      expect(textsOf(html, "subagent-definition-badge").map((word) => word.trim())).toEqual([
        "built-in",
        "built-in",
      ]);
      expect(textOf(html, "subagent-definitions-custom-empty").length).toBeGreaterThan(0);

      // THE RANGE, IN WORDS. The `:all` sentence names the two names no range contains
      // -- that is the feature's post-condition showing up in the copy -- and the
      // "nothing else taken out" clause is what keeps it from reading as though
      // somebody had narrowed it.
      const ranges = textsOf(html, "subagent-definition-range");
      expect(ranges[0]!.startsWith("everything this session has, except eval and delegating")).toBe(true);
      expect(ranges[0]!.endsWith("nothing else taken out")).toBe(true);
      expect(ranges[1]!.startsWith("only tools that cannot change anything")).toBe(true);

      // AND WITH ONE OF YOUR OWN: the third row is there, the line about having none is
      // gone, the badge changes word, and the exclusions are named. One entry, four
      // consequences -- which is why they are asserted together.
      const withCustom = inline(
        <DefinitionRows definitions={[GENERAL, EXPLORE, AUDITOR]} />,
        "en",
      );
      expect(textsOf(withCustom, "subagent-definition-name")).toEqual([
        "general",
        "explore",
        "auditor",
      ]);
      expect(textsOf(withCustom, "subagent-definition-badge")[2]!.trim()).toBe("yours");
      expect(textsOf(withCustom, "subagent-definition-range")[2]!).toContain(
        "minus read, glob",
      );
      expect(() => oneWith(withCustom, "subagent-definitions-custom-empty")).toThrow();
      // The built-ins keep their place at the top: the file's order is the reader's
      // (see `overlay`), and a group that re-sorted would move the two rows a person
      // has learned to look at.
      expect(carries(oneWith(withCustom, "subagent-definition"), "data-builtin")).toBe(true);
    },
  },
  {
    name: "the-roster-marks-a-built-in-so-the-form-can-hide-its-remove",
    run: async () => {
      // THE SETTINGS ROSTER, where the badge DECIDES something rather than describing:
      // a built-in gets no Remove button and a custom one does, and the flag the form
      // reads to know which is `data-builtin`. It is asserted because getting it wrong
      // in the safe direction still leaves a person unable to delete their own
      // subagent, which is the half of the feature they came for.
      const html = inline(
        <DefinitionButtons definitions={[GENERAL, AUDITOR]} onEdit={() => {}} />,
        "en",
      );
      const rows = allWith(html, "subagent-roster-row");
      expect(rows.length).toBe(2);
      expect(carries(rows[0]!, "data-builtin")).toBe(true);
      expect(carries(rows[1]!, "data-builtin")).toBe(false);
      expect(rows[0]!.attrs).toContain('data-name="general"');
      expect(rows[1]!.attrs).toContain('data-name="auditor"');
      expect(textsOf(html, "subagent-definition-badge").map((word) => word.trim())).toEqual([
        "built-in",
        "yours",
      ]);
    },
  },
  {
    name: "the-same-row-reads-the-same-way-in-both-languages",
    run: async () => {
      // THE WORDS ARE THE FEATURE'S, NOT A SCREEN'S, and they come from the `shell`
      // catalog on both screens (see subagent-list.tsx) -- so this is where the two
      // languages are pinned together, in the same elements, rather than only in the
      // parity check that compares the catalogs to each other.
      const definitionsEn = inline(<DefinitionRows definitions={[GENERAL, EXPLORE]} />, "en");
      const definitionsZh = inline(<DefinitionRows definitions={[GENERAL, EXPLORE]} />, "zh");
      expect(textOf(definitionsEn, "subagent-definition-badge").trim()).toBe("built-in");
      expect(textOf(definitionsZh, "subagent-definition-badge").trim()).toBe("内置");
      expect(textOf(definitionsZh, "subagent-definitions-custom-empty").length).toBeGreaterThan(0);
      expect(textOf(definitionsZh, "subagent-definition-range")).toContain("eval");

      const runningEn = inline(
        <RunRows runs={[run({ running: true })]} busy={false} onOpen={() => {}} />,
        "en",
      );
      const runningZh = inline(
        <RunRows runs={[run({ running: true })]} busy={false} onOpen={() => {}} />,
        "zh",
      );
      expect(textOf(runningEn, "subagent-run-state").trim()).toBe("running");
      expect(textOf(runningZh, "subagent-run-state").trim()).toBe("跑着");

      // AND THE EMPTY STATE IS A STATE, in both. It is the answer to "has anything ever
      // been delegated", and a group that drew nothing at all would be an answer
      // nobody could tell from a group that failed to load.
      const emptyEn = inline(<RunRows runs={[]} busy={false} onOpen={() => {}} />, "en");
      const emptyZh = inline(<RunRows runs={[]} busy={false} onOpen={() => {}} />, "zh");
      expect(textOf(emptyEn, "subagent-runs-empty").trim()).toBe(
        "Nothing has been delegated yet.",
      );
      expect(textOf(emptyZh, "subagent-runs-empty").trim()).toBe("还没有委派过。");
      expect(() => oneWith(emptyEn, "subagent-runs")).toThrow();
    },
  },
];

export const subagentsSuite: Suite = { name: "subagents", cases };
