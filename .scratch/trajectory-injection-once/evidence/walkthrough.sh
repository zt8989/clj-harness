#!/usr/bin/env bash
# The real-machine walkthrough behind `t03-01-opening-once.png` and
# `t03-02-injection-turn.png`: a SCRIPTED backend and the UI's own dev server.
#
#   .scratch/trajectory-injection-once/evidence/walkthrough.sh
#
# THEN, in a browser at http://localhost:5199:
#   1. type `/alpha fix the bug`, Enter -- the skill picker opens on `/` and closes on
#      the first space, so the message sends as typed;
#   2. type a second message, Enter;
#   3. switch the thread column from `Conversation` to `Trajectory`.
#
# BOTH HOMES ARE TEMP DIRECTORIES and the vendor is `harness.fake`, scripted from a
# file: the developer's real ~/.clj-harness and ~/.agents/skills are never read, and no
# api-key is involved (AGENTS.md's rule).
#
# THE PORTS ARE NOT THE CONTRACT ONES, on purpose, and that is the only awkward part.
# `ui/src/lib/threads.ts` hardcodes AGENT_URL to :8080 and the edge CORS-allows exactly
# http://localhost:5173 -- both are contracts, and on the machine this was written on
# BOTH were held by a harness somebody was actually using. So this script:
#   * serves the backend on :8124 and patches the edge's CORS origin to :5199
#     (`alter-var-root` on the map, the way dev/harness/e2e_server.clj patches a pin);
#   * runs vite on :5199 (it overrides the config's :5173);
#   * points AGENT_URL at :8124 for the length of the run and PUTS IT BACK on exit.
# The UI is otherwise untouched: this ticket changed no client code.
set -euo pipefail

root=$(cd "$(dirname "$0")/../../.." && pwd)
work=$(mktemp -d "${TMPDIR:-/tmp}/tj-walkthrough.XXXXXX")
ui_origin="http://localhost:5199"
ui_port=5199
agent_port=8124

mkdir -p "$work/home" "$work/userhome/.agents/skills/alpha"
printf '%s\n' '{:default {:protocol :fake :base-url "http://offline.invalid/v1" :model "seeded"}}' \
  > "$work/home/config.edn"
cat > "$work/userhome/.agents/skills/alpha/SKILL.md" <<'MD'
---
name: alpha
description: alpha does a thing
---

ALPHA BODY
MD
printf '%s' '{"turns": [{"content": "first"}, {"content": "second"}]}' > "$work/script.json"

cat > "$work/backend.clj" <<CLJ
(require 'harness.e2e-server 'harness.edge.http)
(let [c (ns-resolve 'harness.edge.http 'cors)]
  (alter-var-root c (constantly (assoc @c "Access-Control-Allow-Origin" "$ui_origin")))
  (println "; dev-only: ui-origin for this run is $ui_origin"))
(apply harness.e2e-server/-main ["--script-file" "$work/script.json"
                                 "--user-home" "$work/userhome"
                                 "--port" "$agent_port"])
CLJ

# TEMP LINE, reverted below. Everything the page asks the server for goes through it.
sed -i.bak "s#^export const AGENT_URL = .*#export const AGENT_URL = \"http://localhost:$agent_port/\";#" \
  "$root/ui/src/lib/threads.ts"

restore() {
  mv "$root/ui/src/lib/threads.ts.bak" "$root/ui/src/lib/threads.ts"
  kill "$backend" "$ui" 2>/dev/null || true
  rm -rf "$work"
}
trap restore EXIT INT TERM

echo "work dir: $work"
CLJ_HARNESS_HOME="$work/home" clojure -M:dev -i "$work/backend.clj" &
backend=$!
(cd "$root/ui" && npm run dev -- --port "$ui_port") &
ui=$!

wait
