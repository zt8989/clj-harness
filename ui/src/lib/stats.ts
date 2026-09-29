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
/// WHICH KEYS THE PUSH OWNS, AND WHY NOT ALL OF THEM. `numbers` carries the session's
/// numbers as its folds had them at that moment (`turns` / `steps` / `usage` /
/// `cacheHitPercent` / `outputTokensPerSecond` / `context`), and REPLACES them.
/// `incomplete` and `record` are facts about the LOG as the reader found it, not numbers
/// a fold can update. Everything else stays the snapshot's.
///
/// `turns` IS ON THE PUSH NOW, and it arrived because of a bug rather than a plan: the first
/// fact of a just-started conversation can land BEFORE the page's first ask answers, so the
/// strip drew a payload with no turn count -- and the cell for it came out as the raw key
/// `stats.turns` (see `StatsPayload.turns`). The count is the same fold's answer as the
/// snapshot's, so carrying it is one answer arriving sooner, not a second clock.
///
/// AND A PAYLOAD THAT HAS ONLY EVER BEEN PUSHED IS STILL TREATED AS WHAT IT IS -- A PAYLOAD:
/// the cells it carries are drawn, and a cell it does not carry is left out. The strip never
/// fills one in (`statsCells`).
///
/// A KEY THE PUSH DOES NOT CARRY IS NOT DELETED: 'not reported' is not zero, and the
/// strip draws absences by leaving them out (`lib/format.ts`'s `nCells`).
///
/// AND THE SAME RULE ONE LEVEL DOWN, WHICH TOOK A BROWSER TO LEARN (measured 2026-09-29): the
/// `context` section is merged FIELD BY FIELD rather than replaced. Every key in it is
/// independently optional -- `harness.edge.context/state->context` says 'every key is absent
/// when it would be a guess' -- and the one that is routinely absent is `parts`: the
/// apportioned buckets need the run's message side, which the kernel writes one beat AFTER the
/// terminal frame the push rides on. Spreading the section DELETED the split the snapshot had,
/// so the ring went from three coloured arcs to the single-colour 'no split yet' arc
/// (near-black `text-foreground` in the light theme) -- and it STAYED that way, because
/// `pushed` is never cleared: the fresh snapshot the ask after a run brings was overridden by
/// the same stale section for the rest of the session.
export function withPushedNumbers(
  snapshot: StatsPayload | null,
  numbers: Partial<StatsPayload> | null | undefined,
): StatsPayload | null {
  if (numbers === null || numbers === undefined) return snapshot;
  if (snapshot === null) return numbers as StatsPayload;
  return {
    ...snapshot,
    ...numbers,
    // THE ONE SECTION THAT IS MERGED RATHER THAN REPLACED (see the paragraph above): a push
    // that does not report the split must not take away the one the snapshot had.
    context:
      numbers.context === undefined
        ? snapshot.context
        : { ...snapshot.context, ...numbers.context },
  };
}
