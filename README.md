# Wingman

A real-time interview assistant. It captures the interviewer's questions (clipboard and microphone), transcribes speech, and streams talking points plus a sample answer to the candidate in the browser.

For how one interaction flows end to end, see [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

## Prerequisites

- **Java 21+** (`java -version` to check).
- **Clojure CLI** (`clj`) on your PATH. Install via https://clojure.org/guides/install_clojure.
- **pi CLI** installed and authenticated. Wingman never handles LLM API keys itself; it spawns `pi --mode rpc` and relies on pi's own configuration. Optional env vars `PI_BIN`, `PI_PROVIDER`, `PI_MODEL` select a specific binary/provider/model; unset, pi's defaults are used.
- **A display** for clipboard capture. Clipboard reading uses the Java AWT system clipboard, so the capture client needs a desktop session (X11 on Linux, any GUI session on macOS). On a headless box the capture client logs `clipboard unavailable:` and clipboard capture is off.
- **Whisper model** (audio only). Audio transcription uses whisper.cpp via `whisper-clj`; point `WHISPER_MODEL` at a ggml `.bin` model file (e.g. `models/ggml-base.en.bin`). Without it, speech is not transcribed; clipboard capture still works.

## Setup

```bash
git clone <this repo> && cd wingman
clj -P              # warm the dependency cache
```

No other build step is needed; both processes run from source.

## Configuration (environment variables)

| Variable | Default | Used by | Purpose |
|---|---|---|---|
| `PORT` | `7777` | frontend | HTTP port |
| `PI_BIN` | `pi` | frontend | pi binary for RPC sessions |
| `PI_PROVIDER` | unset | frontend | passed as `--provider` to pi |
| `PI_MODEL` | unset | frontend | passed as `--model` to pi |
| `WHISPER_MODEL` | unset | frontend | path to ggml model; unset disables transcription |
| `CAPTURE_URL` | `http://localhost:7778` | frontend | capture client control server |
| `CAPTURE_PORT` | `7778` | capture | capture control server port |
| `AGENT_URL` | `http://localhost:7777` | capture | frontend the capture client connects to |
| `CAPTURE_DEVICES` | unset | capture | comma-separated audio input device names; first = candidate mic, rest = interviewer. Unset with no console: default mic as candidate only |

## Run

Two processes, in this order:

```bash
clj -M:capture   # capture client: control server on :7778, waits idle
clj -M:run       # frontend: UI on http://localhost:7777
```

Run both from the repo root (the frontend resolves `tools/` and `AGENT.md` relative to its working directory).

### Audio device selection

If the capture client has a console, it lists available input devices and prompts for a comma-separated selection; the first device is your mic, the rest are treated as the interviewer. Non-interactive setups use `CAPTURE_DEVICES`.

## Use it

1. Open `http://localhost:7777`.
2. Click **+ New**. This creates the session, starts capture, and redirects to `/session/<id>`. Capture only starts here — opening an old session from the sidebar does not.
3. Copy the interviewer's question. It appears in the transcript and the assistant replies with talking points followed by a sample answer. You can also type into the chat box.
4. Footer controls: **Mute** stops sending audio/clipboard, **Pause voice** / **Pause text** stop consumption per channel.

## Deploying the capture client

Build an uberjar, then use the bundled service definitions:

```bash
clj -M:capture-uberjar
```

- Linux (systemd, expects `~/.capture-client/capture-client.jar`): `resources/capture-client.service`
- macOS (launchd, expects `/usr/local/lib/capture-client/capture-client.jar`): `resources/capture-client.plist`

## Troubleshooting

- **Copied text never appears**: check the capture client is running and not idle — `curl http://localhost:7778/status` must say `"status":"capturing"` for the current session. Capture only arms on **+ New**.
- **Text copied before capture started never shows**: the clipboard at capture start is the baseline; copy something new.
- **No live updates in the browser**: the SSE script must load; hard-refresh (Ctrl+Shift+R).
- **Audio but no transcription**: `WHISPER_MODEL` is unset or the file is missing; chunks are dropped silently.

## Tests

```bash
clj -M:test
```
