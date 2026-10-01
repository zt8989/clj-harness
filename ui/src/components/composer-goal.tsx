// THE GOAL STRIP ABOVE THE COMPOSER -- RESERVED, AND DELIBERATELY EMPTY FOR NOW.
//
// WHERE IT SITS IS THE WHOLE OF WHAT THIS FILE DECIDES TODAY, and it is the order the
// owner asked for (2026-10-01), read top to bottom in
// `components/composer-chrome.tsx`: the session's FAILURE, then this GOAL, then the
// TASK LIST, then the QUEUED MESSAGES -- and only the last of those four touches the
// composer's own box. The slot exists so that the day a session's goal gets a reader
// here, the layout is already the one that was asked for and nothing has to be moved.
//
// IT RENDERS NOTHING, AND THAT IS NOT A PLACEHOLDER BOX. An empty container is
// furniture, and furniture that comes and goes is worse than furniture that is simply
// not there (the rule the task list and the archived block both follow) -- so a session
// with no goal draws no goal. What is reserved here is the POSITION in the frame, not a
// rectangle on the screen.
//
// AND WHEN IT DRAWS, IT OWES A `mb-1.5` -- the same 6px blank gap the failure card and the
// task list carry. The goal sits between two of them, so it is a block of its own rather
// than part of either neighbour.
import type { FC } from "react";

export const ComposerGoal: FC<{
  /// The session this strip belongs to, the shape `ComposerTodos` already takes -- here
  /// so that filling this in later is a change to this file and not to its call site.
  threadId: string;
}> = () => null;
