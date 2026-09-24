// The three tickets that put a subagent's work BESIDE the conversation that delegated
// it: the `agent` card that opens the door (04), the panel it opens (05), and the
// sidebar block that stopped being a door (06).
//
// ================================================================ why a source read
//
// WHAT IS ASSERTABLE HERE AND WHAT IS NOT. The drawable half of this feature is
// React -- a panel needs a runtime, an open SSE connection and a real server, and the
// transcript's card needs a message part with a `toolCallId` -- and the layout it lands
// in is only visible in a browser. So the machine gate below reads the SOURCES and pins
// the DECISIONS a later edit could quietly undo: which vocabulary the `agent` arm
// answers, WHICH things a door's existence is conditioned on, that the follow client
// never posts, that the mirror is a switch on `Thread` rather than a forked copy, and
// that the retired sidebar block left nothing behind that still compiles.
//
// The drawing -- the card's hover, the panel's width, the main column staying readable
// beside it, the transcript growing live -- is the walkthrough's
// (`node scripts/dev.mjs --scripted`), and this file says so where it matters rather
// than pretending a regex saw it.
//
// THE IDIOM IS `suites/tool-row.ts`'S, for the same reason it gives: `message-parts.tsx`
// reaches `lib/i18n.ts`, which touches `document` at module scope, and this run has no
// jsdom by design (`vitest.config.ts`). A file that cannot be imported can still be
// READ, and the two tables this feature adds to are exactly the kind of thing a rename
// or a refactor drops in silence.
import messagePartsSource from "../../src/components/message-parts.tsx?raw";
import threadSource from "../../src/components/assistant-ui/elements/thread.aui.tsx?raw";
import panelSource from "../../src/components/subagent-view.tsx?raw";
import contextSource from "../../src/components/subagent-view-context.ts?raw";
import delegationsSource from "../../src/lib/delegations.ts?raw";
import followSource from "../../src/lib/follow.ts?raw";
import sidebarSource from "../../src/components/sidebar.tsx?raw";
import listSource from "../../src/components/subagent-list.tsx?raw";
import appSource from "../../src/app.tsx?raw";
import toggleSource from "../../src/components/right-pane-toggle.tsx?raw";
import shellEn from "../../src/locales/en/shell.json?raw";
import shellZh from "../../src/locales/zh/shell.json?raw";
import { expect } from "vitest";

import { type Case, type Suite } from "../e2e";

