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
import { elicitationSuite } from "./suites/elicitation";
import { framesSuite } from "./suites/frames";
import { skillsSuite } from "./suites/skills";
import { statsSuite } from "./suites/stats";
import { turnSuite } from "./suites/turn";
import { pickerSuite } from "./suites/picker";
import { turnsSuite } from "./suites/turns";

/// Every suite, in the order the runner reports them. A suite that is not listed
/// here is not run, so this is the one place a new one has to be added.
const SUITES: readonly Suite[] = [framesSuite, clientSuite, turnSuite, approvalSuite, skillsSuite, statsSuite, elicitationSuite, attachmentsSuite, turnsSuite, pickerSuite];

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
const EXPECTED_CASES = 31;

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
