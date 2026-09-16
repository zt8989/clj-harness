// The skill list, over the real edge: which roots this session reads, which of them
// is which layer, who won a name conflict, and that asking costs nothing.
//
// THIS IS THE ONE SUITE THAT PLANTS A SYSTEM-LEVEL SKILL, and it can only do that
// because the harness hands its OS home over (see test/support/harness.ts and the
// e2e server's --user-home). That half of the two layers was otherwise unreachable
// from here: the machine's skills live in the developer's real home, and a check
// that read those would depend on one person's dotfiles.
//
// What this suite does NOT prove: how the menu is drawn, which keys select what, and
// what the composer holds afterwards. Those are the picker's behaviour, and they are
// measured in a real browser against a scripted backend (see the feature's spec,
// `skill-picker`), because this driver imports nothing from src/ that needs a
// browser -- ui/vitest.config.ts says so, and it is why a component test would need
// a second driver and a DOM environment this repository does not have.
import fs from "node:fs";
import os from "node:os";
import path from "node:path";

import { expect } from "vitest";

import { type Case, type Suite, homeDir, rm, threadId, url, userHomeDir } from "../e2e";

/// The wire shape, named here so a renamed field is a compile error rather than a
/// test that reads `undefined` and passes.
interface WireSkill {
  name: string;
  description: string | null;
  "available?": boolean;
  reason: string | null;
}

interface WireGroup {
  layer?: string;
  root: string;
  skills: WireSkill[];
}

/// Plant a skill where a person would put one: `<root>/<name>/SKILL.md`.
function plantSkill(root: string, name: string, frontmatter: string, body = "body\n"): void {
  const dir = path.join(root, name);
  fs.mkdirSync(dir, { recursive: true });
  fs.writeFileSync(path.join(dir, "SKILL.md"), `---\n${frontmatter}\n---\n\n${body}`, "utf8");
}

/// A throwaway directory to bind a session to. Names are unique per call, and the
/// caller removes what it made.
function tempProject(tag: string): string {
  const dir = path.join(os.tmpdir(), `clj-harness-ui-${tag}-${Math.random().toString(36).slice(2)}`);
  fs.mkdirSync(dir, { recursive: true });
  return dir;
}

/// A private name per case, so two cases in one run cannot see each other's fixtures
/// in the shared OS home.
function uniqueName(tag: string): string {
  return `${tag}-${Math.random().toString(36).slice(2, 8)}`;
}

async function bind(tid: string, dir: string | null): Promise<Response> {
  return fetch(`${url()}api/project`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ threadId: tid, dir }),
  });
}

async function skillsOf(tid: string): Promise<{ status: number; groups: WireGroup[] }> {
  const res = await fetch(`${url()}api/skills?threadId=${encodeURIComponent(tid)}`);
  const body = (await res.json()) as { groups?: WireGroup[] };
  return { status: res.status, groups: body.groups ?? [] };
}

/// Every file under DIR as `relative path -> size`, or an empty map when the tree is
/// not there. Used to prove a read-only call left nothing behind WITHOUT guessing at
/// the log layout: whatever a run writes, a route that changes nothing must not
/// appear here.
function tree(root: string): Record<string, number> {
  const out: Record<string, number> = {};
  const walk = (dir: string) => {
    if (!fs.existsSync(dir)) return;
    for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
      const full = path.join(dir, entry.name);
      if (entry.isDirectory()) walk(full);
      else out[path.relative(root, full)] = fs.statSync(full).size;
    }
  };
  walk(root);
  return out;
}

