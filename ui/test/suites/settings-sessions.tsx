// The settings panel's session page: every conversation this home keeps, one checkbox a row, and
// three batch verbs -- archive, unarchive, delete -- with the delete behind a confirmation.
//
// ================================================================ why this file is here
//
// THE THREE THINGS THAT MATTER ABOUT THIS PAGE ARE ALL SENTENCES, and nothing else in the tree can
// read them. What it says a row IS (the name, and the two states that change what a verb does), what
// the confirmation PROMISES (how many conversations go, and that the record goes with them), and
// whether the destructive button is OFF are drawn by `components/session-management.tsx` -- which
// exists as a module of its own precisely so this run can render it (`settings-panel.tsx` imports
// `lib/i18n.ts`, which touches `document`; see `vitest.config.ts`). The RULE those words serve is
// `lib/session-status.ts`'s and is pinned where it lives; what is pinned here is that it arrives on
// the button.
//
// THE ROWS ARE RENDERED THE WAY THE SIDEBAR SUITE RENDERS ITS OWN (`test/suites/sidebar.tsx`):
// `react-dom/server`, a real i18n instance from the real catalogs, and a slot's text read out of the
// markup rather than a substring searched for. THE CONFIRMATION IS RENDERED A LITTLE DIFFERENTLY,
// and the difference is the portal: `DialogContent` draws through a Radix portal that is empty until
// a browser mounts it, so a render of the open dialog here is `""`. Its body is a component of its
// own for exactly that reason, and it is rendered into a bare `<Dialog>` -- which is the context
// `DialogTitle` and `DialogDescription` require, and nothing more.
//
// WHAT THIS RUN CANNOT SEE: whether the dialog is really open over the list, whether a press sends
// the right request, and whether the list really loses the row afterwards. Those are the browser
// walkthrough's half (`node scripts/dev.mjs --scripted`, see AGENTS.md and the feature's spec).
import { renderToStaticMarkup } from "react-dom/server";
import { I18nextProvider } from "react-i18next";
import { expect } from "vitest";

import { type Case, type Suite } from "../e2e";
import { renderI18n } from "../support/locale";
import { Dialog } from "../../src/components/ui/dialog";
import {
  DeleteSessionsConfirmBody,
  SessionBatchRow,
  SessionsBatchPanel,
  type SessionsBatchProps,
} from "../../src/components/session-management";
import { TITLE_MAX } from "../../src/lib/session-title";
import type { SessionSummary } from "../../src/lib/projects";
import type { Language } from "../../src/lib/language";

/// A conversation as `GET /api/projects` writes one, which is the shape the panel's rows read (see
/// `lib/projects.ts` for the two nulls and why they are facts rather than gaps).
const ID = "2b0ea1d2-51c7-4b4e-8b3d-2d9f0d9a3f11";
const SAID = "把设置里的会话清一清";

const session = (overrides: Partial<SessionSummary> = {}): SessionSummary => ({
  threadId: ID,
  archived: false,
  running: false,
  lastSentAt: Date.UTC(2026, 8, 17, 6, 30),
  firstUserText: SAID,
  ...overrides,
});

/// THE TEXT OF ONE SLOT, and ONE ATTRIBUTE OF ONE SLOT: the two readers `suites/sidebar.tsx`
/// defines for its rendered rows, restated here for the reason it gives -- "the element is found by
/// its `data-slot` and its children are read" is a different claim from "the string is in the markup
/// somewhere", and the difference is the whole point of a suite that exists because a blank line
/// shipped green.
///
/// THEY ARE PRIVATE THERE AND COPIED HERE RATHER THAN SHARED, like the copy in `right-pane.tsx`:
/// these files are their only readers, and a support module for twelve lines of regex would be a
/// third file to open. The tag name may hold a digit, for the same reason that copy allows it.
function textOf(html: string, slot: string): string {
  const match = new RegExp(`<([a-z][a-z0-9]*)[^>]*data-slot="${slot}"[^>]*>([\\s\\S]*?)</\\1>`).exec(html);
  if (match === null) throw new Error(`no [data-slot="${slot}"] in the rendered panel: ${html}`);
  return match[2]!.replace(/<[^>]*>/g, "");
}

