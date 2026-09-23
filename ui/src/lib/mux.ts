// THE DOWNLINK: one WebSocket per page carrying every conversation this page holds
// (`events.mux`, ADR 0004).
//
// WHY ONE SOCKET AND NOT ONE PER CONVERSATION. The SSE feed this replaces opened a
// connection per watched conversation, and those connections never went away -- a host is
// never evicted and a hidden host kept its feed -- so a page that had opened a handful of
// sessions held a handful of sockets, and the browser's per-origin pool is a handful. The
// next request (a run, a read, an asset) waited behind them. One socket for the whole page
// makes the count constant.
//
// THE SOCKET IS DOWNLINK-ONLY. Nothing is sent over it. The subscription is an HTTP fact,
// declared in this connection's own handshake URL and updated by `POST
// /api/events.mux/subscribe` -- so what the server knows about this page never outlives
// the socket, and a reconnect re-states it (the same property the SSE feed's `since=N` had,
// ADR 0003 decision 7).
//
// ONE SOCKET PER PAGE, NOT PER COMPONENT. This module owns it at module scope, and every
// host that follows a window registers with it. The count is the point.
import type { WindowFrame } from "./feed";
import { API_BASE, downlinkUrl } from "./threads";

/// HOW LONG TO WAIT BEFORE OPENING THE DOWNLINK AGAIN after it closed on its own -- the
/// same fact the SSE feed's reconnect carried: while the socket is up nothing is asked at
/// all, and this is only the pause after a server that is down, so the reconnect is not a
/// tight loop.
const RECONNECT_MS = 1000;

/// A frame on the downlink: the same window frame the SSE feed sent, plus the conversation
/// it is about. THE TAG IS THE WHOLE REASON ONE SOCKET CAN CARRY MANY WINDOWS -- the client
/// routes by it, and `applied` (lib/window.ts) ignores it.
export type MuxFrame = WindowFrame & { threadId: string };

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

/// WHAT THIS PAGE IS FOLLOWING, by conversation. A `Map` rather than an object because a
/// thread id is not a property name (a stem can be anything).
const subscriptions = new Map<string, Subscription>();

let socket: WebSocket | null = null;
/// THE NAME OF THE CURRENT SOCKET, minted when it opens. It exists so the HTTP route that
/// updates the set can address THIS connection; it is thrown away with the socket, and a
/// reconnect mints a new one. It is not an identity and nothing outlives the connection.
let token = "";
let reconnect: ReturnType<typeof setTimeout> | null = null;
/// Whether the page WANTS a downlink at all. A page with no window to follow keeps none.
let wanted = false;

/// THE SET, as the handshake URL and every re-declare spell it.
export function declaredSet(): Array<{ threadId: string; since: number | null; generation: string | null }> {
  return [...subscriptions].map(([threadId, sub]) => ({
    threadId,
    since: sub.since,
    generation: sub.generation,
  }));
}

function open(): void {
  token = crypto.randomUUID();
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
    let frame: MuxFrame;
    try {
      frame = JSON.parse(String(event.data)) as MuxFrame;
    } catch {
      // A frame this client cannot read is one window nobody can show. Dropping it keeps
      // the socket and every other conversation on it alive.
      return;
    }
    subscriptions.get(frame.threadId)?.handlers.onFrame(frame);
  };
  ws.onclose = () => {
    if (socket !== ws) return; // a newer socket replaced this one; its close is not ours
    socket = null;
    if (!wanted) return;
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

function declare(body: { subscribe?: unknown[]; unsubscribe?: string[] }): void {
  if (socket === null || socket.readyState !== WebSocket.OPEN) return;
  void fetch(`${API_BASE}events.mux/subscribe`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ subscriber: token, ...body }),
  }).catch(() => {
    // The declaration did not arrive. The socket still carries the set its handshake named,
    // and the next reconnect re-declares all of it; there is nothing to report here.
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
  const already = subscriptions.has(threadId);
  subscriptions.set(threadId, { since: window.since, generation: window.generation, handlers });
  wanted = true;
  if (!already && socket === null) open();
  else declare({ subscribe: [{ threadId, since: window.since, generation: window.generation }] });
  return () => {
    // A REPLACED SUBSCRIPTION MUST NOT BE TORN DOWN BY THE OLD SUBSCRIPTION'S CLOSER: only
    // the closer that still owns the entry may remove it.
    if (subscriptions.get(threadId)?.handlers !== handlers) return;
    subscriptions.delete(threadId);
    declare({ unsubscribe: [threadId] });
    // THE SOCKET STAYS OPEN with nothing subscribed. One idle connection per page is the
    // budget this module exists to keep; closing and reopening it on every switch would be
    // the churn the single socket is meant to remove.
  };
}
