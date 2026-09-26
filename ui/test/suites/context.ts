// The context ring and its panel: how full the window is, what filled it, and -- more
// of them than you would expect -- the cases where a number is NOT there.
//
// THE PURE HALF ONLY, and that is a deliberate boundary. `contextCells` decides every
// string the ring and the panel say and every absence they keep absent, and it imports
// nothing at runtime (see lib/format.ts), so this driver can pin it without a browser.
// THE DRAWING IS NOT HERE: how the arcs sit on the circle, whether the ring hugs the
// model, whether the popover lands above the button -- a string render cannot see any
// of it, and the walkthrough in `.scratch/context-usage/evidence/` is where that is
// measured.
//
// THE OTHER END OF THE WIRE IS HERE TOO (the last case): a real run over real HTTP,
// folded by `GET /api/threads/<stem>/stats` and handed to the same function. That is
// what ties the server's section to the strings a person reads, and it is one case
// rather than one per absence because the absences are the server's fold's business
// (pinned in test/harness/edge/context_test.clj) -- what this side owns is what it
// does with the answer.
import { expect } from "vitest";

import { type Case, type Suite, postRun, script, threadId, url } from "../e2e";
import { translator } from "../support/locale";
import {
  type StatsPayload,
  contextCells,
  formatContextTokens,
} from "../../src/lib/format";

/// English is the language these words were first written in, and it stays the one the
/// shapes are read against; the Chinese half of each case is below it, pinning the
/// words AND the fact that the numbers are the same in both.
const en = translator("en");
const zh = translator("zh");

/// The endpoint the ring's numbers ride on, as this suite needs to name it.
async function statsOf(tid: string): Promise<StatsPayload> {
  const res = await fetch(`${url()}api/threads/${encodeURIComponent(tid)}/stats`);
  return (await res.json()) as StatsPayload;
}

/// The split, once the record's writer has finished with it.
///
/// THE WRITER IS A BEAT BEHIND ITS OWN LAST FRAME: a run's message tail lands on
/// `:run/done`, which is written after the RUN_FINISHED the client reacts to (see
/// harness.edge.http). So the first ask after a run can carry the vendor's share and
/// no buckets, and the composer's own answer to that is one more ask (a beat later,
/// and again when the panel is opened). This waits the way that does, rather than
/// pinning a moment -- what is being pinned is that the split DOES arrive.
async function statsWithSplit(tid: string): Promise<StatsPayload["context"]> {
  for (let attempt = 0; attempt < 20; attempt += 1) {
    const payload = await statsOf(tid);
    if ((payload.context?.parts ?? []).length > 0) return payload.context;
    await new Promise((resolve) => setTimeout(resolve, 100));
  }
  throw new Error("the record never reported the split");
}

/// A payload carrying a context section, spelled the way the wire spells it.
function withContext(context: StatsPayload["context"]): StatsPayload {
  return { turns: 1, incomplete: false, context };
}

/// The answer the reference draws: 29% of 262144, split three ways.
const THREE_BUCKETS = {
  usedTokens: 76300,
  windowTokens: 262144,
  percent: 29,
  parts: [
    { key: "system", tokens: 1800 },
    { key: "tools", tokens: 12900 },
    { key: "conversation", tokens: 61600 },
  ],
};

