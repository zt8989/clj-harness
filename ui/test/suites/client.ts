// THE PAGE'S OWN CLIENT CODE, AGAINST A LIVE HARNESS: the agent a run goes through
// (`lib/agent.HarnessAgent` -- the real `@ag-ui/client` with exactly one difference)
// and the one helper that makes a session exist (`lib/projects.startTask`). From
// ui/verify.mjs.
//
// What this proves that an offline test structurally cannot: the frames the
// server writes are frames a REAL client can consume. A run can be generated,
// logged and even schema-valid and still fail here, because the client
// materialises messages by applying frames in order -- reasoning, tool calls,
// tool results -- and any of that can come out wrong while every individual frame
// looks fine.
//
// AND IT PROVES THE PAGE'S OWN AGENT IS THE ONE THE SERVER TAKES: a run built by
// `HarnessAgent` carries `append` and no `messages` (ticket 03 of
// `.scratch/sessions-live-on-the-server`), so a client that still sent the
// conversation would be refused here rather than in a browser. Two of the cases below
// read that contract directly: the wire body a run posts, and the server-minted id a
// new conversation is known by.
//
// The script is ours, so the reasoning text and the final answer are known
// exactly, and the byte-for-byte comparison stays meaningful without a model.
import { expect } from "vitest";

import { type Case, type Suite, agentFor, content, ensureSession, runUrl, script, threadId, url } from "../e2e";
import { HarnessAgent } from "@/lib/agent";

const reasoning = "用户想看这个项目。先读 deps.edn 确认依赖。";
const answer = "这是一个 Clojure 项目，只有 4 个依赖。";

/// The page's agent on TID -- the id lives on the agent, because
/// `prepareRunAgentInput` reads `this.threadId` rather than a per-run argument --
/// plus the ordered list of hook names the run fires.
async function newAgent(tid: string): Promise<{ agent: HarnessAgent; events: string[] }> {
  const agent = await agentFor(tid);
  const events: string[] = [];
  agent.subscribe({
    onReasoningMessageStartEvent: () => {
      events.push("REASONING_MESSAGE_START");
    },
    onReasoningMessageContentEvent: (b) => {
      events.push(`reasoning:${b.event.delta}`);
    },
    onReasoningMessageEndEvent: () => {
      events.push("REASONING_MESSAGE_END");
    },
    onTextMessageContentEvent: (b) => {
      events.push(`text:${b.event.delta}`);
    },
    onToolCallStartEvent: (b) => {
      events.push(`tool:${b.event.toolCallName}`);
    },
    onToolCallResultEvent: () => {
      events.push("TOOL_CALL_RESULT");
    },
    onRunFinishedEvent: () => {
      events.push("RUN_FINISHED");
    },
  });
  return { agent, events };
}

