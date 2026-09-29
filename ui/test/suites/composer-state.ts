// WHICH OF THE COMPOSER'S TWO STATES A SCREEN IS IN -- the question that used to be
// answered twice (`.scratch/composer-loading-state`).
//
// ================================================================ what is assertable here
//
// THE DECISION TABLE IS PURE. `lib/thread-view.ts` takes an `AssistantState` and returns a
// boolean, so every row of the table is a literal: a chat nobody has typed into, the
// startup placeholder, a conversation with messages, and -- the row this feature is about
// -- a switched session whose history is still in flight.
//
// THE OTHER HALF IS READ AS SOURCE, the idiom `suites/composer-todos.tsx` uses for where
// its strip sits: the frame cannot be rendered in this run (it reaches the assistant
// runtime, and there is no DOM), so what is pinned is the SHAPE of the fix -- the frame
// asks this one function instead of counting messages, and the question is defined once in
// the tree.
import { expect } from "vitest";

import type { AssistantState } from "@assistant-ui/react";

import { type Case, type Suite } from "../e2e";
import { isNewChatView } from "../../src/lib/thread-view";
import composerChromeSource from "../../src/components/composer-chrome.tsx?raw";
import threadSource from "../../src/components/assistant-ui/elements/thread.aui.tsx?raw";
import threadViewSource from "../../src/lib/thread-view.ts?raw";

/// ONE STATE OF THE PAGE, as much of it as the question reads: how many messages the
/// thread holds, whether THIS conversation's history is being fetched, and whether the
/// thread LIST is. Everything else an `AssistantState` carries is irrelevant here, which is
/// the point of asking the question in one place.
const state = (o: {
  messages?: number;
  loading?: boolean;
  threadsLoading?: boolean;
}): AssistantState =>
  ({
    thread: {
      messages: Array.from({ length: o.messages ?? 0 }, (_, i) => ({ id: `m${i}` })),
      isLoading: o.loading ?? false,
    },
    threads: { isLoading: o.threadsLoading ?? false },
  }) as unknown as AssistantState;

const cases: readonly Case[] = [
  {
    name: "the-new-chat-question-is-answered-for-every-row-of-its-table",
    run: async () => {
      // A chat nobody has typed into, with nothing being read: the new-chat screen.
      expect(isNewChatView(state({}))).toBe(true);
      // THE STARTUP PLACEHOLDER: a thread that says "loading" while what is loading is the
      // LIST, not this conversation. Treat it as a new chat so the composer mounts centered.
      expect(isNewChatView(state({ loading: true, threadsLoading: true }))).toBe(true);
      // A conversation with messages is not a new chat.
      expect(isNewChatView(state({ messages: 2 }))).toBe(false);
      // THE ROW THIS FEATURE IS ABOUT. Empty AND being read: docked, with a skeleton over
      // it -- NOT the new-chat screen. Counting messages said it was.
      expect(isNewChatView(state({ loading: true }))).toBe(false);
    },
  },
  {
    name: "the-frame-asks-the-one-question-instead-of-counting-messages",
    run: async () => {
      // The composer's half exactly as it is written: the negated question.
      expect(composerChromeSource).toContain(
        "const started = useAuiState((s) => !isNewChatView(s));",
      );
      // AND COUNTING MESSAGES HERE IS GONE -- that is the bug, spelled as the line it was.
      expect(composerChromeSource).not.toContain("s.thread.messages.length > 0");
      // ONE definition, and both readers take it from there: the copied element imports the
      // question rather than owning it, and `lib/thread-view.ts` exports it once.
      expect(threadSource).toContain('import { isNewChatView } from "@/lib/thread-view";');
      expect(threadSource).not.toContain("const isNewChatView = (s: AssistantState) =>");
      expect(threadViewSource.match(/export const isNewChatView/g) ?? []).toHaveLength(1);
    },
  },
];

export const composerStateSuite: Suite = { name: "composer-state", cases };
