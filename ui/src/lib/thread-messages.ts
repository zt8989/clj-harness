// THE PAGE'S COPY OF A CONVERSATION, built from the AG-UI messages the server handed over: a
// window's entries (`app.tsx`'s `sessionHistory` and `importWindow`), or a `rebuild`'s messages.
//
// ============================================================ why it is its own module
//
// WHAT A CLIENT IS SHOWN IS THE SERVER'S DECISION (ADR 0002), and the AG-UI adapter decides a
// piece of it on its own: `fromAgUiMessages` puts a status on every assistant message it
// converts, and a message carrying a tool call with NO RESULT gets `requires-action` -- the
// shape a PARKED run needs and the wrong shape for one that is still writing that call. That
// guess is what this module exists to correct, and the correction is subtler than it looks:
// `fromThreadMessageLike` prefers the message's OWN `status` over the fallback it is handed
// (`status: status ?? fallbackStatus`), so a fallback alone is silently ignored. The status has
// to RIDE ON THE MESSAGE. It did not, and the bug was exactly the user's: a `bash` call still
// in flight, after a reload, was drawn 待审批 (an exclamation) instead of a spinner -- the
// server's word was right there in the window and the adapter's guess won (owner's report,
// 2026-09-25).
//
// A LEAF MODULE RATHER THAN A CLOSURE INSIDE `app.tsx`: the suite can import this and pin the
// rule over literals (see `ui/test/suites/injections.ts`), while `app.tsx` -- which reaches the
// runtime, i18n and the DOM -- cannot be rendered in that run at all. `lib/card-parts.ts`'s
// `keepCardParts` is here for the same reason and one more: it is the half of the conversion
// upstream does not have -- for EVERY card, since the injection and the compaction are dropped by
// the adapter the same way and put back the same way.
import { fromThreadMessageLike } from "@assistant-ui/core";
import { fromAgUiMessages } from "@assistant-ui/react-ag-ui";

import { newId } from "./id";
import { keepCardParts } from "./card-parts";
import type { SofarState } from "./threads";

/// HOW FAR ALONG THE CONVERSATION IS, as the messages are built: `running` is the window's own
/// `state` -- the tail page answers it, and every frame after that carries it -- which is how
/// this page learns about a run it is only WATCHING. Null is every other case: a session this
/// client has just minted (no window), or one read through `rebuild`, which is over by
/// definition.
///
/// THE LAST MESSAGE'S STATUS IS WHERE THAT LANDS, and it is not decoration: `lib/turns.ts` folds
/// a turn's steps into a one-line summary exactly when its last message is settled, so a
/// conversation still being written has to say so HERE or it renders as a finished answer that
/// happens to stop mid-sentence. Nothing else gets a status of its own -- the messages before it
/// really are complete.
export type Reads = SofarState | null;

/// The conversation's state as a READING (`Reads`), for a value that came off the wire as a
/// string. A server that grows a fifth word reads here as "not running", which is the safe
/// answer: the one thing a caller does with this is decide whether the last message on screen is
/// still being written.
export function readsOf(state: string | null | undefined): Reads {
  return state === "running" || state === "parked" || state === "settled" || state === "unfinished"
    ? state
    : null;
}

/// ------------------------------------------------------------ and the park has to end up LAST
///
/// A PARKED RUN IS FOUND BY THE LAST ASSISTANT MESSAGE AND BY NOTHING ELSE. Upstream reads the
/// interrupts it draws a card from like this (`AgUiThreadRuntimeCore.getPendingInterrupts`):
///
///   const assistant = this.getMessages().findLast((m) => m.role === "assistant");
///   ... assistant.metadata.custom.agui.interrupts
///
/// so a conversation whose FINAL assistant message is not the one the park rides on has no card at
/// all -- and `submitInterruptResponses` refuses in the same breath ("no pending interrupts on
/// this thread"), so a card drawn some other way could not be answered either. The order is not
/// decoration; it is the whole of what makes the question reachable.
///
/// THE SERVER ALWAYS PUTS IT LAST, AND THE PAGE CAN STILL PUT SOMETHING AFTER IT. The fold hands a
/// parked run back as `[... reasoning(r59), assistant(m60, interrupts)]` -- the thinking belongs to
/// the message that follows it -- and a reload imports exactly that. But a page WATCHING the run
/// merges pages into a window it already holds (`lib/window.ts`'s `merged`), and that merge updates
/// an entry it already knows IN PLACE while APPENDING every entry that is new to it. A provider
/// whose reasoning never came down the frame stream -- only the `kernel-message` row carries it --
/// leaves the page holding `m60` with no `r59`, so the next page ADDS `r59` after it and the park
/// is no longer last. The card vanishes the moment the run parks and comes back on a reload, which
/// is the whole of the owner's report (2026-09-30, sessions `b92dfd61` / `62f30024`) and its two
/// predecessors.
///
/// SO THE ORDER IS FIXED HERE, at the one door every one of those paths goes through, rather than
/// in the merge: `merged` is arithmetic about windows, and this is a rule about parks. What it
/// moves is only what it must -- a parked message with NOTHING BUT THINKING after it -- and
/// anything else is passed through untouched, because a rule that reorders more than it
/// understands is a rule that breaks a conversation to fix a card.

