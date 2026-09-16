#!/usr/bin/env node
// A fake MCP server on stdio, for the Clojure suite.
//
// WHAT IT IS: the smallest thing that speaks the protocol well enough to be
// indistinguishable from a real server, so the tests exercise OUR client -- the
// spawn, the handshake, the roster, the call -- rather than a mock of one. It is
// a real process on a real pipe, which is the only way to test the parts that
// only exist because a server is somebody else's program.
//
// WHY NODE, in a repo whose backend is Clojure: what is being faked is an
// EXTERNAL PROCESS, and the language a fake external process is written in has
// nothing to do with what is being tested. Node is already a prerequisite of this
// repository (the UI and its end-to-end suite), so this adds no new requirement.
//
// ITS TOOLS ARE DELIBERATELY UNHELPFUL IN SPECIFIC WAYS. Each one exists to make
// one behaviour of the client observable:
//
//   echo   answers with the text it was given  -- arguments really arrive
//   where  answers with its own cwd             -- it was started in the project
//   fail   answers with isError: true           -- a refused call is a tool ERROR
//   hang   never answers                        -- a request must time out
//   exit   dies without answering               -- the server going away mid-call
//   ask    asks the USER something first        -- elicitation, the whole point of
//          (its arguments carry the message and the requested schema, and the
//           answer it gets back is what this tool returns, so a test can read
//           exactly what the client sent)
//   <long> a name long enough to overflow a provider's function.name limit
//
// Environment knobs, so one file covers the failure modes too:
//
//   MCP_FAKE_BANNER=1         print a non-JSON line on stdout at startup (a server
//                             that pollutes the protocol stream -- named, never skipped)
//   MCP_FAKE_ROSTER_FILE=path read extra tool names from this file on every
//                             tools/list, so a roster can CHANGE between
//                             connections and a re-list is observable
//   MCP_FAKE_LIFECYCLE=path   append start/term/exit lines here, so a test can see
//                             that a process really was started and really is gone

const readline = require("readline");
const fs = require("fs");

const lifecycle = process.env.MCP_FAKE_LIFECYCLE;

function note(event) {
  if (!lifecycle) return;
  try {
    fs.appendFileSync(lifecycle, event + " " + process.pid + "\n");
  } catch (e) {
    // A diagnostic that cannot be written is not worth dying over.
  }
}

note("start");
process.on("SIGTERM", () => {
  note("term");
  process.exit(0);
});
process.on("exit", () => note("exit"));

// 60 characters: `mcp__fake__` is 12, so the bridged name is 72 and a provider
// that allows 64 must refuse it rather than truncate it.
const LONG_NAME = "x".repeat(60);

const TOOLS = [
  {
    name: "echo",
    description: "Answer with the text it was given.",
    inputSchema: {
      type: "object",
      properties: { text: { type: "string", description: "Anything." } },
      required: ["text"],
    },
  },
  {
    name: "where",
    description: "Answer with this server's working directory.",
    inputSchema: { type: "object", properties: {} },
  },
  {
    name: "fail",
    description: "Answer with a failure.",
    inputSchema: { type: "object", properties: {} },
  },
  {
    name: "hang",
    description: "Never answer.",
    inputSchema: { type: "object", properties: {} },
  },
  {
    name: "exit",
    description: "Go away without answering.",
    inputSchema: { type: "object", properties: {} },
  },
  {
    name: "ask",
    description: "Ask the user something, then answer with what they said.",
    inputSchema: {
      type: "object",
      properties: {
        message: { type: "string" },
        schema: { type: "object" },
      },
    },
  },
  {
    name: LONG_NAME,
    description: "A name too long to bridge.",
    inputSchema: { type: "object", properties: {} },
  },
];

