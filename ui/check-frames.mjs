import { EventSchemas } from "@ag-ui/core";

const url = process.env.HARNESS_URL ?? "http://localhost:8080/";

const res = await fetch(url, {
  method: "POST",
  headers: { "Content-Type": "application/json", Accept: "text/event-stream" },
  body: JSON.stringify({
    threadId: "probe-1",
    runId: "probe-1",
    messages: [{ id: "u1", role: "user", content: "hi" }],
    tools: [],
    context: [],
  }),
});

const text = await res.text();
const frames = text
  .split("\n")
  .filter((l) => l.startsWith("data:"))
  .map((l) => JSON.parse(l.slice(5).trim()));

let bad = 0;
for (const f of frames) {
  const r = EventSchemas.safeParse(f);
  if (!r.success) {
    bad++;
    for (const issue of r.error.issues) {
      console.log(`FAIL ${f.type}  path=${JSON.stringify(issue.path)}  ${issue.message}`);
    }
  }
}

console.log(`\n${frames.length} frames, ${bad} invalid`);
// exitCode rather than exit(): a hard process.exit() during fetch teardown trips a
// libuv assertion on Windows and turns the status into garbage.
process.exitCode = bad ? 1 : 0;
