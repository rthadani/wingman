#!/usr/bin/env bash
set -euo pipefail

FILE="${1:-output/mockup.html}"

fail() {
  echo "FAIL: $1" >&2
  exit 1
}

[ -f "$FILE" ] || fail "mockup file not found: $FILE"

content="$(cat "$FILE")"

check() {
  local marker="$1" desc="$2"
  echo "$content" | grep -q -- "$marker" || fail "missing $desc"
}

check 'cdn.tailwindcss.com' 'Tailwind Play CDN reference'
check 'htmx.org' 'htmx CDN reference'
check 'data-panel="sessions"' 'sessions panel'
check 'data-panel="transcript"' 'transcript panel'
check 'data-panel="assistant"' 'assistant panel'
check 'w-\[260px\]' '260px sessions sidebar'
check 'w-\[360px\]' '360px assistant panel'
check 'flex-1 min-w-0' 'flex-grow transcript panel'
check 'utterance-card' 'utterance card'
check 'data-utterance=' 'utterance card instance'
check 'audio-status-dot' 'audio status dot'
check 'data-status=' 'status dot state marker'
check 'session-timer' 'session timer'
check 'blinking-cursor' 'blinking cursor indicator'

if echo "$content" | grep -qE '<script>[^<]*fetch\(' || echo "$content" | grep -q 'XMLHttpRequest'; then
  fail "mockup must not contain custom JavaScript logic"
fi

echo "OK: mockup lint passed for $FILE"
