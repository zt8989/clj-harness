// `/goal …` as a person types it: the ONE place a message is read as a goal command.
//
// WHY A PURE FUNCTION IN A MODULE OF ITS OWN. Two readers have to agree about it: the
// composer's way out (`lib/agent.ts`, which takes the command out of `append` and folds it
// onto the run request's `commands`) and the strip (`components/composer-goal.tsx`, which is
// what `/goal` alone opens). A rule written twice is a rule that disagrees with itself, and
// this one has a shape worth pinning. The TRIGGER is the skill slash's own: `/` at the start
// of the message, the name ended by whitespace or by the end of the message
// (`components/composer-chrome.tsx`'s `slashAtStart` is the client's copy of
// `harness.cap.skills/slash-pattern`) -- so `/goalx` is not a command, and a `/goal` in the
// middle of a sentence is not one either.
//
// `goal` IS A RESERVED NAME (spec decision 10): a skill called `goal` cannot be loaded with
// the slash, because this rule takes the word first. The cost is written down where the design
// writes its costs; the skill is still listed in the menu and the model can still load it.
import type { GoalAction } from "./goal";

/// THE VERB THAT IS NOT A WRITE, and that is on no wire: `/goal` alone is a READ. It asks the
/// strip to open, and it sends nothing -- there is no command for it, and a question would be
/// exactly the thing a command must not become.
export type GoalShow = "show";

/// THE READ VERB'S DOORBELL, which is the other half of that sentence. `/goal` alone is taken
/// out of a send by `lib/agent.ts` (the seam that decides what goes out) and the strip
/// (`components/composer-goal.tsx`) is what opens for it. A module-level set of listeners
/// rather than React state, for the same reason `lib/mux.ts`'s `openListeners` is one: the
/// asker is not in the component tree.
const showListeners = new Set<() => void>();

/// LISTEN FOR THE READ VERB, and answer the way to stop listening.
export function onGoalShown(reader: () => void): () => void {
  showListeners.add(reader);
  return () => {
    showListeners.delete(reader);
  };
}

/// RING IT. Called where a lone `/goal` is stopped from being sent: the nothing that was sent is
/// the point, and this is what the person gets for it.
export function showGoal(): void {
  for (const reader of showListeners) reader();
}

/// A message read as a command, or `null` when it is a question like any other.
export type ParsedGoalCommand =
  | { action: GoalShow }
  | { action: GoalAction; objective?: string };

/// WHETHER A MESSAGE OPENS WITH THE RESERVED WORD -- `/goal` at the start, the name ended by
/// whitespace or by the end of the message (`harness.cap.skills/slash-pattern`'s shape, which the
/// composer's own skill trigger copies).
///
/// IT IS EXPORTED BECAUSE TWO READERS NEED IT, and they must not spell it twice: this module,
/// which reads the command, and `components/composer-chrome.tsx`, whose SKILL MENU must NOT open
/// for it. `goal` is a reserved name (spec decision 10), and the menu is not merely cosmetic
/// there -- a popover that thinks a skill is being typed SWALLOWS the Enter that would send the
/// command, so `/goal` alone never reached the agent at all (found in the walkthrough, 2026-10-02).
export function opensGoalCommand(text: string): boolean {
  return /^\/goal(?!\S)/.test(text);
}

/// READ ONE MESSAGE. Only the shapes the design names are commands:
///
///   `/goal`                    -> show (the words after it would be an objective, not this)
///   `/goal <目标>`             -> create
///   `/goal edit <目标>`        -> edit
///   `/goal pause|resume|clear` -> that action
///
/// AND THE RESERVED VERBS ARE READ EXACTLY. `edit` with nothing after it is REFUSED rather
/// than taken as an objective called "edit", and `pause`/`resume`/`clear` take no argument at
/// all -- a trailing word there is refused rather than ignored, because "pause and also do
/// this other thing" is a sentence this rule cannot carry out and must not half-carry out.
export function parseGoalCommand(text: string): ParsedGoalCommand | null {
  // `/goal` FIRST, and the character after it whitespace or the end of the message.
  if (!opensGoalCommand(text)) return null;
  const rest = text.slice("/goal".length).trim();
  if (rest === "") return { action: "show" };
  const [, word = "", tail = ""] = /^(\S+)\s*([\s\S]*)$/.exec(rest) ?? [];
  switch (word) {
    case "pause":
    case "resume":
    case "clear":
      return tail === "" ? { action: word } : null;
    case "edit":
      return tail === "" ? null : { action: "edit", objective: tail };
    default:
      // ANYTHING ELSE IS THE OBJECTIVE, whole: a word that is not one of the reserved verbs
      // is part of what the person is asking for, not a verb this rule dropped.
      return { action: "create", objective: rest };
  }
}
