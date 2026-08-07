# Pipecat voice stack — Pica prototype

A minimal Python proof of the voice loop that the **Pica** Android app will
talk to. Today this binary runs the entire loop on one machine; tomorrow we
swap the local audio I/O for a network transport and Pica becomes a thin
mic/speaker client.

```
mic → PipeWire(JACK) → Deepgram STT → Ollama (qwen3:4b-instruct) → Cartesia TTS → speakers
```

| Piece | Why |
|---|---|
| **Deepgram** Nova-3 STT | Streaming transcripts + interim results — barge-in. |
| **Silero VAD** | Detects speech start to trigger interruption. |
| **Ollama / qwen3:4b-instruct** | Local LLM. On a GPU the 4B's TTFB ≈ the old 0.5b's, so it's ~free quality. Swap via `OLLAMA_MODEL`. **Must be the `-instruct` (non-reasoning) variant** — see [Model](#model). |
| **Cartesia** Sonic-2 TTS | Low-latency websocket TTS. |
| **Pipecat LocalAudioTransport** | PortAudio; PipeWire shims it onto JACK. |

## Setup

```bash
cd /home/mjw/Projects/Pipecat
python3 -m venv venv
venv/bin/pip install 'pipecat-ai[deepgram,cartesia,ollama,silero,local]' python-dotenv
ollama pull qwen3:4b-instruct   # non-reasoning 4B — NOT plain qwen3:4b (see Model)
ollama serve &           # if not already running
# .env already exists with keys mirrored from Projects/Lika/env
```

## Run

```bash
pw-jack venv/bin/python main.py
```

Run via PipeWire's JACK shim (`pw-jack`) so the client shows up in
`qpwgraph` / `helvum` and binds through the JACK graph like the other apps
on this machine. Then talk — bot greets you on start; interrupt by speaking
over it.

> **Use headphones.** No echo cancellation in this build, so without
> headphones the speaker bleeds into the mic and Deepgram transcribes Pica
> talking to herself. The Android client will solve this properly (see
> AEC section below).

## Measured latency (desktop baseline)

Per-turn timing is logged by [latency.py](latency.py), built on Pipecat's
`UserBotLatencyObserver`. Representative session: **qwen3:4b-instruct** on local
Ollama (RTX 5080), Cartesia over WAN, wired headset, after the tuning below:

```
⏱  turn 1 │ total 866ms │ turn-detect 401ms │ LLM 171ms │ TTS 206ms │ text-agg  85ms
⏱  turn 3 │ total 916ms │ turn-detect 402ms │ LLM 193ms │ TTS 194ms │ text-agg 124ms
⏱  turn 6 │ total 823ms │ turn-detect 402ms │ LLM 203ms │ TTS 194ms │ text-agg  23ms
```

- **`total`** is silence → first audio frame at the speaker.
- **`turn-detect`** = VAD `stop_secs` (200 ms) + `user_speech_timeout` (200 ms)
  = **0.4 s**, down from 0.8 s. Steady ~402 ms and the largest single chunk.
- **`LLM`** ≈ 180 ms TTFB. STT (Deepgram, ~300 ms) mostly runs *inside* the
  turn-detect window and is absorbed off the critical path — it only prints
  when it finalizes late.
- **`text-agg`** is Pipecat buffering text before TTS — the main jitter source now.

**Headline: ~0.85 s silence-to-speech**, down from ~1.2–1.6 s. The compute
floor (LLM + TTS ≈ 380 ms) is steady; turn detection is the remaining lever
and trades off against cutting people off. See *Tuning*.

**Sliding-window context cap.** qwen3 (and qwen2.5) re-prefill the *entire*
context every turn, so an unbounded transcript makes LLM latency climb linearly
(we watched it 4× over ~15 turns). [main.py](main.py) trims history to the last
`MAX_HISTORY_MESSAGES` (default 8) after every assistant turn, keeping the system
prompt — so prefill, and therefore LLM latency, stays **flat** regardless of
conversation length. The tradeoff is memory: Pica forgets past the last ~4 turns.

### On a GPU, the 4B is ~free

Swapping qwen2.5:0.5b → qwen3:4b-instruct (8× the params) moved average LLM
TTFB from ~180 ms to ~182 ms on the RTX 5080 — TTFB here is dominated by fixed
overhead, not model size. Smarter replies, same responsiveness. **This does not
hold without a GPU**: on CPU-only boxes (e.g. a Raspberry Pi 5) the 4B drops to
multi-second TTFB. For those, either keep the 0.5b locally or point
`OLLAMA_URL` at a GPU host and run the device as a thin client.

## Architecture for the Android client (Pica)

