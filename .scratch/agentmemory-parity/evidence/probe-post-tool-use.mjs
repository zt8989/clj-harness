/*
  A FAKE agentmemory hook script: it prints what it received and exits 0.

  Why this exists: the bridge (`scripts/hooks/agentmemory.mjs`) resolves agentmemory's
  real scripts as `<plugin>/scripts/<name>.mjs`, and AGENTMEMORY_PLUGIN_DIR overrides
  where `<plugin>` is. Point that variable at a directory holding THIS file as
  `scripts/post-tool-use.mjs` and the bridge will happily run it -- so you see exactly
  what agentmemory's own script would see, without needing the server.

  Run (from the repo root):

    mkdir -p /tmp/amp/scripts
    cp .scratch/agentmemory-parity/evidence/probe-post-tool-use.mjs /tmp/amp/scripts/post-tool-use.mjs
    echo '{"hook":"PostToolUse","thread_id":"th-1","project_dir":"/tmp/proj","tool_name":"write",
           "tool_input":"{:path \"src/x.clj\", :content \"hi\"}","result":"wrote 2 lines"}' \
      | AGENTMEMORY_PLUGIN_DIR=/tmp/amp node scripts/hooks/agentmemory.mjs post-tool-use

  The interesting lines are `typeof tool_input` and the last one: what the server-side
  `extractFiles$1` would do with it (it requires an object, so a printed form yields
  no files at all).
*/
const p = (k, v) => process.stderr.write(`PROBE ${k} = ${v}\n`);

let s = "";
for await (const c of process.stdin) s += c;

const d = JSON.parse(s);
p("keys", JSON.stringify(Object.keys(d)));
p("hook_event_name", d.hook_event_name);
p("session_id", d.session_id);
p("cwd", d.cwd);
p("CLAUDE_PROJECT_DIR", process.env.CLAUDE_PROJECT_DIR);
p("tool_name", d.tool_name);
p("typeof tool_input", typeof d.tool_input);
p("tool_input", JSON.stringify(d.tool_input));
p("typeof tool_response", typeof d.tool_response);
p(
  "agentmemory would extract files",
  JSON.stringify(
    typeof d.tool_input === "object" && d.tool_input !== null
      ? Object.entries(d.tool_input)
          .filter(([k]) => ["path", "file_path", "file", "pattern"].includes(k))
          .map(([, v]) => v)
      : [],
  ),
);
