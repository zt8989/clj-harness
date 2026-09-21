// The vitest driver: one harness for the run, one place a suite is registered,
// and a guard against the failure mode a green run can hide.
//
// Why the suites are plain modules under test/suites/ rather than test files of
// their own, when vitest would happily run four files:
//
// 1. ONE harness per run. vitest gives each test FILE its own module graph, so
//    four suite files would spawn four Clojure servers. Here `beforeAll` runs
//    once, the facts are handed to test/e2e.ts once, and every case shares it.
// 2. ONE registration list. A suite missing from SUITES below does not run --
//    the same shape the ClojureScript runner had, where a suite absent from its
//    list silently did not run.
//
// That silent drop is the failure mode this file exists to make impossible, in
// both its forms -- a suite that contributes no cases, and a suite dropped from
// the list. The old bridge guarded only the TOTAL (`deftest` dropped by the
// compiler -> 0 cases -> a green run that proved nothing); the checks below guard
// each suite's non-emptiness AND pin the total, and they run at COLLECTION time,
// so a throw here fails the file before a single case starts -- there is no window
// in which "no cases" is reported as "passed".
import { afterAll, beforeAll, describe, expect, test } from "vitest";

import { configure, type Suite } from "./e2e";
import { startHarness } from "./support/harness";
import { approvalSuite } from "./suites/approval";
import { attachmentsSuite } from "./suites/attachments";
import { clientSuite } from "./suites/client";
import { contextSuite } from "./suites/context";
import { elicitationSuite } from "./suites/elicitation";
import { framesSuite } from "./suites/frames";
import { i18nSuite } from "./suites/i18n";
import { skillsSuite } from "./suites/skills";
import { statsSuite } from "./suites/stats";
import { turnSuite } from "./suites/turn";
import { pickerSuite } from "./suites/picker";
import { restoreSuite } from "./suites/restore";
import { turnsSuite } from "./suites/turns";
import { concurrentSuite } from "./suites/concurrent";
import { sidebarSuite } from "./suites/sidebar";
import { sessionTitleSuite } from "./suites/session-title";
import { relativeTimeSuite } from "./suites/relative-time";
import { sidebarRowsSuite } from "./suites/sidebar-rows";
import { injectionSuite } from "./suites/injections";
import { recordSuite } from "./suites/record";
import { windowSuite } from "./suites/window";

/// Every suite, in the order the runner reports them. A suite that is not listed
/// here is not run, so this is the one place a new one has to be added.
///
/// THIS LIST IS THE MERGED ONE, and both sides of the merge grew it: `brand-header`
/// appended `sessionTitleSuite`, `relativeTimeSuite` and `sidebarRowsSuite` after the
/// `sidebar` suite, and `sessions-live-on-the-server` appended `recordSuite` and
/// `windowSuite`. Neither side touched the other's additions, which is why the resolved
/// list is a concatenation rather than a choice.
const SUITES: readonly Suite[] = [framesSuite, clientSuite, turnSuite, approvalSuite, skillsSuite, statsSuite, contextSuite, elicitationSuite, attachmentsSuite, turnsSuite, injectionSuite, pickerSuite, i18nSuite, restoreSuite, concurrentSuite, sidebarSuite, sessionTitleSuite, relativeTimeSuite, sidebarRowsSuite, recordSuite, windowSuite];

