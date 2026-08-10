<p align="center">
  <img src="app/src/main/ic_launcher-playstore.png" alt="Pica app icon" width="128">
</p>

# Pica — Pipecat Voice Client for Android

> Part of the [DroidKaigi 2026 demo suite](https://github.com/m15-ai/droidkaigi2026) — see the
> top-level repo for the session overview and the sibling demo apps.

Pica is a **thin** Android voice client for [Pipecat](https://github.com/pipecat-ai/pipecat).
You speak into the phone; a Pipecat server does all the work — speech-to-text, LLM,
text-to-speech, turn detection, barge-in — and streams audio back. The phone is just
a good microphone and speaker with a nice UI on top.

It connects over a **direct peer-to-peer WebRTC** link using the official
[Pipecat Android SDK](https://github.com/pipecat-ai/pipecat-client-android) and the
**RTVI** protocol. No JWT, no SFU, no rooms — signaling is literally "POST an SDP
offer to `/api/offer`." That simplicity is the whole point.

> Pica is one of three Android voice apps presented together at the **Android Voice**
> conference, each exploring a different point in the design space:
> - **[Cliff](../Cliff)** — cloud streaming pipeline wired by hand (Deepgram + Claude + Deepgram), client owns the orchestration.
> - **[GVP](../GVP)** — fully **on-device**, no network: Sherpa-ONNX STT + MediaPipe LLM + Android TTS.
> - **Pica** (this app) — **thin client over a Pipecat server**: the server owns the pipeline, the phone owns the audio.

## Talk Highlights

Presenting Pica at the Android developers conference — the demo beats and the
engineering points worth landing on stage:

- **The thesis in one line.** The phone is just a great mic and speaker; *all*
  intelligence — STT, LLM, TTS, turn-taking, barge-in — lives on a Pipecat server.
  Swapping the LLM or TTS voice is a server change the app never sees.
- **Radically simple signaling.** No JWT, no SFU, no rooms. Connecting is literally
  "POST an SDP offer to `/api/offer`" — one HTTP request, then the media flows
  peer-to-peer.
- **The whole SDK lives in one file.** The entire Pipecat surface is quarantined behind
  a transport-agnostic `Listener` in `PicaVoiceClient.kt`. The recent **major SDK jump
  (0.3.x → 1.1.0, RTVI 1.0.0)** touched exactly that one file — nothing else in the app
  moved. A clean argument for isolating your vendor SDK.
- **A number you can defend.** The **latency HUD** measures *silence → first bot audio*
  purely client-side (`onUserStoppedSpeaking` → `onBotStartedSpeaking`) on a monotonic
  clock — the user-perceived number, not a server-reported one.
- **Feels live.** Barge-in stops local playback the instant you speak — before the
  server's interrupt even round-trips — and AEC3 echo cancellation comes free from
  `libwebrtc` on both ends.
- **Polished on modern Android.** Edge-to-edge with proper `WindowInsets` handling
  (the header clears the status bar and camera cutout), a foreground service that keeps
  the session alive off-screen, and hot-plug-aware speaker/Bluetooth/wired routing.

## How It Works

```
                          ┌─────────── Pipecat server (the "agent") ───────────┐
 🎙️ mic ──▶ libwebrtc ──WebRTC/Opus──▶  Deepgram STT → "LLM" slot → Cartesia TTS │
 🔊 spk ◀── libwebrtc ◀──WebRTC/Opus──  (e.g. Homer's agent)  + VAD / barge-in   │
   (AEC3)                  └────────────────────────────────────────────────────┘
            │
            └─ RTVI events (speaking/transcript) ──▶ visualizer, chat, latency HUD
```

1. **Tap an agent, tap start.** Pica's SDK POSTs a WebRTC SDP offer to that agent's
   `/api/offer`; the server answers; ICE completes; media flows P2P.
2. **The bot greets you first** — the server seeds a greeting on connect, so you hear
   Pica before saying anything (confirms the loop is alive end to end).
3. **You talk.** `libwebrtc` captures the mic with **AEC3 echo cancellation built in**,
   encodes Opus, and ships it to the server. The server's VAD + Deepgram transcribe it.
4. **The server answers** through its LLM and Cartesia TTS, streaming Opus audio back
   to the phone's speaker.
5. **You interrupt** — Pica stops local playback the instant you start speaking, and
   the server's VAD/interruption catches up. Conversation feels live.

Everything except audio capture/playback lives on the server. Swapping the LLM, TTS
voice, or even the entire pipeline is a server change — the app never knows.

## Features

- **Pure Pipecat path** — built on the official `ai.pipecat:small-webrtc-transport`
  SDK (**1.1.0**, RTVI protocol 1.0.0); one P2P WebRTC connection, no auth handshake.
- **Multiple agents, one tap to switch** — keep a list of Pipecat backends (host +
  port + path) and pick which one to talk to. Ships seeded with three (**Hermes**,
  **Direct**, **OpenClaw**); add/edit/delete your own. Each gets an accent color and
  badge.
- **Bot-speaks-first greeting** — no awkward "is this thing on?"; the agent talks
  immediately on connect.
- **Barge-in** — local TTS playback stops the moment you speak, before the server's
  interrupt even round-trips.
- **Latency HUD** — live **silence → first-bot-audio** readout per turn, measured
  purely client-side: the user-perceived responsiveness number.
- **Selectable per-agent visualizers** — each agent picks its own session visualizer
  in the editor: **Oscilloscope** (phosphor trace — terminal green for the bot, cyan
  for you), **Running the Bases** (a ballpark where a glowing runner sprints the bases
  while someone speaks and scores runs on a scoreboard), or **Orange Orb** (an additive
  amber particle orb ported from the Cliff sibling app). All pulse with the live
  speech cadence.
- **Conversation history** — transcripts persist to a local Room DB; save, rename,
  delete, and share past conversations from a drawer.
- **Survives the screen turning off** — a microphone foreground service keeps the
  session alive when backgrounded.
- **Smart audio routing** — speaker / wired / Bluetooth SCO / earpiece selection via
  `AudioManager`, with hot-plug detection.

## Architecture

Single-Activity Jetpack Compose app, MVVM, manual DI via `ServiceLocator`. The entire
Pipecat SDK surface is quarantined in **one file** (`PicaVoiceClient`) behind a
transport-agnostic `Listener` interface — everything else just reacts to events.

| Layer | Tech |
|-------|------|
| UI | Jetpack Compose + Material 3 |
| State | Kotlin Coroutines + Flow, `StateFlow`, MVVM |
| Transport | Pipecat `SmallWebRTCTransport` (`libwebrtc`), RTVI protocol |
| Signaling | `POST /api/offer` — SDP offer/answer, P2P |
| Audio | `libwebrtc` capture/playback with AEC3; `AudioManager` routing |
| Persistence | Room DB (sessions + messages) |
| Networking | OkHttp 4 (URL parsing); WebRTC owns the media path |
| DI | `ServiceLocator` (manual) |

```
com.m15.pica
├── MainActivity.kt              Compose host: setup / live session / history viewer
├── App.kt                       ServiceLocator init
├── VoiceAgentViewModel.kt       Session lifecycle, RTVI→UI mapping, latency HUD, persistence
├── ServerEndpoint.kt            One selectable Pipecat backend ("agent") + the seeded three
├── VisualizerStyle.kt           Enum of per-agent visualizers (scope / bases / orb)
├── MicForegroundService.kt      Microphone foreground service (keeps session alive)
├── net/
│   └── PicaVoiceClient.kt       THE ONLY file touching the Pipecat SDK; wraps PipecatClient
│                                + SmallWebRTCTransport, fans events to a Listener
├── di/ServiceLocator.kt         Manual DI (repo, AudioManager)
├── prefs/PicaLocalPrefs.kt      Agent list + selection + speaker pref (JSON in prefs)
├── data/
│   ├── db/                      Room: AppDatabase, Entities, SessionDao, MessageDao
│   └── repo/ConversationRepository.kt
├── ui/
│   ├── SetupScreen.kt           Agent list: select / add / edit / delete (+ visualizer picker)
│   ├── VoiceAgentScreen.kt      Live session: visualizer, transcript, speaker/save controls
│   ├── ScopeVisualizer.kt       Visualizer: phosphor oscilloscope trace (default)
│   ├── BasesVisualizer.kt       Visualizer: Running the Bases ballpark
│   ├── OrbVisualizer.kt         Visualizer: Orange Orb particle stack
│   ├── ConversationDrawer.kt    Saved-conversation history drawer
│   ├── ConversationViewerScreen.kt  Read-only transcript + share
│   └── PicaSessionHost.kt       Connects/disconnects the client around the session UI
└── util/StringUtils.kt
```

### Pipeline flow

1. `PicaSessionHost` calls `VoiceAgentViewModel.connect()`, which builds a fresh
   `PicaVoiceClient` for the selected agent's URL and connects.
2. `PicaVoiceClient` opens the P2P WebRTC link and translates `PipecatEventCallbacks`
   into the app's `Listener` events.
3. The ViewModel maps those events to UI state: speaking → the agent's visualizer,
   transcripts → chat bubbles (persisted to Room), `userStopped → botStarted` → the
   latency HUD.
4. Only **one** backend is ever live at a time — switching agents tears the client
   fully down before bringing the next up.

## The "agent" model

A Pipecat backend is just data: `host`, `port`, `path` (default `/api/offer`), scheme,
a label/accent, and a visualizer style. Because every agent speaks the identical WebRTC+RTVI contract, the
*only* thing that differs between them is the offer URL the transport POSTs to. Adding
a backend is adding a row — no code. The three seeds come from build config:

| Seed | Source | Idea |
|------|--------|------|
| **Hermes** | `HERMES_SERVER_URL` | A voice persona (Kuri) on the Pi, one port |
| **Direct** | `PICA_SERVER_URL` | The straight Pipecat voice loop |
| **OpenClaw** | `OPENCLAW_SERVER_URL` | An agentic backend on the Pi, another port |

After first launch these are ordinary editable/deletable rows — they are not
recomputed from build config.

## The Server

Pica is a client-only app. It needs a Pipecat server exposing `POST /api/offer` — any
server that implements the contract works. The demo server is
**[Homer](https://github.com/m15-ai/droidkaigi2026-homer-server)**, a baseball voice
agent (a NousResearch hermes-agent brain behind a Pipecat WebRTC voice stack,
answering MLB questions from live data), in its own repo:

- **Homer's `SERVER.md`** — build the server yourself, from zero.
- **Homer's `HOMER.md`** — what he is and how a turn flows.
- **[`Docs/CLIENT_SERVER_CONTRACT.md`](Docs/CLIENT_SERVER_CONTRACT.md)** — exactly what
  the app requires from **any** server: the `/api/offer` shape, the audio contract, and
  the RTVI events the app consumes.

The minimum the server must do: answer `/api/offer`, run an `RTVIProcessor` +
`RTVIObserver` (so the app gets speaking/transcript events), enable VAD + interruptions
for barge-in, and seed a greeting on `on_client_connected`.

## Setup

**Prerequisites**
- Android Studio (Ladybug or later), JDK 21, Android SDK 35
- A reachable Pipecat server (see [The Server](#the-server))
- `minSdk 26`

**1. Point the app at your server(s)** in `local.properties`:

```properties
PICA_SERVER_URL=http://100.70.131.13:7860/api/offer
HERMES_SERVER_URL=http://100.70.131.13:7861/api/offer
OPENCLAW_SERVER_URL=http://100.70.131.13:7862/api/offer
```

All three are required by the build (`build.gradle.kts` fails fast if any is missing).
They can also be set as environment variables. Each is the **full** offer endpoint —
the transport POSTs to it verbatim.

**2. Allow cleartext to your server** (if it's plain HTTP on a LAN/tailnet) in
`app/src/main/res/xml/network_security_config.xml`. The repo allows a Tailscale host
and IP as an example; add yours.

**3. Build and run**

```bash
./gradlew installDebug
```

Pick an agent on the setup screen, tap start, grant the mic permission, and Pica
greets you.

## Tech Stack

- **Kotlin** · Jetpack Compose · Material 3 · Coroutines/Flow (MVVM)
- **[Pipecat Android SDK](https://github.com/pipecat-ai/pipecat-client-android)** —
  `ai.pipecat:small-webrtc-transport:1.1.0` (pulls `ai.pipecat:client:1.1.0` +
  `libwebrtc`; RTVI protocol 1.0.0)
- **Room** — local conversation persistence (KSP)
- **OkHttp 4** — URL handling
- **RTVI** protocol over P2P WebRTC/Opus

## Notes & Known Quirks

- **No client-side audio levels.** The SmallWebRTC RTVI client never emits amplitude,
  so all the visualizers are **event-driven** — they animate on a speech envelope
  synthesized from transcript tokens and speaking start/stop rather than true
  amplitude. `onUserAudioLevel`/`onRemoteAudioLevel` are wired up anyway, ready to
  light up for free if a future server sends levels.
- **Voice selection is server-side in v1.** The app sends no voice ID; the server uses
  its configured Cartesia voice. Runtime switching would need an RTVI custom message on
  both ends.

---

*Package id `com.m15.pica` and the `Pica*` identifiers are historical and slated to be
renamed as the app generalizes beyond the prototype.*