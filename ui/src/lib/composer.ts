// The composer's three questions, typed thin: what this session may be switched
// to, which branch its directory is on, and the answers to changing either.
//
// Everything here is PER SESSION and that is a server-side fact, not a UI one:
// the model override lives in the harness's memory keyed by thread id, and the
// branch belongs to the directory the session is bound to. So every call carries
// a threadId and nothing here is cached across threads.
import { AGENT_URL } from "@/lib/threads";

/// The server's `{:error ..}` reason, when the body carries one -- the same
/// habit `lib/projects.ts` keeps, and for the same reason: the server's sentence
/// is the one worth showing.
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

/// What the model picker may offer. `provider`/`model`/`reasoning-effort` are the
/// session's CURRENT values and each is ABSENT when no tier named one -- the
/// harness reports 'nothing chose this' by omission, and a client that turned
/// that into null would be saying something the server did not.
///
/// `name` is the ID and `display-name` is the label a person gave that vendor --
/// TWO keys rather than one already-decided string, because what to show is this
/// side's business (see `providerLabel`) while what to SEND must be the id.
export type Choices = {
  provider?: string;
  model?: string;
  "reasoning-effort"?: string;
  "reasoning-efforts": string[];
  providers: { name: string; "display-name"?: string; models: string[] }[];
};

/// What to call a vendor on screen: its display name when it has one, its id
/// otherwise. The fallback lives here rather than on the server because it is a
/// rendering decision -- the id is always the truth, and a vendor nobody named
/// has no label to show.
export const providerLabel = (provider: { name: string; "display-name"?: string }): string =>
  provider["display-name"] ?? provider.name;

export async function choicesFor(threadId: string): Promise<Choices> {
  const res = await fetch(`${AGENT_URL}api/choices?threadId=${encodeURIComponent(threadId)}`);
  if (!res.ok) throw new Error(await reasonFrom(res));
  return res.json();
}

/// Change this session's selection. Only the named knobs move; `clear` drops the
/// session's own tier and puts it back on config.edn.
export async function setModel(
  threadId: string,
  change: { provider?: string; model?: string; "reasoning-effort"?: string; clear?: boolean },
): Promise<void> {
  const res = await fetch(`${AGENT_URL}api/model`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ threadId, ...change }),
  });
  if (!res.ok) throw new Error(await reasonFrom(res));
}

/// The session's directory as a working tree. `repo?` false is an ordinary
/// answer -- most directories are not repositories, and a session may have no
/// directory at all -- so this never throws for those.
export type GitState = {
  dir: string | null;
  "repo?": boolean;
  branch: string | null;
  branches: string[];
  dirty: number;
};

export async function gitStateFor(threadId: string): Promise<GitState> {
  const res = await fetch(`${AGENT_URL}api/git?threadId=${encodeURIComponent(threadId)}`);
  if (!res.ok) throw new Error(await reasonFrom(res));
  return res.json();
}

/// Move the session's directory onto BRANCH. Refused by name when git refuses --
/// a dirty tree, a branch held by another worktree -- and the refusal is git's
/// own sentence, which names the file in the way.
export async function switchBranch(threadId: string, branch: string): Promise<GitState> {
  const res = await fetch(`${AGENT_URL}api/git`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ threadId, branch }),
  });
  if (!res.ok) throw new Error(await reasonFrom(res));
  return res.json();
}
