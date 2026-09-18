// The session row in the sidebar: two lines, and the FIRST one is the id.
//
// ================================================================ why this file
//
// EVERY OTHER SUITE HERE TALKS TO A HARNESS; this one RENDERS. `ThreadListItem` goes
// through `react-dom/server` to a string and the string is read. That is the whole
// point of the file, and the reason is a bug that shipped green:
//
// 2026-09-18. The i18n merge (`f96d1ef`) hand-merged this component with the
// parallel-sessions branch, took main's parked-word block, and left the branch's
// `{threadId}` behind -- so the row's id line rendered NOTHING, in every row of every
// project, and a `<code>` with no text has no line box: the row looked like a row
// that never had a title rather than one that lost it. The tree was green (859
// backend cases, 36 UI cases, `tsc`, the bundle) because NOTHING IN THIS RUN COULD
// RENDER A COMPONENT. It was found by opening a browser -- see
// `.scratch/session-title-blank/`, which is that browser loop, kept.
//
// So: this is the same assertion, where a suite can reach it. `vitest.config.ts`
// says what the seam costs and what it still cannot see.
//
// WHAT IT CANNOT SEE: LAYOUT. There is no DOM and no stylesheet here, so "the line is
// 16 pixels tall" or "the tail was ellipsized" are a browser's answers. What this
// file pins is what the row SAYS, and which element says it -- which is where the two
// defects of that merge both live (the id was not drawn at all; the parked word was
// drawn inside a line that truncates, where it gets eaten).
import { renderToStaticMarkup } from "react-dom/server";
import { I18nextProvider } from "react-i18next";
import { expect } from "vitest";

import { type Case, type Suite } from "../e2e";
import { renderI18n } from "../support/locale";
import { ThreadListItem } from "../../src/components/assistant-ui/elements/thread-list.aui";
import type { Language } from "../../src/lib/language";

/// A session as `GET /api/projects` writes one: an id, an archived flag, and the two
/// disk facts that are BOTH nullable -- null is a session with no log yet, which is
/// not the same thing as a zero-byte one (see `lib/projects.ts`).
type Session = {
  threadId: string;
  archived: boolean;
  lastActivity: number | null;
  bytes: number | null;
};

/// The instant the timestamp is read from. FIXED, and a literal date is exactly what
/// a case must NOT assert: `formatTime` draws the machine's timezone, so a pinned
/// string would pass on one developer's laptop and fail on the next. The expectation
/// below reads the same instant back through the same locale, the way `stats.ts` does.
const AT = Date.UTC(2026, 8, 17, 6, 30);

const ID = "fe924629-a49b-4a53-a31c-6d4b8af190db";

function session(overrides: Partial<Session> = {}): Session {
  return { threadId: ID, archived: false, lastActivity: AT, bytes: 359, ...overrides };
}

/// ONE ROW, rendered the way the sidebar renders it: inside a real i18n instance, with
/// no action button (the sidebar passes one; the row is a presentation component and
/// does not care) and with every state defaulted to "just sitting there".
function row(
  s: Session,
  language: Language,
  flags: { current?: boolean; running?: boolean; parked?: boolean } = {},
): string {
  return renderToStaticMarkup(
    <I18nextProvider i18n={renderI18n(language)}>
      <ThreadListItem
        session={s}
        current={flags.current ?? false}
        busy={false}
        running={flags.running ?? false}
        parked={flags.parked ?? false}
        onOpen={() => {}}
      />
    </I18nextProvider>,
  );
}

/// THE TEXT OF ONE SLOT in the rendered row: the element found by its `data-slot`, its
/// markup stripped, its nested spans stripped with it.
///
/// A REGEX, AND THE ASSERTION IS WHY. The obvious shorthand -- `expect(html).toContain(ID)`
/// -- WOULD HAVE PASSED ON THE BROKEN ROW, because the id is also the row's `title`
/// attribute. "The id is in the markup somewhere" is a different claim from "the row
/// says it", and this file exists because the difference is invisible until somebody
/// looks at a page. So the element has to be found and its children read.
///
/// `\1` closes the tag that was opened, which is what lets a `<code>` holding a nested
/// `<span>` read as one element rather than stopping at the inner `</span>`. There is no
/// parser in this run (no DOM -- see vitest.config.ts) and one element does not need one.
function textOf(html: string, slot: string): string {
  const match = new RegExp(`<([a-z]+)[^>]*data-slot="${slot}"[^>]*>([\\s\\S]*?)</\\1>`).exec(html);
  if (match === null) throw new Error(`no [data-slot="${slot}"] in the rendered row: ${html}`);
  return match[2]!.replace(/<[^>]*>/g, "");
}

