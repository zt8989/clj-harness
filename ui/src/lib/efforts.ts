// WHICH THINKING LEVELS A MODEL OFFERS, AND WHAT IT CALLS THEM.
//
// WHY THIS IS NOT THE SERVER'S LIST. `GET /api/choices` answers ONE closed list
// (`harness.cap.providers/reasoning-efforts`), because the server refuses no level:
// the value travels to the wire as `reasoning_effort` and the vendor decides what it
// means. That list can only be the union -- and a single union misleads in both
// directions: it offers `medium` to a vendor that maps it away, and it hides `max`
// from the one whose whole point it is. So the OFFER is narrowed here, per model,
// while the value still travels untouched.
//
// THE TABLE RUNS FROM OPENAI THROUGH KIMI, in the order their levels were measured.
// A model is matched by PREFIX -- nobody types a version suffix, and a vendor adds
// one every few months -- against the model id with everything before its last `/`
// dropped, because a relay spells the vendor into the id it serves
// (`openrouter/deepseek-v4.1-flash` is DeepSeek's model carried by OpenRouter, and
// `anthropic/claude-sonnet-4.5` is Claude's). A MODEL NO ROW KNOWS gets OpenAI's
// ladder: it is the widest, so nothing a person could pick is missing, and it is the
// list this picker offered before there was a table at all.
//
// ZERO IMPORTS, so the UI suite can pin the whole rule over literal model ids (see
// `test/suites/picker.ts`) -- the same reason `lib/picker.ts`, `lib/provider-key.ts`
// and `lib/relative-time.ts` import nothing. What the picker DRAWS with it is the
// browser walkthrough's half.

/// One vendor's ladder: the prefix its model ids carry, the levels it accepts in the
/// order it lists them, and the level it picks when nobody picked one.
///
/// `default` is absent for OpenAI alone, and that is the honest answer rather than a
/// gap: which level OpenAI uses depends on the model, so naming one here would be
/// this file inventing a fact the vendor publishes per model.
export type EffortVendor = {
  readonly name: string;
  readonly efforts: readonly string[];
  readonly default?: string | undefined;
};

/// THE LADDERS, OpenAI first. Order matters only for matching: the first row whose
/// prefix the id starts with wins, and the rows' prefixes do not overlap today.
const VENDORS: readonly { readonly prefixes: readonly string[]; readonly vendor: EffortVendor }[] = [
  {
    prefixes: ["gpt", "o1", "o3", "o4"],
    vendor: {
      name: "openai",
      efforts: ["none", "minimal", "low", "medium", "high", "xhigh", "max"],
    },
  },
  {
    prefixes: ["claude"],
    vendor: { name: "anthropic", efforts: ["low", "medium", "high", "max"], default: "high" },
  },
  {
    prefixes: ["gemini"],
    vendor: { name: "google", efforts: ["minimal", "low", "medium", "high"], default: "high" },
  },
  {
    prefixes: ["deepseek"],
    vendor: { name: "deepseek", efforts: ["low", "high", "max"], default: "high" },
  },
  {
    prefixes: ["glm"],
    vendor: { name: "glm", efforts: ["low", "high", "max"], default: "max" },
  },
  {
    prefixes: ["kimi", "moonshot"],
    vendor: { name: "kimi", efforts: ["low", "high", "max"], default: "max" },
  },
];

/// The row that answers a model id nothing recognizes. OpenAI's ladder is the union
/// of the six above, which is what makes it the right floor: a fallback that hid a
/// level would refuse a choice a vendor would have taken.
const FALLBACK: EffortVendor = VENDORS[0]!.vendor;

/// The part of MODEL a person reads as the model: a RELAY SPELLS ITSELF INTO THE ID
/// it serves, and it does not always pick `/` to do it -- OpenRouter's
/// `z-ai/glm-5` writes the family after a slash, a gateway's `cn:glm-5.3-flash`
/// writes it after a colon. Everything after the LAST of either is the family, and
/// an id with no separator is all family. Whichever separator an id uses, the
/// family is the model; the part in front is the endpoint's own spelling.
/// Lowercased, because an id is an address and `KIMI-K2` is the same one.
const bareModel = (model: string): string => {
  const cut = Math.max(model.lastIndexOf("/"), model.lastIndexOf(":"));
  return (cut < 0 ? model : model.slice(cut + 1)).toLowerCase();
};

/// The ladder MODEL offers. A model with no id at all -- a session no tier has named
/// one for -- is the fallback too: there is no vendor to ask.
export function effortsForModel(model: string | undefined): EffortVendor {
  if (model === undefined || model === "") return FALLBACK;
  const id = bareModel(model);
  const hit = VENDORS.find((row) => row.prefixes.some((prefix) => id.startsWith(prefix)));
  return hit?.vendor ?? FALLBACK;
}

/// The levels the picker OFFERS for MODEL, given the level this session is already
/// on. The vendor's own list, and the current level IN FRONT of it when the list
/// does not carry it: switching from a model that knows `xhigh` to one that does not
/// keeps the session's row rather than leaving the trigger blank -- and a pick back
/// to the old model changes nothing, because the session was never re-decided.
export function effortsOffered(model: string | undefined, current: string | undefined): readonly string[] {
  const { efforts } = effortsForModel(model);
  if (current === undefined || current === "" || efforts.includes(current)) return efforts;
  return [current, ...efforts];
}
