// THE GOAL STRIP ABOVE THE COMPOSER: the one objective this conversation is chasing, and the
// hands a person has on it (tickets 07 and 08 of `.scratch/goal`).
//
// WHY IT IS A CARD, AND WHY NO GOAL IS NO CARD. The same two rules the task strip follows
// (`composer-todos.tsx`), because the two sit in one stack -- the failure card, this, the task
// list, the queue -- and three ideas of "a block above the composer" would read as three
// products. A session with no goal draws NOTHING; an empty box is furniture for something that
// is not there.
//
// WHAT IT SAYS, and why each part is where it is:
//
//   * the FOLDED LINE is the phase and the objective -- the two answers to "where is this
//     going" at a glance. The objective is clipped to the line, and the whole of it is one
//     click (and one hover, through the `title`) away: the fold exists for the objective ALONE.
//   * `round n/max` and the blocker's `code`/`reason` are drawn OUTSIDE the fold, always. A goal
//     that has stopped has to say WHY on sight -- putting "no-progress" behind a click is this
//     strip failing at its one job, because the person reading it is the one who unblocks it
//     (`.scratch/goal` decision 9: the driver stops on a round that changed no file).
//   * the ARMED LINE says whether this PROCESS may open the next round by itself. `armed` is not
//     a revision and not part of the goal: it is this process's memory, it is lost on a restart
//     and on a resume or a fork, and the difference between 自动续跑中 and 已停（要人再说一句）
//     is the whole brake the design is built around -- so it is DRAWN, never inferred.
//   * the BUTTONS are the phase's own set, and the set is decided by phase AND arm together: an
//     active armed goal offers `pause` (a person stops it), an active goal with no arm offers
//     `resume` (re-arming is what gets it moving again), paused and blocked goals offer `resume`
//     because only a person may lift either one (`.scratch/goal` decision 6), and a completed
//     goal has one verb left: `clear`.
//
// WHEN IT ASKS -- the whole of "when", and why there is no timer (`docs/rules/panel-data.md`,
// both of its halves). ONE snapshot read on mount and whenever the session changes (the row is
// the server's, so a page that refreshes draws the goal before any run happens); from there the
// pushed `goal` frame is the only thing that moves it (`subscribeGoals`); plus a snapshot
// re-read on the two facts the model's own writes fall between (`model/start`, `turn/end` -- the
// model may create or edit a goal with its tools, and that write has no frame of its own); plus
// one more after a reconnect (`onDownlinkOpen`), because a `goal` frame carries no cursor and a
// change that happened while the socket was down is in no frame this page will be handed. NO
// POLLING: there is no clock anywhere in this file.
//
// A SESSION CHANGE DROPS THE OLD GOAL rather than leaving it up while the new one is in flight:
// `live` refuses an answer a later session superseded, and the reset refuses a stale DRAW -- the
// same two halves `composer-todos.tsx` names.
import { useEffect, useState, type FC } from "react";
import { ChevronUpIcon, TargetIcon } from "lucide-react";
import { useTranslation } from "react-i18next";

import { Button } from "@/components/ui/button";
import {
  Collapsible,
  CollapsibleContent,
  CollapsibleTrigger,
} from "@/components/ui/collapsible";
import { Textarea } from "@/components/ui/textarea";
import { applyGoal, goalFor, noteGoalShown, type Goal, type GoalAction } from "@/lib/goal";
import { onGoalShown } from "@/lib/goal-command";
import { onDownlinkOpen, subscribeFacts, subscribeGoals } from "@/lib/mux";

/// The phase words, spelled as literal keys so `src/i18next.d.ts` catches a rename.
const PHASE_KEY = {
  active: "goal.phase.active",
  paused: "goal.phase.paused",
  blocked: "goal.phase.blocked",
  completed: "goal.phase.completed",
} as const;

/// A HAND THIS STRIP OFFERS: the four writes a strip draws, with `create` left out -- a goal is
/// made from the composer (`/goal <目标>`, `lib/goal-command.ts`) or by the model, never from the
/// strip, which is not drawn at all until there is a goal to draw.
type GoalHand = Exclude<GoalAction, "create">;

/// The four hands, one word each. `resume` covers both of its cases on
/// purpose -- a paused or blocked goal needs LIFTING, an active-but-disarmed one needs RE-ARMING,
/// and to the server that is one verb and to the person one press.
/// `satisfies` RATHER THAN AN ANNOTATION: the values have to stay LITERAL (that is what lets
/// `src/i18next.d.ts` catch a renamed catalog key at the call site) while the four hands have to
/// stay COVERED -- a fifth verb added to `GoalHand` without a word here is a compile error.
const ACTION_KEY = {
  pause: "goal.action.pause",
  resume: "goal.action.resume",
  edit: "goal.action.edit",
  clear: "goal.action.clear",
} as const satisfies Record<GoalHand, string>;

