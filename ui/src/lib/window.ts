// THE REPLICA'S WINDOW: what THIS page holds of a conversation, and the rules by which
// the server's frames change it. Ticket 06 of `.scratch/sessions-live-on-the-server`,
// and the shape ADR 0003 decides (`{entries, baseSeq, hasMore, revision}`).
//
// THE PAGE IS A READ-ONLY COPY AND THIS IS THE COPY. A conversation can be longer than
// anyone wants to send, so the server answers a WINDOW of it -- a tail page, then every
// entry that lands, then pages in front on request -- and this module is the arithmetic
// of holding one: what a frame does to it, where a page in front goes, and what to do
// when the server's answer no longer continues from what we have.
//
// TWO NUMBERS THAT ARE NOT THE SAME NUMBER, and telling them apart is most of this file:
//
//   the ENTRY's `seq`  -- the record offset of the line the entry arrived in. A fact
//                         about the conversation, minted by the server, stable across
//                         refreshes and processes (ADR 0003 decisions 1 and 9).
//   the window's `cursor` -- the newest entry's `seq` as far as this copy knows. It is
//                         how this copy says "I have through N" when it reconnects, and
//                         it only ever moves forward -- to a number the server has
//                         CONFIRMED, never to one this side predicted.
//
// AND ONE THAT IS NOT ABOUT POSITION AT ALL: `revision` counts how many times THIS
// window changed. It is the client's own bookkeeping (the reference implementation
// increments it on every publish), it says nothing about where anything is, and using
// a `seq` where a `revision` belongs is the mistake this file names so it does not have
// to happen.
import type { TFunction } from "i18next";

import type { WindowEntry, WindowFrame } from "./feed";

/// What a frame does to the window, as a value rather than as a side effect: the host
/// decides what to do about it (fetch, reconnect, say something), and the rules below
/// stay pure enough to test without a server.
export type Effect =
  /// Nothing to do: the frame was applied.
  | { kind: "none" }
  /// A HOLE: the frame begins after the newest entry this copy holds, so entries between
  /// the two were never seen. The host pulls the tail page and merges it (`aligned`) --
  /// the reader's place is kept, which is the whole difference between this and a reopen.
  | { kind: "align" }
  /// THE WINDOW IS OVER: the session was put away, swept, or taken over -- the frame
  /// says so, or the generation under it changed, or the server refused our cursor. The
  /// host opens the tail page again and SAYS it did.
  | { kind: "reopen"; reason: string }
  /// The window was rebuilt from a tail page that does not reach back to what we held:
  /// the entries in between could not be fetched, they are gone from this copy, and the
  /// count is how many. NEVER SILENT -- a copy that quietly drops what it was showing is
  /// the one failure a reader cannot see.
  | { kind: "rebuilt"; dropped: number };

export type Window = {
  readonly entries: readonly WindowEntry[];
  /// The record offset of the OLDEST entry this copy holds, or null while none of them
  /// has landed. This is what a page in front is asked for (`?beforeSeq`).
  readonly baseSeq: number | null;
  /// Whether the server has more in front of `baseSeq` -- i.e. whether the "show
  /// earlier" control is drawn at all.
  readonly hasMore: boolean;
  /// The newest record offset this copy knows the server confirmed.
  readonly cursor: number | null;
  /// WHICH WINDOW THIS IS (`harness.edge.sessions/generation` on the server): the claim
  /// the conversation is being served under. A frame from another generation is not a
  /// continuation of this window at all.
  readonly generation: string | null;
  /// How far along the conversation is, in the server's own words (`running`, `parked`,
  /// `settled`, `unfinished`) -- the one fact in a window that is not about position.
  readonly state: string | null;
  /// HOW MANY TIMES THIS WINDOW CHANGED. Ours, not the server's, and not a position.
  readonly revision: number;
};

/// THE NEWEST OF TWO CURSORS, with null meaning "nothing landed yet". It is a maximum
/// and not the frame's value because the cursor ONLY MOVES FORWARD: a frame that names a
/// number behind the one this copy holds is a server with an older view of the same
/// conversation, and stepping back would make the next reconnect re-read entries this
/// page already has (and make the next delta look like a hole).
function newest(a: number | null, b: number | null): number | null {
  if (a === null) return b;
  if (b === null) return a;
  return Math.max(a, b);
}

