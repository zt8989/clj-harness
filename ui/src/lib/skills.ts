// The skill list: what this session can load, and which layer each one came from.
//
// ONE QUESTION, ASKED OF THE SERVER. Which roots this session reads, which of them
// is the machine's and which the project's, who won a name conflict, and why a
// skill cannot be used are all facts `harness.cap.skills` already holds -- the same
// facts the model's catalog is built from. Re-deriving any of them here would be a
// second answer, free to disagree with the first, and the two WOULD drift: they are
// read at different moments, by different code, for different readers.
//
// The layer is a KEY on the wire, not a sentence: what to CALL it is this
// interface's business, the same split `:reason` keeps (a keyword from the server,
// the words around it from here).
import type { TFunction } from "i18next";

import { AGENT_URL } from "@/lib/threads";

/// The translator a layer chip is worded through. It is the composer's own catalog
/// because that is the only face that draws a layer today: `System` / `Project` are
/// this interface's names for the server's `system` / `project` keys, and they say
/// 系统级 / 项目级 in Chinese.
type Translate = TFunction<"composer">;

/// The server's `{:error ..}` reason, when the body carries one -- the same habit
/// `lib/composer.ts` and `lib/projects.ts` keep, and for the same reason: the
/// server's sentence is the one worth showing.
async function reasonFrom(res: Response): Promise<string> {
  const body: unknown = await res.json().catch(() => undefined);
  return body !== undefined &&
    typeof body === "object" &&
    body !== null &&
    "error" in body &&
    typeof body.error === "string"
    ? body.error
    : `HTTP ${res.status}`;
}

/// One skill as the server describes it. `available?` and `reason` are a PAIR: an
/// unusable skill is still listed (a skill that silently vanished and one that was
/// never installed look identical from the outside, so the second, harder thing to
/// debug must not be what a bug produces), and `reason` is why.
export type SkillRow = {
  name: string;
  description: string | null;
  "available?": boolean;
  reason: string | null;
};

/// One root's skills. `layer` is ABSENT when the root came from a configured
/// `:skills {:roots ..}` list: the configuration never said "system" or "project"
/// about those paths, so there is no layer to name -- and the root's path is the
/// honest answer (see `layerWord`).
export type SkillGroup = {
  layer?: string;
  root: string;
  skills: SkillRow[];
};

/// A row of the flat table: the skill plus where it came from. The menu is ONE
/// list with a per-row layer rather than a two-level drill-down -- see the
/// feature's spec for why.
export type Skill = SkillRow & { layer?: string; root: string };

export async function skillsFor(threadId: string): Promise<SkillGroup[]> {
  const res = await fetch(`${AGENT_URL}api/skills?threadId=${encodeURIComponent(threadId)}`);
  if (!res.ok) throw new Error(await reasonFrom(res));
  const body = (await res.json()) as { groups?: SkillGroup[] };
  return body.groups ?? [];
}

/// The groups, flattened, each row carrying the layer and the root of the group it
/// came from.
export function skillsIn(groups: readonly SkillGroup[]): Skill[] {
  return groups.flatMap((group) =>
    group.skills.map((skill) => ({ ...skill, layer: group.layer, root: group.root })),
  );
}

/// The words the two layers get on screen, through a translator. An unknown layer
/// draws NO chip: a root nobody has named is better described by its path (which is
/// what the row's title shows) than by a word invented here to fill the space.
///
/// THE KEYS ARE WRITTEN OUT, one branch per layer, rather than built from the layer
/// (`t("layer." + layer)` is not allowed anywhere in this repo): the layer is a
/// keyword from the server, so a template key would print a raw name for a layer this
/// page has no word for.
export function layerWord(t: Translate, layer: string | undefined): string | null {
  if (layer === "system") return t("skill.layer.system");
  if (layer === "project") return t("skill.layer.project");
  return null;
}

/// The filter, matching the kit's own rule for a trigger item (`id`, `label` and
/// `description`, case-insensitively): the kit delegates to this function when a
/// popover has no categories, so a rule that differed here would be the only rule.
export function matches(skill: Skill, query: string): boolean {
  const q = query.trim().toLowerCase();
  if (q === "") return true;
  return (
    skill.name.toLowerCase().includes(q) ||
    (skill.description ?? "").toLowerCase().includes(q)
  );
}