/// The states a person actually reads off this row: sitting still with facts, brand new
/// with none, and stopped to ask a human.
const cases: Case[] = [
  {
    name: "a-row-is-two-lines-and-the-first-one-is-the-id",
    run: async () => {
      const s = session();
      const html = row(s, "en");

      // THE BUG, IN ONE ASSERTION. The id is the only name a session has in this
      // product -- nothing renames one -- so a row that does not draw it is a row a
      // person cannot tell from the row above it. This is what rendered as the empty
      // string on every row.
      expect(textOf(html, "thread-list-item-id")).toBe(s.threadId);

      // AND THE SECOND LINE IS STILL THE DISK FACTS, which is what makes the row
      // readable rather than merely identifiable. The size is a literal because units
      // do not translate; the timestamp is read back through the locale, because the
      // timezone is the machine's (see `lib/format.ts`).
      const facts = textOf(html, "thread-list-item-meta");
      expect(facts.startsWith(new Date(AT).toLocaleString("en"))).toBe(true);
      expect(facts.endsWith(" · 359 B")).toBe(true);
    },
  },
  {
    name: "a-row-with-no-log-yet-says-which-absence-it-is",
    run: async () => {
      // A SESSION THAT HAS NOT RUN is a row with no disk facts at all, and the words
      // for that are the ROW's choice: `lib/format.ts` deliberately answers no absence
      // (its `formatBytes`/`formatTime` take numbers), because only the thing drawing
      // the row knows which absence it is looking at. So they are pinned here, in both
      // languages, rather than in the catalog suite -- which checks that the two
      // catalogs agree, never that anybody says these words.
      const fresh = session({ lastActivity: null, bytes: null });
      expect(textOf(row(fresh, "en"), "thread-list-item-meta")).toBe("never run · no log yet");
      expect(textOf(row(fresh, "zh"), "thread-list-item-meta")).toBe("还没跑过 · 还没有日志");

      // AND AN ABSENCE IS NOT A REASON TO LOSE THE ID LINE.
      expect(textOf(row(fresh, "en"), "thread-list-item-id")).toBe(fresh.threadId);
    },
  },
  {
    name: "a-row-waiting-on-you-says-so-beside-the-id-and-not-inside-it",
    run: async () => {
      const s = session();
      const html = row(s, "en", { parked: true });

      // THE PAIR THAT COLLIDED. Both of these lines are on the row's first line, and
      // the merge that added the parked word is the merge that dropped the id -- so
      // the two are asserted together, and asserted as being on DIFFERENT SIDES of the
      // id line's element.
      expect(textOf(html, "thread-list-item-parked").trim()).toBe("Waiting on you");
      expect(textOf(html, "thread-list-item-id")).toBe(s.threadId);
      // WHY THE SEPARATION IS THE ASSERTION AND NOT A STYLE CHOICE: the id's element
      // is `truncate` and holds 36 characters of monospace, so it is ellipsized on
      // every real sidebar. A parked word INSIDE it would be clipped away with the
      // tail -- which is the "come here" signal of a run that stopped to ask a human,
      // and the one thing that row has to deliver. So the equality above is the
      // separation: the id's element says the id and NOTHING ELSE.

      // The word is a person's, so it comes from the catalog like every other word in
      // the shell -- and the Chinese one is the same claim.
      expect(textOf(row(s, "zh", { parked: true }), "thread-list-item-parked").trim()).toBe(
        "等你回应",
      );
    },
  },
];

export const sidebarSuite: Suite = { name: "sidebar", cases };
