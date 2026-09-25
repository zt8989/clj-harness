// THE DOWNLINK: one WebSocket per page carrying every conversation this page holds and
// every run this page is driving (`events.mux`, ADR 0004).
//
// WHY ONE SOCKET AND NOT ONE PER CONVERSATION. The SSE feed this replaces opened a
// connection per watched conversation, and those connections never went away -- a host is
// never evicted and a hidden host kept its feed -- so a page that had opened a handful of
// sessions held a handful of sockets, and the browser's per-origin pool is a handful. One
// socket for the whole page makes the count constant.
//
// THE SOCKET IS DOWNLINK-ONLY. Nothing is sent over it. The subscription is an HTTP fact,
// declared in this connection's own handshake URL and updated by `POST
// /api/events.mux/subscribe` -- so what the server knows about this page never outlives
// the socket, and a reconnect re-states it (the same property the SSE feed's `since=N` had,
// ADR 0003 decision 7).
//
// ONE SOCKET, TWO KINDS OF FRAME, routed by `WINDOW_TYPES`: a window frame is this page's
// COPY of a conversation, and a run event is a run being written NOW. Both are keyed by
// `threadId`; the server filters to the threads this connection declared (so a page hears
// nothing about a conversation it is not holding).
import type { WindowFrame } from "./feed";
// `apiBase` RATHER THAN `API_BASE`: a suite points the harness origin at a server it learned
// at runtime, and this module is loaded before that (`threads.ts` says why).
import { apiBase, downlinkUrl } from "./threads";
/// `newId` RATHER THAN `crypto.randomUUID` -- the same trap `lib/id.ts` documents, and this
/// file walked into it once: the socket's name was minted with the platform's shortcut,
/// which does not exist over `http://192.168.x.x`, so the page threw before it drew anything
/// on every phone. A connection name is not a conversation name, but it is still a name the
/// page mints in the browser, so it comes from the same cross-platform generator.
import { newId } from "./id";
import { createBatch } from "./coalesce";

/// HOW LONG TO WAIT BEFORE OPENING THE DOWNLINK AGAIN after it closed on its own -- the
/// same fact the SSE feed's reconnect carried: while the socket is up nothing is asked at
/// all, and this is only the pause after a server that is down, so the reconnect is not a
/// tight loop.
const RECONNECT_MS = 1000;

/// A frame on the downlink: a window frame (or a run's AG-UI event), plus the conversation
/// it is about. THE TAG IS THE WHOLE REASON ONE SOCKET CAN CARRY MANY CONVERSATIONS -- the
/// client routes by it, and `applied` (lib/window.ts) ignores it.
export type MuxFrame = WindowFrame & { threadId: string };

/// A RUN'S OWN FRAME on the same socket: an AG-UI event (upper-case `type`), tagged like a
/// window frame. Its shape is the client library's, not ours -- this side reads `type` to
/// route it and hands the rest through to the SSE the agent parses.
export type RunFrame = { threadId: string; type: string; [key: string]: unknown };

/// THE THIRD FAMILY: FACTS ABOUT a conversation rather than parts OF it -- a turn's two ends
/// and a model call's two ends (`harness.edge.http`; ADR 0006). They are NOT AG-UI frames: they
/// never make a message, a rebuilt conversation does not contain them, and nothing echoes them
/// back to a vendor. They are not window frames either -- they are not the conversation's copy.
///
/// THEY CARRY THE RECORD'S `seq` (a line number, so the two halves of the downlink can be
/// aligned) and, for the two that have them, the session's numbers.
export type FactFrame = {
  threadId: string;
  seq: number | null;
  type: "turn/start" | "turn/end" | "model/start" | "model/end";
  payload?: unknown;
  numbers?: unknown;
};

/// THE FACT FAMILY'S TYPES, NAMED IN ONE PLACE. `harness.edge.http` writes these names and this
/// side routes by them, so the spelling is a contract between two processes, not a detail.
const FACT_TYPES = new Set(["turn/start", "turn/end", "model/start", "model/end"]);

/// WHICH FAMILY A FRAME BELONGS TO, as a value -- so the routing rule can be READ and TESTED
/// without a socket (`test/suites/mux.ts`), and so `onmessage` states it once. The `default` is
/// deliberate: anything that is not one of the two named families is a RUN frame, which is
/// AG-UI's own (upper-case) vocabulary.
export function familyOf(type: string): "window" | "fact" | "run" {
  if (WINDOW_TYPES.has(type)) return "window";
  if (FACT_TYPES.has(type)) return "fact";
  return "run";
}

export type MuxHandlers = {
  onFrame: (frame: MuxFrame) => void;
  /// The socket went away. Nothing is wrong with the window; it is BEHIND, and the repair
  /// is the tail page plus a re-declare -- the same move a dropped feed got.
  onClosed: () => void;
};

