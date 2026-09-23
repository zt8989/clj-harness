// THE PARENT'S HALF OF A DELEGATION: which tool call opened which subagent's
// conversation, as a value the transcript can look up per card.
//
// `GET /api/threads/<stem>/delegations` is the answer (ticket 03): one row per
// delegation the record holds, keyed by the `toolCallId` the kernel made visible
// inside the tool body. THE KEY IS THE POINT -- pairing a card with a child
// session by position ("the second delegation in this conversation") would be
// wrong the moment two of them run at once, which is exactly what a subagent is
// for. The route's envelope is `{:threadId .. :delegations [..]}`; the array is
// what this file reads.
//
// ------------------------------------------------------ one parent, one answer
//
// ONE PARENT, ONE REQUEST, AND EVERY CARD TOLD. The rows live in a module-level map
// keyed by the parent session's id and are published to subscribers through
// `useSyncExternalStore`, so a transcript with twenty `agent` calls does not make
// twenty requests -- and a chat that re-renders per streamed delta makes none. An
// answer that already names a card's call is all that card will ever ask for.
//
// ------------------------------------------------- and the one race it must lose
//
// THE ROW IS WRITTEN *AFTER* THE CARD IS DRAWN, WHICH IS THE WHOLE REASON THIS FILE
// HAS A RETRY IN IT. A card appears when the tool call starts STREAMING; the
// `delegation` line is written when the kernel executes that call, a moment later
// (`run-subagent!`). So the first read is entitled to answer "nothing yet" about a
// delegation that is about to exist -- measured in a browser: the answer was an empty
// list, the 200 was right there in the network tab, and the card stayed plain text
// forever. A cache that treats "not found" as final is a door that never opens for
// the one delegation it was built for.
//
// SO AN ID THE ANSWER DOES NOT NAME IS ASKED ABOUT AGAIN, A FEW TIMES, AND THE SPEC'S
// OWN ACCEPTANCE LINE IS WHY IT IS WORTH THE REQUESTS ("右栏在子agent 还没跑完时就已经
// 看到了它的一部分"): the mirror has to open WHILE the child works, and the only
// moment the page can learn the child's id is when that row lands milliseconds later.
// The budget is `ATTEMPTS` spaced `RETRY_MS` apart -- the gap between a card being
// drawn and the row being written, not a poll of a running subagent -- plus ONE last
// ask when the call settles, in case every retry lost the race. After that the answer
// stands: a call this home's record does not name (an older record, or one the route
// refused) keeps its plain subject, which is the same thing an absent row means.
//
// THE RETRY IS IDEMPOTENT PER CALL, WHICH IS NOT A DETAIL. `subscribe` runs on every
// render -- the callback this hook hands React is a fresh closure each time, which is
// React's own contract for it -- so "ask again while the call is unknown" would be a
// request per RENDER without a guard, and a streaming transcript renders dozens of
// times a second: measured in a browser, two delegations produced seventeen requests
// to the route before this guard existed. `pending` is that guard: at most one ask is
// on its way for a given call, and the retry after it is the scheduled one.
//
// NOTHING IS EVER UN-ASKED, and nothing is defined by an expiry. The rows only ever
// get ADDED to: a delegation is an append-only fact about a record, so merging a later
// answer into the held one can only add, and one empty or partial answer cannot erase
// a row the page already knew. A REFUSAL leaves the cards unclickable -- the honest
// reading of "we could not find out" -- and a page reload is what asks again.
import { useSyncExternalStore } from "react";

import { API_BASE } from "@/lib/threads";

/// How many times a card asks about a call the answer has not named, and how far
/// apart. MEASURED AGAINST THE GAP IT IS FOR, not against a run's length: the card
/// is drawn during the arguments' stream and the row is written when the call is
/// executed, which is tens to hundreds of milliseconds behind. Four asks over about
/// a second and a half covers that gap several times over, and a run that lasts a
/// minute costs the same four requests -- this is not a poll.
const ATTEMPTS = 4;
const RETRY_MS = 400;

