// The suites' i18n: a translator for the pure modules, and a real INSTANCE for the
// one case that renders a component. Both wired to the REAL catalogs.
//
// The pure modules take a `TFunction` (that is how they stay runtime-zero-import),
// so a suite has to hand them one -- and the only translator worth handing them is
// the real one. A hand-written fake would pin the formatters against strings no user
// will ever see, and it would go on passing after a catalog was edited.
//
// `lib/i18n.ts` CANNOT BE IMPORTED HERE: it loads `react-i18next` (which is React)
// and touches `document` at module load, and this run has no browser. So the
// instance below is built from `lib/catalogs.ts`, which imports nothing but JSON. The
// settings that decide these answers are copied from `lib/i18n.ts` -- `fallbackLng`,
// `supportedLngs`, `initAsync: false`, `react.useSuspense: false` -- and one is
// created per call rather than shared, so one case cannot leave a language behind
// for the next.
import i18next, { type TFunction } from "i18next";

import { DEFAULT_NAMESPACE, NAMESPACES, RESOURCES, type Namespace } from "../../src/lib/catalogs";
import { FALLBACK_LANGUAGE, SUPPORTED_LANGUAGES, type Language } from "../../src/lib/language";

/// One initialized instance, from the real catalogs -- the machinery both exports
/// below are cut from.
///
/// AN INSTANCE PER CALL rather than one shared: a case that switches language (or that
/// renders in the other one) cannot leave that behind for the next case.
function instance(language: Language, namespace: Namespace) {
  const i18n = i18next.createInstance();
  void i18n.init({
    resources: RESOURCES,
    lng: language,
    fallbackLng: FALLBACK_LANGUAGE,
    supportedLngs: SUPPORTED_LANGUAGES,
    nonExplicitSupportedLngs: true,
    ns: NAMESPACES,
    defaultNS: namespace,
    initAsync: false,
    interpolation: { escapeValue: false },
    // NOT SUBSCRIBED TO, but read by `useTranslation` -- and with the default
    // (true) the hook SUSPENDS while a namespace is not loaded, which a render to
    // a string cannot do. Same setting, same reason, as `lib/i18n.ts`.
    react: { useSuspense: false },
  });
  return i18n;
}

/// A translator bound to one language and one namespace.
///
/// The namespace is a `Namespace`, not a `string`, and that is what makes the return
/// type carry it: i18next brands its translator with the namespace it was bound to, so
/// a caller that asked for `format` gets a translator whose keys are `format`'s -- the
/// same type `useTranslation("format")` hands a component.
export function translator<N extends Namespace = "format">(
  language: Language,
  namespace: N = "format" as N,
): TFunction<N> {
  return instance(language, namespace).getFixedT(language, namespace);
}

/// A REAL instance, for a case that RENDERS a component.
///
/// `translator` above is a fixed `t`, which is all a pure function needs. A component
/// goes through `useTranslation`, which asks for the instance ITSELF -- so this is the
/// same instance the translator is cut from, handed to `I18nextProvider` instead (see
/// suites/sidebar.tsx). Its default namespace is the bare `t("some.key")` one, because
/// that is the call a component in the shell makes.
///
/// STILL NOT `lib/i18n.ts`: that one reads `localStorage`, `navigator` and `document`
/// as it initializes, and none of the three exists in this run.
export function renderI18n(language: Language): ReturnType<typeof i18next.createInstance> {
  return instance(language, DEFAULT_NAMESPACE);
}
