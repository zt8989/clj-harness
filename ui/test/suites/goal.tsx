// The goal strip above the composer, `/goal …` as the composer reads it, and the goal family on
// the downlink (`.scratch/goal`, tickets 07 and 08).
//
// ================================================================ what is assertable here
//
// THE WORDS AND THE SHAPES ARE RENDERED. `ComposerGoalView` goes through `react-dom/server` to a
// string, in a real i18n instance built from the real catalogs (`test/support/locale.ts`), and the
// string is read back -- the idiom `suites/composer-todos.tsx` and `suites/sidebar.tsx` established,
// for the same reason: a component that renders nothing can pass every suite that only talks to the
// backend, and this one is ALLOWED to render nothing (that is one of the cases below).
//
// THE PARSER IS PURE, so its six shapes and its refusals are literals in and values out -- no
// harness, no socket.
//
// AND THE FRAME PATH IS DRIVEN WITHOUT A BROWSER: a stand-in `WebSocket` answers this module's own
// copy of `lib/mux.ts` (imported dynamically after `vi.resetModules()`, the way `suites/mux.ts`
// does it -- the shared instance is already driving real runs by the time this suite runs), and two
// frames are fed through it. What that pins is the routing the page depends on: a `goal` frame for
// one conversation reaches that conversation's subscribers and NOBODY else's.
//
// WHAT A STRING CANNOT SHOW, and what is therefore read as SOURCE: there is no DOM, so no button is
// ever pressed and the fold is never opened. The one case that reads `composer-goal.tsx?raw` is the
// "when does it ask" rule -- the snapshot, the frame, the two facts, the reconnect, no timer -- which
// is a claim about triggers that this run cannot fire. A real press, a real frame and a real run are
// the browser walkthrough's half (`node scripts/dev.mjs --scripted`).
import { renderToStaticMarkup } from "react-dom/server";
import { I18nextProvider } from "react-i18next";
import { expect, vi } from "vitest";

import { type Case, type Suite } from "../e2e";
import { renderI18n } from "../support/locale";
import { ComposerGoalView } from "../../src/components/composer-goal";
import {
  onGoalShown,
  opensGoalCommand,
  parseGoalCommand,
  showGoal,
} from "../../src/lib/goal-command";
import composerChromeSource from "../../src/components/composer-chrome.tsx?raw";
import agentSource from "../../src/lib/agent.ts?raw";
import { goalShown, noteGoalShown, type Goal } from "../../src/lib/goal";
import type { Language } from "../../src/lib/language";
import composerGoalSource from "../../src/components/composer-goal.tsx?raw";

/// THE TEXT OF ONE SLOT -- the two lines `suites/composer-todos.tsx` defines and this file restates
/// for the same reason: "the element is found by its `data-slot` and its children are read" is a
/// claim about an ELEMENT, not "the string is somewhere in the markup".
function textOf(html: string, slot: string): string {
  const match = new RegExp(`<([a-z][a-z0-9]*)[^>]*data-slot="${slot}"[^>]*>([\\s\\S]*?)</\\1>`).exec(html);
  if (match === null) throw new Error(`no [data-slot="${slot}"] in the render: ${html}`);
  return match[2]!.replace(/<[^>]*>/g, "");
}

/// EVERY HAND THE STRIP OFFERS, in the order it draws them: the verbs ride on each press
/// (`data-action`), so what a phase may do is read without knowing a class name.
function actionsOf(html: string): string[] {
  return [...html.matchAll(/data-slot="composer-goal-action"[^>]*data-action="([^"]*)"/g)].map(
    (match) => match[1]!,
  );
}

/// EVERY HAND'S WORD, in the same order -- what a person actually reads on them.
function labelsOf(html: string): string[] {
  return [...html.matchAll(/<button[^>]*data-slot="composer-goal-action"[^>]*>([^<]*)<\/button>/g)].map(
    (match) => match[1]!,
  );
}

