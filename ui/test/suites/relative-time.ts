// HOW LONG AGO A SESSION WAS LAST SENT TO, as arithmetic: where the ladder's steps are,
// and nothing else.
//
// PURE, in the shape `session-title.ts` established: `src/lib/relative-time.ts` imports
// nothing at all, so every boundary can be pinned with two numbers and no clock, no
// browser and no locale. That is the whole reason the module answers a BUCKET instead of
// a sentence -- a string would need a translator and a calendar, and neither can be a
// literal here.
//
// WHAT THIS SUITE CANNOT SEE: whether the row says the right words for a bucket (that is
// `suites/sidebar.tsx`, which renders the row) and what a date looks like in a language
// (`toLocaleDateString`'s, deliberately not this module's). The two files split the claim
// at exactly that line: here the boundaries, there the sentences.
import { expect } from "vitest";

import { type Case, type Suite } from "../e2e";
import { RECENT_MAX, relativeAge } from "../../src/lib/relative-time";

const SECOND = 1000;
const MINUTE = 60 * SECOND;
const HOUR = 60 * MINUTE;
const DAY = 24 * HOUR;

/// A FIXED "NOW", so every case is arithmetic over literals rather than a race with the
/// clock. The value is arbitrary; the distances from it are not.
const NOW = Date.UTC(2026, 8, 21, 12, 0);

const cases: Case[] = [
  {
    name: "the-ladder-counts-up-and-then-gives-a-date",
    run: async () => {
      // THE FIVE BUCKETS, at the boundaries that matter -- each one is a place two
      // branches meet, which is where an off-by-one lives. `justNow` is INCLUSIVE of its
      // last millisecond and so is every step below it: 59:59.999 is still "just now",
      // 1:00:00 exactly is "1 min".
      expect(relativeAge(NOW, NOW)).toEqual({ kind: "justNow" });
      expect(relativeAge(NOW - SECOND, NOW)).toEqual({ kind: "justNow" });
      expect(relativeAge(NOW - MINUTE + 1, NOW)).toEqual({ kind: "justNow" });
      expect(relativeAge(NOW - MINUTE, NOW)).toEqual({ kind: "minutes", count: 1 });
      expect(relativeAge(NOW - 56 * MINUTE, NOW)).toEqual({ kind: "minutes", count: 56 });
      expect(relativeAge(NOW - HOUR + 1, NOW)).toEqual({ kind: "minutes", count: 59 });
      expect(relativeAge(NOW - HOUR, NOW)).toEqual({ kind: "hours", count: 1 });
      expect(relativeAge(NOW - 23 * HOUR, NOW)).toEqual({ kind: "hours", count: 23 });
      expect(relativeAge(NOW - DAY, NOW)).toEqual({ kind: "days", count: 1 });

      // THE LAST STEP, and it is INCLUSIVE: seven days is still a count, the moment past
      // it is a date. A reader converts "7 天" in their head; "8 天" they do not, which is
      // what the number is for.
      expect(relativeAge(NOW - RECENT_MAX, NOW)).toEqual({ kind: "days", count: 7 });
      expect(relativeAge(NOW - RECENT_MAX - 1, NOW)).toEqual({ kind: "date" });
      expect(relativeAge(NOW - 200 * DAY, NOW)).toEqual({ kind: "date" });
    },
  },
  {
    name: "a-clock-that-runs-ahead-is-not-a-time-in-the-future",
    run: async () => {
      // THE SERVER'S CLOCK WRITES the number and THIS MACHINE'S reads it, and the two can
      // disagree by seconds. `-1 分钟` is what the naive subtraction produces, and it reads
      // as a broken list rather than as two clocks -- so anything not yet a minute old,
      // in either direction, is "just now".
      expect(relativeAge(NOW + SECOND, NOW)).toEqual({ kind: "justNow" });
      expect(relativeAge(NOW + 30 * MINUTE, NOW)).toEqual({ kind: "justNow" });

      // AND THE CLAMP IS THE FIRST BRANCH, not a special case bolted on: every negative
      // difference is under a minute, so it is caught before any division happens.
      expect(relativeAge(NOW + DAY, NOW)).toEqual({ kind: "justNow" });
    },
  },
  {
    name: "the-counts-are-whole-and-floored",
    run: async () => {
      // NO DECIMALS EVER REACH THE WORDS. The buckets are a glance, so a minute and a half
      // is "1 min" and 1.9 hours is "1 h" -- the same discipline `lib/format.ts` keeps for
      // sizes and durations, and the reason the count is computed here rather than left to
      // the caller (a caller dividing on its own is a second ladder).
      expect(relativeAge(NOW - 90 * SECOND, NOW)).toEqual({ kind: "minutes", count: 1 });
      expect(relativeAge(NOW - 90 * MINUTE, NOW)).toEqual({ kind: "hours", count: 1 });
      expect(relativeAge(NOW - 36 * HOUR, NOW)).toEqual({ kind: "days", count: 1 });

      // AND THE BUCKET CARRIES A NUMBER, not a digit string: i18next pluralizes on the
      // count it is given, and a pre-formatted "1" would be the caller deciding grammar.
      const age = relativeAge(NOW - 3 * HOUR, NOW);
      expect(age.kind === "hours" && typeof age.count).toBe("number");
    },
  },
];

export const relativeTimeSuite: Suite = { name: "relative-time", cases };
