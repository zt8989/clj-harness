// This session's GOAL, typed thin: one objective the conversation is chasing across rounds,
// and whether THIS process may open the next round of it.
//
// WHY IT IS NOT THE TASK LIST (`lib/todos.ts`). A `todo_write` list is the MODEL's own
// breakdown of the next few steps, rewritten as it goes; a goal is one sentence about where
// the whole thing is going, written by a person (or by the model on a person's direct
// request) and outliving any single round of it. They are two answers to two questions --
// where to, and what next -- and neither overwrites the other. The row this reads is the
// record's fold (`harness.cap.goal`, `.scratch/goal`'s spec), so a strip asks the STORE,
// exactly as `todosFor` asks it for a task list.
//
// AND A FAILED READ IS NO GOAL, not an exception: a session that never had one answers
// `{goal: null}` with a 200 rather than a 404, and a server that cannot be reached is not
// something a strip has words for either -- the same reasoning, and the same shape, that
// `lib/todos.ts`'s `todosFor` states.
//
// THE OTHER HALF IS A COMMAND, NEVER A ROUTE. `applyGoal` is not a second write endpoint:
// writes go through the session's command queue (`.scratch/run-commands`), which is a
// `commands` list on the same run request every send uses -- the lane `lib/agent.ts` also
// folds a `/goal …` typed into the composer onto.
import type { TFunction } from "i18next";

// `apiBase()` RATHER THAN THE `API_BASE` CONST: a suite learns the harness's origin at
// runtime, after this module is loaded, and this module is one a suite drives through the
// app's own code (`lib/mux.ts` says the same about its own reads).
import { apiBase, refusalFrom } from "@/lib/threads";

/// ONE PHASE, as `harness.cap.goal` names it -- the server's vocabulary, and this interface
/// is what puts words on it (`goal.phase.*`). There is no fifth: a cleared goal is `null`, not
/// a phase, so a session that was never given one and a session that had one taken away are
/// the same answer to every reader.
export type GoalPhase = "active" | "paused" | "blocked" | "completed";

/// WHY A GOAL STOPPED, in the server's own terms: a kebab `code` (a keyword a reader can match
/// on) and a `reason` written for a person. `pending-block` is the same pair plus how many
/// rounds the model has reported it, and it is deliberately NOT the phase -- the design makes a
/// blocker hold for several rounds before it counts (`.scratch/goal` decision 7), because one
/// bad round is not a thing that cannot be done.
export type GoalBlocker = { code: string; reason: string };
export type GoalPendingBlocker = GoalBlocker & { reports: number };

/// One goal as the route answers it, field for field -- kebab-case on the wire, which is how
/// `harness.cap.goal`'s snapshot spells them.
export type Goal = {
  id: string;
  /// THE FENCE A WRITE HAS TO NAME (spec decision 2). It moves on every change, so a stale
  /// `{id, revision}` is refused by name rather than quietly overwriting the write that moved
  /// it -- and a goal a person cleared and made again is a NEW id, never the old one reused.
  revision: number;
  objective: string;
  phase: GoalPhase;
  /// How many rounds the driver has opened, against the cap (a fuse, not a target: the
  /// default is small on purpose -- `.scratch/goal` decision 9).
  rounds: number;
  "max-rounds": number;
  "updated-at": number;
  blocked?: GoalBlocker;
  "pending-block"?: GoalPendingBlocker;
};

/// THE ROUTE'S WHOLE ANSWER, and the reason `armed` is beside `goal` rather than inside it:
/// the goal is the RECORD's (it survives a restart) while `armed` is the PROCESS's ("this
/// process may still open the next round"), held in memory like a pending approval. A restart,
/// a resume or a fork leaves an ACTIVE goal that will not carry on by itself, so folding the
/// two into one object would draw a stopped goal after a restart nobody asked about -- and a
/// client that inferred one from the other would be inventing the fact.
export type GoalAnswer = { goal: Goal | null; armed: boolean };

/// The absence of both, as one value: what every failed read answers, and what a session with
/// no goal answers.
const NO_GOAL: GoalAnswer = { goal: null, armed: false };

