#!/usr/bin/env node
/*
  agentmemory hook bridge: clj-harness payload in, agentmemory's own hook script out.

  WHY THIS IS A BRIDGE RATHER THAN A ONE-LINE `node .../post-tool-use.mjs` IN
  hooks.edn. The two sides agree on the OUTCOME (a command, JSON on stdin, an exit
  code) and disagree about the VOCABULARY:

    clj-harness                     agentmemory (Claude Code's spelling)
    hook          "PostToolUse"     hook_event_name  "PostToolUse"
    thread_id     "th-1"            session_id       "th-1"
    project_dir   "/work/repo"      cwd              "/work/repo"
    tool_name     "write"           tool_name        "write"
    tool_input    {...}             tool_input       {...}
    result        ".."              tool_response   ".."

  Point the declarations straight at agentmemory's scripts and every observation
  lands under session `unknown`, in whatever directory the JVM happened to be
  started from -- memory that recalls nothing, written silently. So the mapping
  lives in one file that names both sides.

  WHAT IT DOES NOT DO. It does not decide anything: exit 0 means the script exited
  0, and the exit code is passed through untouched, because a bridge that answered
  in place of the thing it wrapped would be a second opinion nobody asked for. It
  does not read the REST API: the hook scripts are agentmemory's own, and talking to
  the server directly would be a second copy of their protocol, free to drift.

  USAGE (one declaration per hook point, in ~/.clj-harness/hooks.edn):

    {:post-tool-use [{:command "node \"/abs/scripts/hooks/agentmemory.mjs\" post-tool-use"}]}

  The argument is the name of the script under agentmemory's `plugin/scripts/`.
  AGENTMEMORY_PLUGIN_DIR overrides where that is looked for; without it the plugin
  is found beside the `agentmemory` executable on PATH, which is version-proof
  because that executable is a symlink into the installed package.
*/
import { spawn } from "node:child_process";
import { accessSync, constants, existsSync, realpathSync } from "node:fs";
import { delimiter, dirname, join } from "node:path";

const SCRIPT = process.argv[2];

/** Exit 1, loudly: this bridge could not do its job. 1 is an ordinary failure --
 *  never 2, which clj-harness reads at EVERY point as "block", and a bridge that
 *  cannot find a file has no business stopping a run. */
function die(message) {
  process.stderr.write(`agentmemory hook bridge: ${message}\n`);
  process.exit(1);
}

function readStdin() {
  return new Promise((resolve) => {
    let text = "";
    process.stdin.setEncoding("utf8");
    process.stdin.on("data", (chunk) => (text += chunk));
    process.stdin.on("end", () => resolve(text));
    process.stdin.on("error", () => resolve(text));
  });
}

/** A declared fact, as the harness put it on the wire, as an object when it is
 *  one. clj-harness renders each fact with `str`, so a tool's arguments arrive as
 *  their PRINTED form (`{:path "src/x.clj"}`) rather than as JSON -- parse when
 *  they happen to be JSON, and hand the text through when they do not. A string
 *  still carries the paths agentmemory searches on, and a reader for Clojure's
 *  printer here would be a second dialect kept in step by hand. */
function asObject(value) {
  if (typeof value !== "string") return value;
  try {
    const parsed = JSON.parse(value);
    return parsed !== null && typeof parsed === "object" ? parsed : value;
  } catch {
    return value;
  }
}

/** The payload clj-harness wrote -> the payload agentmemory's scripts read. Only
 *  the fields either side actually has: a key invented here would be a claim
 *  about the run that no one made. */
function translate(payload) {
  const out = { hook_event_name: payload.hook ?? SCRIPT };
  if (payload.thread_id !== undefined) out.session_id = payload.thread_id;
  if (payload.project_dir) out.cwd = payload.project_dir;
  if (payload.tool_name !== undefined) out.tool_name = payload.tool_name;
  if (payload.tool_input !== undefined) out.tool_input = asObject(payload.tool_input);
  if (payload.result !== undefined) out.tool_response = payload.result;
  if (payload.prompt !== undefined) out.prompt = payload.prompt;
  return out;
}

/** The `agentmemory` executable on PATH, or undefined. `bash -lc` is what spawns
 *  a hook, so PATH is already the login one the run itself uses. */
function agentmemoryOnPath() {
  for (const dir of (process.env.PATH ?? "").split(delimiter)) {
    if (!dir) continue;
    const candidate = join(dir, "agentmemory");
    try {
      accessSync(candidate, constants.X_OK);
      return candidate;
    } catch {
      // not here; keep walking
    }
  }
  return undefined;
}

/** The installed package's `plugin/` directory, or undefined. Resolved from the
 *  bin's REAL path (a symlink into `<pkg>/dist/cli.mjs`) so an upgrade, a
 *  different node prefix, or a pnpm/npm layout all answer correctly -- unlike a
 *  path baked in at install time. */
function pluginDir() {
  if (process.env.AGENTMEMORY_PLUGIN_DIR) return process.env.AGENTMEMORY_PLUGIN_DIR;
  const bin = agentmemoryOnPath();
  if (!bin) return undefined;
  try {
    return join(dirname(dirname(realpathSync(bin))), "plugin");
  } catch {
    return undefined;
  }
}

async function main() {
  if (!SCRIPT) die("no hook script named; usage: agentmemory.mjs <session-start|post-tool-use|stop|..>");

  const raw = await readStdin();
  let payload;
  try {
    payload = JSON.parse(raw);
  } catch {
    // The harness always writes JSON. Anything else is not a payload this bridge
    // understands, and a hook that cannot read its input says nothing.
    process.exit(0);
  }
  if (!payload || typeof payload !== "object") process.exit(0);

  const dir = pluginDir();
  if (!dir) die("agentmemory is not on PATH; install it or set AGENTMEMORY_PLUGIN_DIR");

  const script = join(dir, "scripts", `${SCRIPT}.mjs`);
  if (!existsSync(script)) die(`no such hook script: ${script}`);

  const child = spawn(process.execPath, [script], {
    stdio: ["pipe", "inherit", "inherit"],
    env: {
      ...process.env,
      // The scripts fall back to process.cwd() when the payload says nothing
      // about a directory, and a hook's cwd is the JVM's -- so this is the one
      // fact worth stating twice.
      ...(payload.project_dir ? { CLAUDE_PROJECT_DIR: payload.project_dir } : {}),
    },
  });
  child.on("error", (error) => die(`could not run ${script}: ${error.message}`));
  child.on("close", (code, signal) => process.exit(signal ? 1 : (code ?? 1)));
  child.stdin.on("error", () => {
    // The child stopped reading. Its exit code is the answer; the broken pipe is not.
  });
  child.stdin.end(JSON.stringify(translate(payload)));
}

main().catch((error) => die(error?.message ?? String(error)));
