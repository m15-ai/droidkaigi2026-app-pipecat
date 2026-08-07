"""Networked Pipecat server for the Pica Android client.

This is the prototype `main.py` loop with its transport swapped from
`LocalAudioTransport` (mic/speaker on this box) to a per-connection
`SmallWebRTCTransport` behind a FastAPI `POST /api/offer` SDP endpoint. The
phone POSTs a WebRTC offer, we answer, and the same Deepgram → Ollama → Cartesia
pipeline runs once per connection. Everything below the transport — STT, LLM,
TTS, Silero VAD, the turn strategies, the sliding-window history, the qwen3
reasoning-model guard, and the latency observer — is identical to `main.py`.

Run:

    venv/bin/python -m uvicorn server:app --host 0.0.0.0 --port 7860

(Or `python server.py`, which calls uvicorn for you.) No `pw-jack` — there is no
local audio device in play anymore; audio rides WebRTC to/from the phone.

Pair with the Pica Android app, whose `PICA_SERVER_URL` points at
`http://<this-host>:7860`. On a flat Tailscale network the phone reaches us over
the host ICE candidate directly, so no STUN/TURN is needed; off-tailnet use would
require a TURN server.
"""

from __future__ import annotations

import asyncio
import os
import sys

from dotenv import load_dotenv
from loguru import logger

from pipecat.audio.vad.silero import SileroVADAnalyzer
from pipecat.audio.vad.vad_analyzer import VADParams
from pipecat.frames.frames import LLMRunFrame
from pipecat.pipeline.pipeline import Pipeline
from pipecat.pipeline.worker import PipelineParams, PipelineWorker
from pipecat.processors.aggregators.llm_context import LLMContext
from pipecat.processors.aggregators.llm_response_universal import (
    LLMContextAggregatorPair,
    LLMUserAggregatorParams,
)
from pipecat.processors.frameworks.rtvi import (
    RTVIConfig,
    RTVIObserver,
    RTVIProcessor,
)
from pipecat.services.cartesia.tts import CartesiaTTSService
from pipecat.services.deepgram.stt import DeepgramSTTService
from pipecat.services.ollama.llm import OLLamaLLMService
from pipecat.transports.smallwebrtc.transport import SmallWebRTCTransport
from pipecat.transports.base_transport import TransportParams
from pipecat.turns.user_start.vad_user_turn_start_strategy import (
    VADUserTurnStartStrategy,
)
from pipecat.turns.user_stop.speech_timeout_user_turn_stop_strategy import (
    SpeechTimeoutUserTurnStopStrategy,
)
from pipecat.turns.user_turn_strategies import UserTurnStrategies
from pipecat.observers.user_bot_latency_observer import UserBotLatencyObserver
from pipecat.workers.runner import WorkerRunner

from latency import attach_latency_logger


load_dotenv()

# Pipecat is loud at INFO; bump to DEBUG with LOG_LEVEL=DEBUG.
logger.remove()
logger.add(sys.stderr, level=os.getenv("LOG_LEVEL", "INFO"))


SYSTEM_PROMPT = (
    "You are Pica, a friendly, brief voice assistant. "
    "Reply in one or two short sentences. Plain text only — no markdown, "
    "no lists, no emoji, no code. Spell out numbers and URLs. "
    "If the user interrupts you, stop and listen."
)

# Sliding-window history cap. qwen3 (and qwen2.5) re-prefill the entire context
# every turn, so unbounded history makes LLM latency climb linearly. Keep the
# leading system/developer messages plus the most recent MAX_HISTORY_MESSAGES
# conversation messages so prefill (and therefore LLM latency) stays flat.
MAX_HISTORY_MESSAGES = int(os.getenv("MAX_HISTORY_MESSAGES", "8"))

HOST = os.getenv("PICA_HOST", "0.0.0.0")
PORT = int(os.getenv("PICA_PORT", "7860"))


