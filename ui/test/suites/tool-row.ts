// The two tables in `components/message-parts.tsx` keyed by TOOL NAME -- `TOOL_ICONS`
// and the `subjectOf` switch -- and the one failure mode they share: a renamed tool keeps
// working and silently loses its face.
//
// ================================================================ why this file
//
// `TOOL_ICONS` has a fallback (`WrenchIcon`) deliberately unlike every entry, and
// `subjectOf` has a default that answers null -- so a name the page has never heard of
// still draws a row: the wrong hand, and the tool's name with no subject beside it.
// Nothing throws and nothing goes red; the only witness is a person looking at the row.
// The table's own comment names the cost ("a renamed tool loses its icon silently"), and
// 2026-09-22 is the day it would have been paid: `.scratch/omp-parity` ticket 01 renamed
// `anchor_grep` to `grep`, and these are the two lines that had to follow.
//
// WHY THE SOURCE IS READ RATHER THAN THE ROW RENDERED, which is the inferior reading and
// the only one available here: `message-parts.tsx` reaches `lib/i18n.ts` through
// `components/composer-chrome.tsx` and `lib/attachments.ts`, and `lib/i18n.ts` touches
// `document` at module scope (`markDocument`). This run has no jsdom and no `window` for a
// module to find -- deliberately, see `vitest.config.ts` -- so the file cannot be
// imported at all (`test/ui.test.ts` says the same about it where it counts the
// `reasoning-row` cases). Two earlier suites in this directory read a source for exactly
// this reason (`suites/sidebar.tsx`, `suites/i18n.ts`).
//
// WHAT THAT COSTS: this file can say the two tables name `grep`, and cannot say the icon
// draws. The drawing is the browser walkthrough's (`node scripts/dev.mjs --scripted`).
import messagePartsSource from "../../src/components/message-parts.tsx?raw";
import { expect } from "vitest";

import { type Case, type Suite } from "../e2e";

/// The `TOOL_ICONS` literal, as text: the table's own body, so an entry can be read
/// without reading the file around it.
const iconTable = (): string => {
  const found = /const TOOL_ICONS: Record<string, ElementType> = \{([\s\S]*?)\n\};/.exec(
    messagePartsSource,
  );
  expect(found, "no TOOL_ICONS table in message-parts.tsx").not.toBeNull();
  return found![1];
};

const cases: Case[] = [
  {
    name: "the-icon-table-knows-the-name-the-tool-table-registers",
    run: async () => {
      // THE ICON IS NAMED, not merely present: `SearchIcon` is what a search row draws,
      // and any other entry here would be a row that lies about which hand it is.
      expect(iconTable()).toMatch(/^\s*grep: SearchIcon,$/m);
      // ...AND THE OLD NAME IS GONE FROM THE WHOLE FILE. A table entry left behind under
      // `anchor_grep` would be dead text (nothing registers that name any more), which is
      // the quietest way for a rename to half-land.
      expect(messagePartsSource).not.toContain("anchor_grep");
    },
  },
  {
    name: "and-the-row-answers-the-pattern-as-the-subject",
    run: async () => {
      // The switch's `grep` arm: the pattern, which is what a search is ABOUT. The
      // default answers null, so a missing arm is a row with no subject rather than a
      // failure -- see this file's header.
      expect(messagePartsSource).toMatch(/case "grep":\s*return stringArg\(args, "pattern"\) \?\? null;/);
    },
  },
  {
    name: "the-read-half-of-the-list-carries-the-list-s-hand",
    run: async () => {
      // `todo_read` is `todo_write`'s other half -- the same list, read back rather
      // than replaced -- so it draws the SAME icon. Pinned as a pair, because a row
      // that looked like a different hand would be the one thing wrong with a call
      // that has no arguments to show and nothing to say for itself but the name.
      expect(iconTable()).toMatch(/^\s*todo_read: ListTodoIcon,$/m);
      expect(iconTable()).toMatch(/^\s*todo_write: ListTodoIcon,$/m);
    },
  },
  {
    name: "the-job-family-carries-one-hand",
    run: async () => {
      // FOUR NAMES, ONE SUBJECT: a command nobody is waiting for. `job_list` is the door back
      // to the other three (a listing is read, then one of its ids is addressed), so a family
      // whose rows sat together in one transcript must not read as four unrelated tools.
      // BEFORE THIS TICKET none of the four had an entry at all, which is the fallback's
      // sentence -- `WrenchIcon`, 'the page does not know this tool' -- said about tools the
      // backend has had for months: half a family would have been the worse half-measure.
      for (const name of ["job", "job_kill", "job_list", "job_output"]) {
        expect(iconTable()).toMatch(new RegExp(`^\\s*${name}: HourglassIcon,$`, "m"));
      }
    },
  },
];

export const toolRowSuite: Suite = { name: "tool-row", cases };