// THE ONE THING THE TRANSCRIPT AND THE PANEL HAVE TO AGREE ON: what is open.
//
// It lives in a module of its own -- rather than beside the panel -- because BOTH
// sides import it and only one of them may import the other. `message-parts.tsx`
// draws the `agent` card that opens the panel, and the panel renders the same
// message parts (`THREAD_COMPONENTS`); a module holding the door, the panel and the
// parts would be a cycle between the card and the conversation it is drawn in. This
// file imports React and nothing else, so either side can reach it.
import { createContext, useContext } from "react";

/// WHICH DELEGATION THE RIGHT-HAND PANEL IS SHOWING -- one at a time, so this is a
/// single value rather than a list (ticket 05: 一次一个). `subagent` is the name to
/// put in the header; `threadId` is the conversation to follow.
export type SubagentView = { threadId: string; subagent: string };

/// WHAT THE RIGHT-HAND COLUMN IS SHOWING, AND WHETHER IT IS THERE AT ALL -- ONE value with
/// three shapes, held by the page (`app.tsx`). The column is a single element in two states
/// (`.scratch/right-pane-tasks`, decision 1): the TASK VIEW, which is the switch's answer and
/// the answer nothing else has chosen; or one delegation's MIRROR, which is the `agent` card's
/// (`SubagentView` above, reused unchanged); or `null`, the column closed.
///
/// ONE VALUE AND NOT TWO (`open` plus `which`) is the decision: there is no moment at which the
/// column is open and has nothing to show, because opening it IS choosing the task view -- and
/// no moment at which it is showing a mirror while closed. Two flags could say both, and the
/// column would then have to guess which one it is in.
export type RightPane =
  | null
  | { kind: "tasks" }
  | ({ kind: "mirror" } & SubagentView);

/// The opener, or `null` where there is no panel to open (a story, a test that
/// renders one message with no page around it). A null opener is why the card's
/// door is drawn only when it can be walked through.
export const SubagentViewContext = createContext<((view: SubagentView) => void) | null>(null);

export function useOpenSubagentView(): ((view: SubagentView) => void) | null {
  return useContext(SubagentViewContext);
}