// Every frame the server emits, parsed by the SHIPPED AG-UI schema.
//
// From ui/check-frames.mjs, which did this by hand: post one run, split the SSE
// body, feed each frame to @ag-ui/core's EventSchemas. Worth keeping because the
// schema is not a summary of the protocol -- it is the client's own validation,
// and this project already found three real violations through it that reading
// the prose did not. EventSchemas is zod, and zod validates BEFORE the client's
// applier sees a frame, so an invalid frame is a hard client failure no matter
// how harmless it looks.
//
// The script is one turn carrying a tool call, because that is where the most
// frame types appear: the reasoning before it, the call, and its result.
import { EventSchemas } from "@ag-ui/core";
import { expect } from "vitest";

import {
  type Case,
  type Frame,
  type Suite,
  fetchFrames,
  frameTypesFromRun,
  framesFromSse,
  postRun,
  script,
  threadId,
} from "../e2e";
import { familyOf } from "@/lib/mux";

function valid(frame: Frame): boolean {
  return EventSchemas.safeParse(frame).success;
}

function types(frames: readonly Frame[]): string[] {
  return frames.map((f) => f.type);
}

/// The last frame, or undefined on an empty stream. Named rather than indexed at
/// each call site so "the run ENDED" reads the same in every case below.
function last<T>(items: readonly T[]): T | undefined {
  return items[items.length - 1];
}

