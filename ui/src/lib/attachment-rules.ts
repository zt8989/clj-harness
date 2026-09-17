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
// This module IMPORTS NOTHING: no React, no `@` alias, no DOM. That is what lets
// vitest reach it by relative path (see vitest.config.ts, which deliberately
// loads no alias), and a rule this load-bearing is worth a test that needs no
// browser. `format.ts` is the precedent.

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
export function imageRefusal(
  declared: readonly string[] | null | undefined,
  model: string | undefined,
): string | null {
  if (acceptsImages(declared)) return null;
  return (
    `model ${JSON.stringify(model ?? "(unnamed)")} does not take images;` +
    ` it declares ${JSON.stringify(declared)} — change the model,` +
    ` or attach only what it declares`
  );
}

/// Is this file over the cap? Measured on SOURCE BYTES, and measured in exactly
/// one place: everything that needs the answer asks this, so nothing can end up
/// comparing a base64 length here and a `File.size` there.
export function overByteLimit(bytes: number): boolean {
  return bytes > ATTACHMENT_MAX_BYTES;
}

/// The sentence for a file over the cap, or null when it is not. It carries the
/// two numbers a person needs to fix it: what this one is, and what the limit is.
export function sizeRefusal(bytes: number): string | null {
  if (!overByteLimit(bytes)) return null;
  return `this image is ${megabytes(bytes)}; the limit is ${megabytes(ATTACHMENT_MAX_BYTES)}`;
}

/// The attachment's size as a person reads it: a tenth of a megabyte, with a
/// trailing `.0` dropped so the cap reads as the "2 MB" a person would write.
///
/// THE COMPARISON IS EXACT AND THE SENTENCE ROUNDS, and that leaves one small gap
/// worth stating rather than hiding: a file one byte over the cap prints the same
/// "2 MB" the cap does. The two numbers are here to tell somebody what to do about
/// it, and at that size the thing to do is look at the file, not to compute a
/// difference -- while six significant digits would be a worse sentence for every
/// other file that lands here.
function megabytes(bytes: number): string {
  const mb = (bytes / (1024 * 1024)).toFixed(1);
  return `${mb.endsWith(".0") ? mb.slice(0, -2) : mb} MB`;
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
): string | null {
  return imageRefusal(declared, model) ?? sizeRefusal(file.size);
}
