// The key types, read from the English catalogs.
//
// THIS FILE IS THE POINT OF TYPING THE CATALOGS AT ALL: with `resources` declared
// here, `t("view.conversatoin")` is a compile error rather than a key that renders
// itself on screen, and a key that exists in Chinese only cannot be named from any
// call site. `npm run typecheck` is what enforces it, and it runs as part of
// `npm run build`.
//
// ENGLISH IS THE SHAPE (see `lib/catalogs.ts`): it is the fallback, so it is the one
// language that must be complete for a page to be readable.
//
// It has to be a `declare module` with an `import type` rather than a plain
// declaration file: the resource shape lives in the code, and typing it twice here
// is how the two would come apart. It also keeps this file emitting nothing.
import type { RESOURCES } from "./lib/catalogs";

declare module "i18next" {
  interface CustomTypeOptions {
    defaultNS: "shell";
    resources: (typeof RESOURCES)["en"];
  }
}
