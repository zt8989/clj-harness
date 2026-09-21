// A server asking the user something, end to end through the real client.
//
// TWO HALVES, and they are here together because they are the same promise seen
// from two sides: the WIRE half (a parked run whose interrupt says `elicitation`,
// with the question on it and the answer coming back through `resume`) and the
// FORM half (the rules that turn the server's schema into fields and the typed
// answers back into the JSON it asked for).
//
// The form half is pure functions rather than a rendered card: a dropped field
// and a number arriving as "42" are both invisible in a screenshot and both
// produce a form the server believes was answered. `lib/elicitation.ts` is where
// those rules live, and this is what checks them.
import { expect } from "vitest";

import { answersFor, fieldSpecs, inputKindFor, type FieldSpec } from "../../src/lib/elicitation";
import { type Case, type Suite, framesFromSse, homeDir, postRun, script, threadId, url } from "../e2e";
import fs from "node:fs";
import path from "node:path";

/// The fake MCP server this suite declares. A real process on a real pipe, the
/// same one the Clojure suite drives -- so what is being tested here is the wire
/// and the client, not a mock of either.
const FAKE_SERVER = path.resolve(import.meta.dirname, "..", "..", "..", "test", "harness", "cap", "fake_mcp_server.js");

/// A path as the CONTENTS of an EDN string. Not a nicety, and not a Windows
/// quirk to paper over: on Windows the path is `C:\Users\...`, and inside an EDN
/// string a backslash is an escape -- so the raw path is not a path at all, it
/// is `Unsupported escape character: \U`. The reader rejects the whole file, the
/// server has no `fake` to call, and the run ends with no question on it. From
/// the outside that failure reads as an assertion about a question, failing for
/// a reason the assertion never mentions.
function ednPath(p: string): string {
  return p.replace(/\\/g, "\\\\");
}

/// Declare it in the server's own configuration home. Written fresh because
/// `mcp.edn` is read on the way to every request, which is what lets a test set
/// a feature up mid-run.
function declareFakeServer(): void {
  fs.writeFileSync(
    path.join(homeDir(), "mcp.edn"),
    `{:servers {"fake" {:command "node ${ednPath(FAKE_SERVER)}"}}}`,
    "utf8",
  );
}

function undeclare(): void {
  const file = path.join(homeDir(), "mcp.edn");
  if (fs.existsSync(file)) fs.rmSync(file);
}

const ASK_SCHEMA = {
  type: "object",
  properties: {
    name: { type: "string" },
    age: { type: "number" },
    opt_in: { type: "boolean" },
    colour: { type: "string", enum: ["red", "green"] },
  },
};

function askScript(): void {
  script([
    {
      content: "",
      "tool-calls": [
        {
          id: "q1",
          name: "mcp__fake__ask",
          arguments: { message: "What is your name?", schema: ASK_SCHEMA },
        },
      ],
    },
    { content: "thanks" },
  ]);
}

function interruptsOf(body: string): readonly Record<string, unknown>[] {
  const finished = framesFromSse(body).filter((f) => f.type === "RUN_FINISHED");
  const outcome = finished[finished.length - 1]?.outcome as
    | { interrupts?: Record<string, unknown>[] }
    | undefined;
  return outcome?.interrupts ?? [];
}