type Subscription = {
  since: number | null;
  generation: string | null;
  handlers: MuxHandlers;
};

/// THE WINDOW'S OWN FRAME TYPES (`lib/feed`'s `WindowFrame`). Everything else on the socket
/// is a RUN event (AG-UI's vocabulary is upper-case: `RUN_STARTED`, `TEXT_MESSAGE_CONTENT`,
/// ...). The two are routed by this set -- the same socket carries both, which is what makes
/// the sender and a watcher read one stream.
const WINDOW_TYPES = new Set(["window", "append", "page", "tail", "end"]);

/// THE FRAMES THAT MAY NOT WAIT (`lib/coalesce.ts`): a run is over, or the window is. The
/// reader is told the moment one arrives -- whatever was held in front of it goes first.
const TERMINAL_TYPES = new Set(["end", "RUN_FINISHED", "RUN_ERROR", "RUN_CANCELLED"]);

/// WHAT THIS PAGE FOLLOWS, by conversation. A `Map` rather than an object because a thread
/// id is not a property name (a stem can be anything).
const subscriptions = new Map<string, Subscription>();

/// WHAT THIS PAGE IS DRIVING, by conversation: a run's own events, delivered to whoever is
/// running it. A `Map` of SETS because a page may drive runs on more than one conversation.
const runSubscriptions = new Map<string, Set<(event: RunFrame) => void>>();

/// HOW FAR EACH RUN'S FRAME STREAM HAS BEEN READ, by conversation -- the `:seq` of the last
/// run frame this page saw. It is what a reconnecting socket re-declares (`runSince`) so the
/// server hands back the frames that happened while the socket was down: a run is a PUSH,
/// and a push nobody heard is gone unless the sender remembered it.
///
/// IT RESETS ON `RUN_STARTED`, because the sender's numbering does too (`record-run!` starts a
/// fresh buffer per run): carrying a finished run's high-water mark into the next one would
/// ask for frames numbered above anything the new run will ever send.
const runCursors = new Map<string, number>();

/// WHAT THIS PAGE READS FACTS FROM, by conversation: the turn and model-call families
/// (`FactFrame`). A `Map` of SETS for the same reason the run map is one -- a page may hold
/// more than one conversation -- and a separate map from `runSubscriptions` ON PURPOSE: these
/// frames are not a run's, they are the conversation's, and a page that is only WATCHING
/// (driving nothing) still wants them.
const factSubscriptions = new Map<string, Set<(fact: FactFrame) => void>>();

/// THE ROUTING, in one place, because the batch below hands frames over in groups.
///
/// ONE SOCKET, THREE KINDS OF FRAME, AND THE ROUTING IS EXPLICIT. A window frame is about
/// the conversation's copy; a FACT is about the conversation (a turn's or a call's two
/// ends); everything else is a RUN event -- AG-UI's own vocabulary, which goes to whoever
/// is driving that run.
///
/// WHY THE MIDDLE CASE IS NAMED RATHER THAN LEFT TO THE `else`: this used to be 'not a
/// window type => a run frame', and a fact falling through to `@ag-ui/client` would be
/// validated against AG-UI's schema and take the whole run down with it. A frame for a
/// conversation we are not holding still reaches nobody, which is right.
function deliver(frame: MuxFrame & RunFrame): void {
  const family = familyOf(frame.type);
  if (family === "window") {
    subscriptions.get(frame.threadId)?.handlers.onFrame(frame);
    return;
  }
  if (family === "fact") {
    const fact = frame as unknown as FactFrame;
    for (const onFact of factSubscriptions.get(fact.threadId) ?? []) onFact(fact);
    return;
  }
  for (const onEvent of runSubscriptions.get(frame.threadId) ?? []) onEvent(frame);
  // REMEMBER HOW FAR THIS RUN HAS BEEN READ, so a socket that drops can ask for the rest.
  // AT DELIVERY AND NOT AT ARRIVAL (`lib/coalesce.ts`'s header): a frame still waiting in
  // the batch has been seen by nobody, and a mark ahead of it would make the reconnect
  // SKIP it. `RUN_STARTED` RESETS the mark rather than raising it -- the sender starts a
  // fresh numbering per run, and a stale high-water mark would suppress the new run's
  // frames.
  const seq = frame.seq;
  // WIDENED ON PURPOSE: `frame` is a window frame AND a run frame (one socket, two kinds),
  // so its `type` reads as the window union alone until it is asked for as a string.
  const type: string = frame.type;
  if (typeof seq === "number") {
    if (type === "RUN_STARTED" || seq > (runCursors.get(frame.threadId) ?? 0)) {
      runCursors.set(frame.threadId, seq);
    }
  }
}

