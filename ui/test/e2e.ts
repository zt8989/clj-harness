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

import { HarnessAgent, appendOf } from "@/lib/agent";
import { setHarnessOrigin } from "@/lib/threads";
import { familyOf } from "@/lib/mux";

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

/// Point the app's own modules at the harness this run started (see `lib/threads.setHarnessOrigin`).
export function configure(next: HarnessFacts): void {
  facts = next;
  // AND THE APP CODE LEARNS WHERE THE HARNESS IS: the downlink and the panel's frames read
  // resolve their origin at call time, and a suite has no document to resolve a relative one
  // against (`lib/threads.setHarnessOrigin`).
  setHarnessOrigin(next.url);
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

/// POST an AG-UI action, answered with the raw Response. `extra` is merged over the
/// three keys every action carries, which is how a resume is sent.
///
/// THE BODY IS AN ACTION'S, NOT A CONVERSATION'S (ticket 03 of
/// `.scratch/sessions-live-on-the-server`): no `messages`, no `runId` -- the run edge
/// refuses the first by name and mints the second, and both rules are asserted in
/// `test/suites/client.ts`. What MESSAGES contributes is the trailing run of user
/// messages (`appendOf`, the page's own rule), so a suite can keep writing the
/// conversation it means and have the wire carry only what a run adds.
///
/// THE SESSION IS MADE FIRST, on the same door the page uses: an id the run edge has
/// never heard of is refused, so a suite that posted a run for a fresh `threadId(..)`
/// would be testing the refusal rather than the run.
/// HOW A RUN IS READ NOW (tickets 03/05 of `.scratch/events-mux-and-host`): the POST answers
/// an ACK and the frames come down `events.mux`. THIS KEEPS THE OLD SHAPE -- a `Response`
/// whose body is SSE -- by subscribing first, starting the run, and turning the socket's
/// frames back into `data:` lines. `framesFromSse` below and every caller of it are unchanged:
/// a suite still reads the wire, not an interpretation of it.
/// AND THE FACT FAMILY IS NOT A RUN'S FRAME EITHER (ADR 0006): `turn/*` and `model/*` are about
/// the conversation, they carry the record's line number, and the CLIENT routes them away from
/// `@ag-ui/client` (`src/lib/mux.ts`'s `familyOf`). A suite that reads "the frames this run
/// sent" has to do the same, or every case that hands them to AG-UI's schema check fails on a
/// frame that was never meant for it -- which is the property this reader exists to keep honest.
///
/// SO IT ASKS THE CLIENT RATHER THAN SPELLING THE FAMILIES AGAIN. This file used to carry its
/// own `WINDOW_TYPES` and `FACT_TYPES`, and a copy of a vocabulary is the copy that goes stale:
/// a name the server started writing and the client's list did not have would be read HERE as
/// one of this run's frames -- the very misreading that takes a real page's run down.
/// `familyOf` is the one judgement (`lib/mux.ts`), so a suite and a page cannot disagree.
///
/// WHICH IS WHY THE TWO READERS BELOW SHARE ONE SOCKET DANCE (`readRun`): one wants the run's
/// frames, the other wants every NAME the server used -- and neither should spell the routing.

/// THE DECLARATION MUST LAND BEFORE THE RUN STARTS -- the server filters run frames by it --
/// and the socket's own `open` can beat the server's bookkeeping. This asks the route that
/// only ANSWERS once the set is recorded, retrying that race away.
async function muxDeclared(token: string, tid: string): Promise<void> {
  for (let attempt = 0; attempt < 40; attempt += 1) {
    const res = await fetch(`${url()}api/events.mux/subscribe`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ subscriber: token, subscribe: [{ threadId: tid }] }),
    });
    if (res.ok) return;
    await new Promise((resolve) => setTimeout(resolve, 25));
  }
  throw new Error("events.mux never accepted this suite's declaration");
}

/// A PARSED FRAME OFF THE DOWNLINK, plus the two things the SOCKET adds: the conversation it
/// is about, and the downlink's own `:seq` -- the reconnect bookkeeping (ADR 0003 decision 7),
/// which is no family's vocabulary.
type RawFrame = Frame & { threadId?: string; seq?: number };