const cases: Case[] = [
  {
    name: "the-agent-arm-says-which-subagent-and-what-it-was-asked",
    run: async () => {
      // THE ROW A DELEGATION GETS. The default arm answers the first string argument,
      // and for this tool that is the TASK -- so without an arm of its own the row
      // would read `agent · <a paragraph>` and never name the subagent that was picked.
      // The arm is read as text because that is what it is: a projection table, one
      // line per tool.
      const arm = /case "agent": \{([\s\S]*?)\n    \}/.exec(messagePartsSource);
      expect(arm, "no `agent` arm in subjectOf").not.toBeNull();
      const body = arm![1]!;
      expect(body).toContain('stringArg(args, "name")');
      expect(body).toContain('stringArg(args, "prompt")');
      // NAME FIRST, TASK SECOND, on one line: the row already draws `agent · `, so the
      // subject's own separator is what makes the sentence read
      // `agent · explore · find every ns that deps.edn needs`.
      expect(body).toMatch(/\$\{name\} · \$\{task\}/);
      // AND THE TASK IS ONE LINE. A prompt is often a paragraph, and a paragraph in a
      // one-line transcript is a row that pushes every other row off the fold.
      expect(body).toContain('firstLine(stringArg(args, "prompt")');
    },
  },
  {
    name: "the-door-is-conditioned-on-the-record-and-not-on-the-call-succeeding",
    run: async () => {
      // THE PAIRING IS THE ID THE WIRE PUT ON THE PART, and the record's answer for
      // that id -- never a position, and never the tool's result. A delegation is worth
      // opening WHILE IT RUNS, which is the moment there is no result to wait for.
      expect(messagePartsSource).toContain(
        'toolName === "agent" ? toolCallId : null,',
      );
      // `settled` IS HANDED OVER, and it is not decoration: it is the page's only
      // knowledge that the call -- and therefore its `delegation` row -- is over.
      expect(messagePartsSource).toMatch(/toolCallId : null,\s*settled,\s*\)/);
      expect(messagePartsSource).toContain('toolName === "agent" ? delegations.get(toolCallId) : undefined');
      expect(messagePartsSource).toContain("useContext(ThreadIdContext)");
      // ...and the card's state is never consulted: `settled`, `result` and `status`
      // take no part in `openMirror`.
      // THE LINE BREAK IS `\r?\n`, NOT `\n`. This run reads a SOURCE, and a Windows
      // checkout (git's `core.autocrlf`) hands a `?raw` import CRLF -- so a regex that wants
      // a `;` immediately before a `\n` finds nothing, and the red run is about the
      // checkout rather than about the card. What the case means is 'the statement ends at
      // the end of a line', which both endings say.
      const resolver = /const openMirror =([\s\S]*?);\r?\n/.exec(messagePartsSource);
      expect(resolver, "no openMirror in the card").not.toBeNull();
      expect(resolver![1]!).not.toContain("settled");
      expect(resolver![1]!).not.toContain("status");

      // A DOOR ONLY WHERE IT CAN BE WALKED THROUGH: the record knows the call, and the
      // page has a panel to open. Both halves are in the ternary, so an old record
      // (no `delegation` row) and a story with no page around it draw the plain subject
      // they always had -- no underline, no pointer, no promise.
      expect(messagePartsSource).toContain("delegation !== undefined && openView !== null");
      expect(messagePartsSource).toContain("onOpen={openMirror}");
      // AND THE PLAIN SUBJECT IS THE `else` OF A RENDER, not a CSS state: a subject with
      // no opener is a string in the row, not a styled control that does nothing.
      expect(messagePartsSource).toMatch(/onOpen === undefined \? \(\s*subject\s*\) : \(/);
      // The click on the door must not also toggle the card open: the row's own click is
      // the disclosure's, and the two are one click apart.
      expect(messagePartsSource).toContain("event.stopPropagation();");
    },
  },
  {
    name: "one-delegations-read-per-parent-session-serves-every-card",
    run: async () => {
      // NOT ONE REQUEST PER CARD. The rows are held per PARENT session and published to
      // every subscriber, and an id the answer already names asks for nothing -- so a
      // transcript with twenty resolved `agent` calls does not make twenty requests,
      // and a chat that re-renders per delta makes none.
      expect(delegationsSource).toContain("const entries = new Map<string, Entry>()");
      expect(delegationsSource).toContain("useSyncExternalStore");
      // THE RACE, PINNED, because it is the failure a browser found and no suite could:
      // the card is drawn while the call streams and the row is written when the call is
      // executed, so "not found" must NOT be final -- the door has to open while the
      // subagent is still working (the spec's acceptance line). The budget is bounded
      // and the loop stops on any of the three endings.
      expect(delegationsSource).toContain("const ATTEMPTS = 4;");
      expect(delegationsSource).toContain("now.rows.has(toolCallId) || now.listeners.size === 0");
      // AND THE RETRY IS IDEMPOTENT PER CALL, which a browser measured: `subscribe` runs
      // on every render (React's contract for the callback), and an unguarded "ask again
      // while unknown" sent seventeen requests for two delegations. `pending` is the
      // guard, and `reading` is what makes a second asker join the read on its way.
      expect(delegationsSource).toContain("entry.pending.has(toolCallId)");
      expect(delegationsSource).toContain("if (entry.reading !== null) return entry.reading;");
      // ...and a settled call gets exactly ONE more ask, guarded by a set: asking per
      // render instead would put a request on every render of every old delegation.
      expect(delegationsSource).toContain("entry.settledAsk.has(toolCallId)");
      // THE ENVELOPE IS READ, NOT ASSUMED: the route answers `{:threadId :delegations}`,
      // and reading it as a bare array is a bug this suite's own walkthrough found --
      // `rows.map` on an object throws, the catch swallows it, and every card stays
      // unclickable with a 200 in the network tab.
      expect(delegationsSource).toContain("answer.delegations ?? []");
      // The KEY is the tool call, which is what pairs a card with a child session.
      expect(delegationsSource).toContain("merged.set(row.toolCallId, row)");
      // And the route it reads is ticket 03's.
      expect(delegationsSource).toMatch(/threads\/\$\{encodeURIComponent\(threadId\)\}\/delegations/);
      // AND ROWS ARE ONLY EVER ADDED: a later answer is merged into the held map
      // rather than replacing it, so one empty or partial answer cannot erase a
      // delegation the page already knew about.
      expect(delegationsSource).toContain("new Map(entry.rows)");
      expect(contextSource).toContain("createContext<((view: SubagentView) => void) | null>(null)");
    },
  },
  {
    name: "the-mirror-follows-the-read-only-channel-and-never-posts",
    run: async () => {
      // THE PANEL'S CLIENT IS A GET. The follow route answers no `RunAgentInput` and
      // accepts no input, so the transport is replaced wholesale -- and the base class's
      // POST body is not merged into it but dropped.
      expect(followSource).toContain('method: "GET"');
      expect(followSource).not.toContain('"POST"');
      expect(followSource).toMatch(/threads\/\$\{encodeURIComponent\(threadId\)\}\/follow/);
      // THE SIGNAL IS KEPT, because it is the abort path: closing the panel is what
      // drops the server-side subscription.
      expect(followSource).toContain("signal: init?.signal ?? null");

      // AND THE RUN IS THE READ: started once on mount, with no message and no parent
      // for it to hang off, so nothing is appended to the child's conversation.
      expect(panelSource).toContain(
        "runtime.thread.startRun({ parentId: null, sourceId: null, runConfig: {} })",
      );
      expect(panelSource).toContain("agent.abortRun();");
      // NO HYDRATION ADAPTER: `rebuild` plus the replayed frames would draw the child's
      // answers twice (see the panel's header). The route's `MESSAGES_SNAPSHOT` is what
      // carries the task instead.
      expect(panelSource).not.toContain("rebuildThread");
      // AND IT REPORTS NOTHING TO THE PAGE. The sidebar's rows are told about sessions
      // by `SessionHost`; a subagent is not one of them, and this panel is its own host.
      // The two reporters are asserted by their JSX FORM rather than by name, because the
      // header names them on purpose -- they are the thing this component deliberately
      // does not do, and a bare identifier there is the argument, not a call.
      expect(panelSource).not.toContain("SessionStatusReporter");
      expect(panelSource).not.toContain("onStatus=");
      expect(panelSource).not.toContain("onForget=");
    },
  },
  {
    name: "the-mirror-is-a-composer-switch-on-the-thread-and-not-a-fork",
    run: async () => {
      // `composer` IS AN OPTIONAL PROP, the shape the copied element already uses for
      // `Welcome` / `ComposerFrame` / `ComposerTools` / `ComposerAddAttachment`. A
      // forked element would start drifting from this one the day either changed, and
      // the message rendering is exactly what the two sides must agree about.
      expect(threadSource).toContain("composer?: boolean | undefined;");
      expect(threadSource).toContain("composer = true,");
      expect(panelSource).toMatch(/<Thread components=\{THREAD_COMPONENTS\} autoFocus=\{false\} composer=\{false\}/);
      // NO COMPOSER, NOT A DISABLED ONE -- and not the new-chat furniture either: the
      // panel's thread is empty until the first frame lands, and a welcome screen over a
      // conversation that already exists is the panel claiming to be a new chat.
      expect(threadSource).toMatch(/\{composer \? \(\s*<ComposerFrame>/);
      expect(threadSource).toMatch(/\{composer \? \(\s*<AuiIf condition=\{isNewChatView\}>/);

      // THE PANEL IS THE THIRD COLUMN: a `shrink-0` sibling AFTER the chat, which keeps
      // its `min-w-0 flex-1`. That pair is what makes "side by side" mean the main
      // conversation gives up exactly the panel's width and can never be squeezed away.
      expect(appSource).toContain("min-h-0 min-w-0 flex-1");
      expect(appSource).toContain("<SubagentViewPanel");
      expect(appSource.indexOf("<SubagentViewPanel")).toBeGreaterThan(
        appSource.indexOf("min-h-0 min-w-0 flex-1"),
      );
      // ONE AT A TIME, AT THE CONNECTION LEVEL: the panel is keyed by the child's id, so
      // switching subagents remounts it and the first child's follow connection goes with
      // its host rather than living on behind the second one's name.
      expect(appSource).toContain("key={rightPane.threadId}");
    },
  },
  {
    name: "the-mirrors-close-x-is-gone-and-the-columns-own-collapse-stands-in-its-place",
    run: async () => {
      // THE TWO VERBS A HEADER COULD OFFER ARE ONE (`.scratch/right-pane-tasks`, decision 3):
      // the X that used to sit at the trailing end of this header closed the whole column, and
      // so does the switch's collapse -- so the X went, rather than the same door standing in
      // one row twice. WHAT REPLACED IT IS THE SHARED CONTROL, not a second button that
      // happens to look like it: the panel keeps the `onClose` it always had and hands it to
      // `components/right-pane-toggle.tsx`, which is the file that owns the pair, their one
      // id, and what each of them says.
      //
      // READ AS SOURCE, because this run cannot render the panel at all -- it needs an
      // assistant runtime and a live SSE connection (see this file's header). What a source
      // read can prove is the SHAPE of the header, and that is the decision here.
      expect(panelSource).not.toContain("subagent-view-close");
      expect(panelSource).not.toContain("XIcon");
      expect(panelSource).toContain("<RightPaneCollapseButton onCollapse={onClose} />");
      // AT THE LEADING EDGE, BEFORE THE NAME: the row reads `way out . what this is`, and the
      // trailing end is kept for the way back to the list (ticket 03 of this feature).
      expect(panelSource.indexOf("<RightPaneCollapseButton")).toBeLessThan(
        panelSource.indexOf('data-slot="subagent-view-name"'),
      );
      // AND THE COLUMN CARRIES THE ID BOTH CONTROLS NAME. The mirror and the task pane are two
      // states of ONE element (the other suite reads the other state), which is the whole of
      // what `aria-controls` can be pointing at.
      expect(panelSource).toContain("id={RIGHT_PANE_ID}");
      // The one catalog key that went with the X: nothing names `subagentView.close` any more,
      // and the `i18n` suite's orphan check would fail on an entry left behind -- this says
      // WHICH way round the pair moved.
      for (const shell of [shellEn, shellZh]) {
        expect(Object.keys(JSON.parse(shell).subagentView)).toEqual(["title"]);
      }
    },
  },
  {
    name: "the-sidebars-subagent-block-is-gone-and-the-route-it-read-is-not",
    run: async () => {
      // THE RETIRED SCREEN LEFT NOTHING BEHIND THAT STILL COMPILES: no import, no mount,
      // and no exported row component waiting for a second reader.
      expect(sidebarSource).not.toContain("SubagentPanel");
      expect(sidebarSource).not.toContain("subagent-panel");
      expect(listSource).not.toContain("export const RunRows");
      // THE NAMES DID NOT MOVE: the definitions are drawn by the settings page, which is
      // where `.scratch/subagents` put them, and the shared range sentence lives on.
      expect(listSource).toContain("export const DefinitionRows");

      // THE KEYS ONLY THAT BLOCK USED GO WITH IT, in BOTH catalogs -- the two have to
      // say the same things (the `i18n` suite's own rule), so a key removed from one
      // side only is a failure there, and this is the check that the removal happened at
      // all rather than leaving four dead sentences in each file.
      for (const shell of [shellEn, shellZh]) {
        expect(shell).not.toContain('"runsEmpty"');
        expect(shell).not.toContain('"titleHint"');
        expect(shell).not.toContain('"changeInSettings"');
      }
      // AND WHAT THE PANEL SAYS IS IN BOTH, with the name interpolated.
      for (const shell of [shellEn, shellZh]) {
        expect(shell).toContain('"subagentView"');
        expect(shell).toContain("{{name}}");
      }

      // THE SERVER'S ANSWER IS NOT DELETED WITH ITS READER: `GET /api/subagents` still
      // reports the runs, and `subagent-list.tsx` says so where the code used to be --
      // an asymmetry that is a decision, not a missing wire.
      expect(listSource).toContain("GET /api/subagents");
      expect(listSource).toContain("RunRows");
    },
  },
  {
    name: "the-mirrors-header-holds-a-way-back-to-the-list-at-its-trailing-end",
    run: async () => {
      // THE OTHER HALF OF THE HEADER'S ARRANGEMENT, and a MOVED boundary rather than a new
      // one: ticket 01 kept the TRAILING end of this row free for exactly one thing (the case
      // above reads that keeping), and ticket 03 put it there. One control folds the whole
      // column -- the LEADING one -- and another steps back to the TASK LIST; two verbs, and
      // the same row does not say either twice.
      expect(panelSource).toContain("<RightPaneBackButton onBack={onBack} />");
      expect(panelSource.indexOf("<RightPaneBackButton")).toBeGreaterThan(
        panelSource.indexOf("<RightPaneCollapseButton"),
      );
      expect(panelSource.indexOf("<RightPaneBackButton")).toBeGreaterThan(
        panelSource.indexOf('data-slot="subagent-view-name"'),
      );
      // THE CONTROL ITSELF is the shared one from the column's toggle module, and its glyph
      // says back rather than close -- the same glyph-lie this file's other case refuses.
      expect(toggleSource).toContain("export const RightPaneBackButton");
      expect(toggleSource).toContain("<ArrowLeftIcon");
      // AND THE TWO DOORS GO DIFFERENT PLACES ON THE PAGE: the back control writes the TASK
      // VIEW, the collapse writes `null` (the column closes). Swapped, one of the two would be
      // a button that lies about where it goes.
      expect(appSource).toContain('onBack={() => setRightPane({ kind: "tasks" })}');
      expect(appSource).toContain("onClose={() => setRightPane(null)}");
      // THE WORDS LIVE IN BOTH CATALOGS, under the COLUMN's namespace and not the mirror's:
      // `subagentView` still holds exactly `title` (the case above pins that), and the way
      // back is a control of the column in whichever state it is drawing.
      expect((JSON.parse(shellEn) as { rightPane: { back: string } }).rightPane.back).toBe(
        "Back to the list",
      );
      expect((JSON.parse(shellZh) as { rightPane: { back: string } }).rightPane.back).toBe(
        "返回列表",
      );
    },
  },
];

export const subagentViewSuite: Suite = { name: "subagent-view", cases };