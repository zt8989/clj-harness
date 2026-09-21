// THE AGENT THE PAGE DRIVES, and the one thing it says differently from
// `@ag-ui/client`'s `HttpAgent`: IT DOES NOT SEND THE CONVERSATION.
//
// `HttpAgent` posts a `RunAgentInput`, whose `messages` field is the client's whole
// message list -- the shape AG-UI was designed around, and the shape this product had
// until ticket 03 of `.scratch/sessions-live-on-the-server`. The conversation lives on
// the server now (ADR 0002 decisions 4 and 9), so a run carries only WHAT THIS ACTION
// ADDS: the field is `append`, and `messages` and `runId` are not in the body at all.
//
// A SUBCLASS RATHER THAN A SECOND HTTP CLIENT. Everything else about the request --
// the URL, the headers, the event-stream parsing, the abort controller, the frame
// application into `this.messages` -- is `HttpAgent`'s and stays that way; the one
// seam the client library offers for the body is `requestInit`, which is exactly the
// thing that moved. A hand-rolled `fetch` would have meant re-implementing the SSE
// reader to change one field.
//
// AND IT IS DELIBERATELY THE SAME CLASS THE SUITES DRIVE (test/e2e.ts builds one):
// "the page's own agent can talk to this server" is the property the UI tests are
// for, and a suite that built a different client would prove it about that client.

import {
  HttpAgent,
  type HttpAgentConfig,
  type HttpAgentFetchFn,
  type Message,
  type RunAgentInput,
} from "@ag-ui/client";

/// THE ENTRIES AN ACTION ADDS TO A CONVERSATION THE SERVER HOLDS: the trailing run of
/// user messages -- everything after the last message that is not one of the person's
/// own questions.
///
/// WHY THE TAIL AND NOT THE WHOLE LIST. The client's `messages` is the whole
/// conversation it is holding (hydrated from the server, then streamed into), and
/// sending that is what retired: the server holds the conversation, and a run carries
/// only what this action adds. What a person adds is a run of questions at the END --
/// one message per send, and more than one if the runtime hands over several at once.
/// The messages before them are the server's own words coming back, and the server
/// would only have to drop them again.
///
/// THE ID IS WHAT MAKES BEING WRONG SAFE. The server drops an entry whose `:id` the
/// conversation already holds (`harness.edge.sessions/append!`), so a re-send after a
/// socket died -- the same question, the same id -- adds nothing, while a genuinely new
/// one is added once. That is the same rule the record's fold reads, which is why a
/// retry and a rebuild agree about what the conversation is.
export function appendOf(messages: readonly Message[]): Message[] {
  let last = -1;
  messages.forEach((message, index) => {
    if (message.role !== "user") last = index;
  });
  return messages.slice(last + 1).filter((message) => message.role === "user");
}

/// The body a run is: `RunAgentInput` minus the two fields the server owns, plus the
/// one it takes. Named rather than inlined so the shape the page promises is readable
/// in one place -- the edge's own table (`docs/architecture/edge.md`) is the other.
type ActionBody = Omit<RunAgentInput, "messages" | "runId"> & { append: Message[] };

/// THE ID A RUN IS ADDRESSED TO, read off the body this class writes: `requestInit`
/// sends `RunAgentInput`'s own `threadId`, and the run edge answers an id this home has
/// never been asked to keep with a refusal (`refuse-unknown-session!`) -- so which id the
/// body names is exactly the question `ready` below has to answer before the bytes go
/// out. The body is the honest source: it is what the server will read.
///
/// THE AGENT'S OWN `threadId` IS THE FALLBACK, for a body this class did not write (a
/// subclass overriding `requestInit`, a caller driving `fetch` itself). It is the same
/// value on every request this page produces -- `app.tsx` sets it on the agent that
/// speaks for one session -- which is why falling back is safe rather than a guess.
function threadIdOf(init: RequestInit, held: string): string {
  if (typeof init.body !== "string") return held;
  try {
    const body: unknown = JSON.parse(init.body);
    return body !== null && typeof body === "object" && "threadId" in body &&
      typeof body.threadId === "string"
      ? body.threadId
      : held;
  } catch {
    return held;
  }
}

