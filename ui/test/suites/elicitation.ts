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
//
// WAIT -- "A SERVER", in every paragraph above? Not any more, and the second wire
// case is the reason: the SAME chain now carries a question from one of this
// harness's own tools (`ask`), which is why the card's title is a case of its own
// at the bottom. A person answering should know who wants to know, and the two
// askers are told apart by the endpoint rather than guessed at by the card.
import { EventSchemas } from "@ag-ui/core";
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

      const first = await postRun(tid, "r1", [{ id: "u1", role: "user", content: "go" }]);
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
        "r2",
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

/// ---------------------------------------------------------------- a tool asking
///
/// THE SAME CHAIN, A DIFFERENT ASKER. `ask` is one of this harness's own tools, so
/// there is no MCP server anywhere in this case -- the scripted model calls `ask`
/// and everything after that is the harness. What it checks that the case above
/// cannot: that a question from a BUILT-IN reaches the same card, and that the card
/// can say who is asking.

function builtinAskScript(): void {
  script([
    {
      content: "",
      "tool-calls": [
        {
          id: "q1",
          name: "ask",
          arguments: {
            questions: [
              { key: "port", question: "Which port should it listen on?" },
              { key: "tests", question: "Should I update the tests too?" },
            ],
          },
        },
      ],
    },
    { content: "thanks" },
  ]);
}

const ASK_LINE = "Which port should it listen on? / Should I update the tests too?";

const askWireCase: Case = {
  name: "a-tools-question-parks-the-run-and-says-who-is-asking",
  run: async () => {
    const tid = threadId("elicit-ask");
    builtinAskScript();

    const first = await postRun(tid, "r1", [{ id: "u1", role: "user", content: "go" }]);
    const body = await first.text();
    const frames = framesFromSse(body);
    const ints = interruptsOf(body);

    // The frame the card is drawn from, and it is a QUESTION: the reason is the
    // whole vocabulary a client routes on.
    expect(ints.length, "the run parked on one question").toBe(1);
    expect(ints[0].reason).toBe("elicitation");
    expect(ints[0].toolCallId).toBe("q1");
    expect(ints[0].message).toBe(ASK_LINE);
    // And no result was reported for the call: it stopped rather than ran.
    expect(frames.filter((f) => f.type === "TOOL_CALL_RESULT"), "the call did not run").toEqual([]);

    // The interrupt's shape is AG-UI's and strictly validated, so the frame has to
    // satisfy the SHIPPED schema -- an invalid one is a hard client failure, not a
    // cosmetic one (see suites/frames.ts).
    const bad = frames.filter((f) => !EventSchemas.safeParse(f).success);
    expect(bad.map((f) => f.type), "every frame passes EventSchemas").toEqual([]);

    const asked = await fetch(
      new URL(`api/elicitation?interruptId=${encodeURIComponent(String(ints[0].id))}`, url()),
    );
    expect(asked.status).toBe(200);
    const question = (await asked.json()) as {
      prompt: string;
      schema: { properties: Record<string, { description?: string }> };
      askedBy?: string;
      server?: string;
    };
    expect(question.prompt).toBe(ASK_LINE);
    expect(question.askedBy).toBe("model");
    // NO SERVER KEY AT ALL -- absent, not null. The card reads presence to tell
    // "nobody named themselves" from "a server whose name is null".
    expect("server" in question, "no server is claimed for a tool's question").toBe(false);
    expect(
      Object.fromEntries(
        Object.entries(question.schema.properties).map(([k, v]) => [k, v.description]),
      ),
    ).toEqual({
      port: "Which port should it listen on?",
      tests: "Should I update the tests too?",
    });

    const resumed = await postRun(
      tid,
      "r2",
      [{ id: "u1", role: "user", content: "go" }],
      {
        resume: [
          { interruptId: String(ints[0].id), status: "resolved", payload: { port: "8080" } },
        ],
      },
    );
    const resumedFrames = framesFromSse(await resumed.text());
    expect(resumedFrames[resumedFrames.length - 1]?.type, "the run finished").toBe("RUN_FINISHED");
    const result = resumedFrames.find((f) => f.type === "TOOL_CALL_RESULT");
    // The answers, each on the line of the question that asked for it -- and the
    // one they skipped says so instead of going missing.
    expect(result?.content).toContain("- Which port should it listen on? -> 8080");
    expect(result?.content).toContain("- Should I update the tests too? -> (no answer)");
  },
};