const cases: Case[] = [
  {
    name: "every-frame-passes-the-ag-ui-schema",
    run: async () => {
      script([
        {
          reasoning: "先看一眼。",
          content: "",
          "tool-calls": [{ id: "c1", name: "read", arguments: { path: "deps.edn" } }],
        },
        { content: "这是一个 Clojure 项目。" },
      ]);
      const frames = await fetchFrames(threadId("frames"), []);

      expect(frames.length, "the run produced frames at all").toBeGreaterThan(0);

      const bad = frames.filter((f) => !valid(f));
      // Name the offending TYPE, not just a count: a count says a frame is
      // wrong, the type says which one.
      expect(types(bad), `every frame passes EventSchemas; invalid: ${JSON.stringify(types(bad))}`).toEqual([]);

      // The run must have ENDED, or "0 invalid" could just mean the stream
      // stopped early -- the cheapest way to pass a linter.
      expect(last(types(frames)), "the run reached RUN_FINISHED").toBe("RUN_FINISHED");
    },
  },
  {
    name: "reasoning-frames-are-shape-legal",
    // The three violations this project actually shipped, pinned so they cannot
    // come back: REASONING_START/END need a messageId, and the reasoning message's
    // role must be the literal "reasoning".
    run: async () => {
      script([{ reasoning: "先看一下。", content: "ok" }]);
      const frames = await fetchFrames(threadId("reasoning"), []);

      const starts = frames.filter((f) => f.type === "REASONING_MESSAGE_START");
      expect(starts.length, "the run emitted reasoning frames").toBeGreaterThan(0);
      expect(starts.every((f) => f.messageId !== undefined), "every REASONING_MESSAGE_START carries a messageId").toBe(true);
      expect(starts.every((f) => f.role === "reasoning"), 'and its role is the literal "reasoning"').toBe(true);
    },
  },
  {
    name: "tool-frames-name-their-call",
    // A tool call with no id -- or a result that names none -- is how a tool card
    // silently fails to render: the client matches them up by id alone.
    run: async () => {
      script([
        { content: "", "tool-calls": [{ id: "c9", name: "read", arguments: { path: "deps.edn" } }] },
        { content: "done" },
      ]);
      const frames = await fetchFrames(threadId("toolframes"), []);

      const idOf = (f: Frame): string | undefined => f.toolCallId ?? f.id;
      const starts = frames.filter((f) => f.type === "TOOL_CALL_START");
      const ends = frames.filter((f) => f.type === "TOOL_CALL_END");

      expect(starts.length, "one tool call started").toBe(1);
      expect(ends.length, "and one ended").toBe(1);
      expect(starts.every((f) => idOf(f) !== undefined), "every start names a call").toBe(true);
      expect(new Set(starts.map(idOf)), "the ends name the same calls the starts did").toEqual(new Set(ends.map(idOf)));
    },
  },
  {
    name: "parallel-calls-of-one-turn-share-one-assistant-message",
    // Two calls in ONE model turn are one assistant message with two tool_calls --
    // that is what the provider is asked to answer and what the record has to fold
    // back to. A fresh parent message per call splits the turn into two assistant
    // messages, and the first is then followed by an assistant message instead of
    // its tool result: a history an OpenAI-shaped vendor refuses outright. That is
    // the 2026-09-21 RUN_ERROR ("4 tool calls unanswered") in harness.infra.log,
    // so this pins the wire contract at the real client, schema check included.
    run: async () => {
      script([
        {
          content: "",
          "tool-calls": [
            { id: "p1", name: "read", arguments: { path: "deps.edn" } },
            { id: "p2", name: "read", arguments: { path: "README.md" } },
          ],
        },
        { content: "done" },
      ]);
      const frames = await fetchFrames(threadId("parallelframes"), []);

      const starts = frames.filter((f) => f.type === "TOOL_CALL_START");
      expect(starts.length, "both calls of the turn were announced").toBe(2);
      const parents = new Set(starts.map((f) => f.parentMessageId));
      expect(parents.size, "and one assistant message owns them both").toBe(1);
      expect([...parents][0], "the parent is a message the run actually opened").toBeTruthy();
      expect(last(types(frames)), "and the run still ends normally").toBe("RUN_FINISHED");
    },
  },
  {
    name: "no-chunk-frames-reach-the-client",
    // The client materialises complete messages; a CHUNK frame is the old
    // streaming shape and would arrive as an unknown type.
    run: async () => {
      script([{ reasoning: "r", content: "hello" }]);
      const frames = await fetchFrames(threadId("chunks"), []);

      expect(frames.some((f) => f.type.includes("CHUNK")), "no frame type carries CHUNK").toBe(false);
    },
  },
  {
    name: "an-error-run-is-well-formed",
    // A run that fails still owes the client a legal terminal pair. The failure
    // has to happen INSIDE the run -- a body the server cannot even parse is
    // rejected before any frame exists, so it proves nothing about the error path.
    // A resume naming an interrupt this process never parked does happen inside
    // it, and is exactly the sort of client mistake the edge has to survive.
    run: async () => {
      script([{ content: "unused" }]);
      const resp = await postRun(threadId("errframes"), [], {
        resume: [{ interruptId: "never-parked", status: "resolved" }],
      });
      const frames = framesFromSse(await resp.text());

      expect(frames.length, "a failed run still gets frames").toBeGreaterThan(0);
      expect(frames.every(valid), "and every one of them is schema-legal").toBe(true);
      expect(last(types(frames)), "and the run is terminally an error, not a broken stream").toBe("RUN_ERROR");
      expect(last(frames)?.message ?? "", "the error names what was wrong").toContain("unknown interrupt");
    },
  },
  {
    name: "the-wire-says-which-names-are-facts",
    // THE FACT FAMILY'S NAMES ARE WRITTEN IN TWO PROCESSES AND TWO LANGUAGES: the server
    // writes them (`harness.edge.mux/fact-types`) and the client classifies them
    // (`lib/mux.ts`'s `familyOf`), and nothing in either build compares the two lists. So
    // this asks the WIRE. A name the server starts writing and the client does not know is
    // not cosmetic: it falls through the client's routing into the RUN family, AG-UI's
    // schema refuses it, and the run that was drawing goes down.
    //
    // THE THREE FAMILIES ARE TOLD APART BY THEIR SHAPE, which is what lets this read the
    // wire without asking the client to classify first: the window speaks five lower-case
    // words, the fact family speaks `name/name`, and AG-UI's vocabulary is upper-case
    // (`lib/mux.ts` says exactly that where it routes). So everything on this socket that is
    // neither the window's nor upper-case is a fact -- or it is a name somebody has to add
    // on BOTH sides.
    run: async () => {
      const AG_UI_TYPE = /^[A-Z][A-Z0-9_]*$/;
      // Two model calls and one tool: the run that puts the most of the vocabulary on the wire.
      script([
        {
          reasoning: "先看一眼。",
          content: "",
          "tool-calls": [{ id: "c1", name: "read", arguments: { path: "deps.edn" } }],
        },
        { content: "看完了。" },
      ]);

      // A USER MESSAGE, BECAUSE A TURN OPENS WITH A PERSON'S WORDS (ADR 0006 decision 3): run
      // with an empty `append` and there is no turn to hear about, so the four names below come
      // back as two. BOTH HALVES OF THAT WERE RED FIRST: the empty `append` cost `turn/*`, and a
      // reader that hung up at RUN_FINISHED cost `turn/end`, which the server puts on the socket
      // after the frame that says the run is over (`readRun` in `../e2e` says why).
      const seen = await frameTypesFromRun(threadId("fact-names"), [
        { id: "u1", role: "user", content: "看看这个项目。" },
      ]);
      const facts = seen.filter((name) => familyOf(name) !== "window" && !AG_UI_TYPE.test(name)).sort();

      // NOT AN EMPTY QUESTION: such a run emits every fact name there is, so a name that went
      // missing shows up here as a missing entry rather than as a check with nothing to look at.
      expect(facts, `the fact family on the wire, as ${JSON.stringify(seen)}`).toEqual([
        "model/end",
        "model/start",
        "step/end",
        "step/start",
        "turn/end",
        "turn/start",
      ]);
      // AND THE CLIENT'S HALF: every one of them is a frame the page routes AWAY from
      // `@ag-ui/client`, which is the property a name added on the server alone loses.
      for (const name of facts) {
        expect(familyOf(name), `${name} must be the client's fact family`).toBe("fact");
      }
    },
  },
];

export const framesSuite: Suite = { name: "frames", cases };
