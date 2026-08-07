# Pica — client ⇄ server contract

What the Pica Android app strictly requires from the server. As long as these
hold, the server's internal pipeline wiring can change freely. Companion to
[SERVER_DEPLOY.md](SERVER_DEPLOY.md) (how to run) and [server.py](server.py) (the
reference implementation).

The app is a thin client built on the official Pipecat Android SDK
(`ai.pipecat:small-webrtc-transport:0.3.7`, which pulls `ai.pipecat:client:0.3.4`).
It uses the **RTVI** protocol over a **direct P2P WebRTC** connection — no JWT, no
SFU, no rooms. Contrast with the sibling Lika app, which mints a LiveKit JWT and
joins an SFU room; Pica has none of that.

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
make sure the endpoint path is exactly `/api/offer` and it returns the handler's
answer verbatim. After the answer, ICE completes and media flows P2P.

- **Base URL**: the app's `PICA_SERVER_URL` build config (e.g.
  `http://100.70.131.13:7860`) + `/api/offer`. Host/port must match the running
  server; host must be in the app's `network_security_config.xml` for cleartext.

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

For the orb, chat transcript, and latency HUD to work, the server pipeline **must**
include an `RTVIProcessor` and an `RTVIObserver` (see `server.py`). The app
registers `RTVIEventCallbacks`; it reacts to these and ignores the rest:

| RTVI event | App uses it for |
|---|---|
| `onConnected` / `onDisconnected` | session lifecycle |
| `onBotReady` | logs readiness |
| `onBackendError(msg)` | surfaces a snackbar error |
| `onUserStartedSpeaking` / `onUserStoppedSpeaking` | **latency HUD start**, listening state |
| `onBotStartedSpeaking` / `onBotStoppedSpeaking` | **latency HUD stop**, orb amplitude |
| `onRemoteAudioLevel(level, participant)` | orb amplitude while bot speaks |
| `onUserTranscript(Transcript{text, final})` | user chat bubbles (incremental) |
| `onBotTranscript(text)` | assistant chat bubbles |

**The headline metric** for the A/B against Lika is measured purely client-side:
time from `onUserStoppedSpeaking` → `onBotStartedSpeaking` = silence → first bot
audio. The server doesn't need to send a metric for this, but keeping
`enable_metrics=True` lets the server's own `latency.py` breakdown
(turn-detect / LLM TTFB / TTS TTFB) line up with the client number for
cross-checking.

---

## 4. Behaviors the app expects

- **Bot speaks first.** On connect the server seeds a greeting (queues an
  `LLMRunFrame` on `on_client_connected`) so the user hears Pica without speaking
  first — confirms the loop is alive.
- **Barge-in.** Server-side VAD + interruptions are on; when the user talks over
  the bot, TTS stops. The app also stops playback locally on `onUserStartedSpeaking`
  so it feels instant.
- **Voice selection: not in v1.** The app sends no voice ID. The server uses its
  configured `CARTESIA_VOICE_ID`. (LiveKit-style participant metadata has no
  Pipecat equivalent; runtime voice switching would need an RTVI custom message +
  server handling — a future change on both sides.)

---

## 5. Where this lives on the device (reference)

- `app/.../net/PicaVoiceClient.kt` — the only file touching the Pipecat SDK; wraps
  `RTVIClient` + `SmallWebRTCTransport.Factory` and fans RTVI events to the app
  behind a `Listener` interface.
- `app/.../VoiceAgentViewModel.kt` — maps those events to UI state, transcript
  persistence, and the latency HUD.
- `local.properties` → `PICA_SERVER_URL`; `res/xml/network_security_config.xml`
  → tailnet host allowlist for cleartext signaling.
