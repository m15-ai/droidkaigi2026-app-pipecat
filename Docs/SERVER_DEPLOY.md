# Pica server — deploy & run (backend engineer guide)

This is everything needed to stand up the **Pica** voice server on the Pi so the
Pica Android app can connect. Read alongside [server.py](server.py) (the thing you
run), [main.py](main.py) (the original local-audio prototype, kept for reference),
and [README.md](README.md) (the pipeline rationale + latency notes).

> **TL;DR**: `server.py` is `main.py` with the local mic/speaker transport swapped
> for a networked **SmallWebRTCTransport** behind a FastAPI `POST /api/offer`
> endpoint, plus an RTVI layer so the phone gets transcripts/metrics. The
> Deepgram → Ollama → Cartesia pipeline is byte-for-byte the same.

---

## 1. What changed vs the prototype (`main.py` → `server.py`)

| Concern | `main.py` (prototype) | `server.py` (Pica) |
|---|---|---|
| Transport | `LocalAudioTransport` (Pi's own mic/speaker) | `SmallWebRTCTransport`, one per phone connection |
| Signaling | none (local) | FastAPI `POST /api/offer` (WebRTC SDP exchange) |
| Client events | none | `RTVIProcessor` in pipeline + `RTVIObserver` on the worker |
| Greeting | on `on_pipeline_started` | on transport `on_client_connected` (pipeline is per-connection) |
| Run command | `pw-jack venv/bin/python main.py` | `uvicorn server:app --host 0.0.0.0 --port 7860` |

Unchanged: Deepgram STT, Ollama `qwen3:4b-instruct`, Cartesia TTS, Silero VAD,
the turn-taking strategies, the sliding-window history trim, the qwen3
reasoning-model guard, and [latency.py](latency.py)'s per-turn logging.

---

## 2. Install

```bash
cd /home/mjw/Projects/Pipecat        # wherever the prototype venv lives
python3 -m venv venv                 # reuse the existing one if present
# Pipeline deps (same as the prototype) + the WebRTC transport + a web server:
venv/bin/pip install \
  'pipecat-ai[deepgram,cartesia,ollama,silero,webrtc]' \
  fastapi 'uvicorn[standard]' python-dotenv

ollama pull qwen3:4b-instruct        # NOT plain qwen3:4b — see README "Model"
ollama serve &                       # if not already running
```

> The exact extra name for the SmallWebRTC transport may be `webrtc` or
> `small-webrtc` depending on the installed pipecat-ai version. If
> `from pipecat.transports.smallwebrtc.transport import SmallWebRTCTransport`
> fails, `pip install pipecat-ai-small-webrtc` and adjust the import.

---

## 3. Configure (`.env`)

The `.env` already exists from the prototype (keys mirrored from `Projects/Lika/env`).
Pica reads:

```ini
DEEPGRAM_API_KEY=...                 # required
CARTESIA_API_KEY=...                 # required
OLLAMA_URL=http://localhost:11434/v1 # default; point elsewhere for a GPU host
OLLAMA_MODEL=qwen3:4b-instruct       # use the -instruct (non-reasoning) build
CARTESIA_VOICE_ID=...                # the v1 default voice (no in-app picker yet)
CARTESIA_MODEL=sonic-2
MAX_HISTORY_MESSAGES=8               # sliding-window cap (see README)
PICA_HOST=0.0.0.0                    # bind address (default)
PICA_PORT=7860                       # must match the app's PICA_SERVER_URL port
LOG_LEVEL=INFO                       # DEBUG for full frame trace
```

---

## 4. Run

```bash
venv/bin/python server.py
# equivalently:
venv/bin/python -m uvicorn server:app --host 0.0.0.0 --port 7860
```

No `pw-jack` — there is no local audio device in play; audio rides WebRTC
to/from the phone. On start you should see
`Pica server up on 0.0.0.0:7860 — POST /api/offer to connect.`

---

## 5. Verify before involving the phone

Two quick checks, in order:

1. **Health:** `curl http://localhost:7860/health` → `{"status":"ok"}`.
2. **Full loop in a browser** (no Android needed): point Pipecat's
   **small-webrtc-prebuilt** client at `http://<pi>:7860`
   (<https://github.com/pipecat-ai/small-webrtc-prebuilt>). You should hear Pica
   greet you, be able to interrupt it (barge-in), and watch the per-turn lines
   from `latency.py` print in the server log, e.g.
   `⏱  turn 1 │ total 866ms │ turn-detect 401ms │ LLM 171ms │ TTS 206ms`.

If the browser client works, the Android app will too — they speak the same
WebRTC + RTVI protocol.

---

## 6. Networking (Tailscale)

- The phone reaches the Pi over the **tailnet**; the server has no public listener.
- On a flat tailnet, WebRTC connects over the **host ICE candidate** (the Pi's
  `100.x` address) directly — **no STUN/TURN server needed**.
- If you ever run the phone *off* the tailnet, you'd need a TURN server; out of
  scope for v1.
- The app talks to `PICA_SERVER_URL` (e.g. `http://100.70.131.13:7860`). The `/api/offer`
  POST is plain HTTP, but the WebRTC **media is DTLS-encrypted** regardless, and
  the whole link rides WireGuard. The phone's `network_security_config.xml` must
  list the Pi's tailnet host/IP for the cleartext signaling POST to be allowed.

---

## 7. Known risk to confirm on the Pi

The prototype uses the newer `PipelineWorker` / `WorkerRunner` API (not the older
`PipelineTask` / `PipelineRunner` that most RTVI examples show). `server.py` wires
RTVI as `observers=[latency_observer, RTVIObserver(rtvi)]` plus an `RTVIProcessor`
in the pipeline list, and uses `SmallWebRTCRequestHandler` for `/api/offer`. **If
the installed pipecat-ai version exposes these differently**, the fixes are local
to `server.py`:
- RTVI attach point (worker `observers=` vs task-level observer).
- `SmallWebRTCRequestHandler` / `handle_web_request` import path and signature.
- `TransportParams` field names (e.g. `vad_analyzer`, `audio_in_passthrough`).

See [CLIENT_SERVER_CONTRACT.md](CLIENT_SERVER_CONTRACT.md) for what the app
strictly requires from the server (the `/api/offer` shape and the RTVI events the
client consumes) — keep those stable and the internal wiring can move freely.