const idOf = (entry: WindowEntry): string | null => {
  const message = entry.message as { id?: unknown } | null | undefined;
  return typeof message?.id === "string" ? message.id : null;
};

/// WHETHER TWO ENTRIES ARE THE SAME THING, by value. The server hands its entries over as
/// fresh JSON on every frame, so identity says nothing; the one fact this side needs is
/// whether what arrived is the version it is already holding, and that is a comparison of
/// the entry as the wire spells it (its record offset and its message).
const sameEntry = (a: WindowEntry, b: WindowEntry): boolean =>
  a.seq === b.seq && JSON.stringify(a.message) === JSON.stringify(b.message);

/// MERGE WHAT ARRIVED INTO WHAT THIS COPY HOLDS, AND SAY WHETHER ANYTHING CHANGED.
///
/// THE IDENTITY IS THE MESSAGE'S OWN `:id` -- the same identity the server uses to decide
/// whether an action's entry entered (harness.edge.sessions/append!) and the same one the
/// record folds by. What an id is worth is TWO different answers, and getting them apart is
/// the whole of this function:
///
///   a version  -- WHILE A RUN IS BEING ANSWERED, the server hands the SAME id again and
///                again, each time a little longer: the conversation's fold numbers the
///                run's half-written group by the record's last line
///                (`harness.edge.replay/entries`), so every line the run writes re-sends
///                the answer so far under the id it has had all along. That arrival is a
///                NEW VERSION of the entry this copy holds: it takes that entry's place,
///                at the same position, and the draft on screen grows. Dropping it is the
///                freeze `.scratch/refreshed-turn-keeps-growing` is about; appending it
///                would draw the same answer twice.
///   a repeat   -- an entry whose line has not landed yet has no `seq`, so the cursor
///                cannot advance past it and a later frame reaches it byte for byte.
///                That one changes NOTHING: it is not a version, it is the same version,
///                and the window is handed back untouched (no revision, no import).
///
/// AN ENTRY WITH NO ID IS KEPT: guessing that two unnamed messages are the same one would
/// be inventing an identity this side has no licence to invent.
/// `where` SAYS WHERE AN ID THIS COPY HAS NEVER SEEN GOES -- the end of the window for a
/// delta, the front of it for a page in front (`prepended`).
function merged(
  held: readonly WindowEntry[],
  arriving: readonly WindowEntry[],
  where: "end" | "front",
): { entries: readonly WindowEntry[]; changed: boolean } {
  const at = new Map<string, number>();
  held.forEach((entry, index) => {
    const id = idOf(entry);
    if (id !== null) at.set(id, index);
  });
  // THE ARRAY IS COPIED LAZILY, so a frame that changed nothing costs no allocation and the
  // caller can tell by identity whether there is anything to import (see `applied`).
  let out: WindowEntry[] | null = null;
  const additions: WindowEntry[] = [];
  let changed = false;
  for (const entry of arriving) {
    const id = idOf(entry);
    const index = id === null ? undefined : at.get(id);
    if (index !== undefined) {
      const current = (out ?? held)[index];
      if (current !== undefined && !sameEntry(current, entry)) {
        out ??= [...held];
        out[index] = entry;
        changed = true;
      }
      continue;
    }
    // AN ID THIS FRAME HAS ALREADY ADDED IS NOT ADDED AGAIN: the wire does not send one
    // twice, and a second copy is exactly the duplicate an id exists to prevent. `-1` is
    // 'seen in this frame, not in what we hold', and the lookup above reads it as absent.
    if (id !== null) at.set(id, -1);
    additions.push(entry);
    changed = true;
  }
  if (!changed) return { entries: held, changed: false };
  const base = out ?? held;
  const entries = where === "front" ? [...additions, ...base] : [...base, ...additions];
  return { entries, changed: true };
}

/// THE WINDOW AS A FRAME STATES IT, for the frames that are a whole window rather than a
/// change to one: the feed's opening `window` frame, and a `tail`/`page` answer from the
/// page route.
export function windowFrom(frame: WindowFrame): Window {
  return {
    entries: frame.entries ?? [],
    baseSeq: frame.baseSeq ?? null,
    hasMore: frame.hasMore ?? false,
    cursor: frame.cursor ?? frame.baseSeq ?? null,
    generation: frame.generation ?? null,
    state: frame.state ?? null,
    revision: 1,
  };
}