/// The number of cases the suites are expected to contribute, pinned. The count
/// is a contract, not bookkeeping: it is what makes a suite silently dropping out
/// of the list above -- or losing its cases -- fail the run instead of quietly
/// shrinking a green one. Bump it deliberately when a case is genuinely added or
/// removed.
///
/// 11 -> 14: the `skills` suite's three cases (both layers from the real roots; a
/// shadowed name listed once; asking changes nothing).
/// 14 -> 18: the `stats` suite's four (the strip's cells, the numbers it leaves
/// out, a real run folded by the endpoint, and a session that has not run).
/// 18 -> 24: the `elicitation` suite's five -- one through the whole loop (a
/// server's question parks the run and the answer finishes it) and four on the
/// form itself (the four kinds; answers keep the declared type; a field nobody
/// expected is kept and named; an empty or odd schema is an empty form).
/// 24 -> 26: the `attachments` suite's two -- the model rule (the same judgement
/// as the server's `undeclared-input`, and the one place the two could disagree)
/// and the 2 MB cap on source bytes, boundary included. Both are pure.
/// 29 -> 31: the `picker` suite's two -- what a query keeps (the label, the hint,
/// the group, case and whitespace) and how runs of one group are grouped. Both pure.
/// 26 -> 29: the `turns` suite's three -- the boundary of a turn (a run of adjacent
/// assistant messages), when it counts as settled, and the summary line's two
/// numbers. All three are pure arithmetic over a literal message list.
/// 31 -> 33: the `i18n` suite's two -- the language chain (remembered, then the
/// browser's tag, then English, falling through anything unusable) and the parity
/// between the two catalogs (the same keys, every value a non-empty string). Both
/// pure; this is the pair that makes a half-translated page a red run.
/// 33 -> 34: the `stats` suite's third -- the durations and the timestamps in BOTH
/// languages, plus the two units that deliberately do not translate. Ticket 02 merged
/// the four copies of the size/duration words into `lib/format.ts`, and this is the
/// case that pins what they now say.
/// 34 -> 35: the `i18n` suite's third -- every catalog entry is NAMED by some source
/// file, the other direction of the parity check (a mistyped key that was then added
/// to the catalog to satisfy the types, an entry left behind by a deleted row). It
/// reads the sources through `import.meta.glob`; the case itself says what a grep
/// costs and which way it errs.
/// 35 -> 36: the `concurrent` suite's one -- two thread ids running at once, with
/// each session's own log read back off disk (its `input` row, its terminal frame)
/// and rebuilt through the server. It is the backend half of parallel sessions: the
/// server always allowed this and nothing had ever asked it to.
/// 36 -> 39: the `sidebar` suite's three -- the session row RENDERED (react-dom/server)
/// and read as text: its id line, the second line that says which absence it is looking
/// at, and the parked word beside the id rather than inside a line that truncates. This
/// is the suite that exists because the id line went blank in the i18n merge and a green
/// tree could not see it -- see suites/sidebar.tsx and vitest.config.ts.
/// 44 -> 46: the `restore` suite's two -- the page's memory of which session it is in
/// (the one key, the conditional forget, and a storage that throws rather than answers)
/// and the single question it asks of a listing. Both pure; the reload itself is a
/// browser's, and it is the walkthrough in `.scratch/session-after-refresh/`.
/// 47 -> 50: the `injections` suite's three -- what an injected-context card says (its
/// title is the tag the block arrived with, its size is BYTES), how a rebuilt
/// conversation gets its cards back, and the adapter contract the whole feature rests on
/// (`toAgUiMessages` never sends a `data` part back).
/// 46 -> 47: the `sidebar` suite's fourth -- the label a row can wear, which the flat
/// archived block needs (a filed-away session has to say which project it came from,
/// since nothing groups it any more). Same reason the other three are there: it is a
/// thing the row SAYS, and only a render can see it.
/// 50 -> 52: the `sidebar` suite's fifth and sixth -- the two controls that fold the
/// sidebar away and bring it back (`components/sidebar-toggle.tsx`): the word each one
/// says in both languages, and the two `aria-*` facts that make them one verb (one
/// `aria-controls`, and an `aria-expanded` each). The single reference a render in this
/// run cannot reach -- the sidebar's own element -- is read as source in the same case.
/// 52 -> 69: THE `brand-header` SIDE OF THIS MERGE, counted from the same base as the
/// other chain below. Two of them are the new
/// `session-title` suite, which pins a session's title as arithmetic over literal
/// messages -- the first text the user said, what whitespace becomes, the 60-code-point
/// clip (an emoji is two UTF-16 units, so the cut is by character), the fallback word in
/// both languages, and the `· clj-harness` tail -- because a title is derived, not
/// stored, and a browser tab has no ellipsis. The third is the `sidebar` suite's: the
/// brand row's own name and mark, and (read as source, like the `id` beside it) that the
/// collapse control left the header row and now ends the brand row.
/// 55 -> 56: the rail (`sidebar-rail`) -- the mark alone with no wordmark, and the list
/// hidden-but-mounted, both read out of the sidebar's source because this run cannot
/// render that file at all. What the rail LOOKS like is the browser walkthrough's.
/// 60 -> 62: two of the `sidebar` suite's, for the store-backed row: one case was
/// REWRITTEN rather than added (the row is one line now, so the case that read its second
/// line asserts that line's ABSENCE instead), and the two new ones pin the relative age
/// as the row words it -- both languages -- and the indent slot the spinner goes in.
/// 62 -> 65: the `relative-time` suite's three -- the ladder's boundaries (where two
/// branches meet, which is where an off-by-one hides), a clock running ahead (the server
/// writes the number, this machine reads it), and the counts being whole and floored.
/// Pure: `lib/relative-time.ts` imports nothing, which is why it answers a bucket and the
/// row answers the words.
/// 65 -> 69: the `sidebar-rows` suite's four, for "一个项目（或任务那一块）最多画 5 行":
/// the fold itself and the count it reports, the row being read never being the one that
/// folds away, a block opened by hand versus one the current session outranks, and the
/// list not being copied when nothing is folded. Same reason to be pure: `lib/sidebar-rows.ts`
/// imports nothing, and the two callers (`components/sidebar.tsx`) cannot be imported here
/// at all -- so where the control lands and whether clicking it draws the rows are the
/// browser walkthrough's.
/// 52 -> 65: THE `sessions-live-on-the-server` SIDE, and it starts from the same 52 as the
/// chain above -- the two sides were counted independently, so these two totals are two
/// branches of one arithmetic, not a continuation of each other.
/// 52 -> 54: the `record` suite's two -- a record with nothing to say (nothing drawn,
/// and a state this client does not know is silence rather than the wire value on
/// screen) and a degraded one RENDERED in both languages, carrying the writer's own
/// reason and the plural of how much is waiting. It exists for the same reason the
/// `sidebar` suite does: a sentence that reaches the screen is the one thing a green
/// tree could not see.
/// 54 -> 56: the `client` suite's two, and they are ticket 03's UI half. ONE reads the
/// wire: a run's body carries `append` (this action's own entries) and NOT the
/// accumulated `messages` nor a client `runId` -- the second run's `append` holds only
/// the second question, which is the whole change. THE OTHER reads the other end of the
/// same contract: `startTask` with nothing to name comes back with an id the SERVER
/// minted, listed as a conversation this home keeps and usable for a run -- where a
/// page-made id used to be quietly registered by the run edge.
/// 56 -> 65: the `window` suite's nine, and they are ticket 06's UI half -- the rules
/// that turn feed frames into a copy, the control that asks for older history, the
/// sentences owed when the answer is not simply "more messages", and the arithmetic
/// that keeps a reader's place when a page is prepended. The three answers that are not
/// "append" are the reason it exists: a hole, a reopen and a copy that is ahead of the
/// conversation are all SILENT failures when they go wrong, and silence is not
/// something a later test can notice.
/// 69 + 13 = 82: THE RESOLVED TREE. The addition is exact because neither side rewrote a
/// case the other added: `brand-header`'s seventeen (69 - 52) and `main`'s thirteen
/// (65 - 52) land in different files -- the sidebar's own suites on one side, the record
/// and the window on the other -- so the two counts add. What the MERGE itself changed
/// in these suites is one rule rather than a case count: the page speaks a live title
/// only for a session it MINTED (`app.tsx`'s `minted`), because a window's first user
/// message is not the conversation's first -- and this run cannot reach that rule at all
/// (`components/sidebar.tsx` cannot be imported here, so the `sidebar` suite reads it as
/// text and the behaviour itself is the browser walkthrough's).
/// 82 -> 83: `injections`' fourth -- the OPENING ENTRY, which is one message with two
/// readings (a card part and the text the model read). It pins both halves of
/// `.scratch/session-opening` that only this side can see: the card replaces the text
/// rather than joining it, and the copy that goes back out is the text under the same
/// id, which is what makes the server drop the repeat.
const EXPECTED_CASES = 83;

