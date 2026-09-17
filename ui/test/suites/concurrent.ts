// TWO CONVERSATIONS AT ONCE, over the real client and the real server.
//
// The server has always allowed this -- `harness.edge.http` gives every run its
// own go block, its own converter, its own state atom -- and nothing exercised
// it, because nothing could: one page held one runtime, so a second session was
// unreachable while the first was running. The parallel-sessions feature makes
// "two at once" the ordinary case, and this is the case that says the boundary
// the change relies on is real: two thread ids, two runs in flight together,
// two log files, no traffic between them.
//
// THE SCRIPTS CANNOT DIFFER, and that is a property of the test harness rather
// than a shortcut: `harness.e2e-server` serves every thread from ONE script file,
// read when a thread id is first seen. So the two answers are the same sentence
// and the two USER messages are not -- which is exactly the discrimination this
// case needs. What has to be true is that each log carries its OWN side of the
// conversation: the user text rides in the request, is written to the log as the
// `input` row, and is what crosses a thread boundary if anything does.
//
// The terminal condition is read off the log rather than off the run's promise:
// a run can resolve while its log is still short, and "the log reached RUN_FINISHED
// with no RUN_ERROR" is the fact that says the session is complete on disk.
import { HttpAgent } from "@ag-ui/client";
import { expect } from "vitest";

import fs from "node:fs";
import path from "node:path";

import { type Case, type Suite, content, homeDir, runUrl, script, threadId, url } from "../e2e";

/// One row of a session log, as far as this suite reads it.
type LogRow = {
  kind?: string;
  runId?: string | null;
  payload?: { type?: string; content?: unknown; messages?: { role?: string; content?: unknown }[] };
};

function logPath(tid: string): string {
  return path.join(homeDir(), "projects", ".unbound", `${tid}.jsonl`);
}

function logRows(tid: string): LogRow[] {
  try {
    return fs
      .readFileSync(logPath(tid), "utf8")
      .split("\n")
      .filter((line) => line.trim() !== "")
      .map((line) => JSON.parse(line) as LogRow);
  } catch {
    // Not written yet on the first polls -- a missing log is an empty one here.
    return [];
  }
}

/// The frames the server logged for a session: every AG-UI frame rides the log
/// as an `event` row, which is where the terminal one lives.
function frames(rows: readonly LogRow[]): string[] {
  return rows
    .filter((row) => row.kind === "event")
    .map((row) => row.payload?.type ?? "");
}

/// The user text this session's log recorded as carried by a request.
function carried(rows: readonly LogRow[]): string[] {
  return rows
    .filter((row) => row.kind === "input")
    .flatMap((row) => row.payload?.messages ?? [])
    .filter((message) => message.role === "user")
    .map((message) => (typeof message.content === "string" ? message.content : ""));
}

/// Poll until this session's log reached a terminal frame, or give up and hand
/// back what is there -- the assertion that follows is what reports the failure,
/// with the rows in hand rather than a timeout's silence.
async function untilTerminal(tid: string): Promise<LogRow[]> {
  for (let i = 0; i < 400; i += 1) {
    const rows = logRows(tid);
    if (frames(rows).some((type) => type === "RUN_FINISHED" || type === "RUN_ERROR")) return rows;
    await new Promise((resolve) => setTimeout(resolve, 25));
  }
  return logRows(tid);
}

async function rebuiltTexts(tid: string): Promise<string[]> {
  const res = await fetch(`${url()}api/threads/${encodeURIComponent(tid)}/rebuild`, { method: "POST" });
  expect(res.ok, `rebuilding ${tid} succeeded: HTTP ${res.status}`).toBe(true);
  const body = (await res.json()) as { messages?: { role?: string; content?: unknown }[] };
  return (body.messages ?? [])
    .map((message) => (typeof message.content === "string" ? message.content : ""))
    .filter((text) => text !== "");
}

