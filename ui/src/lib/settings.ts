// `GET /api/settings`: what configuration is in force right now.
//
// ONE CALL, AND THE ANSWER IS ALWAYS LIVE. The server re-reads config.edn (both
// of its sections) and the session's own override at call time, so opening
// the panel twice after an edit shows two different answers with no restart --
// the same "config is files, read fresh" discipline the rest of the harness
// follows, made visible here.
//
// THE KEY NEVER ARRIVES. Not its value, not its length, not a prefix: the
// server subtracts it (see harness.cap.providers/unsecret) and reports presence and
// origin instead, under `key`. A client therefore has nothing to redact, which
// is the only arrangement in which "the UI does not leak the key" is a fact
// rather than a promise about a rendering path.
//
// EVERY FIELD IS OPTIONAL IN THE TYPE below except the ones the panel cannot
// draw without, because the server's answer is a resolution's output and a
// resolution may legitimately have less to say: an inline provider declares no
// modalities unless the entry does, and a knob no tier named is absent rather
// than null.
import { AGENT_URL } from "@/lib/threads";

/// Which tier supplied a knob, or `catalog` when no tier did and the provider's
/// entry answered -- a provider's DEFAULT model is nobody's choice but the
/// catalog's, and the panel says so rather than crediting a tier that did not
/// speak.
export type Tier = "config" | "session" | "request" | "catalog";

/// One file this home is made of. `present?` is the whole fact: the panel says
/// which files exist, never what is in them.
export type HomeFile = {
  name: string;
  path: string;
  "present?": boolean;
};

export type HomeSummary = {
  path: string;
  /// Which of harness.infra.home's three rules produced the path. A home the
  /// environment moved and a home nobody moved are debugged in different
  /// places, so the panel may not leave this to be guessed from the string.
  origin: "environment" | "override" | "default";
  files: readonly HomeFile[];
};

/// Where the api-key WOULD be read from. Never the key.
///
/// `name` is the line in `.env` involved either way: the one that won when a key
/// is set, and the one that would be read first when none is -- which is the line
/// a person has to add. It is derived from the provider's id (`acme-gateway` ->
/// `ACME_GATEWAY_API_KEY`), so the panel can name it instead of making the reader
/// derive it, which is the whole point of the rule.
export type KeySource = {
  "present?": boolean;
  source: "env-file" | "environment" | null;
  name?: string;
};

export type Settings = {
  /// The knobs, as chosen, plus what the catalog answered.
  provider?: string;
  model?: string;
  "reasoning-effort"?: string;
  /// The label the catalog gives the selected vendor, when the entry declares one.
  /// `provider` stays the ID: that is the identity, and this is only what to call
  /// it on screen.
  "display-name"?: string;
  protocol?: string;
  "base-url"?: string;
  input?: readonly string[];
  output?: readonly string[];
  "context-window"?: number;
  "max-output-tokens"?: number;
  /// The selection as folded -- present for an inline provider, where the
  /// "selection" is config.edn's description of the whole endpoint.
  selection?: Record<string, unknown>;
  source: "default" | "inline" | "request";
  tiers: Partial<Record<"provider" | "model" | "reasoning-effort", Tier>>;
  key: KeySource;
  home: HomeSummary;
};

/// Thrown with the SERVER's sentence when the configuration cannot be resolved
/// -- an unknown provider, an undeclared model, a config.edn that is not there.
/// That sentence is the panel's content in that case: a half-edited config.edn
/// is the ordinary way a person meets this, and "no provider named :nope; the
/// registry defines [...]" beats a blank pane.
export async function getSettings(threadId: string): Promise<Settings> {
  const res = await fetch(
    `${AGENT_URL}api/settings?threadId=${encodeURIComponent(threadId)}`,
  );
  const body: unknown = await res.json().catch(() => undefined);
  if (!res.ok) {
    const reason =
      body !== undefined &&
      typeof body === "object" &&
      body !== null &&
      "error" in body &&
      typeof body.error === "string"
        ? body.error
        : `HTTP ${res.status}`;
    throw new Error(reason);
  }
  return body as Settings;
}