/// ------------------------------------------------------------------- next door
///
/// WHAT THE CARD SAYS ABOUT WHO IS ASKING has its own suite, because it RENDERS a
/// component and this file is TypeScript without JSX (`suites/elicitation-card.tsx`).
/// The same split `sidebar.tsx` holds: the rules and the wire are checked here, and
/// the pixels-adjacent question -- does the line draw, and does it say the right
/// thing -- is checked where a component can be rendered to a string.

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
  {
    name: "candidates-become-a-choice-and-the-own-words-box-is-not-assumed",
    run: async () => {
      // THE ASKING SIDE'S WORDS, DRIVEN VERBATIM. A candidate is a thing the answer is
      // matched against, so a trimmed or reordered list would be offering a different
      // question than the one the model is comparing against -- and a candidate with a
      // comma inside it is one candidate, which is why nothing here joins or splits.
      const fields = fieldSpecs({
        type: "object",
        properties: {
          db: { type: "string", enum: ["香港 分行", "a, b", "sqlite"], description: "Which?" },
        },
      });
      const db = fields[0] as FieldSpec;
      expect(inputKindFor(db)).toBe("select");
      expect(db.enumValues).toEqual(["香港 分行", "a, b", "sqlite"]);

      // NOT OFFERED UNLESS THE FORM ASKED. The same rules draw a server's own form from
      // the server's schema, so a "write your own" box grown under every enum somebody
      // else declared would be this client editing their question.
      expect(db.allowOther, "no extension key means no own-words box").toBeUndefined();

      // Choosing one answer answers with that word, exactly.
      expect(answersFor(fields, { db: "a, b" })).toEqual({ db: "a, b" });
      // And left alone, a choice sends the empty string it holds -- the existing
      // "this one was not answered" the asking side already knows how to read.
      expect(answersFor(fields, {})).toEqual({ db: "" });
    },
  },
  {
    name: "a-question-may-be-answered-in-the-persons-own-words",
    run: async () => {
      const db = fieldSpecs({
        type: "object",
        properties: { db: { type: "string", enum: ["postgres", "sqlite"], "x-allow-other": true } },
      })[0] as FieldSpec;
      expect(db.allowOther).toBe(true);

      // THE OWN WORDS WIN, AND THE KEY HOLDS ONE VALUE. Sending the pick and the typed
      // answer together would put two answers to one question in the payload and give
      // the person no way to see which of them they had sent.
      expect(answersFor([db], { db: "postgres" }, { db: "mysql 8" })).toEqual({ db: "mysql 8" });
      // With nothing typed, the pick stands -- the box being there is not an answer.
      expect(answersFor([db], { db: "postgres" }, { db: "" })).toEqual({ db: "postgres" });

      // AND A FIELD THAT NEVER OFFERED IT IGNORES WHATEVER IS IN THERE, so a stray entry
      // cannot answer a question that was not asked that way.
      const server = fieldSpecs({
        type: "object",
        properties: { db: { type: "string", enum: ["postgres"] } },
      })[0] as FieldSpec;
      expect(answersFor([server], { db: "postgres" }, { db: "mine" })).toEqual({ db: "postgres" });
    },
  },
  {
    name: "several-answers-are-a-list-and-never-a-joined-string",
    run: async () => {
      const field = fieldSpecs({
        type: "object",
        properties: {
          targets: {
            type: "array",
            items: { type: "string", enum: ["api", "web", "docs"] },
            "x-allow-other": true,
          },
        },
      })[0] as FieldSpec;

      // AN ARRAY IS RECOGNISED BEFORE THE ENUM, and this is why it has to be: a multiple
      // choice's candidates live one level down on `items`, so a spec that had folded
      // them into `enumValues` would be drawn as a select and lose every answer but one.
      expect(inputKindFor(field)).toBe("checkboxes");
      expect(field.itemValues).toEqual(["api", "web", "docs"]);
      expect(field.enumValues, "the candidates are not a single-choice enum").toBeUndefined();
      expect(field.allowOther).toBe(true);

      // ORDER COMES FROM THE OPTIONS, NOT FROM THE CLICKS: the same ticks are the same
      // answer whichever way round they were clicked. A joined string would come apart
      // here, and it would come apart silently -- a candidate may contain a comma.
      expect(answersFor([field], { targets: ["web", "api"] })).toEqual({ targets: ["api", "web"] });
      // ONE TICK IS STILL A LIST: a scalar would be answering a different question.
      expect(answersFor([field], { targets: ["docs"] })).toEqual({ targets: ["docs"] });
      // TICKING NOTHING IS AN ANSWER, and the key is still sent as an empty list -- what
      // `ask` tells apart is `[]` ARRIVING versus the key being absent from the payload
      // altogether, and only the second one means "not answered" (the backend suite
      // holds both, and the result the model reads spells them differently).
      expect(answersFor([field], { targets: [] })).toEqual({ targets: [] });
      expect(answersFor([field], {})).toEqual({ targets: [] });
      // AND THE PERSON'S OWN WORDS ARE ONE MORE ITEM, not the list stringified into a
      // sentence -- "these, and also this" is a thing a person means.
      expect(answersFor([field], { targets: ["api"] }, { targets: "neither" })).toEqual({
        targets: ["api", "neither"],
      });
    },
  },
];

export const elicitationSuite: Suite = {
  name: "elicitation",
  // The WIRE cases first -- they are the ones that boot the harness -- and the rule
  // cases after them, which read as what they are: checks that need nothing.
  cases: [wireCase, askWireCase, ...formCases],
};
