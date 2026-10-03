// The strip above the composer: the session's task list, folded to a line of counts and a
// click away from the detail (`.scratch/composer-todo-strip`, tickets 02 and 03).
//
// ================================================================ what is assertable here
//
// THE WORDS AND THE SHAPES ARE RENDERED. `ComposerTodosView` and `TodoRows` go through
// `react-dom/server` to a string, in a real i18n instance built from the real catalogs
// (`test/support/locale.ts`), and the string is read back -- the idiom `suites/sidebar.tsx`
// introduced for exactly this reason: a component that renders nothing can pass every
// suite that only talks to the backend, and it did once (2026-09-18).
//
// WHAT THIS RUN CANNOT SEE, and what is therefore read as SOURCE. There is no DOM, so no
// click happens here: the fold is radix's `Collapsible` (keyboard and `aria-expanded`
// come with it), the two trigger/content elements are read off `composer-todos.tsx?raw`,
// and the interaction itself is the browser walkthrough's half (`node scripts/dev.mjs
// --scripted`, ticket 03). The same reader pins the two other facts a string cannot carry:
// WHEN the strip asks (the fact family and the two names, no timer anywhere) and WHERE it
// sits (above the composer's input, source order in `composer-chrome.tsx`).
import { renderToStaticMarkup } from "react-dom/server";
import { I18nextProvider } from "react-i18next";
import { expect } from "vitest";

import { type Case, type Suite } from "../e2e";
import { renderI18n } from "../support/locale";
import { ComposerTodosView, TodoRows } from "../../src/components/composer-todos";
import type { TodoItem } from "../../src/lib/todos";
import type { Language } from "../../src/lib/language";
import composerTodosSource from "../../src/components/composer-todos.tsx?raw";
import composerChromeSource from "../../src/components/composer-chrome.tsx?raw";

/// THE TEXT OF ONE SLOT, the two readers `suites/sidebar.tsx` defines and this file
/// restates for the same reason: "the element is found by its `data-slot` and its children
/// are read" is a claim about an element, not "the string is somewhere in the markup".
function textOf(html: string, slot: string): string {
  const match = new RegExp(`<([a-z][a-z0-9]*)[^>]*data-slot="${slot}"[^>]*>([\\s\\S]*?)</\\1>`).exec(html);
  if (match === null) throw new Error(`no [data-slot="${slot}"] in the render: ${html}`);
  return match[2]!.replace(/<[^>]*>/g, "");
}

function allTextOf(html: string, slot: string): string[] {
  const re = new RegExp(`<([a-z][a-z0-9]*)[^>]*data-slot="${slot}"[^>]*>([\\s\\S]*?)</\\1>`, "g");
  return [...html.matchAll(re)].map((match) => match[2]!.replace(/<[^>]*>/g, ""));
}

function attrOf(html: string, slot: string, name: string): string {
  const element = new RegExp(`<([a-z]+)[^>]*data-slot="${slot}"[^>]*>`).exec(html);
  if (element === null) throw new Error(`no [data-slot="${slot}"] in the render: ${html}`);
  const attribute = new RegExp(`\\b${name}="([^"]*)"`).exec(element[0]);
  if (attribute === null) throw new Error(`[data-slot="${slot}"] carries no ${name}: ${element[0]}`);
  return attribute[1]!;
}

/// THE SAME MARKUP WITH THE `sr-only` WORDS TAKEN OUT -- what a person can actually see,
/// which is what "the detail shows no status word" is about. The words stay in the tree;
/// see the case that reads them on their own.
function visible(html: string): string {
  return html.replace(/<span class="sr-only">[\s\S]*?<\/span>/g, "");
}

/// The folded strip and the bare detail, each in a real instance from the real catalogs.
function strip(todos: readonly TodoItem[] | null, language: Language): string {
  return renderToStaticMarkup(
    <I18nextProvider i18n={renderI18n(language)}>
      <ComposerTodosView todos={todos} />
    </I18nextProvider>,
  );
}

function detail(todos: readonly TodoItem[], language: Language): string {
  return renderToStaticMarkup(
    <I18nextProvider i18n={renderI18n(language)}>
      <TodoRows todos={todos} />
    </I18nextProvider>,
  );
}