/// An agent on its own thread, with a place to keep the error a failed run
/// reports. A RUN_ERROR arrives as a FRAME, so `onRunErrorEvent` is the hook that
/// sees it -- `onRunFailedEvent` does not exist on this client, and a typo in a
/// hook name is silently dropped by its subscriber registry.
function agentFor(
  tid: string,
  saidTheUser: string,
): { agent: HttpAgent; failed: () => string | null } {
  const agent = new HttpAgent({ url: runUrl(), threadId: tid });
  let failed: string | null = null;
  agent.subscribe({
    onRunErrorEvent: (b) => {
      failed = b.event.message ?? "RUN_ERROR";
    },
  });
  agent.addMessage({
    id: `u-${tid}`,
    role: "user",
    content: saidTheUser,
  });
  return { agent, failed: () => failed };
}

const cases: Case[] = [
  {
    name: "two-conversations-run-at-once-and-each-keeps-its-own-log",
    run: async () => {
      const tidA = threadId("concurrent-a");
      const tidB = threadId("concurrent-b");
      const saidA = "会话 A 说的这一句。";
      const saidB = "会话 B 说的这一句。";
      const answered = "两个会话拿到的是同一句话，因为脚本只有一个。";

      // ONE script for the run, because the harness serves every thread from this
      // one file. See this suite's header for why that is a fact about the double
      // rather than about the server.
      script([{ content: answered }]);

      const a = agentFor(tidA, saidA);
      const b = agentFor(tidB, saidB);

      // BOTH FLIGHTS, ONE WINDOW: neither is awaited before the other is started,
      // which is the whole point -- the old client refused to send the second while
      // the first was running, and the server never had to be asked.
      await Promise.all([
        a.agent.runAgent({ runId: `run-a-${tidA}`, tools: [], context: [] }),
        b.agent.runAgent({ runId: `run-b-${tidB}`, tools: [], context: [] }),
      ]);

      expect(a.failed(), "conversation A's run reported no error").toBeNull();
      expect(b.failed(), "conversation B's run reported no error").toBeNull();

      // Each client materialised its own answer, once.
      expect(
        a.agent.messages.some((m) => content(m) === answered),
        "A's client got A's answer",
      ).toBe(true);
      expect(
        b.agent.messages.some((m) => content(m) === answered),
        "B's client got B's answer",
      ).toBe(true);

      // AND THE LOGS ARE TWO, not one log with four rows in it.
      const rowsA = await untilTerminal(tidA);
      const rowsB = await untilTerminal(tidB);

      expect(frames(rowsA), "A's log reached a terminal frame").toContain("RUN_FINISHED");
      expect(frames(rowsB), "B's log reached a terminal frame").toContain("RUN_FINISHED");
      expect(frames(rowsA), "A's log carries no failed run").not.toContain("RUN_ERROR");
      expect(frames(rowsB), "B's log carries no failed run").not.toContain("RUN_ERROR");

      // The user text is the discriminator: it rides the REQUEST, so it is in the
      // log, and it is the one thing about this pair that differs.
      expect(carried(rowsA), "A's log carries A's message").toContain(saidA);
      expect(carried(rowsA), "and NOT B's").not.toContain(saidB);
      expect(carried(rowsB), "B's log carries B's message").toContain(saidB);
      expect(carried(rowsB), "and NOT A's").not.toContain(saidA);

      // Rebuilt through the server's own reader -- the same call the UI makes when
      // it opens a session -- each log gives back its own conversation, whole.
      const rebuiltA = await rebuiltTexts(tidA);
      const rebuiltB = await rebuiltTexts(tidB);
      expect(rebuiltA, "A rebuilds to A's conversation").toContain(saidA);
      expect(rebuiltA, "with A's answer in it").toContain(answered);
      expect(rebuiltA, "and nothing of B's").not.toContain(saidB);
      expect(rebuiltB, "B rebuilds to B's conversation").toContain(saidB);
      expect(rebuiltB, "with B's answer in it").toContain(answered);
      expect(rebuiltB, "and nothing of A's").not.toContain(saidA);
    },
  },
];

export const concurrentSuite: Suite = { name: "concurrent", cases };
