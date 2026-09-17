// The harness's management endpoints, typed thin.
//
// The chat itself is one AG-UI POST; everything in this module is the
// conversation's paperwork: what sessions the log directory holds, and handing
// a client its own history back. They live on the same origin as the run route,
// so the one address serves both -- which is why AGENT_URL is defined here and
// the run wiring imports it, rather than each file keeping its own copy of the
// address.
import type { TFunction } from "i18next";

/// The translator a FAILURE is worded through, PINNED TO THE `errors` FACE. i18next
/// brands a translator with the namespace it was bound to, so a shell translator
/// does not typecheck here and only the errors catalog's keys compile.
type Translate = TFunction<"errors">;

export const AGENT_URL = "http://localhost:8080/";

/// One row of `GET /api/threads`. `threadId` is the log file's stem -- the id a
/// rebuild names -- and `lastActivity` is the file's mtime, epoch milliseconds.
export type ThreadSummary = {
  threadId: string;
  lastActivity: number;
  bytes: number;
};

export async function listThreads(t: Translate): Promise<ThreadSummary[]> {
  const res = await fetch(`${AGENT_URL}api/threads`);
  if (!res.ok) throw new Error(t("http.listingSessions", { status: res.status }));
  return res.json();
}

/// What `POST /api/threads/<id>/rebuild` answers: the AG-UI message list the
/// server folded back out of the log (seed + every recorded frame, reasoning
/// and tool calls included) and the context the conversation started with. The
/// server holds no rebuilt state -- the client takes both into its next
/// ordinary run.
export type RebuiltThread = {
  threadId: string;
  messages: readonly unknown[];
  context: readonly unknown[];
};

export async function rebuildThread(threadId: string, t: Translate): Promise<RebuiltThread> {
  const res = await fetch(
    `${AGENT_URL}api/threads/${encodeURIComponent(threadId)}/rebuild`,
    { method: "POST" },
  );
  const body: unknown = await res.json().catch(() => undefined);
  if (!res.ok) {
    // The server refuses a truncated or corrupt log with the reason on the
    // 400. That reason is the sentence the row shows, so it is passed through
    // whole, not wrapped in something friendlier -- the ticket asks for the
    // server's words, and a wrapper's paraphrase would be one more thing to
    // distrust. ONLY WHEN THE BODY HAS NO REASON does this side speak, and then
    // it speaks the interface's language: `HTTP 500` is a fact about the wire.
    const reason =
      body !== undefined &&
      typeof body === "object" &&
      body !== null &&
      "error" in body &&
      typeof body.error === "string"
        ? body.error
        : t("http.status", { status: res.status });
    throw new Error(reason);
  }
  return body as RebuiltThread;
}
