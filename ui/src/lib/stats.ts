// The composer's session numbers, typed thin: how many turns and model calls this
// session has run, how fast, how many tokens, how much of it was the vendor's
// cache.
//
// It reads the RECORD, not the conversation: the answer is folded server-side from
// the session's jsonl log (GET /api/threads/<stem>/stats), which is the only place
// those facts exist -- the vendor's usage never reaches the client, and the client
// must not estimate it. See .scratch/composer-status/spec.md.
//
// AND THE LIVE HALF IS PUSHED (ticket 04b of `.scratch/turn-and-model-events`). The
// server sends a `model/end` fact down the session's own socket with the numbers it
// had at that moment, so the strip keeps up DURING a run instead of asking again after
// every message -- `withPushedNumbers` is how the two halves are put together.
import { API_BASE } from "@/lib/threads";
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
  const res = await fetch(`${API_BASE}threads/${encodeURIComponent(threadId)}/stats`);
  if (!res.ok) return null;
  return (await res.json()) as StatsPayload;
}

/// THE SNAPSHOT, WITH THE PUSHED NUMBERS OVER IT.
///
/// The snapshot (`GET /stats`) is what the strip has when the page opens and after any
/// ask; the push carries what the SESSION's own folds said at the end of one model call.
/// They are the same folds -- the server folded them once and answered both -- so the
/// push is not a second truth, it is the SAME answer arriving sooner.
///
/// WHICH KEYS THE PUSH OWNS, AND WHY NOT ALL OF THEM. `numbers` carries the four cells
/// and the context ring (`steps` / `usage` / `cacheHitPercent` /
/// `outputTokensPerSecond` / `context`), and REPLACES them. `turns` is not on it: a turn
/// is counted where a run is opened, and the push is about a model call.
/// `incomplete` and `record` are facts about the LOG as the reader found it, not numbers
/// a fold can update. Everything else stays the snapshot's.
///
/// A KEY THE PUSH DOES NOT CARRY IS NOT DELETED: 'not reported' is not zero, and the
/// strip draws absences by leaving them out (`lib/format.ts`'s `nCells`).
export function withPushedNumbers(
  snapshot: StatsPayload | null,
  numbers: Partial<StatsPayload> | null | undefined,
): StatsPayload | null {
  if (numbers === null || numbers === undefined) return snapshot;
  if (snapshot === null) return numbers as StatsPayload;
  return { ...snapshot, ...numbers };
}
