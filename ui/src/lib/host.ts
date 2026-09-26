// THE HOST DOWNLINK (`events.host`, ADR 0004): the sidebar's listing, pushed.
//
// WHY THIS IS NOT THE CONVERSATION DOWNLINK. `events.mux` answers "what is this
// conversation now" for the conversations a page holds; this answers "which conversations
// and projects exist, what they are called, and which have a run going" -- one listing the
// whole page shares, and the same question every window asks. It is a SECOND socket because
// it is a second CATEGORY (the DSH shape: one downlink per category), and because a page
// with no conversation open still wants a live sidebar.
//
// NO SUBSCRIPTION AND NO CURSOR: every connection wants the same listing, so every frame is
// the whole thing. A reconnect gets the listing again as its opening frame, so there is
// nothing to re-declare and nothing to reconcile -- the last frame wins.
import type { SidebarListing } from "./projects";
import { downlinkUrl } from "./threads";

/// The same pause the conversation downlink takes before re-opening after a close.
const RECONNECT_MS = 1000;

let socket: WebSocket | null = null;
let listener: ((listing: SidebarListing) => void) | null = null;
let reconnect: ReturnType<typeof setTimeout> | null = null;
let wanted = false;

function open(): void {
  const ws = new WebSocket(downlinkUrl("events.host", new URLSearchParams()));
  socket = ws;
  ws.onmessage = (event) => {
    let frame: Partial<SidebarListing> & { type?: string };
    try {
      frame = JSON.parse(String(event.data)) as Partial<SidebarListing> & { type?: string };
    } catch {
      // A frame this client cannot read is one listing nobody can draw; the socket stays
      // open and the next change brings a whole one.
      return;
    }
    if (frame.type !== "projects") return;
    listener?.({ projects: frame.projects ?? [], tasks: frame.tasks ?? [] });
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

/// FOLLOW THE HOST-LEVEL FACTS: every listing the server pushes is handed to ON_LISTING,
/// starting with the one that arrives the moment the socket opens. Answers the way to stop.
export function subscribeHost(onListing: (listing: SidebarListing) => void): () => void {
  listener = onListing;
  wanted = true;
  if (socket === null) open();
  return () => {
    // A REPLACED LISTENER MUST NOT BE TORN DOWN BY THE OLD ONE'S CLOSER.
    if (listener !== onListing) return;
    listener = null;
    wanted = false;
    if (reconnect !== null) clearTimeout(reconnect);
    reconnect = null;
    socket?.close();
    socket = null;
  };
}
