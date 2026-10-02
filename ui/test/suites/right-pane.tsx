// The right-hand column's switch: the two controls that open and close it, and the shell they
// open onto (`.scratch/right-pane-tasks`, ticket 01).
//
// ================================================================ what is assertable here
//
// THE PAIR IS THE SIDEBAR'S PAIR ON THE OTHER SIDE. `components/sidebar-toggle.tsx` argues the
// contract (one verb in two places, one exported id both name through `aria-controls`, each
// saying which state it is asking for) and `components/right-pane-toggle.tsx` reads that argument
// rather than writing it again -- so the two icon controls can be RENDERED to a string and their
// words and `aria-*` read back, which is what `suites/sidebar.tsx` does for the left-hand two and
// what this file does for these. The task pane renders too: it is a heading and a sentence per
// section, with no runtime and no fetch behind it (the rows are tickets 02/03).
//
// WHAT THIS RUN CANNOT SEE. The COLUMN those controls belong to -- the `aside` that is the third
// child of the page's flex row -- cannot be rendered here: `components/subagent-view.tsx` pulls in
// the transcript, which reaches `lib/i18n.ts` and touches `document` at module scope, and this run
// has no browser by design (`vitest.config.ts`). So the state the column is mounted from, which
// control is drawn when, and the fact that the task pane is the SAME column as the mirror are read
// as SOURCE, the idiom `suites/subagent-view.tsx` introduced. The LAYOUT -- the column appearing
// beside the conversation, the drawer it becomes below `md`, the backdrop behind that drawer, the
// two corners the controls sit in -- is the
// browser walkthrough's half (`node scripts/dev.mjs --scripted`, see AGENTS.md).
import { renderToStaticMarkup } from "react-dom/server";
import { I18nextProvider } from "react-i18next";
import { expect } from "vitest";

import { type Case, type Suite } from "../e2e";
import { renderI18n } from "../support/locale";
import {
  RIGHT_PANE_ID,
  RightPaneBackButton,
  RightPaneCollapseButton,
  RightPaneOpenButton,
} from "../../src/components/right-pane-toggle";
import { TaskPane } from "../../src/components/task-pane";
import { JobRows } from "../../src/components/task-pane-jobs";
import type { JobRow } from "../../src/lib/jobs";
import { SubagentRows } from "../../src/components/task-pane-subagents";
import { mirrorOf, subagentRowsOf, type SubagentTaskRow } from "../../src/lib/subagents-runs";
import type { SubagentListing } from "../../src/lib/subagents";
import toggleSource from "../../src/components/right-pane-toggle.tsx?raw";
import taskPaneSource from "../../src/components/task-pane.tsx?raw";
import jobRowsSource from "../../src/components/task-pane-jobs.tsx?raw";
import subagentRowsSource from "../../src/components/task-pane-subagents.tsx?raw";
import taskPaneHookSource from "../../src/hooks/use-task-pane.ts?raw";
import jobsLibSource from "../../src/lib/jobs.ts?raw";
import taskPaneSubagentsSource from "../../src/components/task-pane-subagents.tsx?raw";
import subagentsRunsSource from "../../src/lib/subagents-runs.ts?raw";
import panelSource from "../../src/components/subagent-view.tsx?raw";
import contextSource from "../../src/components/subagent-view-context.ts?raw";
import appSource from "../../src/app.tsx?raw";
import type { Language } from "../../src/lib/language";
import {
  STATS_TITLE_ID,
  STATS_VIEW_ID,
  StatsCloseButton,
  StatsOpenButton,
} from "../../src/components/right-pane-toggle";
import { StatsView } from "../../src/components/stats-view";
import statsViewSource from "../../src/components/stats-view.tsx?raw";
import homeStatsSource from "../../src/lib/home-stats.ts?raw";
import useHomeStatsSource from "../../src/hooks/use-home-stats.ts?raw";
import focusTrapSource from "../../src/hooks/use-focus-trap.ts?raw";

/// THE TEXT OF ONE SLOT, and ONE ATTRIBUTE OF ONE SLOT: the two readers `suites/sidebar.tsx`
/// defines for its rendered controls and rows, restated here for the same reason it gives --
/// "the element is found by its `data-slot` and its children are read" is a different claim
/// from "the string is in the markup somewhere", and an icon button whose whole meaning is a
/// pair of attributes has no text to fall back on.
///
/// THEY ARE PRIVATE THERE AND COPIED HERE RATHER THAN SHARED: these two files are their only
/// readers, and a support module for twelve lines of regex would be a third file to open.
/// `\1` closes the tag that was opened, which is what lets a slot holding a nested `<span>` or
/// an `<svg>` read as one element -- there is no parser in this run, and one element needs none.
///
/// THE TAG NAME MAY HOLD A DIGIT HERE, which the sidebar's copy does not allow: the slots it
/// reads sit on `<div>`s and `<button>`s, while a SECTION HEADING is an `<h2>` -- and a reader
/// that cannot spell the element under test is a reader that fails on a correct render.
function textOf(html: string, slot: string): string {
  const match = new RegExp(`<([a-z][a-z0-9]*)[^>]*data-slot="${slot}"[^>]*>([\\s\\S]*?)</\\1>`).exec(html);
  if (match === null) throw new Error(`no [data-slot="${slot}"] in the rendered pane: ${html}`);
  return match[2]!.replace(/<[^>]*>/g, "");
}

function attrOf(html: string, slot: string, name: string): string {
  const element = new RegExp(`<([a-z]+)[^>]*data-slot="${slot}"[^>]*>`).exec(html);
  if (element === null) throw new Error(`no [data-slot="${slot}"] in the rendered control: ${html}`);
  const attribute = new RegExp(`\\b${name}="([^"]*)"`).exec(element[0]);
  if (attribute === null) {
    throw new Error(`[data-slot="${slot}"] carries no ${name}: ${element[0]}`);
  }
  return attribute[1]!;
}

/// THE THREE THINGS THIS SUITE RENDERS, each in a real i18n instance from the real catalogs
/// (see `test/support/locale.ts` for why that instance is built there and not in `lib/i18n.ts`).
function openControl(language: Language): string {
  return renderToStaticMarkup(
    <I18nextProvider i18n={renderI18n(language)}>
      <RightPaneOpenButton onOpen={() => {}} />
    </I18nextProvider>,
  );
}

function collapseControl(language: Language): string {
  return renderToStaticMarkup(
    <I18nextProvider i18n={renderI18n(language)}>
      <RightPaneCollapseButton onCollapse={() => {}} />
    </I18nextProvider>,
  );
}

