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