/// WHICH HANDS THIS GOAL OFFERS, from its phase and its arm together -- the four rows of the
/// ticket, in one function so the buttons and the words around them cannot disagree.
function actionsFor(goal: Goal, armed: boolean): readonly GoalHand[] {
  if (goal.phase === "completed") return ["clear"];
  if (goal.phase === "active" && armed) return ["pause", "edit", "clear"];
  return ["resume", "edit", "clear"];
}

/// WHAT A PRESS MEANS, as the view says it: which verb, and the words it carries when the verb is
/// `edit`. The FENCE (`goal_id`, `revision`) is NOT here -- it belongs to whoever is holding the
/// goal (`ComposerGoal`), and two places that could build one are two places that could disagree
/// about which revision a press is about.
export type GoalPress = (action: GoalAction, body?: { objective?: string }) => void;

/// THE STRIP WITH DATA IN HAND -- split from the fetch so the suites can render it to a string
/// (`react-dom/server`), which is where the buttons, the rounds and the words are pinned.
export const ComposerGoalView: FC<{
  goal: Goal | null;
  armed: boolean;
  /// WHETHER THE FOLD IS OPEN. It belongs to the WIRING and not to this view: `/goal` alone is a
  /// request to LOOK at the strip, and that request arrives from outside this component
  /// (`onGoalShown`), so a view holding the flag for itself could not answer it.
  open: boolean;
  onOpenChange: (open: boolean) => void;
  onPress: GoalPress;
  /// What the last press was told no with, or null. It is the SERVER's own sentence (see
  /// `lib/goal.ts`), so it is drawn as it arrived.
  failure?: string | null;
}> = ({ goal, armed, open, onOpenChange, onPress, failure = null }) => {
  const { t } = useTranslation("composer");
  // THE DRAFT WHILE AN EDIT IS IN PLACE, or null when no edit is open. The words go out as the
  // command's `objective`, and the strip does NOT touch its own goal for them: the pushed frame
  // is what says the edit landed.
  const [draft, setDraft] = useState<string | null>(null);

  if (goal === null) return null;

  return (
    <Collapsible
      data-slot="composer-goal"
      open={open}
      onOpenChange={onOpenChange}
      // THE SAME BOX THE FAILURE CARD AND THE TASK STRIP DRAW -- the composer's own material,
      // rounded the composer's way, with the 6px blank gap under it that keeps each block a card
      // of its own rather than part of its neighbour (`composer-todos.tsx` says why).
      className="border-border/60 bg-(--composer-bg) mb-1.5 flex flex-col rounded-(--composer-radius) border"
    >
      <CollapsibleTrigger
        data-slot="composer-goal-toggle"
        className="text-muted-foreground hover:text-foreground focus-visible:ring-ring flex w-full items-center gap-2 rounded-(--composer-radius) px-2 py-1.5 text-xs transition-colors focus-visible:ring-2 focus-visible:outline-none"
      >
        <TargetIcon aria-hidden className="size-4 shrink-0" />
        <span
          data-slot="composer-goal-summary"
          // THE WHOLE OBJECTIVE IS A HOVER AWAY, and the fold is one click -- see the header.
          title={goal.objective}
          className="min-w-0 flex-1 truncate text-start"
        >
          {t(PHASE_KEY[goal.phase])} · {goal.objective}
        </span>
        {/* THE ARROW TURNS OVER RATHER THAN BEING SWAPPED: the control is the same one, and only
            which way it faces changed. */}
        <ChevronUpIcon
          aria-hidden
          className={
            open ? "size-4 shrink-0 rotate-180 transition-transform" : "size-4 shrink-0 transition-transform"
          }
        />
      </CollapsibleTrigger>
      <CollapsibleContent
        data-slot="composer-goal-objective"
        className="text-muted-foreground px-2 pb-1 text-xs whitespace-pre-wrap break-words"
      >
        {goal.objective}
      </CollapsibleContent>
      <p data-slot="composer-goal-rounds" className="text-muted-foreground px-2 pb-0.5 text-xs">
        {t("goal.rounds", { round: goal.rounds, max: goal["max-rounds"] })}
      </p>
      {goal.blocked !== undefined && (
        <p data-slot="composer-goal-blocked" className="text-destructive px-2 pb-0.5 text-xs">
          {t("goal.blocked", { code: goal.blocked.code, reason: goal.blocked.reason })}
        </p>
      )}
      <div className="flex items-center justify-between gap-2 px-2 pb-1.5">
        <span data-slot="composer-goal-armed" className="text-muted-foreground shrink-0 text-xs">
          {armed ? t("goal.armed.running") : t("goal.armed.stopped")}
        </span>
        {draft === null ? (
          <span data-slot="composer-goal-actions" className="flex items-center gap-1">
            {actionsFor(goal, armed).map((action) => (
              <Button
                key={action}
                type="button"
                variant="ghost"
                size="sm"
                data-slot="composer-goal-action"
                // THE VERB RIDES ON THE PRESS so a suite (and a walkthrough) can read which hands
                // a phase offers without knowing a class name.
                data-action={action}
                onClick={() => (action === "edit" ? setDraft(goal.objective) : onPress(action))}
              >
                {t(ACTION_KEY[action])}
              </Button>
            ))}
          </span>
        ) : (
          // THE EDIT IS IN PLACE, where the buttons were: changing the words is not a screen of
          // its own, and going elsewhere for it would lose the goal it is about.
          <span data-slot="composer-goal-edit" className="flex min-w-0 flex-1 items-center gap-1">
            <Textarea
              data-slot="composer-goal-draft"
              rows={2}
              value={draft}
              aria-label={t("goal.edit.label")}
              onChange={(event) => setDraft(event.target.value)}
            />
            <Button
              type="button"
              variant="ghost"
              size="sm"
              data-slot="composer-goal-action"
              data-action="save"
              onClick={() => {
                onPress("edit", { objective: draft });
                setDraft(null);
              }}
            >
              {t("goal.action.save")}
            </Button>
            <Button
              type="button"
              variant="ghost"
              size="sm"
              data-slot="composer-goal-action"
              data-action="cancel"
              onClick={() => setDraft(null)}
            >
              {t("goal.action.cancel")}
            </Button>
          </span>
        )}
      </div>
      {failure !== null && (
        <p role="alert" data-slot="composer-goal-error" className="text-destructive px-2 pb-1.5 text-xs">
          {failure}
        </p>
      )}
    </Collapsible>
  );
};

