// The block above the composer that says this session failed: the error's own sentence
// while it is folded, and the whole thing as JSON one click away (owner, 2026-10-01).
//
// ================================================================ what is assertable here
//
// THE WORDS AND THE SHAPES ARE RENDERED. `SessionErrorDetail` and `SessionErrorCard` go
// through `react-dom/server` to a string, in a real i18n instance built from the real
// catalogs (`test/support/locale.ts`), and the string is read back -- the idiom
// `suites/composer-todos.tsx` uses, and for its reason: a component that renders nothing
// can pass every suite that only talks to the backend.
//
// WHAT THIS RUN CANNOT SEE, and what is therefore read as SOURCE. There is no DOM, so no
// click happens: the fold is radix's `Collapsible`, the trigger and the content elements
// are read off `session-error-card.tsx?raw`, and the interaction itself belongs to a
// browser. The same reader pins the two facts a string cannot carry: WHERE the card sits
// (above the todo strip, which is above the input -- source order in
// `composer-chrome.tsx`) and that the SIDEBAR no longer draws a session's failure at all.
import { renderToStaticMarkup } from "react-dom/server";
import { I18nextProvider } from "react-i18next";
import { expect } from "vitest";

import { type Case, type Suite } from "../e2e";
import { renderI18n } from "../support/locale";
import {
  SessionErrorCard,
  SessionErrorDetail,
} from "../../src/components/session-error-card";
import { sessionFailureOf, type SessionFailure } from "../../src/lib/session-error";
import type { Language } from "../../src/lib/language";
import sessionErrorCardSource from "../../src/components/session-error-card.tsx?raw";
import composerChromeSource from "../../src/components/composer-chrome.tsx?raw";
import threadSource from "../../src/components/assistant-ui/elements/thread.aui.tsx?raw";
import composerTodosSource from "../../src/components/composer-todos.tsx?raw";
import composerGoalSource from "../../src/components/composer-goal.tsx?raw";
import composerQueueSource from "../../src/components/composer-queue.tsx?raw";
import sidebarSource from "../../src/components/sidebar.tsx?raw";
import appSource from "../../src/app.tsx?raw";

/// THE TEXT OF ONE SLOT -- "the element is found by its `data-slot` and its children are
/// read" is a claim about an element, not "the string is somewhere in the markup". The
/// same two readers `suites/composer-todos.tsx` defines.
function textOf(html: string, slot: string): string {
  const match = new RegExp(`<([a-z][a-z0-9]*)[^>]*data-slot="${slot}"[^>]*>([\\s\\S]*?)</\\1>`).exec(html);
  if (match === null) throw new Error(`no [data-slot="${slot}"] in the render: ${html}`);
  return match[2]!.replace(/<[^>]*>/g, "");
}

function attrOf(html: string, slot: string, name: string): string {
  const element = new RegExp(`<([a-z]+)[^>]*data-slot="${slot}"[^>]*>`).exec(html);
  if (element === null) throw new Error(`no [data-slot="${slot}"] in the render: ${html}`);
  const attribute = new RegExp(`\\b${name}="([^"]*)"`).exec(element[0]);
  if (attribute === null) throw new Error(`[data-slot="${slot}"] carries no ${name}: ${element[0]}`);
  return attribute[1]!;
}

/// A failure as the page files it: the error's sentence and its JSON.
function failureOf(message: string, name?: string): SessionFailure {
  const error = new Error(message);
  if (name !== undefined) error.name = name;
  return sessionFailureOf(error);
}

function card(failure: SessionFailure, language: Language): string {
  return renderToStaticMarkup(
    <I18nextProvider i18n={renderI18n(language)}>
      <SessionErrorCard failure={failure} />
    </I18nextProvider>,
  );
}

function detail(failure: SessionFailure): string {
  return renderToStaticMarkup(
    <I18nextProvider i18n={renderI18n("en")}>
      <SessionErrorDetail failure={failure} />
    </I18nextProvider>,
  );
}

