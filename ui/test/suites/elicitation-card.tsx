// WHO THE CARD SAYS IS ASKING: three titles, rendered and read back.
//
// ================================================================ why this file
//
// THIS ONE RENDERS. Every other case about a question is about the wire or about a
// pure rule (`suites/elicitation.ts`), and neither can see what a person actually
// reads at the top of the card. The distinction has already cost this repo once: the
// session row's id line rendered NOTHING after the i18n merge, in every row of every
// project, while 859 backend cases, 36 UI cases, `tsc` and the bundle were all green
// -- because nothing in the run could render a component (see `suites/sidebar.tsx`,
// and vitest.config.ts for what this seam costs and what it cannot see).
//
// WHY A TITLE NEEDS THREE CASES AND NOT ONE. The card's words used to be decided by a
// single question -- 'is there a server name?' -- so its default reading was "a server
// is asking you", and that is now FALSE for a question one of this harness's own tools
// put up (`ask`). A person answering wants to know who wants to know, and the three
// askers are: an outside program by name, this harness's model, and somebody who did
// not name themselves. `ElicitationCardTitle` is where that choice lives and it is
// exported for exactly this: to be rendered here rather than asserted about.
//
// WHAT THIS FILE CANNOT SEE: layout. It has no DOM and no stylesheet, so "the line is
// where the eye lands" or "it was clipped by the card" are a browser's answers --
// `node scripts/dev.mjs --scripted`, per AGENTS.md. What it pins is that the line
// DRAWS and what it SAYS.
import { renderToStaticMarkup } from "react-dom/server";
import { I18nextProvider } from "react-i18next";
import { expect } from "vitest";

import { ElicitationCardTitle, ElicitationField } from "../../src/components/approval-gate";
import type { FieldSpec, FieldValue } from "../../src/lib/elicitation";
import type { Language } from "../../src/lib/language";
import { type Case, type Suite } from "../e2e";
import { renderI18n } from "../support/locale";

/// The languages the catalogs are written in. Both, always: a title that only reads
/// right in the language this developer happens to run in is a title half the users
/// never see, and the parity suite can only check that a key EXISTS in both.
const LANGUAGES: readonly Language[] = ["zh", "en"];

/// ONE TITLE, as a string. Inside a real i18n instance, because the component asks for
/// its own namespace through `useTranslation` -- the same seam the sidebar's row uses.
function titleOf(language: Language, asked: { server?: string; askedBy?: string }): string {
  return renderToStaticMarkup(
    <I18nextProvider i18n={renderI18n(language)}>
      <ElicitationCardTitle {...asked} />
    </I18nextProvider>,
  );
}

const titleCase: Case = {
  name: "the-card-says-who-is-asking-and-never-guesses",
  run: async () => {
    for (const language of LANGUAGES) {
      // An outside program, named. The case that existed before any of this, and the
      // one that must keep working: the name is what makes it worth fetching.
      expect(titleOf(language, { server: "fake" })).toContain("fake");

      // This harness's own tool. No server -- and the sentence must not say one,
      // which is the whole reason the third title exists.
      const model = titleOf(language, { askedBy: "model" });
      expect(model.length, "the model's title draws something").toBeGreaterThan(0);
      expect(model, "a tool's question is not called a server's").not.toContain("server");

      // Nobody named: a question whose asker the endpoint could not name. The old
      // words claimed a server here, which was a guess stated as a fact.
      const unknown = titleOf(language, {});
      expect(unknown.length, "an unnamed asker still draws a title").toBeGreaterThan(0);
      expect(unknown, "and does not invent a server").not.toContain("server");
    }

    // DISTINCT, in both languages. Three titles that all collapsed to one string
    // would satisfy every check above -- "renders something" is not "renders the
    // right thing" -- and that collapse is exactly what the two-title version was.
    for (const language of LANGUAGES) {
      const rendered = new Set([
        titleOf(language, { server: "fake" }),
        titleOf(language, { askedBy: "model" }),
        titleOf(language, {}),
      ]);
      expect(rendered.size, "three askers read as three different things").toBe(3);
    }
  },
};

