// This session's task list, typed thin: what the model last wrote with `todo_write`,
// read back from the STORE rather than from the conversation -- plus the two hands a person
// has on it (`harness.cap.todos`'s reminder).
//
// WHY THE ROW AND NOT THE MESSAGES. The `todo_write` call that produced the list is
// folded away by a compaction, and a card that scrolls out of view answers nothing about
// "what is left to do now" -- but the row outlives the run that wrote it, so the composer
// can ask for it at any moment (harness.cap.todos says the same from the server's side,
// and `GET /api/threads/<stem>/todos` is that row's second reader). See
// `.scratch/composer-todo-strip/spec.md`.
//
// AND THE REMINDER IS THE SECOND HALF OF THE ANSWER (`.scratch/todo-reminder/spec.md`): the
// strip can ASK the harness to push a `<system-reminder>` at the model (`remind`), and it holds
// the SWITCH that does it by itself after a finished round (`auto`). Both are WRITES, and a
// write is a command on a run request -- never a route of its own (`applyGoal`'s rule, and
// `harness.edge.http/run-todo-command!` is the one door both hands go through).
//
// AND A FAILURE IS AN EMPTY LIST, not an exception. There is no log to 404 on -- a session
// that never wrote one answers [] -- and a server that cannot be reached is not something
// this strip has words for either; drawing nothing is the honest answer to both. The same
// shape, and the same reasoning, as `lib/stats.ts`'s `statsFor`.
import type { TFunction } from "i18next";

// `apiBase()` RATHER THAN THE `API_BASE` CONST, for the reason `lib/goal.ts` states: a suite
// learns the harness's origin at runtime, after this module is loaded, and this module is one a
// suite drives through the app's own code.
import { apiBase, refusalFrom } from "@/lib/threads";

/// One item as the route answers it: the model's own words, and one of the three states
/// `harness.cap.todos/statuses` holds.
export type TodoItem = {
  content: string;
  status: "pending" | "in_progress" | "completed";
};

/// The route's whole answer (`harness.edge.http/todos-wire`): the list, and the switch that
/// rides BESIDE it rather than inside it.
export type TodosAnswer = {
  threadId: string;
  todos: TodoItem[];
  /// WHETHER THIS PROCESS IS AUTO-REMINDING THE SESSION (`harness.cap.todos/auto?`). It is not
  /// part of an item for the reason the goal's `armed?` is not part of a goal: the list is the
  /// STORE's and the switch is THIS PROCESS's, so a client that merged the two would draw a
  /// switch as off after a restart nobody touched.
  "auto?": boolean;
};

/// WHAT THE STRIP DRAWS: the list, and whether the harness is pushing it by itself.
export type TodosState = { todos: TodoItem[]; auto: boolean };

/// The absence of both, as one value: what a failed read answers, and what a session that
/// never wrote a list answers.
const NOTHING: TodosState = { todos: [], auto: false };

/// THIS SESSION'S TASK LIST AND ITS REMINDER SWITCH, or the empty answer when there is none
/// to read (`harness.edge.http/todos-wire`).
///
/// It never throws and it never 404s: "never wrote one" and "wrote an empty list" are the
/// same answer from the server (`harness.cap.todos/items-for` collapses them), and this
/// side only has to say the same thing.
export async function todosFor(threadId: string): Promise<TodosState> {
  try {
    const res = await fetch(`${apiBase()}threads/${encodeURIComponent(threadId)}/todos`);
    if (!res.ok) return NOTHING;
    const body = (await res.json()) as Partial<TodosAnswer>;
    return {
      todos: Array.isArray(body.todos) ? body.todos : [],
      auto: body["auto?"] === true,
    };
  } catch {
    // A server that is not there is not this strip's to word, and it is the same absence as a
    // session that never wrote a list.
    return NOTHING;
  }
}

/// ONE HAND A PERSON HAS ON THE LIST: `remind` pushes a reminder at the model now, and `auto`
/// is the switch the round driver obeys.
export type TodoAction = "remind" | "auto";

/// A todo command as it rides a run request's `commands` (`harness.edge.commands`). The names
/// are the WIRE's -- `on` included, because the server reads it by keyword -- so this type is
/// the client's copy of that contract, not a local shape.
export type TodoCommand = {
  type: "todo";
  action: TodoAction;
  on?: boolean;
};

/// What the edge answers about one command: carried out, or refused with the capability's OWN
/// sentence (`harness.edge.http/run-todo-command!` is the only rule a command and a person
/// share, so a refusal is passed through here rather than re-worded).
export type TodoVerdict =
  | { type: "todo"; ok: true }
  | { type: "todo"; reason: string; error: string };

/// THE WRITE DOOR, for both hands on the strip. It packages the command into a run request --
/// `append` empty, `commands` carrying it -- and posts that to the agent route, which is the
/// only route a command has (there is no `POST /api/todos`; spec decision 2).
///
/// A REFUSAL IS THROWN, not returned: the answer to a request that carried ONLY commands is
/// the one place this door can be told no -- a command a RUN carried is refused into that run
/// instead (`harness.edge.http/refusal-note`) -- and a press that failed in silence is the one
/// thing a button must never do.
export async function applyTodo(
  threadId: string,
  action: TodoAction,
  body: { on?: boolean },
  t: TFunction<"errors">,
): Promise<void> {
  const command: TodoCommand = { type: "todo", action, ...body };
  const res = await fetch(`${apiBase()}agent`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ threadId, append: [], commands: [command] }),
  });
  if (!res.ok) throw new Error(await refusalFrom(res, t));
  const answer = (await res.json()) as { commands?: TodoVerdict[] };
  // A REFUSED VERDICT IS THE ONE THAT CARRIES A SENTENCE; an `ok: true` one has none to read.
  const refused = answer.commands?.find((verdict) => "error" in verdict);
  if (refused !== undefined && "error" in refused) throw new Error(refused.error);
}
