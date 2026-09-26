#!/usr/bin/env node
//
// MAKE ONE SCRIPTED SESSION'S RECORD LONG, without running a model 1024 times.
//
//   node .scratch/mobile-adaptation/evidence/long-session.mjs <session.jsonl> [calls]
//
// WHY THIS EXISTS. The strip's width trouble only shows on a session with BIG numbers -- a
// few thousand calls, a few million tokens -- and a scripted run that really made a thousand
// model calls would take a minute per hundred. The numbers the strip reads are folded from
// the RECORD (`harness.edge.stats/records->stats`), so a record that already says so is
// exactly as good as a run that did: this appends `model/start` / `model/end` frames in the
// shape `scripts/example.json`'s second turn produces, with a usage that a vendor could have
// reported (120k prompt, 102k of it cached, 900 out).
//
// IT IS FOR A TEMP HOME ONLY. The file it writes is the one `node scripts/dev.mjs --scripted`
// made and deletes; appending to anything else is editing a real session's history.
//
// ONE PAIR PER CALL, 4s apart, so the fold computes a rate and the calls have durations.
import fs from "node:fs";

const [file, count = "1024"] = process.argv.slice(2);
if (!file) {
  console.error("usage: long-session.mjs <session.jsonl> [calls]");
  process.exit(2);
}

const lines = fs
  .readFileSync(file, "utf8")
  .split("\n")
  .filter((line) => line.trim() !== "");
// THE LAST ROW GOES BACK LAST: the strip reads a log that may be being written, and
// `replay/read-records` drops a half-written final line -- so the RUN_FINISHED that ends this
// record is held out and put back after the calls, which leaves the log ending where it did.
const closing = lines.pop();
const lastTs = Math.max(...lines.map((line) => JSON.parse(line).ts ?? 0));
const runId = JSON.parse(lines[0]).runId ?? "appended";

const frame = (ts, name, value) =>
  JSON.stringify({
    ts,
    runId,
    type: "event",
    // NO ESCAPING OF THE SLASH: the frames `harness.kernel.event` writes spell it
    // `model\/start`, which is JSON for the one string `model/start` -- and `JSON.stringify`
    // writes that plain slash, which parses to the same thing. (Escaping it HERE would put a
    // literal backslash in the name, and the fold would not recognise the frame at all.)
    payload: { type: "CUSTOM", name, value },
  });

const start = {
  model: "scripted",
  "base-url": "http://offline.invalid/v1",
  "context-window": 128000,
};
const usage = {
  usage: {
    prompt_tokens: 120000,
    completion_tokens: 900,
    total_tokens: 120900,
    prompt_tokens_details: { cached_tokens: 102000 },
  },
};

const appended = [];
for (let i = 0; i < Number(count); i += 1) {
  const base = lastTs + i * 4000;
  appended.push(frame(base, "model/start", start));
  appended.push(frame(base + 4000, "model/end", usage));
}

fs.appendFileSync(file, `${[...appended, closing].join("\n")}\n`);
console.log(`${count} model calls appended to ${file}`);
