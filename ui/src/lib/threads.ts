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

import type { RecordHealth } from "./record-health";

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

/// THE DOWNLINK'S ADDRESS: the same origin as the management calls, spoken as a
/// WebSocket. `PATH` names the category (`events.mux`, `events.host` -- ADR 0004), and for
/// `mux` the handshake URL is where the subscription is declared (the socket carries no
/// client message). `VITE_AGENT_URL` unset leaves `HARNESS` relative, and a relative URL
/// resolves against the document -- so the built page and the dev loop both work with no
/// port written down, exactly as `API_BASE` does.
export function downlinkUrl(path: string, params: URLSearchParams): string {
  const base = HARNESS.replace(/^http/, "ws").replace(/\/+$/, "");
  const query = params.toString();
  return `${base}/api/${path.replace(/^\/+/, "")}${query === "" ? "" : `?${query}`}`;
}

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
  /// Same field, same absence, as `ThreadSofar`: a rebuilt conversation whose
  /// record could not be written says so here too.
  record?: RecordHealth;
  /// THE CONVERSATION'S OWN STATE, when this process is HOLDING it -- `running` /
  /// `parked` / `settled` / `unfinished`, the same words `ThreadSofar` and a window
  /// carry (`harness.edge.http/live-state`, which is where the live branch reads it).
  ///
  /// IT IS HERE SO A CLIENT CAN TELL A RUN IS IN FLIGHT ON A DOOR THAT OPENS NO FEED.
  /// A rebuild hands over a SNAPSHOT, so a page that opened a running session from the
  /// sidebar had no server word to close the composer's gate with and its Send was
  /// answered 409. `running` is the one word that matters: the client looks through the
  /// window door instead of holding the snapshot. ABSENT on the record branch, which is
  /// a conversation nobody here is holding (see `rebuild-post`).
  state?: SofarState;
};

/// THE SERVER'S OWN REFUSAL, when the body carries one, and THIS SIDE'S sentence when
/// it does not. Every management route answers a refusal as `{:error ..}` -- the
/// cut-off log's 400 names the run and the way out -- and that reason is the sentence
/// the UI shows, passed through whole rather than wrapped (a paraphrase would be one
/// more thing to distrust). Only a body with no `error` at all -- a proxy's 502, a
/// route that answered empty -- leaves this side speaking, and then it speaks the
/// interface's language: `HTTP 500` is a fact about the wire.
///
/// EXPORTED FOR THE WINDOW (`lib/feed.ts`), which is the other half of this wire and
/// reads refusals the same way: one place decides what a body without a sentence says.
export async function refusalFrom(res: Response, t: Translate): Promise<string> {
  const body: unknown = await res.json().catch(() => undefined);
  return body !== undefined &&
    typeof body === "object" &&
    body !== null &&
    "error" in body &&
    typeof body.error === "string"
    ? body.error
    : t("http.status", { status: res.status });
}

export async function rebuildThread(threadId: string, t: Translate): Promise<RebuiltThread> {
  const res = await fetch(
    `${API_BASE}threads/${encodeURIComponent(threadId)}/rebuild`,
    { method: "POST" },
  );
  if (!res.ok) throw new Error(await refusalFrom(res, t));
  return (await res.json()) as RebuiltThread;
}

/// STOP THE RUN THIS PROCESS IS ANSWERING FOR THREAD-ID -- `POST /api/threads/<id>/cancel`.
///
/// THE ONE ACTION IN THIS MODULE THAT IS NOT A READ, and the one the composer's Stop is:
/// a run belongs to the PROCESS, so a page that opened somebody else's running conversation
/// has no way to end it locally -- `abortRun` closes THIS page's fetch, and there is no
/// fetch. The server rings the running run's own stop switch
/// (`harness.edge.sessions/cancel!`), and what comes back is the id of the run it rang.
///
/// A REFUSAL (409) IS THE OTHER REAL ANSWER: a conversation no run is going in has
/// nothing to stop -- it may have ended between this page's last state frame and the
/// press -- and the server's sentence says so (see `harness.edge.http/cancel-post`).
/// What this does NOT wait for is the run's terminal: the stop is a signal, and the state
/// the page needs next arrives on the window's feed like any other change.
export async function stopRun(threadId: string, t: Translate): Promise<{ runId: string }> {
  const res = await fetch(
    `${API_BASE}threads/${encodeURIComponent(threadId)}/cancel`,
    { method: "POST" },
  );
  if (!res.ok) throw new Error(await refusalFrom(res, t));
  return (await res.json()) as { runId: string };
}

/// WHERE A CONVERSATION HAS GOT TO, as `GET /api/threads/<id>/sofar` states it -- and as
/// a window states it too (`lib/window.ts`), because it is the same fact about the same
/// conversation and both reads carry it.
///
/// THREE ANSWERS AND NOT A BOOLEAN, because the client has three things to do and two
/// of them are not 'normal'. `running` -- a run is being answered in the process right
/// now: show what has arrived, and (ticket 04) do not send. `parked` -- the
/// conversation has stopped to ask a human and is waiting for a decision. `settled`
/// -- nothing is going on, and this is the ordinary case.
///
/// `unfinished` IS THE FOURTH, and it is the RECORD's word rather than the process's: a
/// log that ends mid-run says exactly that, and whether anybody is still writing it is
/// the question `running` answers. It has always been on the wire (`replay/record-state`)
/// and was missing from this type until ticket 06 needed it for window frames.
///
/// THE SERVER-PROCESS FACT, NOT THIS TAB'S. A refreshed page's own runtime has never
/// run anything, so its `isRunning` is false while the process is in the middle of a
/// run; this is the half that says so. It is also why the state cannot be derived
/// from the message list: a partial conversation and a finished one differ by a fact
/// that is not in the file.
export type SofarState = "running" | "parked" | "settled" | "unfinished";

/// What `GET /api/threads/<id>/sofar` answers: what has been recorded so far, and how
/// far along it is. `openRuns` names the runs still being written (present only for
/// `running`); `interrupts` is what the newest run stopped on (present only for
/// `parked`).
export type ThreadSofar = {
  threadId: string;
  messages: readonly unknown[];
  context: readonly unknown[];
  state: SofarState;
  openRuns?: readonly string[];
  interrupts?: readonly unknown[];
  /// THE RECORD'S OWN HEALTH, and its absence is the ordinary answer -- only a
  /// session whose writer gave up on a line carries one (see `lib/record-health.ts`).
  record?: RecordHealth;
};

/// READ THE CONVERSATION, WITHOUT TOUCHING IT. The read a page that has just landed
/// on somebody else's session uses: unlike `rebuildThread` it does not hand the
/// conversation over, it does not close a cut-off log off, and it answers for a run
/// that is still going. See harness.edge.http/sofar-get for the three answers and the
/// one refusal.
///
/// THE REFUSAL IS THE CUT-OFF LOG, and it is the one case where this and rebuild
/// disagree instead of overlapping: a log that ends mid-run with nothing running it
/// needs the repair only rebuild performs (`close-off-open-run!`), so the server
/// refuses this read and names that door. A caller that has no human to relay it to
/// -- the mount restore -- follows it (see `App`'s `restore`).
export async function sofarThread(threadId: string, t: Translate): Promise<ThreadSofar> {
  const res = await fetch(`${API_BASE}threads/${encodeURIComponent(threadId)}/sofar`);
  if (!res.ok) throw new Error(await refusalFrom(res, t));
  return (await res.json()) as ThreadSofar;
}
