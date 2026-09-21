// `GET /api/projects`: the sidebar's listing, typed thin.
//
// EVERY ROW HERE IS A STORE FACT, except one. That is the rule this shape exists to
// keep (the owner's: "左侧所有会话信息都是从 sqlite 加载，除了运行状态"): which projects
// and sessions exist, which session belongs where, which are archived, what each is
// called and WHEN IT WAS LAST SENT TO all come out of the sqlite rows; the only other
// source is the process's live-runs registry, which answers `running` because no file
// can. Nothing in this payload is a stat().
//
// IT USED TO BE A JOIN, and giving that up is the feature: the listing walked the log
// tree per row (size, mtime) and, for a task, the whole projects directory by stem --
// so one refresh was a SELECT plus a walk. A refresh is now a SELECT plus a registry
// lookup, and the disk half of every row is gone (`bytes`, `lastActivity`).
//
// THE ANSWER IS TWO LISTS, and they are the sidebar's two blocks: `projects`, each
// with its sessions, and `tasks` -- conversations with no project at all, flat.
// Every session of every project arrives in one answer, and every task with them.
// That is affordable at this scale and it is what makes the sidebar a single render:
// no per-project fetch, no page cursor, no half-drawn list, and no second request
// that could disagree with the first about which conversations exist.
//
// `lastSentAt` IS NULLABLE, and the null is meaningful rather than defensive: no send
// has ever reached this session, so there is no time to draw. That is the shape of a
// row somebody registered and never used (an old client's, or one whose log was
// removed by hand) -- never a zero, which would claim a send at the epoch. The row
// answers it with a word (`session.neverRun`); the LISTING answers it by sorting it
// last, because "never used" is not "brand new" any more -- a session is created by
// its first send, so nothing recent sits at NULL.
import type { TFunction } from "i18next";

import { API_BASE } from "@/lib/threads";

/// The translator a FAILURE is worded through, PINNED TO THE `errors` FACE.
///
/// i18next brands a translator with the namespace it was bound to, so this is what
/// makes only the errors catalog's keys compile here -- the call site hands over a
/// translator it got from `useTranslation("errors")`, and a shell translator will
/// not typecheck.
type Translate = TFunction<"errors">;

/// One session, as the sidebar needs it.
export type SessionSummary = {
  threadId: string;
  archived: boolean;
  /// WHETHER A RUN IS IN FLIGHT FOR THIS SESSION, and the ONE field here that is not a
  /// store fact: it is read off the server process's live-runs registry, which is the
  /// only place that knows. It is why the listing can light a spinner on a conversation
  /// this browser has never opened -- a run belongs to the process, not to the tab that
  /// started it.
  ///
  /// IT IS A SNAPSHOT, like every other field: the row also draws the page's own
  /// registry (`statuses`), which is fresher for the sessions THIS page is running.
  /// The two are ORed rather than ranked (see `sidebar.tsx`), because either one being
  /// true means a run is in flight.
  running: boolean;
  /// WHEN SOMEBODY LAST PRESSED SEND IN THIS CONVERSATION, in epoch milliseconds, or
  /// null when nothing has ever been sent to it (see this file's header).
  ///
  /// IT IS THE MOMENT OF THE SEND, NOT THE MOMENT OF THE LAST WRITE. A run that takes
  /// five minutes stamps this at the start and leaves it there -- which is what "上次发送
  /// 时间" means, and what makes it different from the file's mtime that used to be
  /// drawn here. It is written by every run that arrives (the same statement that names
  /// the session, `cap.project/remember-send!`), and the rows that predate the column
  /// were backfilled from their logs once, in the migration.
  ///
  /// HOW IT IS DRAWN is `lib/relative-time.ts`'s ladder; the exact instant goes into
  /// the row's tooltip.
  lastSentAt: number | null;
  /// WHAT THE PERSON FIRST SAID IN THIS CONVERSATION, as the STORE remembers it
  /// (`sessions.title`, written once by the first run that arrives), or null when
  /// the session has not been named yet -- it never ran, or it ran before the
  /// column existed. RAW AND LONG: the server keeps up to 200 code points as a
  /// storage guard, and how much of it a row shows is `lib/session-title.ts`'s
  /// question (`titleOf`), asked by whoever draws it.
  ///
  /// IT IS A SENTENCE SOMEBODY TYPED, kept in the store because the sidebar draws forty
  /// rows and cannot open forty logs -- see the migration's docstring in
  /// `harness.infra.db` for the whole argument, including what it costs.
  firstUserText: string | null;
};

