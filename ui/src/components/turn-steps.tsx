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
import { type FC, useCallback, useContext, useSyncExternalStore } from "react";
import { ChevronDownIcon } from "lucide-react";
import { useAuiState, type AssistantState } from "@assistant-ui/react";
import { useTranslation } from "react-i18next";

import { ThreadIdContext } from "@/components/composer-chrome";
import { serverTurnNumbers, subscribeTurnNumbers } from "@/lib/turn-numbers";
import {
  turnConclusion,
  turnIsSettled,
  turnStepBounds,
  turnSummaryLabel,
} from "@/lib/turns";
import type { TurnMessage, TurnOf } from "@/lib/turns";
import { subscribeTurnRows, turnOfMessage, turnRows } from "@/lib/turn-rows";
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
const turnKeyOf = (s: AssistantState, turnOf: TurnOf): string => {
  const { first } = turnStepBounds(s.thread.messages, s.message.index, turnOf);
  return turnOf(s.thread.messages[first] ?? {}) ?? s.thread.messages[first]?.id ?? "";
};

const isHeadOf = (s: AssistantState, turnOf: TurnOf): boolean =>
  turnStepBounds(s.thread.messages, s.message.index, turnOf).first === s.message.index;

const isConclusionOf = (s: AssistantState, turnOf: TurnOf): boolean => {
  const { messages } = s.thread;
  const { first, last } = turnStepBounds(messages, s.message.index, turnOf);
  return turnConclusion(messages, first, last) === s.message.index;
};

/// A turn can fold when it has stopped AND has something to fold. A turn of one
/// message is just the answer, and a summary line in front of it would be a header
/// for nothing -- the same reason `.scratch/flat-step-rows` deleted the old
/// "1 tool call" group header.
const isFoldableOf = (s: AssistantState, turnOf: TurnOf): boolean => {
  const { messages } = s.thread;
  const { first, last } = turnStepBounds(messages, s.message.index, turnOf);
  return last > first && turnIsSettled(messages, last, s.thread.isRunning);
};

/// THE NUMBERS THE SUMMARY LINE PRINTS -- and there is one, not two, since ticket 05 of
/// `.scratch/step-events`: a step IS a model request, the read side sees one message per
/// request, so the message count was this number said twice.
///
/// IS THIS TURN THE CONVERSATION'S LAST ONE? The last turn's own end IS the last `turn/end` a page
/// heard (turns close in order), so for that one -- and only that one -- the number pushed the
/// moment it was written is about the turn this line is drawing.
const isNewestTurnOf = (s: AssistantState, turnOf: TurnOf): boolean => {
  const { last } = turnStepBounds(s.thread.messages, s.message.index, turnOf);
  return last === s.thread.messages.length - 1;
};

/// WHAT TURN THE RECORD PUTS EACH MESSAGE IN, for the conversation on screen (ADR 0017). Every
/// selector below takes it, so they all read the SAME answer -- and it is rebuilt whenever a frame
/// moves the turns, which is what makes them recompute.
const useTurnOf = (): TurnOf => {
  const threadId = useContext(ThreadIdContext);
  const rows = useSyncExternalStore(subscribeTurnRows, () => turnRows(threadId));
  return useCallback(
    (message: TurnMessage) => turnOfMessage(threadId, message.id ?? null)?.turnId,
    // `rows` is not read in the body: it is the DEPENDENCY that says "the record changed its
    // mind", and every selector closing over this function is recomputed when it does.
    [threadId, rows],
  );
};


/// THE STEPS THIS TURN TOOK -- ONE number, and it is the RECORD'S (ADR 0017). It is what the turn's
/// own `turn/end` row carries: either the row the window is holding, or the same row pushed the
/// moment it was written (`lib/turn-numbers.ts`). Both are the one fact, said on two roads.
const useTurnSteps = (turnOf: TurnOf): number => {
  const threadId = useContext(ThreadIdContext);
  const rows = useSyncExternalStore(subscribeTurnRows, () => turnRows(threadId));
  const mine = useAuiState((s) => turnKeyOf(s, turnOf));
  const newest = useAuiState((s) => isNewestTurnOf(s, turnOf));
  const heard = useSyncExternalStore(subscribeTurnNumbers, () => serverTurnNumbers(threadId));
  const row = rows.find((turn) => turn.turnId === mine);
  return newest && heard !== undefined ? heard.steps : (row?.steps ?? 0);
};

// ------------------------------------------------------------------ the hooks

/// Where this message stands in its turn's fold:
///
///   "none"    nothing is folded here -- draw the message as it stands
///   "step"    a step inside a folded turn: the whole message is put away
///   "head"    the summary line is drawn, and this message's own content is put
///             away while the turn is folded
///   "answer"  the turn's CONCLUSION with the turn folded: this message is drawn,
///             but only what was SAID of it -- its reasoning and its tool calls are
///             steps like the rest, and the fold puts those away
///
/// "head" is the message's PLACE in the turn, not its state: a head keeps drawing
/// the summary line after the reader opens the turn, because that line is the only
/// control that can fold it again -- a trigger that vanishes when used has no way
/// back. `useTurnFolded` is what the line and the head's own content follow.
///
/// A TURN WITH NO CONCLUSION HAS NO "answer": every message of it is a "step", the
/// last one included, so a turn that stopped mid-thought is put away WHOLE rather
/// than leaving its last thought or tool call on screen (see `turnConclusion`). The
/// running turn never reaches here at all -- `isFoldableOf` is false for it -- which
/// is what keeps its steps in front of the reader while they are happening.
///
/// A CARD IS NOT A STEP, and this is the one thing the answer above does not decide
/// (`.scratch/compaction-frames`, `lib/card-parts`). The three answers here are about a
/// MESSAGE's place in the turn; whether a message that is put away still shows something
/// depends on its PARTS, and `thread.aui.tsx` draws exactly one kind of card in a folded
/// turn: a COMPACTION (`isKeptCardPart`), because it is the boundary where history stopped
/// being messages. An INJECTED CONTEXT is material the turn was handed -- an instruction
/// file, a skill body, a job's ending -- so it goes away with the turn's steps, which is what
/// 'fold the context injection together with the turn' means. This function's answer is
/// unchanged by any of that -- a "step" is still a step.
///
/// Every selector above returns a primitive, because `useAuiState` compares with
/// `Object.is`: a fresh object would re-render this message on every store update,
/// which during a run is every token.
export function useStepFold(): "none" | "step" | "head" | "answer" {
  const turnOf = useTurnOf();
  const head = useAuiState((s) => isHeadOf(s, turnOf));
  const foldable = useAuiState((s) => isFoldableOf(s, turnOf));
  const folded = useTurnFolded();
  const conclusion = useAuiState((s) => isConclusionOf(s, turnOf));

  if (!foldable) return "none";
  if (head) return "head";
  if (!folded) return "none";
  return conclusion ? "answer" : "step";
}

