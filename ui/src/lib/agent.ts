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
// THE GOAL'S OWN MODULES: `/goal …` is read out of the outgoing messages below, and the two
// types are the wire's (a command, and what the edge answers about one).
import { parseGoalCommand, showGoal } from "./goal-command";
import { goalShown, type GoalAction, type GoalCommand, type GoalVerdict } from "./goal";

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

/// THE COMMAND MESSAGES THIS PAGE HAS ALREADY PUT ON THE WIRE, by message id.
///
/// WHY THERE HAS TO BE A MEMO AT ALL. A `/goal …` command leaves NO answer in the conversation
/// -- the server writes what a command DID, never that somebody asked -- so the message stays the
/// last user message the client holds, and every LATER send's trailing run still contains it. The
/// server dedupes a repeated `append` entry by its `:id`; a command has no id to dedupe by, so
/// the page remembers the ones it has sent. THE COST, stated: a command whose request never
/// reached the edge is not re-sent by the next send -- the person says it again.
const sentCommands = new Set<string>();

/// WHAT ONE SEND IS, once `/goal …` has been read out of it: the entries that are questions
/// (`append`), the commands the rest become, the ids those commands came from, whether `/goal`
/// alone was among them (the READ verb), and whether NOTHING is left to send.
///
/// ONE FUNCTION FOR BOTH DOORS THAT NEED THE ANSWER: `runAgent`, which has to know whether a run
/// is worth starting at all, and `requestInit`, which builds the body. THEY WERE TWO ONCE, and the
/// walkthrough (2026-10-02) found where they disagreed: the one that decided whether to run only
/// recognised a LONE `/goal`, so a second one -- the message stays in the client's list, see the
/// memo above -- looked like a plain question to it and started a run with nothing in it.
///
/// IT ONLY READS THE MEMO. The write is `requestInit`'s, after the fold is on its way out -- a
/// decision asked twice must not spend the memo once.
type GoalSend = {
  append: Message[];
  commands: GoalCommand[];
  commandIds: string[];
  readVerb: boolean;
  emptied: boolean;
};

/// THE FENCE (`goal_id`, `revision`) A VERB HAS TO NAME, read from the goal this page is SHOWING
/// (`lib/goal.ts`'s `goalShown`): a command typed into the composer is about the goal on screen,
/// exactly as a strip's button is, and `create` is the one verb with no fence.
function fence(action: GoalAction, threadId: string): Partial<GoalCommand> {
  const goal = action === "create" ? null : goalShown(threadId);
  return goal === null ? {} : { goal_id: goal.id, revision: goal.revision };
}

function goalSend(messages: readonly Message[], threadId: string): GoalSend {
  const append: Message[] = [];
  const commands: GoalCommand[] = [];
  const commandIds: string[] = [];
  let readVerb = false;
  let entries = 0;
  for (const message of appendOf(messages)) {
    entries += 1;
    const parsed =
      typeof message.content === "string" ? parseGoalCommand(message.content) : null;
    if (parsed === null) {
      append.push(message);
      continue;
    }
    // A READ VERB IS NEITHER A QUESTION NOR A COMMAND: `/goal` alone opens the strip, and it
    // sends nothing -- `runAgent` below is where that send is stopped.
    if (parsed.action === "show") {
      readVerb = true;
      continue;
    }
    const id = String(message.id);
    if (sentCommands.has(id)) continue;
    commandIds.push(id);
    commands.push({
      type: "goal",
      action: parsed.action,
      ...(parsed.objective === undefined ? {} : { objective: parsed.objective }),
      ...fence(parsed.action, threadId),
    });
  }
  return {
    append,
    commands,
    commandIds,
    readVerb,
    // SOMETHING WAS THERE AND ALL OF IT WAS CONSUMED: a `/goal`, or a command the page has
    // already sent. An EMPTY append with nothing consumed is not this -- that is a resume, which
    // carries no new entries at all and must still run.
    emptied: entries > 0 && append.length === 0 && commands.length === 0,
  };
}

/// THE SERVER'S OWN SENTENCE IN AN ANSWER TO A COMMAND-ONLY REQUEST, or null when every command
/// was carried out. The answer is JSON and small (`.scratch/run-commands`: this build RUNS the
/// commands in-process rather than starting a run, so there is no stream to read), and a body
/// this cannot read is not a refusal -- see the wrapper's own note on why the sentence has to
/// come out here at all.
function commandRefusal(text: string): string | null {
  let body: unknown;
  try {
    body = JSON.parse(text);
  } catch {
    return null;
  }
  const commands =
    body !== null && typeof body === "object" && "commands" in body
      ? (body as { commands?: GoalVerdict[] }).commands
      : undefined;
  const refused = commands?.find((verdict) => "error" in verdict);
  return refused !== undefined && "error" in refused ? refused.error : null;
}

/// WHETHER THIS REQUEST CARRIED THE HARNESS'S OWN WORK INSTEAD OF A QUESTION. It is asked of the
/// BODY `requestInit` wrote rather than of the answer, because the edge's ACK to a run and its
/// answer to a command list are BOTH `200 application/json` -- and the two need different
/// treatment (a run's frames come down the socket; a command's answer is the whole story). The
/// shape is the design's own: `append` empty, `commands` not (`harness.edge.http/handle-run`'s
/// 3c), so a body this cannot read is not one.
function commandsOnly(init: RequestInit): boolean {
  if (typeof init.body !== "string") return false;
  try {
    const body: unknown = JSON.parse(init.body);
    if (body === null || typeof body !== "object") return false;
    const { append, commands } = body as { append?: unknown; commands?: unknown };
    return Array.isArray(commands) && commands.length > 0 && Array.isArray(append) && append.length === 0;
  } catch {
    return false;
  }
}

