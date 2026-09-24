// Which languages this page speaks, and how a value that arrives from anywhere becomes
// one of them.
//
// ZERO IMPORTS, like `format.ts` and `turns.ts`, so the suite can pin the reduction
// without a browser: `test/suites/i18n.ts` imports this by relative path.
//
// THE LANGUAGE IS NOT THIS PAGE'S PREFERENCE. It is the HARNESS's: it lives in
// config.edn's `:ui :language` and is resolved on the server -- config, then the OS's
// own language, then the terminal's, then English (harness.infra.language). The page
// only READS the answer (`GET /api/language`) and narrows it to one of the two, which
// is what the functions below do. Nothing here decides a language from the browser's
// preferences: that chain moved to the server so the interface and the model cannot
// disagree about which language they are speaking.
//
// THE LIST IS CLOSED: two languages, and a third is another `locales/` directory plus
// one line here. `lib/i18n.ts` hands this same list to i18next, so the two cannot
// disagree about what the page can speak.

export const SUPPORTED_LANGUAGES = ["en", "zh"] as const;

export type Language = (typeof SUPPORTED_LANGUAGES)[number];

/// What the page speaks when nothing told it anything -- a route that did not answer,
/// a value nobody can read. English is the floor, the same one the server's chain ends
/// on.
export const FALLBACK_LANGUAGE: Language = "en";

/// Whether a value off the wire is one the page can speak.
///
/// A TYPE GUARD, because the values that arrive at a language switch come from the DOM
/// as `string`, and a cast would be the one place this file's closed list stops being
/// enforced.
export function isLanguage(value: unknown): value is Language {
  return SUPPORTED_LANGUAGES.some((language) => language === value);
}

/// One of the page's languages, from a value that may be anything.
///
/// THIS IS THE REDUCTION THE WHOLE PAGE RESTS ON. The server answers a tag; i18next's
/// `i18n.language` is typed `string` for the same reason it cannot know this list; a
/// `<select>` hands over a `string`. This narrows all of them -- and normalizes a
/// regional tag on the way (`zh-Hans-CN` is Chinese), because a value written into
/// config.edn by hand may carry one. Anything unreadable becomes the fallback, which is
/// what a first load with no answer from the server gets.
export function asLanguage(value: unknown): Language {
  return (typeof value === "string" ? baseLanguage(value) : null) ?? FALLBACK_LANGUAGE;
}

/// A language tag reduced to one the page speaks, or null.
///
/// The BASE SUBTAG is what decides. `zh-CN`, `zh-TW` and the legacy `zh_TW` are all
/// Chinese, because there is one Chinese here and nothing yet needs to tell those
/// apart; a tag nobody has (`fr`, `de`) is not an error, it is null. Null is the answer
/// that lets the caller keep looking, which is why this returns null rather than the
/// fallback: "I do not know this" and "the answer is English" are different facts.
function baseLanguage(tag: string | null | undefined): Language | null {
  if (typeof tag !== "string") return null;
  const base = tag.trim().toLowerCase().split(/[-_]/)[0];
  return SUPPORTED_LANGUAGES.find((language) => language === base) ?? null;
}
