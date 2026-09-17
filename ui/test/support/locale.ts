// A translator for the suites, wired to the REAL catalogs.
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
// `supportedLngs`, `initAsync: false` -- and an instance is created per call rather
// than shared, so one case cannot leave a language behind for the next.
import i18next, { type TFunction } from "i18next";

import { NAMESPACES, RESOURCES, type Namespace } from "../../src/lib/catalogs";
import { FALLBACK_LANGUAGE, SUPPORTED_LANGUAGES, type Language } from "../../src/lib/language";

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
  const instance = i18next.createInstance();
  void instance.init({
    resources: RESOURCES,
    lng: language,
    fallbackLng: FALLBACK_LANGUAGE,
    supportedLngs: SUPPORTED_LANGUAGES,
    nonExplicitSupportedLngs: true,
    ns: NAMESPACES,
    defaultNS: namespace,
    initAsync: false,
    interpolation: { escapeValue: false },
  });
  return instance.getFixedT(language, namespace);
}
