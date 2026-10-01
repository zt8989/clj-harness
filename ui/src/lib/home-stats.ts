// THE HOME'S STATISTICS, read once and then pushed: how many times each tool was called,
// which skills were loaded, and how many tokens each model burned -- over EVERY conversation
// this home holds.
//
// WHY A THIRD SOCKET. `events.host` answers "which conversations and projects exist" for the
// sidebar and `events.mux` answers "what is this conversation now" for the open one. This
// answers a question about the whole home, and the server builds it by scanning every tool
// call the projection holds -- which is why it is a category of its own: a page with only a
// sidebar open must not be made to pay for that scan (see `harness.edge.host`'s statistics
// section, and `docs/rules/panel-data.md` for the two halves this module is one of).
//
// NO SUBSCRIPTION AND NO CURSOR, the shape `events.host` has: every connection wants the same
// leaderboards, so every frame is the whole answer and the last frame wins. A reconnect is
// handed the current one at once, so there is nothing to re-declare and nothing to reconcile
// -- which is exactly why a missed frame needs no repair beyond the reopened socket.
import { apiBase, downlinkUrl } from "./threads";

/// ONE ROW OF A RANKING: what was counted, and how many times. The server sorts it (most
/// first, ties broken by name), so the client draws the array and never sorts it again.
export type Leader = { name: string; calls: number };

/// ONE MODEL'S USAGE. `model` is null for a call the record never named, and every token key
/// is ABSENT rather than zero when no call reported it -- 'not reported' is not 'none', the
/// rule `harness.edge.stats` keeps and this side must not flatten.
export type ModelLeader = {
  model: string | null;
  calls: number;
  promptTokens?: number;
  completionTokens?: number;
  totalTokens?: number;
  cachedTokens?: number;
};

/// The whole answer, as `GET /api/stats` and the `events.stats` frame both spell it.
export type HomeStats = {
  tools: Leader[];
  skills: Leader[];
  models: ModelLeader[];
};

/// The same pause the other downlinks take before re-opening after a close.
const RECONNECT_MS = 1000;

let socket: WebSocket | null = null;
let listener: ((stats: HomeStats) => void) | null = null;
let reconnect: ReturnType<typeof setTimeout> | null = null;
let wanted = false;

function open(): void {
  const ws = new WebSocket(downlinkUrl("events.stats", new URLSearchParams()));
  socket = ws;
  ws.onmessage = (event) => {
    let frame: Partial<HomeStats> & { type?: string };
    try {
      frame = JSON.parse(String(event.data)) as Partial<HomeStats> & { type?: string };
    } catch {
      // A frame this client cannot read is one ranking nobody can draw; the socket stays open
      // and the next projection round brings a whole one.
      return;
    }
    if (frame.type !== "stats") return;
    listener?.({ tools: frame.tools ?? [], skills: frame.skills ?? [], models: frame.models ?? [] });
  };
  ws.onclose = () => {
    if (socket !== ws) return;
    socket = null;
    if (!wanted) return;
    schedule();
  };
  // An error is always followed by a close, which is where the reconnect is decided.
  ws.onerror = () => {};
}

function schedule(): void {
  if (reconnect !== null || !wanted) return;
  reconnect = setTimeout(() => {
    reconnect = null;
    if (wanted && socket === null) open();
  }, RECONNECT_MS);
}

/// FOLLOW THE HOME'S STATISTICS: every answer the server pushes is handed to ON_STATS,
/// starting with the one that arrives the moment the socket opens. Answers the way to stop.
export function subscribeHomeStats(onStats: (stats: HomeStats) => void): () => void {
  listener = onStats;
  wanted = true;
  if (socket === null) open();
  return () => {
    // A REPLACED LISTENER MUST NOT BE TORN DOWN BY THE OLD ONE'S CLOSER (the rule
    // `lib/host.ts` spells out for the same shape).
    if (listener !== onStats) return;
    listener = null;
    wanted = false;
    if (reconnect !== null) clearTimeout(reconnect);
    reconnect = null;
    socket?.close();
    socket = null;
  };
}

/// The SNAPSHOT half: what the leaderboards are right now.
///
/// A FAILURE IS AN ORDINARY ANSWER HERE and the reason this returns null instead of throwing:
/// the view has nothing useful to say about a broken answer, and a red line where a ranking
/// goes is not the place to say it. A NULL IS 'NOT REPORTED', never 'nothing was called' --
/// the empty arrays the socket sends are that.
export async function homeStats(signal?: AbortSignal): Promise<HomeStats | null> {
  try {
    const res = await fetch(`${apiBase()}stats`, { signal });
    if (!res.ok) return null;
    return (await res.json()) as HomeStats;
  } catch {
    return null;
  }
}