/// THE STRIP ABOVE THE COMPOSER, WIRED TO THE SESSION -- the snapshot read, the frame, the two
/// facts, the reconnect, the press, and the view above.
export const ComposerGoal: FC<{ threadId: string }> = ({ threadId }) => {
  const { t: tErrors } = useTranslation("errors");
  const [goal, setGoal] = useState<Goal | null>(null);
  const [armed, setArmed] = useState(false);
  const [failure, setFailure] = useState<string | null>(null);
  const [nonce, setNonce] = useState(0);
  // THE FOLD, held here rather than in the view because `/goal` alone arrives from outside it
  // (`onGoalShown`): the snapshot, the frame and the read verb all land on the same strip.
  const [open, setOpen] = useState(false);

  useEffect(() => {
    setGoal(null);
    setOpen(false);
    setArmed(false);
    setFailure(null);
  }, [threadId]);

  useEffect(() => {
    let live = true;
    void goalFor(threadId).then((answer) => {
      // A LATE ANSWER FROM A PREVIOUS SESSION MUST NOT LAND ON THIS ONE (`composer-todos.tsx`).
      if (!live) return;
      setGoal(answer.goal);
      setArmed(answer.armed);
    });
    return () => {
      live = false;
    };
  }, [threadId, nonce]);

  useEffect(() => {
    const { unsubscribe } = subscribeGoals(threadId, (frame) => {
      setGoal(frame.goal);
      setArmed(frame["armed?"]);
    });
    return unsubscribe;
  }, [threadId]);

  useEffect(() => {
    const { unsubscribe } = subscribeFacts(threadId, (fact) => {
      if (fact.type !== "model/start" && fact.type !== "turn/end") return;
      setNonce((n) => n + 1);
    });
    return unsubscribe;
  }, [threadId]);

  useEffect(() => onDownlinkOpen(() => setNonce((n) => n + 1)), []);

  // AND THE READ VERB, which is not a request at all: `/goal` alone is taken out of the send
  // (`lib/agent.ts`) and all the person gets is the strip, open.
  useEffect(() => onGoalShown(() => setOpen(true)), []);

  // AND WHAT IS ON SCREEN IS WHAT A `/goal …` TYPED INTO THE COMPOSER IS ABOUT: the agent that
  // folds such a command into a send cannot see this component, so the goal it has to fence
  // against is published where that seam can read it (`lib/goal.ts`'s `goalShown`). Null is
  // published too -- a session whose goal was cleared must not fence against a goal that is gone.
  useEffect(() => noteGoalShown(threadId, goal), [threadId, goal]);

  const press: GoalPress = (action, body) => {
    setFailure(null);
    void applyGoal(
      threadId,
      action,
      // THE FENCE COMES FROM THE GOAL ON SCREEN, never from the caller: a press is about the
      // revision the person is looking at, and a button that sent a ref it had just made up would
      // move a goal nobody had seen.
      { ...(goal === null ? {} : { goal_id: goal.id, revision: goal.revision }), ...body },
      tErrors,
    ).catch((error: unknown) => {
      // A REFUSAL IS SAID. On this door the command's own answer is where one arrives (a command
      // a RUN carried is refused into that run instead), and the server's own sentence goes up --
      // `harness.cap.goal` is the only rule, and a paraphrase would be a second one.
      setFailure(error instanceof Error ? error.message : String(error));
    });
  };

  return (
    <ComposerGoalView
      goal={goal}
      armed={armed}
      open={open}
      onOpenChange={setOpen}
      onPress={press}
      failure={failure}
    />
  );
};
