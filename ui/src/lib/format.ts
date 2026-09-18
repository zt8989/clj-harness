// How the sidebar's disk facts, the composer's session numbers, and every elapsed
// time on the page are written for a person.
//
// All of them answer a question a reader actually has: "which one was this", "is
// it big enough to be worth opening", "how fast and how much did this session
// run". None is a measurement, which is why nothing here carries more precision
// than a glance needs -- a size to the byte and a timestamp to the millisecond
// would both be noise, and the raw numbers stay available to anyone who wants
// them by looking at the file or asking the endpoint.
//
// THE STRIP'S CELLS ARE BUILT HERE TOO (statsCells), and that is deliberate: they
// are strings for a person, and this module imports NOTHING AT RUNTIME (the
// `TFunction` below is a TYPE import, which the compiler erases) -- so the one place
// that decides what "2.9M tok" says is also a place a test can reach without a
// browser. Which cells exist is the same kind of decision: a number the record could
// not establish is LEFT OUT rather than written as zero (see harness.edge.stats),
// and this is where that becomes visible.
//
// ------------------------------------------------------------------ the language
//
// LANGUAGE ARRIVES AS AN ARGUMENT, never as module state. A module-level "current
// language" would be a second copy of what i18next already holds, it would make
// every one of these functions untestable from a plain call, and it would let the
// numbers change under a caller that had already read them.
//
// TWO KINDS OF WORD, AND ONLY ONE OF THEM GOES THROUGH THE CATALOG:
//
//   * UNITS DO NOT. `B` / `KB` / `MB` and the `k` and `M` of `formatTokens` are the
//     same string in both languages the page speaks, and a round trip through a
//     translation table would only create a second place to change one unit.
//     (The strip's five cells DO come from the catalog -- see `statsCells` -- because
//     each of them is a phrase, and the units inside it are interpolated into it.)
//   * PHRASES AND QUANTIFIERS DO. `<1s`, `2m 15s` and `3 次模型调用` are language.
//
// A MISSING NUMBER IS THE CALLER'S SENTENCE, NOT THIS MODULE'S. A null log size and a
// session that never ran used to be answered here ("no log yet", "never run"), which
// made this module decide when an absence becomes a phrase. The caller is the one
// that knows WHICH absence it is looking at -- one of them is not about bytes or
// time at all -- so `formatBytes` and `formatTime` take a number, and the caller
// says what it has instead when it has nothing.
import type { TFunction } from "i18next";

import type { Language } from "./language";

/// The translator these functions take, PINNED TO THIS MODULE'S FACE.
///
/// i18next's `TFunction` is branded with the namespace it was bound to, so a bare
/// `TFunction` here would mean "whatever the default namespace is" and would accept a
/// shell translator by mistake -- while a `string` key would lose the key check
/// inside this file entirely. Naming `format` keeps both: only the format catalog's
/// keys compile here, and only a format translator can be passed in.
type Translate = TFunction<"format">;

