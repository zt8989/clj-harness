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

import { type Case, type Frame, type Suite, fetchFrames, framesFromSse, postRun, script, threadId } from "../e2e";

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
      const frames = await fetchFrames(threadId("frames"), "r1", []);

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
      const frames = await fetchFrames(threadId("reasoning"), "r1", []);

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
      const frames = await fetchFrames(threadId("toolframes"), "r1", []);

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
    name: "no-chunk-frames-reach-the-client",
    // The client materialises complete messages; a CHUNK frame is the old
    // streaming shape and would arrive as an unknown type.
    run: async () => {
      script([{ reasoning: "r", content: "hello" }]);
      const frames = await fetchFrames(threadId("chunks"), "r1", []);

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
      const resp = await postRun(threadId("errframes"), "r1", [], {
        resume: [{ interruptId: "never-parked", status: "resolved" }],
      });
      const frames = framesFromSse(await resp.text());

      expect(frames.length, "a failed run still gets frames").toBeGreaterThan(0);
      expect(frames.every(valid), "and every one of them is schema-legal").toBe(true);
      expect(last(types(frames)), "and the run is terminally an error, not a broken stream").toBe("RUN_ERROR");
      expect(last(frames)?.message ?? "", "the error names what was wrong").toContain("unknown interrupt");
    },
  },
];

export const framesSuite: Suite = { name: "frames", cases };
