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
import { translator } from "../support/locale";
import { type StatsPayload, formatBytes, formatMillis, formatTime, statsCells } from "../../src/lib/format";

/// English is the language these cells were first written in, and it stays the one
/// the shapes are read against; the Chinese half of each case is below it, pinning
/// the words AND the quantifier rules that differ.
const en = translator("en");
const zh = translator("zh");

/// The endpoint the strip reads, as this suite needs to name it.
async function statsOf(tid: string): Promise<{ status: number; body: StatsPayload }> {
  const res = await fetch(`${url()}api/threads/${encodeURIComponent(tid)}/stats`);
  const body = (await res.json().catch(() => ({}))) as StatsPayload;
  return { status: res.status, body };
}

/// THE ENDPOINT'S ANSWER ONCE THE RUN'S RECORD IS WHOLE, or whatever it last said when MS ran
/// out. A run is over when its body is drained, but the record's LAST LINE is written by the
/// harness on a schedule of its own -- so a fold read once, a moment too early, reports
/// `incomplete` with every other number already right. That was this case's one red on
/// 2026-09-23 (a full run on a loaded machine), and it is what this waits for: the record,
/// not the response.
async function wholeRecord(tid: string, ms = 10_000): Promise<{ status: number; body: StatsPayload }> {
  const deadline = Date.now() + ms;
  for (;;) {
    const answer = await statsOf(tid);
    if (answer.body.incomplete === false || Date.now() > deadline) return answer;
    await new Promise((resolve) => setTimeout(resolve, 100));
  }
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
      const payload: StatsPayload = {
        turns: 1,
        steps: 2,
        stepsWithUsage: 2,
        usage: { totalTokens: 2248, promptTokens: 2200, completionTokens: 48, cachedTokens: 2000 },
        cacheHitPercent: 91,
        outputTokensPerSecond: 242,
        incomplete: false,
      };
      expect(statsCells(payload, en)).toEqual({
        turns: "1 turn",
        steps: "2 steps",
        rate: "242 tok/s",
        total: "2k tok",
        cached: "91% cached",
      });

      // THE SAME FIVE FACTS, SAID IN CHINESE -- and this is the assertion that makes
      // the catalog the thing under test rather than a decoration: the same payload
      // through the same function has to come out in the other language, with the
      // units (which do not translate) unchanged and the words (which do) not.
      // `steps` is a count of MODEL CALLS, and `CONTEXT.md` says the Chinese word for
      // one is 模型调用 -- not 步, which is the synonym the glossary exists to forbid.
      expect(statsCells(payload, zh)).toEqual({
        turns: "1 轮",
        steps: "2 次模型调用",
        rate: "242 tok/s",
        total: "2k tok",
        cached: "91% 命中缓存",
      });

      // The quantifier is a real rule and it is DIFFERENT in each language: English
      // has a singular form and Chinese does not, so the same count reads two ways.
      const three: StatsPayload = { turns: 3, incomplete: false };
      expect(statsCells(three, en)?.turns).toBe("3 turns");
      expect(statsCells(three, zh)?.turns).toBe("3 轮");

      // And the magnitudes: bare under a thousand, whole thousands under a million,
      // one decimal above -- the same glance-worth precision `formatBytes` keeps.
      // These are the NUMBER's, not the language's, which is why both translators
      // answer identically.
      const tokens = (totalTokens: number): StatsPayload => ({
        turns: 1,
        usage: { totalTokens },
        incomplete: false,
      });
      expect(statsCells(tokens(409), en)?.total).toBe("409 tok");
      expect(statsCells(tokens(812_400), en)?.total).toBe("812k tok");
      expect(statsCells(tokens(2_940_000), en)?.total).toBe("2.9M tok");
      expect(statsCells(tokens(812_400), zh)?.total).toBe(statsCells(tokens(812_400), en)?.total);
    },
  },
  {
    name: "a-number-the-record-could-not-establish-is-not-drawn",
    run: async () => {
      // The whole point of the server's absences arriving as absences: a session
      // whose log predates the model-call lines has turns and NOTHING else, and the
      // strip must show that rather than a row of zeroes. AN ABSENT CELL IS ABSENT IN
      // BOTH LANGUAGES -- it is the string that has a language, never the null.
      const cells = statsCells({ turns: 4, incomplete: false }, en);
      expect(cells).toEqual({ turns: "4 turns", steps: null, rate: null, total: null, cached: null });
      expect(statsCells({ turns: 4, incomplete: false }, zh)).toEqual({
        turns: "4 轮",
        steps: null,
        rate: null,
        total: null,
        cached: null,
      });

      // A call that reported nothing: steps are there, the tokens are not.
      const partial = statsCells({ turns: 1, steps: 3, stepsWithUsage: 2, incomplete: false }, en);
      expect(partial?.steps).toBe("3 steps");
      expect(partial?.total).toBeNull();
      expect(partial?.cached).toBeNull();

      // Nothing at all: no session of numbers to report, and nothing to draw.
      expect(statsCells(null, en)).toBeNull();
      expect(statsCells({ turns: 0, incomplete: false }, en)).toBeNull();
    },
  },
  {
    name: "the-durations-and-the-timestamps-speak-both-languages",
    run: async () => {
      // THE BUCKETS ARE ONE FORMATTER'S (they used to be the tool card's, copied
      // there), so their words have to come from one place too. All four buckets, in
      // both languages: the sub-second one is a phrase, the other three are a number
      // with a unit around it, and `2m 15s` is the one that is not a suffix at all.
      expect(formatMillis(400, en)).toBe("<1s");
      expect(formatMillis(400, zh)).toBe("<1 秒");
      expect(formatMillis(1400, en)).toBe("1.4s");
      expect(formatMillis(1400, zh)).toBe("1.4 秒");
      expect(formatMillis(45_000, en)).toBe("45s");
      expect(formatMillis(45_000, zh)).toBe("45 秒");
      expect(formatMillis(135_000, en)).toBe("2m 15s");
      expect(formatMillis(135_000, zh)).toBe("2 分 15 秒");

      // THE UNITS DO NOT TRANSLATE, and these two assertions are here to say so out
      // loud: a byte size and a token count are the same string in both languages, so
      // there is no catalog entry for `B`/`KB`/`MB` or for the `k`/`M` of a token
      // count -- only one place to change each of them.
      expect(formatBytes(4096)).toBe("4 KB");
      expect(formatBytes(3 * 1024 * 1024)).toBe("3 MB");

      // AND THE TIMESTAMP IS THE ONE PLACE THE TWO ARE SPLIT: the timezone is the
      // machine's (so this assertion is written against a FIXED instant and reads it
      // back through the same locale the page would), while the punctuation is the
      // interface language's. Same millis, two languages, two punctuations -- which
      // is the whole point, and why the locale is a parameter and not a machine fact.
      const at = Date.UTC(2026, 8, 17, 6, 30);
      expect(formatTime(at, "en")).toBe(new Date(at).toLocaleString("en"));
      expect(formatTime(at, "zh")).toBe(new Date(at).toLocaleString("zh"));
      expect(formatTime(at, "zh")).not.toBe(formatTime(at, "en"));
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
      const resp = await postRun(tid, [
        { id: "u1", role: "user", content: "看看这个项目" },
      ]);
      expect(resp.status).toBe(200);
      await resp.text(); // drain: the run is over when the body is

      // WAITED FOR, NOT READ ONCE (see `wholeRecord`): the numbers below are a fold of the
      // whole record, and the record's last line lands after the response did.
      const { status, body } = await wholeRecord(tid);
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
      const cells = statsCells(body, en);
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
