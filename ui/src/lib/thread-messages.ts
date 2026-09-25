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
// runtime, i18n and the DOM -- cannot be rendered in that run at all. `lib/injections.ts`'s
// `keepInjectionCards` is here for the same reason and one more: it is the half of the
// conversion upstream does not have.
import { fromThreadMessageLike } from "@assistant-ui/core";
import { fromAgUiMessages } from "@assistant-ui/react-ag-ui";

import { newId } from "./id";
import { keepInjectionCards } from "./injections";
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
  // `fromAgUiMessages` rebuilds text, reasoning and tool calls; the injection cards are put back
  // right after it, because upstream's converter has no case for a `data` part (see
  // `lib/injections.ts`). Everything else about a rebuilt message is upstream's.
  const converted = keepInjectionCards(agUiMessages, fromAgUiMessages(agUiMessages));
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
