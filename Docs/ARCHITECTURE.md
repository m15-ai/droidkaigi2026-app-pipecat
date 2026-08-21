# Pica — Architecture

> The phone is just a **mic and a speaker**. The server is the brain.

Pica is a **thin** Android voice client for Pipecat. You speak; a Pipecat server
does all the work — STT, LLM, TTS, turn detection, barge-in — and streams audio
back over a **direct peer-to-peer WebRTC** link. No JWT, no SFU, no rooms:
signaling is literally **POST an SDP offer to `/api/offer`**.

`Pipecat SDK 1.1.0` · `RTVI 1.0.0` · `P2P WebRTC / Opus` · `Kotlin · Compose · MVVM` · `Room DB` · `minSdk 26 · target 35`

> This is the Markdown rendition for reading on GitHub. The same content lives
> in [ARCHITECTURE.html](ARCHITECTURE.html) (prettier — open in a browser) and
> [architecture.json](architecture.json) (structured data).

---

## Signal path — one WebRTC link, two directions, one side channel

Audio flows peer-to-peer as Opus in both directions. The same link carries RTVI
events — a control side channel that drives the chat, the latency HUD, and the
agent's chosen visualizer. Everything between the two Opus streams lives on the
server; the app never knows what's in there.

```
                          ┌────────── Pipecat server — "the agent" ──────────┐
 🎙️ mic ──▶ libwebrtc ──WebRTC/Opus──▶  01 VAD · turn detection              │
   (AEC3)                              │  02 Deepgram Nova-3 STT              │
                                       │  03 "LLM" slot — e.g. Homer's        │
                                       │     OpenClaw agent                   │
 🔊 spk ◀── libwebrtc ◀──WebRTC/Opus── │  04 Cartesia Sonic-2 TTS             │
   (AEC3)                              │  05 interruptions · barge-in         │
                                       └──────────────────────────────────────┘
            │
            └─ ◇ RTVI events (speaking start/stop · transcripts · bot-ready ·
               backend-error) ──▶ chat · latency HUD · visualizer
```