/// ONE FIELD, as a string. The same seam as the title and for the same reason: which
/// control a person can click is a `data-slot` in the markup, and neither a pure rule
/// nor a translation key can see whether it drew.
function fieldOf(spec: FieldSpec, value?: FieldValue): string {
  return renderToStaticMarkup(
    <I18nextProvider i18n={renderI18n("en")}>
      <ElicitationField field={spec} value={value} own="" onValue={() => {}} onOwn={() => {}} />
    </I18nextProvider>,
  );
}

/// How many times a slot appears. The COUNT is part of the claim rather than an
/// incidental: the tick boxes are one per candidate, and a form that drew three where
/// the list held four would be offering a shorter question.
function slots(markup: string, slot: string): number {
  return markup.split(`data-slot="${slot}"`).length - 1;
}

const fieldCase: Case = {
  name: "the-control-a-field-gets-is-the-one-its-kind-calls-for",
  run: async () => {
    // A MULTIPLE CHOICE IS ONE TICK BOX PER CANDIDATE AND NOT A SELECT. That is the
    // failure `inputKindFor`'s ordering exists to prevent, and it would be invisible:
    // a select over the same candidates looks right and can carry only one answer.
    const unticked = fieldOf({
      name: "targets",
      kind: "array",
      itemValues: ["api", "web", "docs"],
      allowOther: true,
    });
    expect(slots(unticked, "elicitation-checkbox"), "one box per candidate").toBe(3);
    expect(slots(unticked, "elicitation-select"), "and not a single-choice list").toBe(0);
    expect(unticked).toContain("api");
    expect(slots(unticked, "elicitation-other"), "the own-words box sits beside the list").toBe(1);

    // WHICH ONES ARE TICKED comes from the value, and only from it.
    const ticked = fieldOf(
      { name: "targets", kind: "array", itemValues: ["api", "web", "docs"] },
      ["api"],
    );
    expect(slots(ticked, "elicitation-checkbox")).toBe(3);
    expect(ticked.split("checked").length - 1, "exactly the ticked one is ticked").toBe(1);

    // A SINGLE CHOICE IS A SELECT -- with an own-words box ONLY because the schema asked
    // for one. The same shape without the extension key is what a server's own enum is,
    // and it is what this card drew before any of this existed.
    const offered = fieldOf({
      name: "db",
      kind: "string",
      enumValues: ["postgres", "sqlite"],
      allowOther: true,
    });
    expect(slots(offered, "elicitation-select")).toBe(1);
    expect(slots(offered, "elicitation-other")).toBe(1);
    // TWO CONTROLS MEANS NO `<label>` WRAPPER -- so the control has to name itself, and
    // so does the own-words box (a placeholder is not a name). Only on this path: inside
    // a label an `aria-label` would REPLACE the words already sitting next to the box.
    expect(offered, "the control names itself where the label is gone").toContain('aria-label="db"');
    expect(offered, "and so does the box that is not the answer").toContain(
      'aria-label="or type your own"',
    );

    const servers = fieldOf({ name: "db", kind: "string", enumValues: ["postgres", "sqlite"] });
    expect(slots(servers, "elicitation-select")).toBe(1);
    expect(slots(servers, "elicitation-other"), "no box nobody asked for").toBe(0);
    expect(servers, "and no aria-label where the label is already there").not.toContain("aria-label");

    // A SCALAR KEEPS ITS TEXT BOX, and the wrapper still names the kind -- so a field
    // this client has no input for is drawn and labelled rather than quietly dropped.
    const text = fieldOf({ name: "note", kind: "string" });
    expect(slots(text, "elicitation-input")).toBe(1);
    expect(text).toContain('data-field-kind="string"');
    expect(slots(text, "elicitation-other")).toBe(0);
    expect(text, "a lone control keeps its label").not.toContain("aria-label");
  },
};

export const elicitationCardSuite: Suite = {
  name: "elicitation-card",
  cases: [titleCase, fieldCase],
};