const cases: Case[] = [
  {
    name: "the-skill-list-answers-both-layers-from-the-real-roots",
    run: async () => {
      const tid = threadId("skills-layers");
      const project = tempProject("skills-layers");
      const userRoot = path.join(userHomeDir(), ".agents", "skills");
      const projectRoot = path.join(project, ".agents", "skills");
      const machine = uniqueName("machine");
      const mine = uniqueName("project");
      try {
        plantSkill(userRoot, machine, `name: ${machine}\ndescription: from the machine`);
        plantSkill(projectRoot, mine, `name: ${mine}\ndescription: from the project`);
        expect((await bind(tid, project)).status).toBe(200);

        const { status, groups } = await skillsOf(tid);
        expect(status).toBe(200);

        // The roots and their layers, in precedence order -- and the roots are the
        // two directories THIS case made, which is what makes them assertions
        // rather than echoes of whatever the machine happens to hold.
        expect(groups.map((g) => g.layer).slice(0, 2)).toEqual(["system", "project"]);
        const roots = groups.map((g) => g.root);
        expect(roots).toContain(userRoot);
        expect(roots).toContain(projectRoot);
        expect(roots.indexOf(userRoot)).toBeLessThan(roots.indexOf(projectRoot));

        // A row is a name, a description and whether it can be used -- nothing the
        // menu does not draw.
        const row = groups[0].skills.find((s) => s.name === machine);
        expect(row).toEqual({
          name: machine,
          description: "from the machine",
          "available?": true,
          reason: null,
        });
      } finally {
        await bind(tid, null);
        rm(project);
        fs.rmSync(path.join(userRoot, machine), { recursive: true, force: true });
      }
    },
  },
  {
    name: "a-shadowed-name-is-listed-once-in-the-winning-root",
    run: async () => {
      // Order is the mechanism, not a label: `scan` lets the earlier root keep a
      // name, so the machine's copy is the one both the model's catalog and this
      // list see -- asserted here over the wire, the way harness.cap.skills-test
      // asserts it in-process.
      const tid = threadId("skills-shadow");
      const project = tempProject("skills-shadow");
      const userRoot = path.join(userHomeDir(), ".agents", "skills");
      const projectRoot = path.join(project, ".agents", "skills");
      const name = uniqueName("shadow");
      try {
        plantSkill(userRoot, name, `name: ${name}\ndescription: the MACHINE copy`);
        plantSkill(projectRoot, name, `name: ${name}\ndescription: the PROJECT copy`);
        expect((await bind(tid, project)).status).toBe(200);

        const { groups } = await skillsOf(tid);
        const holders = groups.filter((g) => g.skills.some((s) => s.name === name));
        expect(holders).toHaveLength(1);
        expect(holders[0].layer).toBe("system");
        expect(holders[0].skills.find((s) => s.name === name)?.description).toBe("the MACHINE copy");
      } finally {
        await bind(tid, null);
        rm(project);
        fs.rmSync(path.join(userRoot, name), { recursive: true, force: true });
      }
    },
  },
  {
    name: "asking-for-the-list-changes-nothing",
    run: async () => {
      // A menu may be asked for as often as a person types a slash, and it must be
      // safe to ask: the route resolves and returns, and no session record moves.
      // The comparison is the whole projects tree rather than one expected file --
      // whatever a run writes, a read-only route must not change it.
      const tid = threadId("skills-readonly");
      const bookkeeping = path.join(homeDir(), "projects");

      const before = tree(bookkeeping);
      const first = await skillsOf(tid);
      const second = await skillsOf(tid);
      expect(first.status).toBe(200);
      expect(second.groups).toEqual(first.groups);
      expect(tree(bookkeeping)).toEqual(before);

      // The same for a session with a skill planted and a project bound: a listing
      // that reads two roots is still a read.
      const project = tempProject("skills-readonly");
      const userRoot = path.join(userHomeDir(), ".agents", "skills");
      const name = uniqueName("readonly");
      try {
        plantSkill(userRoot, name, `name: ${name}\ndescription: a skill for the read-only case`);
        expect((await bind(tid, project)).status).toBe(200);
        const bound = tree(bookkeeping);
        const { groups } = await skillsOf(tid);
        expect(groups.flatMap((g) => g.skills).some((s) => s.name === name)).toBe(true);
        expect(tree(bookkeeping)).toEqual(bound);

        // Only GET: anything else is refused in the shape every other route refuses
        // in, and the refusal changes nothing either.
        const res = await fetch(`${url()}api/skills?threadId=${encodeURIComponent(tid)}`, {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: "{}",
        });
        expect(res.status).toBe(405);
        expect(((await res.json()) as { error?: string }).error).toBe("method not allowed");
        expect(tree(bookkeeping)).toEqual(bound);
      } finally {
        await bind(tid, null);
        rm(project);
        fs.rmSync(path.join(userRoot, name), { recursive: true, force: true });
      }
    },
  },
];

export const skillsSuite: Suite = { name: "skills", cases };