function backControl(language: Language): string {
  return renderToStaticMarkup(
    <I18nextProvider i18n={renderI18n(language)}>
      <RightPaneBackButton onBack={() => {}} />
    </I18nextProvider>,
  );
}

function taskPane(language: Language): string {
  return renderToStaticMarkup(
    <I18nextProvider i18n={renderI18n(language)}>
      <TaskPane threadId="t1" onCollapse={() => {}} onOpen={() => {}} onStats={() => {}} />
    </I18nextProvider>,
  );
}

/// THE STATISTICS' TRAILING CONTROL, rendered on its own for the same reason the other three
/// are: its whole meaning is a glyph and a name, and neither survives a source read.
function statsControl(language: Language): string {
  return renderToStaticMarkup(
    <I18nextProvider i18n={renderI18n(language)}>
      <StatsOpenButton onOpen={() => {}} />
    </I18nextProvider>,
  );
}

/// THE STATISTICS PAGE'S OWN WAY OUT, rendered for the same reason: its meaning is a glyph and a
/// name, and the name it points at is a region of its own (`STATS_VIEW_ID`).
function statsCloseControl(language: Language): string {
  return renderToStaticMarkup(
    <I18nextProvider i18n={renderI18n(language)}>
      <StatsCloseButton onClose={() => {}} />
    </I18nextProvider>,
  );
}

/// THE WHOLE COLUMN, rendered to a string. THIS WORKS WITHOUT A DOM because nothing it
/// reaches at module scope touches one: the hook's effects (the snapshot read, the socket, the
/// observation) never run in `renderToStaticMarkup`, so what is drawn here is the view with no
/// answer yet -- which is exactly the state its empty sentences exist for.
function statsView(language: Language): string {
  return renderToStaticMarkup(
    <I18nextProvider i18n={renderI18n(language)}>
      <StatsView onClose={() => {}} />
    </I18nextProvider>,
  );
}

