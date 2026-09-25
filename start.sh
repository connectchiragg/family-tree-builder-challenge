#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"

for command in java mvn node npm; do
  if ! command -v "$command" >/dev/null 2>&1; then
    echo "Missing $command. Install JDK 21/22, Maven, and Node.js 22.12+ first." >&2
    exit 1
  fi
done
node -e 'const [major, minor] = process.versions.node.split(".").map(Number); if (major < 22 || (major === 22 && minor < 12)) { console.error("Node.js 22.12+ is required."); process.exit(1); }'

# Never source .env as shell code or replace an existing configuration file.
umask 077
if [[ ! -f server/.env ]]; then
  cp server/.env.example server/.env
fi
if ! node <<'JS'
const fs = require('node:fs');
const entries = fs.readFileSync('server/.env', 'utf8').split(/\r?\n/);
const keys = entries.filter(line => /^\s*ANTHROPIC_API_KEY\s*[=:]/.test(line));
const key = process.env.ANTHROPIC_API_KEY || keys.at(-1)?.replace(/^\s*ANTHROPIC_API_KEY\s*[=:]\s*/, '').trim();
process.exit(key ? 0 : 1);
JS
then
  read -r -s -p "Enter your OpenRouter API key (hidden): " api_key || { echo; echo "No key supplied; setup stopped." >&2; exit 1; }
  echo
  if [[ ! "$api_key" =~ ^[A-Za-z0-9_-]+$ ]]; then
    echo "Enter a non-empty API key containing only letters, digits, underscores or hyphens." >&2
    exit 1
  fi
  printf '%s' "$api_key" | node -e '
    const fs = require("node:fs");
    const key = fs.readFileSync(0, "utf8");
    const lines = fs.readFileSync("server/.env", "utf8").split(/\r?\n/)
      .filter(line => !/^\s*ANTHROPIC_API_KEY\s*[=:]/.test(line));
    fs.writeFileSync("server/.env", lines.join("\n").trimEnd() + "\nANTHROPIC_API_KEY=" + key + "\n", {mode: 0o600});
  '
  unset api_key
fi
chmod 600 server/.env

echo "Installing dependencies…"
npm ci
echo "Starting Family Tree Builder at http://127.0.0.1:5173 — press Ctrl+C to stop."
exec npm run dev
