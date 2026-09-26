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
  type AgentSubscriber,
  type HttpAgentConfig,
  type HttpAgentFetchFn,
  type Message,
  type RunAgentInput,
  type RunAgentResult,
} from "@ag-ui/client";
import { subscribeRun, type RunFrame } from "./mux";

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

/// THE ERROR NAME THAT DECIDES WHAT A HUNG-UP RUN IS CALLED on the far side of
/// this call. `@assistant-ui/react-ag-ui`'s subscriber turns an error whose `name`
/// is `AbortError` into `RUN_CANCELLED`, and anything else into `RUN_ERROR`
/// (`isAbortError`, and the dispatch beside it) -- so this name is not decoration:
/// it is the whole difference between "Cancelled" and "Failed" in the
/// conversation, and between a card that shows a reason and one that shows a
/// sentence nobody can act on.
function asAbort(error: Error): Error {
  const abort = new Error(error.message);
  abort.name = "AbortError";
  return abort;
}

/// THE SAME RULE WHERE THE TRANSPORT WORDS THE ABORT AS A FRAME, which is the
/// path this product actually travels: `@ag-ui/client`'s HTTP transport catches the
/// browser's `AbortError` itself and synthesises a `RUN_ERROR` frame from it
/// (`code: "abort"`, the browser's sentence as the message) -- because a terminal
/// is the only thing the wire vocabulary can say. That frame is the transport's, not
/// the server's, and left alone it overwrites the `RUN_CANCELLED` the runtime has
/// already dispatched, so the run is drawn `Failed` with the browser's wording under
/// it. Handing it to the subscriber through the channel the library's own abort rule
/// reads (`onRunFailed`, with a name of `AbortError`) makes it a cancellation: no
/// `RUN_ERROR` is dispatched, no `RUN_FINISHED` follows it, and the card says
/// "Cancelled" -- which is what happened.
///
/// IT IS GATED ON THE TRANSPORT'S OWN FACT, not on the frame: `code: "abort"` is
/// this client's synthesis (a server terminal carries a reason, not this code), and
/// `aborted` is this run's own request having been killed from here.
function cancellationAware(
  subscriber: AgentSubscriber | undefined,
  aborted: () => boolean,
  cancel: () => void,
): AgentSubscriber | undefined {
  const onRunErrorEvent = subscriber?.onRunErrorEvent;
  if (subscriber === undefined || onRunErrorEvent === undefined) return subscriber;
  return {
    ...subscriber,
    onRunErrorEvent: (params) => {
      // THE SECOND WAY A RUN ENDS WITHOUT FINISHING, and it is the SERVER's: a Stop
      // pressed on a conversation THIS page is driving arrives here as the terminal the
      // run loop emitted (`code: "stopped"`, `harness.edge.ag-ui`).
      //
      // IT IS SWALLOWED AND THIS RUN IS CANCELLED LOCALLY, rather than handed to
      // `onRunFailed`: the runtime treats that channel as a FAILURE unless its own
      // controller was aborted (measured in a browser, 2026-09-22 -- the session's whole
      // column disappeared, because `onRunFailed` reaches the page's `onError` and a host
      // that cannot load its history is dropped), while `cancel` is the disposition the
      // runtime already has for 'a run that ended because somebody stopped it': it aborts
      // this run's controller, dispatches `RUN_CANCELLED`, and the turn is drawn
      // \"Cancelled\". The server has ALREADY stopped the run, so aborting the fetch here
      // is not a second stop -- it is this page letting go of a stream that is ending
      // anyway.
      //
      // IT IS GATED ON THE SERVER'S OWN CODE and not on the sentence: the message is
      // written for a person (`harness.kernel.loop/stop-sentence`), and a client that
      // matched on prose would draw a stop as a failure the day the wording moved. The
      // `stopped` code is a fact the server states, and this is the only reader.
      if (params.event.code === "stopped") {
        // DEFERRED BY A MICROTASK, and that is not a detail: this callback runs INSIDE the
        // transport's own frame loop, and aborting the fetch from inside it left the run
        // promise pending forever (measured -- the client suite's stop case hung). Letting
        // the loop finish the frame it is on and aborting on the next microtask settles the
        // run the same way while leaving the transport able to unwind itself.
        queueMicrotask(cancel);
        return;
      }
      if (params.event.code === "abort" && aborted()) {
        subscriber.onRunFailed?.({
          ...params,
          error: asAbort(new Error(params.event.message ?? "the run was aborted")),
        });
        return;
      }
      return onRunErrorEvent(params);
    },
  };
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

/// A RUN'S FRAMES AS THE SSE `@ag-ui/client` PARSES: every event the socket delivers for
/// this conversation, encoded as a `data:` frame, closed at the terminal. THIS IS THE WHOLE
/// TRANSPORT TRICK of ticket 03 -- the base class's reader, frame loop and abort handling
/// are untouched; only where the bytes come from changed.
///
/// THE BUFFER IS NOT OPTIONAL. The subscription is made BEFORE the run starts (or its first
/// frames could be filtered out as undeclared), so events can arrive while the start request
/// is still in flight; they queue here and are flushed the moment a reader attaches.
function runStream(
  subscription: { unsubscribe: () => void },
  buffer: readonly RunFrame[],
  attach: (deliver: (frame: RunFrame) => void) => void,
  signal: AbortSignal | null | undefined,
): Response {
  const encoder = new TextEncoder();
  let closed = false;
  const end = (controller: ReadableStreamDefaultController<Uint8Array>) => {
    if (closed) return;
    closed = true;
    subscription.unsubscribe();
    try {
      controller.close();
    } catch {
      // The reader already went away; closing twice is not a failure.
    }
  };
  const stream = new ReadableStream<Uint8Array>({
    start(controller) {
      const push = (frame: RunFrame) => {
        if (closed) return;
        try {
          controller.enqueue(encoder.encode(`data: ${JSON.stringify(frame)}\n\n`));
        } catch {
          return;
        }
        if (frame.type === "RUN_FINISHED" || frame.type === "RUN_ERROR") {
          // A SERVER'S OWN STOP IS NOT THE SAME ENDING as a run that finished: it arrives as a
          // terminal and is then cancelled LOCALLY (`cancellationAware`, on `code: "stopped"`),
          // and that path ABORTS this stream -- which is what makes the run `RUN_CANCELLED`.
          // Closing here first would swallow it: a closed stream cannot be aborted into an
          // AbortError, so the cancellation would read as a clean finish (measured: the client
          // suite's stop case lost its `onRunFailed`).
          if ((frame as { code?: string }).code === "stopped") return;
          end(controller);
        }
      };
      attach(push);
      for (const frame of buffer) push(frame);
      const onAbort = () => {
        if (closed) return;
        closed = true;
        subscription.unsubscribe();
        // THE NAME IS THE CONTRACT: the client library reads a killed body as an abort by
        // that name, and `HarnessAgent.onError` turns an aborted run into `RUN_CANCELLED`.
        try {
          controller.error(new DOMException("the run was aborted", "AbortError"));
        } catch {
          // Already closed; nothing to signal.
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
      const threadId = threadIdOf(init, this.threadId);
      try {
        await ready?.(threadId);
      } catch {
        // See `ready`: a registration the page could not make is the page's to word,
        // and it must not cost the run. Nothing is logged -- the row carries the
        // sentence, and a second copy in the console would be a fact nobody reads.
      }
      // THE RUN'S FRAMES COME FROM THE DOWNLINK (`events.mux`, ADR 0004) -- the only carrier
      // a run has now. SUBSCRIBE BEFORE STARTING: the server filters a run's
      // frames by what this connection declared, so a run begun before the declaration lands
      // would lose its first frames. Then start it -- the answer is an ACK -- and hand back
      // the socket's frames as the SSE the base class parses.
      const buffer: RunFrame[] = [];
      let deliver: ((frame: RunFrame) => void) | null = null;
      const subscription = subscribeRun(threadId, (frame) => {
        if (deliver === null) buffer.push(frame);
        else deliver(frame);
      });
      try {
        await subscription.declared;
        const started = await send(url, init);
        if (!started.ok || !(started.headers.get("content-type") ?? "").includes("application/json")) {
          // A REFUSAL IS HANDED BACK WHOLE so the base class's error path words it (it reads
          // the body), and a caller that somehow still got a stream keeps reading that.
          subscription.unsubscribe();
          return started;
        }
        return runStream(subscription, buffer, (next) => {
          deliver = next;
        }, init.signal);
      } catch (error) {
        subscription.unsubscribe();
        throw error;
      }
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

  /// WHAT A RUN THIS CLIENT HUNG UP REPORTS -- the third seam, after `ready` and
  /// `requestInit`, and the same rule `cancellationAware` applies to the frame the
  /// transport synthesises: a run killed from here is a cancellation, not a failure.
  ///
  /// THE FACT IT USES IS THE TRANSPORT'S OWN: `HttpAgent` binds one `AbortController`
  /// per run and `abortRun` aborts it, so at the moment an error arrives,
  /// `abortController.signal.aborted` is exactly "the request this error came out of
  /// was killed from here" -- a stop the person pressed, a session this page closed,
  /// a teardown. No new state, and nothing matched against a message: the wording is
  /// the browser's, and a real provider failure that happened to mention aborting
  /// must still come through as a failure.
  ///
  /// THIS IS THE PATH FOR AN ABORT THAT REACHED THE RUN AS AN ERROR -- killed
  /// before the response headers, so the transport had no frame to synthesise
  /// (`cancellationAware` handles that one). The AG-UI client reads "aborted" by the
  /// error's `name` and a short list of messages, and the browser's wording for a
  /// killed body read is on neither, so what it would otherwise report is an
  /// ordinary run failure.
  ///
  /// THE ERROR IS RE-NAMED, NOT SWALLOWED: the client library's vocabulary is a
  /// name, and `AbortError` is the one its subscriber turns into `RUN_CANCELLED`
  /// (see `asAbort`). The interface already draws that state -- its word is
  /// "Cancelled", and a cancelled call is struck through
  /// (`components/message-parts.tsx`) -- so this reports one state honestly instead
  /// of inventing another. Everything else about the error stays the base class's,
  /// which is why this delegates rather than finishing the run here.
  protected onError(input: RunAgentInput, error: Error, subscribers: AgentSubscriber[]) {
    return super.onError(
      input,
      this.abortController.signal.aborted ? asAbort(error) : error,
      subscribers,
    );
  }

  /// AND THE SAME RULE ON THE WAY IN: every run goes out through a subscriber that
  /// can tell a run which ENDED WITHOUT FINISHING from a failure -- an abort this page
  /// asked for, or the server's own `stopped` terminal (`cancellationAware`). The
  /// base class's own subscriber plumbing is untouched -- this hands it one more
  /// callback, and the run, the transport and the frames are all still the base
  /// class's.
  runAgent(
    parameters?: Parameters<HttpAgent["runAgent"]>[0],
    subscriber?: AgentSubscriber,
  ): Promise<RunAgentResult> {
    return super.runAgent(
      parameters,
      cancellationAware(
        subscriber,
        () => this.abortController.signal.aborted,
        () => this.abortRun(),
      ),
    );
  }

  /// THE SAME CALLBACK FOR A SUBSCRIBER REGISTERED AHEAD OF TIME (`subscribe`
  /// rather than handed to one run), because a rule about what a hung-up run reports
  /// cannot depend on which door a caller came through. The app's runtime passes its
  /// subscriber to each run; a suite registers one on the agent.
  subscribe(subscriber: AgentSubscriber) {
    return super.subscribe(
      cancellationAware(
        subscriber,
        () => this.abortController.signal.aborted,
        () => this.abortRun(),
      ) ?? subscriber,
    );
  }
}
