// The settings panel's Security row: THIS HOME's sensitive paths as TWO groups -- the
// built-in half (always in force, NOT deletable) and the custom half (the person's own,
// each row removable, with 'clear my own list' when there is one).
//
// ================================================================ why this file
//
// THE ROW IS A PROMISE ABOUT WHAT A BUTTON DOES, and the promise is the two-group split:
// the built-in paths must be shown WITHOUT a delete button (they cannot be removed -- the
// list can never be switched off), and the custom paths must be shown WITH one. A render of
// the presentation component (`SecurityPathsView`, given data and callbacks) is the one
// place in this run that can read those two facts off the markup.
//
// IT IS `SecurityPathsView` AND NOT `SensitivePathsRow`: the container fetches on mount and
// a render to a string runs no effects, so it would draw only the loading line. The view is
// pure -- data in, callbacks in, markup out -- and it is the seam
// `components/security-paths.tsx` exists to expose (the same move
// `components/session-management.tsx` makes for the sessions page).
//
// WHAT THIS RUN CANNOT SEE: whether a press really writes config.edn, and whether the
// server's answer then changes the two groups. Those are the browser walkthrough's half
// (`node scripts/dev.mjs --scripted`, see AGENTS.md), and what is pinned here is what the
// row SAYS and which controls it draws.
import { renderToStaticMarkup } from "react-dom/server";
import { expect } from "vitest";

import { type Case, type Suite } from "../e2e";
import { translator } from "../support/locale";
import { SecurityPathsView } from "../../src/components/security-paths";
import type { Security } from "../../src/lib/securitySetting";
import type { Language } from "../../src/lib/language";

const BUILTIN = ["~/.ssh/", "~/.aws/"] as const;
const CUSTOM = ["~/.config/mine/"] as const;

const security = (overrides: Partial<Security> = {}): Security => ({
  "sensitive-paths": [...BUILTIN, ...CUSTOM],
  builtin: [...BUILTIN],
  custom: [...CUSTOM],
  ...overrides,
});

/// THE VIEW, rendered with the given record and language, and NOTHING ELSE -- every callback
/// is a no-op (this run cannot press a button), and nothing is saving or failing unless the
/// case says so.
function view(
  s: Security,
  language: Language,
  flags: { draft?: string; saving?: boolean; error?: string | null } = {},
): string {
  return renderToStaticMarkup(
    <SecurityPathsView
      security={s}
      draft={flags.draft ?? ""}
      saving={flags.saving ?? false}
      error={flags.error ?? null}
      t={translator(language, "settings")}
      onDraft={() => {}}
      onAdd={() => {}}
      onRemove={() => {}}
      onClear={() => {}}
    />,
  );
}

/// THE TEXT OF ONE SLOT, and the element found by its `data-slot` (the same regex the
/// sidebar suite reads with -- there is no DOM in this run, and one element needs no parser).
function textOf(html: string, slot: string): string {
  const match = new RegExp(`<([a-z]+)[^>]*data-slot="${slot}"[^>]*>([\\s\\S]*?)</\\1>`).exec(html);
  if (match === null) throw new Error(`no [data-slot="${slot}"] in the rendered row: ${html}`);
  return match[2]!.replace(/<[^>]*>/g, "");
}

/// EVERY REMOVE BUTTON ON THE PAGE, found by the `aria-label` the row gives each one. Its
/// presence on a CUSTOM row and its absence from a BUILT-IN row is the whole two-group
/// contract, so the label is the thing worth counting.
const removeButtons = (html: string): number =>
  (html.match(/aria-label="Remove /g) ?? []).length;

const cases: Case[] = [
  {
    name: "the-built-in-half-is-shown-and-has-no-remove-button",
    run: async () => {
      const html = view(security(), "en");

      // THE BUILT-IN ROWS ARE DRAWN.
      const builtin = textOf(html, "settings-security-builtin");
      expect(builtin).toContain("~/.ssh/");
      expect(builtin).toContain("~/.aws/");
      expect(builtin).toContain("built-in");

      // AND NOT ONE OF THEM CARRIES A REMOVE BUTTON: the built-in list cannot be switched
      // off, so a delete control on it would be a lie about what the panel can do.
      const builtinBlock = /data-slot="settings-security-builtin"[\s\S]*?<\/ul>/.exec(html)![0];
      expect(builtinBlock).not.toContain("Remove");

      // THE CUSTOM HALF, by contrast, has exactly the one remove button for its one row.
      expect(textOf(html, "settings-security-paths")).toContain("~/.config/mine/");
      expect(removeButtons(html)).toBe(1);
    },
  },
  {
    name: "with-nothing-of-your-own-the-empty-sentence-stands-and-no-clear-button",
    run: async () => {
      const html = view(security({ "sensitive-paths": [...BUILTIN], custom: [] }), "en");

      // THE BUILT-IN HALF IS STILL DRAWN -- the empty state is about the CUSTOM half, not
      // about the guard being off.
      expect(textOf(html, "settings-security-builtin")).toContain("~/.ssh/");
      expect(textOf(html, "settings-security-empty")).toContain("built-in list is still in force");

      // NO CUSTOM ROWS, NO REMOVE BUTTONS, AND NO 'clear my own list' -- there is nothing of
      // the person's to clear.
      expect(removeButtons(html)).toBe(0);
      expect(html).not.toContain("Clear my own list");
    },
  },
  {
    name: "the-clear-button-appears-only-when-there-is-a-custom-half",
    run: async () => {
      const withCustom = view(security(), "en");
      expect(withCustom).toContain("Clear my own list");

      const withoutCustom = view(security({ "sensitive-paths": [...BUILTIN], custom: [] }), "en");
      expect(withoutCustom).not.toContain("Clear my own list");
    },
  },
  {
    name: "the-write-controls-go-dark-while-a-write-is-in-flight",
    run: async () => {
      const html = view(security(), "en", { saving: true });
      // The remove button and the add button both carry `disabled` while saving -- the row
      // never advances its own state optimistically, so a second write must not be possible
      // until the first has answered.
      expect(html).toContain("disabled");
      // AND THE ADD BUTTON IS NOT ARMED FOR AN EMPTY DRAFT either (that is the ordinary
      // state, pinned here so the two reasons a control is off do not get merged).
      expect(view(security(), "en", { draft: "" })).toContain("disabled");
    },
  },
];

export const securityPathsSuite: Suite = { name: "security-paths", cases };