def _sliding_window(messages: list) -> list:
    """Trim conversation history to the last MAX_HISTORY_MESSAGES messages.

    Preserves all leading system/developer messages (the persona/instructions)
    and only windows the user/assistant tail. Won't open the window on an
    assistant reply, which would leave it dangling with no preceding user turn.
    """
    head_end = 0
    while head_end < len(messages) and messages[head_end].get("role") in (
        "system",
        "developer",
    ):
        head_end += 1

    head, convo = messages[:head_end], messages[head_end:]
    if len(convo) <= MAX_HISTORY_MESSAGES:
        return messages

    tail = convo[-MAX_HISTORY_MESSAGES:]
    while tail and tail[0].get("role") == "assistant":
        tail = tail[1:]
    return head + tail


def _require_env(name: str) -> str:
    value = os.getenv(name)
    if not value:
        raise SystemExit(f"Missing required env var: {name} (see .env)")
    return value


async def run_bot(transport: SmallWebRTCTransport) -> None:
    """Build and run the voice pipeline for a single WebRTC connection.

    This is the body of the prototype's ``run()``, minus the local-audio
    transport (passed in) and the runner setup (we await the worker here so the
    pipeline lives exactly as long as the connection).
    """
    deepgram_key = _require_env("DEEPGRAM_API_KEY")
    cartesia_key = _require_env("CARTESIA_API_KEY")

    ollama_url = os.getenv("OLLAMA_URL", "http://localhost:11434/v1")
    ollama_model = os.getenv("OLLAMA_MODEL", "qwen2.5:0.5b")
    cartesia_voice = os.getenv(
        "CARTESIA_VOICE_ID", "a167e0f3-df7e-4d52-a9c3-f949145efdab"
    )
    cartesia_model = os.getenv("CARTESIA_MODEL", "sonic-2")

    stt = DeepgramSTTService(
        api_key=deepgram_key,
        sample_rate=16000,
        # Defaults give nova-3 + interim_results — exactly what we want for barge-in.
    )

    llm = OLLamaLLMService(
        base_url=ollama_url,
        settings=OLLamaLLMService.Settings(model=ollama_model),
    )

    tts = CartesiaTTSService(
        api_key=cartesia_key,
        sample_rate=24000,
        settings=CartesiaTTSService.Settings(
            voice=cartesia_voice,
            model=cartesia_model,
        ),
    )

    # VAD-driven turn taking: start a turn the moment Silero hears speech
    # (enabling interruptions), stop after trailing silence. Total end-of-turn
    # latency = stop_secs (0.2) + user_speech_timeout (0.2) = 0.4 s.
    vad = SileroVADAnalyzer(
        sample_rate=16000,
        params=VADParams(stop_secs=0.2),
    )
    turn_strategies = UserTurnStrategies(
        start=[VADUserTurnStartStrategy(enable_interruptions=True)],
        stop=[SpeechTimeoutUserTurnStopStrategy(user_speech_timeout=0.2)],
    )

    # Hybrid-reasoning qwen3 variants emit <think> output that, over the
    # OpenAI-compatible /v1 endpoint, lands in the content stream and gets spoken
    # aloud. Warn rather than patch — stripping reasoning still costs the latency.
    _model = ollama_model.lower()
    if "qwen3" in _model and "instruct" not in _model:
        logger.warning(
            f"{ollama_model} is a reasoning model; over /v1 its <think> output will "
            "be spoken aloud. Use qwen3:4b-instruct for the voice loop."
        )

    context = LLMContext(messages=[{"role": "system", "content": SYSTEM_PROMPT}])
    aggregators = LLMContextAggregatorPair(
        context,
        user_params=LLMUserAggregatorParams(
            vad_analyzer=vad,
            user_turn_strategies=turn_strategies,
        ),
    )

    # Trim history right after each assistant turn lands in the context, so the
    # next LLM call sees a bounded prompt. Fires once per turn (post-append).
    @aggregators.assistant().event_handler("on_assistant_turn_stopped")
    async def _trim_context(_aggregator, _message):
        before = len(context.messages)
        context.transform_messages(_sliding_window)
        dropped = before - len(context.messages)
        if dropped:
            logger.debug(f"context window: dropped {dropped} old message(s)")

    # RTVI: the Android client speaks the RTVI protocol. The processor handles
    # inbound client messages and the client/server handshake (it emits the
    # bot-ready the client waits on); the observer (added to the worker below)
    # translates pipeline frames — transcripts, speaking start/stop, metrics —
    # into the RTVI messages the client's PipecatEventCallbacks fire on.
    rtvi = RTVIProcessor(config=RTVIConfig(config=[]))

    pipeline = Pipeline(
        [
            transport.input(),
            rtvi,
            stt,
            aggregators.user(),
            llm,
            tts,
            transport.output(),
            aggregators.assistant(),
        ]
    )

    # enable_metrics turns on per-service TTFB collection, which both the latency
    # observer (server logs) and the RTVI observer (client HUD) read.
    latency_observer = UserBotLatencyObserver()
    attach_latency_logger(latency_observer)

    worker = PipelineWorker(
        pipeline,
        params=PipelineParams(
            audio_in_sample_rate=16000,
            audio_out_sample_rate=24000,
            enable_metrics=True,
        ),
        observers=[latency_observer, RTVIObserver(rtvi)],
    )

    # Seed a user-side "greet the user" so the LLM speaks first — but only once
    # the phone has actually connected (the pipeline is per-connection now, so we
    # hang the greeting off the client-connected event rather than pipeline-start).
    @transport.event_handler("on_client_connected")
    async def _on_client_connected(_transport, _client):
        logger.info("Client connected — Pica greeting.")
        context.messages.append(
            {
                "role": "user",
                "content": "Briefly greet the user and offer your help.",
            }
        )
        await worker.queue_frame(LLMRunFrame())

    @transport.event_handler("on_client_disconnected")
    async def _on_client_disconnected(_transport, _client):
        logger.info("Client disconnected.")

    runner = WorkerRunner(handle_sigint=False)
    await runner.add_workers(worker)
    await runner.run()