/// ONE RUN, READ STRAIGHT OFF THE SOCKET: every frame the downlink carried for TID while the
/// run ran, in arrival order -- the window's frames, the facts, and the run's AG-UI events,
/// exactly as they arrived. A REFUSED RUN hands back its response instead: there was no run to
/// follow, and a case about a refusal still reads its status and its sentence.
///
/// AFTERTERMINALMS IS THE ROOM A READER NEEDS WHEN IT WANTS THE CONVERSATION'S OWN FACTS TOO:
/// the run's last fact can arrive after the frame that says the run is over, and the terminal
/// branch below says why. Zero -- what `postRun` wants, and what this did before the option
/// existed -- stops at the terminal.
///
/// THE ONE SOCKET DANCE, because the two readers below want different halves of what lands on
/// it -- `postRun` wants the run's frames, `frameTypesFromRun` wants every name the server used.
/// Reading once and filtering after is what keeps the two from drifting apart.
async function readRun(
  tid: string,
  messages: readonly Message[],
  extra?: Record<string, unknown>,
  afterTerminalMs = 0,
): Promise<{ refused: Response } | { frames: RawFrame[] }> {
  await ensureSession(tid);
  const token = crypto.randomUUID();
  const params = new URLSearchParams();
  params.set("subscriber", token);
  params.set("sessions", JSON.stringify([{ threadId: tid }]));
  const socket = new WebSocket(`${url().replace(/^http/, "ws")}api/events.mux?${params}`);
  const frames: RawFrame[] = [];
  let settle: () => void = () => {};
  const done = new Promise<void>((resolve) => {
    settle = resolve;
  });
  socket.addEventListener("message", (event) => {
    const frame = JSON.parse(String((event as MessageEvent).data)) as RawFrame;
    // ANOTHER CONVERSATION'S FRAME IS NOT THIS RUN'S. The server filters by what this
    // connection declared, so this is the belt to that pair of braces -- and this is the one
    // place a frame about a thread nobody asked for stops rather than being handed on.
    if (frame.threadId !== tid) return;
    frames.push(frame);
    // THE TERMINAL FRAME IS NOT ALWAYS THE LAST THING A RUN PUTS ON THE SOCKET, so a reader
    // that hangs up here can miss the tail of the conversation's own facts. `turn/end` goes out
    // in the edge's `:run/done` branch, and the frame saying the run is over comes from
    // `:run/end` -- ONE KERNEL EVENT EARLIER (`harness.edge.http`'s drain loop notes, where it
    // converts, that `:run/done` itself is never converted). `afterTerminalMs` is the room to
    // wait for that tail; a reader wanting only the run's frames needs none of it.
    if (frame.type === "RUN_FINISHED" || frame.type === "RUN_ERROR") {
      if (afterTerminalMs > 0) setTimeout(settle, afterTerminalMs);
      else settle();
    }
  });
  await new Promise<void>((resolve, reject) => {
    socket.addEventListener("open", () => resolve());
    socket.addEventListener("error", () => reject(new Error("events.mux refused this suite")));
  });
  await muxDeclared(token, tid);
  const started = await fetch(runUrl(), {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ threadId: tid, append: appendOf(messages), tools: [], ...extra }),
  });
  if (!started.ok) {
    socket.close();
    return { refused: started };
  }
  await done;
  socket.close();
  return { frames };
}

export async function postRun(
  tid: string,
  messages: readonly Message[],
  extra?: Record<string, unknown>,
): Promise<Response> {
  const read = await readRun(tid, messages, extra);
  if ("refused" in read) return read.refused;
  // THE RUN'S OWN FRAMES, AND ONLY THEIR OWN FIELDS: `:seq` is the downlink's bookkeeping for
  // the reconnect cursor, and the rest of a frame -- `threadId` included -- is the AG-UI event
  // the server sent, exactly as a runtime would read it. The window's frames and the facts are
  // the two families this SSE never carried (`familyOf` is what says so, above).
  const body = read.frames
    .filter((frame) => familyOf(frame.type) === "run")
    .map(({ seq: _seq, ...rest }) => `data: ${JSON.stringify(rest)}\n\n`)
    .join("");
  return new Response(body, { status: 200, headers: { "Content-Type": "text/event-stream" } });
}

