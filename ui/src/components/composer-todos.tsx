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
  ChevronUpIcon,
  CircleCheckIcon,
  CircleIcon,
  ListTodoIcon,
  LoaderCircleIcon,
} from "lucide-react";
import { useTranslation } from "react-i18next";

import {
  Collapsible,
  CollapsibleContent,
  CollapsibleTrigger,
} from "@/components/ui/collapsible";
import { subscribeFacts } from "@/lib/mux";
import { todosFor, type TodoItem } from "@/lib/todos";

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
          className="flex items-start gap-2 px-1.5 py-0.5 text-xs"
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
export const ComposerTodosView: FC<{ todos: readonly TodoItem[] | null }> = ({ todos }) => {
  const { t } = useTranslation("composer");
  // FOLDED IS THE DEFAULT and is not remembered: refresh brings it back folded, the same
  // "transient layout preference" rule the right pane's toggle follows.
  const [open, setOpen] = useState(false);

  if (todos === null || todos.length === 0) return null;

  return (
    <Collapsible
      data-slot="composer-todos"
      open={open}
      onOpenChange={setOpen}
      className="flex flex-col"
    >
      <CollapsibleTrigger
        data-slot="composer-todos-toggle"
        className="text-muted-foreground hover:text-foreground focus-visible:ring-ring flex w-full items-center gap-2 rounded-(--composer-radius) px-1.5 py-0.5 text-xs transition-colors focus-visible:ring-2 focus-visible:outline-none"
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
  const [todos, setTodos] = useState<TodoItem[] | null>(null);
  const [nonce, setNonce] = useState(0);

  useEffect(() => {
    setTodos(null);
  }, [threadId]);

  useEffect(() => {
    let live = true;
    void todosFor(threadId).then((next) => {
      if (live) setTodos(next);
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

  return <ComposerTodosView todos={todos} />;
};