/// One delegation, as the record states it.
export type Delegation = {
  /// The tool call that opened the child conversation -- the key a card looks
  /// itself up by.
  toolCallId: string;
  /// The subagent's NAME, which is what the panel's header calls it.
  subagent: string;
  /// The child conversation's own id: a session, its own log, its own trajectory.
  threadId: string;
  /// When the delegation was written, in epoch milliseconds.
  at: number;
};

/// The route's envelope. Read as a shape rather than assumed to be a bare array:
/// the answer names the parent it is about, which is worth having in a response a
/// page keys by parent -- and reading it as an array is a bug a browser walkthrough
/// found (`rows.map` on an object throws, the catch swallows it, and a 200 leaves
/// every card unclickable).
type Answer = { threadId?: string; delegations?: readonly Delegation[] };

type Entry = {
  rows: ReadonlyMap<string, Delegation>;
  listeners: Set<() => void>;
  /// call -> how many asks have been spent on it while its call was still running.
  tries: Map<string, number>;
  /// call -> the retry scheduled for it, so two mounts cannot stack two loops.
  timers: Map<string, ReturnType<typeof setTimeout>>;
  /// calls whose ONE settled ask has been made.
  settledAsk: Set<string>;
  /// calls with an ask ON ITS WAY -- the guard that makes a render's re-subscribe
  /// cheap (see the header). A call leaves this set when its read comes back.
  pending: Set<string>;
  /// The read this parent has in flight, so a second render's ask JOINS it instead of
  /// sending a request of its own.
  reading: Promise<void> | null;
};

/// parent thread id -> what is known about its delegations. Absent means "not asked".
const entries = new Map<string, Entry>();

const EMPTY: ReadonlyMap<string, Delegation> = new Map();

const entryOf = (threadId: string): Entry | undefined => entries.get(threadId);

function stopTimer(entry: Entry, toolCallId: string): void {
  const timer = entry.timers.get(toolCallId);
  if (timer !== undefined) {
    clearTimeout(timer);
    entry.timers.delete(toolCallId);
  }
}

/// The read for a parent, shared by every asker that arrives while it is on its way.
function ask(threadId: string): Promise<void> {
  const entry = entryOf(threadId);
  if (entry === undefined) return Promise.resolve();
  if (entry.reading !== null) return entry.reading;
  const reading = read(threadId).finally(() => {
    const now = entryOf(threadId);
    if (now !== undefined) now.reading = null;
  });
  entry.reading = reading;
  return reading;
}

/// Ask about `toolCallId` if the budget allows, and schedule the next ask if it is
/// still unknown. See the header for why this exists at all, and for why the
/// `pending` guard is what makes it safe to call on every render.
function ensure(threadId: string, toolCallId: string): void {
  const entry = entryOf(threadId);
  if (entry === undefined || entry.rows.has(toolCallId) || entry.pending.has(toolCallId)) return;
  const made = entry.tries.get(toolCallId) ?? 0;
  if (made >= ATTEMPTS) return;
  entry.tries.set(toolCallId, made + 1);
  entry.pending.add(toolCallId);
  void ask(threadId).then(() => {
    const now = entryOf(threadId);
    if (now === undefined) return;
    now.pending.delete(toolCallId);
    // STOPPED BY ANY OF THE THREE ENDINGS: the id was found, nobody is looking any
    // more (the card unmounted, and the reads it caused must not outlive it), or the
    // budget is spent.
    if (now.rows.has(toolCallId) || now.listeners.size === 0) return;
    if ((now.tries.get(toolCallId) ?? 0) >= ATTEMPTS) return;
    stopTimer(now, toolCallId);
    now.timers.set(
      toolCallId,
      setTimeout(() => {
        now.timers.delete(toolCallId);
        ensure(threadId, toolCallId);
      }, RETRY_MS),
    );
  });
}