/// Whether the turn is folded right now: what the summary line's chevron and
/// `aria-expanded` report, and what the head's own content follows.
export function useTurnFolded(): boolean {
  const turnOf = useTurnOf();
  const key = useAuiState((s) => turnKeyOf(s, turnOf));
  const foldable = useAuiState((s) => isFoldableOf(s, turnOf));
  const unfolded = useTurnOpened(key);
  return foldable && !unfolded;
}

/// The first message of the NEXT turn after this one -- where the turn this message stands in front
/// of begins. -1 when there is none.
///
/// IT HAS TO LOOK FORWARD because a CARD-ONLY user message belongs to no turn at all: `turnOf`
/// puts it in none, so `first === last` for it and there is nothing to fold. A session's opening
/// blocks arrive exactly that way.
const nextTurnIndex = (s: AssistantState, turnOf: TurnOf): number => {
  const { messages } = s.thread;
  for (let i = s.message.index + 1; i < messages.length; i += 1) {
    if (turnOf(messages[i] ?? {}) !== undefined && turnStepBounds(messages, i, turnOf).first === i) {
      return i;
    }
  }
  return -1;
};

/// The name of the turn that follows this message -- the same name that turn's own summary line
/// folds and unfolds.
const followingTurnKeyOf = (s: AssistantState, turnOf: TurnOf): string => {
  const i = nextTurnIndex(s, turnOf);
  if (i < 0) return "";
  const { messages } = s.thread;
  const { first } = turnStepBounds(messages, i, turnOf);
  return turnOf(messages[first] ?? {}) ?? messages[first]?.id ?? "";
};

const isFollowingFoldableOf = (s: AssistantState, turnOf: TurnOf): boolean => {
  const i = nextTurnIndex(s, turnOf);
  if (i < 0) return false;
  const { messages } = s.thread;
  const { first, last } = turnStepBounds(messages, i, turnOf);
  return last > first && turnIsSettled(messages, last, s.thread.isRunning);
};

/// WHETHER THE TURN THIS MESSAGE STANDS IN FRONT OF IS FOLDED -- the question an injected
/// card asks (`thread.aui.tsx`'s `UserInjectionCard`), so that folding a turn takes the
/// context it was handed with it: an opening's instruction file, the skill catalogue, a body
/// a person asked for. Unfold the turn and the card is back, like any other step of it.
///
/// A CARD WITH NO TURN AFTER IT IS NEVER HIDDEN (`isFollowingFoldableOf` is false for it):
/// there is nothing to fold it with, and hiding it would be the view losing a fact.
export function useFollowingTurnFolded(): boolean {
  const turnOf = useTurnOf();
  const key = useAuiState((s) => followingTurnKeyOf(s, turnOf));
  const foldable = useAuiState((s) => isFollowingFoldableOf(s, turnOf));
  const unfolded = useTurnOpened(key);
  return foldable && !unfolded;
}


/// Whether THIS message is the turn's conclusion WHILE the turn is folded -- the one
/// message a folded turn still shows, and of which it shows only what was said. The
/// head can be the conclusion too (a turn whose first message already answered and
/// then kept working): that message still draws the summary line, and this is what
/// keeps its own prose rather than hiding it with the rest of the head.
///
/// It is `false` for the running turn and for a turn with no conclusion, which is
/// exactly the two cases `useStepFold` already answers `"none"`/`"step"` for.
export function useFoldedAnswer(): boolean {
  const turnOf = useTurnOf();
  const folded = useTurnFolded();
  const conclusion = useAuiState((s) => isConclusionOf(s, turnOf));
  return folded && conclusion;
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
  const turnOf = useTurnOf();
  const key = useAuiState((s) => turnKeyOf(s, turnOf));
  const folded = useTurnFolded();
  const steps = useTurnSteps(turnOf);
  // The line's own words live in the `thread` face; `lib/turns.ts` takes the
  // translator rather than holding one, so this row is the one place that binds it
  // to the language the page is speaking.
  const { t } = useTranslation("thread");

  return (
    <button
      type="button"
      data-slot="turn-steps-trigger"
      data-steps={steps}
      aria-expanded={!folded}
      onClick={() => toggleTurn(key)}
      className="aui-turn-steps-trigger text-muted-foreground hover:text-foreground flex w-full origin-left items-center gap-1.5 py-1.5 text-[13px] transition-[color,scale] active:scale-[0.98]"
    >
      <span
        data-slot="turn-steps-label"
        className="aui-turn-steps-label leading-none"
      >
        {turnSummaryLabel(steps, t)}
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
