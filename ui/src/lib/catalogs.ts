// The catalogs, and the one place a namespace is registered.
//
// IMPORTS JSON AND NOTHING ELSE -- no i18next, no React, no DOM -- for the same
// reason `format.ts` imports nothing: the suite reaches it by relative path
// (`test/suites/i18n.ts`), and the check it runs there is the one this whole
// feature rests on. Every key in one language exists in the other, with a non-empty
// value. That is not tidiness: i18next's fallback would quietly fill a missing
// Chinese entry with the English one, and a half-translated page is precisely what
// the feature exists to remove, so it has to fail as a test rather than look fine
// on a screen.
//
// THE LAYOUT IS ONE FILE PER FACE PER LANGUAGE (`locales/<language>/<namespace>.json`).
// A face is a place on the page -- the shell, the composer, the transcript, the
// settings panel -- chosen so that the work of translating one is a change to its
// own files, and so a reader looking for a string knows which file to open from
// where it is drawn.
//
// ADDING A FACE IS FOUR LINES ON THIS FILE (two imports, two entries), which makes
// this the one file two faces share. It is deliberately kept to four lines per face
// for that reason, and the faces are added in order rather than in parallel.
//
// KEYS ARE WRITTEN LITERALLY AT THE CALL SITE, never built from a template
// (`t("status." + x)` is not allowed anywhere in this repo). That is what lets the
// types below fail a key that does not exist, and it is what will let the last
// ticket find an entry nobody uses. A dynamic key defeats both.
import enApproval from "../locales/en/approval.json";
import enComposer from "../locales/en/composer.json";
import enElementsCard from "../locales/en/elements-card.json";
import enElementsThread from "../locales/en/elements-thread.json";
import enErrors from "../locales/en/errors.json";
import enFormat from "../locales/en/format.json";
import enSettings from "../locales/en/settings.json";
import enShell from "../locales/en/shell.json";
import enThread from "../locales/en/thread.json";
import enTrajectory from "../locales/en/trajectory.json";
import zhApproval from "../locales/zh/approval.json";
import zhComposer from "../locales/zh/composer.json";
import zhElementsCard from "../locales/zh/elements-card.json";
import zhElementsThread from "../locales/zh/elements-thread.json";
import zhErrors from "../locales/zh/errors.json";
import zhFormat from "../locales/zh/format.json";
import zhSettings from "../locales/zh/settings.json";
import zhShell from "../locales/zh/shell.json";
import zhThread from "../locales/zh/thread.json";
import zhTrajectory from "../locales/zh/trajectory.json";

import type { Language } from "./language";

/// Every catalog, by language and then by namespace.
///
/// ENGLISH IS THE SHAPE both languages are held to: it is the one the key types are
/// read from (`src/i18next.d.ts`) and the fallback, so a key that exists only in
/// Chinese is a key no call site can name.
export const RESOURCES = {
  en: { approval: enApproval, composer: enComposer, "elements-card": enElementsCard, "elements-thread": enElementsThread, errors: enErrors, format: enFormat, settings: enSettings, shell: enShell, thread: enThread, trajectory: enTrajectory },
  zh: { approval: zhApproval, composer: zhComposer, "elements-card": zhElementsCard, "elements-thread": zhElementsThread, errors: zhErrors, format: zhFormat, settings: zhSettings, shell: zhShell, thread: zhThread, trajectory: zhTrajectory },
} as const satisfies Record<Language, Record<string, unknown>>;

/// Derived from the tables rather than listed again: a second list is a second
/// thing to keep in step, and the failure it would produce -- a namespace loaded
/// but not enabled -- is invisible on screen.
export type Namespace = keyof (typeof RESOURCES)["en"];

export const NAMESPACES = Object.keys(RESOURCES.en) as Namespace[];

/// The namespace a bare `t("some.key")` is read from. The shell is the frame every
/// other face is drawn inside, which makes it the one whose keys are hardest to
/// confuse with anybody else's.
export const DEFAULT_NAMESPACE: Namespace = "shell";
