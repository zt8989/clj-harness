// The strip ABOVE the composer: this session's task list, folded to one line of counts
// and one click away from the detail.
//
// WHY IT EXISTS. The task list has a row in the store and no place on screen: `todo_write`
// writes it, `todo_read` hands it back to the model, and the only thing a PERSON could see
// was the card of the call itself -- which scrolls away with the conversation, so "what is
// the agent working on now" meant scrolling back. This is the reader
// `harness.cap.todos` writes its own docstring for. See
// `.scratch/composer-todo-strip/spec.md`.
//
// IT IS A CARD, NOT A LINE OF TEXT ON THE COMPOSER'S BACKDROP (owner, 2026-10-01). It
// carries the same rounded box the composer does -- `rounded-(--composer-radius)`,
// `border-border/60`, `bg-(--composer-bg)` -- so it reads as a thing of its own ABOVE the
// composer rather than as part of the grey frame around it, and a blank gap under it keeps
// the two apart. The one strip that is joined to the composer is the QUEUE of messages
// waiting to go (`components/composer-queue.tsx`), because that is what the composer is
// about to send.
//
// ONE VERB: LOOK. There is no check, no delete, no reorder here. The model is the list's
// only writer; the strip draws what it wrote and is not a second hand on it.
//
// WHEN IT ASKS -- the whole of "when", and why there is no timer. A snapshot on mount and
// whenever the session changes (the row is the server's, so a refresh draws the list
// before any run happens), and then the FACT family pushes it: `model/start` and
// `turn/end`. `todo_write` runs AFTER a model call ends and BEFORE the next one starts, so
// the next `model/start` is the first boundary a completed write is visible on -- asking on
// `model/end` would be one round late -- and `turn/end` is the last ask of a run, which
// catches a run that ends on the write or is stopped mid-way. NO POLLING: the rule is
// `docs/rules/panel-data.md` (a snapshot first, then the push), and `model/end`'s numbers
// aside, there is no clock anywhere in this file.
import { useEffect, useState } from "react";
import type { FC } from "react";
import type { TFunction } from "i18next";
import {
  BellIcon,
  BellOffIcon,
  BellRingIcon,
  ChevronUpIcon,
  CircleCheckIcon,
  CircleIcon,
  ListTodoIcon,
  LoaderCircleIcon,
} from "lucide-react";
import { useTranslation } from "react-i18next";

// THE TWO HANDS ON THE LIST ARE `Button`s, like the goal strip's -- one `remind` (push a
// reminder now) and one switch (`auto`, the harness pushing it by itself).
import { Button } from "@/components/ui/button";
import {
  Collapsible,
  CollapsibleContent,
  CollapsibleTrigger,
} from "@/components/ui/collapsible";
import { onDownlinkOpen, subscribeFacts, subscribeTodos } from "@/lib/mux";
import { applyTodo, todosFor, type TodoItem } from "@/lib/todos";

type Status = TodoItem["status"];

/// THE THREE STATES, in the order the folded line names them -- done, in progress, then
/// pending, which is the order the reference screenshot reads. A state with a count of
/// zero is left out of the line rather than drawn as a zero.
const STATUSES: readonly Status[] = ["completed", "in_progress", "pending"];

/// The state words for the ACCESSIBILITY tree, spelled as literal keys so
/// `src/i18next.d.ts` catches a rename. They are drawn `sr-only`: the screen shows three
/// shapes, and a reader of the tree hears the words.
const STATE_KEY = {
  completed: "todos.state.completed",
  in_progress: "todos.state.inProgress",
  pending: "todos.state.pending",
} as const;

/// The counted words for the folded line, in `STATUSES`' own order. Literal keys for the
/// same reason as `STATE_KEY`: a catalog rename is `npm run typecheck`'s to catch, not a
/// blank line on screen.
const COUNT_KEY = {
  completed: "todos.done",
  in_progress: "todos.inProgress",
  pending: "todos.pending",
} as const;