/// Bytes in the units a person reads: whole KB under a megabyte, whole MB above.
/// Zero is spelled out rather than rounded away, because a zero-byte log is a
/// fact worth noticing.
export function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${Math.round(bytes / 1024)} KB`;
  return `${Math.round(bytes / (1024 * 1024))} MB`;
}

/// A size in megabytes to ONE DECIMAL, with a trailing `.0` dropped so a cap reads
/// as the "2 MB" a person would write.
///
/// IT IS A SECOND PRECISION ON PURPOSE, and the reason is a sentence rather than a
/// taste: a refusal says "this image is 3.1 MB; the limit is 2 MB", and the two
/// numbers in it have to be tellable apart. At whole-megabyte rounding (which is what
/// `formatBytes` above does, correctly, for a size you glance at) a 2.1 MB file would
/// be refused with "this image is 2 MB; the limit is 2 MB" -- which reads as a bug.
/// The precision belongs to the sentence that needs it, and it lives HERE, next to
/// the other one, so the units are still named in one file.
export function formatMegabytes(bytes: number): string {
  const mb = (bytes / (1024 * 1024)).toFixed(1);
  return `${mb.endsWith(".0") ? mb.slice(0, -2) : mb} MB`;
}

/// The log file's mtime, in the reader's own timezone and the INTERFACE language's
/// punctuation.
///
/// Those two are deliberately separate and this is the one place the split shows: the
/// timezone is a fact about where the reader is sitting (so it comes from the
/// machine, exactly as the magnitudes above do), while how a date is punctuated is a
/// fact about the language the page is speaking. Epoch millis are for machines; a
/// session list is read by a person looking for "the one from this morning".
export function formatTime(ms: number, locale: Language): string {
  return new Date(ms).toLocaleString(locale);
}

/// Tokens in the units a person reads: bare under a thousand, thousands under a
/// million, one decimal above. The same discipline as `formatBytes` -- and the
/// reason it matters here is that this number is a SUM over every call in the
/// session (each call re-sends the whole context), so it grows past anything a
/// reader would count digit by digit.
export function formatTokens(n: number): string {
  if (n < 1000) return `${n}`;
  if (n < 1000 * 1000) return `${Math.round(n / 1000)}k`;
  return `${(n / 1000 / 1000).toFixed(1)}M`;
}

/// Elapsed milliseconds, in the same buckets upstream's duration uses: sub-second
/// is "<1s", then one decimal, then whole seconds, then minutes and seconds.
///
/// IT LIVES HERE RATHER THAN IN THE TOOL CARD because the trajectory draws the same
/// spans in a second place, and the tool card used to keep its OWN copy of these
/// buckets -- so the same 1.4s could be written two ways by two files. One
/// formatter, and the buckets are the tool card's on purpose: this is a glance, not a
/// stopwatch, and the raw millisecond marks are on the wire for anyone who needs
/// them.
export function formatMillis(ms: number, t: Translate): string {
  if (ms < 1000) return t("duration.subSecond");
  const seconds = ms / 1000;
  if (seconds < 10) {
    return t("duration.seconds", { value: (Math.floor(seconds * 10) / 10).toFixed(1) });
  }
  if (seconds < 60) return t("duration.seconds", { value: Math.floor(seconds) });
  return t("duration.minutes", {
    minutes: Math.floor(seconds / 60),
    seconds: Math.floor(seconds % 60),
  });
}

/// The wire's answer, named. EVERY FIELD BUT `turns` IS OPTIONAL AND STAYS THAT
/// WAY: the server omits what the record could not establish (`steps` on a log
/// that predates the model-call lines, `usage` when no call reported any,
/// `cacheHitPercent` when no call reported the pair), so `undefined` here means
/// "not reported" and must never be turned into 0.
export interface StatsPayload {
  turns: number;
  steps?: number;
  stepsWithUsage?: number;
  usage?: {
    totalTokens?: number;
    promptTokens?: number;
    completionTokens?: number;
    cachedTokens?: number;
  };
  cacheHitPercent?: number;
  outputTokensPerSecond?: number;
  incomplete: boolean;
  /// HOW FULL THE MODEL'S WINDOW IS, beside the session's totals above.
  ///
  /// IT IS NOT A SIXTH CELL OF THE STRIP: it answers a different question -- what the
  /// next call is about to be sent, rather than what every call so far has cost -- and
  /// it is drawn in a different place (the ring beside the model). It rides on this
  /// payload because it is folded from the same record, in the same read, at the same
  /// moment: two endpoints would be two clocks for one session's log. See
  /// harness.edge.context and .scratch/context-usage/spec.md.
  context?: ContextPayload;
}

/// What the strip draws: one string per cell that EXISTS. `null` means draw
/// nothing at all -- there is no session of numbers to show, or nothing worth a
/// line yet.
export interface StatsCells {
  turns: string;
  steps: string | null;
  rate: string | null;
  total: string | null;
  cached: string | null;
}

/// The payload -> the cells, with the absences kept absent.
///
/// NOTHING IS INVENTED FOR A MISSING NUMBER, and the strip is not drawn at all
/// for an empty session: a row of placeholders over a conversation that has not
/// started is furniture, and "0 tok" would be a claim the record does not make.
///
/// THE QUANTIFIER IS i18next's `count`, NOT A HAND-ROLLED `n === 1 ? …`. The two
/// languages the page speaks have genuinely different rules -- English has a
/// singular form and Chinese does not -- so which form to use is a fact about the
/// language, and it belongs in the catalogs beside the words, where the parity check
/// can see it.
export function statsCells(payload: StatsPayload | null, t: Translate): StatsCells | null {
  if (payload === null) return null;
  if (payload.turns === 0 && payload.steps === undefined) return null;

  const total = payload.usage?.totalTokens;
  return {
    turns: t("stats.turns", { count: payload.turns }),
    steps: payload.steps === undefined ? null : t("stats.steps", { count: payload.steps }),
    rate:
      payload.outputTokensPerSecond === undefined
        ? null
        : t("stats.rate", { value: payload.outputTokensPerSecond }),
    total: total === undefined ? null : t("stats.total", { value: formatTokens(total) }),
    cached:
      payload.cacheHitPercent === undefined
        ? null
        : t("stats.cached", { value: payload.cacheHitPercent }),
  };
}

/// The context section of the payload, named -- the ring's answer.
///
/// EVERY KEY IS OPTIONAL FOR THE SAME REASON `StatsPayload`'s are: `usedTokens` is
/// absent when no call ever reported a prompt, `windowTokens` and `percent` are absent
/// when nothing declared a window for that call, and `parts` is absent while the run
/// that produced the numbers is still being written (see harness.edge.context). A
/// `undefined` here means the record did not say, and it must never become a zero.
export interface ContextPayload {
  usedTokens?: number;
  windowTokens?: number;
  /// The whole-number share of the window in use. COMPUTED SERVER-SIDE and never
  /// divided here: the numerator and the denominator are one call's, and the client
  /// is not the place that knows which call (see harness.edge.context).
  percent?: number;
  parts?: { key: string; tokens: number }[];
}

/// One bucket of the prompt, as the panel and the ring draw it: what it is called
/// and how big it was.
export interface ContextPart {
  key: string;
  label: string;
  /// With the `~`: see `contextCells`.
  value: string;
  /// The count the bar and the ring divide by -- the four numbers are all there is,
  /// and they add up to `usedTokens` (the server's own guarantee).
  tokens: number;
}

/// What the ring and its panel draw. `percent` is the number (the arc needs one),
/// `label` is the sentence both surfaces say, and `parts` may be empty.
export interface ContextCells {
  percent: number;
  label: string;
  used: string;
  window: string;
  parts: ContextPart[];
}

/// Tokens as this feature writes them: one decimal above a thousand, the trailing
/// `.0` trimmed, and `M` above a million.
///
/// IT IS NOT `formatTokens` AND THAT IS DELIBERATE. The strip's number is a session
/// total in the millions, where a decimal is noise and `2.9M` / `812k` is the whole
/// story; these numbers are one call's prompt against a window in the thousands, and
/// `1.8K` beside `12.9K` beside `262K` is what makes three buckets comparable at a
/// glance. Both are the same KIND of number, so both are written here, one above the
/// other, rather than in the two components that need them.
///
/// `K` and `M` are units and do not go through the catalog (see the header).
export function formatContextTokens(n: number): string {
  const trim = (s: string): string => (s.endsWith(".0") ? s.slice(0, -2) : s);
  if (n < 1000) return `${n}`;
  if (n < 1000 * 1000) return `${trim((n / 1000).toFixed(1))}K`;
  return `${trim((n / 1000 / 1000).toFixed(1))}M`;
}

/// The catalog's names for the three buckets, by the key the record reports.
///
/// A KEY NOBODY KNOWS IS DROPPED, not drawn as an empty row and not folded into an
/// `other` bucket: the server is the one that decides what filled a prompt, and this
/// side adding a bucket of its own would be this side guessing at what it was sent.
const PART_LABELS: Record<string, "context.system" | "context.tools" | "context.conversation"> = {
  system: "context.system",
  tools: "context.tools",
  conversation: "context.conversation",
};

/// The payload -> what the ring and its panel draw, with the absences kept absent.
///
/// NULL IS THE WHOLE ANSWER when the ring cannot be drawn, and it means exactly one
/// thing: there is no fraction to draw. A session that never ran has no log, a call
/// that reported no prompt has no numerator, and a model nobody declared a window for
/// has no denominator -- in all three the honest drawing is none at all, because a
/// ring is a fraction, and drawing one without a fraction is drawing a number that
/// does not exist. (The panel hangs off the ring, so its absence follows from this one
/// rule rather than from a second one.)
///
/// `parts` MAY STILL BE EMPTY with a fraction drawn: that is a run whose numbers are in
/// the record and whose message side is not written yet, and the ring shows the share
/// with no buckets in it until the next ask.
///
/// THE TILDE IS ON EVERY NUMBER THAT DESCRIBES THE PROMPT -- the total and each
/// bucket -- and not on the window. The buckets are an estimate (the server divides a
/// measured total by measured sizes), and the total is written the same way because
/// the four numbers are one thought: mixing a measured figure with three estimated
/// ones inside one sentence is a distinction nobody reading a ring should have to
/// make. The window is the model's own declaration and stands alone.
export function contextCells(payload: StatsPayload | null, t: Translate): ContextCells | null {
  const context = payload?.context;
  if (context === undefined) return null;
  const { usedTokens, windowTokens, percent } = context;
  if (usedTokens === undefined || windowTokens === undefined || percent === undefined) {
    return null;
  }

  return {
    percent,
    label: t("context.used", { value: percent }),
    used: `~${formatContextTokens(usedTokens)}`,
    window: formatContextTokens(windowTokens),
    parts: (context.parts ?? []).flatMap((part) => {
      const key = PART_LABELS[part.key];
      return key === undefined
        ? []
        : [
            {
              key: part.key,
              label: t(key),
              value: `~${formatContextTokens(part.tokens)}`,
              tokens: part.tokens,
            },
          ];
    }),
  };
}