/// The strip, with data in hand, in a real instance from the real catalogs.
function strip(goal: Goal | null, armed: boolean, language: Language, open = false): string {
  return renderToStaticMarkup(
    <I18nextProvider i18n={renderI18n(language)}>
      <ComposerGoalView
        goal={goal}
        armed={armed}
        open={open}
        onOpenChange={() => {}}
        onPress={() => {}}
      />
    </I18nextProvider>,
  );
}

/// ONE GOAL, the shape `GET …/goal` answers -- a real objective, a revision worth fencing on, and
/// a round count against the cap.
const ACTIVE: Goal = {
  id: "g-8f3a",
  revision: 3,
  objective: "把登录模块重构完，补齐测试和迁移说明",
  phase: "active",
  rounds: 7,
  "max-rounds": 25,
  "updated-at": 1696000000000,
};

/// The same goal, stopped by the driver's own brake: `.scratch/goal` decision 9 turns a round that
/// changed no file into this phase, and the code is the criterion's own name for it.
const STOPPED: Goal = {
  ...ACTIVE,
  phase: "blocked",
  blocked: { code: "no-progress", reason: "上一轮没有改过一个文件" },
};

// ------------------------------------------------------- a socket this suite can feed
//
// THE BROWSER'S `WebSocket`, STANDING IN FOR THE LENGTH OF THE FRAME CASE BELOW. It never connects;
// it computes its `readyState` from a flag so one assignment moves the socket the module is already
// holding, and `feed` hands it a frame the way the browser does (the module reads `event.data` and
// nothing else).
class StandingSocket {
  static readonly CONNECTING = 0;
  static readonly OPEN = 1;
  static last: StandingSocket | null = null;
  constructor(readonly url: string) {
    StandingSocket.last = this;
  }
  get readyState(): number {
    return StandingSocket.OPEN;
  }
  onopen: (() => void) | null = null;
  onmessage: ((event: { data: string }) => void) | null = null;
  onclose: (() => void) | null = null;
  addEventListener(): void {}
  removeEventListener(): void {}
  feed(frame: Record<string, unknown>): void {
    this.onmessage?.({ data: JSON.stringify(frame) });
  }
}

/// THE THREE BITS OF THE BROWSER THE FRAME CASE NEEDS, and where they go back to: a COPY of
/// `lib/mux.ts` (so this case owns the socket and disturbs nobody), an answering stand-in above, and
/// a clock that is a queue nothing runs until the case says so. `fetch` answers the subscription's
/// declaration (`POST /api/events.mux/subscribe`) and records it.
async function withGoalSocket<T>(
  body: (state: {
    mux: typeof import("../../src/lib/mux");
    declares: Array<Record<string, unknown>>;
    draw: () => void;
    socket: () => StandingSocket;
  }) => Promise<T>,
): Promise<T> {
  const browser = globalThis.WebSocket;
  const realFetch = globalThis.fetch;
  const realRaf = globalThis.requestAnimationFrame;
  const declares: Array<Record<string, unknown>> = [];
  const clock: Array<() => void> = [];
  globalThis.WebSocket = StandingSocket as unknown as typeof WebSocket;
  globalThis.requestAnimationFrame = ((cb: FrameRequestCallback) => {
    clock.push(cb as unknown as () => void);
    return 0;
  }) as typeof requestAnimationFrame;
  globalThis.fetch = (async (_url: unknown, init?: RequestInit) => {
    const body = JSON.parse(String(init?.body ?? "{}")) as {
      subscribe?: Array<Record<string, unknown>>;
    };
    for (const entry of body.subscribe ?? []) declares.push(entry);
    return new Response("{}", { status: 200, headers: { "Content-Type": "application/json" } });
  }) as typeof fetch;
  try {
    vi.resetModules();
    const mux = await import("../../src/lib/mux");
    return await body({
      mux,
      declares,
      draw: () => {
        for (const cb of clock.splice(0)) cb();
      },
      socket: () => {
        if (StandingSocket.last === null) throw new Error("the module never opened a socket");
        return StandingSocket.last;
      },
    });
  } finally {
    // WHAT WAS HELD GOES OUT (a frame left in the batch would be a frame this case never saw), and
    // every stub goes back where it was: no later suite may see a stand-in socket.
    for (const cb of clock.splice(0)) cb();
    globalThis.WebSocket = browser;
    globalThis.fetch = realFetch;
    if (realRaf === undefined)
      delete (globalThis as { requestAnimationFrame?: unknown }).requestAnimationFrame;
    else globalThis.requestAnimationFrame = realRaf;
  }
}