/// The metadata namespace a parked run's interrupts ride on: `harness.kernel.frames/
/// park-namespace` spelled on this side, and it has to be the same word on both or this rule
/// finds nothing at all.
const PARK_NAMESPACE = "agui";

/// Is THIS message the one a parked run stopped on? The list's own presence is the test: the
/// server folds `RUN_FINISHED.outcome.interrupts` onto the message its `toolCallId` belongs to
/// (`harness.edge.replay/park-on-call`), so no message carries one by accident.
const carriesAPark = (message: unknown): boolean => {
  const interrupts = (
    message as { metadata?: { custom?: Record<string, { interrupts?: unknown }> } } | null
  )?.metadata?.custom?.[PARK_NAMESPACE]?.interrupts;
  return Array.isArray(interrupts) && interrupts.length > 0;
};

/// Does this message hold NOTHING BUT thinking? The shape that trails a park -- and the only one
/// this rule is allowed to move.
const isThinkingOnly = (message: unknown): boolean => {
  const parts = (message as { content?: unknown } | null)?.content;
  return (
    (message as { role?: unknown } | null)?.role === "assistant" &&
    Array.isArray(parts) &&
    parts.length > 0 &&
    parts.every((part) => (part as { type?: unknown } | null)?.type === "reasoning")
  );
};

/// THE PARK LAST, or the conversation exactly as it arrived -- see above for why the shape is what
/// a card needs. Generic rather than typed to `ThreadMessageLike` so the caller keeps its own type
/// and this module stays free of `@assistant-ui/react` (the head's reason for being a leaf).
export function parkStaysLast<T>(messages: readonly T[]): readonly T[] {
  const parked = messages.findIndex(
    (message) => (message as { role?: unknown } | null)?.role === "assistant" && carriesAPark(message),
  );
  if (parked < 0) return messages;
  const trailing = messages.slice(parked + 1);
  // NOTHING TO MOVE -- the common case by far, and the one that must cost nothing.
  if (trailing.length === 0 || !trailing.every(isThinkingOnly)) return messages;
  // THE THINKING GOES IN FRONT OF THE PARK, which is where the server's own fold has it: the
  // reasoning that led to the question is read before the question.
  return [...messages.slice(0, parked), ...trailing, messages[parked] as T];
}

/// ------------------------------------------------------ and a result with no call is not a card
///
/// A WINDOW MAY OPEN ON A TOOL RESULT. `GET /api/threads/<stem>/page` cuts a page at an ARRIVAL
/// boundary, so the tail page a sidebar click opens can begin with the RESULT of a call whose
/// `assistant` message lies on the page in front of it -- the one 显示更早 fetches and nothing has
/// fetched yet. Upstream cannot pair the two, and does not leave it at that: a `role: "tool"`
/// message its converter has no call for makes it INVENT one (`conversions.js`, the
/// `if (updated) continue` fallthrough), reading the name off
/// `getString(rawMessage, "name") ?? getString(rawMessage, "toolName") ?? "tool"`. THE WIRE'S TOOL
/// MESSAGE CARRIES NEITHER -- `harness.edge.ag_ui`'s `TOOL_CALL_RESULT` frame is
/// `{messageId, toolCallId, content, role}` -- so the invented call is named the literal `tool`,
/// with `args: {}`, and `components/message-parts.tsx` draws `TOOL_LABELS[toolName] ?? toolName`:
/// a card for a tool that does not exist (owner's report, 2026-10-02, session `4f1f48d5`; the
/// tail page of 17 of the 39 threads this home could open that day began this way).
///
/// SO A HEADLESS RESULT IS NEVER HANDED TO THE CONVERTER. Not the same card under a truer name --
/// there is no call to draw, and the args such a card shows are the ones nobody has. Nothing is
/// lost by dropping it: the page in front of this one holds the call AND its result together, and
/// 显示更早 is what fetches it.
///
/// ONLY THE LEADING RUN, and that is all of them: a record opens on the person's `user` message
/// (measured -- every one of this home's 76 logs whose first message row is not the `system`
/// block), and a result always follows the call it answers, so a `tool` message at the window's
/// front has nothing in front of it to be paired with. The WINDOW is untouched -- only what is
/// imported is -- so 显示更早 still extends from the same `baseSeq`.
export function dropOrphanResults<T>(messages: readonly T[]): readonly T[] {
  let cut = 0;
  while (cut < messages.length && (messages[cut] as { role?: unknown } | null)?.role === "tool") {
    cut++;
  }
  // NOTHING TO DROP -- the common case by far, and it is handed straight back.
  return cut === 0 ? messages : messages.slice(cut);
}

