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
import { injectionSuite } from "./suites/injections";
import { recordSuite } from "./suites/record";

/// Every suite, in the order the runner reports them. A suite that is not listed
/// here is not run, so this is the one place a new one has to be added.
const SUITES: readonly Suite[] = [framesSuite, clientSuite, turnSuite, approvalSuite, skillsSuite, statsSuite, contextSuite, elicitationSuite, attachmentsSuite, turnsSuite, injectionSuite, pickerSuite, i18nSuite, restoreSuite, concurrentSuite, sidebarSuite, recordSuite];

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
/// 52 -> 54: the `record` suite's two -- a record with nothing to say (nothing drawn,
/// and a state this client does not know is silence rather than the wire value on
/// screen) and a degraded one RENDERED in both languages, carrying the writer's own
/// reason and the plural of how much is waiting. It exists for the same reason the
/// `sidebar` suite does: a sentence that reaches the screen is the one thing a green
/// tree could not see.
const EXPECTED_CASES = 54;

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