function subscribe(
  threadId: string,
  toolCallId: string,
  settled: boolean,
  listener: () => void,
): () => void {
  const entry =
    entryOf(threadId) ??
    {
      rows: EMPTY,
      listeners: new Set<() => void>(),
      tries: new Map<string, number>(),
      timers: new Map<string, ReturnType<typeof setTimeout>>(),
      settledAsk: new Set<string>(),
      pending: new Set<string>(),
      reading: null,
    };
  entries.set(threadId, entry);
  entry.listeners.add(listener);

  if (!entry.rows.has(toolCallId)) {
    if (settled) {
      // THE CALL IS OVER, SO THE ROW EXISTS IF IT EVER WILL. One ask, once: a
      // settled call the record does not name is an answer, and asking again on
      // every later render would be a request per render for every old delegation.
      // The budget is set to leave exactly ONE try, so this ask cannot turn into a
      // second round of retries behind it.
      if (!entry.settledAsk.has(toolCallId)) {
        entry.settledAsk.add(toolCallId);
        stopTimer(entry, toolCallId);
        entry.tries.set(toolCallId, ATTEMPTS - 1);
        entry.pending.delete(toolCallId);
        ensure(threadId, toolCallId);
      }
    } else if (!entry.timers.has(toolCallId)) {
      // THE CALL IS STILL RUNNING, which is the case the header is about: the row
      // may not be written yet, so an unknown id gets its few asks.
      ensure(threadId, toolCallId);
    }
  }

  return () => {
    entry.listeners.delete(listener);
    if (entry.listeners.size === 0) {
      // A CARD THAT WENT AWAY TAKES ITS RETRIES WITH IT, and a later mount starts
      // from the full budget again -- the transcript's cards come and go with the
      // session being shown, and the accounted state is per mount, not per record.
      for (const timer of entry.timers.values()) clearTimeout(timer);
      entry.timers.clear();
      entry.tries.clear();
      entry.settledAsk.clear();
      entry.pending.clear();
    }
  };
}

/// Read the parent's delegations and merge them into what is held.
async function read(threadId: string): Promise<void> {
  try {
    const res = await fetch(`${API_BASE}threads/${encodeURIComponent(threadId)}/delegations`);
    if (!res.ok) return;
    const answer = (await res.json()) as Answer;
    const entry = entryOf(threadId);
    if (entry === undefined) return;
    const merged = new Map(entry.rows);
    for (const row of answer.delegations ?? []) merged.set(row.toolCallId, row);
    // A NEW MAP, ALWAYS: `useSyncExternalStore` compares snapshots by identity, and
    // mutating the held one would tell React that nothing changed while telling the
    // cards exactly the opposite.
    entries.set(threadId, { ...entry, rows: merged });
    for (const listener of entry.listeners) listener();
  } catch {
    // See the header: an unknown answer leaves the cards unclickable, which is the
    // honest reading of "we could not find out".
  }
}

/// WHAT PARENT THREAD `threadId` HAS DELEGATED, keyed by the tool call that did it,
/// asked about the call the caller is drawing.
///
/// `settled` IS THE CALLER'S OWN STATE, and it is here because it is the only thing
/// the page knows that the read does not: a call that has finished has had its row
/// written, so one more ask is the last word on it (see the header).
///
/// `toolCallId === null` -- a card for some other tool, or no session at all (the
/// copied kit's stories render one message with no page around it) -- subscribes to
/// nothing and reads nothing: a conversation with no delegation in it should not send
/// this request at all.
export function useDelegations(
  threadId: string | null,
  toolCallId: string | null,
  settled: boolean,
): ReadonlyMap<string, Delegation> {
  const subscribeTo = (listener: () => void) =>
    threadId === null || toolCallId === null
      ? () => {}
      : subscribe(threadId, toolCallId, settled, listener);
  const snapshot = () => (threadId === null ? EMPTY : (entryOf(threadId)?.rows ?? EMPTY));
  return useSyncExternalStore(subscribeTo, snapshot, snapshot);
}