The server pipeline is swappable and invisible to the app — any stack honoring
the [contract](CLIENT_SERVER_CONTRACT.md) works. The demo server is
[**Homer**](https://github.com/m15-ai/droidkaigi2026-homer-openclaw), the baseball
voice agent: an OpenClaw agent brain in the pipeline's LLM slot, answering MLB
questions from live data.

## Architecture layers

Single-Activity Jetpack Compose, MVVM, manual DI. The entire Pipecat SDK surface
is quarantined in one file behind a transport-agnostic `Listener` — everything
else just reacts to events.

| Layer | Technology |
|---|---|
| UI | Jetpack Compose + Material 3 |
| State | Kotlin Coroutines + Flow, `StateFlow`, MVVM |
| Transport | Pipecat `SmallWebRTCTransport` (libwebrtc), RTVI protocol 1.0.0 |
| Signaling | `POST /api/offer` — SDP offer/answer, P2P |
| Audio | libwebrtc capture/playback with AEC3; `AudioManager` routing |
| Persistence | Room DB (sessions + messages), via KSP |
| Networking | OkHttp 4 (URL parsing only); WebRTC owns the media path |
| DI | `ServiceLocator` (manual) |

## Module map (`com.m15.pica`)

The whole vendor SDK sits in `net/PicaVoiceClient.kt` — the 0.3.x → 1.1.0 SDK
jump touched only that file.

| File | Role |
|---|---|
| `MainActivity.kt` | Compose host. Three top-level states: live session, read-only history viewer, idle setup (+ history drawer). Owns the snackbar, permission requests, keep-screen-on, mic foreground service. |
| `VoiceAgentViewModel.kt` | Session brain. Implements the `Listener`; maps RTVI events to UI state, transcript persistence, and the latency HUD. Manages the agent list, selection, speaker routing, history. |
| `net/PicaVoiceClient.kt` | **The only Pipecat-SDK file.** Wraps `PipecatClient` + `SmallWebRTCTransport`; `connect()` POSTs the SDP offer to the agent's URL verbatim. |
| `ServerEndpoint.kt` | The "agent" model: host, port, path, scheme, label, accent, and a `visualizer` key (stored as a string so unknown future keys degrade to the default). `url`/`badge` derived. |
| `VisualizerStyle.kt` | Visualizer registry: `SCOPE` / `BASES` / `ORB`, persisted per agent as its string key. |
| `MicForegroundService.kt` | Holds the `MICROPHONE` foreground type so a session survives the screen turning off. Captures no audio itself — libwebrtc owns the mic. |
| `di/ServiceLocator.kt` | Manual DI: Room DB + `ConversationRepository` + `AudioManager`. |
| `prefs/PicaLocalPrefs.kt` | Agent list + selection + speaker preference, persisted as JSON in SharedPreferences. |
| `data/db/` + `data/repo/` | Room: `ChatSession` (with an agent snapshot) + `MessageItem`. `ConversationRepository` upserts incremental transcripts, streams saved sessions, purges unsaved ones at startup. |
| `ui/SetupScreen.kt` | Agent list: select / add / edit / delete, start session. The editor dialog includes the visualizer picker. |
| `ui/VoiceAgentScreen.kt` | Live session UI; the single branch point that composes the agent's chosen visualizer. |
| `ui/ScopeVisualizer.kt` · `BasesVisualizer.kt` · `OrbVisualizer.kt` | The three visualizers (see below). |
| `ui/PicaSessionHost.kt` | `DisposableEffect`: `connect()` on enter, `disconnect()` on dispose. Composed only while a session is active. |
| `ui/ConversationDrawer.kt` + `ConversationViewerScreen.kt` | Saved-conversation drawer and read-only transcript viewer with share-sheet export. |

## Session lifecycle

1. **Start** — `startSession()` creates a Room session row (with an agent
   snapshot), registers an `AudioDeviceCallback` for hot-plug routing, flips
   `sessionActive=true`. MainActivity requests mic (+ notification) permission,
   starts the foreground service, keeps the screen on.
2. **Connect** — `PicaSessionHost` composes and calls `connect()`. A fresh
   `PicaVoiceClient` bound to the selected agent's URL POSTs the SDP offer; the
   server answers; ICE completes; media flows P2P.
3. **Bot speaks first** — the server seeds a greeting on `on_client_connected`,
   confirming the loop is alive end to end.
4. **Converse** — libwebrtc captures the mic (AEC3), encodes Opus, ships it up.
   RTVI events map to UI: speaking → visualizer tint + latency stopwatch;
   transcripts → chat bubbles, persisted to Room incrementally.
5. **Barge-in** — local playback stops the instant you speak
   (`onUserStartedSpeaking`), before the server's VAD interrupt even
   round-trips. The server's interruption catches up.
6. **Stop** — `onDispose → disconnect()` fully tears the client down.
   `stopSession()` resets audio mode, unregisters the device callback, discards
   the session if unsaved, stops the foreground service. Only one backend is
   ever live at a time.

## RTVI events → UI state

| RTVI event | What the app does with it |
|---|---|
| `onConnected` / `onDisconnected` | Session lifecycle; only a mid-session drop raises "Connection lost". |
| `onBotReady` | Logs RTVI readiness / version. |
| `onBackendError(msg)` | Snackbar "Server error" while connected. |
| `onUserStartedSpeaking` | `activeSource = USER` (tints the visualizer) + locally stops playback for barge-in. |
| `onUserStoppedSpeaking` | Starts the **silence→first-audio** stopwatch; sets `isThinking`. |
| `onBotStartedSpeaking` | Stops the stopwatch → `lastTurnMs` (the HUD number); `activeSource = BOT`. |
| `onBotStoppedSpeaking` | Clears `activeSource`; advances the bot transcript turn counter. |
| `onUserTranscript` | User bubbles — interim text replaces one row; `final` advances the id. |
| `onBotTranscript` | Assistant bubbles — sentence chunks accumulate within a turn. *(Deprecated in 1.x → `onBotOutput`; still fires.)* |
| `onUserAudioLevel` / `onRemoteAudioLevel` | Wired but **never fired** by the SmallWebRTC 1.1.0 client — it emits no levels. Kept ready for a future server. |

## The client ⇄ server contract

The full contract lives in
[CLIENT_SERVER_CONTRACT.md](CLIENT_SERVER_CONTRACT.md). The short version — the
server must:

- Answer `POST /api/offer` and return `SmallWebRTCRequestHandler`'s answer verbatim.
- Run an `RTVIProcessor` + `RTVIObserver` so the app gets speaking/transcript events.
- Enable VAD + interruptions for barge-in.
- Seed a greeting on `on_client_connected` — the bot speaks first.

**AEC3 on both ends** — echo cancellation is built into libwebrtc on client and
server; do not disable it, or the bot transcribes itself. **Not in v1:** voice
selection — the app sends no voice ID.

## The "agent" model — a backend is just data

Every agent speaks the identical WebRTC+RTVI contract, so the **only** thing
that differs is the offer URL the transport POSTs to — plus cosmetics: a label,
an accent color, and a per-agent visualizer. Adding a backend is adding a row —
no code. One ships seeded from build config (`OPENCLAW_SERVER_URL` is required
by the build); after first launch it's an ordinary editable/deletable row, never
recomputed from build config.

| id | Title | Source | Idea |
|---|---|---|---|
| `openclaw` | OpenClaw | `OPENCLAW_SERVER_URL` | The agentic backend — the conference demo, the default selection |

## Visualizers — a per-agent choice

Each agent picks its own session visualizer in the agent editor (a radio list
that enumerates `VisualizerStyle.entries`). The choice is persisted on the agent
as a plain string key, so an unknown future key degrades to the default instead
of failing to deserialize. `VoiceAgentScreen` is the single branch point.

| Key | Style | File | What it looks like |
|---|---|---|---|
| `scope` *(default)* | Oscilloscope | `ScopeVisualizer.kt` | Phosphor-scope trace — terminal green for the bot, cyan for the user — pulsing with speech cadence. |
| `bases` | Running the Bases | `BasesVisualizer.kt` | Dodger-blue ballpark. A glowing runner sprints the bases while someone speaks, holds the nearest bag in silence, scores runs on a scoreboard pill. Runner tint follows the speaker. |
| `orb` | Orange Orb | `OrbVisualizer.kt` | The Cliff sibling app's additive amber/bronze orb stack; breathing, spread, spin and wobble track speech, with a warm/cool center bloom per speaker. |

All three run on the same **synthesized speech envelope** (`level` +
`speechPulse` + `activeSource`) — the transport delivers no real audio levels.
Adding a style is one `VisualizerStyle` entry plus one branch in
`VoiceAgentScreen`; the editor's picker needs no change.

## Latency HUD — the headline number

**`onUserStoppedSpeaking` → `onBotStartedSpeaking`** = silence → first bot
audio, per turn, via monotonic `SystemClock.elapsedRealtime()`. Measured purely
client-side, so it's the user-perceived responsiveness — not a server-reported
metric. Keeping `enable_metrics=True` server-side lets the turn-detect / LLM
TTFB / TTS TTFB breakdown line up for cross-checking.

## Known quirks

- **No client-side audio levels.** The SmallWebRTC RTVI client never emits
  amplitude, so all three visualizers are event-driven — they animate on a
  synthesized envelope rather than true amplitude. The level hooks are wired but
  stay at 0.
- **Voice selection is server-side in v1.** The app sends no voice ID; switching
  would need an RTVI custom message on both ends.
- **`onBotTranscript` is deprecated** in the 1.x SDK (in favor of
  `onBotOutput`) but still fires; migrating is a follow-up.

## Dependencies

| | |
|---|---|
| Pipecat | `ai.pipecat:small-webrtc-transport:1.1.0` (+ client 1.1.0, libwebrtc, RTVI 1.0.0) |
| Compose | BOM 2024.10.01 · Material 3 1.3.0 |
| Room | androidx.room 2.6.1 (KSP) |
| Coroutines | kotlinx-coroutines-android 1.9.0 |
| OkHttp | okhttp 4.12.0 (URL parsing only) |
| Serialization | kotlinx-serialization-json 1.6.3 |
| Lifecycle | androidx.lifecycle 2.8.6 |
| Toolchain | Kotlin · JDK 21 · compileSdk 35 |

*Package id `com.m15.pica` is historical and slated to be renamed.*