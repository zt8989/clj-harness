// The composer's session numbers, typed thin: how many turns and model calls this
// session has run, how fast, how many tokens, how much of it was the vendor's
// cache.
//
// It reads the RECORD, not the conversation: the answer is folded server-side from
// the session's jsonl log (GET /api/threads/<stem>/stats), which is the only place
// those facts exist -- the vendor's usage never reaches the client, and the client
// must not estimate it. See .scratch/composer-status/spec.md.
import { AGENT_URL } from "@/lib/threads";
import type { StatsPayload } from "@/lib/format";

/// This session's numbers.
///
/// A 404 IS AN ORDINARY ANSWER HERE, and it is the reason this function returns
/// null instead of throwing: a session that has never run has no log, so there is
/// nothing to fold, and "no numbers yet" is exactly what the strip should show by
/// drawing nothing. Any other failure -- a broken log, the server down -- is also
/// null, because the strip has no way to say anything useful about it and a red
/// line under the composer is not the place to try.
export async function statsFor(threadId: string): Promise<StatsPayload | null> {
  const res = await fetch(`${AGENT_URL}api/threads/${encodeURIComponent(threadId)}/stats`);
  if (!res.ok) return null;
  return (await res.json()) as StatsPayload;
}