/// ONE STATUS, ONE SHAPE, and the only thing in the detail that moves. The spinner is the
/// honest drawing of "being done now"; `motion-reduce` stops it for a reader who asked
/// motion to stop, and the `sr-only` word below it still says the state, so nothing is lost
/// by stopping.
function StatusIcon({ status }: { status: Status }) {
  if (status === "in_progress") {
    return (
      <LoaderCircleIcon
        data-slot="composer-todos-icon"
        aria-hidden
        className="size-4 shrink-0 animate-spin motion-reduce:animate-none"
      />
    );
  }
  if (status === "completed") {
    return <CircleCheckIcon data-slot="composer-todos-icon" aria-hidden className="size-4 shrink-0" />;
  }
  return <CircleIcon data-slot="composer-todos-icon" aria-hidden className="size-4 shrink-0" />;
}

/// THE FOLDED LINE, as words: only the states that have items, in `STATUSES`' order.
function countsLine(t: TFunction<"composer">, todos: readonly TodoItem[]): string {
  return STATUSES.map((status) => ({
    status,
    count: todos.filter((todo) => todo.status === status).length,
  }))
    .filter(({ count }) => count > 0)
    .map(({ status, count }) => t(COUNT_KEY[status], { count }))
    .join(" · ");
}

/// THE DETAIL: one row per item, in the order the model wrote them. A status WORD is not
/// drawn here -- the icon is the status -- but it is handed to the accessibility tree, so a
/// reader hears "in progress" rather than an anonymous spinning circle. The model's own
/// text is drawn as written: it is the model's words, and this strip does not translate
/// them.
export const TodoRows: FC<{ todos: readonly TodoItem[] }> = ({ todos }) => {
  const { t } = useTranslation("composer");
  return (
    <>
      {todos.map((todo, index) => (
        <div
          key={index}
          data-slot="composer-todos-item"
          data-status={todo.status}
          // `px-2` LINES THE ROWS UP WITH THE TRIGGER'S TEXT above them, which is `px-2`
          // too -- the box around this list is the strip's own card now (owner, 2026-10-01).
          className="flex items-start gap-2 px-2 py-0.5 text-xs"
        >
          <StatusIcon status={todo.status} />
          <span className="min-w-0 flex-1 whitespace-pre-wrap break-words">{todo.content}</span>
          <span className="sr-only">{t(STATE_KEY[todo.status])}</span>
        </div>
      ))}
    </>
  );
};

