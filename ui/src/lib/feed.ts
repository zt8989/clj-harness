// THE WINDOW'S WIRE: the two routes a replica reads a conversation through, and the SSE
// reader that keeps one following. Ticket 06 of `.scratch/sessions-live-on-the-server`.
//
//   GET /api/threads/<stem>/page[?beforeSeq=N]      one page, one answer
//   GET /api/threads/<stem>/feed[?since=N&generation=G]
//                                                   the window, then every entry as it
//                                                   lands -- until the window is over
//
// THE RULES ABOUT WHAT TO DO WITH THE FRAMES ARE NOT HERE (`lib/window.ts`): this module
// answers "what did the server say", and that one answers "what does it mean for what I
// hold". The split is what makes the second half testable without a server and this half
// testable without a replica.
//
// THE FEED IS READ WITH `fetch`, NOT WITH `EventSource`. `EventSource` reconnects by
// itself, and its reconnect is the one thing this side must not delegate: a reconnect
// has to say `since=<the newest entry I hold>` or it re-reads the window (or, worse,
// gets the 409 that means the window is gone), and it has to be able to STOP -- a page
// that navigated away must not leave a retry loop behind it. `fetch` plus a reader is
// the same twenty lines and none of that ambiguity.
import type { TFunction } from "i18next";

import type { RecordHealth } from "./record-health";
import { API_BASE, refusalFrom } from "./threads";

type Translate = TFunction<"errors">;

/// ONE ENTRY AS THE WIRE DELIVERS IT: the message, and the RECORD OFFSET of the line it
/// arrived in, or null while that line is still in the writer's queue (the server mints
/// the number when the line lands, and never predicts it).
export type WindowEntry = { seq: number | null; message: unknown };

/// A frame on the feed, or a page route's answer -- the same shape either way, because
/// the page route and the feed answer the same window (`harness.edge.http`'s
/// `window-frame`). The five types are: `window` (the feed's opening tail page),
/// `append` (entries after the reader's cursor), `page` (the page in front of a reader's
/// oldest entry), `tail` (the newest page, for a reader with no cursor), `end` (the
/// window is over, with a `reason` a person can be told).
export type WindowFrame = {
  type: "window" | "append" | "page" | "tail" | "end";
  entries?: readonly WindowEntry[];
  baseSeq?: number | null;
  hasMore?: boolean;
  cursor?: number | null;
  generation?: string | null;
  state?: string | null;
  reason?: string;
  /// The record writer's health, or absent when there is nothing to say. On feed frames
  /// as well as page answers: a failure that starts mid-run has to reach whoever is
  /// looking, and since ticket 06 a live connection is what is looking.
  record?: RecordHealth | null;
};

/// What `GET .../page` answers: the frame, plus WHERE IT WAS READ FROM -- `live` true
/// means this process is holding the conversation (so the page came out of memory, and
/// is as new as the conversation is), false means it was folded out of the record.
///
/// `record` IS THE WRITER'S HEALTH, and it rides here for the same reason it rides on
/// `rebuild` and `sofar` (ADR 0002 decision 6): a page that reads a conversation through
/// this route has to be able to say that its bytes are not reaching the disk. Absent when
/// there is nothing to say.
export type PageAnswer = WindowFrame & { live?: boolean; record?: RecordHealth | null };

/// ONE PAGE. `beforeSeq` is the OLDEST record offset this copy holds, so the answer is
/// the page immediately in front of it -- the server cuts pages at arrival boundaries,
/// which is what makes "immediately in front" mean no overlap and nothing skipped.
export async function pageThread(
  threadId: string,
  t: Translate,
  beforeSeq?: number | null,
): Promise<PageAnswer> {
  const query = beforeSeq === undefined || beforeSeq === null ? "" : `?beforeSeq=${beforeSeq}`;
  const res = await fetch(`${API_BASE}threads/${encodeURIComponent(threadId)}/page${query}`);
  if (!res.ok) throw new Error(await refusalFrom(res, t));
  return (await res.json()) as PageAnswer;
}

/// What the feed does to whoever opened it. `onRefused` is the WINDOW being over as far
/// as the server is concerned -- a 409 with the current generation and baseSeq in it (the
/// client is holding numbers from a conversation that is no longer being served), or any
/// other refusal -- and `onClosed` is the stream ending without an `end` frame: the
/// connection dropped, and the reader's move is to reconnect with its cursor.
export type FeedHandlers = {
  onFrame: (frame: WindowFrame) => void;
  onRefused: (status: number, body: unknown) => void;
  onClosed: () => void;
};

/// SPLIT A CHUNK OF SSE INTO FRAMES. Pure, and separate, because this is the one piece of
/// the reader that has to be right about boundaries: a frame is `data: <json>`, frames
/// end at a blank line, and a chunk can stop in the middle of either.
export function feedFrames(text: string): { frames: WindowFrame[]; rest: string } {
  const frames: WindowFrame[] = [];
  let buffer = text;
  for (;;) {
    const at = buffer.indexOf("\n\n");
    if (at < 0) break;
    const block = buffer.slice(0, at);
    buffer = buffer.slice(at + 2);
    const line = block.split("\n").find((candidate) => candidate.startsWith("data:"));
    if (line === undefined) continue;
    try {
      frames.push(JSON.parse(line.slice(5).trim()) as WindowFrame);
    } catch {
      // A frame this client cannot read is DROPPED, and the stream stays open: a
      // malformed frame is one entry nobody can show, and tearing the window down over
      // it would take the rest of the conversation with it. The next frame's numbers
      // still say where the conversation is, so the copy is not lost.
    }
  }
  return { frames, rest: buffer };
}

/// FOLLOW A CONVERSATION. Answers the way to stop -- call it when the host goes away, or
/// the loop outlives the page that opened it.
///
/// `since` IS THIS COPY'S CURSOR and `generation` is the window it belongs to: together
/// they are "here is what I hold, and which window I am holding it from", which is the
/// whole of what this side tells the server. Nothing about the reader -- no identity, no
/// session, no subscription -- crosses this line (ADR 0003 decision 7).
export function feedThread(
  threadId: string,
  window: { since: number | null; generation: string | null },
  handlers: FeedHandlers,
): () => void {
  const controller = new AbortController();
  const params = new URLSearchParams();
  if (window.since !== null) params.set("since", String(window.since));
  if (window.generation !== null) params.set("generation", window.generation);
  const query = params.toString();
  const url = `${API_BASE}threads/${encodeURIComponent(threadId)}/feed${query === "" ? "" : `?${query}`}`;

  void (async () => {
    try {
      const res = await fetch(url, {
        signal: controller.signal,
        headers: { Accept: "text/event-stream" },
      });
      if (!res.ok || res.body === null) {
        handlers.onRefused(res.status, await res.json().catch(() => undefined));
        return;
      }
      const reader = res.body.getReader();
      const decoder = new TextDecoder();
      let buffer = "";
      for (;;) {
        const { value, done } = await reader.read();
        if (done) break;
        buffer += decoder.decode(value, { stream: true });
        const split = feedFrames(buffer);
        buffer = split.rest;
        for (const frame of split.frames) handlers.onFrame(frame);
      }
      handlers.onClosed();
    } catch {
      // An aborted fetch is this side hanging up, and it is not news.
      if (!controller.signal.aborted) handlers.onClosed();
    }
  })();

  return () => controller.abort();
}
