# Architecture: one interaction, end to end

The four actors:

- **Browser** — htmx + `htmx-ext-sse`, renders the UI.
- **Frontend** (port 7777) — the only component with session state; owns the SSE registry and the pi RPC sessions.
- **Capture client** (port 7778) — stateless harvester for microphone audio and clipboard; talks to the frontend over one WebSocket.
- **pi RPC child** — spawned per session by the frontend; the actual assistant.

Data flows frontend←capture over the WebSocket and frontend→browser over SSE.

## 1. Session creation

You click **+ New** on `/`. The injected `new-session-js` (bound to the first button in the DOM) does `fetch POST /api/session`, reads `{id}` from the JSON, and redirects to `/session/<id>`.

`session-create-handler` does two things:

1. `session/create-session` writes `data/sessions/<uuid>.edn` containing a single `:session-created` event. This file's existence is what makes a session "known" to every later check (`known-session?` guards `/session/:id`, the SSE route, and the WebSocket route).
2. Calls `capture-control/start!` — a fire-and-forget async POST to `http://localhost:7778/start` with `{"session-id": "<id>"}`.

Gotcha: the capture call is async and its failures only reach stderr, so a dead capture client still yields a successful session — the page just silently captures nothing.

## 2. The capture client arms itself

Its `/start` handler runs `start-capture!` inside one `swap!`, deriving `ws://localhost:7777/ws/session/<id>` from `AGENT_URL`. It starts four things:

- **`keep-connected!`** — the reconnect loop (backoff 1s doubling to 30s) that dials the WebSocket and parks until the conn closes or `stop-ch` fires.
- **`start-sender!`** — a core.async channel (dropping buffer of 64) plus one consumer thread that turns messages into WS frames. Audio goes out as a binary frame (one speaker byte, 0 = candidate / 1 = interviewer, then PCM); clipboard as a text frame `{"type":"clipboard.update","payload":"..."}`. Each send is aborted if it stalls for 15s.
- **Audio dispatchers** (tarsos) — the first selected device speaks as `:candidate`, the rest as `:interviewer`.
- **The clipboard watch** — baselines the *current* clipboard as `last-sent` and only forwards changes afterward (a `FlavorListener` plus a 500ms poll, guarded by compare-and-set). Every offer checks `(:muted? @state)` first.

That is why copies made before capture started never appear.

## 3. The session page wires the browser to the frontend

`GET /session/:id` passes `known-session?` (file exists, id matches `[a-zA-Z0-9-]+`), then serves `app.html` with three string rewrites:

- `sse-connect="/events"` → `sse-connect="/api/session/<id>/sse"`
- the chat form's `SESSION_ID` → the real id
- footer control JS injected before `</body>`

The browser connects, htmx + `htmx-ext-sse` initialize, and the SSE extension sees `hx-ext="sse" sse-connect=...` on `<body>` and opens an EventSource.

## 4. SSE registration

`sse-handler` validates the session again, then `sse/sse-response` writes the `text/event-stream` headers, registers the http-kit channel in `(:sse-registry sys)` under the session id (deregistered on close), and starts the keepalive. Two live swap targets remain: the transcript div (`sse-swap="transcript"`) and `#assistant-output` (`sse-swap="assistant" hx-swap="beforeend"`).

The home page is the same HTML with `sse-connect` stripped out, so it is an inert shell.

## 5. The clipboard round trip

You copy the interviewer's question. The watch fires, offers `[:clipboard text]` to the sender, the sender ships a text frame over the WebSocket. The frontend's `/ws/session/:id` handler (`http-kit/as-channel` `on-receive` → `handle-ws-frame`) parses it, confirms `type=clipboard.update` and a string payload, and offers the text into the chat pipeline — created lazily on first message via `ensure-chat-pipeline!`.

### Interviewer audio

A second capture device records the interviewer. The first selected device is tagged `:candidate`; every additional device is tagged `:interviewer`. The binary WS frame prefixes PCM with one speaker byte (`0` for candidate, `1` for interviewer). OS-specific loopback/BlackHole recipes are in README.md.

`run-chat-turn` then:

1. Appends the text to the session `.edn` as a `:transcript` event under a per-session lock (the event's `:id` is the running transcript count).
2. Broadcasts a `transcript` SSE frame containing the hiccup card.
3. Calls `agent-loop/run-turn!`.

## 6. The pi turn

The first message also spawned `start-session!`: `stage-tools!` builds a temp context-dir containing the four tool scripts plus `AGENT.md`, and `start-rpc-session!` spawns `pi --mode rpc --name <session-id> --no-session` with that working directory, reading pi's JSON events from stdout into `out-ch`.

`run-turn!` writes one prompt message — `format-context` (Role/Topic lines plus the last 20 transcript/response entries, so the fresh clipboard text is the latest entry) — into pi's stdin, then consumes events: `message_update`/`text_delta` deltas accumulate and complete lines are pushed to `line-ch`; `agent_settled` ends the turn. A go-loop relays each line as an `assistant` SSE frame, so the answer streams into the right panel live. When the turn settles, the full text is appended as a `:response` event and a final assistant frame goes out.

`AGENT.md` is the agent's standing instruction file (assist the candidate: talking points, then a sample answer). Edits to it apply on the next session with no restart, because the context-dir is rebuilt per session.

`mark_topic_answered`/`skip?` still exists in code but is vestigial — `AGENT.md` no longer instructs the agent to use it.

## 7. Render

`sse/broadcast` writes `event: transcript\ndata: <fragment>\n\n` frames to every registered client channel for that session. The transcript fragment carries its own `hx-swap-oob="beforeend:..."` so the card appends; assistant frames append line-by-line via `beforeend`.

The audio path is identical in shape except it enters as a binary WS frame → `pcm->wav` → whisper (if `WHISPER_MODEL` is set) → the same transcript event + SSE broadcast. It is gated by the **Pause voice** toggle; clipboard/chat by **Pause text**.

## HTTP surfaces

Frontend (7777): `GET /`, `GET /session/:id`, `GET /sessions`, `POST /api/session`, `POST /api/session/:id/chat`, `GET /api/session/:id/sse`, `GET /ws/session/:id` (capture client), `POST /api/capture/stop`, `POST /api/capture/mute|unmute`, `POST /api/pause/:kind`, `POST /api/resume/:kind`, `GET /api/pause/status`.

Capture client (7778): `POST /start`, `POST /stop`, `POST /mute`, `POST /unmute`, `GET /status`.

## Known failure modes

- Capture silently dead while the frontend looks healthy: session creation succeeds even when the capture client is down, and the WS handler 404s silently if the session `.edn` vanished. Check `GET :7778/status`.
- The clipboard baseline: content on the clipboard at capture start is never forwarded.
- Mute stops *offers* at the capture client; pause stops *consumption* in the frontend pipelines — different layers, different toggles.