/// One project: a directory this home knows, and the sessions in it.
export type ProjectSummary = {
  projectId: number;
  /// The directory's CANONICAL path -- the project's identity. Two spellings of
  /// one directory share this value, which is why it, and not a name, is what
  /// the server keys projects by.
  path: string;
  sessions: readonly SessionSummary[];
};

/// THE WHOLE SIDEBAR, in one answer: the projects, and the tasks.
///
/// TWO HALVES RATHER THAN TWO CALLS, and that is a decision about what a snapshot
/// is: the sidebar draws one moment, and two requests are two moments that can
/// disagree about which conversation exists. The mount restore reads the same
/// payload (`lib/session-memory.ts`), so "is the session I remember still a
/// session" is answered by the same read that draws the list.
export type SidebarListing = {
  projects: readonly ProjectSummary[];
  /// THE TASKS: conversations with no project and no memory of one, flat and
  /// ungrouped -- the half that makes the sidebar two blocks instead of one.
  /// Same row shape as a project's sessions, because they are the same kind of
  /// thing: what differs is only that nothing owns them.
  tasks: readonly SessionSummary[];
};

export async function listSidebar(t: Translate): Promise<SidebarListing> {
  const res = await fetch(`${API_BASE}projects`);
  if (!res.ok) throw new Error(t("http.listingProjects", { status: res.status }));
  return res.json();
}

/// Make one conversation a session of this home, with no project (POST
/// /api/sessions). This is what the sidebar's "new task" does before a word has
/// been typed, and it is FIND-OR-CREATE on the server: an id that already exists
/// -- belonging to a project even -- is left exactly as it is, so this can never
/// unbind anything.
///
/// ONE CONVERSATION PER CALL, named by the id the CLIENT minted: this product has
/// never minted ids on the server, and a task is not an exception.
export async function startTask(threadId: string, t: Translate): Promise<string> {
  const res = await fetch(`${API_BASE}sessions`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ threadId }),
  });
  if (!res.ok) throw new Error(await reasonFrom(res, t));
  const body = (await res.json()) as { threadId: string };
  return body.threadId;
}

/// A project's display name: its last path segment, or the whole path when there
/// is nothing to cut (the filesystem root). Both separators are handled because
/// the server's canonical paths are whatever the host platform produces, and the
/// browser does not know which one that was.
export function projectName(path: string): string {
  const parts = path.split(/[/\\]/).filter((part) => part !== "");
  return parts.length === 0 ? path : parts[parts.length - 1];
}

/// The server's `{:error ..}` reason, when the body carries one. Every
/// management route answers a refusal that way, and the reason is the sentence
/// the UI is supposed to show -- a wrapper's paraphrase would be one more thing
/// to distrust, so this only digs it out.
///
/// THE SERVER'S SENTENCE WINS, AND IS NEVER TRANSLATED. Only when the body carries
/// no `error` at all -- a proxy's 502, a route that answered empty -- does this side
/// speak, and then the fallback is this interface's sentence in its own language
/// (see docs/architecture/client.md's boundary section): `HTTP 500` is a fact about
/// the wire, not a message from the server.
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

/// Make DIR a project of this home. Answers the project's CANONICAL path and its
/// id, and answers the SAME project when the directory is already listed -- the
/// server's find-or-create, so re-adding something a person forgot was there is
/// not an error they have to fix.
///
/// This is the SINGULAR/PLURAL distinction at the client end: this adds a
/// DIRECTORY (POST /api/projects, plural), while binding a session to one is
/// POST /api/project (singular) -- see `bindThread` below.
export type AddedProject = { projectId: number; path: string };

export async function addProject(dir: string, t: Translate): Promise<AddedProject> {
  const res = await fetch(`${API_BASE}projects`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ dir }),
  });
  if (!res.ok) throw new Error(await reasonFrom(res, t));
  return res.json();
}

