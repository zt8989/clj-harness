// GET and POST /api/session: config.edn's `:session`, read and written as GROUPS.
//
// A GROUP IS A BUNDLE OF SESSION SETTINGS AND THE MODELS IT SERVES. The DEFAULT group
// is config.edn's own top-level :session keys, and it serves EVERY model; each entry of
// `:groups` names the models it serves and lays its own blocks over the default group's,
// key by key (harness.cap.providers/merge-session-blocks). Which group a SESSION gets is
// therefore a function of the model it runs on, resolved on the server from the ordinary
// tiers -- nothing here decides it, and nothing here caches it.
//
// EVERYTHING IS SHOWN AS WRITTEN. The form edits the file, so a `~` in a path and a block
// the default group does not carry have to survive the round trip; the resolved answer
// (which group a given session actually gets) is a different question, and the server
// keeps it. `path` rides along so a save can say where it landed.
import type { TFunction } from "i18next";

import { API_BASE } from "@/lib/threads";

/// The translator a failure is worded through, PINNED TO THE `errors` FACE -- the rule
/// every route pair in this app keeps: the server's own sentence is preferred, and this
/// is only for a failure with no readable body.
type Translate = TFunction<"errors">;

/// `:editing` -- which file-editing implementation a session gets and how it treats the
/// slips an edit's payload can carry. THE WHOLE SHAPE, because a group that overrides it
/// merges key by key with the default group's.
export type EditingBlock = {
  mode: "hashline" | "str-replace";
  grep: boolean;
  "require-path": boolean;
  "strict-input": boolean;
  "diff-context-lines": number;
};

/// `:compaction` -- the ratios a run compacts at, the retry bound after a vendor refuses a
/// request for length, and the output cap on the summary call. All four are
/// `harness.edge.compaction`'s rules; the server refuses a retain at or above the
/// threshold, so the shape here is loose.
export type CompactionBlock = {
  "threshold-ratio": number;
  "retain-ratio": number;
  "overflow-retries": number;
  "max-tokens": number;
};

/// `:llm` -- the idle guard: how long a silent model call is allowed to sit, and how many
/// times a call that produced nothing is retried.
export type LlmBlock = {
  "idle-timeout-ms": number;
  "idle-timeout-retries": number;
};

/// `:approval` -- the project fence's extra free paths and its strict switch.
export type ApprovalBlock = {
  allow: readonly string[];
  strict: boolean;
};

/// `:skills` -- the roots a session reads skill directories from.
export type SkillsBlock = {
  roots: readonly string[];
};

/// `:instructions` -- the instruction files a session reads.
export type InstructionsBlock = {
  files: readonly string[];
};

/// `:tools` -- how the tools a session is served behave. One knob today: the longest a
/// `bash` call may wait, in milliseconds. A CEILING rather than a default -- a call asking
/// for more than it is REFUSED rather than shortened -- and the server refuses a value
/// above the harness's own 600000ms, so this knob can only lower it.
export type ToolsBlock = {
  "bash-max-timeout-ms": number;
};

/// The seven blocks a group may carry. A block that is ABSENT is not "empty": it is the
/// default group's (for a group) or the harness's built-in default (for the default
/// group), and the form draws that difference rather than flattening it.
export type SessionBlocks = {
  editing?: EditingBlock;
  compaction?: CompactionBlock;
  llm?: LlmBlock;
  approval?: ApprovalBlock;
  skills?: SkillsBlock;
  instructions?: InstructionsBlock;
  tools?: ToolsBlock;
};

/// One model a group serves. `provider` is optional on purpose: leaving it out says
/// "this id, from whoever serves it".
export type ModelRef = {
  provider?: string;
  model: string;
};

/// One per-model group: a name, the models it serves, and any of the seven blocks it lays
/// over the default group.
export type SessionGroup = {
  name: string;
  models: readonly ModelRef[];
} & SessionBlocks;

/// What `GET /api/session` answers, and what every write answers with too.
export type SessionConfig = {
  /// The default group -- config.edn's own top-level :session blocks.
  default: SessionBlocks;
  /// The per-model overrides, in file order (a later one wins a key an earlier also named).
  groups: readonly SessionGroup[];
  /// config.edn's absolute path -- where a save landed.
  path: string;
};

/// The default group's patch: a key that is PRESENT is set to its value, or REMOVED when
/// its value is `null`; a key that is absent is left exactly as it was. The same
/// absent-vs-null line POST /api/defaults draws.
export type SessionBlocksPatch = {
  [K in keyof SessionBlocks]?: SessionBlocks[K] | null;
};

/// What a save submits. Either half may be left out, and a half that is absent is left
/// alone: the form that changed one group does not restate the default group.
export type SessionChange = {
  default?: SessionBlocksPatch;
  /// Replaces the whole list; `[]` removes the groups.
  groups?: readonly SessionGroup[];
};

/// The server's own sentence when a route refused, or a sentence about the status when
/// the body carried none.
function reasonFrom(body: unknown, status: number, t: Translate): string {
  return body !== undefined &&
    typeof body === "object" &&
    body !== null &&
    "error" in body &&
    typeof (body as { error?: unknown }).error === "string"
    ? (body as { error: string }).error
    : t("http.status", { status });
}

/// The section as the server wrote it, with the two halves defaulted so the page never
/// has to guard a missing one: an empty default group and no groups is a home that says
/// nothing about how a session runs, which is a fact rather than a malformed answer.
function asSession(body: unknown): SessionConfig {
  const wire = (body ?? {}) as Record<string, unknown>;
  const blocks = (wire.default ?? {}) as SessionBlocks;
  const groups = Array.isArray(wire.groups) ? (wire.groups as SessionGroup[]) : [];
  return {
    default: blocks,
    groups,
    path: typeof wire.path === "string" ? wire.path : "",
  };
}

/// config.edn's :session as the Session behaviour page reads it, or a throw carrying
/// the server's sentence.
export async function readSession(t: Translate): Promise<SessionConfig> {
  const res = await fetch(`${API_BASE}session`);
  const body: unknown = await res.json().catch(() => undefined);
  if (!res.ok) throw new Error(reasonFrom(body, res.status, t));
  return asSession(body);
}

/// Write the section and answer it read back -- which is the point of answering with the
/// server's copy rather than the body that was sent: a canonicalized keyword comes back
/// as the keyword the file holds.
export async function writeSession(
  change: SessionChange,
  t: Translate,
): Promise<SessionConfig> {
  const res = await fetch(`${API_BASE}session`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(change),
  });
  const body: unknown = await res.json().catch(() => undefined);
  if (!res.ok) throw new Error(reasonFrom(body, res.status, t));
  return asSession(body);
}