/// The strip WITH ITS TWO HANDS WIRED (`.scratch/todo-reminder`): the reminder half, which a
/// bare `todos` render (`strip` above) deliberately leaves out -- the view's hand props are
/// optional, and a hand nobody wired is a hand not drawn.
function hands(todos: readonly TodoItem[] | null, auto: boolean, language: Language): string {
  return renderToStaticMarkup(
    <I18nextProvider i18n={renderI18n(language)}>
      <ComposerTodosView todos={todos} auto={auto} onRemind={() => {}} onToggleAuto={() => {}} />
    </I18nextProvider>,
  );
}

/// The five-item list the reference screenshot reads: three done, one in progress, one
/// pending.
const MIXED: readonly TodoItem[] = [
  { content: "读一遍 ComposerFrame", status: "completed" },
  { content: "画那条统计条", status: "completed" },
  { content: "写走查脚本", status: "completed" },
  { content: "写横条", status: "in_progress" },
  { content: "走查一遍", status: "pending" },
];

const cases: readonly Case[] = [
  {
    name: "the-folded-line-counts-each-state-in-the-reference-order",
    run: async () => {
      // 已完成 · 进行中 · 待处理 IS THE ORDER, not the order the items were written in --
      // and the words follow the interface language while the detail below does not.
      expect(textOf(strip(MIXED, "en"), "composer-todos-summary")).toBe("3 done · 1 in progress · 1 pending");
      expect(textOf(strip(MIXED, "zh"), "composer-todos-summary")).toBe("3 已完成 · 1 进行中 · 1 待处理");
    },
  },
  {
    name: "a-state-with-nothing-in-it-is-not-named",
    run: async () => {
      // NOT "0 done · 2 pending": a zero is furniture for something that is not there.
      const pendingOnly: readonly TodoItem[] = [
        { content: "a", status: "pending" },
        { content: "b", status: "pending" },
      ];
      const en = strip(pendingOnly, "en");
      expect(textOf(en, "composer-todos-summary")).toBe("2 pending");
      expect(textOf(strip(pendingOnly, "zh"), "composer-todos-summary")).toBe("2 待处理");
      expect(en).not.toContain("done");
      expect(en).not.toContain("in progress");
    },
  },
  {
    name: "an-empty-list-and-no-answer-yet-draw-nothing",
    run: async () => {
      // `null` IS "THE FIRST ANSWER HAS NOT COME BACK" and `[]` is "the model wrote an
      // empty list": the strip draws nothing for either, because there is nothing to say.
      expect(strip([], "en")).toBe("");
      expect(strip(null, "en")).toBe("");
      expect(strip([], "zh")).toBe("");
    },
  },
  {
    name: "the-trigger-is-folded-by-default-and-the-fold-is-radixs",
    run: async () => {
      // ONE BUTTON, FOLDED, and it controls the detail: `aria-expanded` and the keyboard
      // are the primitive's, which is why the source pin below matters -- a hand-rolled
      // `div` with an `onClick` would pass a screenshot and fail a screen reader.
      const html = strip(MIXED, "en");
      expect(/<button[^>]*data-slot="composer-todos-toggle"/.test(html)).toBe(true);
      expect(attrOf(html, "composer-todos-toggle", "aria-expanded")).toBe("false");
      // FOLDED MEANS NO ROW IS DRAWN. Radix keeps a hidden, EMPTY box for the content so
      // it can animate open (the element is in the string, `hidden`), but it renders the
      // children only when open -- so the absence worth asserting is the ROWS'.
      expect(html).not.toContain('data-slot="composer-todos-item"');
      expect(composerTodosSource).toContain("<CollapsibleTrigger");
      expect(composerTodosSource).toContain("<CollapsibleContent");
    },
  },
  {
    name: "the-detail-is-one-icon-one-line-and-no-status-word",
    run: async () => {
      const sample: readonly TodoItem[] = [
        { content: "读一遍 ComposerFrame", status: "in_progress" },
        { content: "写横条", status: "pending" },
        { content: "走查一遍", status: "completed" },
      ];
      const html = detail(sample, "zh");
      // ONE ROW PER ITEM, and the status rides on the row so a reader (a suite, a
      // walkthrough) can find it without knowing a class name.
      expect(html.match(/data-slot="composer-todos-item"/g)?.length).toBe(3);
      expect(html).toContain('data-status="in_progress"');
      expect(html).toContain('data-status="pending"');
      expect(html).toContain('data-status="completed"');
      // WHAT A PERSON SEES IS THE MODEL'S OWN WORDS -- in the order they were written,
      // untranslated -- and never a status word.
      expect(allTextOf(visible(html), "composer-todos-item")).toEqual([
        "读一遍 ComposerFrame",
        "写横条",
        "走查一遍",
      ]);
      // AND THE STATUS WORDS ARE STILL THERE, one per row, for the accessibility tree.
      expect([...html.matchAll(/<span class="sr-only">([^<]*)<\/span>/g)].map((m) => m[1])).toEqual([
        "进行中",
        "待处理",
        "已完成",
      ]);
    },
  },
  {
    name: "each-state-draws-its-own-shape",
    run: async () => {
      // THREE SHAPES, NOT THREE WORDS: a checked circle, a spinner, a hollow circle --
      // and only the spinner moves, with the reduced-motion escape hatch on it.
      const html = detail(
        [
          { content: "done", status: "completed" },
          { content: "running", status: "in_progress" },
          { content: "waiting", status: "pending" },
        ],
        "en",
      );
      const rows = html.split('data-slot="composer-todos-item"');
      expect(rows.length).toBe(4);
      expect(rows[1]).toContain("lucide-circle-check");
      expect(rows[2]).toContain("lucide-loader-circle");
      expect(rows[2]).toContain("animate-spin");
      expect(rows[2]).toContain("motion-reduce:animate-none");
      expect(rows[3]).toContain("lucide-circle");
      expect(rows[3]).not.toContain("lucide-circle-check");
    },
  },
  {
    name: "the-when-it-asks-is-a-snapshot-and-two-facts-never-a-timer",
    run: async () => {
      // TICKET 03, READ OFF THE SOURCE because the triggers are effects and this run has no
      // DOM to fire them in. `model/start` is the boundary just after `todo_write` (it runs
      // after a call ends and before the next call starts) and `turn/end` is the last ask
      // of a run; both arrive on the EXISTING fact family, so no new protocol.
      expect(composerTodosSource).toContain("subscribeFacts");
      expect(composerTodosSource).toContain('"model/start"');
      expect(composerTodosSource).toContain('"turn/end"');
      // NO TIMER ASKS THIS ROUTE, which is `docs/rules/panel-data.md`'s rule stated as an
      // assertion rather than a paragraph.
      expect(composerTodosSource).not.toContain("setInterval");
      // A SESSION CHANGE DROPS THE OLD ANSWER before the new one lands (the `live` flag and
      // the reset are the two halves). A stale list drawn on the next session is the bug
      // this pins.
      expect(composerTodosSource).toContain("setTodos(null)");
      expect(composerTodosSource).toContain("live");
      // AND THE DETAIL SCROLLS ITSELF rather than pushing the input down the page, with a
      // focus style on the trigger.
      expect(composerTodosSource).toContain("overflow-y-auto");
      expect(composerTodosSource).toContain("max-h-");
      expect(composerTodosSource).toContain("focus-visible:ring");
    },
  },
  {
    name: "the-strip-sits-above-the-input-in-the-composer-frame",
    run: async () => {
      // WHERE IT IS, as source order in the frame that wraps the composer: the strip is
      // mounted before the composer's own root (`{children}`). The `{children}` read is the
      // LAST one because the frame's early return for a null thread id says `{children}`
      // too, and that one comes first.
      const stripAt = composerChromeSource.indexOf("<ComposerTodos");
      const inputAt = composerChromeSource.lastIndexOf("{children}");
      expect(stripAt).toBeGreaterThan(-1);
      expect(inputAt).toBeGreaterThan(-1);
      expect(stripAt).toBeLessThan(inputAt);
    },
  },
  {
    name: "the-two-hands-are-a-nudge-and-a-switch",
    run: async () => {
      // THE REMINDER HALF (`harness.cap.todos`): one button that pushes the reminder now, and
      // one switch the round driver obeys. BOTH SAY WHAT THEY ARE -- and the switch carries its
      // state three times over (the icon, the on/off word, `aria-pressed`) because it is
      // PROCESS memory the page must not infer from the list.
      const off = hands(MIXED, false, "en");
      expect(textOf(off, "composer-todos-remind")).toContain("Nudge");
      expect(textOf(off, "composer-todos-auto")).toContain("Auto-remind · off");
      expect(attrOf(off, "composer-todos-auto", "data-on")).toBe("false");
      expect(attrOf(off, "composer-todos-auto", "aria-pressed")).toBe("false");
      expect(off).toContain("lucide-bell-off");
      // ICON ONLY ON A PHONE (`.scratch/todo-strip-mobile`): two labelled buttons are wide enough
      // to push the counts into a second row on a narrow screen, so the word is hidden there --
      // and it STAYS IN THE MARKUP, which is what keeps it the button's name for a screen
      // reader. The `sm:not-sr-only` is the other half: on a wide screen the word is back.
      expect(attrOf(off, "composer-todos-remind-word", "class")).toContain("sr-only");
      expect(attrOf(off, "composer-todos-remind-word", "class")).toContain("sm:not-sr-only");
      expect(attrOf(off, "composer-todos-auto-word", "class")).toContain("sr-only");
      expect(attrOf(off, "composer-todos-auto-word", "class")).toContain("sm:not-sr-only");
      // AND THE STATE IS STILL READABLE WITH THE WORD HIDDEN: the icon changes with the
      // boolean, and `aria-pressed` (above) says it to a reader.
      expect(off).toContain("lucide-bell-off");

      const on = hands(MIXED, true, "zh");
      expect(textOf(on, "composer-todos-auto")).toContain("自动提醒 · 开");
      expect(attrOf(on, "composer-todos-auto", "data-on")).toBe("true");
      expect(attrOf(on, "composer-todos-auto", "aria-pressed")).toBe("true");
      expect(on).toContain("lucide-bell");
      // AND THE HANDS SHARE THE FOLD'S ROW (`.scratch/todo-strip-inline`): the strip is ONE line
      // tall because the two controls sit in the counts' row instead of a row of their own.
      // Read off the SOURCE, because 'the same parent element' is what a rendered string is
      // worst at saying: the trigger closes, the hands follow, and only then does the list
      // begin -- and the row they share carries the class below.
      expect(composerTodosSource).toContain("flex w-full min-w-0 items-center gap-1 pr-1");
      const triggerAt = composerTodosSource.indexOf("</CollapsibleTrigger>");
      const handsAt = composerTodosSource.indexOf('data-slot="composer-todos-remind"');
      const listAt = composerTodosSource.indexOf("<CollapsibleContent");
      expect(triggerAt).toBeGreaterThan(-1);
      expect(handsAt).toBeGreaterThan(triggerAt);
      expect(listAt).toBeGreaterThan(handsAt);
      // AND THE FOLD IS THE RIGHTMOST OF THE THREE (owner, 2026-10-03): the chevron is a
      // control of its own at the row's right edge, after the hands.
      const foldAt = composerTodosSource.indexOf('data-slot="composer-todos-fold"');
      expect(foldAt).toBeGreaterThan(handsAt);
      expect(listAt).toBeGreaterThan(foldAt);
    },
  },
  {
    name: "the-nudge-is-disabled-on-a-list-with-nothing-left",
    run: async () => {
      // THE SERVER'S RULE, SAID BEFORE THE PRESS: a list every item of which is done has
      // nothing to remind about (`:nothing-to-remind`), so the button is disabled and its
      // `title` says why.
      const done: readonly TodoItem[] = [{ content: "everything", status: "completed" }];
      const html = hands(done, false, "zh");
      expect(attrOf(html, "composer-todos-remind", "title")).toBe("没有没做完的事可提醒");
      expect(attrOf(html, "composer-todos-remind", "disabled")).toBe("");
      // AND IT IS LIVE WHILE THERE IS WORK -- the same rule, the other way round.
      const live = /<button[^>]*data-slot="composer-todos-remind"[^>]*>/.exec(hands(MIXED, false, "en"))?.[0] ?? "";
      expect(live).not.toContain('disabled=""');
    },
  },
  {
    name: "the-press-is-a-command-and-the-switch-state-comes-from-the-route",
    run: async () => {
      // A WRITE IS A COMMAND, NEVER A ROUTE OF ITS OWN (`lib/goal.ts`'s rule): both hands ride
      // `applyTodo`, which posts a run request carrying the command. The switch's state is READ
      // (`setAuto(next.auto)`) and never inferred -- and no timer is involved, which is
      // `docs/rules/panel-data.md` stated as an assertion.
      expect(composerTodosSource).toContain("applyTodo");
      expect(composerTodosSource).toContain('press("remind")');
      expect(composerTodosSource).toContain('press("auto"');
      expect(composerTodosSource).toContain("setAuto(next.auto)");
      expect(composerTodosSource).not.toContain("setInterval");
      // AND THE SWITCH IS PUSHED TOO: a `todos` frame moves it (someone else's press), and a
      // reconnect re-reads because that frame carries no cursor.
      expect(composerTodosSource).toContain("subscribeTodos");
      expect(composerTodosSource).toContain("onDownlinkOpen");
    },
  },
];

export const composerTodosSuite: Suite = { name: "composer-todos", cases };
