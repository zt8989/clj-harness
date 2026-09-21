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

import { HttpAgent, type Message, type RunAgentInput } from "@ag-ui/client";

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

export class HarnessAgent extends HttpAgent {
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
