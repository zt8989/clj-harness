// `GET/POST /api/subagents`: the subagents this home has, typed thin.
//
// ---------------------------------------------------------------- one answer
//
// One call answers BOTH screens -- the sidebar's panel and the settings page --
// and that is the server's arrangement rather than a saving here: the definitions
// come from harness.edn and the run records come from the store plus the server's
// own live table, and only the side that owns those files can join them. A client
// that read the file itself would need to know where a home keeps its
// configuration, which is not a thing a page should learn.
//
// ------------------------------------------------- what the server's words are
//
// The server's refusal is shown VERBATIM. Every non-OK answer from these routes
// carries `{error}` -- the definition checks live in one place on the server, and
// their sentences name the thing that was wrong ("the baseline of \"x\" must be
// :all or :read-only, but it says \"read/write\""), which is far more use than a
// paraphrase that has to guess at the same fact a second time.
//
// This side speaks only when there is nothing to pass through -- a proxy's 502, a
// route that answered empty -- and then it is this interface's own sentence in its
// own language (`errors.http.*`), because `HTTP 500` is a fact about the wire.
import type { TFunction } from "i18next";

import { API_BASE } from "@/lib/threads";

/// The translator a FAILURE is worded through, PINNED TO THE `errors` FACE. Same
/// rule as `lib/projects.ts`: i18next brands a translator with its namespace, so a
/// call site handing over a `settings` translator will not typecheck.
type Translate = TFunction<"errors">;

/// How a definition starts its range. TWO VALUES, and the vocabulary is the
/// SERVER's (`:all` / `:read-only`, arriving as JSON strings) -- the words a person
/// reads are the screens', and each screen writes its own literal key for each of
/// the two (`baselineLabel`).
export type Baseline = "all" | "read-only";

/// One subagent, as the panel and the form both read it.
export type SubagentDefinition = {
  name: string;
  description: string;
  baseline: Baseline;
  /// Tool names taken OUT of whatever the baseline serves. The forbidden two can
  /// never appear here: the server refuses an entry that names them, because a
  /// range is a baseline plus exclusions and there is no key that puts a tool back.
  exclude: readonly string[];
  /// Whether the CODE supplies this name. It is what the two screens decide their
  /// buttons from -- a built-in is editable and not deletable -- and it is the
  /// server's answer rather than a list of two names re-derived here, because a
  /// second answer to that question is a second thing to keep in step.
  builtin: boolean;
};

/// One delegation this home has a record of.
export type SubagentRun = {
  threadId: string;
  /// The session whose model delegated. A delegation is one level deep, so this is
  /// always an ordinary conversation rather than another subagent.
  parent: string;
  subagent: string;
  /// The project the subagent inherited from its parent, or null.
  project: string | null;
  /// When the delegation opened its session, in epoch milliseconds.
  delegatedAt: number | null;
  /// RUNNING NOW, which is the server's process-local table and not a column: a
  /// restart answers false for everything, exactly as it does for parked calls. A
  /// delegation that finished and one from a previous process read the same,
  /// because that is the same fact about now.
  running: boolean;
};

/// The whole answer: what the subagents are, and what has been delegated.
export type SubagentListing = {
  subagents: readonly SubagentDefinition[];
  /// The first thing in harness.edn's :subagents block that could not be honoured,
  /// or null. THE PRESENCE OF THIS IS NOT A FAILURE: a home with a typo in that
  /// block still runs, and the sentence is here so a screen can say which file to
  /// open. A file that cannot be parsed at all is the other case and arrives as a
  /// refusal instead.
  problem: string | null;
  /// The user-level harness.edn these definitions come from (or would be written
  /// to), so a save can say where it landed.
  path: string | null;
  runs: readonly SubagentRun[];
};

async function reasonFrom(res: Response, fallback: string): Promise<string> {
  const body: unknown = await res.json().catch(() => undefined);
  return body !== undefined &&
    typeof body === "object" &&
    body !== null &&
    "error" in body &&
    typeof body.error === "string"
    ? body.error
    : fallback;
}

export async function listSubagents(t: Translate): Promise<SubagentListing> {
  const res = await fetch(`${API_BASE}subagents`);
  if (!res.ok) {
    throw new Error(await reasonFrom(res, t("http.listingSubagents", { status: res.status })));
  }
  return res.json();
}

/// The row a form sends. `baseline` is the keyword's spelling as JSON has it, which
/// is what the server expects on this side of the wire.
export type SubagentDraft = {
  name: string;
  description: string;
  baseline: Baseline;
  exclude: readonly string[];
};

/// Create or replace ONE definition in the home's harness.edn.
///
/// `replace` IS THE REQUEST'S OWN STATEMENT ABOUT WHICH SCREEN SENT IT, and it is
/// not decoration: the edit view is changing a row it is showing, and the
/// new-subagent view must not silently rewrite a built-in because somebody typed
/// its name into a field that said "new". False plus a taken name is a refusal,
/// with a sentence naming the subagent that already has it.
///
/// A THROWN ERROR IS ALSO THE PROOF THAT NOTHING WAS WRITTEN: the server validates
/// the whole block before it opens the file, so this side can keep the form open
/// and say so.
export async function putSubagent(
  draft: SubagentDraft,
  replace: boolean,
  t: Translate,
): Promise<SubagentDefinition> {
  const res = await fetch(`${API_BASE}subagents`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ ...draft, replace }),
  });
  if (!res.ok) throw new Error(await reasonFrom(res, t("http.status", { status: res.status })));
  return res.json();
}

/// Take one definition out of the home's harness.edn. A built-in is refused by name
/// (there is nothing in the file to remove), and a name this home does not have is
/// refused too -- guessing at which subagent a stale row meant would delete the
/// wrong one.
///
/// The name rides in the PATH, as ONE encoded segment: a subagent name is an
/// ordinary word today, and a route that only worked for words without spaces would
/// be a trap waiting for the first one that had one.
export async function removeSubagent(name: string, t: Translate): Promise<SubagentDefinition> {
  const res = await fetch(`${API_BASE}subagents/${encodeURIComponent(name)}/remove`, {
    method: "POST",
  });
  if (!res.ok) throw new Error(await reasonFrom(res, t("http.status", { status: res.status })));
  return res.json();
}
