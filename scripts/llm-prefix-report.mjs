#!/usr/bin/env node
// llm-prefix-report -- why did the vendor's prefix cache miss on this call?
//
// WHAT IT ANSWERS, and why the answer needs a program instead of an eye:
//   * per call: prompt / cached / rate, and the rate against BOTH denominators --
//     `cached/prompt` (the session-level number, dragged down by the cold first call)
//     and `cached/prevPrompt` (how much of the prefix that WAS available the vendor
//     actually recognised). Reading only the first makes a healthy session look broken.
//   * per call: how many BYTES of the previous request this one shares, and where the
//     first divergence is, with context.
//   * across sessions: the fingerprint of every tool table a session wrote, which
//     sessions changed their table MID-SESSION, and the first tool/field that differs
//     between two tables.
//
// IT COMPARES THE RAW BYTES, NEVER A RE-SERIALIZATION. harness.infra.llm-debug's
// namespace docstring (src/harness/infra/llm_debug.clj:15-20) is the argument: the
// request is logged as the exact string that went on the wire, because the prefix cache
// keys on BYTES, and an analysis that parsed the body into a map and stringified it
// again would be comparing a second spelling -- key order, the empty reasoning_content
// pad, the tool table's order all free to differ -- so 'the prefix was identical' would
// stop being a fact about the wire. So: JSON.parse the LINE (to reach the `body`
// STRING), and then treat that string as bytes. Never JSON.parse the body.
//
// IT IS READ-ONLY. It never writes anywhere, and it never opens harness.db. Pointing it
// at the real home is a READ of it. See .scratch/llm-prefix-cache/issues/04.
//
// USAGE
//   node scripts/llm-prefix-report.mjs                       # the live log + this project's records
//   node scripts/llm-prefix-report.mjs --log <path>
//   node scripts/llm-prefix-report.mjs --records <dir>       # session records (projects/<slug>/)
//   node scripts/llm-prefix-report.mjs --no-records          # the traffic log only
//   node scripts/llm-prefix-report.mjs --assert-stable       # exit 1 if a session's table drifted mid-session
//   node scripts/llm-prefix-report.mjs --require-identical   # exit 1 unless every session wrote ONE table
//
// `--assert-stable` is the invariant that is ALWAYS a bug: a table that changes between
// two calls of one session costs that session its whole prefix, conversation included.
// `--require-identical` is the stricter question -- 'is every session on this account
// byte-identical' -- which is only true when every session shares one configuration
// (editing mode, MCP roster), so it is opt-in rather than the default gate.

import { readFileSync, readdirSync, existsSync, statSync } from "node:fs";
import { createHash } from "node:crypto";
import { homedir } from "node:os";
import { join } from "node:path";

// ---------------------------------------------------------------- raw JSON access
//
// The body is a STRING in the log line, and it is the wire bytes. These two helpers pull
// a top-level field out of that string WITHOUT parsing it, so what comes back is exactly
// the bytes the vendor saw.
//
// The key scan is safe against matching inside a string VALUE: inside a JSON string a
// quote is always escaped (`\"`), so the only unescaped `"messages":` is a real key.

function valueEnd(text, i) {
  while (i < text.length && /\s/.test(text[i])) i++;
  const c = text[i];
  if (c === '"') {
    i++;
    while (i < text.length) {
      if (text[i] === "\\") { i += 2; continue; }
      if (text[i] === '"') return i + 1;
      i++;
    }
    throw new Error("unterminated string in a logged body");
  }
  if (c === "[" || c === "{") {
    const open = c;
    const close = c === "[" ? "]" : "}";
    let depth = 0;
    while (i < text.length) {
      if (text[i] === '"') { i = valueEnd(text, i); continue; }
      if (text[i] === open) depth++;
      else if (text[i] === close) { depth--; if (depth === 0) return i + 1; }
      i++;
    }
    throw new Error("unbalanced braces in a logged body");
  }
  let j = i;
  while (j < text.length && !/[,\]}\s]/.test(text[j])) j++;
  return j;
}

function rawField(text, key) {
  const needle = `"${key}":`;
  const at = text.indexOf(needle);
  if (at === -1) return null;
  const start = at + needle.length;
  return text.slice(start, valueEnd(text, start));
}

// ------------------------------------------------------------------------ helpers

const sha = (s) => createHash("sha256").update(s, "utf8").digest("hex").slice(0, 12);

function sharedPrefix(a, b) {
  const n = Math.min(a.length, b.length);
  let i = 0;
  while (i < n && a.charCodeAt(i) === b.charCodeAt(i)) i++;
  return i;
}