/// THE BATCH EVERY FRAME GOES THROUGH (`lib/coalesce.ts`): run frames, window frames and
/// facts alike, so what comes out is what arrived, in the order it arrived.
const batch = createBatch<MuxFrame & RunFrame>({
  deliver: (frames) => {
    for (const frame of frames) deliver(frame);
  },
  terminal: (frame) => TERMINAL_TYPES.has(frame.type),
  schedule: (flush) => {
    // A CLOCK THAT CANNOT ARRANGE A FLUSH MUST NOT COST A FRAME: the page has
    // `requestAnimationFrame`, a suite's environment may not -- and a frame that was held
    // and never scheduled is a frame nobody ever sees (which is exactly what the client
    // suite caught: an undefined `requestAnimationFrame` inside `push` left the run's
    // frames in the queue). With no clock, the batch goes out AT ONCE, which is what this
    // side did before the batch existed.
    if (typeof requestAnimationFrame === "function") requestAnimationFrame(flush);
    else flush();
  },
});

let socket: WebSocket | null = null;
/// THE NAME OF THE CURRENT SOCKET, minted when it opens. It exists so the HTTP route that
/// updates the set can address THIS connection; it is thrown away with the socket, and a
/// reconnect mints a new one. It is not an identity and nothing outlives the connection.
let token = "";
let reconnect: ReturnType<typeof setTimeout> | null = null;
/// Whether the page WANTS a downlink at all. A page with nothing to follow keeps none.
let wanted = false;

/// EVERY CONVERSATION THIS CONNECTION MUST BE TOLD ABOUT -- a window it follows OR a run it
/// drives. The server filters run frames by this same set, so a run's thread has to be in it
/// even when the page holds no window for it (a session this page just minted).
function wantedThreads(): string[] {
  return [...new Set<string>([...subscriptions.keys(), ...runSubscriptions.keys()])];
}

/// THE SET, as the handshake URL and every re-declare spell it. A thread with no window
/// follower still appears, with a null cursor: the declaration is about WHICH conversations,
/// and a cursor only matters to the window half.
export function declaredSet(): Array<{
  threadId: string;
  since: number | null;
  generation: string | null;
  runSince: number | null;
}> {
  return wantedThreads().map((threadId) => {
    const sub = subscriptions.get(threadId);
    return {
      threadId,
      since: sub?.since ?? null,
      generation: sub?.generation ?? null,
      runSince: runCursors.get(threadId) ?? null,
    };
  });
}

function open(): void {
  token = newId();
  const params = new URLSearchParams();
  params.set("subscriber", token);
  params.set("sessions", JSON.stringify(declaredSet()));
  const ws = new WebSocket(downlinkUrl("events.mux", params));
  socket = ws;
  ws.onopen = () => {
    if (socket !== ws) return;
    // DECLARE THE WHOLE SET AGAIN. The handshake URL named it, and this covers the one race
    // the URL cannot: a subscription added while the socket was still CONNECTING, when the
    // POST below had nowhere to go.
    declare({ subscribe: declaredSet() });
  };
  ws.onmessage = (event) => {
    let frame: MuxFrame & RunFrame;
    try {
      frame = JSON.parse(String(event.data)) as MuxFrame & RunFrame;
    } catch {
      // A frame this client cannot read is one nobody can show. Dropping it keeps the socket
      // and every other conversation on it alive.
      return;
    }
    // HELD, NOT DROPPED (`lib/coalesce.ts`): the frame joins the batch for the browser's
    // next animation frame, which is what keeps a fast vendor's stream at one React update
    // per frame instead of one per token. WHICH KIND of frame this is, and who reads it, is
    // `deliver` above.
    batch.push(frame);
  };
  ws.onclose = () => {
    if (socket !== ws) return; // a newer socket replaced this one; its close is not ours
    socket = null;
    if (!wanted) return;
    // WHAT WAS HELD GOES OUT BEFORE THE REPAIR IS ASKED FOR: `onClosed` must be told about a
    // position the readers have already been given, or the repair reads from a mark ahead
    // of frames nobody has seen (`lib/coalesce.ts`'s header).
    batch.flush();
    // EVERY FOLLOWED WINDOW IS NOW BEHIND, and each has its own repair (a tail page).
    for (const sub of subscriptions.values()) sub.handlers.onClosed();
    schedule();
  };
}

function schedule(): void {
  if (reconnect !== null || !wanted) return;
  reconnect = setTimeout(() => {
    reconnect = null;
    if (wanted && socket === null) open();
  }, RECONNECT_MS);
}

function declare(body: { subscribe?: unknown[]; unsubscribe?: string[] }): Promise<void> | null {
  if (socket === null || socket.readyState !== WebSocket.OPEN) return null;
  return fetch(`${apiBase()}events.mux/subscribe`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ subscriber: token, ...body }),
  })
    .then(() => undefined)
    .catch(() => {
      // The declaration did not arrive. The socket still carries the set its handshake named,
      // and the next reconnect re-declares all of it; there is nothing to report here.
    });
}