const cases: readonly Case[] = [
  {
    name: "a-session-with-no-goal-draws-nothing-at-all",
    run: async () => {
      // `null` IS "THIS SESSION HAS NO GOAL", and it is the ordinary answer -- most sessions never
      // get one. ZERO NODES, not an empty box: furniture that comes and goes is worse than
      // furniture that is simply not there (the rule the task strip follows too).
      expect(strip(null, false, "en")).toBe("");
      expect(strip(null, true, "en")).toBe("");
      expect(strip(null, false, "zh")).toBe("");
    },
  },
  {
    name: "each-phase-and-arm-combination-draws-its-own-hands",
    run: async () => {
      // THE FOUR ROWS OF TICKET 07, and the fifth case they share with `completed`. What a phase
      // offers is decided by the phase AND the arm together, and nothing else:
      //
      //   active + armed     -> a person can only STOP it (pause), or edit, or clear
      //   active + disarmed  -> RE-ARM it, or edit, or clear
      //   paused / blocked   -> resume, edit, clear (only a person may lift either)
      //   completed          -> clear, and nothing else
      expect(actionsOf(strip(ACTIVE, true, "en"))).toEqual(["pause", "edit", "clear"]);
      expect(actionsOf(strip(ACTIVE, false, "en"))).toEqual(["resume", "edit", "clear"]);
      expect(actionsOf(strip({ ...ACTIVE, phase: "paused" }, false, "en"))).toEqual([
        "resume",
        "edit",
        "clear",
      ]);
      expect(actionsOf(strip(STOPPED, false, "en"))).toEqual(["resume", "edit", "clear"]);
      expect(actionsOf(strip({ ...ACTIVE, phase: "completed" }, false, "en"))).toEqual(["clear"]);
      // AND IT IS A BUTTON PER HAND, not a list of verbs somewhere: the count is the set's.
      expect(actionsOf(strip(ACTIVE, false, "en")).length).toBe(3);
    },
  },
  {
    name: "the-rounds-and-the-blocker-are-drawn",
    run: async () => {
      // `round n/max` IS THE ROUND COUNT AGAINST THE CAP the driver counts (decision 9), and the
      // blocked pair is the SERVER's own code and sentence -- never a paraphrase.
      expect(textOf(strip(STOPPED, false, "zh"), "composer-goal-rounds")).toBe("第 7/25 轮");
      expect(textOf(strip(STOPPED, false, "en"), "composer-goal-rounds")).toBe("round 7/25");
      expect(textOf(strip(STOPPED, false, "zh"), "composer-goal-blocked")).toBe(
        "no-progress：上一轮没有改过一个文件",
      );
      expect(textOf(strip(STOPPED, false, "en"), "composer-goal-blocked")).toBe(
        "no-progress: 上一轮没有改过一个文件",
      );
      // AND A GOAL THAT IS GOING NOWHERE HAS NO BLOCKER LINE AT ALL -- a goal that is merely
      // paused is not a goal that is stuck, and drawing one as the other would be the strip
      // inventing a reason.
      expect(strip({ ...ACTIVE, phase: "paused" }, false, "en")).not.toContain(
        'data-slot="composer-goal-blocked"',
      );
      // AND THE FOLD CARRIES THE WHOLE OBJECTIVE: folded, the line is clipped and the whole of
      // it is the `title`; opened, it is drawn in full -- the one thing the fold is for.
      expect(textOf(strip(STOPPED, false, "zh", true), "composer-goal-objective")).toBe(
        ACTIVE.objective,
      );
    },
  },
  {
    name: "the-words-come-from-the-catalog",
    run: async () => {
      // THE PHASE AND THE OBJECTIVE ARE THE FOLDED LINE, in the interface's language; the model's
      // own objective is NOT translated, because it is the words somebody wrote.
      expect(textOf(strip(ACTIVE, true, "en"), "composer-goal-summary")).toBe(
        `active · ${ACTIVE.objective}`,
      );
      expect(textOf(strip(ACTIVE, true, "zh"), "composer-goal-summary")).toBe(
        `进行中 · ${ACTIVE.objective}`,
      );
      expect(textOf(strip(STOPPED, false, "zh"), "composer-goal-summary")).toBe(
        `卡住了 · ${ACTIVE.objective}`,
      );
      // THE ARM IS DRAWN AS A SENTENCE, and the two sentences are the whole brake the design is
      // built around: 自动续跑中 is "this process will open the next round", 已停 is "it will not
      // until somebody says something".
      expect(textOf(strip(ACTIVE, true, "zh"), "composer-goal-armed")).toBe("自动续跑中");
      expect(textOf(strip(ACTIVE, false, "zh"), "composer-goal-armed")).toBe("已停（要人再说一句）");
      expect(textOf(strip(ACTIVE, true, "en"), "composer-goal-armed")).toBe("carrying on by itself");
      // AND THE HANDS THEMSELVES: the same three verbs, the two languages.
      expect(labelsOf(strip(ACTIVE, true, "en"))).toEqual(["Pause", "Edit", "Clear"]);
      expect(labelsOf(strip(ACTIVE, true, "zh"))).toEqual(["暂停", "编辑", "清除"]);
      expect(labelsOf(strip(ACTIVE, false, "zh"))).toEqual(["恢复", "编辑", "清除"]);
    },
  },
  {
    name: "the-six-shapes-are-read-as-commands",
    run: async () => {
      // THE SHAPES TICKET 08 NAMES, one literal each. `/goal` alone is the READ verb -- it is not
      // on any wire, and `suites/goal`'s frame case above is a different thing from it.
      expect(parseGoalCommand("/goal")).toEqual({ action: "show" });
      expect(parseGoalCommand("/goal   ")).toEqual({ action: "show" });
      expect(parseGoalCommand("/goal 把登录模块重构完")).toEqual({
        action: "create",
        objective: "把登录模块重构完",
      });
      expect(parseGoalCommand("/goal edit 只改文字")).toEqual({
        action: "edit",
        objective: "只改文字",
      });
      expect(parseGoalCommand("/goal pause")).toEqual({ action: "pause" });
      expect(parseGoalCommand("/goal resume")).toEqual({ action: "resume" });
      expect(parseGoalCommand("/goal clear")).toEqual({ action: "clear" });
      // THE RESERVED WORDS ARE RESERVED INSIDE A SENTENCE TOO: only the FIRST word is a verb, so an
      // objective that happens to start with one of them is still just words.
      expect(parseGoalCommand("/goal 重构 pause 这件事")).toEqual({
        action: "create",
        objective: "重构 pause 这件事",
      });
      // AND THE SHAPE IS ONE RULE, not two: the trigger the parser reads is the one the SKILL MENU
      // refuses, because `goal` is the reserved name. The menu's share is not cosmetic -- a popover
      // that believes a skill is being typed eats the Enter, which is how `/goal` alone failed to
      // reach the agent at all (walkthrough, 2026-10-02).
      expect(opensGoalCommand("/goal")).toBe(true);
      expect(opensGoalCommand("/goal 重构")).toBe(true);
      expect(opensGoalCommand("/goalx")).toBe(false);
      expect(opensGoalCommand("请 /goal 一下")).toBe(false);
      expect(composerChromeSource).toContain("opensGoalCommand(typed)");
    },
  },
  {
    name: "the-messages-that-are-not-commands-are-refused",
    run: async () => {
      // THE RESERVED VERBS ARE READ EXACTLY: `edit` with nothing after it is refused rather than
      // taken as an objective called "edit", and the other three take no words at all -- a trailing
      // one is a sentence this rule cannot carry out, and half-carrying it out is worse.
      expect(parseGoalCommand("/goal edit")).toBeNull();
      expect(parseGoalCommand("/goal edit  ")).toBeNull();
      expect(parseGoalCommand("/goal pause 顺手把这个也做了")).toBeNull();
      expect(parseGoalCommand("/goal clear 现在")).toBeNull();
      // AND THE TRIGGER IS THE SKILL SLASH'S OWN (`components/composer-chrome.tsx`): `/` at the
      // START of the message, the name ended by whitespace or the end. So `/goalx` is a message
      // about something else, and a `/goal` in the middle of one is not a command either.
      expect(parseGoalCommand("/goals")).toBeNull();
      expect(parseGoalCommand("/goalx 重构")).toBeNull();
      expect(parseGoalCommand("请 /goal 一下")).toBeNull();
      expect(parseGoalCommand("说一句普通的话")).toBeNull();
      expect(parseGoalCommand("")).toBeNull();
    },
  },
  {
    name: "the-read-verb-rings-the-strip-and-sends-nothing",
    run: async () => {
      // `/goal` ALONE IS NOT A REQUEST. It is a request to LOOK at the strip, and it has two
      // halves in two modules: `lib/agent.ts` stops the send (there is no command and no
      // question), and the strip opens for the ring. The doorbell is pure, so it is pinned here;
      // the stopped send is read off that seam's source, because this run has no runtime to
      // send one through.
      let rings = 0;
      const stop = onGoalShown(() => {
        rings += 1;
      });
      showGoal();
      expect(rings).toBe(1);
      stop();
      showGoal();
      expect(rings, "a reader that stopped is not rung again").toBe(1);
      // WHICH SEAM, AND WHY, is written at that seam: the run itself is where a lone `/goal` is
      // stopped (`goalSend`'s `emptied`), and `showGoal()` is what it does instead.
      expect(agentSource).toContain("goalSend(this.messages, this.threadId)");
      expect(agentSource).toContain("showGoal()");
    },
  },
  {
    name: "a-typed-command-names-the-goal-on-screen",
    run: async () => {
      // THE FENCE A `/goal …` TYPED INTO THE COMPOSER HAS TO NAME, which the walkthrough
      // (2026-10-02) found missing: the server refused every `pause`/`resume`/`clear` with 'goal
      // moved', because the request carried a nil `{goal_id, revision}`. The page shows ONE goal
      // and a command is about THAT one -- and the seam that builds the request (`lib/agent.ts`)
      // cannot see the component that draws it, so the strip publishes what it drew and the seam
      // reads it (`lib/goal.ts`'s `goalShown`).
      expect(goalShown("goal-shown-a")).toBeNull();
      noteGoalShown("goal-shown-a", ACTIVE);
      expect(goalShown("goal-shown-a")?.revision).toBe(3);
      expect(goalShown("goal-shown-b"), "by conversation, never one for the whole page").toBeNull();
      noteGoalShown("goal-shown-a", { ...ACTIVE, revision: 4 });
      expect(goalShown("goal-shown-a")?.revision, "the fence moves with the goal").toBe(4);
      noteGoalShown("goal-shown-a", null);
      expect(goalShown("goal-shown-a"), "a cleared goal is not a fence").toBeNull();
      // AND THE TWO ENDS ARE WIRED: the strip publishes what it draws, and the seam reads it.
      expect(composerGoalSource).toContain("noteGoalShown(threadId, goal)");
      expect(agentSource).toContain("goalShown(threadId)");
      // A FRESH GOAL IS THE ONE VERB WITH NOTHING TO FENCE AGAINST.
      expect(agentSource).toContain('action === "create" ? null : goalShown(threadId)');
    },
  },
  {
    name: "a-goal-frame-reaches-only-the-conversation-it-is-about",
    run: async () => {
      // THE ROUTING RULE FOR THE FIFTH FAMILY, driven through the real `deliver`: a `goal` frame
      // carries a whole payload and no cursor (`GoalFrame`), so the only thing that makes it safe is
      // that the page hands it to the RIGHT conversation's subscribers -- and to nobody else's.
      await withGoalSocket(async ({ mux, draw, socket }) => {
        expect(mux.familyOf(mux.GOAL_FRAME_TYPE)).toBe("goal");
        // A FRAME THAT FELL THROUGH TO THE RUN FAMILY WOULD BE VALIDATED AS AG-UI and take the run
        // down with it -- the trap `familyOf`'s own branches exist to stop.
        expect(mux.familyOf("goal")).not.toBe("run");

        const here: Array<[number | undefined, boolean | undefined]> = [];
        const elsewhere: string[] = [];
        const mine = mux.subscribeGoals("goal-frame-a", (frame) =>
          here.push([frame.goal?.revision, frame["armed?"]]),
        );
        const other = mux.subscribeGoals("goal-frame-b", (frame) => elsewhere.push(frame.threadId));

        // A STRIP IS A CLAIM ON THE CONVERSATION (`wantedThreads`): without this the server would
        // never send the goal, and the strip above the composer would only ever draw the snapshot.
        expect(mux.declaredSet().map((entry) => entry.threadId)).toContain("goal-frame-a");

        socket().feed({
          threadId: "goal-frame-a",
          type: mux.GOAL_FRAME_TYPE,
          goal: { ...ACTIVE, revision: 9 },
          "armed?": true,
        });
        draw();

        // THE WHOLE PAYLOAD ARRIVED, and only to the conversation it names.
        expect(here).toEqual([[9, true]]);
        expect(elsewhere).toEqual([]);

        // AND THE OTHER CONVERSATION'S OWN FRAME -- `clear` pushes `goal: null` -- goes to IT and
        // not back to the first one.
        socket().feed({
          threadId: "goal-frame-b",
          type: mux.GOAL_FRAME_TYPE,
          goal: null,
          "armed?": false,
        });
        draw();
        expect(elsewhere).toEqual(["goal-frame-b"]);
        expect(here).toEqual([[9, true]]);

        // THE LAST CLAIM OUT TURNS THE WATCH OFF, as every other family's door does.
        mine.unsubscribe();
        other.unsubscribe();
        expect(mux.declaredSet().map((entry) => entry.threadId)).not.toContain("goal-frame-a");
      });
    },
  },
  {
    name: "the-strip-asks-once-then-listens-and-never-ticks",
    run: async () => {
      // THE WHOLE OF "WHEN", read off the source because the triggers are effects and this run has
      // no DOM to fire them in. One snapshot on mount and per session, the pushed frame, a re-read
      // on the two facts a model's own write falls between, and one more after a reconnect
      // (`docs/rules/panel-data.md`, both halves). A `goal` frame has no cursor, so the reconnect
      // read is the only repair there is.
      expect(composerGoalSource).toContain("goalFor(threadId)");
      expect(composerGoalSource).toContain("subscribeGoals");
      expect(composerGoalSource).toContain("subscribeFacts");
      expect(composerGoalSource).toContain('"model/start"');
      expect(composerGoalSource).toContain('"turn/end"');
      expect(composerGoalSource).toContain("onDownlinkOpen");
      // AND THE READ VERB'S OWN DOOR, which is not a trigger at all: `/goal` alone rings it and
      // the strip opens (`lib/agent.ts` is the other end).
      expect(composerGoalSource).toContain("onGoalShown");
      // NO TIMER ASKS THIS ROUTE, which is the rule stated as an assertion rather than a paragraph.
      expect(composerGoalSource).not.toContain("setInterval");
      // A SESSION CHANGE DROPS THE OLD GOAL (the reset) and a late answer from it is refused (the
      // `live` flag) -- the two halves `composer-todos.tsx` names.
      expect(composerGoalSource).toContain("setGoal(null)");
      expect(composerGoalSource).toContain("live");
      // AND THE FENCE A PRESS SENDS IS THE ONE ON SCREEN: the wiring holds the goal, and the view
      // is what a press comes from -- a ref built anywhere else is a ref for a goal nobody saw.
      expect(composerGoalSource).toContain("goal_id: goal.id");
      expect(composerGoalSource).toContain("revision: goal.revision");
    },
  },
];

export const goalSuite: Suite = { name: "goal", cases };
