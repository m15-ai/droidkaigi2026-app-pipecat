# Pica — client ⇄ server contract

What the Pica Android app strictly requires from the server. As long as these
hold, the server's internal pipeline wiring can change freely — swap the STT,
LLM, TTS, or the whole brain and the app never notices.

The reference implementation is **Homer**, the DroidKaigi 2026 demo server —
a baseball voice agent in its own repo:
<https://github.com/m15-ai/droidkaigi2026-homer-server>.

The app is a thin client built on the official Pipecat Android SDK
(`ai.pipecat:small-webrtc-transport:1.1.0`, which pulls `ai.pipecat:client:1.1.0`;
RTVI protocol 1.0.0). It uses the **RTVI** protocol over a **direct P2P WebRTC**
connection — no JWT, no SFU, no rooms. Signaling is a single HTTP POST.

**Want the app to talk to *your* server?** Implement the four numbered sections
below, then add your server as an agent in the app — tap **＋** on the setup
screen and enter its host, port, and path. No app rebuild needed.

---

## 1. Signaling — `POST /api/offer`

The only HTTP endpoint. The app's SDK posts a WebRTC SDP offer; the server
answers. One offer per session.

**Request** (`Content-Type: application/json`)
```json
{ "sdp": "<offer sdp>", "type": "offer", "pc_id": "<optional, for renegotiation>" }
```

**Response (200)**
```json
{ "sdp": "<answer sdp>", "type": "answer", "pc_id": "<connection id>" }
```

The exact JSON is produced/consumed by Pipecat's `SmallWebRTCRequestHandler` on
the server and the `SmallWebRTCTransport` on the client — don't hand-roll it; just
make sure the endpoint answers at the path the agent is configured with (default
`/api/offer`) and returns the handler's answer verbatim. After the answer, ICE
completes and media flows P2P.

- **Where the URL comes from**: each agent row in the app stores scheme + host +
  port + path; the transport POSTs to that full URL verbatim. Three agents are
  seeded at first launch from the build's `*_SERVER_URL` values, but any agent
  can be added or edited in-app.
- **Cleartext note**: for plain `http://` signaling, the host must be listed in
  the app's `res/xml/network_security_config.xml` (Android blocks cleartext by
  default). The WebRTC media is DTLS-encrypted regardless.

---

## 2. Audio contract

Handled by WebRTC/Opus on both ends — listed for completeness. The server
resamples to/from its internal rates (STT 16 kHz, TTS 24 kHz); the client uses the
device mic/speaker via libwebrtc. **AEC3 is built into libwebrtc on both ends** —
do not disable it; without echo cancellation the bot transcribes itself.

| Direction | Codec | Notes |
|---|---|---|
| Mic → server | Opus (WebRTC) | server VAD/STT see PCM after decode |
| Server → speaker | Opus (WebRTC) | TTS PCM encoded to Opus by the transport |

---

## 3. RTVI events the app consumes

For the chat transcript, latency HUD, and session visualizer to work, the server
pipeline **must** include an `RTVIProcessor` and an `RTVIObserver` (see Homer's
`server.py` for the wiring). The app registers `PipecatEventCallbacks`; it
reacts to these and ignores the rest:

| RTVI event | App uses it for |
|---|---|
| `onConnected` / `onDisconnected` | session lifecycle |
| `onBotReady` | logs readiness |
| `onBackendError(msg)` | surfaces a snackbar error |
| `onUserStartedSpeaking` / `onUserStoppedSpeaking` | **latency HUD start**, local barge-in, visualizer tint |
| `onBotStartedSpeaking` / `onBotStoppedSpeaking` | **latency HUD stop**, visualizer tint |
| `onUserTranscript(Transcript{text, final})` | user chat bubbles (incremental) |
| `onBotTranscript(text)` | assistant chat bubbles *(deprecated in the 1.x SDK in favor of `onBotOutput`, but still fires)* |

The SmallWebRTC 1.1.0 client **never emits audio levels**
(`onUserAudioLevel` / `onRemoteAudioLevel` stay silent), so the app's
visualizers animate on a speech envelope synthesized from the speaking and
transcript events above — the server doesn't need to send anything extra.

**The headline metric** is measured purely client-side:
time from `onUserStoppedSpeaking` → `onBotStartedSpeaking` = silence → first bot
audio. The server doesn't need to send a metric for this, but keeping
`enable_metrics=True` lets the server's own per-turn latency breakdown
(turn-detect / LLM TTFB / TTS TTFB) line up with the client number for
cross-checking.

---

## 4. Behaviors the app expects

- **Bot speaks first.** On connect the server seeds a greeting (queues an
  `LLMRunFrame` on `on_client_connected`) so the user hears the bot without
  speaking first — confirms the loop is alive.
- **Barge-in.** Server-side VAD + interruptions are on; when the user talks over
  the bot, TTS stops. The app also stops playback locally on `onUserStartedSpeaking`
  so it feels instant.
- **Voice selection: not in v1.** The app sends no voice ID. The server uses its
  configured `CARTESIA_VOICE_ID`. (Runtime voice switching would need an RTVI
  custom message + server handling — a future change on both sides.)

---

## 5. Where this lives on the device (reference)

- `app/.../net/PicaVoiceClient.kt` — the only file touching the Pipecat SDK; wraps
  `PipecatClient` + `SmallWebRTCTransport` and fans RTVI events to the app
  behind a `Listener` interface.
- `app/.../VoiceAgentViewModel.kt` — maps those events to UI state, transcript
  persistence, and the latency HUD.
- `app/.../ServerEndpoint.kt` — the agent model (host/port/path + label, accent,
  visualizer); `local.properties` `*_SERVER_URL` values seed the first three rows.
- `res/xml/network_security_config.xml` — host allowlist for cleartext signaling.