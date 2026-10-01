// THE QUEUE OF MESSAGES WAITING TO BE SENT, drawn directly above the composer -- and the
// ONE block up there that is joined to it.
//
// THE ORDER AND THE SPACING ARE THE WHOLE OF WHAT THIS FILE DECIDES TODAY (owner,
// 2026-10-01), and they are two different rules:
//
//   * it comes LAST, under the failure card, the goal and the task list
//     (`components/composer-chrome.tsx`);
//   * and it carries NO gap beneath it, because what it is about is the very box it sits
//     on -- a queued message is a message this composer is about to send, so the two read
//     as one thing. Every other strip in that stack carries the blank gap and is therefore
//     a separate card; this is the exception, and that is the point of it -- so whichever
//     element draws a queued message here must NOT carry that gap class.
//
// IT RENDERS NOTHING FOR NOW. There is no queue on screen yet -- the slot is reserved so
// that the day there is one, the order and the spacing above are already what the owner
// asked for. An empty container is furniture (the rule the task list follows), so an
// empty queue must draw nothing rather than an empty row.
import type { FC } from "react";

export const ComposerQueue: FC<{
  /// The session this queue belongs to, the shape `ComposerTodos` already takes -- here
  /// so that filling this in later is a change to this file and not to its call site.
  threadId: string;
}> = () => null;