/// THE GOAL THIS PAGE IS SHOWING, by conversation -- the FENCE a `/goal …` typed into the composer
/// has to name, published by the strip and read by the seam that folds the command into a send
/// (`lib/agent.ts`). The two are in different trees -- a request is built by the agent, and what
/// is on screen is a component's -- so the one thing they must agree about (which revision a
/// command is about) travels through here rather than through either.
///
/// IT IS NOT THE GOAL'S READER: `goalFor` and the pushed frame are that. This is only what the
/// person is LOOKING at, which is what a command's fence means.
const showing = new Map<string, Goal>();

/// Publish what the strip has just drawn. Null is 'nothing on screen for this conversation',
/// which is the ordinary answer for a session with no goal.
export function noteGoalShown(threadId: string, goal: Goal | null): void {
  if (goal === null) showing.delete(threadId);
  else showing.set(threadId, goal);
}

/// WHAT A COMMAND TYPED WITH NO GOAL ON SCREEN NAMES: nothing. Such a command still goes out --
/// the server's own sentence is a better answer than this side inventing one -- and a verb that
/// needs a fence is refused by name (`harness.cap.goal/fenced`).
export function goalShown(threadId: string): Goal | null {
  return showing.get(threadId) ?? null;
}

/// THIS SESSION'S GOAL, or none. One GET, and it never throws: see the header.
export async function goalFor(threadId: string): Promise<GoalAnswer> {
  try {
    const res = await fetch(`${apiBase()}threads/${encodeURIComponent(threadId)}/goal`);
    if (!res.ok) return NO_GOAL;
    const body = (await res.json()) as { goal?: Goal | null; "armed?"?: unknown };
    return { goal: body.goal ?? null, armed: body["armed?"] === true };
  } catch {
    // A server that is not there is not this strip's to word, and it is the same absence as a
    // session that never had a goal -- which is what `todosFor`'s empty list means too.
    return NO_GOAL;
  }
}

/// ONE HAND A PERSON HAS ON A GOAL: the four writes, plus `create` (which the model can do by
/// itself, on a person's direct request). `edit` changes the WORDS and nothing else (spec
/// decision 10): a paused goal stays paused after an edit, because the words are not the phase.
export type GoalAction = "create" | "edit" | "pause" | "resume" | "clear";

/// A goal command as it rides a run request's `commands` (`harness.edge.commands`). The names
/// are the WIRE's, `goal_id` and `max_goal_rounds` included, because the server reads them by
/// keyword -- this type is the client's copy of that contract, not a local shape.
export type GoalCommand = {
  type: "goal";
  action: GoalAction;
  objective?: string;
  goal_id?: string;
  revision?: number;
  max_goal_rounds?: number;
};

/// What the edge answers about one command: carried out, or refused with the capability's OWN
/// sentence. `harness.cap.goal` is the only rule a command and a tool share, so a refusal is
/// passed through here rather than re-worded.
export type GoalVerdict =
  | { type: "goal"; ok: true }
  | { type: "goal"; reason: string; error: string };

/// THE WRITE DOOR, for every button on the strip. It packages the command into a run request
/// -- `append` empty, `commands` carrying it -- and posts that to the agent route, which is
/// the only route a command has (there is no `POST /api/goal`; spec decision 4).
///
/// `body` IS THE FENCE THE STRIP IS HOLDING (`goal_id`, `revision`), plus the new words for
/// `edit`. The strip never invents a ref: a ref it made up is one the server would refuse, or
/// worse, accept against a goal that had already moved.
///
/// A REFUSAL IS THROWN, not returned. The answer to a request that carried ONLY commands is
/// the one place this door can be told no -- a command a RUN carried is refused into that run
/// instead (`harness.edge.http/refusal-note`) -- and a press that failed in silence is the one
/// thing a button must never do.
export async function applyGoal(
  threadId: string,
  action: GoalAction,
  body: Omit<GoalCommand, "type" | "action">,
  t: TFunction<"errors">,
): Promise<void> {
  const command: GoalCommand = { type: "goal", action, ...body };
  const res = await fetch(`${apiBase()}agent`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ threadId, append: [], commands: [command] }),
  });
  if (!res.ok) throw new Error(await refusalFrom(res, t));
  const answer = (await res.json()) as { commands?: GoalVerdict[] };
  // A REFUSED VERDICT IS THE ONE THAT CARRIES A SENTENCE; a `ok: true` one has none to read.
  const refused = answer.commands?.find((verdict) => "error" in verdict);
  if (refused !== undefined && "error" in refused) throw new Error(refused.error);
}