/// APPLY A FRAME FROM THE FEED.
///
/// THE ORDER OF THE CHECKS IS THE ORDER OF THE QUESTIONS, and each one is a different
/// answer, which is why they are not merged:
///
///   1. `end` -- the server said the window is over.
///   2. another generation -- this window's numbers are about a conversation that is no
///      longer being served (ADR 0003 decision 6).
///   3. a whole window -- the feed's opening frame, or a page route's tail answer: the
///      same merge-or-rebuild question a repair answers (`aligned`), because the frame
///      is authoritative about where the window ends and says nothing about what this
///      copy holds in front of it.
///   4. a frame that does not continue from our cursor -- a HOLE, whose repair is the
///      tail page and keeps the reader's place (`align`).
///   5. otherwise it continues, and the entries are MERGED -- a new one appended, one this
///      copy already holds replaced IN PLACE by its newer version while a run writes it
///      (`merged`).
///
/// A FRAME THAT CHANGES NOTHING RETURNS THE WINDOW IT WAS GIVEN, by identity: callers
/// use that to decide whether there is anything to import, and a feed pushes plenty of
/// frames that only say "still here".
export function applied(window: Window, frame: WindowFrame): { window: Window; effect: Effect } {
  if (frame.type === "end") {
    return { window, effect: { kind: "reopen", reason: frame.reason ?? "gone" } };
  }
  if (
    frame.generation !== undefined &&
    frame.generation !== null &&
    window.generation !== null &&
    frame.generation !== window.generation
  ) {
    return { window, effect: { kind: "reopen", reason: "generation" } };
  }
  if (frame.type === "window") return aligned(window, frame);
  const base = frame.baseSeq ?? null;
  if (frame.type === "append" && base !== null && window.cursor !== null && base > window.cursor) {
    return { window, effect: { kind: "align" } };
  }
  const grown = merged(window.entries, frame.entries ?? [], "end");
  // THE CURSOR ONLY MOVES FORWARD AND ONLY TO A NUMBER THE SERVER SENT. A frame whose
  // entries are all still in the writer's queue carries no cursor, and the old one
  // stands: it is the honest answer to "what have I been told about".
  const cursor = newest(window.cursor, frame.cursor ?? null);
  const state = frame.state ?? window.state;
  const generation = frame.generation ?? window.generation;
  if (!grown.changed && cursor === window.cursor && state === window.state && generation === window.generation) {
    return { window, effect: { kind: "none" } };
  }
  return {
    window: {
      ...window,
      entries: grown.changed ? grown.entries : window.entries,
      cursor,
      generation,
      state,
      revision: window.revision + 1,
    },
    effect: { kind: "none" },
  };
}

/// PREPEND A PAGE: the answer to `?beforeSeq=` (the "show earlier" control).
///
/// IT GOES IN FRONT, and `baseSeq` moves to the page's own base while `cursor` does not
/// move at all -- the newest entry this copy holds is the same entry it held before.
/// The server cuts pages at arrival boundaries, so the page ends exactly where this
/// copy begins and there is nothing to reconcile (ADR 0003 decision 4).
export function prepended(window: Window, frame: WindowFrame): Window {
  const { entries } = merged(window.entries, frame.entries ?? [], "front");
  return {
    ...window,
    entries,
    baseSeq: frame.baseSeq ?? window.baseSeq,
    hasMore: frame.hasMore ?? false,
    generation: frame.generation ?? window.generation,
    revision: window.revision + 1,
  };
}