# --- FastAPI signaling: POST /api/offer ------------------------------------

from contextlib import asynccontextmanager  # noqa: E402

from fastapi import BackgroundTasks, FastAPI  # noqa: E402
from fastapi.responses import JSONResponse  # noqa: E402
from pipecat.transports.smallwebrtc.request_handler import (  # noqa: E402
    SmallWebRTCRequestHandler,
)

# One handler process-wide; it tracks live peer connections and lets the same
# pc_id renegotiate (e.g. ICE restart) instead of spinning up a second bot.
_webrtc_handler = SmallWebRTCRequestHandler()


@asynccontextmanager
async def _lifespan(_app: FastAPI):
    logger.info(f"Pica server up on {HOST}:{PORT} — POST /api/offer to connect.")
    yield
    await _webrtc_handler.close()


app = FastAPI(lifespan=_lifespan)


@app.get("/health")
async def health():
    return {"status": "ok"}


@app.post("/api/offer")
async def offer(request: dict, background_tasks: BackgroundTasks):
    """Accept a WebRTC SDP offer from the phone, return our answer.

    On a fresh connection we build a SmallWebRTCTransport and launch run_bot()
    as a background task; the answer goes back immediately so ICE can complete.
    """

    async def on_connection(connection):
        transport = SmallWebRTCTransport(
            webrtc_connection=connection,
            params=TransportParams(
                audio_in_enabled=True,
                audio_out_enabled=True,
                # VAD/turn-taking live in the aggregator; the transport just needs
                # to forward inbound audio downstream so STT + VAD can see it.
                audio_in_passthrough=True,
                vad_analyzer=SileroVADAnalyzer(
                    sample_rate=16000, params=VADParams(stop_secs=0.2)
                ),
            ),
        )
        background_tasks.add_task(run_bot, transport)

    answer = await _webrtc_handler.handle_web_request(
        request=request,
        webrtc_connection_callback=on_connection,
    )
    return JSONResponse(content=answer)


if __name__ == "__main__":
    import uvicorn

    try:
        uvicorn.run(app, host=HOST, port=PORT)
    except KeyboardInterrupt:
        pass