function context(text, at, span = 120) {
  const from = Math.max(0, at - span);
  const to = Math.min(text.length, at + span);
  const cut = (s) => s.replace(/\s+/g, " ").slice(0, 220);
  return { before: cut(text.slice(from, at)), after: cut(text.slice(at, to)) };
}

function rawUsageCheck(response, telemetry) {
  // THE FOLDED READING AND THE BYTES IT CAME FROM, put side by side. The log holds both
  // (see the docstring in src/harness/infra/llm_debug.clj): `:body` is the raw SSE frame
  // text and `:telemetry` is what the fold made of it. A disagreement means the fold lost
  // or moved a number, which is the one failure a folded map cannot show on its own --
  // 'the vendor never sent cached_tokens' and 'we dropped it' look identical there.
  //
  // Null (and so silence) for a line written before the response was logged raw: there is
  // nothing to compare, and inventing a verdict would be worse than saying nothing.
  const raw = response?.body;
  if (typeof raw !== "string") return null;
  let usage = null;
  for (const line of raw.split("\n")) {
    if (!line.startsWith("data:")) continue;
    const payload = line.slice(5).trim();
    if (!payload || payload === "[DONE]") continue;
    try {
      const frame = JSON.parse(payload);
      if (frame && typeof frame.usage === "object" && frame.usage !== null) usage = frame.usage;
    } catch {
      // A partial or non-JSON frame is not evidence about usage; keep looking.
    }
  }
  const pick = (u) =>
    JSON.stringify([
      u?.prompt_tokens ?? null,
      u?.prompt_tokens_details?.cached_tokens ?? null,
    ]);
  const where = usage
    ? `${usage.prompt_tokens ?? "-"} / ${usage.prompt_tokens_details?.cached_tokens ?? "-"}`
    : "no usage in the raw text";
  return pick(usage) === pick(telemetry?.usage ?? null)
    ? `agrees (${where})`
    : `MISMATCH raw=${pick(usage)} folded=${pick(telemetry?.usage ?? null)}`;
}

const pct = (num, den) => (den ? ((num / den) * 100).toFixed(1) : "-");

