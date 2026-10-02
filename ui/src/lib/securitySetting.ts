// GET and POST /api/security: the paths THIS HOME declares sensitive -- ON TOP OF the
// built-in list, which is always in force.
//
// THE SERVER OWNS THE LIST. The built-in half lives in the server's `default-sensitive-paths`
// and can never be switched off; the custom half lives in config.edn's
// `:security :sensitive-paths` and is what this route writes. The fence that parks a call is
// built-in UNION custom, derived on the server -- so the page keeps no copy of its own: it
// reads this route and writes this route, the same discipline `lib/languageSetting.ts`
// keeps for the language.
//
// WHAT THE PANEL EDITS IS THE CUSTOM HALF. `sensitive-paths` is answered as the union IN
// FORCE (what the fence actually parks on), `builtin` is the half the panel shows but
// cannot delete, and `custom` is the half the add/remove buttons write. Sending `[]` does
// NOT turn the guard off -- it empties this home's own additions, the built-in list stays.
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
  /// The list IN FORCE -- built-in then custom, as written. What the fence parks on.
  "sensitive-paths": readonly string[];
  /// The built-in half: always in force, never deletable from the panel.
  builtin: readonly string[];
  /// THIS HOME's own additions -- the half the add/remove buttons write.
  custom: readonly string[];
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
  const strings = (value: unknown): string[] =>
    Array.isArray(value) ? (value as string[]) : [];
  return {
    "sensitive-paths": strings(wire["sensitive-paths"]),
    builtin: strings(wire.builtin),
    custom: strings(wire.custom),
  };
}

/// The sensitive paths in force right now, or a throw carrying the server's sentence.
export async function readSecurity(t: Translate): Promise<Security> {
  const res = await fetch(`${API_BASE}security`);
  const body: unknown = await res.json().catch(() => undefined);
  if (!res.ok) throw new Error(reasonFrom(body, res.status, t));
  return asSecurity(body);
}

/// Write THIS HOME's OWN additions (the built-in half is never in the request and never
/// dropped: `[]` empties the custom half, it does not turn the guard off), and answer the
/// record the server now reads back.
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
