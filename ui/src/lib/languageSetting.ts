// GET and POST /api/language: the one source of the page's language.
//
// THE SERVER OWNS THE VALUE. It lives in config.edn's `:ui :language` and is resolved
// with the chain in harness.infra.language, so the page never guesses -- it reads the
// route at boot (`lib/i18n.ts` awaits `fetchLanguage` before the first render) and
// writes the route when somebody picks a language.
//
// A READ THAT FAILS IS NOT FATAL: the page falls back to English and renders, because a
// language is not worth a blank screen, and the next load tries again. A WRITE THAT
// FAILS IS REPORTED: the choice did not take, and the caller shows the server's own
// sentence rather than switching as if it had.
import type { TFunction } from "i18next";

import { API_BASE } from "@/lib/threads";
import { FALLBACK_LANGUAGE, asLanguage, type Language } from "@/lib/language";

/// The translator a failed write is worded through, PINNED TO THE `errors` FACE. The
/// server's sentence is preferred; this is only for a failure with no readable body.
type Translate = TFunction<"errors">;

/// The language this home speaks, as the server resolves it.
export async function fetchLanguage(): Promise<Language> {
  try {
    const res = await fetch(`${API_BASE}language`);
    if (!res.ok) return FALLBACK_LANGUAGE;
    const body: unknown = await res.json().catch(() => undefined);
    return asLanguage(
      body !== null && typeof body === "object" && "language" in body
        ? (body as { language?: unknown }).language
        : undefined,
    );
  } catch {
    return FALLBACK_LANGUAGE;
  }
}

/// Write LANGUAGE into config.edn's `:ui :language`, or throw with the server's own
/// sentence. The caller applies the switch only after this resolves.
export async function saveLanguage(language: Language, t: Translate): Promise<void> {
  const res = await fetch(`${API_BASE}language`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ language }),
  });
  if (res.ok) return;
  const body: unknown = await res.json().catch(() => undefined);
  const reason =
    body !== undefined &&
    typeof body === "object" &&
    body !== null &&
    "error" in body &&
    typeof (body as { error?: unknown }).error === "string"
      ? (body as { error: string }).error
      : t("http.status", { status: res.status });
  throw new Error(reason);
}
