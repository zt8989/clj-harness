// i18next, initialized once, before the first render.
//
// WHY A LIBRARY rather than a hand-rolled table (the owner's call, 2026-09-17):
// plurals, interpolation and a third language later are all solved there, and this
// repo would otherwise grow a small translation framework of its own. The cost is
// the repo's first new UI dependency in a long while, and it is paid here in one
// file rather than spread across the components.
//
// THE INIT IS SYNCHRONOUS (`initAsync: false`), and that is not a detail: the
// catalogs are bundled, so there is nothing to wait for, and an asynchronous init
// would let the first paint draw raw keys (`view.conversation`) before the tables
// landed -- a flash of exactly the failure this feature exists to remove. For the
// same reason there is no Suspense (`react.useSuspense: false`): there is no
// loading to suspend on.
//
// <html lang> IS PART OF THE STATE, not decoration. It is what a screen reader
// picks a voice from and what the browser hyphenates by, so a page whose copy says
// one thing and whose `lang` says another is worse than an untranslated one. It is
// set at init AND on every change, from the same place the language itself changes,
// so the two cannot drift.
//
// WHAT THIS FILE DOES NOT DO: translate anything. It owns the mechanism; the words
// live in `locales/`, and each face of the page moves its own into them.
import i18n from "i18next";
import { initReactI18next } from "react-i18next";

import { DEFAULT_NAMESPACE, NAMESPACES, RESOURCES } from "./catalogs";
import {
  FALLBACK_LANGUAGE,
  LANGUAGE_STORAGE_KEY,
  SUPPORTED_LANGUAGES,
  resolveLanguage,
  type Language,
} from "./language";

/// What this browser was told last time, if it was told anything and can still be
/// read.
///
/// A REMEMBERED PREFERENCE THAT CANNOT BE READ IS NOT AN ERROR -- localStorage
/// throws outright in a browser that forbids storage (a hardened profile, a
/// sandboxed frame), and that browser is simply a first visit. Failing here would
/// take the page down over a preference.
function rememberedLanguage(): string | null {
  try {
    return window.localStorage.getItem(LANGUAGE_STORAGE_KEY);
  } catch {
    return null;
  }
}

/// Switch the page's language, and remember it.
///
/// The write is deliberately best-effort and its failure is silent: NOT REMEMBERING
/// IS A SMALLER FAILURE THAN NOT SWITCHING. A browser that refuses storage still
/// changes language for the session; it just forgets on the next load, which is the
/// same thing a first visit does.
export function setLanguage(language: Language): void {
  try {
    window.localStorage.setItem(LANGUAGE_STORAGE_KEY, language);
  } catch {
    // See above: the switch still happens.
  }
  void i18n.changeLanguage(language);
}

void i18n.use(initReactI18next).init({
  resources: RESOURCES,
  lng: resolveLanguage(rememberedLanguage(), navigator.language),
  fallbackLng: FALLBACK_LANGUAGE,
  supportedLngs: SUPPORTED_LANGUAGES,
  // `zh-CN` is a browser saying "Chinese" with more precision than the page has any
  // use for; this is what lets the base subtag decide instead of a miss.
  nonExplicitSupportedLngs: true,
  ns: NAMESPACES,
  defaultNS: DEFAULT_NAMESPACE,
  initAsync: false,
  interpolation: { escapeValue: false },
  react: { useSuspense: false },
});

/// The document says which language it is in, from the first paint and after every
/// switch.
function markDocument(language: string): void {
  document.documentElement.lang = language;
}

markDocument(i18n.language);
i18n.on("languageChanged", markDocument);

export default i18n;
