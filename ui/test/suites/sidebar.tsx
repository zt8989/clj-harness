// The session row in the sidebar: ONE line, holding what the person first said (the id
// when nothing has been said, always one hover away) and how long ago they last sent to
// it. The line used to be two; the second one was the log's `mtime · bytes`, and both
// facts are gone from the listing -- the time moved to the right end as a relative age,
// the absolute value moved into the tooltip, and the size is not shown at all.
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
// project, and an empty element has no line box: the row looked like a row
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
import { AppBrand } from "../../src/components/app-brand";
import { REVEAL_ON_HOVER } from "../../src/lib/reveal";
import { TITLE_MAX } from "../../src/lib/session-title";
import {
  ThreadListItem,
  ThreadListItemAction,
} from "../../src/components/assistant-ui/elements/thread-list.aui";
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

/// A session as `GET /api/projects` writes one: an id, an archived flag, whether a run
/// is in flight, and the two nullable things -- when it was last sent to, and what it
/// was called (see `lib/projects.ts` for all four, and for why the nulls are facts
/// rather than gaps).
type Session = {
  threadId: string;
  archived: boolean;
  running: boolean;
  /// WHEN SOMEBODY LAST PRESSED SEND, or null for a row nothing was ever sent to.
  lastSentAt: number | null;
  /// WHAT THE PERSON FIRST SAID IN THIS CONVERSATION, as the STORE has it, or null
  /// when it has not been named (`sessions.title`; the field's own doc comment is in
  /// `lib/projects.ts`). Raw and up to 200 code points, which is why the row's line
  /// is not simply this string -- see the case about the two lengths.
  firstUserText: string | null;
};

/// What most rows carry: a conversation somebody has spoken in. `session()` defaults
/// to it because that is the ordinary row, and the unnamed ones say so themselves.
const SAID = "把侧边栏的标题改成会话标题";

/// THE INSTANT THE ORDINARY ROW WAS SENT, read once, and every fixture that uses the
/// default send time is an OFFSET from it. That is forced by what the row draws: a
/// relative age is measured against the clock at render, which this run cannot freeze --
/// so a fixture pins the DISTANCE (56 minutes) and lets the two clocks differ by the
/// milliseconds between this line and the render, rather than pinning a string that only
/// passes on the day it was written.
///
/// A CASE WHOSE RUNG IS SHORTER THAN THIS SUITE'S RUNTIME READS ITS OWN CLOCK. The
/// shortest rung here is half a minute ("just now"), and a full suite can take longer
/// than that between THIS line and the case that uses it -- so that case reads `Date.now()`
/// where it stands (`the-right-end-counts-back-from-now`). Reading it here and reusing it
/// below made the case fail on a slow run with "1 min ago", which is a fact about the
/// suite's runtime rather than about the row.
///
/// The one absolute instant below is the TOOLTIP's, and it is read back through the same
/// locale for the same reason `stats.ts` does: the timezone is the machine's.
const NOW = Date.now();
const MINUTE = 60_000;
const HOUR = 60 * MINUTE;
const DAY = 24 * HOUR;
/// A fixed instant, used only where an ABSOLUTE time is what is being asserted.
const AT = Date.UTC(2026, 8, 17, 6, 30);

const ID = "fe924629-a49b-4a53-a31c-6d4b8af190db";

function session(overrides: Partial<Session> = {}): Session {
  return {
    threadId: ID,
    archived: false,
    running: false,
    lastSentAt: NOW - 56 * MINUTE,
    firstUserText: SAID,
    ...overrides,
  };
}

