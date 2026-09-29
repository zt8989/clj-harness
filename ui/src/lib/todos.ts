// This session's task list, typed thin: what the model last wrote with `todo_write`,
// read back from the STORE rather than from the conversation.
//
// WHY THE ROW AND NOT THE MESSAGES. The `todo_write` call that produced the list is
// folded away by a compaction, and a card that scrolls out of view answers nothing about
// "what is left to do now" -- but the row outlives the run that wrote it, so the composer
// can ask for it at any moment (harness.cap.todos says the same from the server's side,
// and `GET /api/threads/<stem>/todos` is that row's second reader). See
// `.scratch/composer-todo-strip/spec.md`.
//
// AND A FAILURE IS AN EMPTY LIST, not an exception. There is no log to 404 on -- a session
// that never wrote one answers [] -- and a server that cannot be reached is not something
// this strip has words for either; drawing nothing is the honest answer to both. The same
// shape, and the same reasoning, as `lib/stats.ts`'s `statsFor`.
import { API_BASE } from "@/lib/threads";

/// One item as the route answers it: the model's own words, and one of the three states
/// `harness.cap.todos/statuses` holds.
export type TodoItem = {
  content: string;
  status: "pending" | "in_progress" | "completed";
};

/// The route's whole answer.
type TodosAnswer = { threadId: string; todos: TodoItem[] };

/// THIS SESSION'S TASK LIST, or [] when there is none to read.
///
/// It never throws and it never 404s: "never wrote one" and "wrote an empty list" are the
/// same answer from the server (`harness.cap.todos/items-for` collapses them), and this
/// side only has to say the same thing.
export async function todosFor(threadId: string): Promise<TodoItem[]> {
  const res = await fetch(`${API_BASE}threads/${encodeURIComponent(threadId)}/todos`);
  if (!res.ok) return [];
  const body = (await res.json()) as TodosAnswer;
  return Array.isArray(body.todos) ? body.todos : [];
}
