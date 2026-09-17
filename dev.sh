#!/usr/bin/env bash
#
# Start the harness on a port nobody is using, and the UI in front of it.
#
#   ./dev.sh                     harness (real, your own ~/.clj-harness) + UI
#   ./dev.sh --port 8080         the address the client used to hardcode
#   ./dev.sh --scripted          the scripted double instead: no api-key, no
#                                model, a temp home, a provider that replays
#   ./dev.sh --scripted my.json  ...with your own turns (see the format below)
#   ./dev.sh --ui-port 5199      somewhere other than 5173
#
# WHY THIS EXISTS. `npm run dev` on its own expects a harness on 8080, and 8080
# is the one port a second checkout, a test run, or yesterday's forgotten session
# is most likely to be holding. So the backend is started on a port the OS picks,
# that port becomes the dev server's proxy target (`HARNESS_BACKEND_URL`, read by
# ui/vite.config.js), and the browser keeps talking to its own origin -- no CORS
# allowance to keep in step, and nothing in the source learns a port.
#
# THE PORT IS READ BACK, NOT GUESSED. The backend is asked for port 0 and the
# port it actually got is what it prints; probing for a free port first and then
# handing the number over would be a race with every other process on the
# machine, and would ask the backend to trust a port it did not choose. So the
# wait below is for that line, not a sleep.
#
# THE REAL MODE USES YOUR OWN ~/.clj-harness -- that is what it is for: your
# harness, your config, your provider, your key. THE SCRIPTED MODE MUST NOT: it
# gets a temp config root AND a temp OS home (the two are siblings, never nested
# -- see AGENTS.md on the homes), both deleted on exit, exactly as the suites do
# it. A scripted turn is
#   {"turns": [{"content": "..."},
#              {"content": "", "tool-calls": [{"id": "c1", "name": "read",
#                                             "arguments": {"path": "deps.edn"}}]}]}
# consumed one per model call -- one tool round costs two.
set -euo pipefail

ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)

PORT=0
UI_PORT=5173
SCRIPTED=false
SCRIPT_FILE=""
TMP=""
BACKEND_PID=""

usage() {
  cat <<'TXT'
Start the harness on a port nobody is using, and the UI in front of it.

  ./dev.sh                     harness (real, your own ~/.clj-harness) + UI
  ./dev.sh --port 8080         the address the client used to hardcode
  ./dev.sh --scripted          the scripted double instead: no api-key, no
                               model, a temp home, a provider that replays
  ./dev.sh --scripted my.json  ...with your own turns
  ./dev.sh --ui-port 5199      somewhere other than 5173

The backend is started on port 0 (the OS picks) and the port it announces is
handed to the dev server as HARNESS_BACKEND_URL, which ui/vite.config.js uses
as its proxy target. Ctrl-C stops both, and the temp home the scripted mode
made is deleted with it.
TXT
}

while [ $# -gt 0 ]; do
  case "$1" in
    --port)     PORT=${2:?--port wants a number}; shift 2 ;;
    --ui-port)  UI_PORT=${2:?--ui-port wants a number}; shift 2 ;;
    --scripted) SCRIPTED=true; shift
                # An OPTIONAL file: what follows is the script only when it is
                # not another option.
                if [ $# -gt 0 ] && [ "${1#--}" = "$1" ]; then SCRIPT_FILE=$1; shift; fi ;;
    -h|--help)  usage; exit 0 ;;
    *) echo "dev.sh: unknown argument: $1" >&2; usage >&2; exit 2 ;;
  esac
done

# THE BACKEND GOES IN ITS OWN PROCESS GROUP, so stopping this script stops the
# JVM under it too. `clojure` is a launcher that execs java, and what is left
# behind when only the launcher's pid is killed is a harness still holding the
# port -- which is the exact nuisance this script exists to avoid causing.
cleanup() {
  if [ -n "$BACKEND_PID" ]; then
    kill -- "-$BACKEND_PID" 2>/dev/null || kill "$BACKEND_PID" 2>/dev/null || true
    wait "$BACKEND_PID" 2>/dev/null || true
  fi
  [ -n "$TMP" ] && rm -rf "$TMP"
  return 0
}
trap cleanup EXIT INT TERM

TMP=$(mktemp -d)
LOG="$TMP/backend.log"

if $SCRIPTED; then
  mkdir -p "$TMP/home" "$TMP/user-home"
  printf '%s\n' '{:default {:protocol :fake :base-url "http://offline.invalid/v1" :model "seeded"}}' \
    > "$TMP/home/config.edn"
  if [ -z "$SCRIPT_FILE" ]; then
    SCRIPT_FILE="$TMP/script.json"
    printf '%s\n' '{"turns": [{"content": "（这是脚本厂商的一条回答。）"}]}' > "$SCRIPT_FILE"
  fi
  BACKEND=(env "CLJ_HARNESS_HOME=$TMP/home" clojure -M:dev -m harness.e2e-server
           --script-file "$SCRIPT_FILE" --port "$PORT" --user-home "$TMP/user-home")
  # `harness.e2e-server`'s ready line, which is machine-readable on purpose.
  READY_RE='PRINT-READY {:port \([0-9]\{1,\}\)}'
else
  BACKEND=(clojure -M:run --port "$PORT")
  # `start!`'s line, which names the BOUND port -- the whole reason --port 0 is
  # usable at all.
  READY_RE='harness listening on http://localhost:\([0-9]\{1,\}\)'
fi

set -m
"${BACKEND[@]}" >"$LOG" 2>&1 &
BACKEND_PID=$!
set +m

BOUND=""
for _ in $(seq 1 240); do          # 60s, which is a cold JVM plus a deps download
  if ! kill -0 "$BACKEND_PID" 2>/dev/null; then
    echo "dev.sh: the backend stopped before it listened. Its output:" >&2
    cat "$LOG" >&2
    exit 1
  fi
  BOUND=$(sed -n "s#.*$READY_RE.*#\1#p" "$LOG" | head -1)
  [ -n "$BOUND" ] && break
  sleep 0.25
done

if [ -z "$BOUND" ]; then
  echo "dev.sh: the backend never announced a port within 60s. Its output:" >&2
  cat "$LOG" >&2
  exit 1
fi

echo "dev.sh: harness ${BACKEND[0]}$([ "$PORT" = 0 ] && echo ' (a port the OS picked)') on http://127.0.0.1:$BOUND"
echo "dev.sh: the log is $LOG"
echo "dev.sh: UI on http://localhost:$UI_PORT (Ctrl-C stops both)"

cd "$ROOT/ui"
if [ ! -d node_modules ]; then
  echo "dev.sh: no ui/node_modules -- running npm install first"
  npm install
fi

HARNESS_BACKEND_URL="http://127.0.0.1:$BOUND" npm run dev -- --port "$UI_PORT"