/// ONE ROW, rendered the way the sidebar renders it: inside a real i18n instance, with
/// no action button (the sidebar passes one; the row is a presentation component and
/// does not care) and with every state defaulted to "just sitting there".
function row(
  s: Session,
  language: Language,
  flags: { current?: boolean; running?: boolean; parked?: boolean; liveTitle?: string | null } = {},
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
        liveTitle={flags.liveTitle ?? null}
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
/// `\1` closes the tag that was opened, which is what lets a first line holding a nested
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
    name: "a-row-is-one-line-and-it-says-the-name-and-how-long-ago",
    run: async () => {
      const s = session({ lastSentAt: AT });
      const html = row(s, "en");

      // THE BUG, IN ONE ASSERTION -- kept, with the line it was about renamed. The id
      // used to be the only name a session had in this product, so a row that did not
      // draw it was a row a person could not tell from the row above it; that is what
      // rendered as the empty string on every row. Now the name is what the person
      // first said, and the assertion is the same one: THE LINE HAS TEXT.
      expect(textOf(html, "thread-list-item-title")).toBe(SAID);

      // AND THE DISK FACTS ARE GONE, both of them -- which is the shape of this feature
      // rather than a tidy-up: the listing does not stat anything any more, so a row
      // that still drew a size would be drawing a field that is not on the wire. The
      // element is asserted ABSENT rather than left unchecked, because the failure this
      // would hide is a row that kept rendering a stale second line.
      expect(html).not.toContain("thread-list-item-meta");
      expect(html).not.toContain("359");
      expect(html).not.toContain("B<");

      // THE RIGHT END SAYS WHEN IT WAS LAST SENT TO, relatively: this fixture is fixed
      // in the past, so at render it is a date (`lib/relative-time.ts` decides, and the
      // case below pins every bucket). What matters here is that the element exists and
      // that it holds an AGE rather than a size.
      expect(textOf(html, "thread-list-item-time").length).toBeGreaterThan(0);

      // AND BOTH EXACT FACTS ARE ONE HOVER AWAY: the id, which disambiguates two rows
      // with the same title ("继续" is a sentence two sessions can share), and the full
      // timestamp, which is what a relative age deliberately rounds off. Two lines in
      // one attribute, read back through the locale because the timezone is the
      // machine's.
      expect(attrOf(html, "thread-list-item-trigger", "title")).toBe(
        `${s.threadId}\n${new Date(AT).toLocaleString("en")}`,
      );
    },
  },
  {
    name: "the-right-end-counts-back-from-now",
    run: async () => {
      // THE LADDER, AS THE ROW WORDS IT. `lib/relative-time.ts` owns the boundaries and
      // `test/suites/relative-time.ts` pins them as arithmetic; what this case is for is
      // the OTHER half -- that the bucket reaches the row as a translated phrase, in
      // both languages, with the count in it. A row that drew the bucket's name, or
      // dropped the count, would be green in that suite and wrong on the page.
      // THE CLOCK IS READ HERE, not at module load: "just now" is half a minute wide and
      // this suite is 84 cases long, so an instant read before they ran put the fixture
      // over the boundary (it read "1 min ago" on a 53 s run). Every instant in this case
      // is an offset from THIS read, so the whole case is one instant plus the
      // milliseconds between it and the render.
      const now = Date.now();
      const at = (ago: number) => textOf(row(session({ lastSentAt: now - ago }), "en"), "thread-list-item-time");
      const atZh = (ago: number) =>
        textOf(row(session({ lastSentAt: now - ago }), "zh"), "thread-list-item-time");

      expect(at(MINUTE / 2)).toBe("just now");
      expect(at(56 * MINUTE)).toBe("56 min ago");
      expect(at(3 * HOUR)).toBe("3 h ago");
      expect(at(3 * DAY)).toBe("3 d ago");
      expect(at(200 * DAY)).toBe(
        new Date(now - 200 * DAY).toLocaleDateString("en", { month: "numeric", day: "numeric" }),
      );

      // AND THE SAME LADDER IN THE OTHER LANGUAGE, because these are sentences a person
      // reads rather than units: `刚刚` and `56 分钟前` come from the Chinese catalog,
      // and a missing key would render the key itself here.
      expect(atZh(MINUTE / 2)).toBe("刚刚");
      expect(atZh(56 * MINUTE)).toBe("56 分钟前");
      expect(atZh(3 * HOUR)).toBe("3 小时前");
      expect(atZh(3 * DAY)).toBe("3 天前");
    },
  },
  {
    name: "the-indent-slot-is-always-drawn-and-the-spinner-goes-in-it",
    run: async () => {
      // THE OWNER'S SECOND GEOMETRY REQUEST ("留下的缩进刚好显示 loading 状态"), as far as a
      // render can see it: the slot is a FIXED ELEMENT on every row, and a run puts the
      // spinner INSIDE it rather than adding an element before the name. That is what
      // keeps the title's x fixed -- a spinner that came and went as a sibling would
      // shift every word on the row when a run started.
      //
      // (The pixels are the browser's answer and the walkthrough measures them: the slot
      // is `size-3.5` and the row is `ps-2 gap-1.5`, which is the project row's 28px in
      // `sidebar.tsx`. What is pinned here is the STRUCTURE that number depends on.)
      const idle = row(session(), "en", { running: false });
      const running = row(session(), "en", { running: true });

      expect(idle).toContain('data-slot="thread-list-item-slot"');
      expect(running).toContain('data-slot="thread-list-item-slot"');
      expect(idle).not.toContain("thread-list-item-running");
      expect(running).toContain("thread-list-item-running");

      // THE SLOT COMES BEFORE THE NAME IN THE MARKUP, in both -- the spinner is in the
      // indent, not after the title.
      expect(running.indexOf('data-slot="thread-list-item-slot"')).toBeLessThan(
        running.indexOf('data-slot="thread-list-item-title"'),
      );
      // AND THE NAME ITSELF DOES NOT MOVE OR CHANGE when a run starts: the row says the
      // same thing, and the difference between the two renders is the icon and nothing
      // else that a reader reads.
      expect(textOf(running, "thread-list-item-title")).toBe(textOf(idle, "thread-list-item-title"));
      expect(textOf(running, "thread-list-item-time")).toBe(textOf(idle, "thread-list-item-time"));

      // AND THE SPINNER STILL HAS ITS WORD, for a screen reader that cannot see it (see
      // the `session.running` key).
      expect(running).toContain("Running");
    },
  },
  {
    name: "a-row-shows-the-title-the-server-kept-not-the-whole-copy-it-kept",
    run: async () => {
      // TWO LENGTHS, AND THEY ARE NOT THE SAME LENGTH ON PURPOSE. The store keeps up to
      // 200 code points of the first message (a storage guard, so one pasted file
      // cannot make the sidebar's one listing megabytes); a row shows 60 and an
      // ellipsis (`lib/session-title.ts`). The row is therefore where the two rules
      // meet, and the failure this pins is the one that would look like nothing at all:
      // a row rendering the stored string directly, with no end to it.
      const long = "好".repeat(200);
      const html = row(session({ firstUserText: long }), "en");
      const drawn = textOf(html, "thread-list-item-title");

      expect([...drawn].length).toBe(TITLE_MAX + 1);
      expect(drawn.endsWith("…")).toBe(true);
      expect(drawn.startsWith("好".repeat(TITLE_MAX))).toBe(true);

      // AND AN EMPTY STORED TITLE IS NOT A TITLE: a row with `""` falls back to the id
      // exactly like a null one, because the fallback is about having nothing to say
      // rather than about how the store spells nothing.
      expect(textOf(row(session({ firstUserText: "   " }), "en"), "thread-list-item-title")).toBe(
        ID,
      );
    },
  },
  {
    name: "the-page-knows-better-than-the-listing-it-asked-for",
    run: async () => {
      // THE SECOND SOURCE, and the reason there are two: the listing is a snapshot from
      // whenever the sidebar last asked, so a session that has just been typed into is
      // missing exactly the message that named it -- and the row it was typed into is
      // where a person is looking. The page holds that session's runtime, so its answer
      // wins.
      const live = "刚打完的第一句话";
      const html = row(session(), "en", { liveTitle: live });

      expect(textOf(html, "thread-list-item-title")).toBe(live);
      expect(textOf(html, "thread-list-item-title")).not.toBe(SAID);

      // AND A STALE LISTING STILL WINS OVER NOTHING: the override is per session and
      // only present while this page holds that runtime, so a row without one draws the
      // store's copy (asserted by the case above, which passes no `liveTitle` at all).
    },
  },
  {
    name: "a-row-nothing-was-ever-sent-to-says-which-absence-it-is",
    run: async () => {
      // A SESSION NOTHING WAS EVER SENT TO has no time to draw, and the word for that
      // is the ROW's choice: `lib/relative-time.ts` deliberately answers no absence (it
      // returns buckets), because only the thing drawing the row knows which absence it
      // is looking at. So it is pinned here, in both languages, rather than in the
      // catalog suite -- which checks that the two catalogs agree, never that anybody
      // says these words.
      const fresh = session({ lastSentAt: null, firstUserText: null });
      expect(textOf(row(fresh, "en"), "thread-list-item-time")).toBe("never run");
      expect(textOf(row(fresh, "zh"), "thread-list-item-time")).toBe("还没跑过");

      // AND A ROW WITH NO SEND HAS NOTHING TO PUT IN THE TOOLTIP BUT THE ID: there is
      // no instant, so there is no second line -- an empty one would read as a time
      // that failed to load.
      expect(attrOf(row(fresh, "en"), "thread-list-item-trigger", "title")).toBe(fresh.threadId);

      // AND AN ABSENCE IS NOT A REASON TO LOSE THE FIRST LINE: a session nobody has
      // spoken in is drawn as its ID, which is the owner's call rather than a leftover
      // (the top bar says `New session` for the same state -- see the two doc comments,
      // which have to agree: a row reading `New session` twenty times identifies
      // nothing, and a bar reading a uuid names nothing).
      expect(textOf(row(fresh, "en"), "thread-list-item-title")).toBe(fresh.threadId);
    },
  },
  {
    name: "a-row-waiting-on-you-says-so-beside-the-name-and-not-inside-it",
    run: async () => {
      const s = session();
      const html = row(s, "en", { parked: true });

      // THE PAIR THAT COLLIDED. Both of these lines are on the row's first line, and
      // the merge that added the parked word is the merge that dropped the other one --
      // so the two are asserted together, and asserted as being on DIFFERENT SIDES of
      // the first line's element.
      expect(textOf(html, "thread-list-item-parked").trim()).toBe("Waiting on you");
      expect(textOf(html, "thread-list-item-title")).toBe(SAID);
      // WHY THE SEPARATION IS THE ASSERTION AND NOT A STYLE CHOICE: the name's element
      // is `truncate`, so a long title is ellipsized on every real sidebar. A parked
      // word INSIDE it would be clipped away with the tail -- which is the "come here"
      // signal of a run that stopped to ask a human, and the one thing that row has to
      // deliver. So the equality above is the separation: the name's element says the
      // name and NOTHING ELSE.

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
      // BESIDE THE NAME, NOT INSIDE IT, for the same measured reason the parked word is
      // there: the name's element truncates, so a label inside it is clipped away on
      // every real row -- and this one is what tells two identically-shaped rows apart.
      expect(textOf(html, "thread-list-item-title")).toBe(SAID);

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

      // AND EACH REPORTS THE STATE OF THE REGION IT NAMES (`aria-expanded` is that reading,
      // not a statement of what pressing the button would do): the control that exists only
      // while the sidebar is folded names a collapsed region and says `false`; the one in
      // the header names the expanded one and says `true`. Swapping them would have a
      // screen reader announce the opposite of what is on screen.
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
  {
    name: "the-brand-row-says-what-this-is",
    run: async () => {
      // WHAT THE MARK AND THE NAME SAY, in a render -- the first line of the sidebar, which
      // is the one piece of this page that is a NAME rather than a control.
      const brand = renderToStaticMarkup(<AppBrand />);
      expect(textOf(brand, "brand-name")).toBe("clj-harness");

      // THE MARK IS DECORATION AND SAYS SO (`aria-hidden`): the name beside it is the
      // information, and a mark that announced itself would have a screen reader say the
      // product twice. It is also not a link -- this product has one page, so a brand that
      // looks like it navigates would be a control that does nothing.
      expect(attrOf(brand, "brand-mark", "aria-hidden")).toBe("true");
      expect(brand).not.toContain("<a ");

      // WHERE THE ROW PUTS IT IS READ AS SOURCE, for the same reason the `id` above is:
      // the whole sidebar cannot be rendered in this run. Two facts, and the second is the
      // one this feature turned over -- the collapse control left the header row (New task,
      // add project, refresh) and now ends the brand row, which is the arrangement the demo
      // it copies uses. The `aria-controls` case above pins that it is still the SAME
      // control; this pins which row draws it. Layout -- that the two rows really are two
      // rows, and which one is on top -- is a browser's answer (`.scratch/brand-header/`).
      const brandRow = /data-slot="sidebar-brand"[\s\S]*?<\/div>/.exec(sidebarSource);
      expect(brandRow, "no brand row in sidebar.tsx").not.toBeNull();
      // TWO BRANCHES NOW (`components/sidebar.tsx`): the full row, and the rail's square.
      // The name-bearing one is what this case is about, so it is asserted by name.
      expect(brandRow![0]).toContain("<AppBrand />");
      expect(brandRow![0]).toContain("<SidebarCollapseButton");
      const headerRow = /data-slot="sidebar-header"[\s\S]*?<\/header>/.exec(sidebarSource);
      expect(headerRow, "no header row in sidebar.tsx").not.toBeNull();
      expect(headerRow![0]).not.toContain("SidebarCollapseButton");

      // AND THE BRAND ROW COMES FIRST, which is where a header's mark belongs: DOM order is
      // visual order in this column, so this one assertion is what "on top" means here.
      expect(sidebarSource.indexOf('data-slot="sidebar-brand"')).toBeLessThan(
        sidebarSource.indexOf('data-slot="sidebar-header"'),
      );
    },
  },
  {
    name: "a-disabled-row-action-stays-out-of-the-way",
    run: async () => {
      // THE BUG THIS PINS, measured in a browser on 2026-09-21: the sidebar disables every
      // row action while it is busy, shadcn's Button dims what is disabled
      // (`disabled:opacity-50`), and that is the SAME UTILITY WITH THE SAME VARIANT as the
      // reveal's `opacity-0` -- so `cn` (tailwind-merge) kept whichever came last and the
      // reveal lost. Every archive button in the list appeared at half opacity the moment
      // the owner clicked "Add project". Nothing about that is visible to a renderer, but
      // WHICH CLASSES SURVIVE `cn` is, and that is the whole bug: the two files that draw a
      // revealed control both take their reveal from `lib/reveal.ts`, so this case renders
      // that one string through the real component and reads the class attribute back.
      const html = renderToStaticMarkup(
        <I18nextProvider i18n={renderI18n("en")}>
          <ThreadListItemAction data-slot="thread-list-item-archive" disabled={true} />
        </I18nextProvider>,
      );
      const classes = attrOf(html, "thread-list-item-archive", "class").split(/\s+/);
      expect(classes).toContain("opacity-0");
      expect(classes).toContain("group-hover:opacity-100");
      expect(classes).toContain("group-focus-within:opacity-100");
      // THE TWO THAT DECIDE IT, and they are ONE assertion: the blanket dimming must be gone
      // (it would beat `opacity-0`, equal specificity, later in the sheet) and the scoped one
      // must be there (it is more specific, so a revealed disabled control still looks
      // disabled). A plain `not.toContain("disabled:opacity-50")` would fail on the second.
      expect(classes).toContain("disabled:opacity-0");
      expect(classes.filter((c) => c === "disabled:opacity-50")).toEqual([]);
      expect(classes).toContain("group-hover:disabled:opacity-50");
      // AND IT IS THE SHARED STRING the two call sites both take, so a fix to one is a fix
      // to the other: the sidebar's project "more" button renders the same tokens.
      expect(REVEAL_ON_HOVER.split(/\s+/).every((c) => classes.includes(c))).toBe(true);
    },
  },
  {
    name: "the-rail-is-the-mark-alone-with-nothing-to-read",
    run: async () => {
      // A FOLDED COLUMN IS TWO THINGS, and the wide one is a 48px RAIL
      // (`.scratch/sidebar-rail`): the controls stay, every word goes. Two facts are
      // machine-checkable here and both are the kind of thing that silently disappears.
      //
      // ONE: THE MARK WITH NO WORDMARK, because a 48px column has room for one thing and a
      // name truncated to a letter is worse than no name. The `compact` form is a PROP of
      // the one drawing of the mark, so this is also the assertion that the rail did not
      // grow a second logo.
      const compact = renderToStaticMarkup(<AppBrand compact />);
      expect(compact).toContain('data-slot="brand-mark"');
      expect(compact).not.toContain('data-slot="brand-name"');
      const full = renderToStaticMarkup(<AppBrand />);
      expect(full).toContain('data-slot="brand-mark"');
      expect(textOf(full, "brand-name")).toBe("clj-harness");

      // AND THE RAIL IS THE FORM THE SIDEBAR ACTUALLY ASKS FOR. Read as source, for the
      // reason this file keeps repeating: `sidebar.tsx` reaches `lib/i18n.ts` and cannot be
      // rendered in this run. The rail's cell is the one that says `compact`.
      const brandRow = /data-slot="sidebar-brand"[\s\S]*?<\/div>/.exec(sidebarSource);
      expect(brandRow, "no brand row in sidebar.tsx").not.toBeNull();
      expect(brandRow![0]).toContain("<AppBrand compact />");
      // ...and the way back is IN that cell, which is the whole difference between the two
      // shapes of a fold: on this shape the column never leaves, so the control that opens
      // it is the column's own (the page's floating one is `lg:hidden`).
      expect(brandRow![0]).toContain("<SidebarOpenButton");
      expect(brandRow![0]).toContain('shape="rail"');

      // TWO: THE LIST IS HIDDEN AND STILL MOUNTED. Both halves matter and they are one
      // claim: `hidden` and not absent is what keeps the scroll position, the folded-open
      // projects and the escape hatch's text -- and it is also what keeps this component,
      // the only reader of `GET /api/projects`, on the page. A rail that unmounted the list
      // would take the page's mount restore down with it (see the `folded` prop).
      const scroll = /data-slot="sidebar-scroll"[\s\S]*?className=\{cn\(([^)]*)\)/.exec(
        sidebarSource,
      );
      expect(scroll, "the list region is not a conditional class any more").not.toBeNull();
      expect(scroll![1]).toContain('folded && "hidden"');
      // AND THERE IS EXACTLY ONE OF IT, which is the other half of "still mounted": a
      // second, folded-only copy of the list would keep the pixels and lose the state.
      expect(sidebarSource.match(/data-slot="sidebar-scroll"/g)?.length).toBe(1);
    },
  },
];

export const sidebarSuite: Suite = { name: "sidebar", cases };
