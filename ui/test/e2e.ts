// Shared plumbing for the suites that drive a live harness: the address, the
// script hand-off, the wire, and the shape a case and a suite take.
//
// The harness is started by the vitest driver (test/ui.test.ts, not here) and
// hands this module its two facts through `configure`: HARNESS_URL, and the
// script file -- the file the server re-reads whenever a NEW thread id arrives.
// Writing that file is how a case says what the model will reply, which is the
// whole reason these suites need no api-key: the provider is `harness.fake`,
// scripted, and the script is bytes on disk both processes can see.
//
// Every suite uses its own thread ids, so two of them cannot collide even when
// run back to back in one process, and a conversation that goes wrong cannot
// poison the next.
import crypto from "node:crypto";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";

import type { Message } from "@ag-ui/client";

/// One test: a name, and a body. Registration belongs to the driver, so the
/// driver is also the place that can refuse to run a suite that contributed
/// nothing (see test/ui.test.ts).
export interface Case {
  name: string;
  run: () => Promise<void>;
}

/// One suite's cases, in source order.
export interface Suite {
  name: string;
  cases: readonly Case[];
}

/// The two facts the harness hands over. Set once, by the driver's beforeAll.
export interface HarnessFacts {
  url: string;
  scriptPath: string;
  /// The config root, where the server's session logs land.
  home: string;
  /// The OS home -- the host's convention directory (`~/.agents/skills`,
  /// `~/AGENTS.md`). A case that wants a SYSTEM-LEVEL skill in the catalogue
  /// writes one here, before the call that should see it.
  userHome: string;
}

/// A parsed SSE frame, as the wire delivers it. Only the fields the suites read
/// are named; everything else rides along. `framesFromSse` is the one place a
/// JSON.parse result is given this name, and the reason it is a named boundary
/// type rather than a cast to `any` is that a typo in a field name below should
/// be a compile error, not a test that quietly reads undefined.
export interface Frame {
  type: string;
  messageId?: string;
  role?: string;
  toolCallId?: string;
  id?: string;
  message?: string;
  [key: string]: unknown;
}

let facts: HarnessFacts | null = null;

export function configure(next: HarnessFacts): void {
  facts = next;
}

function requireFacts(): HarnessFacts {
  if (facts === null) {
    throw new Error("the e2e harness is not configured -- start it in the driver's beforeAll and call configure()");
  }
  return facts;
}

/// The address the running harness announced, or a throw.
///
/// NO FALLBACK TO A PORT. This used to answer the harness's DEFAULT address when
/// nothing had been configured, and that is the one port a developer's own session,
/// another checkout, or yesterday's forgotten server is most likely to be holding --
/// so an unconfigured run would have quietly talked to a STRANGER, and could have
/// passed against it. The spawner states the same rule in its own header
/// (`test/support/harness.ts`: a run must not be satisfied by a stale server that
/// happens to be listening); the three accessors below already refuse to guess, and
/// this one now does too.
export function url(): string {
  return requireFacts().url;
}

/// THE AG-UI ENDPOINT: where a `RunAgentInput` is POSTed. NOT `url()`, which is
/// the server root -- the run edge lives under the API prefix with every other
/// route (`POST /api/agent`), and an agent built against `url()` would post a run
/// at the root and be answered `no such route`.
export function runUrl(): string {
  return `${url()}api/agent`;
}

/// The two homes the running harness was given. Read fresh on every call, like
/// the server reads them: the server re-reads its convention files and skill
/// roots on every run, so a case plants a file and the NEXT call is what proves
/// it was read. Nothing is cached here -- a cached path would still be right, but
/// a cached ANSWER is the bug this discipline exists to avoid.
export function homeDir(): string {
  return requireFacts().home;
}

export function userHomeDir(): string {
  return requireFacts().userHome;
}

/// A fresh id per conversation. The harness holds NO session state, so an id is
/// just a log filename.
export function threadId(prefix: string): string {
  return `${prefix}-${crypto.randomUUID()}`;
}

/// Install `turns` as what the server serves from for the next NEW thread id.
///
/// A turn declares a tool call like:
///   { id: "c1", name: "read", arguments: { path: "deps.edn" } }
/// `tool-calls` and `arguments` are spelled the way harness.fake reads them; the
/// server's JSON reader keywordizes them on arrival.
export function script(turns: readonly unknown[], opts?: { thinking?: boolean }): void {
  fs.writeFileSync(requireFacts().scriptPath, JSON.stringify({ turns, ...opts }));
}

// -------------------------------------------------------------- the wire

/// A message's text, or "" -- a message may carry only reasoning, or only tool
/// calls, and `content` is then undefined (or, on an activity message, an
/// object) rather than a string.
export function content(m: Message): string {
  return typeof m.content === "string" ? m.content : "";
}

/// POST an AG-UI RunAgentInput, answered with the raw Response. `extra` is
/// merged over the four required keys, which is how a resume is sent.
export async function postRun(
  tid: string,
  rid: string,
  messages: readonly Message[],
  extra?: Record<string, unknown>,
): Promise<Response> {
  return fetch(runUrl(), {
    method: "POST",
    headers: { "Content-Type": "application/json", Accept: "text/event-stream" },
    body: JSON.stringify({ threadId: tid, runId: rid, messages, tools: [], context: [], ...extra }),
  });
}

/// An SSE body -> an array of parsed data frames. Deliberately hand-rolled
/// rather than borrowed from the client: this is the raw wire, and a test of
/// the wire should not ask the client to interpret it first.
export function framesFromSse(body: string): Frame[] {
  return body
    .split("\n")
    .filter((line) => line.length >= 5 && line.slice(0, 5) === "data:")
    .map((line) => JSON.parse(line.slice(5).trim()) as Frame);
}

export async function fetchFrames(tid: string, rid: string, messages: readonly Message[]): Promise<Frame[]> {
  const resp = await postRun(tid, rid, messages);
  return framesFromSse(await resp.text());
}

// -------------------------------------------------------------- the filesystem

export function tmpPath(name: string): string {
  return path.join(os.tmpdir(), name);
}

export function rm(p: string): void {
  fs.rmSync(p, { force: true, recursive: true });
}

export function fileExists(p: string): boolean {
  return fs.existsSync(p);
}
