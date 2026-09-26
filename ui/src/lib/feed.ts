// THE WINDOW'S WIRE, WHAT IS LEFT OF IT (ticket 05 of `.scratch/events-mux-and-host`):
// the PAGE route a reader scrolls up with, and the frame types both the page and the
// downlink speak.
//
//   GET /api/threads/<stem>/page[?beforeSeq=N]      one page, one answer
//
// THE STREAMING HALF IS GONE. The feed this module used to read -- one SSE per watched
// conversation -- was replaced by the page-wide downlink (`lib/mux.ts`, ADR 0004), which
// carries the same `WindowFrame`s tagged by conversation. The RULES about what to do with
// those frames were never here (`lib/window.ts`): this module answers "what did the server
// say" for the one-shot page, and the downlink answers it for the tail.
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
