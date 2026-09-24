// THE PANEL'S AG-UI CLIENT: the one client on this page that never POSTs.
//
// A delegation panel watches a CHILD conversation it does not own. It reads that
// conversation in TWO halves (ADR 0004, ticket 04):
//
//   the record   `GET /api/threads/<stem>/frames` -- what the child has already written, as
//                AG-UI frames, ordered the way a runtime must read them (a `RUN_STARTED`
//                first, the conversation snapshot the frames cannot rebuild next, then the
//                frames). This is how a panel opened on a FINISHED child still shows it.
//   the downlink the same socket every other part of the page uses (`events.mux`): the
//                child's LIVE frames, tagged and numbered like the record's.
//
// THE SAME `:seq` JOINS THE TWO, which is the whole trick: the server numbers a child's
// frames once (the frame bus's counter, which the record carries too), so a live frame whose
// number is not past the replay's last is one the replay already handed over and is dropped.
// Without that, a panel opened mid-report would draw the same token twice.
//
// ------------------------------------------------- why still a subclass, not a reader
//
// THIS wire IS AG-UI, frame for frame -- it is what the child's own run sent -- and the
// runtime already knows what to do with AG-UI frames. `HttpAgent`'s SSE parser, its frame
// validation and its message-id bookkeeping are what the panel needs; this class changes ONE
// thing about it: the TRANSPORT. `run()` still builds an input and parses an event stream;
// where the bytes come from is ours.
import { HttpAgent, type HttpAgentConfig, type HttpAgentFetchFn } from "@ag-ui/client";

import { subscribeRun, type RunFrame } from "@/lib/mux";
import { apiBase } from "@/lib/threads";

/// The record's replay for one conversation. The LIVE tail is the downlink, not here.
export function framesUrl(threadId: string): string {
  return `${apiBase()}threads/${encodeURIComponent(threadId)}/frames`;
}

const seqOf = (frame: RunFrame): number | null =>
  typeof frame.seq === "number" ? frame.seq : null;

const isTerminal = (type: string): boolean => type === "RUN_FINISHED" || type === "RUN_ERROR";

/// ONE FRAME AS THE WIRE SPELLS IT. `:seq` is the server's bookkeeping for the boundary, not
/// a field of any AG-UI frame, so it never reaches the runtime.
function wire(frame: RunFrame): RunFrame {
  const { seq: _seq, ...rest } = frame;
  return rest as RunFrame;
}

export class FollowAgent extends HttpAgent {
  constructor(config: HttpAgentConfig & { threadId: string }) {
    super(config);
    const { threadId } = config;
    const read: HttpAgentFetchFn = async (_url, init) => {
      // SUBSCRIBE FIRST, REPLAY SECOND -- the order the old channel used for the same
      // reason: a frame written between the two reads would otherwise never be seen.
      const buffer: RunFrame[] = [];
      let deliver: ((frame: RunFrame) => void) | null = null;
      const subscription = subscribeRun(threadId, (frame) => {
        if (deliver === null) buffer.push(frame);
        else deliver(frame);
      });
      let replay: RunFrame[];
      let running: boolean;
      try {
        const res = await fetch(framesUrl(threadId), { signal: init?.signal ?? null });
        if (!res.ok) {
          subscription.unsubscribe();
          return res;
        }
        const body = (await res.json()) as { frames?: RunFrame[]; running?: boolean };
        replay = body.frames ?? [];
        running = body.running === true;
      } catch (error) {
        subscription.unsubscribe();
        throw error;
      }
      return replayThenLive(
        subscription,
        replay,
        buffer,
        (next) => {
          deliver = next;
        },
        running,
        init?.signal,
      );
    };
    this.fetch = read;
  }
}

/// THE REPLAY, THEN THE LIVE TAIL, as one SSE. The replay's last `:seq` is the boundary: a
/// live frame at or below it is one already drawn (the two sources overlap by construction,
/// because the subscription exists before the replay is read).
function replayThenLive(
  subscription: { unsubscribe: () => void },
  replay: readonly RunFrame[],
  buffer: readonly RunFrame[],
  attach: (deliver: (frame: RunFrame) => void) => void,
  running: boolean,
  signal: AbortSignal | null | undefined,
): Response {
  const encoder = new TextEncoder();
  let closed = false;
  let boundary: number | null = null;
  for (const frame of replay) {
    const seq = seqOf(frame);
    if (seq !== null) boundary = boundary === null ? seq : Math.max(boundary, seq);
  }
  const endedInReplay = replay.length > 0 && isTerminal(replay[replay.length - 1].type);

  const stream = new ReadableStream<Uint8Array>({
    start(controller) {
      const end = () => {
        if (closed) return;
        closed = true;
        subscription.unsubscribe();
        try {
          controller.close();
        } catch {
          // The reader already went away.
        }
      };
      const emit = (frame: RunFrame) => {
        try {
          controller.enqueue(encoder.encode(`data: ${JSON.stringify(wire(frame))}\n\n`));
        } catch {
          // The reader already went away; nothing to do with the frame.
        }
      };
      const push = (frame: RunFrame) => {
        if (closed) return;
        const seq = seqOf(frame);
        if (seq !== null && boundary !== null && seq <= boundary) return;
        emit(frame);
        if (seq !== null) boundary = boundary === null ? seq : Math.max(boundary, seq);
        if (isTerminal(frame.type)) end();
      };

      for (const frame of replay) emit(frame);
      // A CHILD THAT IS NOT RUNNING (finished, or never ran) HAS NOTHING LIVE TO ADD: the
      // replay is the whole conversation, terminal included when there is one.
      if (endedInReplay || !running) {
        end();
        return;
      }
      attach(push);
      for (const frame of buffer) push(frame);
      const onAbort = () => {
        if (closed) return;
        closed = true;
        subscription.unsubscribe();
        // The name is the contract the client library reads (see `lib/agent.ts`).
        try {
          controller.error(new DOMException("the panel was closed", "AbortError"));
        } catch {
          // Already closed.
        }
      };
      if (signal?.aborted) onAbort();
      else signal?.addEventListener("abort", onAbort, { once: true });
    },
    cancel() {
      closed = true;
      subscription.unsubscribe();
    },
  });
  return new Response(stream, {
    status: 200,
    headers: { "Content-Type": "text/event-stream" },
  });
}
