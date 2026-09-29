// WHAT THE THREAD IS LOOKING AT, asked once because TWO things read it.
//
// `isNewChatView` is the whole of "is this the new-chat screen?": the thread mounts the
// composer CENTERED on it, and the composer's own chrome is the other half of the same
// answer -- the project/branch bar belongs to it, and the docked chrome (the footer's zero
// bottom padding, the slot the task strip sits in) belongs to everything else.
//
// IT USED TO BE ANSWERED TWICE, and the space between the two answers was a state of its
// own. The composer counted messages (`s.thread.messages.length > 0`), which is FALSE for
// a session whose history is still in flight -- a session that is not a new chat. So for
// that instant the frame drew the new-chat bar over a docked, skeleton-filled
// conversation, and the footer under it kept the `pb-4 md:pb-6` strip that exists to be
// removed. See `.scratch/composer-loading-state/spec.md`.
//
// Startup exposes a loading placeholder thread; treat it as a new chat so the composer
// mounts centered. Loads after startup keep the docked layout.

import type { AssistantState } from "@assistant-ui/react";

export const isNewChatView = (s: AssistantState) =>
  s.thread.messages.length === 0 &&
  (!s.thread.isLoading || s.threads.isLoading);