/// THE STRIP WITH DATA IN HAND -- split from the fetch so it can be rendered to a string
/// by the suites (`react-dom/server`), which is where the words and the shapes are pinned.
///
/// `null` (no answer yet) and an empty list draw NOTHING, the same absence: a folded line
/// that says "0 pending" is furniture for something that is not there.
/// THE TWO HANDS A PERSON HAS ON THE LIST (`harness.cap.todos`), drawn on the card:
///
///   * `remind` -- push a reminder at the model NOW. It is DISABLED when every item is done,
///     because the server refuses that press by name (`:nothing-to-remind`) and a button that
///     can only fail should say so before it is pressed.
///   * `auto` -- the switch the round driver obeys: on, a finished round that left work opens
///     the next one carrying the reminder; off, nothing happens until somebody presses
///     `remind`. The state is drawn from the server and never inferred -- it is THIS PROCESS's
///     memory, and a restart turns it off without telling anybody.
///
/// OPTIONAL ON PURPOSE: this view's own suite renders it with nothing but a list, and a hand
/// that was never wired is a hand that is not drawn.
export const ComposerTodosView: FC<{
  todos: readonly TodoItem[] | null;
  auto?: boolean;
  failure?: string | null;
  onRemind?: () => void;
  onToggleAuto?: () => void;
}> = ({ todos, auto = false, failure = null, onRemind, onToggleAuto }) => {
  const { t } = useTranslation("composer");
  // FOLDED IS THE DEFAULT and is not remembered: refresh brings it back folded, the same
  // "transient layout preference" rule the right pane's toggle follows.
  const [open, setOpen] = useState(false);

  if (todos === null || todos.length === 0) return null;
  /// IS THERE ANYTHING LEFT FOR A REMINDER TO BE ABOUT? The nudge's disabled state -- the same
  /// question the server asks before it refuses the press (`:nothing-to-remind`).
  const unfinished = todos.some((todo) => todo.status !== "completed");

  return (
    <Collapsible
      data-slot="composer-todos"
      open={open}
      onOpenChange={setOpen}
      // A ROUNDED BOX OF ITS OWN, in the composer's own material -- so it reads as a card
      // sitting above the composer rather than as a line of text on the grey backdrop behind
      // it (owner, 2026-10-01). `mb-1.5` is the BLANK GAP under it, the same 6px the error
      // card above uses and the same the frame pads itself with; the strip therefore never
      // sits flush against the composer's box.
      className="border-border/60 bg-(--composer-bg) mb-1.5 flex flex-col rounded-(--composer-radius) border"
    >
      <CollapsibleTrigger
        data-slot="composer-todos-toggle"
        className="text-muted-foreground hover:text-foreground focus-visible:ring-ring flex w-full items-center gap-2 rounded-(--composer-radius) px-2 py-1.5 text-xs transition-colors focus-visible:ring-2 focus-visible:outline-none"
      >
        <ListTodoIcon aria-hidden className="size-4 shrink-0" />
        <span data-slot="composer-todos-summary" className="min-w-0 flex-1 text-start">
          {countsLine(t, todos)}
        </span>
        {/* THE ARROW TURNS OVER RATHER THAN BEING SWAPPED: the point is that the control is
            the same one, and only which way it faces changed. */}
        <ChevronUpIcon
          aria-hidden
          className={
            open
              ? "size-4 shrink-0 rotate-180 transition-transform"
              : "size-4 shrink-0 transition-transform"
          }
        />
      </CollapsibleTrigger>
      {/* THE DETAIL SCROLLS ITSELF. It sits inside the composer's own box, and a long list
          that pushed the input down the screen would be the strip taking over the page. */}
      <CollapsibleContent data-slot="composer-todos-list" className="max-h-40 overflow-y-auto py-0.5">
        <TodoRows todos={todos} />
      </CollapsibleContent>
      {/* THE TWO HANDS ARE ALWAYS DRAWN -- the fold hides the LIST, not the controls: asking
          the harness to push a reminder is not a detail of what the list says. `px-2` lines
          them up with the rows above, `justify-end` keeps them off the trigger's text.
          THE NUDGE'S DISABLED STATE IS THE SERVER'S RULE, said before the press: a list every
          item of which is done has nothing to be reminded about (`:nothing-to-remind`). */}
      <div className="flex items-center justify-end gap-1 px-2 pb-1.5">
        <Button
          type="button"
          variant="ghost"
          size="sm"
          data-slot="composer-todos-remind"
          title={unfinished ? undefined : t("todos.remind.none")}
          disabled={!unfinished}
          onClick={onRemind}
        >
          <BellRingIcon aria-hidden className="size-3.5" />
          {/* ICON ONLY ON A PHONE (`.scratch/todo-strip-mobile`): two labelled buttons are wide
              enough to push the counts into a second row on a narrow screen, so the word is
              hidden there and comes back from `sm` up. `sr-only` KEEPS IT AS THE BUTTON'S
              NAME -- a screen reader still hears `提醒一下` -- and the `title` above is the
              tooltip a pointer gets. */}
          <span data-slot="composer-todos-remind-word" className="sr-only sm:not-sr-only">
            {t("todos.remind.now")}
          </span>
        </Button>
        {/* THE SWITCH SAYS ITS OWN STATE: the icon, the on/off word and `aria-pressed` are
            three readings of one boolean, and none of them is inferred from the list. */}
        <Button
          type="button"
          variant="ghost"
          size="sm"
          data-slot="composer-todos-auto"
          data-on={auto ? "true" : "false"}
          aria-pressed={auto}
          onClick={onToggleAuto}
        >
          {auto ? (
            <BellIcon aria-hidden className="size-3.5" />
          ) : (
            <BellOffIcon aria-hidden className="size-3.5" />
          )}
          {/* ICON ONLY ON A PHONE, the nudge's rule again -- and here the icon is what says
              the state (a bell against a bell with a slash), with the word, the `title` and
              `aria-pressed` all saying it too wherever there is room. */}
          <span data-slot="composer-todos-auto-word" className="sr-only sm:not-sr-only">
            {t("todos.auto.label")} · {auto ? t("todos.auto.on") : t("todos.auto.off")}
          </span>
        </Button>
      </div>
      {failure !== null && (
        <p role="alert" data-slot="composer-todos-error" className="text-destructive px-2 pb-1.5 text-xs">
          {failure}
        </p>
      )}
    </Collapsible>
  );
};

