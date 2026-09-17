"use client";

// The fold: when a turn has stopped, its steps are put away behind one summary
// line, and the answer is what stays.
//
// WHY A TURN IS THE UNIT. A run is not one message -- every LLM round opens its
// own assistant message (see `lib/turns.ts`) -- so a turn that read four files
// and then answered is five messages: four of them the WORK, one of them the
// ANSWER the reader asked for. Left unfolded, a long conversation is mostly work:
// the reader scrolls past rows of `read` to find the sentence. So a settled turn
// shows its answer and says what is behind it.
//
// FOLDED IS THE DEFAULT; OPENING IS THE EXCEPTION. This module holds the turns the
// reader has opened by hand, so "folded" needs no effect and no transition: a turn
// folds when it settles because settling is what makes it foldable, and a reader
// who opens one keeps it open, because nothing can undo their click.
//
// WHILE A TURN IS RUNNING NOTHING IS FOLDED. The steps are what is happening --
// a thought is streaming, a tool is running -- and a running turn is not settled
// (see `turnIsSettled`). A PARKED turn is not settled either: its approval card
// lives inside a step, and folding would put the thing the run is waiting on out
// of reach. History is always settled, so history always arrives folded.
//
// THE FOLD IS NOT REMEMBERED ACROSS A RELOAD, and that is deliberate: it is a way
// of looking at the conversation in front of you rather than a preference about
// conversations, which is the same line `app.tsx` takes for the
// conversation/trajectory switch.
import { type FC, useSyncExternalStore } from "react";
import { ChevronDownIcon } from "lucide-react";
import { useAuiState, type AssistantState } from "@assistant-ui/react";

import {
  turnBounds,
  turnCounts,
  turnIsSettled,
  turnSummaryLabel,
} from "@/lib/turns";
import { cn } from "@/lib/utils";

// ------------------------------------------------------------------ the store

/// The turns the reader has opened, by the id of the turn's FIRST message.
///
/// Module-level rather than React state, because the two ends of one fold are not
/// in one component: the summary line is drawn by the turn's first message, and
/// the steps it hides are that message's siblings. There is no common parent short
/// of the copied `thread.aui.tsx` itself, so the state lives beside the hooks that
/// read it. (`lib/attachments.ts` keeps the composer's store the same way.)
const opened = new Set<string>();
const listeners = new Set<() => void>();

function subscribe(listener: () => void): () => void {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
}

/// Fold or unfold one turn. Only the summary line calls this.
function toggleTurn(key: string): void {
  if (opened.has(key)) opened.delete(key);
  else opened.add(key);
  for (const listener of listeners) listener();
}

function useTurnOpened(key: string): boolean {
  return useSyncExternalStore(subscribe, () => opened.has(key));
}

// ------------------------------------------------------------------ the facts

/// The turn this message belongs to, named by its first message's id -- one name
/// for a turn that survives the turn growing under it.
const turnKeyOf = (s: AssistantState): string => {
  const { first } = turnBounds(s.thread.messages, s.message.index);
  return s.thread.messages[first]?.id ?? "";
};

const isHeadOf = (s: AssistantState): boolean =>
  turnBounds(s.thread.messages, s.message.index).first === s.message.index;

const isTailOf = (s: AssistantState): boolean =>
  turnBounds(s.thread.messages, s.message.index).last === s.message.index;

/// A turn can fold when it has stopped AND has something to fold. A turn of one
/// message is just the answer, and a summary line in front of it would be a header
/// for nothing -- the same reason `.scratch/flat-step-rows` deleted the old
/// "1 tool call" group header.
const isFoldableOf = (s: AssistantState): boolean => {
  const { messages } = s.thread;
  const { first, last } = turnBounds(messages, s.message.index);
  return last > first && turnIsSettled(messages, last, s.thread.isRunning);
};

/// The two numbers the summary line prints.
const turnCallsOf = (s: AssistantState): number => {
  const { first, last } = turnBounds(s.thread.messages, s.message.index);
  return turnCounts(s.thread.messages, first, last).calls;
};

const turnMessagesOf = (s: AssistantState): number => {
  const { first, last } = turnBounds(s.thread.messages, s.message.index);
  return last - first + 1;
};

// ------------------------------------------------------------------ the hooks

/// Where this message stands in its turn's fold:
///
///   "none"  nothing is folded here -- draw the message as it stands
///   "step"  a step inside a folded turn: the whole message is put away
///   "head"  the summary line is drawn, and this message's own content is put
///           away while the turn is folded: the answer further down is what the
///           reader keeps
///
/// "head" is the message's PLACE in the turn, not its state: a head keeps drawing
/// the summary line after the reader opens the turn, because that line is the only
/// control that can fold it again -- a trigger that vanishes when used has no way
/// back. `useTurnFolded` is what the line and the head's own content follow.
///
/// Every selector above returns a primitive, because `useAuiState` compares with
/// `Object.is`: a fresh object would re-render this message on every store update,
/// which during a run is every token.
export function useStepFold(): "none" | "step" | "head" {
  const head = useAuiState(isHeadOf);
  const tail = useAuiState(isTailOf);
  const foldable = useAuiState(isFoldableOf);
  const folded = useTurnFolded();

  if (!foldable) return "none";
  if (head) return "head";
  return tail || !folded ? "none" : "step";
}

/// Whether the turn is folded right now: what the summary line's chevron and
/// `aria-expanded` report, and what the head's own content follows.
export function useTurnFolded(): boolean {
  const key = useAuiState(turnKeyOf);
  const foldable = useAuiState(isFoldableOf);
  const unfolded = useTurnOpened(key);
  return foldable && !unfolded;
}

// ------------------------------------------------------------ the summary line

/// The line a folded turn leaves behind: what is behind it, and the way in.
///
/// It stays on screen while the turn is OPEN as well -- it is the only control
/// that can fold the turn again, and a disclosure whose trigger disappears when
/// opened has no way back.
///
/// `13px` and muted, like the step rows it stands in for: it is a row of the
/// transcript, not a piece of chrome. The chevron follows the text rather than
/// sitting at the far right, the way the reference this repo is matched against
/// draws it.
export const TurnStepsTrigger: FC = () => {
  const key = useAuiState(turnKeyOf);
  const folded = useTurnFolded();
  const calls = useAuiState(turnCallsOf);
  const messages = useAuiState(turnMessagesOf);

  return (
    <button
      type="button"
      data-slot="turn-steps-trigger"
      data-calls={calls}
      data-messages={messages}
      aria-expanded={!folded}
      onClick={() => toggleTurn(key)}
      className="aui-turn-steps-trigger text-muted-foreground hover:text-foreground flex w-full origin-left items-center gap-1.5 py-1.5 text-[13px] transition-[color,scale] active:scale-[0.98]"
    >
      <span
        data-slot="turn-steps-label"
        className="aui-turn-steps-label leading-none"
      >
        {turnSummaryLabel(calls, messages)}
      </span>
      <ChevronDownIcon
        data-slot="turn-steps-chevron"
        aria-hidden="true"
        className={cn(
          "aui-turn-steps-chevron size-4 shrink-0",
          "transition-transform duration-200 ease-[cubic-bezier(0.32,0.72,0,1)] motion-reduce:transition-none",
          folded && "-rotate-90",
        )}
      />
    </button>
  );
};
