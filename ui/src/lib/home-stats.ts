// THE HOME'S STATISTICS, read once and then pushed: how many times each tool was called, which
// skills were loaded, and how many tokens each model burned -- over EVERY conversation this home
// holds, WITHIN A WINDOW OF DAYS.
//
// WHY A THIRD SOCKET. `events.host` answers "which conversations and projects exist" for the
// sidebar and `events.mux` answers "what is this conversation now" for the open one. This answers
// a question about the whole home, and the server builds it by scanning the tool calls the
// projection holds -- which is why it is a category of its own: a page with only a sidebar open
// must not be made to pay for that scan (see `harness.edge.host`'s statistics section, and
// `docs/rules/panel-data.md` for the two halves this module is one of).
//
// THE WINDOW RIDES THE CONNECTION. `?days=` is part of the SOCKET'S URL rather than a field in a
// frame, because the server builds a different answer per window and captures the window in the
// push it registers -- so changing the range is a new connection, not a new filter laid over an
// old answer (`subscribeHomeStats` does exactly that).
//
// NO SUBSCRIPTION AND NO CURSOR, the shape `events.host` has: every connection wants the same
// leaderboards for ITS window, so every frame is the whole answer and the last frame wins. A
// reconnect is handed the current one at once, so there is nothing to re-declare.
import { apiBase, downlinkUrl } from "./threads";

/// ONE ROW OF A RANKING: what was counted, and how many times. The server sorts it (most first,
/// ties broken by name), so the client draws the array and never sorts it again.
export type Leader = { name: string; calls: number };

/// ONE MODEL'S USAGE. `model` is null for a call the record never named, and every token key is
/// ABSENT rather than zero when no call reported it -- 'not reported' is not 'none', the rule
/// `harness.edge.stats` keeps and this side must not flatten.
export type ModelLeader = {
  model: string | null;
  calls: number;
  promptTokens?: number;
  completionTokens?: number;
  totalTokens?: number;
  cachedTokens?: number;
};

/// The whole answer, as `GET /api/stats?days=` and the `events.stats` frame both spell it.
/// `since` is the cutoff the server actually used (epoch ms), so a reader can say what the
/// numbers cover rather than guess.
export type HomeStats = {
  days: number;
  since: number;
  tools: Leader[];
  skills: Leader[];
  models: ModelLeader[];
};

/// The three windows the view offers. HERE rather than only in the component because the type of
/// a range is the list's, and a second place that spelled 7/30/90 would be a second answer to
/// "which windows does this page know" (the SERVER takes any positive number, `stats-days` says
/// why).
export const STATS_RANGES = [7, 30, 90] as const;

/// The same pause the other downlinks take before re-opening after a close.
const RECONNECT_MS = 1000;

let socket: WebSocket | null = null;
let listener: ((stats: HomeStats) => void) | null = null;
let reconnecting: ReturnType<typeof setTimeout> | null = null;
let wanted = false;
/// WHICH WINDOW THE OPEN SOCKET IS FOR. A socket is opened with `?days=` in its URL, so this is
/// the answer to "may the connection I am holding be reused for this question".
let openDays: number | null = null;

function open(days: number): void {
  const ws = new WebSocket(downlinkUrl("events.stats", new URLSearchParams({ days: String(days) })));
  socket = ws;
  openDays = days;
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
    listener?.({
      days: frame.days ?? days,
      since: frame.since ?? 0,
      tools: frame.tools ?? [],
      skills: frame.skills ?? [],
      models: frame.models ?? [],
    });
  };
  ws.onclose = () => {
    if (socket !== ws) return;
    socket = null;
    openDays = null;
    if (!wanted) return;
    schedule(days);
  };
  // An error is always followed by a close, which is where the reconnect is decided.
  ws.onerror = () => {};
}

function schedule(days: number): void {
  if (reconnecting !== null || !wanted) return;
  reconnecting = setTimeout(() => {
    reconnecting = null;
    if (wanted && socket === null) open(days);
  }, RECONNECT_MS);
}

/// FOLLOW THE HOME'S STATISTICS FOR ONE WINDOW: every answer the server pushes is handed to
/// ON_STATS, starting with the one that arrives the moment the socket opens. Answers the way to
/// stop.
///
/// A DIFFERENT WINDOW IS A DIFFERENT QUESTION, so the connection is replaced rather than
/// re-filtered: the server captured the old window in the push it registered, and a frame for 7
/// days drawn under a "30 days" heading would be a lie the client could not see.
export function subscribeHomeStats(
  days: number,
  onStats: (stats: HomeStats) => void,
): () => void {
  if (socket !== null && openDays !== days) {
    const old = socket;
    // CLEARED BEFORE THE CLOSE, so the close handler does not read this as a dropped connection
    // and schedule a reconnect for the window nobody wants any more.
    socket = null;
    openDays = null;
    old.close();
  }
  listener = onStats;
  wanted = true;
  if (socket === null) open(days);
  return () => {
    // A REPLACED LISTENER MUST NOT BE TORN DOWN BY THE OLD ONE'S CLOSER (the rule `lib/host.ts`
    // spells out for the same shape).
    if (listener !== onStats) return;
    listener = null;
    wanted = false;
    if (reconnecting !== null) clearTimeout(reconnecting);
    reconnecting = null;
    socket?.close();
    socket = null;
    openDays = null;
  };
}

/// The SNAPSHOT half: what the leaderboards are for DAYS, right now.
///
/// A FAILURE IS AN ORDINARY ANSWER HERE and the reason this returns null instead of throwing: the
/// view has nothing useful to say about a broken answer, and a red line where a ranking goes is
/// not the place to say it. A NULL IS 'NOT REPORTED', never 'nothing was called' -- the empty
/// arrays the socket sends are that.
export async function homeStats(days: number, signal?: AbortSignal): Promise<HomeStats | null> {
  try {
    const res = await fetch(`${apiBase()}stats?days=${encodeURIComponent(String(days))}`, { signal });
    if (!res.ok) return null;
    return (await res.json()) as HomeStats;
  } catch {
    return null;
  }
}

/// ASK THE SERVER TO MAKE THE PROJECTION AGAIN FOR THIS WINDOW (`POST /api/stats/rebuild`).
///
/// THE ONE FULL READ IN THE FEATURE, and it is this button's: a content table the projection did
/// not have when a log was copied cannot be filled from the offsets that are already past those
/// bytes, so a person asks for the copy to be made again -- scoped to the window they are looking
/// at. The answer comes back as the next pushed frame, not as this response.
///
/// Answers whether the server took it; false is 'we could not ask', which the button draws as
/// nothing happening rather than as a claim that it worked.
export async function rebuildStatsWindow(days: number): Promise<boolean> {
  try {
    const res = await fetch(`${apiBase()}stats/rebuild`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ days }),
    });
    return res.ok;
  } catch {
    return false;
  }
}
