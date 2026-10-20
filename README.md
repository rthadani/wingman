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
| `CAPTURE_DEVICES` | unset | capture | comma-separated audio input device names; first = candidate mic, rest = interviewer. Entries may be `device=speaker` where speaker is `candidate` or `interviewer`. Unset with no console: default mic as candidate only |

## Run

Two processes, in this order:

```bash
clj -M:capture   # capture client: control server on :7778, waits idle
clj -M:run       # frontend: UI on http://localhost:7777
```

Run both from the repo root (the frontend resolves `tools/` and `AGENT.md` relative to its working directory).

### Audio device selection

If the capture client has a console, it lists available input devices and prompts for a comma-separated selection; the first device is your mic, the rest are treated as the interviewer. Non-interactive setups use `CAPTURE_DEVICES`.

### Interviewer audio (capturing the call)

The first device in `CAPTURE_DEVICES` (or the first number you pick in the interactive startup prompt) is you (`candidate`); every device after it is the `interviewer`. Device matching is case-insensitive substring matching.

#### Linux (PipeWire — verified)

This setup uses two ALSA loopback cards so Wingman can capture both the call audio and your microphone without locking the mic away from the call app. On `snd-aloop`, playback on PCM device 1 (`plughw:X,1`) appears on the paired capture PCM device 0 (`plughw:X,0`). The services therefore use a PipeWire-to-ALSA pipe: `pw-record` from a PipeWire source is piped into `aplay -D plughw:X,1`, and Wingman captures from `plughw:X,0`.

1. Load two ALSA loopback cards (persist across reboots):
```bash
sudo modprobe snd-aloop enable=1,1
echo 'options snd-aloop enable=1,1' | sudo tee /etc/modprobe.d/snd-aloop.conf
echo snd-aloop | sudo tee /etc/modules-load.d/snd-aloop.conf
```
`enable=1,1` creates card A (call audio) and card B (mic mirror). Check actual card numbers with `cat /proc/asound/cards` and adjust the service files and capture command below accordingly.

2. Install and start both PipeWire loopback user services:

   - `wingman-pw-loopback` pipes the call monitor source into card A playback `plughw:3,1`.
   - `wingman-pw-loopback-mic` pipes your microphone PipeWire source into card B playback `plughw:4,1`.

```bash
mkdir -p ~/.config/systemd/user
cp resources/wingman-pw-loopback.service ~/.config/systemd/user/
cp resources/wingman-pw-loopback-mic.service ~/.config/systemd/user/
# edit Environment=SOURCE lines if your source node names or card numbers differ
systemctl --user daemon-reload
systemctl --user enable --now wingman-pw-loopback
systemctl --user enable --now wingman-pw-loopback-mic
```

Start/stop both with `systemctl --user start|stop wingman-pw-loopback wingman-pw-loopback-mic`; status/logs via `systemctl --user status wingman-pw-loopback wingman-pw-loopback-mic` and `journalctl --user -u wingman-pw-loopback -u wingman-pw-loopback-mic`.

Find the correct PipeWire source node names with:
```bash
pw-dump | grep -o '"node.name": "[^"]*"' | sort -u    # look for your HDMI/output monitor and mic input
```

Verify a service is moving audio by checking the ALSA substream status:
```bash
head -1 /proc/asound/card3/pcm1p/sub0/status   # should show state: RUNNING
head -1 /proc/asound/card4/pcm1p/sub0/status   # should show state: RUNNING
```

3. Keep your normal output as the default sink so you still hear the call.

4. Start the frontend with `WHISPER_MODEL` set; transcription is silently disabled if it is missing:
```bash
WHISPER_MODEL=models/ggml-base.en.bin clj -M:run
```

5. Start capture with both devices:
```bash
CAPTURE_DEVICES="plughw:4=candidate,plughw:3=interviewer" clj -M:capture
```
`plughw:4` is the mic mirror (card B, `candidate`); `plughw:3` is the call audio mirror (card A, `interviewer`). Adjust card numbers to match `cat /proc/asound/cards`.

> **Note:** The microphone source name (`SOURCE` in `wingman-pw-loopback-mic.service`) can change if the USB device re-enumerates; update the service file and run `systemctl --user daemon-reload restart wingman-pw-loopback-mic` if that happens.

#### macOS (not verified on hardware)

1. Install BlackHole 2ch.
2. Audio MIDI Setup → create a **Multi-Output Device** containing BlackHole 2ch plus your speakers/headphones, so you still hear the call. Set the call app's output to that Multi-Output Device.
3. Start capture with both devices (or pick two numbers in the startup prompt):
```bash
CAPTURE_DEVICES="<your mic>,BlackHole 2ch" clj -M:capture
```
CoreAudio devices appear in the Java mixer list automatically. whisper natives ship for macOS arm64 and x64.

#### Windows

Capture and clipboard work, but transcription does not: there is no whisper native artifact for Windows. A virtual cable (VB-Audio Virtual Cable / Stereo Mix) would supply the interviewer device once a whisper backend exists.

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