const cases: Case[] = [
  {
    name: "reasoning-and-tool-calls-survive-the-wire",
    run: async () => {
      const { agent, events } = await newAgent(threadId("client"));
      script([
        {
          reasoning,
          content: "",
          "tool-calls": [{ id: "c1", name: "read", arguments: { path: "deps.edn" } }],
        },
        { content: answer },
      ]);
      await agent.runAgent({ tools: [], context: [] });

      const messages = agent.messages;
      const byRole = (role: string) => messages.filter((m) => m.role === role);

      expect(byRole("reasoning").length, "the client materialised a reasoning message").toBeGreaterThan(0);
      expect(byRole("reasoning").some((m) => content(m) === reasoning), "and its text survived the wire byte for byte").toBe(true);
      expect(
        messages.some((m) => m.role === "assistant" && (m.toolCalls?.length ?? 0) > 0),
        "an assistant message carries the tool call",
      ).toBe(true);
      expect(byRole("tool").length, "the tool result came back as a message").toBeGreaterThan(0);
      expect(messages.some((m) => content(m) === answer), "the final answer arrived intact").toBe(true);
      expect(events.some((e) => e.includes("reasoning:")), "the reasoning HOOKS fired, not merely the message list").toBe(true);
      expect(events.some((e) => e.includes("CHUNK")), "no chunk event ever reached the client").toBe(false);
      expect(events, "and the run finished").toContain("RUN_FINISHED");
    },
  },
  {
    name: "a-tool-round-costs-two-llm-calls",
    // The scripted provider hands out ONE TURN PER CALL, so a tool round has to
    // consume exactly two turns: the call, then the reply to its result. If the
    // kernel called the model once and reused the answer, the first turn's tool
    // call would come back as the final message and the second turn's text would
    // never appear -- which is what this asserts, rather than trusting a count.
    run: async () => {
      const { agent } = await newAgent(threadId("twocalls"));
      script([
        { content: "", "tool-calls": [{ id: "c1", name: "read", arguments: { path: "deps.edn" } }] },
        { content: "第二轮。" },
      ]);
      await agent.runAgent({ tools: [], context: [] });

      const messages = agent.messages;
      expect(messages.some((m) => content(m) === "第二轮。"), "the SECOND turn's text is in the conversation").toBe(true);
      expect(messages.filter((m) => m.role === "tool").length, "exactly one tool message, from the one call").toBe(1);
    },
  },

  {
    name: "a-second-turn-echoes-its-reasoning-back-to-a-thinking-mode-vendor",
    // THE COVERAGE GAP THIS EXISTS FOR: the case above runs ONE turn and looks at
    // what the client materialised. The defect that bit a real provider only shows
    // up on the SECOND request -- the one that carries the first turn's assistant
    // messages in its history. WHAT THAT REQUEST IS BUILT FROM CHANGED (ticket 03):
    // the client no longer sends the history back, so the second request's
    // conversation comes out of the SERVER's memory, folded from the record. The
    // defect would survive that move exactly as it was, which is why this case is
    // still here: two turns on one thread, against a strict vendor
    // (`script(.., {thinking: true})`) that answers 400 whenever an assistant
    // message arrives without `reasoning_content`.
    //
    // THE 400 IS THE ASSERTION. `harness.fake` refuses with the real gateway's own
    // bytes (test/harness/fake.clj), so a second turn that finishes is a second
    // request that carried the reasoning TEXT. What it cannot show is the pad for a
    // round that produced none -- that is `harness.kernel.llm-test`'s case, where the
    // exact history can be read (`thinking-mode-history`).
    run: async () => {
      const tid = threadId("thinkturn");
      const first = "第一轮：先看一眼 deps.edn。";
      const second = "第二轮的想法。";
      const { agent } = await newAgent(tid);
      // ONE script for the whole conversation: the file is re-read per NEW thread id,
      // and a tool round costs two LLM calls (the call, then the reply to its result).
      script(
        [
          { reasoning: first, content: "", "tool-calls": [{ id: "c1", name: "read", arguments: { path: "deps.edn" } }] },
          { content: "第一轮的回答。" },
          { reasoning: second, content: "第二轮的回答。" },
        ],
        { thinking: true },
      );

      await agent.runAgent({ tools: [], context: [] });
      // The SAME agent runs again. Its own `messages` now carry the first turn -- and
      // what crosses the wire is only the new question (`lib/agent.appendOf`), so the
      // history this request is built from is the server's.
      await agent.runAgent({ tools: [], context: [] });

      const messages = agent.messages;
      expect(
        messages.some((m) => content(m) === "第二轮的回答。"),
        "the second turn's answer arrived -- so the vendor accepted the second request",
      ).toBe(true);
      expect(
        messages.some((m) => m.role === "reasoning" && content(m) === first),
        "and the client materialised the first turn's reasoning as its own message",
      ).toBe(true);

      // THE OTHER SIDE OF THE SAME FACT: the conversation the SERVER holds still
      // carries that reasoning. Read through the server's own rebuild (the call the
      // page makes when it opens a session), so a pass cannot come from the vendor
      // having been asked something else.
      const held = await heldTexts(tid);
      expect(held, "the reasoning text is in the conversation the server holds").toContain(first);
      expect(held, "and so is the first turn's answer").toContain("第一轮的回答。");
    },
  },
  {
    name: "a-run-carries-the-action-and-not-the-conversation",
    // TICKET 03'S OWN CONTRACT, read off the WIRE rather than inferred from the
    // server's answer. The body a run posts is `threadId`, `append` and `tools`: no
    // `messages` (the conversation is the server's) and no `runId` (the server names
    // the run, and the frames carry what it named). The capture is the seam
    // `HttpAgent` already offers for customizing a request -- an own `fetch` -- so
    // this reads the same bytes the page would put on the wire.
    //
    // AND THE SECOND RUN IS THE INTERESTING ONE. A client that sent the conversation
    // would send both questions the second time; this asserts it sends ONLY the new
    // one, because the server already has the rest -- which is the whole change.
    run: async () => {
      const tid = threadId("action-body");
      await ensureSession(tid);
      type Body = { threadId?: string; append?: { id?: string }[]; [key: string]: unknown };
      const bodies: Body[] = [];
      const agent = new HarnessAgent({
        url: runUrl(),
        fetch: (requestUrl, init) => {
          bodies.push(JSON.parse(String(init.body)) as Body);
          return fetch(requestUrl, init);
        },
      });
      agent.threadId = tid;
      script([{ content: "第一次回答。" }, { content: "第二次回答。" }]);

      agent.addMessage({ id: "u-1", role: "user", content: "第一个问题。" });
      await agent.runAgent({ tools: [], context: [] });
      agent.addMessage({ id: "u-2", role: "user", content: "第二个问题。" });
      await agent.runAgent({ tools: [], context: [] });

      expect(bodies.length, "two runs, two bodies").toBe(2);
      for (const [index, body] of bodies.entries()) {
        expect(Object.keys(body), `body ${index + 1} carries no messages`).not.toContain("messages");
        expect(Object.keys(body), `body ${index + 1} carries no runId`).not.toContain("runId");
        expect(body.threadId, `body ${index + 1} names the session`).toBe(tid);
      }
      expect(bodies[0].append?.map((m) => m.id), "the first body adds the first question").toEqual(["u-1"]);
      expect(
        bodies[1].append?.map((m) => m.id),
        "the second adds ONLY the second question -- the conversation is not sent back",
      ).toEqual(["u-2"]);

      // AND THE SERVER CONTINUED FROM ITS OWN COPY: both questions, and both answers
      // the client materialised, are in one conversation.
      expect(
        agent.messages.some((m) => content(m) === "第二次回答。"),
        "the second turn's answer arrived, so the run continued from the server's conversation",
      ).toBe(true);
      const held = await heldTexts(tid);
      expect(held, "the server's conversation holds both questions").toEqual(
        expect.arrayContaining(["第一个问题。", "第二个问题。"]),
      );
      expect(held, "and both answers").toEqual(expect.arrayContaining(["第一次回答。", "第二次回答。"]));
    },
  },
  {
    name: "a-server-named-session-is-one-a-run-can-be-sent-to",
    // THE OTHER HALF OF TICKET 03, asked the way this run CAN ask it. What the page
    // calls is `lib/projects.startTask` -- `POST /api/sessions` with nothing named --
    // and the server mints the id in its answer. THE HELPER ITSELF CANNOT BE CALLED
    // FROM HERE: its address is the PAGE's own origin (`lib/threads.API_BASE`, a bare
    // "/api/..."), and a node process has no base to resolve that against; the suites
    // reach the harness by absolute address instead (`url()`).
    //
    // SO THIS ASKS THE ROUTE AND THEN DOES WHAT THE PAGE DOES WITH THE ANSWER: parks on
    // the id and sends a run under it. An id the page had made up and never registered
    // would be REFUSED there by name -- that door is `harness.edge.http-test`'s case --
    // and this is the accepted half, which is what makes the server's answer usable.
    run: async () => {
      const askForOne = async (): Promise<string> => {
        const resp = await fetch(`${url()}api/sessions`, {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({}),
        });
        expect(resp.status, "a body that names nothing is a request, not a mistake").toBe(200);
        return ((await resp.json()) as { threadId?: string }).threadId ?? "";
      };

      const minted = await askForOne();
      expect(minted, "the answer names the conversation").not.toBe("");

      // IT IS A CONVERSATION THIS HOME LISTS, before a word has been typed: a task,
      // which is what the sidebar draws a session with no project as.
      const listing = (await (await fetch(`${url()}api/projects`)).json()) as {
        tasks?: { threadId?: string }[];
      };
      expect(listing.tasks?.some((s) => s.threadId === minted), "the id is a row the sidebar draws").toBe(true);

      // AND THE PAGE'S OWN AGENT RUNS UNDER IT.
      script([{ content: "开张。" }]);
      const agent = await agentFor(minted);
      await agent.runAgent({ tools: [], context: [] });
      expect(agent.messages.some((m) => content(m) === "开张。"), "the run ran under the server's id").toBe(true);

      // MINTING IS PER CALL: a second ask is a second conversation, which is what two
      // clicks on "New task" are.
      expect(await askForOne(), "the next ask is another conversation").not.toBe(minted);
    },
  },
];

/// The texts of the conversation the SERVER holds for TID, read through
/// `POST /api/threads/<tid>/rebuild` -- the reader the page uses when it opens a
/// session, and the one that answers the display shape (reasoning as its own
/// message, tool results included).
async function heldTexts(tid: string): Promise<string[]> {
  const res = await fetch(`${url()}api/threads/${encodeURIComponent(tid)}/rebuild`, { method: "POST" });
  expect(res.ok, `rebuilding ${tid} succeeded: HTTP ${res.status}`).toBe(true);
  const body = (await res.json()) as { messages?: { content?: unknown }[] };
  return (body.messages ?? [])
    .map((message) => (typeof message.content === "string" ? message.content : ""))
    .filter((text) => text !== "");
}

export const clientSuite: Suite = { name: "client", cases };