/// WHAT THIS AGENT TAKES ON TOP OF `HttpAgent`'s OPTIONS: the one hook this product
/// needs that the client library has no seam of its own for.
export type HarnessAgentConfig = HttpAgentConfig & {
  /// CALLED WITH THE SESSION'S ID ON EVERY RUN REQUEST, AWAITED BEFORE IT GOES OUT.
  ///
  /// WHY IT EXISTS AT ALL: this page MINTS a session's id in the browser (the owner's
  /// rule -- 点击新增不立刻会话，发送才新建), while the run edge refuses an id this home
  /// has never been asked to keep (ADR 0002 decision 9). The two are compatible only if
  /// something registers the id in between, and the run itself is the only moment where
  /// both facts are in the same place: the id is known, and nothing has been written yet.
  ///
  /// WHY THE RUN AND NOT A SEND HANDLER: this is the last point that is unambiguously
  /// BEFORE the request, and it is below every caller of `run` -- a first send, a resume,
  /// a retry after a dropped socket -- without the page having to enumerate them.
  ///
  /// IT IS CALLED ON EVERY RUN, AND THAT IS NOT A RETRY: "once per id" belongs to the
  /// page's own bookkeeping (`app.tsx`'s `pendingBinds`, which deletes the entry before
  /// it awaits), because only the page knows what a minted session was waiting to
  /// belong to. This class holds no state about what has been registered.
  ready?: (threadId: string) => Promise<void>;
};

export class HarnessAgent extends HttpAgent {
  /// THE OTHER SEAM: the fetch a run goes out through, wrapped so that `ready` has
  /// finished before the request is handed to the transport.
  ///
  /// THIS IS AN OVERRIDE OF A FIELD AND NOT OF A METHOD, because `HttpAgent` takes its
  /// transport as a constructor option (`this.fetch = e.fetch ?? ((u, i) => fetch(u, i))`)
  /// and `run()` is nothing but `this.fetch(this.url, this.requestInit(e))`. Wrapping the
  /// installed function -- rather than passing one into `super`, and rather than
  /// reimplementing `requestInit` -- keeps the base class's own choice of transport: a
  /// caller that brought a `fetch` of its own still gets it, and this class only inserts
  /// one await in front of it.
  ///
  /// IT IS SET AFTER `super`, deliberately: `this` does not exist until the base
  /// constructor has run, and the wrapper needs `this.threadId` as its fallback.
  ///
  /// A REJECTION OUT OF `ready` IS SWALLOWED, and that is the ordering's own logic rather
  /// than politeness: the run is what creates the session, so a registration that failed
  /// must not become a refused run on top of it. The failure is worded by whoever passed
  /// the hook -- the page puts the sentence on the row (`openErrors`) -- and if the id is
  /// STILL unknown when the request arrives, the edge's own refusal is the honest answer
  /// to give the person.
  constructor(config: HarnessAgentConfig) {
    const { ready, ...rest } = config;
    super(rest);
    const send: HttpAgentFetchFn = this.fetch;
    this.fetch = async (url, init) => {
      try {
        await ready?.(threadIdOf(init, this.threadId));
      } catch {
        // See `ready`: a registration the page could not make is the page's to word,
        // and it must not cost the run. Nothing is logged -- the row carries the
        // sentence, and a second copy in the console would be a fact nobody reads.
      }
      return send(url, init);
    };
  }

  /// THE SEAM: the request the base class would send, with its body rewritten.
  ///
  /// `messages` IS DROPPED RATHER THAN EMPTIED. The run edge refuses a body that
  /// carries the field at all -- `messages: []` included, and deliberately: an empty
  /// array would still be a client saying "here is the conversation", and the rule has
  /// to be one a reader can see from the body rather than one about how full it is.
  ///
  /// `runId` GOES THE SAME WAY. The server mints it (a client that can name a run can
  /// collide with one, and the id is what the record and the frames are keyed by), and
  /// what it answers -- `RUN_STARTED`, and the `messageId` of every frame -- is where
  /// the client reads it from.
  ///
  /// EVERYTHING ELSE IS THE BASE CLASS'S, which is why this delegates rather than
  /// building an init: the headers, the content type, the abort signal and the
  /// subagent-field cleanup are all facts about speaking AG-UI, and none of them are
  /// this feature's business.
  protected requestInit(input: RunAgentInput): RequestInit {
    const { messages, runId: _serverOwns, ...rest } = input;
    const body: ActionBody = { ...rest, append: appendOf(messages) };
    return super.requestInit(body as unknown as RunAgentInput);
  }
}