/// The roster this server offers RIGHT NOW: the fixed tools plus whatever the
/// roster file names. Read per tools/list rather than at startup, so a test can
/// change the set and see whether a reconnect re-lists.
function roster() {
  const file = process.env.MCP_FAKE_ROSTER_FILE;
  if (!file) return TOOLS;
  let extra = [];
  try {
    extra = fs
      .readFileSync(file, "utf8")
      .split("\n")
      .map((s) => s.trim())
      .filter((s) => s !== "");
  } catch (e) {
    extra = [];
  }
  return TOOLS.concat(
    extra.map((name) => ({
      name,
      description: "From the roster file.",
      inputSchema: { type: "object", properties: {} },
    }))
  );
}

if (process.env.MCP_FAKE_BANNER === "1") {
  process.stdout.write("fake mcp server starting\n");
}

function send(msg) {
  process.stdout.write(JSON.stringify(msg) + "\n");
}

// ONE QUESTION AT A TIME, and the tools/call it belongs to is remembered. MCP
// gives an elicitation no correlation id, so a real server correlates the same
// way this does: it is holding exactly one call open while it asks.
let nextElicitationId = 9000;
const waiting = [];

function elicit(args) {
  const id = nextElicitationId++;
  send({
    jsonrpc: "2.0",
    id,
    method: "elicitation/create",
    params: {
      message: String(args.message || "What?"),
      requestedSchema: args.schema || { type: "object", properties: {} },
    },
  });
  return id;
}

function text(s) {
  return { content: [{ type: "text", text: s }] };
}

function call(name, args) {
  switch (name) {
    case "echo":
      return text("echo: " + String(args.text));
    case "where":
      return text(process.cwd());
    case "fail":
      return { content: [{ type: "text", text: "the fake server refused" }], isError: true };
    case "hang":
      return null; // never answers
    case "ask":
      // Answers LATER, when the user's answer comes back -- see the handler.
      return { elicit: elicit(args) };
    case "exit":
      // Goes away mid-call: no answer, no goodbye. This is what a server that
      // crashed looks like from the client's side.
      process.exit(0);
      return null;
    default:
      return { content: [{ type: "text", text: "no such tool: " + name }], isError: true };
  }
}

function handle(msg) {
  const { id, method, params, result } = msg;
  // A notification: no id, and nothing to answer.
  if (id === undefined || id === null) return;

  // THE ANSWER TO A QUESTION WE ASKED: finish the call that was waiting on it,
  // and answer THAT with what the user said -- so the client's tool result is a
  // faithful copy of what it sent us, which is how a test reads it back.
  if (method === undefined && result !== undefined) {
    const at = waiting.findIndex((w) => w.elicitId === id);
    if (at !== -1) {
      const [w] = waiting.splice(at, 1);
      send({
        jsonrpc: "2.0",
        id: w.callId,
        result: {
          content: [
            { type: "text", text: JSON.stringify({ action: result.action, content: result.content || null }) },
          ],
          isError: result.action !== "accept",
        },
      });
    }
    return;
  }

  switch (method) {
    case "initialize":
      send({
        jsonrpc: "2.0",
        id,
        result: {
          protocolVersion: "2025-06-18",
          capabilities: { tools: {} },
          serverInfo: { name: "fake", version: "1" },
        },
      });
      break;
    case "tools/list":
      send({ jsonrpc: "2.0", id, result: { tools: roster() } });
      break;
    case "tools/call": {
      const answer = call(params.name, params.arguments || {});
      if (answer === null) break;
      if (answer.elicit !== undefined) {
        // Hold this call until the answer to that question arrives.
        waiting.push({ callId: id, elicitId: answer.elicit });
        break;
      }
      send({ jsonrpc: "2.0", id, result: answer });
      break;
    }
    default:
      send({
        jsonrpc: "2.0",
        id,
        error: { code: -32601, message: "no such method: " + method },
      });
  }
}

readline.createInterface({ input: process.stdin }).on("line", (line) => {
  if (line.trim() === "") return;
  let msg;
  try {
    msg = JSON.parse(line);
  } catch (e) {
    return; // a client that sent junk gets silence, like any server would
  }
  handle(msg);
});
