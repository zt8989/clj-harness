// The provider catalog, as the settings form reads and writes it.
//
// ONE MODULE FOR ONE QUESTION. Which vendors this home can reach, what each one
// serves, which line in `.env` holds its key, and what the default tier says are
// all facts `harness.cap.providers` already holds -- the same facts the model
// catalog is built from. Re-deriving any of them here would be a second answer,
// free to disagree with the first.
//
// EVERY CALL ANSWERS WITH THE WHOLE CATALOG, writes included. The form refetches
// after every change anyway, and one shape for GET and for the writes means there
// is one thing to parse and one place a field can be forgotten.
import type { TFunction } from "i18next";

import type { ProviderKey } from "@/lib/provider-key";
import type { ModelEntry } from "@/lib/model-entry";

import { API_BASE } from "@/lib/threads";

/// The translator a FAILURE is worded through, PINNED TO THE `errors` FACE. i18next
/// brands a translator with the namespace it was bound to, so a shell translator
/// does not typecheck here and only the errors catalog's keys compile.
type Translate = TFunction<"errors">;

/// The server's `{:error ..}` reason, when the body carries one -- the habit
/// `lib/skills.ts`, `lib/composer.ts` and `lib/settings.ts` all keep, and for the
/// same reason: the server's sentence is the one worth showing. A refusal here
/// names the field, the value, and what to write instead, and it is the only thing
/// the form has to show.
///
/// ONLY WHEN THE BODY HAS NO REASON does this side speak, and then in the
/// interface's language -- the server's sentence is never translated.
async function reasonFrom(res: Response, t: Translate): Promise<string> {
  const body: unknown = await res.json().catch(() => undefined);
  return body !== undefined &&
    typeof body === "object" &&
    body !== null &&
    "error" in body &&
    typeof body.error === "string"
    ? body.error
    : t("http.status", { status: res.status });
}

/// How to reach one model, and what it is allowed to be asked for. `input` and
/// `output` arrive as SORTED STRING VECTORS (JSON has no sets) -- the same
/// rendering the model endpoint and the log lines use.
export type ModelRow = {
  id: string;
  /// EMPTY MEANS THE FILE SAID NOTHING, which is a state and not an error: the
  /// server fills the modalities from the models.dev database at resolution and
  /// floors at text (`harness.cap.providers/assemble`). The form draws them as
  /// unchecked, and a save that leaves them unchecked writes nothing.
  input: readonly string[];
  output: readonly string[];
  /// WHAT A PERSON CALLS THIS MODEL. Optional, and only ever the person's word:
  /// the server's resolution carries the database's name as `:model-name` when the
  /// file wrote none, while the report offers it as `name-suggested`.
  name?: string;
  /// The database's name for this id, OFFERED rather than applied -- a placeholder
  /// the form shows when the row has no name of its own (`harness.cap.model-data`).
  "name-suggested"?: string;
  /// THE SAME IDEA FOR THE FACTS THE FOLD ANSWERS (owner, 2026-10-03): what models.dev
  /// says about this id, OFFERED as a placeholder or a summary rather than applied --
  /// a save that leaves the field empty still writes nothing, which is what keeps 'the
  /// file is silent' a state a person can leave a row in. A miss carries no key.
  "input-suggested"?: readonly string[];
  "output-suggested"?: readonly string[];
  "context-window-suggested"?: number;
  "max-output-tokens-suggested"?: number;
  /// WHAT THE FILE ITSELF SAID, still: the two counts a person may override. Both
  /// optional -- 'the file is silent' is the state the suggested keys describe.
  "context-window"?: number;
  "max-output-tokens"?: number;
  /// WHERE A MOVED INSTRUCTION GOES, IF THIS MODEL's line says. ABSENT is not
  /// the same as "replace": absent means the file is SILENT (and the server serves
  /// the conservative default), while "replace" is something a person wrote. The
  /// control is three-state for exactly that reason.
  "instruction-updates"?: "in-place" | "replace";
};

/// One row of a vendor's own `/models` listing: the id it gave, and -- WHEN A PREFIX
/// TABLE SPEAKS FOR THAT FAMILY -- the delivery mode to prefill a row with. A miss
/// carries no key, the same "only when there is something to say" the report keeps.
///
/// THE MATCHING IS THE SERVER'S (`.scratch/instruction-updates` decision 8): this side
/// only carries the answer to the form, because a second prefix table here would be a
/// second answer free to drift from the one the run's file is written against.
export type ModelSuggestion = {
  id: string;
  "instruction-updates"?: "in-place" | "replace";
};

/// Where a provider came from, which is what the page must tell apart: the built-in
/// table's, yours alone, or YOUR PATCH of a built-in. Only the last two can be
/// edited or removed here.
export type Origin = "builtin" | "user" | "builtin-patched";

