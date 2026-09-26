// The subagent rows, RENDERED: the definitions group, and the roster the settings form
// opens a row from.
//
// THE DELEGATION ROWS ARE GONE FROM HERE WITH THE ROW ITSELF (`.scratch/subagent-view`
// ticket 06): `RunRows` had one reader -- the sidebar block -- and the sidebar block is
// retired, because the door into a subagent's conversation is the `agent` call in the
// transcript now, and the mirror that call opens is a panel rather than a navigation.
// The ENDPOINT is still pinned below, `runs` included: the route stays (`GET
// /api/subagents` is the only answer to "what has this home ever delegated"), and a
// front end with no reader for half of an answer is a fact worth keeping visible.
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
} from "../../src/components/subagent-list";
import type { Language } from "../../src/lib/language";
import type { SubagentDefinition } from "../../src/lib/subagents";

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

/// Render ONE component inside a real i18n instance, the way a screen would.
function inline(node: ReactElement, language: Language): string {
  return renderToStaticMarkup(<I18nextProvider i18n={renderI18n(language)}>{node}</I18nextProvider>);
}

/// EVERY element carrying one `data-slot`, as its attributes and its stripped text.
///
/// ALL OF THEM, NOT THE FIRST, because the claims below are about a LIST -- which rows
/// carry which marker -- and a helper that answered only for the first could not tell
/// two rows apart.
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
        /// THE HALF NO SCREEN READS ANY MORE (ticket 06): the route keeps answering it,
        /// so it is still pinned here -- an endpoint half of which quietly disappeared
        /// from the wire is a change nobody would see in a browser.
        runs: unknown[];
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

      // AND THE EMPTY STATE IS A STATE, in both: a home with no subagents of its own
      // gets a line saying so rather than a blank group, because "none" and "failed to
      // load" must not look the same.
      const emptyEn = inline(<DefinitionRows definitions={[GENERAL, EXPLORE]} />, "en");
      const emptyZh = inline(<DefinitionRows definitions={[GENERAL, EXPLORE]} />, "zh");
      expect(textOf(emptyEn, "subagent-definitions-custom-empty").length).toBeGreaterThan(0);
      expect(textOf(emptyZh, "subagent-definitions-custom-empty").length).toBeGreaterThan(0);
    },
  },
];

export const subagentsSuite: Suite = { name: "subagents", cases };
