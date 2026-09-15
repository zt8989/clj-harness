// `GET /api/projects`: the sidebar's listing, typed thin.
//
// One call answers the whole sidebar, because the sidebar's two halves come from
// two different places on the server and only the server can join them: the STORE
// says which projects and sessions exist, which session belongs where and which
// are archived; the TREE says how big each log is and when it last changed. A
// client that tried to join them itself would need the log directory layout, and
// that knowledge belongs on the side that writes the files.
//
// Every session of every project arrives in one answer. That is affordable at
// this scale and it is what makes the sidebar a single render: no per-project
// fetch, no page cursor, no half-drawn list.
//
// `lastActivity` and `bytes` are NULLABLE, and that nullability is meaningful
// rather than defensive: a session with no log yet -- one just created, before
// its first run -- is a row with no disk facts. See the row component for how
// that is drawn; the one thing it must never become is a zero-byte file, which
// would be a lie about a broken log.
import { AGENT_URL } from "@/lib/threads";

/// One session, as the sidebar needs it.
export type SessionSummary = {
  threadId: string;
  archived: boolean;
  /// The log's mtime in epoch milliseconds, or null when there is no log yet.
  lastActivity: number | null;
  /// The log's size in bytes, or null when there is no log yet.
  bytes: number | null;
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

export async function listProjects(): Promise<ProjectSummary[]> {
  const res = await fetch(`${AGENT_URL}api/projects`);
  if (!res.ok) throw new Error(`listing projects failed: HTTP ${res.status}`);
  return res.json();
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

/// Make DIR a project of this home. Answers the project's CANONICAL path and its
/// id, and answers the SAME project when the directory is already listed -- the
/// server's find-or-create, so re-adding something a person forgot was there is
/// not an error they have to fix.
///
/// This is the SINGULAR/PLURAL distinction at the client end: this adds a
/// DIRECTORY (POST /api/projects, plural), while binding a session to one is
/// POST /api/project (singular) -- see `bindThread` below.
export type AddedProject = { projectId: number; path: string };

export async function addProject(dir: string): Promise<AddedProject> {
  const res = await fetch(`${AGENT_URL}api/projects`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ dir }),
  });
  if (!res.ok) throw new Error(await reasonFrom(res));
  return res.json();
}

/// The native folder dialog, opened by the server. Answers the chosen absolute
/// path, or null when the human cancelled -- and CANCELLING IS NOT AN ERROR, so
/// it is a null rather than a rejection: nothing failed, someone changed their
/// mind.
///
/// The chosen path is only ever FILLED IN. Nothing is bound or created here: the
/// person still submits the form, which is what keeps "picking a folder" from
/// being an accidental one-step commit.
export async function pickFolder(): Promise<string | null> {
  const res = await fetch(`${AGENT_URL}api/project/pick`, { method: "POST" });
  if (!res.ok) throw new Error(await reasonFrom(res));
  const body = (await res.json()) as { dir?: string | null };
  return body.dir ?? null;
}

/// Bind THREAD-ID's session to DIR (POST /api/project, singular). This is what
/// makes a session belong to a project, and for a brand-new session it is also
/// what makes the session exist at all: the store learns about a conversation
/// when something asks for it to belong somewhere.
export async function bindThread(threadId: string, dir: string): Promise<string> {
  const res = await fetch(`${AGENT_URL}api/project`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ threadId, dir }),
  });
  if (!res.ok) throw new Error(await reasonFrom(res));
  const body = (await res.json()) as { dir: string };
  return body.dir;
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
export async function setArchived(threadId: string, archived: boolean): Promise<boolean> {
  const res = await fetch(`${AGENT_URL}api/threads/${encodeURIComponent(threadId)}/archive`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ archived }),
  });
  if (!res.ok) throw new Error(await reasonFrom(res));
  const body = (await res.json()) as { archived: boolean };
  return body.archived;
}