/// ALIGN WITH A TAIL PAGE, keeping the reader's place.
///
/// THE TWO ANSWERS ARE TWO CASES, and the difference is whether the page reaches back to
/// what this copy holds:
///
///   it does  -- the page continues from our cursor, so the entries in between are
///               appended and NOTHING this copy was showing is lost. This is the repair
///               for a dropped connection (the reference implementation's "fix reconnect
///               or seq gap through a tail page").
///   it does not -- there is a stretch of conversation this copy can neither show nor
///               fetch, so the window is rebuilt from the tail and the entries that fall
///               outside it are REPORTED (`rebuilt`), not dropped in silence.
export function aligned(window: Window, frame: WindowFrame): { window: Window; effect: Effect } {
  const page = windowFrom(frame);
  if (window.cursor === null || page.baseSeq === null || page.baseSeq <= window.cursor) {
    // CONTIGUOUS: keep what we hold, add what the page adds. `baseSeq` stays ours --
    // this copy still holds entries in front of the page's first one.
    const grown = merged(window.entries, page.entries, "end");
    const cursor = newest(window.cursor, page.cursor);
    const state = page.state ?? window.state;
    const generation = page.generation ?? window.generation;
    if (
      !grown.changed &&
      cursor === window.cursor &&
      state === window.state &&
      generation === window.generation
    ) {
      return { window, effect: { kind: "none" } };
    }
    return {
      window: {
        ...window,
        entries: grown.changed ? grown.entries : window.entries,
        cursor,
        generation,
        state,
        revision: window.revision + 1,
      },
      effect: { kind: "none" },
    };
  }
  return {
    window: page,
    effect: { kind: "rebuilt", dropped: window.entries.length },
  };
}

/// WHAT THIS COPY HOLDS THAT THE CONVERSATION DOES NOT, by message id.
///
/// THE COPY CANNOT BE AHEAD OF THE SERVER ANY MORE -- this side stopped being the author
/// of the conversation (ticket 03), so every entry a window shows arrived in a frame or a
/// page. A non-empty answer here therefore means something this side drew is not in the
/// conversation the server just described, and the ONE thing that must not happen is a
/// silent drop: the host says it.
///
/// ONLY WITHIN THE RANGE THE ANSWER ACTUALLY COVERS, and that is the whole subtlety: a
/// page is a WINDOW, not the conversation (`frame.baseSeq` in, newest entry out), so an
/// entry this copy holds BELOW that range is not evidence of anything -- it is simply
/// not what was asked about, and calling it missing would make every reopen of every long
/// conversation announce that the history had been deleted. Two kinds are outside the
/// range and both are silent: an entry numbered below `baseSeq`, and an entry whose `seq`
/// is still null (that line has not landed, so the server has never been asked about it).
export function aheadOf(window: Window, frame: WindowFrame): string[] {
  const base = frame.baseSeq ?? null;
  if (base === null) return [];
  const served = new Set((frame.entries ?? []).map(idOf).filter((id): id is string => id !== null));
  return window.entries
    .filter((entry) => entry.seq !== null && entry.seq >= base)
    .map(idOf)
    .filter((id): id is string => id !== null && !served.has(id));
}

/// WHAT THE PAGE OWES THE READER WHEN THE WINDOW CHANGED UNDER THEM. Three kinds, and
/// none of them is optional: a copy that reopens, drops, or is ahead of the conversation
/// and says nothing is showing something that is not true (ticket 06's judgements 2 and
/// 5 -- "say it, never drop it in silence").
export type WindowNotice =
  /// The window was over (put away, taken over, or refused) and the tail was opened
  /// again: the reader is looking at the newest messages now.
  | { kind: "reopened" }
  /// The window could not be fetched across and was rebuilt from a tail page: `dropped`
  /// entries this copy was holding are not in it.
  | { kind: "rebuilt"; dropped: number }
  /// This copy was showing entries the conversation no longer has.
  | { kind: "ahead"; count: number }
  /// A read failed. The sentence is the SERVER's own, passed through whole -- the same
  /// rule every other refusal on this page follows (`lib/threads.ts`'s `refusalFrom`).
  | { kind: "failed"; message: string };

/// The translator these sentences are worded through: the caller's own, which is the
/// `shell` face (the frame every other face is drawn inside) -- the same face
/// `lib/record-health.ts` words its strip through.
type Translate = TFunction<"shell">;

export const windowNotice = (t: Translate, notice: WindowNotice | null): string | null => {
  if (notice === null) return null;
  switch (notice.kind) {
    case "reopened":
      return t("window.reopened");
    case "rebuilt":
      return t("window.rebuilt", { count: notice.dropped });
    case "ahead":
      return t("window.ahead", { count: notice.count });
    case "failed":
      return notice.message;
  }
};
