// i18next, brought up once with the language the SERVER holds, before the first render.
//
// WHY A LIBRARY rather than a hand-rolled table (the owner's call, 2026-09-17):
// plurals, interpolation and a third language later are all solved there, and this
// repo would otherwise grow a small translation framework of its own. The cost is
// the repo's first new UI dependency in a long while, and it is paid here in one
// file rather than spread across the components.
//
// THE INIT IS NO LONGER A SIDE EFFECT OF IMPORT. The language lives in config.edn
// (`:ui :language`, resolved server-side -- see harness.infra.language), so there is
// nothing in the browser that can be computed synchronously: the value has to be
// fetched. `startLanguage` is that fetch plus the init, and `main.tsx` AWAITS IT
// BEFORE THE FIRST RENDER -- the alternative is one frame of raw keys
// (`view.conversation`) or of the wrong language, which is the whole failure this
// arrangement exists to remove. The cost is one round trip before anything is drawn,
// and it is written down rather than hidden.
//
// THE INIT ITSELF IS STILL SYNCHRONOUS (`initAsync: false`): the catalogs are bundled,
// so once the language is known there is nothing left to wait for. For the same reason
// there is no Suspense (`react.useSuspense: false`).
//
// <html lang> IS PART OF THE STATE, not decoration. It is what a screen reader picks a
// voice from and what the browser hyphenates by, so a page whose copy says one thing
// and whose `lang` says another is worse than an untranslated one. It is set at init
// AND on every change, from the same place the language itself changes, so the two
// cannot drift.
//
// WHAT THIS FILE DOES NOT DO: translate anything. It owns the mechanism; the words
// live in `locales/`, and each face of the page moves its own into them.
import i18n from "i18next";
import { initReactI18next } from "react-i18next";

import { DEFAULT_NAMESPACE, NAMESPACES, RESOURCES } from "./catalogs";
import { FALLBACK_LANGUAGE, SUPPORTED_LANGUAGES, type Language } from "./language";
import { fetchLanguage } from "./languageSetting";

/// The document says which language it is in, from the first paint and after every
/// switch.
function markDocument(language: string): void {
  document.documentElement.lang = language;
}

/// Read this home's language and start i18next on it. AWAITED BY THE ENTRY before the
/// root is created -- see the note at the top for why it cannot be an import side
/// effect.
export async function startLanguage(): Promise<void> {
  const language = await fetchLanguage();
  await i18n.use(initReactI18next).init({
    resources: RESOURCES,
    lng: language,
    fallbackLng: FALLBACK_LANGUAGE,
    supportedLngs: SUPPORTED_LANGUAGES,
    // `zh-CN` is a tag saying "Chinese" with more precision than the page has any use
    // for; this is what lets the base subtag decide instead of a miss.
    nonExplicitSupportedLngs: true,
    ns: NAMESPACES,
    defaultNS: DEFAULT_NAMESPACE,
    initAsync: false,
    interpolation: { escapeValue: false },
    react: { useSuspense: false },
  });
  markDocument(i18n.language);
  i18n.on("languageChanged", markDocument);
}

/// Switch the page to LANGUAGE. THE CALLER WRITES FIRST (`POST /api/language`), then
/// calls this -- so a switch that could not be saved is never shown as if it had been.
export async function applyLanguage(language: Language): Promise<void> {
  await i18n.changeLanguage(language);
}

export default i18n;
