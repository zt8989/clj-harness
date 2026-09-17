// The harness's management endpoints, typed thin.
//
// The chat itself is one AG-UI POST; everything in this module is the
// conversation's paperwork: what sessions the log directory holds, and handing
// a client its own history back. They live on the same origin as the run route,
// so the one address serves both -- which is why AGENT_URL is defined here and
// the run wiring imports it, rather than each file keeping its own copy of the
// address.
//
// THIS ORIGIN IS THE DEFAULT, not `http://localhost:8080/`. The page and the
// harness are served from ONE address, and the dev server is what forwards the
// harness's share of it (`ui/vite.config.js`, and `dev.sh` for starting the two
// together on a port it picks). Two things follow, and both are the reason:
// the browser makes no cross-origin request at all -- so nothing here depends on
// the harness's CORS allowance, which is a list of origins that has to keep up
// with a port that no longer has to be 5173 -- and a build carries no address
// of ours into it, so the same bundle works behind whatever a deployment puts
// in front.
//
// VITE_AGENT_URL IS THE WAY BACK OUT. Pointed at an absolute address it talks
// to a harness directly, exactly as this file used to; that is the mode the
// CORS allowance exists for, and a page served by something with no proxy needs
// it.
export const AGENT_URL: string = import.meta.env.VITE_AGENT_URL ?? "/";

/// One row of `GET /api/threads`. `threadId` is the log file's stem -- the id a
/// rebuild names -- and `lastActivity` is the file's mtime, epoch milliseconds.
export type ThreadSummary = {
  threadId: string;
  lastActivity: number;
  bytes: number;
};

export async function listThreads(): Promise<ThreadSummary[]> {
  const res = await fetch(`${AGENT_URL}api/threads`);
  if (!res.ok) throw new Error(`listing sessions failed: HTTP ${res.status}`);
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

export async function rebuildThread(threadId: string): Promise<RebuiltThread> {
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
    // distrust.
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
  return body as RebuiltThread;
}