function attrOf(html: string, slot: string, name: string): string {
  const tag = new RegExp(`<([a-z][a-z0-9]*)[^>]*data-slot="${slot}"[^>]*>`).exec(html);
  if (tag === null) throw new Error(`no [data-slot="${slot}"] in the rendered panel: ${html}`);
  const attribute = new RegExp(`\\b${name}="([^"]*)"`).exec(tag[0]);
  if (attribute === null) throw new Error(`[data-slot="${slot}"] carries no ${name}: ${tag[0]}`);
  return attribute[1]!;
}

/// WHETHER ONE SLOT'S OPENING TAG CARRIES ONE ATTRIBUTE, which `attrOf` above deliberately cannot
/// answer: it throws when nothing is there, and that is right for "read me this value" and wrong for
/// "is this button off". THE EMPTY VALUE IS THE ATTRIBUTE, not a word: React renders a boolean one as
/// `disabled=""`, and the button's class list also contains `disabled:opacity-50` -- so a substring
/// search for "disabled" would say yes to a button that is on.
function hasAttr(html: string, slot: string, name: string): boolean {
  const tag = new RegExp(`<([a-z][a-z0-9]*)[^>]*data-slot="${slot}"[^>]*>`).exec(html);
  if (tag === null) throw new Error(`no [data-slot="${slot}"] in the rendered panel: ${html}`);
  return new RegExp(`\\s${name}(=""|\\s|>)`).test(tag[0]);
}

/// ONE ROW, rendered the way the page renders it: a real i18n instance, nothing selected and nothing
/// in flight unless a case says otherwise.
function row(session: SessionSummary, language: Language, error: string | null = null, selected = false): string {
  return renderToStaticMarkup(
    <I18nextProvider i18n={renderI18n(language)}>
      <ul>
        <SessionBatchRow
          session={session}
          selected={selected}
          busy={false}
          error={error}
          onToggle={() => {}}
        />
      </ul>
    </I18nextProvider>,
  );
}

/// THE WHOLE PAGE BODY, with everything a case does not care about defaulted to "just sitting
/// there": no selection, no failures, nothing in flight, the confirmation closed.
function panel(language: Language, overrides: Partial<SessionsBatchProps> = {}): string {
  const props: SessionsBatchProps = {
    sessions: [],
    selected: [],
    errors: {},
    busy: false,
    confirming: false,
    failure: null,
    onToggle: () => {},
    onToggleAll: () => {},
    onArchive: () => {},
    onDelete: () => {},
    onConfirmDelete: () => {},
    onCancelDelete: () => {},
    ...overrides,
  };
  return renderToStaticMarkup(
    <I18nextProvider i18n={renderI18n(language)}>
      <SessionsBatchPanel {...props} />
    </I18nextProvider>,
  );
}

/// THE CONFIRMATION'S INSIDE, rendered into a bare `Dialog` -- the context its title and description
/// need, and as much of the real dialog as this run can draw (see the header on the portal).
function confirm(language: Language, count: number): string {
  return renderToStaticMarkup(
    <I18nextProvider i18n={renderI18n(language)}>
      <Dialog open>
        <DeleteSessionsConfirmBody
          count={count}
          busy={false}
          onCancel={() => {}}
          onConfirm={() => {}}
        />
      </Dialog>
    </I18nextProvider>,
  );
}