const cases: Case[] = [
  {
    name: "the-share-the-sizes-and-the-three-buckets",
    run: async () => {
      const cells = contextCells(withContext(THREE_BUCKETS), en);
      expect(cells?.label).toBe("Context used 29%");
      expect(cells?.percent).toBe(29);
      // THE SIZES READ LIKE THE REFERENCE: one decimal above a thousand, the trailing
      // `.0` trimmed -- and the window has no `~`, because it is the model's own
      // declaration rather than a bucket of an estimated split.
      expect(cells?.used).toBe("~76.3K");
      expect(cells?.window).toBe("262.1K");
      expect(cells?.parts.map((part) => [part.key, part.label, part.value])).toEqual([
        ["system", "System prompt", "~1.8K"],
        ["tools", "Tool definitions", "~12.9K"],
        ["conversation", "Conversation", "~61.6K"],
      ]);

      // THE SAME FOUR FACTS, SAID IN CHINESE: the words are the catalog's, the numbers
      // are not -- `76.3K` and `29%` are the same in both, because they are a
      // measurement rather than a sentence.
      const cellsZh = contextCells(withContext(THREE_BUCKETS), zh);
      expect(cellsZh?.label).toBe("上下文已用 29%");
      expect(cellsZh?.used).toBe("~76.3K");
      expect(cellsZh?.parts.map((part) => part.label)).toEqual(["系统提示词", "工具定义", "对话消息"]);
    },
  },
  {
    name: "the-magnitudes-the-numbers-are-written-in",
    run: async () => {
      // Bare under a thousand, one decimal above it, `M` above a million -- and a
      // whole thousand keeps no `.0`, because `1.8K` and `12.9K` are the precision
      // this number is worth and `262.0K` is a digit nobody read.
      expect(formatContextTokens(812)).toBe("812");
      expect(formatContextTokens(1800)).toBe("1.8K");
      expect(formatContextTokens(76_300)).toBe("76.3K");
      expect(formatContextTokens(262_144)).toBe("262.1K");
      expect(formatContextTokens(262_000)).toBe("262K");
      expect(formatContextTokens(1_048_576)).toBe("1M");
      expect(formatContextTokens(2_940_000)).toBe("2.9M");
    },
  },
  {
    name: "a-fraction-that-is-not-there-is-not-drawn",
    run: async () => {
      // A RING IS A FRACTION, so this function answers null for every shape that has
      // none -- and the ring and its panel are both gone, because the panel hangs off
      // the ring rather than off a rule of its own.
      expect(contextCells(null, en)).toBeNull();
      expect(contextCells({ turns: 3, incomplete: false }, en)).toBeNull();
      // A call that reported no prompt: no numerator.
      expect(contextCells(withContext({ windowTokens: 262_144 }), en)).toBeNull();
      // A model nobody declared a window for: no denominator, and NO PERCENTAGE
      // EITHER -- the server does not send one, and this side does not divide.
      expect(contextCells(withContext({ usedTokens: 76300 }), en)).toBeNull();
      expect(contextCells(withContext({ usedTokens: 76300, percent: 29 }), en)).toBeNull();
      // A session that never ran has no log at all, which is the same answer from the
      // other end: `statsFor` answers null and this function is handed null.
      expect(contextCells(null, zh)).toBeNull();
    },
  },
  {
    name: "buckets-that-are-missing-are-missing-on-their-own",
    run: async () => {
      // The run that produced these numbers is still being written: the share is a
      // measured fact and the split is not there yet. The ring is drawn; the bar and
      // the list are not.
      const inFlight = contextCells(withContext({ usedTokens: 500, windowTokens: 1000, percent: 50 }), en);
      expect(inFlight?.percent).toBe(50);
      expect(inFlight?.used).toBe("~500");
      expect(inFlight?.parts).toEqual([]);

      // A BUCKET NOBODY KNOWS IS DROPPED, not drawn as an empty row and not merged
      // into an `other` row: this side does not get to invent a bucket the server's
      // fold never reported.
      const unknown = contextCells(
        withContext({
          usedTokens: 100,
          windowTokens: 1000,
          percent: 10,
          parts: [
            { key: "system", tokens: 60 },
            { key: "preamble", tokens: 40 },
          ],
        }),
        en,
      );
      expect(unknown?.parts.map((part) => part.key)).toEqual(["system"]);
      expect(unknown?.parts[0].value).toBe("~60");
    },
  },
  {
    name: "a-real-run-fills-the-ring-from-its-own-record",
    run: async () => {
      // THE WIRE, ONCE. A tool round is TWO model calls, each reporting its own
      // usage -- so the share the ring shows is the LAST call's prompt, which is the
      // one that filled the window.
      script([
        {
          content: "",
          "tool-calls": [{ id: "c1", name: "no-such-tool", arguments: {} }],
          usage: { prompt_tokens: 1000, completion_tokens: 40, total_tokens: 1040 },
        },
        {
          content: "done",
          usage: { prompt_tokens: 1200, completion_tokens: 8, total_tokens: 1208 },
        },
      ]);
      const tid = threadId("context-ring");
      const resp = await postRun(tid, [
        { id: "u1", role: "user", content: "看看这个项目" },
      ]);
      expect(resp.status).toBe(200);
      await resp.text(); // drain: the run is over when the body is

      // The share is there as soon as the run's own lines are -- it is the vendor's
      // number and a window the scripted double declared (test/harness/fake.clj).
      const first = await statsOf(tid);
      expect(first.context?.usedTokens).toBe(1200);
      expect(first.context?.windowTokens).toBe(128_000);
      expect(first.context?.percent).toBe(1); // 1200 of 128000, rounded

      // And the split, which needs the run's message tail on disk.
      const context = await statsWithSplit(tid);
      const cells = contextCells({ turns: 1, incomplete: false, context }, en);
      expect(cells).not.toBeNull();
      expect(cells?.percent).toBe(context?.percent);
      expect(cells?.parts.reduce((sum, part) => sum + part.tokens, 0)).toBe(context?.usedTokens);
      // The three buckets are the record's own words, in the order the panel draws them.
      expect(cells?.parts.map((part) => part.key)).toEqual(["system", "tools", "conversation"]);
      expect(cells?.used).toBe("~1.2K");
      expect(cells?.window).toBe("128K");
    },
  },
];

export const contextSuite: Suite = {
  name: "context",
  cases,
};
