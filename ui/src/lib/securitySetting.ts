// GET and POST /api/security: the paths THIS HOME declares sensitive.
//
// THE SERVER OWNS THE LIST. It lives in config.edn's `:security :sensitive-paths` (or in
// the built-in list, when that file names none), and the fence that parks a call is derived
// from it there -- so the page never keeps a copy of its own: it reads this route and
// writes this route, the same discipline `lib/languageSetting.ts` keeps for the language.
//
// THE LIST IS SHOWN AS WRITTEN. `~/.ssh/` is what a person typed and what the file holds;
// the expanded absolute form is a machine fact the server keeps to itself, because a row
// showing `/home/someone/.ssh/` would be showing a path nobody typed.
//
// A READ THAT FAILS IS REPORTED, NOT SWALLOWED, which is the one place this differs from
// the language: a language nobody can read is still usable (English), while a guard
// silently drawn as absent would tell a person their credentials are listed when they are
// not.
import type { TFunction } from "i18next";

import { API_BASE } from "@/lib/threads";

/// The translator a failure is worded through, PINNED TO THE `errors` FACE: the server's
/// own sentence is preferred, and this is only for a failure with no readable body.
type Translate = TFunction<"errors">;

/// What this home's sensitive list IS, as `GET /api/security` answers it.
export type Security = {
  /// The paths as written -- a leading `~` and all.
  "sensitive-paths": readonly string[];
  /// Where the list came from: `config` when this home wrote one, `default` when the
  /// built-in list answered instead.
  source: "config" | "default";
  /// The built-in list, so `restore` is a local action rather than a second request.
  defaults: readonly string[];
};

/// The server's own sentence when a route refused, or a sentence about the status when the
/// body carried none -- the rule every route pair in this app keeps.
function reasonFrom(body: unknown, status: number, t: Translate): string {
  return body !== undefined &&
    typeof body === "object" &&
    body !== null &&
    "error" in body &&
    typeof (body as { error?: unknown }).error === "string"
    ? (body as { error: string }).error
    : t("http.status", { status });
}

function asSecurity(body: unknown): Security {
  const wire = (body ?? {}) as Record<string, unknown>;
  const paths = Array.isArray(wire["sensitive-paths"]) ? (wire["sensitive-paths"] as string[]) : [];
  const defaults = Array.isArray(wire.defaults) ? (wire.defaults as string[]) : [];
  return {
    "sensitive-paths": paths,
    source: wire.source === "config" ? "config" : "default",
    defaults,
  };
}

/// The sensitive paths in force right now, or a throw carrying the server's sentence.
export async function readSecurity(t: Translate): Promise<Security> {
  const res = await fetch(`${API_BASE}security`);
  const body: unknown = await res.json().catch(() => undefined);
  if (!res.ok) throw new Error(reasonFrom(body, res.status, t));
  return asSecurity(body);
}

/// Write the WHOLE list (that is the route's shape: an empty array says this home guards
/// nothing), and answer the list the server now reads back.
export async function writeSecurity(
  paths: readonly string[],
  t: Translate,
): Promise<Security> {
  const res = await fetch(`${API_BASE}security`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ "sensitive-paths": paths }),
  });
  const body: unknown = await res.json().catch(() => undefined);
  if (!res.ok) throw new Error(reasonFrom(body, res.status, t));
  return asSecurity(body);
}