let total = 0;
for (const suite of SUITES) {
  if (suite.cases.length === 0) {
    throw new Error(`suite "${suite.name}" contributed no cases -- a green run that proves nothing`);
  }
  total += suite.cases.length;
}
if (total !== EXPECTED_CASES) {
  throw new Error(
    `the suites contributed ${total} cases, expected ${EXPECTED_CASES} -- a suite may have been dropped from SUITES; update EXPECTED_CASES if the change is intended`,
  );
}

let harness: Awaited<ReturnType<typeof startHarness>> | null = null;

beforeAll(async () => {
  harness = await startHarness();
  // Both homes go to the suites: a case that wants a system-level skill, or an
  // instruction file, writes into the OS home first -- and a case that wants to
  // read a session log looks under the config root.
  configure({
    url: harness.url,
    scriptPath: harness.scriptPath,
    home: harness.home,
    userHome: harness.userHome,
  });
  console.log(`harness for this run: ${harness.url}`);
});

afterAll(async () => {
  if (harness) await harness.stop();
});

for (const suite of SUITES) {
  describe(suite.name, () => {
    for (const c of suite.cases) {
      test(c.name, async () => {
        await c.run();
        // A case that ran but asserted nothing is EMPTY, not passing -- the same
        // distinction the old bridge drew from its pass counter. vitest's own
        // `hasAssertions` is that guard: without it a body that stopped calling
        // `expect` (an early return, a renamed helper) would report green.
        expect.hasAssertions();
      });
    }
  });
}
