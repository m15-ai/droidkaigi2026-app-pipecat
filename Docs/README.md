# Pica docs

Client-side documentation for the Pica Android app. The server side of the
demo is **Homer** — a baseball voice agent (a NousResearch hermes-agent brain
behind a Pipecat WebRTC voice stack) — and lives in its own repo:

**<https://github.com/m15-ai/droidkaigi2026-homer-server>**

- Homer's **`HOMER.md`** — what he is and how a turn flows
- Homer's **`SERVER.md`** — build the server yourself, from zero
- Part of the [DroidKaigi 2026 demo hub](https://github.com/m15-ai/droidkaigi2026)

## What's in this folder

| File | What it is |
|---|---|
| [ARCHITECTURE.md](ARCHITECTURE.md) | The app's architecture — **start here on GitHub** |
| [ARCHITECTURE.html](ARCHITECTURE.html) | The same, prettier — open in a browser |
| [architecture.json](architecture.json) | The same architecture as structured data |
| [CLIENT_SERVER_CONTRACT.md](CLIENT_SERVER_CONTRACT.md) | Exactly what the app requires from **any** server: the `/api/offer` shape, the audio contract, and the RTVI events the app consumes |

## The one-paragraph version

Pica is a thin client: the phone is just a great microphone and speaker with a
nice UI. It POSTs a WebRTC SDP offer to a Pipecat server's `/api/offer`, media
flows peer-to-peer as Opus, and RTVI events ride the same link to drive the
chat, latency HUD, and per-agent visualizer. Every server that implements the
[contract](CLIENT_SERVER_CONTRACT.md) works — Homer is one of them. Swapping
the STT, LLM, TTS, or the entire brain behind the pipeline is a server change
the app never sees.