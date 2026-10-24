#!/usr/bin/env bash
set -uo pipefail
cd "$(dirname "$0")"

CMD="${1:-start}"

port_pid() {
  ss -ltnp 2>/dev/null | grep ":$1" | grep -oP 'pid=\K[0-9]+' | head -1
}

stop_all() {
  for port in 7777 7778; do
    pid=$(port_pid "$port" || true)
    [ -n "${pid:-}" ] && kill "$pid" 2>/dev/null || true
  done
  systemctl --user stop wingman-pw-loopback wingman-pw-loopback-mic 2>/dev/null || true
  echo "stopped"
}

case "$CMD" in
  stop)
    stop_all
    exit 0
    ;;
  start) ;;
  *)
    echo "usage: $0 [start|stop]" >&2
    exit 2
    ;;
esac

# ----- configuration (override by exporting before running) -----
# WHISPER_MODEL_FILE overrides the model; WHISPER_MODEL is ignored on purpose.
export WHISPER_MODEL="${WHISPER_MODEL_FILE:-$PWD/models/ggml-medium.en.bin}"
echo "whisper model: $WHISPER_MODEL"
export PI_PROVIDER="${PI_PROVIDER:-failover}"
export PI_MODEL="${PI_MODEL:-kimi-k2.7-code}"
export CAPTURE_DEVICES="${CAPTURE_DEVICES:-plughw:4,0=candidate,plughw:3,0=interviewer}"
export SILENCE_THRESHOLD_DB="${SILENCE_THRESHOLD_DB:--50}"

if [ ! -f "$WHISPER_MODEL" ]; then
  echo "warning: whisper model not found at $WHISPER_MODEL (speech will not transcribe)" >&2
fi

# ----- audio mirrors (systemd user services) -----
systemctl --user start wingman-pw-loopback wingman-pw-loopback-mic 2>/dev/null || \
  echo "warning: audio mirror services unavailable; interviewer/candidate audio will not flow" >&2

# ----- restart app processes -----
for port in 7777 7778; do
  pid=$(port_pid "$port" || true)
  [ -n "${pid:-}" ] && kill "$pid" 2>/dev/null || true
done
sleep 2

nohup clj -M:capture > /tmp/wingman-capture.log 2>&1 &
nohup clj -M:run     > /tmp/wingman-frontend.log 2>&1 &

for _ in $(seq 1 30); do
  sleep 2
  up=$(ss -ltnp 2>/dev/null | grep -cE ':7777|:7778' || true)
  [ "$up" = "2" ] && break
done

echo "frontend : $(curl -s -o /dev/null -w '%{http_code}' http://localhost:7777/ 2>/dev/null || echo down)"
echo "capture  : $(curl -s http://localhost:7778/status 2>/dev/null || echo down)"
echo
echo "open http://localhost:7777 then click + New and start talking"
echo "logs: /tmp/wingman-frontend.log  /tmp/wingman-capture.log"
echo "stop: $0 stop"
