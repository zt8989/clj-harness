// THE PANEL'S AG-UI CLIENT: the one client on this page that never POSTs.
//
// `GET /api/threads/<stem>/follow` (ticket 02) is a READ-ONLY SSE of one thread's
// frames: it replays what the record already holds -- the task the subagent was
// handed, as a `MESSAGES_SNAPSHOT`, and then every frame the run has written --
// and then follows the in-process frame bus until the run's terminal frame. A
// panel that watches a delegation is that channel and nothing else.
//
// ------------------------------------------------- why a subclass, not a reader
//
// `lib/feed.ts` reads the WINDOW's wire by hand (fetch plus a reader) because that
// wire is not AG-UI: its frames carry record entries, and `lib/window.ts` decides
// what they mean for the copy the page holds. THIS wire IS AG-UI, frame for frame
// -- it is what the child's own run sent -- and the runtime already knows what to
// do with AG-UI frames. Re-implementing the SSE reader, the frame validation and
// the message-id bookkeeping here would be a second implementation of the thing
// `@ag-ui/client` is FOR, and the second one would drift.
//
// So this class changes ONE thing about `HttpAgent`: the TRANSPORT. `run()` still
// builds a `RunAgentInput` and still parses the event stream into frames; the
// request that carries it is ours, and it is a `GET` with no body at all -- the
// follow channel answers no `RunAgentInput`, accepts no input, and has nothing to
// run. Everything else the base class does (the abort controller, the SSE parser,
// the frame dispatch into the subscriber) is exactly what the panel needs, and the
// abort is what makes closing the panel cost nothing on the server: killing the
// request drops the subscription (`follow-get`'s `on-close`).
import { HttpAgent, type HttpAgentConfig, type HttpAgentFetchFn } from "@ag-ui/client";

import { API_BASE } from "@/lib/threads";

/// The channel one subagent's panel reads.
export function followUrl(threadId: string): string {
  return `${API_BASE}threads/${encodeURIComponent(threadId)}/follow`;
}

export class FollowAgent extends HttpAgent {
  /// A `GET`, whatever the base class asked for.
  ///
  /// THE INIT IS NOT MERGED, IT IS REPLACED, and both halves of that matter: the
  /// method is `GET` (the base class's `requestInit` is a POST with a JSON body,
  /// which this route would refuse), and the body is dropped rather than emptied --
  /// "here is the conversation" is exactly the claim this channel does not make.
  /// The signal is kept, because it is the abort path (see the header).
  constructor(config: HttpAgentConfig) {
    super(config);
    const read: HttpAgentFetchFn = (url, init) =>
      fetch(url, {
        method: "GET",
        headers: { Accept: "text/event-stream" },
        signal: init?.signal ?? null,
      });
    this.fetch = read;
  }
}