function slugOf(dir) {
  return dir.replace(/\//g, "_");
}

function mainRepoSlug(cwd) {
  // A LINKED WORKTREE HAS `.git` AS A FILE, not a directory, and it points at the main
  // checkout's `.git/worktrees/<name>`. Sessions run in a worktree are recorded under the
  // worktree's own slug -- but the interesting ones are usually the main checkout's, and
  // running this from a worktree is exactly what a prefix-stability change looks like. So
  // the fallback follows the gitdir line rather than guessing at a name.
  try {
    const dotGit = join(cwd, ".git");
    if (!statSync(dotGit).isFile()) return null;
    const m = /^gitdir:\s*(.+)$/m.exec(readFileSync(dotGit, "utf8"));
    if (!m) return null;
    const gitdir = m[1].trim();
    const main = gitdir.replace(/\/\.git\/worktrees\/[^/]+\/?$/, "");
    return main === gitdir ? null : slugOf(main);
  } catch {
    return null;
  }
}

function canonical(value) {
  // Keys sorted at EVERY level. The obvious `JSON.stringify(v, Object.keys(v).sort())`
  // is NOT this: a replacer ARRAY applies at every level of the walk, so it filters the
  // nested objects too -- each tool came out as `{}` and the "fingerprint" degraded into
  // a hash of the array LENGTH, which is why this file once called eleven different
  // tables six.
  if (Array.isArray(value)) return value.map(canonical);
  if (value && typeof value === "object") {
    const out = {};
    for (const k of Object.keys(value).sort()) out[k] = canonical(value[k]);
    return out;
  }
  return value;
}

function toolFingerprint(tools) {
  // Our OWN hashing of a parsed map, which is fine and must not be confused with
  // evidence: it groups two tables that SAY the same thing however they were written.
  // Every claim about the wire in this report comes from the raw strings instead.
  return sha(JSON.stringify(canonical(tools)));
}

function firstTableDifference(a, b) {
  const names = (ts) => ts.map((t) => t?.function?.name);
  const an = names(a);
  const bn = names(b);
  const bIndex = new Map(bn.map((n, i) => [n, i]));
  for (let i = 0; i < an.length; i++) {
    if (!bIndex.has(an[i])) return `tool ${an[i]} is only in the first table`;
    if (bIndex.get(an[i]) !== i) {
      return `order: ${an[i]} is #${i + 1} in the first table and #${bIndex.get(an[i]) + 1} in the second`;
    }
    for (const field of ["description", "parameters", "type"]) {
      const av = JSON.stringify(a[i]?.function?.[field]);
      const bv = JSON.stringify(b[bIndex.get(an[i])]?.function?.[field]);
      if (av !== bv) {
        const where = i > 0 ? ` (right after ${an[i - 1]})` : " (the first tool)";
        return `field: ${an[i]}.${field}${where}\n      only in first : ${(av ?? "undefined").slice(0, 300)}\n      only in second: ${(bv ?? "undefined").slice(0, 300)}`;
      }
    }
  }
  if (bn.length !== an.length) return `${bn.length - an.length} more tool(s) in the second table`;
  return "no difference (same bytes after canonical hashing)";
}

// -------------------------------------------------------------------------- the log

function readLog(path) {
  const lines = readFileSync(path, "utf8").split("\n").filter((l) => l.trim());
  return lines.map((l) => JSON.parse(l));
}

function reportLog(entries) {
  const threads = new Map();
  for (const e of entries) {
    const id = e["thread-id"] ?? "(no thread)";
    if (!threads.has(id)) threads.set(id, []);
    threads.get(id).push(e);
  }
  let worst = 0;
  for (const [id, es] of threads) {
    const requests = es.filter((e) => e.at === "request");
    const byCall = requests.map((r) => {
      const response = es.find((e) => e.at === "response" && e.ts > r.ts) ?? null;
      return {
        body: r.body,
        model: r.model,
        ts: r.ts,
        response,
        telemetry: response?.telemetry ?? null,
      };
    });
    console.log(`\n=== session ${id} -- ${byCall.length} call(s)`);
    let sumCached = 0;
    let sumPrompt = 0;
    let prevMessages = null;
    let prevPrompt = null;
    byCall.forEach((c, i) => {
      const u = c.telemetry?.usage ?? {};
      const prompt = u.prompt_tokens;
      const cached = u.prompt_tokens_details?.cached_tokens;
      const messages = rawField(c.body, "messages") ?? "";
      const tools = rawField(c.body, "tools") ?? "";
      let share = "-";
      let diverge = "-";
      if (prevMessages !== null) {
        // The previous array minus its closing `]`: this call should begin with exactly
        // that, because a call appends to the conversation and changes nothing behind it.
        const stem = prevMessages.slice(0, -1);
        const shared = sharedPrefix(stem, messages);
        const ok = shared === stem.length;
        share = `${shared}/${stem.length} bytes${ok ? " (byte-identical prefix)" : ""}`;
        if (!ok) {
          const ctx = context(messages, shared);
          diverge = `at byte ${shared}\n      before: ...${ctx.before}\n      after : ${ctx.after}...`;
        }
      }
      if (typeof prompt === "number") {
        sumPrompt += prompt;
        if (typeof cached === "number") sumCached += cached;
      }
      console.log(
        `  call ${i + 1}  prompt=${prompt ?? "-"}  cached=${cached ?? "-"}` +
          `  cached/prompt=${pct(cached, prompt)}%` +
          `  cached/prevPrompt=${pct(cached, prevPrompt)}%` +
          `  tools_sha=${sha(tools)}`
      );
      console.log(`         prefix: ${share}`);
      const raw = rawUsageCheck(c.response, c.telemetry);
      if (raw) console.log(`         raw vs folded usage: ${raw}`);
      if (diverge !== "-") console.log(`         FIRST DIVERGENCE ${diverge}`);
      prevMessages = messages;
      if (typeof prompt === "number") prevPrompt = prompt;
    });
    if (sumPrompt) {
      console.log(`  ${"-".repeat(60)}`);
      console.log(
        `  session: cached=${sumCached} prompt=${sumPrompt} -> ${pct(sumCached, sumPrompt)}%` +
          `   (call 1 alone: ${pct(
            byCall[0]?.telemetry?.usage?.prompt_tokens_details?.cached_tokens,
            byCall[0]?.telemetry?.usage?.prompt_tokens
          )}%)`
      );
    }
    worst = Math.max(worst, sumPrompt ? 0 : 0);
  }
  return threads.size;
}

// ---------------------------------------------------------------------- the records
//
// The tool table a session actually sent lives in the session record, not in the traffic
// log: every `model/start` frame carries the resolved `:tools` that went out. Comparing
// fingerprints across records is how the cross-process half of 'the table is stable'
// gets EVIDENCE rather than a synthetic test.

const MODEL_START = '"model\\/start"';

function readRecords(dir) {
  if (!existsSync(dir)) return [];
  const out = [];
  for (const file of readdirSync(dir)) {
    if (!file.endsWith(".jsonl")) continue;
    const path = join(dir, file);
    let first = null;
    let last = null;
    let calls = 0;
    for (const line of readFileSync(path, "utf8").split("\n")) {
      if (!line.includes(MODEL_START)) continue;
      let d;
      try { d = JSON.parse(line); } catch { continue; }
      if (d.kind !== "model/start") continue;
      const tools = d.payload?.tools;
      if (!tools) continue;
      calls++;
      if (!first) first = tools;
      last = tools;
    }
    if (first) out.push({ file, calls, first, last, drifted: sha(JSON.stringify(first)) !== sha(JSON.stringify(last)) });
  }
  return out;
}

function reportRecords(records) {
  if (!records.length) {
    console.log("\n=== no session records found (pass --records <dir>, or --no-records)");
    return { drifted: 0, distinct: 0 };
  }
  console.log(`\n=== tool tables across ${records.length} session(s) with a recorded table`);
  const groups = new Map();
  let drifted = 0;
  for (const r of records) {
    const fp = toolFingerprint(r.first);
    if (!groups.has(fp)) groups.set(fp, []);
    groups.get(fp).push(r);
    if (r.drifted) drifted++;
  }
  for (const [fp, rs] of [...groups].sort((a, b) => b[1].length - a[1].length)) {
    const names = rs[0].first.map((t) => t?.function?.name);
    const mcp = names.filter((n) => n?.startsWith("mcp__")).length;
    console.log(`  ${fp}  sessions=${rs.length}  tools=${names.length} (mcp=${mcp})  e.g. ${rs[0].file}`);
  }
  console.log(`  distinct tables: ${groups.size}`);
  if (drifted) {
    console.log(`\n  MID-SESSION DRIFT (the table changed between two calls of one session):`);
    for (const r of records.filter((x) => x.drifted)) {
      console.log(`    ${r.file} (${r.calls} call(s))`);
      console.log(`      first -> ${toolFingerprint(r.first)}   last -> ${toolFingerprint(r.last)}`);
      console.log(`      ${firstTableDifference(r.first, r.last)}`);
    }
  } else {
    console.log(`  no session changed its table mid-session`);
  }
  const pairs = [...groups.values()];
  if (pairs.length > 1) {
    console.log(`\n  FIRST DIFFERENCE between the two largest groups:`);
    console.log(`    ${firstTableDifference(pairs[0][0].first, pairs[1][0].first)}`);
  }
  return { drifted, distinct: groups.size };
}

// ---------------------------------------------------------------------------- main

function main() {
  const args = process.argv.slice(2);
  const opt = { assertStable: false, requireIdentical: false };
  for (let i = 0; i < args.length; i++) {
    switch (args[i]) {
      case "--log": opt.log = args[++i]; break;
      case "--records": opt.records = args[++i]; break;
      case "--no-records": opt.noRecords = true; break;
      case "--assert-stable": opt.assertStable = true; break;
      case "--require-identical": opt.requireIdentical = true; break;
      case "--help": case "-h":
        console.log(readFileSync(new URL(import.meta.url), "utf8").split("\n")
          .filter((l) => l.startsWith("//")).map((l) => l.slice(3)).join("\n"));
        return 0;
      default:
        console.error(`unknown argument: ${args[i]} (try --help)`);
        return 2;
    }
  }
  const home = join(homedir(), ".clj-harness");
  const logPath = opt.log ?? join(home, "logs", "llm-debug.jsonl");
  const ownDir = join(home, "projects", slugOf(process.cwd()));
  const mainSlug = mainRepoSlug(process.cwd());
  const mainDir = mainSlug ? join(home, "projects", mainSlug) : null;
  const recordsDir =
    opt.records ??
    (existsSync(ownDir) || !mainDir || !existsSync(mainDir) ? ownDir : mainDir);
  if (!opt.records && recordsDir === mainDir) {
    console.log(`records    : ${recordsDir} (this is a linked worktree; its own dir is empty)`);
  } else if (!opt.noRecords) {
    console.log(`records    : ${recordsDir}`);
  }

  console.log(`traffic log: ${logPath}`);
  if (!existsSync(logPath)) {
    console.error(`no such file. Set CLJ_HARNESS_LLM_DEBUG=1 and run a session first.`);
    return 2;
  }
  reportLog(readLog(logPath));

  let res = { drifted: 0, distinct: 0 };
  if (!opt.noRecords) {
    console.log(`\nrecords    : ${recordsDir}`);
    res = reportRecords(readRecords(recordsDir));
  }

  let code = 0;
  if (opt.assertStable && res.drifted) {
    console.error(`\nFAIL --assert-stable: ${res.drifted} session(s) changed their tool table mid-session`);
    code = 1;
  }
  if (opt.requireIdentical && res.distinct > 1) {
    console.error(`\nFAIL --require-identical: ${res.distinct} distinct tool tables`);
    code = 1;
  }
  return code;
}

process.exit(main());