Pica is a **thin** client: mic → server, server → speaker. All STT/LLM/TTS
lives on the server. The Python loop above stays the same; the only change
is swapping `LocalAudioTransport` for a network transport. The LLM the server
calls is `qwen3:4b-instruct` on Ollama — read [Model](#model) before wiring up
the Ollama call (the reasoning-variant trap bites the client too).

We will build the **pure-Pipecat path first** so we can A/B it against the
existing **Lika/LiveKit** stack on the same device.

### Audio contract

This is what server and client must agree on. Pipecat handles it; Pica must
match.

| Direction | Format | Sample rate | Channels | Frame size |
|---|---|---:|---:|---|
| Mic → server | PCM 16-bit LE | 16 000 Hz | mono | ≥ 20 ms |
| Server → speaker | PCM 16-bit LE | 24 000 Hz | mono | ≥ 20 ms |

If the chosen transport supports Opus (LiveKit/WebRTC), use it — the codec
handles framing/resampling and the server resamples to/from the rates above.

### Required on the Android side

1. **Acoustic echo cancellation (AEC) is mandatory.** Without it, the bot
   transcribes itself and goes off the rails. Two free paths on Android:
   - **WebRTC AEC3** (best). Comes with the LiveKit Android SDK and with any
     WebRTC-based transport. Same canceller Meet/Zoom use.
   - **Android `AcousticEchoCanceler`** (`audiofx`) attached to the
     `AudioRecord` session by `audioSessionId`. Quality varies by OEM HAL;
     fine on Pixels/recent Samsungs, weak on cheap devices.
2. **Foreground service** for mic capture so the loop survives the screen
   turning off / app being backgrounded.
3. **Bluetooth audio path:** request `AudioManager.MODE_IN_COMMUNICATION`
   when active and a SCO path for BT headsets; A2DP-only headphones lack a
   mic so capture stays on the phone mic.
4. **Barge-in UX:** as soon as the user starts speaking, stop the local TTS
   playback immediately — even before the server's interruption signal
   round-trips. The server-side VAD/interrupt will catch up.
5. **Surface latency**: log the same three numbers the server logs
   (turn-detect, LLM TTFB, TTS TTFB) plus a client-side number for
   *network → speaker* so we can compare Pipecat-Pica vs Lika cleanly.

### Transport options

Two paths, pick one (we want to evaluate both):

**A. Pipecat `SmallWebRTCTransport`** — fastest to ship, server returns an
SDP answer over HTTP, Pica uses any standard WebRTC peer connection on the
client. Opus codec → AEC3 included. Closest apples-to-apples to LiveKit
(both are WebRTC), so a clean A/B against Lika.

**B. Pipecat WebSocket transport (PCM)** — simplest protocol, raw PCM
frames over a single WS. Lower transport overhead (~30–50 ms one-way) but
you own the AEC on the device.

Both transports plug into the *same* Pipecat pipeline; the LLM, STT, TTS,
turn-detection, and observers don't change.

### What an A/B against Lika tells us

Lika is the existing LiveKit-based stack. Comparing on the same Android
device gives us a clean signal:

- **Total silence-to-speech.** Apples-to-apples user-perceived latency.
- **Turn-detect time.** Both use Deepgram-like STT; this isolates the
  audio-path latency from the LLM/TTS layer.
- **Audio quality / AEC.** Subjective but real.
- **Battery / CPU.** WebRTC vs PCM-over-WS has measurable client cost.

## Model

The LLM is `qwen3:4b-instruct`, set in [.env](.env) via `OLLAMA_MODEL`.

> ⚠️ **Use `qwen3:4b-instruct`, not plain `qwen3:4b`.** The base/Thinking qwen3
> variants are *reasoning* models: they emit a `<think>…</think>` chain before
> the answer, and the toggles to disable it are unreliable over Ollama's
> OpenAI-compatible `/v1` endpoint (the `/no_think` prompt token is ignored, and
> `think:false` only exists on the native `/api/chat` endpoint, which Pipecat
> and any OpenAI-style client don't use). Result: **the entire reasoning chain
> lands in the streamed `content` and gets spoken aloud**, plus several seconds
> of added latency. We verified this directly. `qwen3:4b-instruct` is the
> non-reasoning build — clean one-shot answers, no `<think>`, ~180 ms TTFB.
>
> [main.py](main.py) logs a warning if `OLLAMA_MODEL` looks like a reasoning
> qwen3 (`qwen3` in the name without `instruct`).

**For the Android client:** whatever talks to Ollama — the Pipecat server, or
the app directly — must target `qwen3:4b-instruct` and the `/v1` endpoint. If a
reasoning model is ever swapped in, the client/server must strip everything up
to and including `</think>` before TTS, *and* eat the reasoning latency. Avoid
it; stay on `-instruct`.

## Tuning

In [main.py](main.py):

- `OLLAMA_MODEL` (in [.env](.env)) — `qwen3:4b-instruct` is the default. Drop to
  `qwen2.5:0.5b` for CPU-only / low-power hosts. See [Model](#model) for the
  reasoning-variant warning.
- `MAX_HISTORY_MESSAGES` env var (default 8) — sliding-window history cap. Higher
  = longer memory but LLM latency slowly rises again; lower = tighter but
  shorter memory. See the latency section.
- `CARTESIA_VOICE_ID`, `CARTESIA_MODEL` — pick from
  https://play.cartesia.ai.
- `SileroVADAnalyzer(params=VADParams(stop_secs=0.2))` — lower trims the
  total directly. 0.15 is the floor before false stops at natural pauses.
- `SpeechTimeoutUserTurnStopStrategy(user_speech_timeout=0.2)` — how long the
  strategy waits for Deepgram's final transcript after VAD says stop. Currently
  0.2 (was 0.6); with `stop_secs` this sets the ~0.4 s turn-detect floor. Below
  this, natural mid-sentence pauses start triggering false turn-ends.
- `LOG_LEVEL=DEBUG` env var — full frame trace.

## File map

| File | What it is |
|---|---|
| [main.py](main.py) | Pipeline definition + runner |
| [latency.py](latency.py) | Per-turn latency logger (wraps Pipecat's built-in observer) |
| [.env](.env) | API keys (gitignored) |
| `venv/` | Python env (gitignored) |