const cases: Case[] = [
  {
    name: "the-task-pane-is-two-sections-and-the-mirrors-own-column",
    run: async () => {
      // IT IS THE SAME COLUMN AS THE MIRROR, and that is asserted as a string rather than trusted
      // to a copy-paste: the two states of this pane are one element (spec decision 1), so a width
      // or a breakpoint edited on one side and not the other is exactly the drift worth a red run.
      // The classes are read out of `subagent-view.tsx`, which is the box the mirror keeps.
      const mirrorColumn = /data-slot="subagent-view"[\s\S]*?className="([^"]*)"/.exec(panelSource);
      expect(mirrorColumn, "no column classes in subagent-view.tsx").not.toBeNull();
      expect(taskPaneSource).toContain(`className="${mirrorColumn![1]!}"`);
      // ...and the element carries the `id` the two controls name (the mirror's side of that is
      // read by `suites/subagent-view.tsx`).
      expect(taskPaneSource).toContain("id={RIGHT_PANE_ID}");

      // AND THAT ONE CLASS STRING DRAWS TWO SHAPES, both read off it as text because this run has
      // no stylesheet: OVER the conversation below `md` (absolute, from the trailing edge, above
      // the page's backdrop), and a fixed-width sibling from `md` up. A `hidden` left in it, or an
      // `absolute` with no `md:static` to undo it, would cost the page either the phone or the desk
      // -- and the browser walkthrough below measures which one it got.
      expect(mirrorColumn![1]).toContain("absolute");
      expect(mirrorColumn![1]).toContain("md:static");
      expect(mirrorColumn![1]).not.toContain("hidden");

      // TWO SECTIONS, SUBAGENTS ABOVE JOBS: which one is first is a decision of the spec's, and a
      // source read is where it lives. Each keeps its own half of the column.
      const subagents = /data-slot="task-pane-subagents"[\s\S]*?<\/section>/.exec(taskPaneSource);
      const jobs = /data-slot="task-pane-jobs"[\s\S]*?<\/section>/.exec(taskPaneSource);
      expect(subagents, "no subagents section in task-pane.tsx").not.toBeNull();
      expect(jobs, "no jobs section in task-pane.tsx").not.toBeNull();
      expect(taskPaneSource.indexOf('data-slot="task-pane-subagents"')).toBeLessThan(
        taskPaneSource.indexOf('data-slot="task-pane-jobs"'),
      );
      // ...AND EACH ONE HOLDS ITS OWN TWO SLOTS, which is what the capture above is for: a heading
      // written outside both sections, or inside the other one, is a row of the wrong half.
      expect(subagents![0]).toContain('data-slot="task-pane-subagents-title"');
      expect(subagents![0]).toContain('data-slot="task-pane-subagents-empty"');
      expect(jobs![0]).toContain('data-slot="task-pane-jobs-title"');
      expect(jobs![0]).toContain('data-slot="task-pane-jobs-empty"');

      // AND EACH SECTION SAYS WHICH ONE IT IS, in both languages, as a RENDERED string: the words
      // are the whole of what tells these two apart, and a heading swapped between the sections
      // would be a green tree and a wrong page (the lesson `suites/sidebar.tsx` was built for).
      expect(textOf(taskPane("en"), "task-pane-subagents-title")).toBe("Subagents");
      expect(textOf(taskPane("en"), "task-pane-jobs-title")).toBe("Background jobs");
      expect(textOf(taskPane("zh"), "task-pane-subagents-title")).toBe("子代理");
      expect(textOf(taskPane("zh"), "task-pane-jobs-title")).toBe("后台任务");

      // EACH SECTION'S OWN EMPTY SENTENCE, and it is the ROW of this section that has to say it:
      // empty in one of them is a heading over nothing, and the same sentence in both means one of
      // them lost its own. What the copy says is the `i18n` suite's business (both catalogs, same
      // keys, nothing empty); what is this file's is that the two sentences are two.
      const emptyEn = textOf(taskPane("en"), "task-pane-subagents-empty");
      const emptyEnJobs = textOf(taskPane("en"), "task-pane-jobs-empty");
      const emptyZh = textOf(taskPane("zh"), "task-pane-subagents-empty");
      const emptyZhJobs = textOf(taskPane("zh"), "task-pane-jobs-empty");
      for (const sentence of [emptyEn, emptyEnJobs, emptyZh, emptyZhJobs]) {
        expect(sentence.trim().length).toBeGreaterThan(0);
      }
      expect(emptyEn).not.toBe(emptyEnJobs);
      expect(emptyZh).not.toBe(emptyZhJobs);

      // THE BOUNDARY MOVED, AND TICKET 02 IS THE TICKET THAT MOVED IT -- exactly as ticket 01
      // said this block was for. That boundary was "the pane asks nobody for anything, because
      // the two lists are tickets 02/03"; the BOTTOM section is ticket 02, so it reads now. What
      // still holds, and is what this block pins instead, is HOW it reads: the pane fetches
      // nothing and owns no timer itself -- the read lives in ONE reader (`lib/jobs.ts`) and the
      // poll in ONE hook (`hooks/use-task-pane.ts`), so ticket 03's second read joins the SAME
      // tick rather than starting a second one.
      expect(taskPaneSource).not.toContain("fetch(");
      expect(taskPaneSource).not.toContain("setInterval");
      expect(taskPaneSource).toContain("useTaskPane(");
      expect(taskPaneSource).toContain("<JobRows");
      // THE READ ITSELF: one URL in one place, and the status vocabulary that is the record's
      // (the row asks `isRunning`, and never invents a word for an ending).
      expect(jobsLibSource).toContain("threads/${encodeURIComponent(threadId)}/jobs");
      expect(jobsLibSource).toContain("RUNNING_STATUS");
      expect(jobRowsSource).toContain("isRunning(job)");
      // THE POLL IS GONE AND A SUBSCRIPTION TOOK ITS PLACE (ticket 01 of
      // `.scratch/task-pane-push`, and the rule in `docs/rules/panel-data.md`): the two routes
      // are read ONCE (a snapshot) and every change after that arrives as a `task` frame on the
      // session's socket. The pin that used to be here asserted the poll by name, which is what
      // makes this reversal readable rather than silent.
      expect(taskPaneHookSource).not.toContain("setInterval(read");
      expect(taskPaneHookSource).toContain("subscribeTasks(threadId");
      expect(taskPaneHookSource).toContain("void jobsFor(threadId, flight.signal)");
      expect(taskPaneHookSource).toContain("unsubscribe?.()");

      // THE ONE TIMER THAT SURVIVES IS THE LOCAL TICK, and it is the single exception the rule
      // names: a duration that keeps moving cannot come from the server, so the client advances
      // it -- and asks NOTHING for it, and STOPS when nothing is running.
      expect(taskPaneHookSource).toContain("export const TASK_PANE_TICK_MS = 1000");
      expect(taskPaneHookSource).toContain("setInterval(() => setNow(Date.now()), TASK_PANE_TICK_MS)");
      expect(taskPaneHookSource).toContain("if (!running) return;");
      expect(taskPaneHookSource).toContain("clearInterval(timer)");

      // THE TIMES ON A ROW (owner, 2026-09-27): 开始时间 always, 持续时间 always -- and the
      // duration's two arms are the two clocks (`now` while it runs, `endedAt` once it is over).
      expect(jobRowsSource).toContain("rightPane.jobStarted");
      expect(jobRowsSource).toContain("running ? \"rightPane.jobElapsed\" : \"rightPane.jobTook\"");
      expect(jobRowsSource).toContain("(running ? now : (job.endedAt ?? now)) - job.startedAt");
      expect(subagentRowsSource).toContain("rightPane.subagentTook");
      expect(subagentRowsSource).toContain("rightPane.subagentElapsed");
      expect(subagentRowsSource).toContain("row.running ? now : row.finishedAt!");
      expect(taskPaneHookSource).toContain('document.addEventListener("visibilitychange"');
      expect(taskPaneHookSource).toContain("AbortController");
      expect(taskPaneHookSource).toContain("stop();");
      // THE THIRD WAY, ASKED OF THE LAYOUT RATHER THAN OF A BREAKPOINT: an observation says
      // whether the element has boxes at all, so the hook stops when the column is not drawn --
      // and the pane hands its own element in, because that is the thing being asked about.
      expect(taskPaneHookSource).toContain("IntersectionObserver");
      expect(taskPaneHookSource).toContain("observer.disconnect()");
      expect(taskPaneSource).toContain("useTaskPane(threadId, pane)");
      expect(taskPaneSource).toContain("useRef<HTMLElement | null>(null)");
    },
  },
  {
    name: "both-ends-of-the-right-columns-fold-name-the-same-region",
    run: async () => {
      // ONE CONTRACT, TWO PLACES: a screen reader arriving at either control is told WHICH region
      // it folds, so both name the same id -- the column itself, in whichever of its two states it
      // happens to be. This is the assertion `suites/sidebar.tsx` makes for the left-hand pair,
      // made for the right-hand one.
      expect(attrOf(openControl("en"), "right-pane-open", "aria-controls")).toBe(RIGHT_PANE_ID);
      expect(attrOf(collapseControl("en"), "right-pane-collapse", "aria-controls")).toBe(RIGHT_PANE_ID);

      // AND EACH REPORTS THE STATE OF THE REGION IT NAMES (`aria-expanded` is that reading, not a
      // statement of what pressing the button would do): the control that exists only while the
      // column is closed names a collapsed region and says `false`; the one in the column's own
      // header names the expanded one and says `true`. Swapped, a screen reader announces the
      // opposite of what is on screen.
      expect(attrOf(openControl("en"), "right-pane-open", "aria-expanded")).toBe("false");
      expect(attrOf(collapseControl("en"), "right-pane-collapse", "aria-expanded")).toBe("true");

      // THE WORDS, IN BOTH LANGUAGES -- and they are the WHOLE of what these icon buttons say to
      // somebody who cannot see the glyph, the same hole `suites/sidebar.tsx` pins for the other
      // pair. A missing one leaves a button in a corner with no name.
      expect(textOf(openControl("en"), "right-pane-open")).toBe("Open task pane");
      expect(textOf(openControl("zh"), "right-pane-open")).toBe("打开任务视图");
      expect(textOf(collapseControl("en"), "right-pane-collapse")).toBe("Collapse task pane");
      expect(textOf(collapseControl("zh"), "right-pane-collapse")).toBe("收起任务视图");

      // WHICH GLYPH, and it is asserted from the SOURCE because a rendered `lucide` svg carries no
      // name: a control that says "open" while drawing the close icon is the easiest lie in this
      // shell to read. These are the two icons the sidebar's pair uses, on the other side.
      expect(toggleSource).toContain("<PanelRightIcon");
      expect(toggleSource).toContain("<PanelRightCloseIcon");
      expect(toggleSource).not.toContain("PanelLeftIcon");
      expect(toggleSource).not.toContain("PanelLeftCloseIcon");
    },
  },
  {
    name: "one-value-three-shapes-and-the-open-control-only-while-it-is-closed",
    run: async () => {
      // THE STATE IS ONE VALUE WITH THREE SHAPES, and the type is the one place the three are
      // named together (`components/subagent-view-context.ts`). An `open` bit beside a `which`
      // would let this page say "open and showing nothing", and the column would have to guess.
      expect(contextSource).toMatch(
        /export type RightPane =\s*\| null\s*\| \{ kind: "tasks" \}\s*\| \{ kind: "stats" \}\s*\| \(\{ kind: "mirror" \} & SubagentView\);/,
      );
      expect(appSource).toContain("const [rightPane, setRightPane] = useState<RightPane>(null)");

      // WHO WRITES WHICH SHAPE, AND THROUGH WHICH DOOR: the switch opens the TASK VIEW, the
      // `agent` card opens the MIRROR (through the context value every tool card reads), and
      // both ways out write `null`. THE TWO OPENERS GO THROUGH ONE WRITER (`openPane`) because
      // that writer also owns the drawer rule below: a door that wrote the state directly would
      // be a door that could stack this panel over the sidebar's on a phone.
      expect(appSource).toContain('openPane({ kind: "tasks" })');
      expect(appSource).toContain('openPane({ kind: "mirror", ...view })');
      expect(appSource).toContain("setRightPane(null)");

      // AND THE COLUMN IS DRAWN FROM THAT VALUE: the mirror's panel keeps its `key` -- one
      // delegation at a time, at the connection level -- and the task pane is the other arm.
      expect(appSource).toContain("rightPane.kind === \"mirror\"");
      expect(appSource).toContain("rightPane.kind === \"tasks\"");
      // THE THREAD ID IS THE ONE ON SCREEN (`roster.shown`) -- the pane polls THAT session,
      // so a pane showing one session never draws another's jobs or delegations. It is also
      // handed the page's DOOR (`onOpen`), the same writer the transcript's `agent` card
      // uses, so a row and a card open one state rather than two that could drift.
      //
      // NORMALIZED FIRST: this run reads a SOURCE, and a Windows checkout (`core.autocrlf`)
      // hands a multi-line `?raw` import CRLF -- so a `\n` search finds nothing and the red
      // run would be about the checkout rather than about the page.
      const app = appSource.replace(/\r\n/g, "\n");
      expect(app).toContain(
        "<TaskPane\n            threadId={roster.shown}\n            onCollapse={() => setRightPane(null)}\n            onOpen={openMirror}\n            // THE TRAILING `…`'s DOOR, through the same writer as the two above: the statistics\n            // are the third state of this column, not a panel stacked over it\n            // (`.scratch/global-stats-panel/`).\n            onStats={() => openPane({ kind: \"stats\" })}\n          />",
      );

      // THE OPEN CONTROL IS DRAWN ONLY WHILE THE COLUMN IS CLOSED, and it is drawn by the PAGE:
      // that is the whole reason the state lives in `app.tsx` rather than in the column -- a closed
      // column cannot draw the control that opens it (see `components/right-pane-toggle.tsx`).
      expect(appSource).toContain("{rightPane === null && <RightPaneOpenButton");

      // THE CORNER CONTROL IS DRAWN AT EVERY WIDTH, which is what this block used to refuse: the
      // column is no longer `display: none` below `md` -- it is the DRAWER there (the mirror's own
      // `absolute ... md:static`) -- so this button opens something on a phone too. The classes
      // are read back off the RENDERED control, through the same merge the component uses -- and
      // `inline-flex` being gone is the point: two display utilities left in one class string
      // would leave the winner to the stylesheet's order.
      const classes = attrOf(openControl("en"), "right-pane-open", "class").split(/\s+/);
      expect(classes).toContain("flex");
      expect(classes).not.toContain("hidden");
      expect(classes).not.toContain("inline-flex");

      // THE PHONE'S WAY OUT OF THE DRAWER, which is the page's box rather than the panel's, the
      // same arrangement the sidebar has: a backdrop drawn exactly where the column is a drawer
      // (`md:hidden` -- one breakpoint, two readers) that closes it when tapped. Read as SOURCE
      // because `app.tsx` cannot be rendered here (this file's header).
      const backdrop = /data-slot="right-pane-backdrop"[\s\S]*?className="([^"]*)"/.exec(appSource);
      expect(backdrop, "no backdrop for the right column in app.tsx").not.toBeNull();
      expect(backdrop![1]!.split(/\s+/)).toContain("md:hidden");
      expect(appSource).toContain('onClick={() => setRightPane(null)}');

      // AND ONLY ONE DRAWER AT A TIME WHERE BOTH REALLY ARE DRAWERS: `rightPaneIsDrawer` reads
      // the width AT THE MOMENT of the decision (its own comment argues that), and the two doors
      // close the other panel through it -- so a phone cannot end up with two panels and two
      // backdrops stacked. From `md` up it answers `false` and neither door touches the other.
      expect(appSource).toContain("if (rightPaneIsDrawer()) setFolded(true)");
      expect(appSource).toContain("if (rightPaneIsDrawer()) setRightPane(null)");
      expect(toggleSource).toContain("export function rightPaneIsDrawer()");
      expect(toggleSource).toContain('const WIDE_ENOUGH = "(min-width: 48rem)"');
    },
  },
  {
    name: "a-job-row-says-which-command-and-how-it-is-going",
    run: async () => {
      // WHAT A RENDER CAN SEE, and it is the whole point of ticket 02's bottom section. A row is
      // a pure function of what the server sent (`lib/jobs.ts`), so it can be drawn to a string
      // in both languages -- and the two claims this store exists for are exactly the two a green
      // tree would not notice: a RUNNING row grows a clock and a FINISHED one does not, and the
      // status is the record's OWN line rather than anything this side decided to call it.
      const finished: JobRow = {
        id: "j1",
        command: "npm test --\n  --watch=false",
        status: "[exit 0]",
        startedAt: 1_000,
        // THE ENDING CLOCK (ticket 01 of `.scratch/task-pane-push`): a finished row draws a
        // duration from these two instants.
        endedAt: 61_000,
        path: "C:\\home\\jobs\\t1\\j1-run.log",
      };
      const running: JobRow = {
        id: "j2",
        command: "npm run dev",
        status: "[running]",
        startedAt: 1_000,
        // STILL GOING: no ending yet, which is the nil a running row's duration is measured
        // against the LOCAL tick instead.
        endedAt: null,
        path: "C:\\home\\jobs\\t1\\j2-run.log",
      };
      // `now` IS AN ARGUMENT, exactly as `lib/relative-time.ts` takes one: a duration is a
      // function of two instants, and a suite that had to wait for a clock would be about time.
      const rows = (jobs: readonly JobRow[], language: Language): string =>
        renderToStaticMarkup(
          <I18nextProvider i18n={renderI18n(language)}>
            <JobRows jobs={jobs} threadId="t1" now={4_200} />
          </I18nextProvider>,
        );

      const done = rows([finished], "en");
      // THE ENDING IS THE RECORD'S WORD, NOT A TRANSLATION OF IT: a row that said "succeeded"
      // here would be a second vocabulary over the one `job_output` hands the model.
      expect(textOf(done, "task-pane-job-status")).toBe("[exit 0]");
      // A FINISHED JOB NOW CARRIES BOTH CLOCKS (owner, 2026-09-27: 已完成的要显示开始时间、持续时间) --
      // the start it began with, and the duration it took, measured from the ending the SERVER
      // stamped when the record closed. The pin that used to be here asserted the opposite ("a
      // finished job has no clock"), so this is the reversal, readable.
      // 61 000 - 1 000 = 60 000 ms -> `1m 0s`, and 4 200 is irrelevant to it: a duration that is
      // over is a fact, not a moving number.
      expect(textOf(done, "task-pane-job-duration")).toBe("took 1m 0s");
      expect(textOf(rows([finished], "zh"), "task-pane-job-duration")).toBe("耗时 1 分 0 秒");
      // THE START IS A BUCKET, not a formatted instant (`lib/relative-time.ts`'s ladder): 4 200
      // against a 1 000 start is under a minute, which the ladder draws as 刚刚.
      expect(textOf(done, "task-pane-job-started")).toBe("started just now");
      // THE COMMAND IS ONE LINE: written over two, drawn as one (the newline becomes a space).
      expect(textOf(done, "task-pane-job-command")).toBe("npm test -- --watch=false");
      // THE ID IS MONO AND THE COMMAND IS CLIPPED -- the two things that keep a long command
      // from deciding the column's width. That the column really does not grow is the
      // walkthrough's half, and this suite's header says so.
      expect(attrOf(done, "task-pane-job-id", "class")).toContain("font-mono");
      expect(attrOf(done, "task-pane-job-command", "class")).toContain("truncate");
      // THE RECORD'S PATH rides on the row, because this pane is a reader.
      expect(attrOf(done, "task-pane-job", "title")).toBe(finished.path);

      const live = rows([running], "en");
      expect(textOf(live, "task-pane-job-status")).toBe("[running]");
      // 4 200 - 1 000 = 3 200 ms -> `3.2s`, and the phrase around it is the catalog's, in both
      // languages: the number is `lib/format`'s one formatter, the words are the shells'.
      expect(textOf(live, "task-pane-job-duration")).toBe("3.2s so far");
      expect(textOf(rows([running], "zh"), "task-pane-job-duration")).toBe("已跑 3.2 秒");
    },
  },
  {
    name: "a-running-job-is-stopped-from-its-own-row",
    run: async () => {
      // TICKET 04'S CONTROL: the ■ on a row whose job is STILL RUNNING. What a render can
      // see is where it is drawn and what it says. The press itself, the tick that carries
      // `[stopped]` back, and the browser's real behaviour are source reads and the
      // walkthrough's half -- this run has no DOM (see this file's header).
      const running: JobRow = {
        id: "j2",
        command: "npm run dev",
        status: "[running]",
        startedAt: 1_000,
        // STILL GOING: no ending yet, which is the nil a running row's duration is measured
        // against the LOCAL tick instead.
        endedAt: null,
        path: "C:\\home\\jobs\\t1\\j2-run.log",
      };
      const finished: JobRow = { ...running, id: "j1", status: "[exit 0]" };
      const rows = (jobs: readonly JobRow[], language: Language): string =>
        renderToStaticMarkup(
          <I18nextProvider i18n={renderI18n(language)}>
            <JobRows jobs={jobs} threadId="t1" now={4_200} />
          </I18nextProvider>,
        );

      // A FINISHED JOB HAS NOTHING TO STOP, so the control is simply not drawn -- the
      // absence is the assertion, exactly as it is for the duration line above.
      const live = rows([running], "en");
      expect(live).toContain('data-slot="task-pane-job-stop"');
      expect(rows([finished], "en")).not.toContain('data-slot="task-pane-job-stop"');

      // AND IT SAYS WHAT IT DOES, IN BOTH LANGUAGES: an icon button whose whole meaning is
      // its accessible name, the hole `suites/sidebar.tsx` pins for the two toggles.
      expect(attrOf(live, "task-pane-job-stop", "aria-label")).toBe("Stop this job");
      expect(attrOf(rows([running], "zh"), "task-pane-job-stop", "aria-label")).toBe(
        "停掉这条后台任务",
      );

      // THE IN-FLIGHT STATE IS THE COMPOSER'S STOP'S (`session-run-stop.tsx`): the press is
      // a bit, the control is disabled while it is on, and it is let go when either the
      // answer or the next tick arrives. SSR draws it unpressed, so what is pinned here is
      // the wiring -- the bit, the disable, the glyph.
      const jobRows = jobRowsSource.replace(/\r\n/g, "\n");
      expect(jobRows).toContain("setPressing(true)");
      expect(jobRows).toContain("disabled={pressing}");
      expect(jobRows).toContain("<SquareIcon");

      // A REFUSAL IS DRAWN, NEVER SWALLOWED. The sentence is the SERVER's, read through the
      // `errors` face; what this side owes is the slot and the end of the press.
      expect(jobRows).toContain('data-slot="task-pane-job-stop-refusal"');
      expect(jobRows).toContain("setFailure(error instanceof Error ? error.message : String(error))");
      expect(jobRows).toContain("setPressing(false)");

      // THE ROW'S NEW STATE ARRIVES ON THE PANE'S EXISTING TICK, and that is the whole
      // reason this component applies no answer: it drops the press when the row stops
      // being a running one, which is one read later by construction (`TASK_PANE_POLL_MS`).
      // NO SECOND CLOCK, and no second reader of the list, is started for one press.
      expect(jobRows).toContain("if (!running) {");
      expect(jobRows).not.toContain("setInterval");
      expect(jobRows).not.toContain("jobsFor(");

      // THE CALL ITSELF: one URL, the verb, and the id in a JSON body the route reads the
      // way `archive-post` reads its own -- and a refusal worded rather than swallowed.
      expect(jobsLibSource).toContain("threads/${encodeURIComponent(threadId)}/jobs");
      expect(jobsLibSource).toContain('method: "POST"');
      expect(jobsLibSource).toContain("JSON.stringify({ job: jobId })");
      expect(jobsLibSource).toContain("refusalFrom(res, t)");

      // AND THE ROW KNOWS WHICH SESSION IT BELONGS TO: the pane hands down the ONE on
      // screen, so a press can never address another session's job.
      // THE ROWS ARE HANDED THE CLOCK THE HOOK OWNS (`now`), which is what a running row's
      // duration is measured against -- see `hooks/use-task-pane.ts` on why that number is the
      // client's to advance.
      expect(taskPaneSource).toContain("<JobRows jobs={jobs} threadId={threadId} now={now} />");
    },
  },
  {
    name: "the-subagent-section-is-this-sessions-runs-joined-to-their-definitions",
    run: async () => {
      // THE JOIN AND THE NARROWING, both pure (`lib/subagents-runs.ts`), so both are
      // assertable without a browser. The section answers "what did THIS session delegate",
      // so another session's run is not in it -- and the definition that carries the same
      // name is what supplies the description.
      const listing: SubagentListing = {
        subagents: [
          {
            name: "explore",
            description: "find every namespace",
            baseline: "read-only",
            exclude: [],
            builtin: true,
          },
          {
            name: "build",
            description: "change the tree",
            baseline: "all",
            exclude: [],
            builtin: false,
          },
        ],
        problem: null,
        path: "C:\\home\\harness.edn",
        runs: [
          { threadId: "s-new", parent: "t1", subagent: "build", project: null, delegatedAt: 2_000, finishedAt: null, running: true },
          { threadId: "s-gone", parent: "t1", subagent: "removed", project: null, delegatedAt: null, finishedAt: null, running: false },
          { threadId: "s-other", parent: "t2", subagent: "explore", project: null, delegatedAt: 3_000, finishedAt: null, running: true },
          { threadId: "s-old", parent: "t1", subagent: "explore", project: null, delegatedAt: 1_000, finishedAt: 9_000, running: false },
        ],
      };

      const rows = subagentRowsOf(listing, "t1");
      // THE SERVER'S ORDER IS THE ORDER: newest delegation first, and no second sort here.
      expect(rows.map((row) => row.threadId)).toEqual(["s-new", "s-gone", "s-old"]);
      // THE DEFINITION IS JOINED BY NAME...
      expect(rows[0]).toMatchObject({
        name: "build",
        description: "change the tree",
        running: true,
        delegatedAt: 2_000,
      });
      expect(rows[2]).toMatchObject({ name: "explore", description: "find every namespace", running: false });
      // ...AND A NAME NO DEFINITION CARRIES STILL DRAWS: the name, the status, no
      // description. The subagent was deleted after it was delegated to, which
      // `cap.subagents/runs` calls an ordinary record rather than an error to catch --
      // and `delegatedAt: null` is drawn as no line at all (the row case below).
      expect(rows[1]).toMatchObject({
        name: "removed",
        description: null,
        finishedAt: null,
        running: false,
        delegatedAt: null,
      });
      // A SESSION THAT DELEGATED NOTHING IS THE EMPTY ANSWER, not an error.
      expect(subagentRowsOf(listing, "nothing-here")).toEqual([]);

      // AND A ROW OPENS ITS OWN DELEGATION. Two of these rows name two different children
      // -- the concurrent case -- and pairing a row with a child BY POSITION is exactly
      // what this refuses.
      expect(mirrorOf(rows[0]!)).toEqual({ threadId: "s-new", subagent: "build" });
      expect(mirrorOf(rows[2]!)).toEqual({ threadId: "s-old", subagent: "explore" });

      // THE WIRING A RENDER CANNOT CLICK (this run has no DOM): the row hands
      // `mirrorOf(row)` -- its own -- and the pane hands the page's `openMirror`, the same
      // writer the transcript's `agent` card writes through.
      expect(taskPaneSubagentsSource).toContain("onClick={() => onOpen(mirrorOf(row))}");
      expect(taskPaneSource).toContain("<SubagentRows rows={subagents} onOpen={onOpen} now={now} />");
      expect(appSource).toContain("onOpen={openMirror}");
      // THE SECOND READ: one route, the same one `lib/subagents.ts` reads, sharing the
      // tick's ONE controller (`hooks/use-task-pane.ts`) rather than starting a timer.
      expect(subagentsRunsSource).toContain("${API_BASE}subagents");
      expect(subagentsRunsSource).toContain("run.parent === parent");
      expect(taskPaneHookSource).toContain("subagentsFor(threadId, flight.signal)");
    },
  },
  {
    name: "a-subagent-row-says-which-delegation-and-how-it-is-going",
    run: async () => {
      // A ROW IS A PURE FUNCTION OF THE SERVER'S ROW, so it draws to a string in both
      // languages -- and the two claims a green tree would miss are here: the status word
      // comes from `running` ALONE (there is no third state), and a null `delegatedAt` is
      // no line rather than a zero.
      const row = (r: SubagentTaskRow, language: Language, now = 181_000): string =>
        renderToStaticMarkup(
          <I18nextProvider i18n={renderI18n(language)}>
            <SubagentRows rows={[r]} onOpen={() => {}} now={now} />
          </I18nextProvider>,
        );

      const running: SubagentTaskRow = {
        threadId: "s1",
        name: "explore",
        description: "find every namespace",
        delegatedAt: 1_000,
        finishedAt: null,
        running: true,
      };
      const gone: SubagentTaskRow = {
        threadId: "s2",
        name: "removed",
        description: null,
        // AN EARLIER PROCESS'S DELEGATION: a start nobody recorded and an end nobody watched,
        // which is the row that draws no times at all rather than inventing them.
        delegatedAt: null,
        finishedAt: null,
        running: false,
      };

      // THE NAME AND THE DESCRIPTION, each in its own slot; the description is clipped by
      // CSS (`truncate`) and the whole of it rides on the slot's `title`.
      const live = row(running, "en");
      expect(textOf(live, "task-pane-subagent-name")).toBe("explore");
      expect(textOf(live, "task-pane-subagent-description")).toBe("find every namespace");
      expect(attrOf(live, "task-pane-subagent-description", "title")).toBe("find every namespace");
      expect(attrOf(live, "task-pane-subagent-description", "class")).toContain("truncate");

      // THE STATUS IS THE SERVER'S ONE BOOLEAN SAID IN THE CATALOG'S TWO WORDS, and no
      // third: the same row with `running` flipped is the other word, in both languages.
      expect(textOf(live, "task-pane-subagent-status")).toBe("Running");
      expect(textOf(row(running, "zh"), "task-pane-subagent-status")).toBe("运行中");
      const over = row(gone, "en");
      expect(textOf(over, "task-pane-subagent-status")).toBe("Finished");
      expect(textOf(row(gone, "zh"), "task-pane-subagent-status")).toBe("已结束");

      // WHEN IT STARTED: 181 000 - 1 000 = 3 minutes, as a bucket (`lib/relative-time.ts`)
      // with the shell's own words around it.
      expect(textOf(live, "task-pane-subagent-started")).toBe("started 3 min ago");
      expect(textOf(row(running, "zh"), "task-pane-subagent-started")).toBe("3 分钟前开始");
      // ...AND NO LINE AT ALL WHEN `delegatedAt` IS NULL -- the absence is the assertion.
      expect(over).not.toContain('data-slot="task-pane-subagent-started"');
      // A NAME WITH NO DEFINITION DRAWS NO DESCRIPTION EITHER.
      expect(over).not.toContain('data-slot="task-pane-subagent-description"');
      expect(textOf(over, "task-pane-subagent-name")).toBe("removed");

      // AND THE DURATION, which is the two clocks a row measures. A FINISHED delegation's is
      // FIXED (`finishedAt` - `delegatedAt`), and a row the wire carried no `finishedAt` for
      // draws NO duration at all rather than the `NaN 分 NaN 秒` the missing field used to
      // produce (owner, 2026-10-02).
      const settled: SubagentTaskRow = {
        threadId: "s3",
        name: "explore",
        description: "find every namespace",
        delegatedAt: 1_000,
        finishedAt: 61_000,
        running: false,
      };
      expect(textOf(row(settled, "en"), "task-pane-subagent-duration")).toBe("took 1m 0s");
      expect(textOf(row(settled, "zh"), "task-pane-subagent-duration")).toBe("耗时 1 分 0 秒");
      // NO END, NO DURATION -- a delegation an earlier process left behind has no clock to
      // draw from, and the honest drawing is none rather than a made-up one.
      expect(over).not.toContain('data-slot="task-pane-subagent-duration"');
    },
  },
  {
    name: "the-way-back-to-the-list-is-a-control-of-the-columns-own",
    run: async () => {
      // A THIRD CONTROL OF THE SAME COLUMN (ticket 03, at the trailing end the pair above
      // deliberately kept free): it names the region its siblings name, and it says in both
      // languages where it goes.
      expect(attrOf(backControl("en"), "right-pane-back", "aria-controls")).toBe(RIGHT_PANE_ID);
      expect(textOf(backControl("en"), "right-pane-back")).toBe("Back to the list");
      expect(textOf(backControl("zh"), "right-pane-back")).toBe("返回列表");
      // AND IT IS NOT A DISCLOSURE: it steps back inside a region that stays open, so it
      // makes no `aria-expanded` claim about the box it names. (The class string mentions
      // `aria-expanded` because it is a VARIANT rule -- so the claim is read off the
      // element's own attributes, not off its classes.)
      const back = /<button[^>]*data-slot="right-pane-back"[^>]*>/.exec(backControl("en"));
      expect(back, "no back control in the render").not.toBeNull();
      expect(back![0]).not.toContain("aria-expanded=");
      // WHICH GLYPH, read from the source because a rendered `lucide` svg carries no name:
      // a way back, not a fold.
      expect(toggleSource).toContain("<ArrowLeftIcon");
    },
  },
  {
    name: "the-statistics-are-the-columns-third-state-reached-from-the-trailing-ellipsis",
    run: async () => {
      // THE TRAILING CONTROL OF THE TASK VIEW'S HEADER (owner, 2026-10-01: 「增加...」). Its
      // whole meaning is a glyph, so what is asserted is what a reader cannot see: the name it
      // says, the region it names, and the glyph itself.
      expect(attrOf(statsControl("en"), "right-pane-stats", "aria-controls")).toBe(STATS_VIEW_ID);
      expect(textOf(statsControl("en"), "right-pane-stats")).toBe("Statistics");
      expect(textOf(statsControl("zh"), "right-pane-stats")).toBe("统计");
      expect(toggleSource).toContain("<EllipsisIcon");
      // THE WAY OUT OF THE PAGE IS ITS OWN CONTROL, and it names the SAME region the `…` names --
      // a full-area page introduced by one control and closed by a control pointing elsewhere
      // would be two claims about one box.
      expect(attrOf(statsCloseControl("en"), "stats-close", "aria-controls")).toBe(STATS_VIEW_ID);
      expect(textOf(statsCloseControl("en"), "stats-close")).toBe("Back to the conversation");
      expect(textOf(statsCloseControl("zh"), "stats-close")).toBe("回到对话");
      // IT IS A NAVIGATION, NOT A DISCLOSURE: it steps to another state of a region that
      // stays open, so it makes no `aria-expanded` claim (the mirror's back control argues the
      // same line).
      const control = /<button[^>]*data-slot="right-pane-stats"[^>]*>/.exec(statsControl("en"));
      expect(control, "no statistics control in the render").not.toBeNull();
      expect(control![0]).not.toContain("aria-expanded=");
      // AND THE TASK VIEW IS WHERE IT IS DRAWN.
      expect(taskPaneSource).toContain("<StatsOpenButton onOpen={onStats} />");

      // THE PAGE OWNS THE THIRD SHAPE, and it opens through the same writer as the other two
      // (`openPane`), so the drawer rule below `md` covers this state too.
      const app = appSource.replace(/\r\n/g, "\n");
      expect(app).toContain('rightPane.kind === "stats"');
      expect(app).toContain('onStats={() => openPane({ kind: "stats" })}');
      expect(app).toContain("<StatsView");

      // THE TWO HALVES OF THE DATA (`docs/rules/panel-data.md`): one snapshot read of
      // `/api/stats`, and a downlink of its OWN -- not the sidebar's listing, because the
      // leaderboards are a scan over every tool call this home has made and a page with no
      // statistics open must not be made to pay for one. AND NOTHING POLLS: the hook owns no
      // timer at all, which is what the rule asks of a panel whose every fact can be pushed.
      expect(homeStatsSource).toContain("api/");
      expect(homeStatsSource).toContain('downlinkUrl("events.stats"');
      expect(homeStatsSource).toContain("subscribeHomeStats");
      expect(useHomeStatsSource).toContain("IntersectionObserver");
      expect(useHomeStatsSource).not.toContain("setInterval");
    },
  },
  {
    name: "the-statistics-view-draws-two-rankings-and-folds-the-token-one-away",
    run: async () => {
      // IT IS A DRAWER OVER THE WHOLE PAGE (owner, 2026-10-01): `absolute inset-0` against the
      // page's own box, above every other layer the shell draws, and NOT a sibling in the flex
      // row. Read as SOURCE -- this run has no stylesheet, and a class string is where that
      // decision lives.
      expect(statsViewSource).toContain("id={STATS_VIEW_ID}");
      expect(statsViewSource).toContain("bg-background absolute inset-0 z-50");
      expect(statsViewSource).not.toContain("w-[26rem]");
      expect(statsViewSource).not.toContain("min-h-0 min-w-0 flex-1");
      const app = appSource.replace(/\r\n/g, "\n");
      // NOTHING IS HIDDEN BEHIND IT: a drawer slides OVER what is there, so the conversation keeps
      // its runtime, its run and its scroll position -- and the page needs no backdrop either,
      // because the cover leaves nothing to tap beside it.
      expect(app).not.toContain('rightPane?.kind === "stats" ? " hidden" : ""');
      expect(app).toContain('rightPane.kind !== "stats" && (');
      // AND ESCAPE CLOSES WHAT IS ON TOP: the statistics drawer answers FIRST and at every width,
      // because it is above the two panels whose being drawers depends on the window.
      expect(app).toContain('rightPane.kind === "stats") {');
      // NOTHING IS HANDED DOWN ABOUT ITS NEIGHBOURS: the floating "open the sidebar" control and
      // this header's leading button used to share the box at 8,8 (the walkthrough found it, and
      // `ps-12 lg:ps-3` cleared it) -- under a cover, that control is BEHIND the drawer instead.
      expect(app).not.toContain("sidebarFolded");
      // THE RENDERED HEADER, not the source: the file's own prose names `ps-12` while explaining
      // why it is gone, and a claim about a class belongs on the element that would wear it.
      expect(attrOf(statsView("en"), "stats-view-header", "class")).not.toContain("ps-12");

      // A MODAL DIALOG, AND BOTH HALVES ASSERTED TOGETHER because either one alone is a lie:
      // `aria-modal` claims everything outside is inert, and the trap is what makes that true.
      // The name is the heading ON SCREEN rather than a label of its own, and the container takes
      // focus itself (`tabindex="-1"`) for the case where there is nothing inside to focus.
      expect(attrOf(statsView("en"), "stats-view", "role")).toBe("dialog");
      expect(attrOf(statsView("en"), "stats-view", "aria-modal")).toBe("true");
      expect(attrOf(statsView("en"), "stats-view", "aria-labelledby")).toBe(STATS_TITLE_ID);
      expect(attrOf(statsView("en"), "stats-view-name", "id")).toBe(STATS_TITLE_ID);
      expect(attrOf(statsView("en"), "stats-view", "tabindex")).toBe("-1");
      expect(statsViewSource).toContain("useFocusTrap(pane)");
      // AND THE TRAP'S OWN RULES, as source: what it does with Tab needs a DOM, and that half is
      // the walkthrough's (a real browser pressing the key) -- what a string can see is that the
      // three promises are written down at all.
      expect(focusTrapSource).toContain("event.shiftKey");
      expect(focusTrapSource).toContain("previous.focus()");
      expect(focusTrapSource).toContain("!root.contains(here)");

      // WHAT IT SAYS, IN BOTH LANGUAGES: the words are the whole of what tells these three
      // rankings apart, and a heading swapped between them would be a green tree and a wrong
      // column (the lesson `suites/sidebar.tsx` was built for).
      const en = statsView("en");
      expect(textOf(en, "stats-view-name")).toBe("Statistics");
      expect(textOf(en, "stats-tools-title")).toBe("Tool calls");
      expect(textOf(en, "stats-skills-title")).toBe("Skill calls");
      expect(textOf(en, "stats-models-title")).toBe("Tokens by model");
      expect(textOf(en, "stats-models-toggle")).toBe("Show the token usage");
      const zh = statsView("zh");
      expect(textOf(zh, "stats-view-name")).toBe("统计");
      expect(textOf(zh, "stats-tools-title")).toBe("工具调用排行");
      expect(textOf(zh, "stats-skills-title")).toBe("Skill 调用排行");
      expect(textOf(zh, "stats-models-title")).toBe("各模型 token 用量");
      expect(textOf(zh, "stats-models-toggle")).toBe("展开 token 用量");

      // THE WINDOW: three buttons, and 7 is the one that is on -- `aria-pressed` is how a reader
      // is told which question is being asked, and the words are the catalog's in both languages.
      expect(attrOf(en, "stats-range-7", "aria-pressed")).toBe("true");
      expect(attrOf(en, "stats-range-30", "aria-pressed")).toBe("false");
      expect(attrOf(en, "stats-range-90", "aria-pressed")).toBe("false");
      expect(textOf(en, "stats-range-7")).toBe("7 days");
      expect(textOf(en, "stats-range-90")).toBe("90 days");
      expect(textOf(zh, "stats-range-30")).toBe("30 天");

      // AND THE ONE VERB ON THE PAGE (`重算`): making the projection again for the window on
      // screen. The word is rendered; the call and the window it carries are source reads.
      expect(textOf(en, "stats-rebuild")).toBe("Rebuild");
      expect(textOf(zh, "stats-rebuild")).toBe("重算");
      expect(statsViewSource).toContain("rebuildStatsWindow(days)");
      expect(homeStatsSource).toContain("stats/rebuild");
      // AND BOTH HALVES ARE ABOUT THE SAME WINDOW: the snapshot read and the socket's URL carry
      // `days`, so a heading and the numbers under it cannot disagree about the range.
      expect(homeStatsSource).toContain("days=${encodeURIComponent(String(days))}");
      expect(homeStatsSource).toContain("openDays !== days");

      // THE TWO RANKINGS ON SIGHT, EACH WITH ITS OWN EMPTY SENTENCE: an empty home draws a
      // sentence per section, and the same sentence in both means one of them lost its own.
      expect(textOf(en, "stats-tools-empty")).not.toBe(textOf(en, "stats-skills-empty"));
      for (const sentence of [
        textOf(en, "stats-tools-empty"),
        textOf(en, "stats-skills-empty"),
        textOf(zh, "stats-tools-empty"),
        textOf(zh, "stats-skills-empty"),
      ]) {
        expect(sentence.trim().length).toBeGreaterThan(0);
      }

      // AND THE TOKEN RANKING IS THE CLICK THE OWNER ASKED FOR: the disclosure starts closed,
      // so its rows are not drawn at all -- and `aria-expanded` is how a reader is told.
      expect(attrOf(en, "stats-models-toggle", "aria-expanded")).toBe("false");
      expect(en).not.toContain('data-slot="stats-models-rows"');
      expect(en).not.toContain('data-slot="stats-models-empty"');
    },
  },
];

export const rightPaneSuite: Suite = { name: "right-pane", cases };