/// What pops up when this machine has NO folder dialog to open. It carries the
/// server's sentence, which is what gets shown and what the typed path answers.
export class PickerUnavailableError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "PickerUnavailableError";
  }
}

/// The native folder dialog, opened by the server. Answers the chosen absolute
/// path, or null when the human cancelled -- and CANCELLING IS NOT AN ERROR, so
/// it is a null rather than a rejection: nothing failed, someone changed their
/// mind.
///
/// A machine that cannot open the dialog at all throws `PickerUnavailableError`,
/// which is the THIRD answer and not a flavour of either of the others. It has
/// to be its own thing because the two ask for different next moves: silence
/// after a cancellation, and somewhere to type a path after this. Collapsing
/// them is what made a dead folder button look like a slow one.
///
/// The chosen path is only ever FILLED IN. Nothing is bound or created here: the
/// person still submits the form, which is what keeps "picking a folder" from
/// being an accidental one-step commit.
export async function pickFolder(t: Translate): Promise<string | null> {
  const res = await fetch(`${API_BASE}project/pick`, { method: "POST" });
  if (res.status === 501) throw new PickerUnavailableError(await reasonFrom(res, t));
  if (!res.ok) throw new Error(await reasonFrom(res, t));
  const body = (await res.json()) as { dir?: string | null };
  return body.dir ?? null;
}

/// Bind THREAD-ID's session to DIR (POST /api/project, singular). This is what
/// makes a session belong to a project, and for a brand-new session it is also
/// what makes the session exist at all: the store learns about a conversation
/// when something asks for it to belong somewhere.
export async function bindThread(threadId: string, dir: string, t: Translate): Promise<string> {
  const res = await fetch(`${API_BASE}project`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ threadId, dir }),
  });
  if (!res.ok) throw new Error(await reasonFrom(res, t));
  const body = (await res.json()) as { dir: string };
  return body.dir;
}

/// Take a directory out of this home's project list
/// (POST /api/projects/<canonical path>/remove). Answers the path and how many
/// sessions it released.
///
/// THE PATH IS THE PROJECT'S IDENTITY, not its `projectId`, and that is what this
/// client has: every project row carries `path`, the canonical form, and the
/// route is keyed the way the thing is known. It is encoded as ONE path segment,
/// which the server decodes back into a path -- a directory with slashes in it is
/// ordinary, so the encoding is not optional.
///
/// A REMOVAL, NOT A DELETION. The row goes, its sessions become unbound, and
/// nothing under `projects/<workspace>/` is opened -- the jsonl files keep their
/// bytes and their mtimes. Re-adding the same directory adopts the sessions back.
/// So a caller must not follow this with anything file-shaped either, and the
/// answer's `unbound` count is the only thing that changed that is worth saying
/// out loud.
export type RemovedProject = { path: string; unbound: number };

export async function removeProject(path: string, t: Translate): Promise<RemovedProject> {
  const res = await fetch(
    `${API_BASE}projects/${encodeURIComponent(path)}/remove`,
    { method: "POST" },
  );
  if (!res.ok) throw new Error(await reasonFrom(res, t));
  return res.json();
}

/// Archive THREAD-ID's session, or bring it back (POST /api/threads/<id>/archive).
/// One call for both directions, because they are one column write -- the path
/// names the action, the body names the direction.
///
/// NOTHING HERE TOUCHES THE LOG, and that is the whole promise of archiving: the
/// flag lives in the store, the jsonl is left byte-for-byte and mtime-for-mtime
/// alone. So this call is small and cheap, and a client must not be tempted to
/// follow it with anything file-shaped.
///
/// The answer is the flag as the STORE now holds it, echoed back rather than
/// assumed -- see `project/archive!` for why the value coming out, not the one
/// going in, is what a caller should believe.
export async function setArchived(threadId: string, archived: boolean, t: Translate): Promise<boolean> {
  const res = await fetch(`${API_BASE}threads/${encodeURIComponent(threadId)}/archive`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ archived }),
  });
  if (!res.ok) throw new Error(await reasonFrom(res, t));
  const body = (await res.json()) as { archived: boolean };
  return body.archived;
}
