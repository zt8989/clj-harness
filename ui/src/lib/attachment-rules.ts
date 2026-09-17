// The two rules a file has to pass to become an attachment, and the sentences
// that say which one it failed.
//
// ------------------------------------------------- ONE RULE, TWO READERS
//
// `acceptsImages` is the SAME judgement as the server's
// `harness.edge.ag_ui/undeclared-input`, and it has to be, because the two ways of
// disagreeing both hurt:
//
//   - STRICTER THAN THE SERVER (reading "declared nothing" as "takes nothing")
//     turns away a configuration the server has never objected to. "没有声明就是
//     没有承诺" is that rule's own wording.
//   - LOOSER THAN THE SERVER lets the message out and the run comes back
//     RUN_ERROR, with the composer already cleared: the words somebody typed and
//     the picture they pasted are both gone. That is the failure
//     `approval-gate.tsx`'s `isSendDisabled` exists to prevent, for the same
//     reason (a refusal the client can see beats a silent one it cannot).
//
// ------------------------------------------------- ABSENT IS NOT EMPTY
//
// Only two states reach for an image, and they are NOT the same state:
//
//     `input` key absent  ->  the model declared nothing, so nothing is guarded
//     `input: []`         ->  the model declared an empty set: it takes nothing,
//                             and an image aimed at it IS refused
//
// The server measures it exactly that way -- `undeclared-input` with `#{}`
// returns [:image], with nil returns [] -- and the wire keeps the two apart
// (providers/wire renders a declared empty set as [] and omits the key when
// there is none). So this does too.
//
// ------------------------------------------------------------ WHY IT IS BARE
//
// This module IMPORTS NOTHING that needs a browser: no React, no `@` alias, no DOM.
// That is what lets vitest reach it by relative path (see vitest.config.ts, which
// deliberately loads no alias), and a rule this load-bearing is worth a test that
// needs no browser. `format.ts` is the precedent -- and since that one is now the
// single place a byte size is written, the size sentence below goes through it
// rather than carrying a second copy of the units (there used to be three copies of
// `B`/`KB`/`MB` in this UI; see .scratch/ui-i18n/spec.md, ticket 02).
//
// THE SENTENCES BELOW ARE THIS SIDE'S OWN, and that is why they take a translator.
// A refusal is decided HERE, before any request is sent, so there is no server
// sentence to prefer -- the rule the fetch helpers keep (use `body.error` verbatim
// when the server spoke, and only otherwise say our own words) has nothing to read
// here. The model id and the declared modalities are interpolated as they arrived,
// never translated; only the words around them are.
import type { TFunction } from "i18next";

import { formatMegabytes } from "./format";

/// The translator these sentences are worded through, PINNED TO THE FACE THEY
/// BELONG TO.
///
/// i18next's `TFunction` is branded with the namespace it was bound to, so a bare
/// `TFunction` here would mean "whatever the default namespace is" and would accept a
/// shell translator by mistake -- while a `string` key would lose the key check
/// inside this file entirely. Naming `errors` keeps both: only the errors catalog's
/// keys compile here, and only an errors translator can be passed in (the same
/// choice `format.ts` makes for its own face).
type Translate = TFunction<"errors">;

/// The most SOURCE-FILE BYTES a single attachment may carry.
///
/// The unit is the source file and not the base64 length or the decoded pixels,
/// because the size of the file in somebody's hands is the only one of the three
/// they can see and the only one they can do anything about.
///
/// WHY THERE IS A CAP AT ALL: the AG-UI client resends the whole conversation
/// every round, so an attachment is re-recorded in every round's `input` line --
/// a 2 MB screenshot is ~2.7 MB of base64 per round, and twenty rounds of it is
/// fifty-odd megabytes of record for one picture.
export const ATTACHMENT_MAX_BYTES = 2 * 1024 * 1024;

/// Does the session's model take images?
///
/// DECLARED is `/api/model`'s `:input` as the wire spells it (["image", "text"]),
/// or undefined/null when the model declared nothing -- see this module's header
/// for why those two are not the same answer as an empty array.
export function acceptsImages(
  declared: readonly string[] | null | undefined,
): boolean {
  if (declared === null || declared === undefined) return true;
  return declared.includes("image");
}

/// The sentence for a model that does not take images, or null when this one
/// does. It names the model, what it declared, and the way out -- the same three
/// things the server's own refusal names, so a reader who meets either sentence
/// has been told the same thing.
///
/// A MODEL WITH NO ID GETS THIS SIDE'S WORD FOR IT (`(unnamed)`), which is why that
/// one word comes out of the catalog; the id itself -- and the declared modalities --
/// are interpolated exactly as they arrived, JSON-quoted as they always were.
export function imageRefusal(
  declared: readonly string[] | null | undefined,
  model: string | undefined,
  t: Translate,
): string | null {
  if (acceptsImages(declared)) return null;
  return t("attachment.model", {
    model: JSON.stringify(model ?? t("attachment.unnamedModel")),
    declared: JSON.stringify(declared),
  });
}

/// Is this file over the cap? Measured on SOURCE BYTES, and measured in exactly
/// one place: everything that needs the answer asks this, so nothing can end up
/// comparing a base64 length here and a `File.size` there.
export function overByteLimit(bytes: number): boolean {
  return bytes > ATTACHMENT_MAX_BYTES;
}

/// The sentence for a file over the cap, or null when it is not. It carries the
/// two numbers a person needs to fix it: what this one is, and what the limit is.
///
/// BOTH ARE FORMATTED BY THE ONE DECIMAL FORM (`formatMegabytes`), not the
/// glanceable one: a file that is over the cap by a fraction of a megabyte must not
/// print the same two numbers as the cap, or the refusal reads as a bug. That
/// caveat -- the comparison is exact while the sentence rounds -- is the formatter's
/// now, which is where the other copy of it used to live.
///
/// THE UNITS DO NOT TRANSLATE, so the two formatted magnitudes go into the sentence
/// as VALUES while the words around them come from the catalog.
export function sizeRefusal(bytes: number, t: Translate): string | null {
  if (!overByteLimit(bytes)) return null;
  return t("attachment.size", {
    actual: formatMegabytes(bytes),
    limit: formatMegabytes(ATTACHMENT_MAX_BYTES),
  });
}

/// Everything to be said about one file, before it is added -- or null, which is
/// permission to add it.
///
/// THE MODEL'S RULE IS ASKED FIRST. A file that is both too big and bound for a
/// model that takes no images would otherwise be answered with "shrink it", and
/// shrinking it would earn the other refusal -- a first refusal that leads
/// straight to a second one is worse than the one that names the real obstacle.
///
/// FILE is anything with a `size` -- a `File` in practice, and nothing more is
/// read, which is what makes this callable from a test without a browser.
export function refusalFor(
  file: { size: number },
  declared: readonly string[] | null | undefined,
  model: string | undefined,
  t: Translate,
): string | null {
  return imageRefusal(declared, model, t) ?? sizeRefusal(file.size, t);
}