/// One vendor, with everything a row and a form need.
export type ProviderRow = {
  name: string;
  "display-name"?: string;
  origin: Origin;
  protocol: string;
  "base-url": string;
  /// The model id served when no tier names one, or ABSENT when the file names none --
  /// then the vendor's own `/models` listing answers it. Absent, never `""`: an empty id
  /// is not a model, and the save would write one the server has to refuse.
  model?: string;
  models: readonly ModelRow[];
  /// The `.env` name this provider's key is read from -- derived from the id, and
  /// named here so nobody has to derive it in their head.
  credential: string;
  /// WHETHER THIS HOME HOLDS A KEY for it, and where from -- the shared `ProviderKey`,
  /// because the composer's choices row reports the same fact and one type is what keeps
  /// the two from describing it differently. No value, at any depth.
  key: ProviderKey;
};

/// One vendor a person may pick when adding a provider, as the server answers it.
/// `name` is ABSENT for a vendor the document does not name -- the form falls back to the
/// id rather than being handed a made-up label, the same rule `providerLabel` keeps.
export type KnownProvider = {
  readonly id: string;
  readonly name?: string;
  readonly "base-url": string;
  readonly "model-count": number;
  /// Whether THIS ENVIRONMENT already holds the key this vendor reads -- checked with
  /// the same lookup a run does (the document's own variable name first, then this
  /// harness's derived name, then the global), so 'pick and use' is true exactly when a
  /// run would pass. The `key` fact beside it carries WHICH name won; a VALUE never
  /// rides along, in a pick list any more than anywhere else.
  readonly "key-ready": boolean;
  readonly key: ProviderKey;
};

/// What `GET /api/providers` answers, and what every write answers with too.
export type Registry = {
  providers: readonly ProviderRow[];
  /// The vendors a person may PICK when adding one: models.dev's OpenAI-compatible
  /// vendors (each with the address it publishes) union this harness's own table. NOT
  /// about this home -- see the server's `known-providers` for why it rides here.
  "known-providers": readonly KnownProvider[];
  /// The protocols this harness implements, read off the server's own dispatch.
  protocols: readonly string[];
  /// The reasoning efforts the picker may offer -- a closed, offered list, not a
  /// guard: a vendor decides what the value means.
  "reasoning-efforts": readonly string[];
  /// config.edn's `:default` section AS WRITTEN. The three knobs when it names a
  /// provider, an endpoint description when it describes one -- tell them apart by
  /// whether `provider` is there, which is exactly how the server tells them apart.
  default: Record<string, unknown>;
  /// Present on a removal's answer: which entry went.
  removed?: string;
};

/// What the form submits for one provider. `api-key` is OPTIONAL and means what the
/// server says it means: absent leaves `.env` alone, and a value becomes that
/// provider's own line.
export type ProviderPayload = {
  id: string;
  "display-name"?: string;
  protocol: string;
  "base-url": string;
  /// THE DEFAULT MODEL, when the form has one to name. It may be left OUT: the
  /// server then takes the first id the vendor's own listing answers with.
  model?: string;
  /// MODEL ENTRIES, NOT REPORT ROWS, and the two are not the same thing: what a write
  /// carries is what config.edn may say about a model (`lib/model-entry.ts`, and the
  /// server's `model-keys` is the list it enforces). A report row carries the database's
  /// answers BESIDE that under `-suggested` keys, which is what a form shows and what it
  /// must never send back -- one of them arriving here is a refused write. `entryOf` is
  /// the projection, and the form's save goes through it.
  models: readonly ModelEntry[];
  "api-key"?: string;
};

/// The three knobs, each either a value or `null` -- where ABSENT means "do not
/// touch that knob" and `null` means "remove the key". Those are different
/// requests, and the server draws the same line.
export type DefaultKnobs = {
  provider?: string | null;
  model?: string | null;
  "reasoning-effort"?: string | null;
};

async function read(res: Response, t: Translate): Promise<Registry> {
  if (!res.ok) throw new Error(await reasonFrom(res, t));
  return (await res.json()) as Registry;
}

async function post(path: string, body: unknown, t: Translate): Promise<Registry> {
  const res = await fetch(`${API_BASE}${path}`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
  });
  return read(res, t);
}

export async function registryFor(t: Translate): Promise<Registry> {
  return read(await fetch(`${API_BASE}providers`), t);
}

export async function putProvider(payload: ProviderPayload, t: Translate): Promise<Registry> {
  return post("providers", payload, t);
}

export async function removeProvider(id: string, t: Translate): Promise<Registry> {
  return post(`providers/${encodeURIComponent(id)}/remove`, {}, t);
}

export async function putDefaults(knobs: DefaultKnobs, t: Translate): Promise<Registry> {
  return post("defaults", knobs, t);
}

/// Ask a VENDOR what it serves -- the one call in this feature that leaves the
/// machine. The key may be one just typed into the form (trying it before it is
/// written anywhere is the point) or absent, in which case the server resolves it
/// exactly as a run would.
export async function probeModels(
  ask: { id?: string; "base-url"?: string; protocol?: string; "api-key"?: string },
  t: Translate,
): Promise<{ models: ModelSuggestion[]; asked: string }> {
  const res = await fetch(`${API_BASE}providers/models`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(ask),
  });
  if (!res.ok) throw new Error(await reasonFrom(res, t));
  return (await res.json()) as { models: ModelSuggestion[]; asked: string };
}
