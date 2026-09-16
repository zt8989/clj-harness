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
//   <long> a name long enough to overflow a provider's function.name limit
//
// Environment knobs, so one file covers the failure modes too:
//
//   MCP_FAKE_BANNER=1   print a non-JSON line on stdout at startup (a server that
//                       pollutes the protocol stream -- named, never skipped)

const readline = require("readline");

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
    name: LONG_NAME,
    description: "A name too long to bridge.",
    inputSchema: { type: "object", properties: {} },
  },
];

if (process.env.MCP_FAKE_BANNER === "1") {
  process.stdout.write("fake mcp server starting\n");
}

function send(msg) {
  process.stdout.write(JSON.stringify(msg) + "\n");
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
    default:
      return { content: [{ type: "text", text: "no such tool: " + name }], isError: true };
  }
}

function handle(msg) {
  const { id, method, params } = msg;
  // A notification: no id, and nothing to answer.
  if (id === undefined || id === null) return;

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
      send({ jsonrpc: "2.0", id, result: { tools: TOOLS } });
      break;
    case "tools/call": {
      const answer = call(params.name, params.arguments || {});
      if (answer !== null) send({ jsonrpc: "2.0", id, result: answer });
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
