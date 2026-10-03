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

/// WHAT THE SETTINGS LIST DRAWS. A SECOND RULE ON PURPOSE, and not the one above: the
/// picker offers what a run could be served by, while the settings list shows what THIS
/// HOME IS ABOUT.
///
/// THE RULE IS 'a key, OR an entry that is the person's own'. The built-in table is a
/// convenience catalog -- Ollama is in it, needs no key, and has no business holding a
/// row on a page about this home's providers. An entry somebody WROTE is a different
/// thing: it is in config.edn because a person put it there, so it stays visible whether
/// or not a key is in place yet (a local gateway may need none at all).
///
/// `origin` IS THE SERVER'S ANSWER (`providers/origin-of`) and not a guess made here --
/// which of the three an entry is decides whether the page offers to delete a built-in.
export const drawnInSettings = (
  provider: KeyFact & { readonly origin: string },
): boolean => hasKey(provider) || provider.origin !== "builtin";
