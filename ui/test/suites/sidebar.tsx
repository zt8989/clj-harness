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
import {
  SIDEBAR_ID,
  SidebarCollapseButton,
  SidebarOpenButton,
} from "../../src/components/sidebar-toggle";
// THE ONE FILE IN THIS SUITE THAT IS IMPORTED AS TEXT RATHER THAN AS CODE, and the
// reason is in the case that reads it: the sidebar cannot be executed in this run, so
// the id its element carries is checked by reading the source. `?raw` is vite's own
// switch, so this is still the build's file list -- not a second notion of the source.
import sidebarSource from "../../src/components/sidebar.tsx?raw";
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
  label?: string,
): string {
  return renderToStaticMarkup(
    <I18nextProvider i18n={renderI18n(language)}>
      <ThreadListItem
        session={s}
        current={flags.current ?? false}
        busy={false}
        running={flags.running ?? false}
        parked={flags.parked ?? false}
        label={label ?? null}
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

/// ONE ATTRIBUTE OF ONE SLOT: the element found by its `data-slot`, the named attribute
/// read off its opening tag. The toggle below is an icon button whose whole meaning is a
/// pair of attributes (`aria-controls`, `aria-expanded`) -- there is no text to read and
/// nothing else in the markup that says what that control does.
function attrOf(html: string, slot: string, name: string): string {
  const element = new RegExp(`<([a-z]+)[^>]*data-slot="${slot}"[^>]*>`).exec(html);
  if (element === null) throw new Error(`no [data-slot="${slot}"] in the rendered control: ${html}`);
  const attribute = new RegExp(`\\b${name}="([^"]*)"`).exec(element[0]);
  if (attribute === null) {
    throw new Error(`[data-slot="${slot}"] carries no ${name}: ${element[0]}`);
  }
  return attribute[1]!;
}

/// THE FOLDING CONTROLS, rendered the way their two owners render them: each inside a real
/// i18n instance, with the one callback it exists to call.
///
/// THEY ARE RENDERED HERE RATHER THAN THE WHOLE SIDEBAR, and that is a boundary of this
/// run rather than a choice: `sidebar.tsx` imports `settings-panel.tsx`, which imports
/// `lib/i18n.ts`, which touches `document` as it initializes -- and this run has no
/// browser (see `vitest.config.ts` and `test/support/locale.ts`). The panel itself cannot
/// be rendered here; the pair of controls can, and they are where the words and the two
/// `aria-*` contracts live.
function openControl(language: Language): string {
  return renderToStaticMarkup(
    <I18nextProvider i18n={renderI18n(language)}>
      <SidebarOpenButton onOpen={() => {}} />
    </I18nextProvider>,
  );
}

function collapseControl(language: Language): string {
  return renderToStaticMarkup(
    <I18nextProvider i18n={renderI18n(language)}>
      <SidebarCollapseButton onCollapse={() => {}} />
    </I18nextProvider>,
  );
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
  {
    name: "a-row-can-say-which-project-it-came-from",
    run: async () => {
      // THE ARCHIVED BLOCK IS FLAT -- it holds the filed-away sessions of every project
      // AND the filed-away tasks -- so the row is the only place that can say which
      // project one came from, and the answer matters: that conversation's log is still
      // under that directory's workspace.
      const s = session();
      const html = row(s, "en", {}, "clj-harness");

      expect(textOf(html, "thread-list-item-label").trim()).toBe("clj-harness");
      // BESIDE THE ID, NOT INSIDE IT, for the same measured reason the parked word is
      // there: the id's element truncates, so a label inside it is clipped away on every
      // real row -- and this one is what tells two identically-shaped rows apart.
      expect(textOf(html, "thread-list-item-id")).toBe(s.threadId);

      // AND A TASK HAS NO LABEL AT ALL. Not an empty span to be read as one: a task came
      // from nowhere, and a blank slot where a project name goes would read as a bug.
      expect(row(s, "en")).not.toContain('data-slot="thread-list-item-label"');
      expect(row(s, "en", {}, "")).not.toContain('data-slot="thread-list-item-label"');
    },
  },
  {
    name: "the-fold-controls-say-what-they-do-in-both-languages",
    run: async () => {
      // TWO CONTROLS, ONE WORD EACH, and they are icon buttons -- so the words below are
      // the WHOLE of what either one says to a person who cannot see the glyph. A missing
      // one of these leaves a button in the corner with no name, which is invisible to
      // every other check in the tree (the same shape of hole the id line fell through).
      expect(textOf(openControl("en"), "sidebar-open")).toBe("Open sidebar");
      expect(textOf(openControl("zh"), "sidebar-open")).toBe("打开侧边栏");
      expect(textOf(collapseControl("en"), "sidebar-collapse")).toBe("Collapse sidebar");
      expect(textOf(collapseControl("zh"), "sidebar-collapse")).toBe("收起侧边栏");
    },
  },
  {
    name: "both-ends-of-the-fold-name-the-same-region",
    run: async () => {
      // THE TWO CONTROLS ARE ONE VERB, so each has to name the thing it folds: a screen
      // reader arriving at either button is told WHICH region it belongs to. Both say the
      // same id, and they say it about the element `sidebar.tsx` draws.
      expect(attrOf(openControl("en"), "sidebar-open", "aria-controls")).toBe(SIDEBAR_ID);
      expect(attrOf(collapseControl("en"), "sidebar-collapse", "aria-controls")).toBe(SIDEBAR_ID);

      // AND EACH REPORTS THE STATE IT IS OFFERING, which is not the state it is in: the
      // control that exists only while the sidebar is away asks for it to be there
      // (`false` -> expanded), and the one in the header asks for the opposite. Swapping
      // the two would read as a control that lies about what pressing it does.
      expect(attrOf(openControl("en"), "sidebar-open", "aria-expanded")).toBe("false");
      expect(attrOf(collapseControl("en"), "sidebar-collapse", "aria-expanded")).toBe("true");

      // THE THIRD REFERENCE IS READ AS SOURCE, not rendered -- and the reason is worth
      // stating rather than hiding: `sidebar.tsx` cannot be imported into this run at all
      // (it reaches `lib/i18n.ts`, which touches `document`; see the helper above), so the
      // element the id is written on is the one link of this chain a render cannot reach.
      // Without this line the pair above could name a region that does not exist and every
      // case here would stay green -- the exact failure this suite was built for.
      expect(sidebarSource).toMatch(/id=\{SIDEBAR_ID\}[\s\S]*?data-slot="sidebar"/);
    },
  },
];

export const sidebarSuite: Suite = { name: "sidebar", cases };
