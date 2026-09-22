// ONE RULE, TWO FACES: a provider is shown when this home holds a key pointing at it.
//
// WHY IT IS A RULE AT ALL: a provider with no key refuses every run, so putting it in
// front of a person is leading them to a run that cannot work. The settings page's
// list and the composer's model picker are the two places a provider is offered, and
// the spec that asked for this (`.scratch/provider-availability`) is explicit that
// they are ONE rule rather than two that happen to agree today -- a second copy is a
// second answer, free to drift from the first.
//
// IT READS A FACT THE SERVER DERIVES, not a fact this side works out. `:key` arrives
// with both answers already (`harness.cap.providers/api-key-source`), so the
// precedence between the home's `.env` and the real environment is decided in one
// place and this module only asks whether a key won. Deriving it here would mean this
// side could disagree with a run about whether a key exists.
//
// ZERO IMPORTS, so the UI suite can pin the rule over literal rows (see
// `test/suites/picker.ts`) -- the same reason `lib/picker.ts` and
// `lib/relative-time.ts` import nothing, and the same division of labour: this run
// proves the rule, and `scripts/dev.mjs --scripted` proves what the two components
// DRAW with it, which needs a browser.

/// WHAT THE SERVER SAYS ABOUT A KEY POINTING AT A PROVIDER -- `api-key-source`'s whole
/// answer, and the ONE type for it. The settings registry's row and the picker's
/// choices row are different shapes that carry this same fact, and two hand-written
/// copies of it is how the picker's copy quietly stopped mentioning `source` and `name`
/// while the server went on sending both.
///
/// IT IS A FACT AND NEVER A VALUE, at any depth: `source` names WHERE a key answered
/// (the home's `.env` before the real environment) and `name` is the LINE a key would
/// go on, not the key. Nothing in here is a secret, which is the property the server's
/// own answer is written to keep.
export type ProviderKey = {
  readonly "present?": boolean;
  readonly source: "env-file" | "environment" | null;
  readonly name?: string;
};

/// A row that carries that fact, named structurally rather than as one of the two wire
/// shapes: this module imports nothing and BOTH of them import it, so it cannot name
/// either one without a cycle.
///
/// `key` is always present on both -- the server sends the facts whether or not a key
/// was found -- so an absent `key` is a bug in a caller rather than a state to survive
/// here, and one that fails loudly beats one that silently reads as "no key".
export type KeyFact = { readonly key: ProviderKey };

/// Whether this home holds a key pointing at PROVIDER -- from the home's `.env` or
/// from the real environment, which is one answer and not two.
export const hasKey = (provider: KeyFact): boolean => provider.key["present?"];

/// PROVIDERS split into the ones this home holds a key for and the ones it does not,
/// each keeping the order it was given: the caller's order is the reader's order, and
/// the server already sorted them by name.
///
/// BOTH HALVES ARE RETURNED because both are DRAWN: the keyed ones as the list, and
/// the rest behind a sentence that says how many there are and how to bring them back.
/// Dropping the second half would hide the built-in table's ids, and "add a provider
/// to give openrouter a key" is an action a person takes by reading one.
export function splitByKey<T extends KeyFact>(
  providers: readonly T[],
): { keyed: T[]; unkeyed: T[] } {
  const keyed: T[] = [];
  const unkeyed: T[] = [];
  for (const provider of providers) {
    if (hasKey(provider)) keyed.push(provider);
    else unkeyed.push(provider);
  }
  return { keyed, unkeyed };
}