const cases: readonly Case[] = [
  {
    name: "the-folded-line-is-the-errors-own-sentence-in-both-languages",
    run: async () => {
      // THE SENTENCE IS THE ERROR'S, not the interface's -- an error is a fact about the
      // bytes and is drawn as it arrived. The one word that IS translated is the control
      // that opens the detail, which is interface copy.
      const failure = failureOf("the run died", "RunError");
      expect(textOf(card(failure, "en"), "session-error-message")).toBe("the run died");
      expect(textOf(card(failure, "zh"), "session-error-message")).toBe("the run died");
      expect(textOf(card(failure, "en"), "session-error-details")).toBe("Details");
      expect(textOf(card(failure, "zh"), "session-error-details")).toBe("详情");
    },
  },
  {
    name: "the-detail-is-the-whole-error-as-json-not-an-empty-object",
    run: async () => {
      // THE BUG THIS CATCHES: `JSON.stringify(new Error(..))` is `{}`, because an Error's
      // `name`, `message` and `stack` are not own enumerable properties. The detail is the
      // reader's one look at the failure, so it has to say what happened.
      const error = new Error("the run died");
      error.name = "RunError";
      (error as Error & { code?: string }).code = "E_PROVIDER";
      const failure = sessionFailureOf(error);

      expect(failure.message).toBe("the run died");
      const parsed = JSON.parse(failure.detail) as Record<string, unknown>;
      expect(parsed.name).toBe("RunError");
      expect(parsed.message).toBe("the run died");
      expect(typeof parsed.stack).toBe("string");
      // AN OWN PROPERTY A VENDOR HUNG ON IT IS KEPT -- `code` is exactly what somebody
      // opening the detail wants.
      expect(parsed.code).toBe("E_PROVIDER");

      // AND IT IS THE SAME STRING THE COMPONENT DRAWS.
      expect(textOf(detail(failure), "session-error-json")).toContain("RunError");
      expect(textOf(detail(failure), "session-error-json")).toContain("E_PROVIDER");
    },
  },
  {
    name: "the-folded-line-is-the-short-sentence-inside-the-refusals-json",
    run: async () => {
      // A REFUSAL ARRIVES AS A STATUS, A COLON, AND A WALL OF JSON
      // (`harness.kernel.llm` words a vendor's `:refuse` that way). The card is ONE line
      // above the composer, so what it says is the SENTENCE INSIDE -- and the JSON wall is
      // still one click away, in the detail (owner, 2026-10-01).
      const raw =
        'HTTP 500: {"error":{"message":"the vendor exploded mid-turn","type":"server_error"}}';
      const failure = sessionFailureOf(new Error(raw));
      expect(failure.message).toBe("HTTP 500: the vendor exploded mid-turn");
      // THE STATUS IS KEPT -- it is the one thing a person can act on without opening
      // anything -- and the detail still carries the whole error, JSON wall included.
      expect((JSON.parse(failure.detail) as { message: string }).message).toBe(raw);
      // AND THAT IS WHAT THE CARD DRAWS, not the wall.
      const html = card(failure, "zh");
      expect(textOf(html, "session-error-message")).toBe("HTTP 500: the vendor exploded mid-turn");
      expect(textOf(html, "session-error-message")).not.toContain("server_error");

      // THE OTHER TWO SHAPES A BODY ARRIVES IN, and the two things that are NOT a body:
      // a flat `{"error": ".."}`, a bare `{"message": ".."}`, and a colon followed by a
      // sentence rather than JSON -- which is the error's own wording and is left exactly
      // as it stands.
      expect(sessionFailureOf(new Error('HTTP 400: {"error":"flat refusal"}')).message).toBe(
        "HTTP 400: flat refusal",
      );
      expect(sessionFailureOf(new Error('HTTP 400: {"message":"bare sentence"}')).message).toBe(
        "HTTP 400: bare sentence",
      );
      expect(sessionFailureOf(new Error("HTTP 500: not json at all")).message).toBe(
        "HTTP 500: not json at all",
      );
      expect(sessionFailureOf(new Error("the run died")).message).toBe("the run died");
    },
  },
  {
    name: "a-cause-is-kept-and-a-cycle-is-named-rather-than-thrown",
    run: async () => {
      const cause = new Error("the socket closed");
      const outer = new Error("the run died") as Error & { cause?: unknown };
      outer.cause = cause;
      const parsed = JSON.parse(sessionFailureOf(outer).detail) as Record<string, unknown>;
      expect((parsed.cause as Record<string, unknown>).message).toBe("the socket closed");

      // A SELF-REFERENCE MUST NOT THROW: serialising a failure can never be the second
      // failure.
      const self = new Error("self") as Error & { self?: unknown };
      self.self = self;
      expect(sessionFailureOf(self).detail).toContain("[circular]");

      // AND SOMETHING THAT IS NOT AN ERROR AT ALL is still a sentence (`registerPending`
      // can reject with anything).
      expect(sessionFailureOf("plain refusal").message).toBe("plain refusal");
    },
  },
  {
    name: "the-fold-is-radixs-and-the-detail-is-folded-away-by-default",
    run: async () => {
      const html = card(failureOf("the run died", "RunError"), "en");
      // ONE BUTTON, FOLDED: `aria-expanded` and the keyboard are the primitive's, which is
      // why the source pin below matters.
      expect(/<button[^>]*data-slot="session-error-toggle"/.test(html)).toBe(true);
      expect(attrOf(html, "session-error-toggle", "aria-expanded")).toBe("false");
      // FOLDED MEANS THE DETAIL IS NOT DRAWN -- what a person sees by default is the
      // message and nothing else.
      expect(html).not.toContain('data-slot="session-error-json"');
      expect(sessionErrorCardSource).toContain("<CollapsibleTrigger");
      expect(sessionErrorCardSource).toContain("<CollapsibleContent");
      // AND THE DETAIL SCROLLS ITSELF rather than pushing the input down the page.
      expect(sessionErrorCardSource).toContain("max-h-60");
      expect(sessionErrorCardSource).toContain("overflow-y-auto");
    },
  },
  {
    name: "the-stack-is-failure-goal-todos-queue-and-composer-and-only-the-queue-is-joined",
    run: async () => {
      // THE ORDER IS THE WHOLE OF THIS CASE (owner, 2026-10-01), read as source order in
      // the frame that wraps the composer: FAILURE, GOAL, TASKS, QUEUE, and then the
      // composer's own root (`{children}`). The `{children}` read is the LAST one because
      // the frame's early return for a null thread id says `{children}` too, and that one
      // comes first.
      const at = (needle: string): number => {
        const index = composerChromeSource.indexOf(needle);
        expect(index, `${needle} is not mounted in the composer frame`).toBeGreaterThan(-1);
        return index;
      };
      const cardAt = at("<SessionErrorCard");
      const goalAt = at("<ComposerGoal");
      const todosAt = at("<ComposerTodos");
      const queueAt = at("<ComposerQueue");
      const inputAt = composerChromeSource.lastIndexOf("{children}");
      expect(inputAt).toBeGreaterThan(-1);
      expect(cardAt).toBeLessThan(goalAt);
      expect(goalAt).toBeLessThan(todosAt);
      expect(todosAt).toBeLessThan(queueAt);
      expect(queueAt).toBeLessThan(inputAt);
      // IT READS THE FAILURE OUT OF A CONTEXT, because the composer is inside the copied
      // element and cannot be handed a prop.
      expect(composerChromeSource).toContain("useSessionFailure()");
      expect(appSource).toContain("<SessionErrorContext.Provider value={failure}>");
      expect(appSource).toContain("failure={sessionErrors[host.id] ?? null}");

      // AND THE LEFT COLUMN NO LONGER HAS ANYTHING TO DRAW A SESSION'S FAILURE WITH: the
      // `openErrors` prop is gone and a row's `error` is only its own refusal.
      expect(sidebarSource).not.toContain("openErrors");
      expect(sidebarSource).toContain(
        "error={rowError?.id === session.threadId ? rowError.message : null}",
      );

      // AND THE GAPS ARE THE OTHER HALF (owner, 2026-10-01): every block that DRAWS carries a
      // blank gap under it so it reads as a card of its own, and the QUEUE -- the one thing
      // joined to the composer -- carries none. THE GOAL SLOT IS NO LONGER ONE OF THE RESERVED
      // ONES (ticket 07 of `.scratch/goal` filled it in), so the only slot that draws nothing
      // today is the queue, and its spacing is a written obligation rather than a rendered one.
      // The goal's own spacing is the same obligation, and now also a rendered one (`suites/goal`
      // pins the strip).
      expect(sessionErrorCardSource).toContain("mb-1.5");
      expect(composerTodosSource).toContain("mb-1.5");
      // AND THE TASK LIST IS A ROUNDED BOX OF ITS OWN, in the composer's own material --
      // otherwise it is a line of text on the grey frame and reads as part of the
      // composer. This is the whole of "only the queue is joined to the composer".
      expect(composerTodosSource).toContain("border-border/60");
      expect(composerTodosSource).toContain("bg-(--composer-bg)");
      expect(composerGoalSource).toContain("mb-1.5");
      expect(composerQueueSource).not.toContain("mb-1.5");
      // AND THE GOAL SLOT STILL DRAWS NOTHING FOR A SESSION WITH NO GOAL -- the same claim the
      // line below makes about the queue, and the same one the `() => null` slot used to make.
      expect(composerGoalSource).toContain("if (goal === null) return null;");
      expect(composerQueueSource).toContain("() => null");

      // AND THE CONVERSATION DRAWS NO ERROR OF ITS OWN ANY MORE (owner, 2026-10-01): the
      // same failure used to be said twice -- upstream's red alert under the failed turn
      // AND the strip above the composer. It is said once, in the strip.
      expect(threadSource).not.toContain("<MessageError />");
      expect(threadSource).not.toContain("ErrorPrimitive");
    },
  },
];

export const sessionErrorSuite: Suite = { name: "session-error", cases };
