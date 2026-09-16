// The status strip under the composer: the five numbers, and — more of them than
// you would expect — the cases where a number is NOT there.
//
// TWO HALVES, ON PURPOSE. The first two cases are pure: they hand `statsCells` the
// payloads the server can answer with and pin what the strip draws, including the
// absences the record is entitled to have. The last two drive a REAL run over real
// HTTP and fold the endpoint's answer into the same cells, which is what ties the
// wire to the strings a person reads.
//
// WHAT THIS SUITE CANNOT SEE: the strip's markup. vitest.config.ts explains why —
// this driver imports no React and has no DOM, so the drawing is measured in a real
// browser instead (see .scratch/composer-status/evidence/). Importing the pure
// formatter is fine and deliberate: `src/lib/format.ts` imports nothing at all, so
// it needs neither the `@` alias nor a browser, and the one place that decides what
// "2.9M tok" says is worth a test.
import { expect } from "vitest";

import { type Case, type Suite, postRun, script, threadId, url } from "../e2e";
import { type StatsPayload, statsCells } from "../../src/lib/format";

/// The endpoint the strip reads, as this suite needs to name it.
async function statsOf(tid: string): Promise<{ status: number; body: StatsPayload }> {
  const res = await fetch(`${url()}api/threads/${encodeURIComponent(tid)}/stats`);
  const body = (await res.json().catch(() => ({}))) as StatsPayload;
  return { status: res.status, body };
}

/// A vendor's usage, spelled the way the wire spells it.
function usage(prompt: number, completion: number, cached: number): Record<string, number> {
  return {
    prompt_tokens: prompt,
    completion_tokens: completion,
    total_tokens: prompt + completion,
    "prompt_tokens_details": { cached_tokens: cached } as unknown as number,
  };
}

const cases: Case[] = [
  {
    name: "the-cells-are-what-the-record-answered",
    run: async () => {
      const cells = statsCells({
        turns: 1,
        steps: 2,
        stepsWithUsage: 2,
        usage: { totalTokens: 2248, promptTokens: 2200, completionTokens: 48, cachedTokens: 2000 },
        cacheHitPercent: 91,
        outputTokensPerSecond: 242,
        incomplete: false,
      });
      expect(cells).toEqual({
        turns: "1 turn",
        steps: "2 steps",
        rate: "242 tok/s",
        total: "2k tok",
        cached: "91% cached",
      });

      // The plural is a real rule, not decoration: a session that has run one turn
      // reads "1 turn".
      expect(statsCells({ turns: 3, incomplete: false })?.turns).toBe("3 turns");

      // And the magnitudes: bare under a thousand, whole thousands under a million,
      // one decimal above -- the same glance-worth precision `formatBytes` keeps.
      expect(statsCells({ turns: 1, usage: { totalTokens: 409 }, incomplete: false })?.total).toBe("409 tok");
      expect(statsCells({ turns: 1, usage: { totalTokens: 812_400 }, incomplete: false })?.total).toBe("812k tok");
      expect(statsCells({ turns: 1, usage: { totalTokens: 2_940_000 }, incomplete: false })?.total).toBe("2.9M tok");
    },
  },
  {
    name: "a-number-the-record-could-not-establish-is-not-drawn",
    run: async () => {
      // The whole point of the server's absences arriving as absences: a session
      // whose log predates the model-call lines has turns and NOTHING else, and the
      // strip must show that rather than a row of zeroes.
      const cells = statsCells({ turns: 4, incomplete: false });
      expect(cells).toEqual({ turns: "4 turns", steps: null, rate: null, total: null, cached: null });

      // A call that reported nothing: steps are there, the tokens are not.
      const partial = statsCells({ turns: 1, steps: 3, stepsWithUsage: 2, incomplete: false });
      expect(partial?.steps).toBe("3 steps");
      expect(partial?.total).toBeNull();
      expect(partial?.cached).toBeNull();

      // Nothing at all: no session of numbers to report, and nothing to draw.
      expect(statsCells(null)).toBeNull();
      expect(statsCells({ turns: 0, incomplete: false })).toBeNull();
    },
  },
  {
    name: "the-endpoint-the-strip-reads-folds-the-record",
    run: async () => {
      const tid = threadId("stats-strip");
      // A tool round is TWO model calls, each reporting its own usage -- so the
      // numbers a person reads are a sum over calls, which is the shape this whole
      // feature is about.
      script([
        {
          content: "",
          "tool-calls": [{ id: "c1", name: "no-such-tool", arguments: {} }],
          usage: usage(1000, 40, 900),
        },
        { content: "done", usage: usage(1200, 8, 1100) },
      ]);
      const resp = await postRun(tid, "stats-strip-run-1", [
        { id: "u1", role: "user", content: "看看这个项目" },
      ]);
      expect(resp.status).toBe(200);
      await resp.text(); // drain: the run is over when the body is

      const { status, body } = await statsOf(tid);
      expect(status).toBe(200);
      expect(body.turns).toBe(1);
      expect(body.steps).toBe(2);
      expect(body.stepsWithUsage).toBe(2);
      expect(body.usage).toEqual({
        totalTokens: 2248,
        promptTokens: 2200,
        completionTokens: 48,
        cachedTokens: 2000,
      });
      expect(body.cacheHitPercent).toBe(91); // 2000 / 2200, rounded
      expect(body.incomplete).toBe(false);

      // The wire's answer, as the strip draws it. The rate is the one number this
      // case cannot pin by hand (it is completion tokens over the two calls' real
      // durations), so it is asserted as a shape.
      const cells = statsCells(body);
      expect(cells?.turns).toBe("1 turn");
      expect(cells?.steps).toBe("2 steps");
      expect(cells?.total).toBe("2k tok");
      expect(cells?.cached).toBe("91% cached");
      expect(cells?.rate).toMatch(/^\d+ tok\/s$/);
    },
  },
  {
    name: "a-session-that-has-never-run-has-no-numbers-yet",
    run: async () => {
      // The ordinary state of a fresh conversation: no log, so nothing to fold, so
      // nothing to draw. A 404 here is an ANSWER, not a failure -- the strip shows
      // its absence by not being there.
      const { status, body } = await statsOf(threadId("stats-fresh"));
      expect(status).toBe(404);
      expect((body as { error?: string }).error).toMatch(/no log for thread/);
    },
  },
];

export const statsSuite: Suite = { name: "stats", cases };
