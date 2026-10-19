#!/usr/bin/env bash
set -euo pipefail

HOW_TO_RUN="output/how-to-run.md"
STACK="output/stack.md"

fail() {
  echo "FAIL: $1" >&2
  exit 1
}

[ -f "$HOW_TO_RUN" ] || fail "missing $HOW_TO_RUN"
[ -f "$STACK" ] || fail "missing $STACK"

htr="$(cat "$HOW_TO_RUN")"
stack="$(cat "$STACK")"

check() {
  local content="$1" marker="$2" desc="$3"
  echo "$content" | grep -q -- "$marker" || fail "$desc"
}

# how-to-run.md must cover prerequisites, build, run, and routes
check "$htr" 'Java 21' 'how-to-run: Java 21 prerequisite'
check "$htr" 'whisper.clj' 'how-to-run: whisper.clj prerequisite'
check "$htr" 'WHISPER_MODEL' 'how-to-run: WHISPER_MODEL env var'
check "$htr" 'PI_BIN' 'how-to-run: PI_BIN env var'
check "$htr" 'PI_PROVIDER' 'how-to-run: PI_PROVIDER env var'
check "$htr" 'PI_MODEL' 'how-to-run: PI_MODEL env var'
check "$htr" 'direct LLM API' 'how-to-run: no direct API key handling'
check "$htr" 'clj -M:run' 'how-to-run: run command'
check "$htr" '7777' 'how-to-run: port 7777'
check "$htr" '/api/session/:id/audio' 'how-to-run: audio route'
check "$htr" '/api/session/:id/chat' 'how-to-run: chat route'
check "$htr" '/api/session/:id/sse' 'how-to-run: sse route'
check "$htr" '/sessions' 'how-to-run: sessions route'
check "$htr" '/session/:id' 'how-to-run: session route'
check "$htr" ':success-check' 'how-to-run: success check'

# stack.md must list the technology inventory
check "$stack" 'Clojure' 'stack: Clojure'
check "$stack" 'http-kit' 'stack: http-kit'
check "$stack" '2.8.0' 'stack: http-kit version'
check "$stack" 'reitit' 'stack: reitit'
check "$stack" '0.7.2' 'stack: reitit version'
check "$stack" 'hiccup' 'stack: hiccup'
check "$stack" '2.0.0-RC4' 'stack: hiccup version'
check "$stack" 'core.async' 'stack: core.async'
check "$stack" '1.7.701' 'stack: core.async version'
check "$stack" 'cheshire' 'stack: cheshire'
check "$stack" '5.13.0' 'stack: cheshire version'
check "$stack" 'whisper-clj' 'stack: whisper.clj'
check "$stack" 'pi CLI' 'stack: pi CLI'
check "$stack" 'htmx' 'stack: htmx'
check "$stack" 'Tailwind' 'stack: Tailwind'
check "$stack" 'test-runner' 'stack: cognitect test-runner'

echo "OK: docs check passed"
