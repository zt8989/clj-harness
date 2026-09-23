// ONE RULE, TWO FACES -- the same shape `lib/provider-key.ts` records for the other
// half of a vendor's row. The Models page labels each provider with it, and the
// composer's model picker heads its rows with it. Two copies that happen to agree
// today is how the second one quietly stops agreeing, so there is one copy.
//
// IT IS A RENDERING DECISION, which is why the fallback lives on this side rather than
// on the server: `:name` is the ID -- what is SENT, what a log line says -- and
// `:display-name` is the label a person gave that vendor, when they gave one. The
// server reports both (`harness.cap.providers/choices`); what to DRAW from them is the
// interface's business.
//
// ZERO IMPORTS, and structural rather than either wire type: the settings registry's
// row and the picker's choices row are different shapes carrying these same two
// fields, and naming either one here would be a cycle or a lie.
export type NamedProvider = { readonly name: string; readonly "display-name"?: string | undefined };

/// What to call a vendor on screen: the label its entry declares, the id otherwise.
/// The id is always the truth, and a vendor nobody named has no label to show.
export const providerLabel = (provider: NamedProvider): string =>
  provider["display-name"] ?? provider.name;