/// THE STRIP ABOVE THE COMPOSER, WIRED TO THE SESSION -- the fetch, the two effects that
/// say when to repeat it, and the view above.
///
/// A SESSION CHANGE DROPS THE OLD ANSWER rather than leaving it up while the new one is in
/// flight: `live` refuses an answer a later session superseded, and the reset refuses a
/// stale DRAW -- the two halves of the mistake `composer-numbers.tsx` names with its own
/// flag.
export const ComposerTodos: FC<{ threadId: string }> = ({ threadId }) => {
  const { t: tErrors } = useTranslation("errors");
  const [todos, setTodos] = useState<TodoItem[] | null>(null);
  const [auto, setAuto] = useState(false);
  const [failure, setFailure] = useState<string | null>(null);
  const [nonce, setNonce] = useState(0);

  useEffect(() => {
    setTodos(null);
    setAuto(false);
    setFailure(null);
  }, [threadId]);

  useEffect(() => {
    let live = true;
    void todosFor(threadId).then((next) => {
      if (!live) return;
      setTodos(next.todos);
      setAuto(next.auto);
    });
    return () => {
      live = false;
    };
  }, [threadId, nonce]);

  useEffect(() => {
    const { unsubscribe } = subscribeFacts(threadId, (fact) => {
      if (fact.type !== "model/start" && fact.type !== "turn/end") return;
      setNonce((n) => n + 1);
    });
    return unsubscribe;
  }, [threadId]);

  // AND THE PUSHED FRAME (`harness.edge.http/todos-send!`): a press in ANOTHER page moving this
  // process's switch is the one change no run brackets, so it arrives here or nowhere.
  useEffect(() => {
    const { unsubscribe } = subscribeTodos(threadId, (frame) => {
      setTodos(frame.todos);
      setAuto(frame["auto?"]);
    });
    return unsubscribe;
  }, [threadId]);

  // AND A RECONNECT RE-READS: a `todos` frame carries no cursor, so a change that happened while
  // the socket was down is in no frame this page will be handed (`docs/rules/panel-data.md`).
  useEffect(() => onDownlinkOpen(() => setNonce((n) => n + 1)), []);

  /// ONE PRESS, TWO HANDS: both ride a `todo` command on a run request (`lib/todos.ts`), and
  /// a refusal is the SERVER's own sentence -- drawn in the card rather than swallowed, because
  /// a press that failed in silence is the one thing a button must never do.
  const press = (action: "remind" | "auto", on?: boolean) => {
    setFailure(null);
    void applyTodo(threadId, action, on === undefined ? {} : { on }, tErrors)
      // A SUCCESSFUL PRESS RE-READS THE ROUTE: `auto` moved this process's switch, and the
      // next fact would take a whole model call to say so.
      .then(() => setNonce((n) => n + 1))
      .catch((error: unknown) => setFailure(error instanceof Error ? error.message : String(error)));
  };
  return (
    <ComposerTodosView
      todos={todos}
      auto={auto}
      failure={failure}
      onRemind={() => press("remind")}
      onToggleAuto={() => press("auto", !auto)}
    />
  );
};