function ensure(): void {
  wanted = true;
  if (socket === null) open();
}

function declareThread(threadId: string): Promise<void> | null {
  const sub = subscriptions.get(threadId);
  return declare({
    subscribe: [
      {
        threadId,
        since: sub?.since ?? null,
        generation: sub?.generation ?? null,
        runSince: runCursors.get(threadId) ?? null,
      },
    ],
  });
}

/// FOLLOW ONE CONVERSATION'S WINDOW over the shared downlink, and answer the way to stop.
///
/// `since`/`generation` ARE THE SAME CURSOR THE SSE FEED TOOK -- this is a carrier change,
/// not a protocol change. Calling it again for the same conversation is an UPDATE (a cursor
/// that moved after a repair), which the server is told about.
export function subscribeMux(
  threadId: string,
  window: { since: number | null; generation: string | null },
  handlers: MuxHandlers,
): () => void {
  subscriptions.set(threadId, { since: window.since, generation: window.generation, handlers });
  ensure();
  void declareThread(threadId);
  return () => {
    // A REPLACED SUBSCRIPTION MUST NOT BE TORN DOWN BY THE OLD SUBSCRIPTION'S CLOSER: only
    // the closer that still owns the entry may remove it.
    if (subscriptions.get(threadId)?.handlers !== handlers) return;
    subscriptions.delete(threadId);
    if (!runSubscriptions.has(threadId)) void declare({ unsubscribe: [threadId] });
    // THE SOCKET STAYS OPEN with nothing subscribed. One idle connection per page is the
    // budget this module exists to keep; closing and reopening it on every switch would be
    // the churn the single socket is meant to remove.
  };
}

/// DRIVE ONE RUN over the shared downlink, and answer the way to stop: every AG-UI event the
/// run emits for THREAD-ID is handed to ON_EVENT. This is the carrier `lib/agent.ts` turns
/// back into an SSE for `@ag-ui/client` to parse.
///
/// IT ANSWERS A PROMISE alongside the unsubscribe, and the caller MUST await it before
/// starting the run: the server filters run frames by what this connection declared, so a run
/// started before the declaration lands would lose its first frames.
export function subscribeRun(
  threadId: string,
  onEvent: (event: RunFrame) => void,
): { unsubscribe: () => void; declared: Promise<void> } {
  const set = runSubscriptions.get(threadId) ?? new Set<(event: RunFrame) => void>();
  set.add(onEvent);
  runSubscriptions.set(threadId, set);
  ensure();
  // WHEN THE SOCKET IS NOT OPEN YET, `declareThread` cannot post; `onopen` declares the whole
  // set, so waiting for that is the declaration. `ready` resolves either way.
  const declared = socket !== null && socket.readyState === WebSocket.OPEN
    ? declareThread(threadId) ?? Promise.resolve()
    : whenOpen().then(() => declareThread(threadId) ?? undefined).then(() => undefined);
  return {
    unsubscribe: () => {
      const current = runSubscriptions.get(threadId);
      if (current === undefined || !current.delete(onEvent)) return;
      if (current.size === 0) runSubscriptions.delete(threadId);
      if (!subscriptions.has(threadId)) void declare({ unsubscribe: [threadId] });
    },
    declared,
  };
}


/// READ A CONVERSATION'S FACTS: every turn and model-call frame for THREAD-ID is handed to
/// ON_FACT. This is the family the composer's strip takes its numbers from (and the family the
/// fold line will take its counts from), so it is the same shape as `subscribeRun` minus the
/// promise: a fact is a push nobody has to declare a cursor for yet (`_scratch/turn-and-model-
/// events` ticket 05 adds that), and one missed while the socket was down is repaired by the
/// next snapshot -- a page that opens a conversation asks `/stats` once.
export function subscribeFacts(
  threadId: string,
  onFact: (fact: FactFrame) => void,
): { unsubscribe: () => void } {
  const set = factSubscriptions.get(threadId) ?? new Set<(fact: FactFrame) => void>();
  set.add(onFact);
  factSubscriptions.set(threadId, set);
  ensure();
  return {
    unsubscribe: () => {
      const current = factSubscriptions.get(threadId);
      if (current === undefined || !current.delete(onFact)) return;
      if (current.size === 0) factSubscriptions.delete(threadId);
    },
  };
}

/// The socket's next OPEN, resolved if it is open already. Used by `subscribeRun`, which must
/// not miss a run's first frames.
function whenOpen(): Promise<void> {
  const ws = socket;
  if (ws === null || ws.readyState === WebSocket.OPEN) return Promise.resolve();
  return new Promise((resolve) => {
    const listener = () => {
      ws.removeEventListener("open", listener);
      resolve();
    };
    ws.addEventListener("open", listener);
  });
}