/// EVERY DISTINCT FRAME TYPE THE SERVER PUT ON TID'S SOCKET WHILE ONE RUN RAN -- the whole
/// vocabulary of the downlink, not just the run's half.
///
/// THIS IS HOW A SUITE ASKS WHAT THE SERVER CALLS THINGS. The fact family's names live in two
/// processes and two languages (`harness.edge.mux/fact-types` writes them, `lib/mux.ts`'s
/// `FACT_TYPES` classifies them) and no build compares the two, so the agreement can only be
/// read off a real run: see `suites/frames.ts`'s `the-wire-says-which-names-are-facts`.
export async function frameTypesFromRun(tid: string, messages: readonly Message[]): Promise<string[]> {
  // A GRACE PERIOD, not a guess about ordering: the run's terminal frame has already gone out by
  // the time `turn/end` follows it (see the terminal branch in `readRun`), and the two are one
  // kernel event apart -- so this waits for the tail instead of hanging up on the run.
  const read = await readRun(tid, messages, undefined, 500);
  if ("refused" in read) {
    throw new Error(`the run was refused: HTTP ${read.refused.status} ${await read.refused.text()}`);
  }
  return [...new Set(read.frames.map((frame) => frame.type))];
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

export async function fetchFrames(tid: string, messages: readonly Message[]): Promise<Frame[]> {
  const resp = await postRun(tid, messages);
  return framesFromSse(await resp.text());
}

/// MAKE SURE THIS HOME KNOWS TID, answering the id to use (POST /api/sessions).
///
/// Find-or-create, and never an unbind (the server's own rule), so a case may say this
/// about any conversation, at any point, more than once. It is the page's
/// `lib/projects.startTask` with no id to bring, then with one: a suite's ids are its
/// own making, which is what `threadId(..)` is for -- and registering is how an id this
/// suite made up becomes a conversation this home keeps.
export async function ensureSession(tid: string): Promise<string> {
  const resp = await fetch(`${url()}api/sessions`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ threadId: tid }),
  });
  if (!resp.ok) throw new Error(`POST /api/sessions refused ${tid}: HTTP ${resp.status} ${await resp.text()}`);
  return ((await resp.json()) as { threadId: string }).threadId;
}

/// THE PAGE'S OWN AGENT, for a session this home knows: the class `app.tsx` builds
/// (`lib/agent.HarnessAgent`), driving the address the running harness announced.
///
/// A SUITE THAT BUILT ITS OWN CLIENT WOULD PROVE NOTHING ABOUT THE PAGE, which is the
/// whole reason this exists: the thing that changed in ticket 03 is what a run's BODY
/// is, and the body is built by this class. `ensureSession` runs first because the
/// agent's first request would otherwise be refused by name.
export async function agentFor(tid: string): Promise<HarnessAgent> {
  await ensureSession(tid);
  // THE DOWNLINK IS THE TRANSPORT (ADR 0004): the suite's agent reads its run's frames the
  // way the page does, so a change to that path is what a suite failure means.
  const agent = new HarnessAgent({ url: runUrl() });
  agent.threadId = tid;
  return agent;
}

// -------------------------------------------------------------- the filesystem

/// A directory of this case's own under the system temp directory, named for
/// PREFIX. `mkdtempSync`, NOT `<tmpdir>/<name>`: the temp directory outlives this
/// process, so a composed name is THE SAME PATH on the next run and in every
/// process running beside it -- a marker file one run left behind is then read as
/// this run's answer, and two runs at once write into one directory. mkdtemp asks
/// the OS for a name nothing holds, in the same call that creates it, and hands
/// back an EMPTY directory. The caller removes what it made (`rm`).
export function tmpDir(prefix: string): string {
  return fs.mkdtempSync(path.join(os.tmpdir(), prefix));
}

export function rm(p: string): void {
  fs.rmSync(p, { force: true, recursive: true });
}

export function fileExists(p: string): boolean {
  return fs.existsSync(p);
}
