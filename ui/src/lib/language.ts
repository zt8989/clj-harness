// Which language this page speaks, as arithmetic: what the browser asks for, what
// this browser was told last time, and which of the two wins.
//
// ZERO IMPORTS, like `format.ts` and `turns.ts`, so the suite can pin the chain
// without a browser: `test/suites/i18n.ts` imports this by relative path and calls
// `resolveLanguage` with literal tags. Resolution is exactly the kind of thing that
// looks obviously right and is wrong at the edges -- `zh-TW` is Chinese, `zh_TW` is
// the same tag spelled the old way, and a remembered value that no longer names a
// language the UI can draw has to fall THROUGH to the browser rather than pin the
// page to something it cannot say.
//
// THE LIST IS CLOSED: two languages, and a third is another `locales/` directory
// plus one line here. `lib/i18n.ts` hands this same list to i18next, so the two
// cannot disagree about what the page can speak.

export const SUPPORTED_LANGUAGES = ["en", "zh"] as const;

export type Language = (typeof SUPPORTED_LANGUAGES)[number];

/// What a browser asking for something the page does not speak gets.
export const FALLBACK_LANGUAGE: Language = "en";

/// Where this browser's answer is remembered. THE KEY IS SPELLED HERE AND NOWHERE
/// ELSE: whoever writes the preference and whoever reads it have to agree on the
/// string, and two literals in two files is exactly how they stop agreeing.
export const LANGUAGE_STORAGE_KEY = "clj-harness.language";

/// Whether a value off the wire is one the page can speak.
///
/// A TYPE GUARD, because the values that arrive at a language switch come from the
/// DOM as `string`, and a cast would be the one place this file's closed list stops
/// being enforced. It is also the honest answer to "what if the list changes under a
/// stored preference" -- see `resolveLanguage` below, which is built on the same
/// question.
export function isLanguage(value: unknown): value is Language {
  return SUPPORTED_LANGUAGES.some((language) => language === value);
}

/// One of the page's languages, from a value that may be anything.
///
/// FOR THE CALL SITES THAT ALREADY HAVE AN ANSWER and only need it typed: i18next's
/// `i18n.language` is the live language, but it is typed `string` because the library
/// cannot know this repo's list. This narrows it -- and normalizes a regional tag on
/// the way, which is what a `<html lang>` or a stored value would carry. Anything
/// unreadable becomes the fallback, the same answer a first visit gets.
export function asLanguage(value: unknown): Language {
  return (typeof value === "string" ? baseLanguage(value) : null) ?? FALLBACK_LANGUAGE;
}

/// A language tag reduced to one the page speaks, or null.
///
/// The BASE SUBTAG is what decides. `zh-CN`, `zh-TW` and the legacy `zh_TW` are all
/// Chinese, because there is one Chinese here and nothing yet needs to tell those
/// apart; a tag nobody has (`fr`, `de`) is not an error, it is null. Null is the
/// answer that lets the caller keep looking, which is why this returns null rather
/// than the fallback: "I do not know this" and "the answer is English" are
/// different facts, and only the caller knows whether there is anywhere else to
/// look.
function baseLanguage(tag: string | null | undefined): Language | null {
  if (typeof tag !== "string") return null;
  const base = tag.trim().toLowerCase().split(/[-_]/)[0];
  return SUPPORTED_LANGUAGES.find((language) => language === base) ?? null;
}

/// What the browser asks for, when this browser was never told otherwise.
///
/// `navigator.language` is the one-tag answer and is what the page uses; the whole
/// preference list would be a search through languages for one that fits, which is
/// worth doing only once there is a reason to prefer a second choice over English.
export function languageFromNavigator(tag: string | null | undefined): Language {
  return baseLanguage(tag) ?? FALLBACK_LANGUAGE;
}

/// The chain: what this browser was told last time, then what the browser asks for,
/// then English.
///
/// A REMEMBERED VALUE THAT NO LONGER RESOLVES FALLS THROUGH, and that is the whole
/// reason this is one function rather than a lookup plus a default: if the list of
/// languages ever drops one, the browsers that chose it must land on their own
/// browser's answer -- the same place a first visit lands -- rather than on a
/// language the page can no longer draw. A hand-edited or truncated localStorage
/// value is the same case and takes the same path.
export function resolveLanguage(
  remembered: string | null | undefined,
  navigatorTag: string | null | undefined,
): Language {
  return baseLanguage(remembered) ?? languageFromNavigator(navigatorTag);
}
