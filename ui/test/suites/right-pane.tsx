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
// beside the conversation, the two corners the controls sit in, nothing drawn below `md` -- is the
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
import taskPaneHookSource from "../../src/hooks/use-task-pane.ts?raw";
import jobsLibSource from "../../src/lib/jobs.ts?raw";
import taskPaneSubagentsSource from "../../src/components/task-pane-subagents.tsx?raw";
import subagentsRunsSource from "../../src/lib/subagents-runs.ts?raw";
import panelSource from "../../src/components/subagent-view.tsx?raw";
import contextSource from "../../src/components/subagent-view-context.ts?raw";
import appSource from "../../src/app.tsx?raw";
import type { Language } from "../../src/lib/language";

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
      <TaskPane threadId="t1" onCollapse={() => {}} onOpen={() => {}} />
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
      // THE TICK: a named cadence, ONE interval, and the two ways it stops -- the pane unmounting
      // and the page going hidden -- each of which also aborts a read in flight. A SOURCE READ
      // CAN PIN THE DECISIONS, NOT THE BEHAVIOUR: that a closed pane and a hidden page really
      // leave nothing in flight is the browser walkthrough's half (this suite's header).
      expect(taskPaneHookSource).toContain("export const TASK_PANE_POLL_MS = 1000");
      expect(taskPaneHookSource).toContain("setInterval(read, TASK_PANE_POLL_MS)");
      expect(taskPaneHookSource).toContain("clearInterval(timer)");
      expect(taskPaneHookSource).toContain('document.addEventListener("visibilitychange"');
      expect(taskPaneHookSource).toContain("AbortController");
      expect(taskPaneHookSource).toContain("stop();");
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
        /export type RightPane =\s*\| null\s*\| \{ kind: "tasks" \}\s*\| \(\{ kind: "mirror" \} & SubagentView\);/,
      );
      expect(appSource).toContain("const [rightPane, setRightPane] = useState<RightPane>(null)");

      // WHO WRITES WHICH SHAPE: the switch opens the TASK VIEW, the `agent` card opens the MIRROR
      // (through the context value every tool card reads), and both ways out write `null`.
      expect(appSource).toContain('setRightPane({ kind: "tasks" })');
      expect(appSource).toContain('setRightPane({ kind: "mirror", ...view })');
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
        "<TaskPane\n            threadId={roster.shown}\n            onCollapse={() => setRightPane(null)}\n            onOpen={openMirror}\n          />",
      );

      // THE OPEN CONTROL IS DRAWN ONLY WHILE THE COLUMN IS CLOSED, and it is drawn by the PAGE:
      // that is the whole reason the state lives in `app.tsx` rather than in the column -- a closed
      // column cannot draw the control that opens it (see `components/right-pane-toggle.tsx`).
      expect(appSource).toContain("{rightPane === null && <RightPaneOpenButton");

      // BELOW `md` NEITHER IS DRAWN: that is where the column stops existing (the mirror's own
      // `hidden ... md:flex`), so the corner control is hidden with it rather than offered as a
      // button that would do nothing. The classes are read back off the RENDERED control, through
      // the same merge the component uses -- and `inline-flex` being gone is the point: two display
      // utilities left in one class string would leave the winner to the stylesheet's order.
      const classes = attrOf(openControl("en"), "right-pane-open", "class").split(/\s+/);
      expect(classes).toContain("hidden");
      expect(classes).toContain("md:flex");
      expect(classes).not.toContain("inline-flex");
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
        path: "C:\\home\\jobs\\t1\\j1-run.log",
      };
      const running: JobRow = {
        id: "j2",
        command: "npm run dev",
        status: "[running]",
        startedAt: 1_000,
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
      // A FINISHED JOB HAS NO CLOCK, and the ABSENCE is the assertion -- the slot simply is not
      // in the markup.
      expect(done).not.toContain('data-slot="task-pane-job-duration"');
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
      expect(taskPaneSource).toContain("<JobRows jobs={jobs} threadId={threadId} />");
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
          { threadId: "s-new", parent: "t1", subagent: "build", project: null, delegatedAt: 2_000, running: true },
          { threadId: "s-gone", parent: "t1", subagent: "removed", project: null, delegatedAt: null, running: false },
          { threadId: "s-other", parent: "t2", subagent: "explore", project: null, delegatedAt: 3_000, running: true },
          { threadId: "s-old", parent: "t1", subagent: "explore", project: null, delegatedAt: 1_000, running: false },
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
      expect(taskPaneSource).toContain("<SubagentRows rows={subagents} onOpen={onOpen} />");
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
        running: true,
      };
      const gone: SubagentTaskRow = {
        threadId: "s2",
        name: "removed",
        description: null,
        delegatedAt: null,
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
];

export const rightPaneSuite: Suite = { name: "right-pane", cases };
