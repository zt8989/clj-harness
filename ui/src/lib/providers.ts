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
import { AGENT_URL } from "@/lib/threads";

/// The server's `{:error ..}` reason, when the body carries one -- the habit
/// `lib/skills.ts`, `lib/composer.ts` and `lib/settings.ts` all keep, and for the
/// same reason: the server's sentence is the one worth showing. A refusal here
/// names the field, the value, and what to write instead, and it is the only thing
/// the form has to show.
async function reasonFrom(res: Response): Promise<string> {
  const body: unknown = await res.json().catch(() => undefined);
  return body !== undefined &&
    typeof body === "object" &&
    body !== null &&
    "error" in body &&
    typeof body.error === "string"
    ? body.error
    : `HTTP ${res.status}`;
}

/// How to reach one model, and what it is allowed to be asked for. `input` and
/// `output` arrive as SORTED STRING VECTORS (JSON has no sets) -- the same
/// rendering the model endpoint and the log lines use.
export type ModelRow = {
  id: string;
  input: readonly string[];
  output: readonly string[];
  "context-window"?: number;
  "max-output-tokens"?: number;
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
  /// The model id served when no tier names one.
  model: string;
  models: readonly ModelRow[];
  /// The `.env` name this provider's key is read from -- derived from the id, and
  /// named here so nobody has to derive it in their head.
  credential: string;
  key: { "present?": boolean; source: "env-file" | "environment" | null; name?: string };
};

/// What `GET /api/providers` answers, and what every write answers with too.
export type Registry = {
  providers: readonly ProviderRow[];
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
  model: string;
  models: readonly {
    id: string;
    input: readonly string[];
    output: readonly string[];
    "context-window"?: number;
    "max-output-tokens"?: number;
  }[];
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

async function read(res: Response): Promise<Registry> {
  if (!res.ok) throw new Error(await reasonFrom(res));
  return (await res.json()) as Registry;
}

async function post(path: string, body: unknown): Promise<Registry> {
  const res = await fetch(`${AGENT_URL}${path}`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
  });
  return read(res);
}

export async function registryFor(): Promise<Registry> {
  return read(await fetch(`${AGENT_URL}api/providers`));
}

export async function putProvider(payload: ProviderPayload): Promise<Registry> {
  return post("api/providers", payload);
}

export async function removeProvider(id: string): Promise<Registry> {
  return post(`api/providers/${encodeURIComponent(id)}/remove`, {});
}

export async function putDefaults(knobs: DefaultKnobs): Promise<Registry> {
  return post("api/defaults", knobs);
}

/// Ask a VENDOR what it serves -- the one call in this feature that leaves the
/// machine. The key may be one just typed into the form (trying it before it is
/// written anywhere is the point) or absent, in which case the server resolves it
/// exactly as a run would.
export async function probeModels(
  ask: { id?: string; "base-url"?: string; protocol?: string; "api-key"?: string },
): Promise<{ models: string[]; asked: string }> {
  const res = await fetch(`${AGENT_URL}api/providers/models`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(ask),
  });
  if (!res.ok) throw new Error(await reasonFrom(res));
  return (await res.json()) as { models: string[]; asked: string };
}

/// What to call a provider on screen: the label its entry declares, the id
/// otherwise. The fallback is a rendering decision, so it lives here rather than on
/// the server -- the id is always the truth, and a vendor nobody named has no label.
export const providerLabel = (provider: ProviderRow): string =>
  provider["display-name"] ?? provider.name;
