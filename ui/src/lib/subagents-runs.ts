// THE DELEGATIONS THIS SESSION MADE, as the task pane's top section draws them.
//
// THE ROUTE IS `GET /api/subagents`, and it is ALREADY THE WHOLE ANSWER: its `runs` say
// which home delegated to whom and whether it is still going, and its `subagents` are the
// definitions a run names. This ticket adds no endpoint (`.scratch/right-pane-tasks`,
// decision 4), so this module is a JOIN and a NARROWING and nothing else: `:parent` keeps
// the rows of the session the pane is showing, and the definition with the same name
// supplies the description.
//
// A RUN WHOSE DEFINITION IS GONE IS AN ORDINARY ROW, not a failure to catch: the subagent
// was deleted after it was delegated to, `cap.subagents/runs`'s docstring is written for
// exactly that case ("a record whose definition was since deleted"), and the row draws its
// name and its status and no description.
//
// WHY NOT `lib/subagents.ts`'s `listSubagents`. That reader serves the settings screens: it
// takes a translator because a non-OK answer is a SENTENCE somebody reads, and it throws.
// This pane has nobody to word a failure for, and its caller polls once a second while the
// pane is open -- so it needs the OTHER discipline, `lib/jobs.ts`'s: a read that could not
// be made is NULL ("keep what you had"), an abort is not a failure, and a `signal` is
// honoured. The two disagreements are the failure discipline and the signal, and folding
// either into the settings reader would leave that screen deciding when this pane keeps its
// rows. THE TYPES ARE NOT DUPLICATED: `SubagentListing` comes from `lib/subagents.ts`, and
// the URL below is the same route that reader reads.
import { API_BASE } from "@/lib/threads";
import type { SubagentListing } from "@/lib/subagents";

/// One delegation, as the pane's top section draws it: WHO (the subagent's name), WHAT IT
/// IS FOR (the definition's description, or null once that definition is gone), WHETHER IT
/// IS STILL GOING (the server's one boolean), and WHEN IT STARTED (`delegatedAt`, or null).
export type SubagentTaskRow = {
  /// THE ROW'S OWN ID -- the child conversation, and the thing the row opens. NEVER PAIRED
  /// BY POSITION: two delegations can be in flight at once, and a list that matched a row
  /// to a card by index would open the wrong one the moment either moved (the same rule
  /// `toolCallId` keeps on the transcript's `agent` card).
  threadId: string;
  name: string;
  /// The definition's own sentence, or null when no definition carries this name any more.
  description: string | null;
  /// When the delegation opened its session, in epoch milliseconds, or null.
  delegatedAt: number | null;
  /// RUNNING NOW. The server's process-local boolean, so a reload answers false for a
  /// delegation another process is still working on -- the honest answer, and the two words
  /// the row has are this boolean's.
  running: boolean;
};

/// THE ROWS THIS SESSION MADE, IN THE ORDER THE SERVER LISTED THEM -- newest first, which
/// is `cap.subagents/runs`'s own sort and not a second one invented here.
export function subagentRowsOf(listing: SubagentListing, parent: string): SubagentTaskRow[] {
  const definitions = new Map(listing.subagents.map((definition) => [definition.name, definition]));
  return listing.runs
    .filter((run) => run.parent === parent)
    .map((run) => ({
      threadId: run.threadId,
      name: run.subagent,
      description: definitions.get(run.subagent)?.description ?? null,
      delegatedAt: run.delegatedAt,
      running: run.running,
    }));
}

/// WHICH MIRROR A ROW OPENS: its OWN delegation, named by its OWN `threadId` and by the
/// subagent whose header the mirror draws. A function rather than a literal at the click
/// site so the one thing that must never be read off a position has one place to be read
/// from -- and so a suite can ask two concurrent rows which door each of them is.
export function mirrorOf(row: SubagentTaskRow): { threadId: string; subagent: string } {
  return { threadId: row.threadId, subagent: row.name };
}

/// THIS SESSION's delegations, or null when the question could not be asked.
///
/// A NON-OK ANSWER IS NULL AND NOT `[]`, exactly as in `lib/jobs.ts`: `[]` is "this session
/// has delegated to nobody", while a 500 or a dropped connection is "we do not know", and
/// drawing one as the other would either invent an empty section or keep rows alive that
/// are gone.
export async function subagentsFor(
  parent: string,
  signal?: AbortSignal,
): Promise<SubagentTaskRow[] | null> {
  try {
    const res = await fetch(`${API_BASE}subagents`, { signal });
    if (!res.ok) return null;
    const answer = (await res.json()) as SubagentListing;
    return subagentRowsOf(answer, parent);
  } catch {
    // AN ABORT IS NOT A FAILURE -- it is this reader's own pane going away. Either way the
    // caller gets null, and null means "keep what you had".
    return null;
  }
}
