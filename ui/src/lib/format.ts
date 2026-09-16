// How the sidebar's two disk facts and the composer's five session numbers are
// written for a person.
//
// All of them answer a question a reader actually has: "which one was this", "is
// it big enough to be worth opening", "how fast and how much did this session
// run". None is a measurement, which is why nothing here carries more precision
// than a glance needs -- a size to the byte and a timestamp to the millisecond
// would both be noise, and the raw numbers stay available to anyone who wants
// them by looking at the file or asking the endpoint.
//
// THE STRIP'S CELLS ARE BUILT HERE TOO (statsCells), and that is deliberate: they
// are strings for a person, and this module imports NOTHING -- no React, no alias,
// no DOM -- so the one place that decides what "2.9M tok" says is also a place a
// test can reach without a browser. Which cells exist is the same kind of
// decision: a number the record could not establish is LEFT OUT rather than
// written as zero (see harness.edge.stats), and this is where that becomes
// visible.

/// Bytes in the units a person reads: whole KB under a megabyte, whole MB above.
/// Zero is spelled out rather than rounded away, because a zero-byte log is a
/// fact worth noticing.
export function formatBytes(bytes: number | null): string {
  if (bytes === null) return "no log yet";
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${Math.round(bytes / 1024)} KB`;
  return `${Math.round(bytes / (1024 * 1024))} MB`;
}

/// The log file's mtime, in the reader's own timezone and punctuation. Epoch
/// millis are for machines; a session list is read by a person looking for "the
/// one from this morning".
export function formatTime(ms: number | null): string {
  if (ms === null) return "never run";
  return new Date(ms).toLocaleString();
}

// ------------------------------------------------- the composer's session numbers

/// One number, one word for how many of a thing it is. English, because that is
/// what the UI says (the docs and the tickets are the Chinese half of this repo),
/// and two forms only: these are counts of turns and of model calls, and no
/// language this UI speaks needs a third.
export function plural(n: number, one: string, many: string): string {
  return `${n} ${n === 1 ? one : many}`;
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
export function statsCells(payload: StatsPayload | null): StatsCells | null {
  if (payload === null) return null;
  if (payload.turns === 0 && payload.steps === undefined) return null;

  const total = payload.usage?.totalTokens;
  return {
    turns: plural(payload.turns, "turn", "turns"),
    steps: payload.steps === undefined ? null : plural(payload.steps, "step", "steps"),
    rate:
      payload.outputTokensPerSecond === undefined
        ? null
        : `${payload.outputTokensPerSecond} tok/s`,
    total: total === undefined ? null : `${formatTokens(total)} tok`,
    cached: payload.cacheHitPercent === undefined ? null : `${payload.cacheHitPercent}% cached`,
  };
}
