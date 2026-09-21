// WHEN A SESSION WAS LAST SENT TO, as a person reads it rather than as a number.
//
// ------------------------------------------------------------- what this is not
//
// IT IS NOT A DATE FORMATTER, and it is not a clock. It answers one question -- WHICH
// BUCKET does this instant fall in, counting back from now -- and nothing else. The
// words are the caller's (`components/assistant-ui/elements/thread-list.aui.tsx` holds
// the catalog), and so is the calendar: past a week the answer is `date`, and what
// `date` looks like in a language is `toLocaleDateString`'s business, asked by the
// thing that knows which language the page is speaking. That split is the same one
// `lib/session-title.ts` makes: this module imports NOTHING and answers with a value,
// so a suite can pin every boundary with literals instead of a rendered row.
//
// WHY A BUCKET RATHER THAN A STRING, which is the one design decision worth arguing:
// a string would have to be built here, and building it needs both a translator and a
// locale -- two things this module deliberately cannot reach. The caller has both. What
// it cannot work out for itself is where the boundaries are, and those are what is
// pinned here (`test/suites/relative-time.ts`).
//
// ------------------------------------------------------------ why relative at all
//
// THE LIST IS READ FOR ONE THING: "which of these was I just in". An absolute
// timestamp answers that badly -- two rows a minute apart read as two long strings to
// be compared character by character -- while `12 分钟` and `1 小时` are told apart at a
// glance. The absolute value is not thrown away; it moves into the row's `title`
// attribute, where it is exact and one hover away.

/// A second, a minute, an hour, a day: the units the ladder counts in.
const MINUTE = 60_000;
const HOUR = 60 * MINUTE;
const DAY = 24 * HOUR;

/// HOW LONG "RECENTLY" LASTS, in milliseconds. Past this the answer is a DATE, because
/// "8 天前" is a number nobody converts in their head while a date is one they place
/// immediately (the same reason a year is not written as 365).
///
/// THE BOUNDARY IS INCLUSIVE (`<=`): seven days ago is still `7 d`, eight is a date.
/// That is the owner's screenshot, and the reason it is written as a constant rather
/// than inline is that both the ladder and the suite name it.
export const RECENT_MAX = 7 * DAY;

/// WHICH BUCKET AN INSTANT FALLS IN, counting back from `now`.
///
/// `date` CARRIES THE INSTANT, not a formatted string: the caller draws it in the
/// reader's own calendar. Every other case carries the count the words will be
/// pluralized with -- i18next takes it as `count` and picks the form itself, which is
/// why the count is a number here and not a digit someone already turned into text.
export type RelativeAge =
  | { readonly kind: "justNow" }
  | { readonly kind: "minutes"; readonly count: number }
  | { readonly kind: "hours"; readonly count: number }
  | { readonly kind: "days"; readonly count: number }
  | { readonly kind: "date" };

/// THE LADDER, and it is arithmetic: `刚刚 / N 分钟 / N 小时 / N 天 / 日期`.
///
/// A FUTURE INSTANT IS `justNow`, and that is not sloppiness about clock skew: the
/// time comes from the server's clock and is drawn by a browser that may be a few
/// seconds behind it, so a session sent to one second ago can arrive looking like one
/// sent to "in 1 minute" -- and a row reading `-1 分钟` would look like a bug in the
/// list rather than the two clocks it actually is. Negative differences are therefore
/// clamped by the first branch, which is also the branch that covers "just sent".
export function relativeAge(sentAt: number, now: number): RelativeAge {
  const elapsed = now - sentAt;
  if (elapsed < MINUTE) return { kind: "justNow" };
  if (elapsed < HOUR) return { kind: "minutes", count: Math.floor(elapsed / MINUTE) };
  if (elapsed < DAY) return { kind: "hours", count: Math.floor(elapsed / HOUR) };
  if (elapsed <= RECENT_MAX) return { kind: "days", count: Math.floor(elapsed / DAY) };
  return { kind: "date" };
}
