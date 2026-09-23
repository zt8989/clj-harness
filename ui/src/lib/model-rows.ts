// A MODEL ROW IS NAMED BY ITS VENDOR AND ITS ID.
//
// WHY IT IS A RULE AT ALL: the picker identifies a row by ONE string -- it is what the
// trigger matches, what the list draws as the current one, what the keyboard opens on, and
// what `onPick` hands back. That string used to be the model id ALONE, on the reasoning that
// the vendor is "implied by the heading". It is not implied: two vendors may declare the
// same id, and a home really does hold both (`qwen` and `workbuddy` both serving
// `deepseek-v4.1-flash` is one such home). Then the id names two rows: both are drawn as
// the current one, and a pick under either heading resolves to whichever vendor the catalog
// happens to list first (`qwen`, because the server sorts vendors by name).
//
// SO THE IDENTITY IS THE PAIR, written `provider/id`. The split is unambiguous: a provider
// id matches `^[a-z][a-z0-9-]*$` (the settings form's rule), so it can never hold the
// separator, while a model id may (openrouter serves `anthropic/claude-sonnet-4.5`). A row
// with NO vendor -- a provider described inline in `config.edn` has no id to send -- is
// written with an EMPTY provider, so `/id` is a key no named vendor can ever spell.
//
// ZERO IMPORTS BUT THE TWO RULES IT COMPOSES (whether a vendor is offered at all, and what
// to call one on screen), so the UI suite can pin this over literal rows -- the division of
// labour `lib/provider-key.ts` records: this run proves the rule, and
// `scripts/dev.mjs --scripted` proves what the component DRAWS with it, which needs a
// browser.
import type { PickerOption } from "./picker";
import { providerLabel } from "./provider-label";
import { hasKey, type KeyFact } from "./provider-key";

/// One row: the vendor that declares a model, and the id it declares. The vendor is absent
/// -- not empty -- for a provider the home describes inline, which has no id to send.
export type ModelRow = { readonly provider?: string | undefined; readonly model: string };

/// A vendor as this menu reads it: the shared key fact, the ids it declares, and the label
/// it may carry. Structural rather than the wire's own row, for the reason `KeyFact` gives
/// -- anything carrying these fields is one of these, and the shapes that do are not this
/// module's to name.
export type MenuProvider = KeyFact & {
  readonly name: string;
  readonly "display-name"?: string | undefined;
  readonly models: readonly string[];
};

/// The identity of one row: the vendor AND the id, as one string. Two rows are the same row
/// exactly when this is equal.
export const modelRowKey = (row: ModelRow): string => `${row.provider ?? ""}/${row.model}`;

/// A row back out of an identity. Everything before the FIRST separator is the vendor --
/// the only place it can be, since a provider id never contains one -- and a leading one
/// means the row has no vendor to name.
export function modelRowFromKey(key: string): ModelRow {
  const cut = key.indexOf("/");
  if (cut < 0) return { model: key };
  const provider = key.slice(0, cut);
  const model = key.slice(cut + 1);
  return provider === "" ? { model } : { provider, model };
}

/// What the model picker offers, and which row it marks as the session's own.
export type ModelMenu = {
  /// The identity of the row this session is being served by, or `""` when no tier named a
  /// model at all -- a session with no row of its own to mark.
  readonly current: string;
  readonly options: PickerOption[];
};

/// THE MENU, built here rather than in the component so that "which rows are offered, and
/// which one is the session's own" is one pure answer over the `choices` payload.
///
/// ONLY THE VENDORS THIS HOME HOLDS A KEY FOR reach the menu (`lib/provider-key.ts` is the
/// one copy of that rule, shared with the settings page): offering a vendor that will
/// certainly refuse leads a person to a run that cannot work.
///
/// THE SESSION'S ROW IS NOT TOUCHED BY THAT FILTER. A session served by a model the catalog
/// does not offer -- its vendor has no key, or it is an inline provider, or the vendor has
/// since been removed -- is neither erased nor renamed: its OWN row rides at the top with
/// the existing 'not in the catalog' hint. Erasing what a session is being SERVED BY is a
/// bigger lie than listing a vendor without a key.
export function modelMenu(
  providers: readonly MenuProvider[],
  current: { readonly provider?: string | undefined; readonly model?: string | undefined },
  notInCatalog: string,
): ModelMenu {
  const offered: PickerOption[] = providers
    .filter(hasKey)
    .flatMap((provider) =>
      provider.models.map((model) => ({
        value: modelRowKey({ provider: provider.name, model }),
        label: model,
        group: providerLabel(provider),
      })),
    );

  const model = current.model === undefined || current.model === "" ? undefined : current.model;
  const key = model === undefined ? "" : modelRowKey({ provider: current.provider, model });

  // A SESSION WITH NO MODEL NAMED has no row of its own to mark, and the picker still has to
  // say something: it opens on the first row the menu offers, which is what this picker has
  // always shown. (Which model such a session would actually run on is the SERVER's answer
  // -- the catalog resolves it -- and that answer is not in this payload.)
  const options =
    model === undefined || offered.some((option) => option.value === key)
      ? offered
      : [{ value: key, label: model, hint: notInCatalog }, ...offered];

  return { current: model === undefined ? (options[0]?.value ?? "") : key, options };
}
