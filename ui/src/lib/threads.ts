// The harness's management endpoints, typed thin.
//
// The chat itself is one AG-UI POST; everything in this module is the
// conversation's paperwork: what sessions the log directory holds, and handing
// a client its own history back. THE WHOLE HARNESS IS UNDER ONE PREFIX -- the run
// endpoint is `POST /api/agent` and everything else is `/api/<something>` -- and
// that is deliberate rather than incidental: one prefix is one thing a front end
// has to know (and one thing a dev proxy has to forward), and the run used to be
// the odd one out at the server root, which meant a cross-origin allowance and a
// proxy rule that had to tell a run from a page by METHOD.
//
// SO THERE ARE TWO ADDRESSES HERE, and they are two because they answer different
// questions: `API_BASE` is where the management calls hang off, `AGENT_URL` is the
// one endpoint an AG-UI client is constructed with (`HttpAgent({url})` posts
// exactly there). The two are NOT interchangeable: `${AGENT_URL}api/...` would
// glue the path onto the endpoint rather than the prefix.
//
// THE HARNESS'S ADDRESS IS THIS ORIGIN by default, not `http://localhost:8080/`.
// The page and the harness are served from ONE address then, and the dev server is
// what forwards the harness's share of it (`ui/vite.config.js`, and
// `scripts/dev.mjs` for starting the two together on a port it picks). A
// deployment wants exactly that shape, and so does a hand-started dev server: the
// browser makes no cross-origin request at all, and a build carries no address of
// ours into it, so the same bundle works behind whatever a deployment puts in
// front.
//
// VITE_AGENT_URL IS THE WAY OUT, AND IT IS WHAT THE DEV LOOP USES. Pointed at an
// absolute address it talks to a harness directly, which is what makes the page's
// requests cross-origin -- the mode the harness's CORS allowance exists for, and
// the mode `scripts/dev.mjs` selects on purpose (its header says why: vite's own
// forwarder intermittently loses the last chunk of an SSE response, and a run
// whose response never ends leaves the composer stuck on Cancel forever).
// The trailing slash is optional: `http://host:8080` and `http://host:8080/` are
// the same address here rather than two, because getting it wrong would otherwise
// be a URL with `api` glued to the host.
import type { TFunction } from "i18next";

const HARNESS = `${(import.meta.env.VITE_AGENT_URL ?? "/").replace(/\/+$/, "")}/`;

/// The translator a FAILURE is worded through, PINNED TO THE `errors` FACE. i18next
/// brands a translator with the namespace it was bound to, so a shell translator
/// does not typecheck here and only the errors catalog's keys compile.
type Translate = TFunction<"errors">;

/// Where every management call hangs off: `GET /api/projects`, `POST /api/project`,
/// `GET /api/threads/<stem>/stats`, ...
export const API_BASE = `${HARNESS}api/`;

/// THE AG-UI ENDPOINT. An `HttpAgent` posts a `RunAgentInput` here and is answered
/// with SSE; nothing else in the app posts a run.
export const AGENT_URL = `${API_BASE}agent`;

/// One row of `GET /api/threads`. `threadId` is the log file's stem -- the id a
/// rebuild names -- and `lastActivity` is the file's mtime, epoch milliseconds.
export type ThreadSummary = {
  threadId: string;
  lastActivity: number;
  bytes: number;
};

export async function listThreads(t: Translate): Promise<ThreadSummary[]> {
  const res = await fetch(`${API_BASE}threads`);
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
    `${API_BASE}threads/${encodeURIComponent(threadId)}/rebuild`,
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
