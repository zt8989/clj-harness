// WHETHER A TURN IS OPEN, as the page's own value: one turn at a time, seeded from the read side
// and then told by the TURN FAMILY (`turn/start` / `turn/end`, `lib/mux.ts`'s facts).
//
// ================================================================ why not `stillBeingWritten`
//
// THAT READING ANSWERS A DIFFERENT QUESTION. `stillBeingWritten` is about the RUN: 'is somebody
// answering this conversation' -- this page's own run, or the window's `running` word. A TURN is a
// bigger thing than a run. It opens with a person's own words and closes only when a run leaves
// nothing owed (ADR 0006 decision 3: the server sends `turn/end` for `:run/end` / `:run/error` /
// `:run/stopped`, and NOT for `:run/interrupt`), so a parked run leaves its turn OPEN and a resume
// continues the same turn.
//
// The dot at a turn's end is about the TURN -- 'this turn is still being written' -- so the two
// facts are kept apart and neither is asked to answer the other's question:
//
//   * `open`      -- the turn's own lifecycle (this module);
//   * `writing`   -- somebody is writing RIGHT NOW (the run's, `lib/session-status.ts`);
//   * `isLast`    -- THIS footer is the open turn's end. Only one turn can be open and the open one
//                    is the thread's last; that is the shape of a turn list rather than a guess,
//                    and it is what keeps an earlier turn's end wearing its own furniture (the
//                    owner's third report: one dot per turn end, the moment a second message was
//                    sent).
//
// A MODULE OF ITS OWN rather than a few lines in `app.tsx`, for the reason `lib/turns.ts` gives:
// this is arithmetic over values the page holds, and the suite can pin it (`test/suites/running.tsx`)
// where `app.tsx` -- which reaches the runtime, i18n and the DOM -- cannot be rendered at all.

/// WHETHER A TURN IS OPEN. A value rather than a boolean so that the one fact it carries has a name
/// and a home: today it is 'has this turn's `turn/end` arrived?', which is all the dot needs.
export type LiveTurn = { readonly open: boolean };

/// NO OPEN TURN: what a page that has not opened anything, or has just finished a turn, holds.
export const NO_TURN: LiveTurn = { open: false };

export const turnIsOpen: LiveTurn = { open: true };

/// THE READ SIDE'S SEED for a page that OPENS a conversation in the middle of a turn.
///
/// THE TURN FAMILY CANNOT ANSWER THAT ONE, by decision (`.scratch/turn-and-model-events` decision 5:
/// the past is not replayed as events, only as a snapshot), so a reload hears nothing about the turn
/// that is already going. What it does have is the window's own state word: `running` means a run is
/// going, and the turn that run belongs to is open; `parked` leaves it open too (a parked turn is
/// RESUMED, not closed); `settled` -- and `unfinished`, a record that stops mid-run -- mean there is
/// no open turn to speak of. Anything else (a server that grows a fifth word) reads as 'no turn'.
export function turnFromWindow(state: string | null | undefined): LiveTurn {
  return state === "running" || state === "parked" ? turnIsOpen : NO_TURN;
}

/// ONE FACT -> the next value. `turn/start` opens a turn and `turn/end` closes it; every other frame
/// of the family (the model calls) and every frame of the other families leaves it alone -- so a
/// caller may hand this whatever its subscription delivers.
export function turnAfterFact(turn: LiveTurn, type: string): LiveTurn {
  if (type === "turn/start") return turnIsOpen;
  if (type === "turn/end") return NO_TURN;
  return turn;
}

/// WHETHER THIS MESSAGE'S TURN END WEARS THE DOT, from the three facts above. The dots are killed
/// one at a time and each kill is a case in the suite: an ENDED turn's end never wears one (that is
/// `open`), a turn nobody is writing in right now never wears one (`writing` -- the parked turn's
/// card is the sign there), and an EARLIER turn's end never wears one (`isLast`).
export const wearsWorkingDot = (turn: LiveTurn, writing: boolean, isLastMessage: boolean): boolean =>
  turn.open && writing && isLastMessage;
