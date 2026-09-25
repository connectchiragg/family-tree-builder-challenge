#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
backend_pid=''
ui_pid=''
cleanup() {
  trap - EXIT INT TERM
  [[ -z "$ui_pid" ]] || kill -TERM "$ui_pid" 2>/dev/null || true
  [[ -z "$backend_pid" ]] || kill -TERM "$backend_pid" 2>/dev/null || true
  wait 2>/dev/null || true
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

# Do not mistake an unrelated running backend for the one started here.
node <<'JS'
const net = require('node:net');
const server = net.createServer();
server.once('error', () => { console.error('Port 3001 is busy. Stop the existing backend first.'); process.exit(1); });
server.listen(3001, () => server.close());
JS

echo "Starting backend; waiting for database readiness…"
# Each supervisor terminates its complete npm/Maven process tree on shutdown.
./node_modules/.bin/concurrently --kill-others "npm run dev:server" &
backend_pid=$!
ready=false
for ((attempt=0; attempt<180; attempt++)); do
  if ! kill -0 "$backend_pid" 2>/dev/null; then
    echo "Backend exited before becoming ready. See the error above." >&2
    exit 1
  fi
  if node -e '
    fetch("http://127.0.0.1:3001/api/health", {signal: AbortSignal.timeout(1000)})
      .then(async r => { if (!r.ok || (await r.json()).ok !== true) process.exit(1); })
      .catch(() => process.exit(1));
  '; then
    ready=true
    break
  fi
  sleep 1
done
if [[ "$ready" != true ]]; then
  echo "Backend did not become ready within the startup timeout. UI was not started." >&2
  exit 1
fi

echo "Opening UI at http://127.0.0.1:5173"
./node_modules/.bin/concurrently --kill-others "npm run dev:client" &
ui_pid=$!
while kill -0 "$backend_pid" 2>/dev/null && kill -0 "$ui_pid" 2>/dev/null; do
  sleep 1
done
echo "A service stopped; shutting down the other service."
exit 1