const cases: Case[] = [
  {
    name: "a-row-shows-the-name-and-the-two-states-that-decide-a-verb",
    run: async () => {
      // WHAT A ROW HAS TO SAY BEFORE ANYBODY PRESSES ANYTHING: what this conversation is called, and
      // the two facts that change what the verbs do -- an archived row is what an unarchive moves
      // back, and a running one is what the server refuses to delete.
      const both = session({ archived: true, running: true });
      const en = row(both, "en");
      expect(textOf(en, "settings-session-title")).toBe(SAID);
      expect(textOf(en, "settings-session-archived")).toBe("Archived");
      expect(textOf(en, "settings-session-running")).toBe("Running");

      // THE SAME TWO WORDS IN THE OTHER LANGUAGE, from the same `shell` catalog the sidebar's rows
      // read: a missing key would render the key itself here, and a second wording would make one
      // state read two ways on one screen.
      const zh = row(both, "zh");
      expect(textOf(zh, "settings-session-archived")).toBe("已归档");
      expect(textOf(zh, "settings-session-running")).toBe("运行中");

      // AND A SETTLED CONVERSATION WEARS NEITHER -- not an empty badge, no element at all: a slot
      // that is always there would be read as a state, and this one has none.
      const calm = row(session(), "en");
      expect(calm).not.toContain("settings-session-archived");
      expect(calm).not.toContain("settings-session-running");

      // NO TITLE MEANS THE ID, which is the row's fallback rather than the top bar's (that one says
      // `New session`): forty rows reading `New session` identify nothing.
      expect(textOf(row(session({ firstUserText: null }), "en"), "settings-session-title")).toBe(ID);

      // AND THE STORE'S COPY GOES THROUGH THE SAME CLIP the sidebar's row applies -- the store keeps
      // up to 200 code points and a row shows `TITLE_MAX` of them plus an ellipsis.
      const long = "好".repeat(200);
      const drawn = textOf(row(session({ firstUserText: long }), "en"), "settings-session-title");
      expect([...drawn].length).toBe(TITLE_MAX + 1);
      expect(drawn.endsWith("…")).toBe(true);
    },
  },
  {
    name: "the-three-verbs-say-what-they-do-and-the-delete-is-off-for-a-running-one",
    run: async () => {
      const calm = session({ threadId: "11111111-1111-4111-8111-111111111111" });
      const live = session({
        threadId: "22222222-2222-4222-8222-222222222222",
        running: true,
        firstUserText: "还在跑的那条",
      });
      const sessions = [calm, live];

      // THE THREE VERBS, in the words the sidebar's own archive control already uses.
      const some = panel("en", { sessions, selected: [calm.threadId] });
      expect(textOf(some, "settings-sessions-archive")).toBe("Archive");
      expect(textOf(some, "settings-sessions-unarchive")).toBe("Unarchive");
      expect(textOf(some, "settings-sessions-delete")).toBe("Delete");
      expect(hasAttr(some, "settings-sessions-delete", "disabled")).toBe(false);

      // THE SELECT-ALL BOX REPORTS THE SELECTION rather than promising an action: off while one row
      // is ticked, on when every row is -- which is also what makes one control do both jobs.
      expect(hasAttr(some, "settings-sessions-select-all", "checked")).toBe(false);
      const all = panel("en", { sessions, selected: [calm.threadId, live.threadId] });
      expect(hasAttr(all, "settings-sessions-select-all", "checked")).toBe(true);
      expect(textOf(all, "settings-sessions-select-all-label")).toBe("Select all");

      // THE RUNNING CONVERSATION IS WHAT TURNS DELETE OFF -- the server refuses to take back a
      // conversation whose run is writing those very bytes, so the press is not offered. The reason
      // is on the button, and the ARCHIVE beside it stays on: filing a row away is not that refusal.
      expect(hasAttr(all, "settings-sessions-delete", "disabled")).toBe(true);
      expect(hasAttr(all, "settings-sessions-archive", "disabled")).toBe(false);
      expect(attrOf(all, "settings-sessions-delete", "title")).toBe(
        "A conversation here is still running — delete it once it settles.",
      );

      // NOTHING TICKED IS NOTHING TO DO: all three are off on an empty selection.
      const none = panel("en", { sessions, selected: [] });
      expect(hasAttr(none, "settings-sessions-archive", "disabled")).toBe(true);
      expect(hasAttr(none, "settings-sessions-unarchive", "disabled")).toBe(true);
      expect(hasAttr(none, "settings-sessions-delete", "disabled")).toBe(true);

      // AND THE SAME THREE VERBS IN THE OTHER LANGUAGE: 归档 / 取消归档 / 删除, the first two from the
      // catalog the sidebar reads.
      const zh = panel("zh", { sessions, selected: [calm.threadId] });
      expect(textOf(zh, "settings-sessions-archive")).toBe("归档");
      expect(textOf(zh, "settings-sessions-unarchive")).toBe("取消归档");
      expect(textOf(zh, "settings-sessions-delete")).toBe("删除");
      expect(textOf(zh, "settings-sessions-select-all-label")).toBe("全选");
    },
  },
  {
    name: "the-confirmation-names-how-many-and-what-goes-with-them",
    run: async () => {
      // THE TWO PROMISES THE CLICK MAKES. The number, because a selection is what was ticked and the
      // rows behind the modal are no longer readable; and the record, because that is the difference
      // between this verb and the archive beside it -- which is why the copy says "for good".
      const en = confirm("en", 3);
      expect(textOf(en, "settings-sessions-delete-count")).toBe("3 conversations will go.");
      expect(textOf(en, "settings-sessions-delete-body")).toContain("jsonl");
      expect(textOf(en, "settings-sessions-delete-body")).toContain("for good");
      expect(textOf(en, "settings-sessions-delete-submit")).toBe("Delete for good");
      expect(textOf(en, "settings-sessions-delete-cancel")).toBe("Keep them");

      // ONE IS ITS OWN SENTENCE, which is what a plural form is for and where a hard-coded "s" is
      // the bug: the count is the only variable either language has here.
      expect(textOf(confirm("en", 1), "settings-sessions-delete-count")).toBe(
        "1 conversation will go.",
      );

      // AND THE CHINESE SAYS THE SAME TWO THINGS, with the format's name left alone -- `jsonl` is a
      // file format and not a word to translate.
      const zh = confirm("zh", 3);
      expect(textOf(zh, "settings-sessions-delete-count")).toBe("3 条会话会被删掉。");
      expect(textOf(zh, "settings-sessions-delete-body")).toContain("jsonl");
      expect(textOf(zh, "settings-sessions-delete-submit")).toBe("彻底删除");
      expect(textOf(zh, "settings-sessions-delete-cancel")).toBe("留着");
    },
  },
  {
    name: "a-refused-row-and-a-refused-request-are-drawn-in-the-server-s-own-words",
    run: async () => {
      // THE SERVER REFUSES ROW BY ROW, so its sentence has a row to land on -- and it is drawn
      // VERBATIM: it is the server that refused, it says why, and a translation or a paraphrase here
      // would be one more thing to distrust (the rule `lib/projects.ts` states for the whole-request
      // case). The same sentence in both languages is the assertion that nothing translated it.
      const SENTENCE = "no session 2b0ea1d2 in this home, so there is nothing to delete";
      const en = panel("en", { sessions: [session()], selected: [ID], errors: { [ID]: SENTENCE } });
      expect(textOf(en, "settings-session-row-error")).toBe(SENTENCE);
      const zh = panel("zh", { sessions: [session()], selected: [ID], errors: { [ID]: SENTENCE } });
      expect(textOf(zh, "settings-session-row-error")).toBe(SENTENCE);

      // A REQUEST THAT FAILED WHOLE HAS NO ROW, so it is drawn above the list -- also verbatim, and
      // this is the other half of "nothing is rolled back": the rows that landed are still drawn as
      // landed underneath it.
      const refused = panel("en", {
        sessions: [session()],
        failure: "threadIds must be a non-empty list of conversation ids",
      });
      expect(textOf(refused, "settings-sessions-error")).toBe(
        "threadIds must be a non-empty list of conversation ids",
      );
      expect(refused).toContain("settings-session-row");

      // AND A HOME WITH NOTHING IN IT SAYS SO, which is a different sentence from a selection with
      // nothing ticked -- the empty state is about the home, not about the boxes.
      expect(textOf(panel("en"), "settings-sessions-empty")).toBe(
        "This home has no conversations yet.",
      );
      expect(textOf(panel("zh"), "settings-sessions-empty")).toBe("这一家还没有会话。");
    },
  },
  {
    name: "a-ticked-row-says-so-on-the-row-and-not-only-in-the-box",
    run: async () => {
      // THE ROW IS THE BUG'S OWN SHAPE. What was wrong with the selection was not the checkbox --
      // that one is honest -- but that NOTHING ELSE on the row said it: a list where four of forty
      // rows are quietly in the selection is a list somebody deletes the wrong thing from. So the
      // marker is the ROW's, in the shape the sidebar's rows use for `data-archived`.
      const ticked = row(session(), "en", null, true);
      expect(hasAttr(ticked, "settings-session-row", "data-selected")).toBe(true);

      // AND AN UNTICKED ROW DOES NOT CARRY IT, which is the half a substring search of the markup
      // cannot answer: the row's own class list names the colours the selected branch draws with,
      // so the attribute is asked for BY NAME rather than looked for as a word (`hasAttr`'s own
      // reason, one state over).
      expect(hasAttr(row(session(), "en"), "settings-session-row", "data-selected")).toBe(false);
    },
  },
];

export const settingsSessionsSuite: Suite = { name: "settings-sessions", cases };