/// The body a run is: `RunAgentInput` minus the two fields the server owns, plus the two it
/// takes. Named rather than inlined so the shape the page promises is readable in one place --
/// the edge's own table (`docs/architecture/edge.md`) is the other.
type ActionBody = Omit<RunAgentInput, "messages" | "runId"> & {
  append: Message[];
  /// THE HARNESS'S OWN WORK, when this action is asking for some instead of (or as well as)
  /// asking the model (`.scratch/run-commands`): `append` empty plus a command list is the ONE
  /// body the edge will not answer as "a second run" while one is going. See `requestInit`.
  commands?: GoalCommand[];
};

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
  /// the hook -- the page files it as a session failure (`app.tsx`'s `reportFailure`,
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
        // A REQUEST THAT CARRIED ONLY COMMANDS IS ANSWERED WITH JSON AND STARTS NO RUN, so it
        // must NOT fall through to `runStream` below: that stream ends when the server sends a
        // terminal frame, and this request has no run to send one -- the page would sit on
        // "Cancel" forever (the failure mode `runStream`'s own header names). THIS BUILD runs
        // the commands in-process instead of starting a run for them
        // (`harness.edge.commands/types`), and that answer is also the only place a REFUSAL on
        // this door can be said -- the run's reader can read `data:` frames and nothing else, so
        // a `/goal <目标>` typed against an unfinished goal would have gone silent. Throwing it
        // makes THIS run report the sentence, the same disposition a 409 from this route gets.
        if (started.ok && commandsOnly(init)) {
          const text = await started.text();
          const refusal = commandRefusal(text);
          if (refusal !== null) throw new Error(refusal);
          // NOTHING WILL EVER COME DOWN THIS SUBSCRIPTION -- there is no run behind this
          // request -- so it is dropped here rather than left holding the conversation open.
          subscription.unsubscribe();
          return new Response(text, { status: started.status, headers: started.headers });
        }
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
    // WHICH SEAM A `/goal …` LEAVES THROUGH, AND WHY (ticket 08 of `.scratch/goal` demands that
    // sentence): THIS ONE -- `requestInit`, the request every send goes out through
    // (`HttpAgent.run` is nothing but `this.fetch(this.url, this.requestInit(e))`). The
    // composer's submit path is upstream's, and the one LOCAL: insertion point near it
    // (`thread.aui.tsx`'s `ComposerFrame`) wraps markup rather than deciding what is SENT; this
    // is the last point at which the OUTGOING MESSAGES are in hand, and every caller -- a first
    // send, a resume, a retry after a dropped socket -- arrives here without the page having to
    // enumerate them.
    //
    // A COMMAND IS TAKEN OUT OF `append` RATHER THAN SENT AS A QUESTION: the message itself
    // never reaches the record (`harness.edge.commands` writes what a command DID, never that
    // somebody asked), and the request STAYS a run request -- the same POST, with `append` empty
    // and the command on `commands`. That is the one body the run edge does not answer as "a
    // second run" while one is in flight (`harness.edge.http/handle-run`'s 3c), so a command
    // typed into a running conversation is queued onto it and a command typed into a quiet one
    // is run -- the route decides, from the same shape.
    //
    // ONE COMMAND PER SEND, WHICH IS THE COMPOSER'S OWN SHAPE: the message a `/goal …` command
    // came out of is the WHOLE message (the parse says so), so `append` is empty unless a caller
    // handed over several messages at once. A body carrying BOTH a question and a command would
    // be a run to the edge (`handle-run`'s 3c asks for an empty `append`), and the command in it
    // would be ignored -- `commandsOnly` below is what tells the two apart. AND A COMMAND'S FENCE
    // (`goal_id`, `revision`) COMES FROM WHAT THE PAGE IS SHOWING, not from this class: see
    // `fence`, which is also what makes `/goal pause` typed into the composer mean the same goal
    // as the strip's own `pause` button.
    const send = goalSend(messages, input.threadId);
    // AND THE COMMANDS THIS REQUEST CARRIES ARE NOW SAID: the memo is spent HERE, where the
    // decision has actually become a body (`goalSend` only reads it).
    for (const id of send.commandIds) sentCommands.add(id);
    const body: ActionBody = {
      ...rest,
      append: send.append,
      ...(send.commands.length === 0 ? {} : { commands: send.commands }),
    };
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
    // WHETHER A RUN IS WORTH STARTING AT ALL, asked BEFORE the run -- which is the only door that
    // can stop one: `requestInit` above is called from INSIDE the run, once the bytes are
    // already being built. A send whose entries were ALL consumed (`goalSend`'s `emptied`: a
    // `/goal` alone, or a command this page has already put on the wire) leaves a `/goal`-free,
    // command-free body, and a run started for it would be a model call nobody asked for. A
    // RESUME is not this: it carries no entries at all, so there was nothing to consume.
    const send = goalSend(this.messages, this.threadId);
    // ...AND THE READ VERB STILL ANSWERS SOMETHING: the strip is told to open (`showGoal`), which
    // is the whole of what `/goal` alone asks for.
    if (send.readVerb) showGoal();
    if (send.emptied) return Promise.resolve({ result: undefined, newMessages: [] });
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