/// The converted history a restore hands the runtime: `fromAgUiMessages` rebuilds text,
/// reasoning and tool calls -- and reads back a parked run's `metadata.custom.agui.interrupts` --
/// but its output is still the loose `ThreadMessageLike` shape; the repository wants the
/// finished one. THE PAIR IS WHOLE ONLY BECAUSE THE SERVER FOLDS THAT METADATA
/// (`kernel.frames/apply-frames` writes it from `RUN_FINISHED.outcome.interrupts`; ticket 06 of
/// `.scratch/session-after-refresh`), and the per-message status below is not overwritten for
/// anything but the running tail, so `requires-action`/`interrupt` survives. The runtime's own
/// snapshot-import path runs this exact pair (AgUiThreadRuntimeCore.importMessagesSnapshot), so
/// the conversion is upstream's, quoted rather than reinvented.
export function toThreadMessages(agUiMessages: readonly unknown[], reads: Reads) {
  // `fromAgUiMessages` rebuilds text, reasoning and tool calls; the CARD parts are put back right
  // after it, because upstream's converter has no case for a `data` part (see `lib/card-parts.ts`,
  // which owns the names and the rule for both cards). Everything else about a rebuilt message is
  // upstream's.
  // THE CONVERSION'S OWN ORDER IS NOT ALWAYS THE SERVER'S, and the park is the one message that
  // has to be last (`parkStaysLast`, above): a page following a run can be handed a conversation
  // whose thinking arrived after the question. A no-op on every other shape.
  // A WINDOW THAT OPENS ON A RESULT IS NOT HANDED ONE (see `dropOrphanResults`), so the converter
  // has a call for every result it is given.
  const paired = dropOrphanResults(agUiMessages);
  const converted = parkStaysLast(keepCardParts(paired, fromAgUiMessages(paired)));
  const last = converted.length - 1;
  return converted.map((message, index) => {
    // THE STATUS A REBUILT MESSAGE ARRIVES WITH IS ITS OWN, and only the LAST one's is
    // overridden -- and only by the window saying it is still being written. Everything else the
    // converter already decided: `fromAgUiMessages` reads a parked run's
    // `metadata.custom.agui.interrupts` and hands that message `requires-action`/`interrupt`,
    // which is exactly the shape `getPendingInterrupts()` looks for. Forcing `complete` on every
    // message (as this did) threw that away, so a refreshed parked conversation came back with no
    // card and `assertNoPendingInterrupts()` wrongly opened (ticket 06 of
    // `.scratch/session-after-refresh`).
    //
    // AND IT RIDES ON THE MESSAGE. The same converter ALSO hands `requires-action` to any message
    // whose tool call has no result yet -- right for a parked run, wrong for one that is still
    // writing that call -- and `fromThreadMessageLike` keeps the like's own `status` over the
    // fallback (`status: status ?? fallbackStatus`). Handed as a fallback alone, the running tail
    // we mean was therefore SILENTLY DROPPED, and a `bash` call in flight came back as a tool row
    // wearing 待审批 (see the head of this module).
    const status =
      index === last && reads === "running"
        ? ({ type: "running" } as const)
        : (message.status ?? { type: "complete", reason: "unknown" });
    //
    // ...ON AN ASSISTANT MESSAGE ONLY: `fromThreadMessageLike` refuses a `status` on any other
    // role, and the fallback it is handed is what every other message gets.
    return fromThreadMessageLike(
      message.role === "assistant" ? { ...message, status } : message,
      message.id ?? newId(),
      status,
    );
  });
}

/// The rebuilt messages as the repository the history adapter returns: a flat chain, each message
/// parented to the one before it. Built here rather than with `ExportedMessageRepository.fromArray`
/// because that helper assigns fresh ids, and these messages already have the ids the runtime
/// recorded.
export function repositoryFrom(agUiMessages: readonly unknown[], reads: Reads = null) {
  const messages = toThreadMessages(agUiMessages, reads);
  let parentId: string | null = null;
  const items = messages.map((message) => {
    const item = { parentId, message };
    parentId = message.id;
    return item;
  });
  return { messages: items };
}