const wireCase: Case = {
  name: "a-servers-question-parks-the-run-and-the-answer-finishes-it",
  run: async () => {
    declareFakeServer();
    try {
      const tid = threadId("elicit-wire");
      askScript();

      const first = await postRun(tid, [{ id: "u1", role: "user", content: "go" }]);
      const body = await first.text();
      const ints = interruptsOf(body);

      // A QUESTION, not an approval: the reason is the whole vocabulary a client
      // routes on, and it is what decides which card is drawn.
      expect(ints.length, "the run parked on one question").toBe(1);
      expect(ints[0].reason).toBe("elicitation");
      expect(ints[0].message).toBe("What is your name?");
      // The schema is NOT on the wire: the interrupt's shape is AG-UI's and is
      // strictly validated, so it comes from the harness's own endpoint.
      expect(ints[0].schema).toBeUndefined();

      const asked = await fetch(
        new URL(`api/elicitation?interruptId=${encodeURIComponent(String(ints[0].id))}`, url()),
      );
      expect(asked.status).toBe(200);
      const question = (await asked.json()) as { server: string; prompt: string; schema: unknown };
      expect(question.server).toBe("fake");
      expect(question.prompt).toBe("What is your name?");
      expect(question.schema).toEqual(ASK_SCHEMA);

      // The answer rides `resume`, and the call finishes with it.
      const resumed = await postRun(
        tid,
        [{ id: "u1", role: "user", content: "go" }],
        {
          resume: [
            {
              interruptId: String(ints[0].id),
              status: "resolved",
              payload: { name: "Ada", age: 36, opt_in: true, colour: "red" },
            },
            ],
        },
      );
      const resumedFrames = framesFromSse(await resumed.text());
      expect(resumedFrames[resumedFrames.length - 1]?.type, "the run finished").toBe("RUN_FINISHED");
      const result = resumedFrames.find((f) => f.type === "TOOL_CALL_RESULT");
      expect(JSON.stringify(result?.content)).toContain("Ada");

    } finally {
      undeclare();
    }
  },
};

const formCases: readonly Case[] = [
  {
    name: "the-four-kinds-get-the-input-they-are",
    run: async () => {
      const fields = fieldSpecs(ASK_SCHEMA);
      const byName = new Map(fields.map((f) => [f.name, f]));
      expect(inputKindFor(byName.get("name") as FieldSpec)).toBe("text");
      expect(inputKindFor(byName.get("age") as FieldSpec)).toBe("number");
      expect(inputKindFor(byName.get("opt_in") as FieldSpec)).toBe("select");
      expect(inputKindFor(byName.get("colour") as FieldSpec)).toBe("select");
      expect(byName.get("colour")?.enumValues).toEqual(["red", "green"]);
    },
  },
  {
    name: "answers-keep-the-type-the-schema-declared",
    run: async () => {
      const fields = fieldSpecs(ASK_SCHEMA);
      const answers = answersFor(fields, { name: "Ada", age: "36", opt_in: "true", colour: "red" });
      expect(answers).toEqual({ name: "Ada", age: 36, opt_in: true, colour: "red" });
      // A number is a NUMBER, not the string that was typed -- sending "36"
      // would be answering a different question.
      expect(typeof answers.age).toBe("number");
      expect(typeof answers.opt_in).toBe("boolean");
    },
  },
  {
    name: "a-field-nobody-expected-is-kept-and-named",
    run: async () => {
      const schema = {
        type: "object",
        properties: {
          when: { type: "string", format: "date-time" },
          payload: { type: "object" },
          list: { type: "array" },
        },
      };
      const fields = fieldSpecs(schema);
      // KEPT, every one of them: a form that dropped what it did not recognise
      // would look answered while missing half the answers.
      expect(fields.map((f) => f.name).sort()).toEqual(["list", "payload", "when"]);
      // And each says what the schema called it, so the card can show that.
      expect(fields.map((f) => f.kind).sort()).toEqual(["array", "object", "string"]);
      expect(inputKindFor(fields[0] as FieldSpec)).toBe("text");
      const answers = answersFor(fields, { when: "2026-01-01", payload: "{}", list: "a,b" });
      expect(Object.keys(answers).sort()).toEqual(["list", "payload", "when"]);
    },
  },
  {
    name: "an-empty-or-odd-schema-is-an-empty-form-and-not-a-crash",
    run: async () => {
      // A question with no fields is still a question -- the person can accept
      // it -- but it must not throw on the way to being drawn.
      for (const schema of [undefined, null, {}, { type: "object" }, { properties: 42 }]) {
        expect(fieldSpecs(schema)).toEqual([]);
      }
      expect(answersFor([], {})).toEqual({});
    },
  },
];

export const elicitationSuite: Suite = {
  name: "elicitation",
  cases: [wireCase, ...formCases],
};
