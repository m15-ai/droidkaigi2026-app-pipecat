"""Pipecat voice-stack proof: mic → Deepgram → Ollama (Qwen) → Cartesia → speakers.

Run this through PipeWire's JACK shim so the audio shows up in qpwgraph:

    pw-jack venv/bin/python main.py

Press Ctrl-C to stop. Talk to Pica — Deepgram's interim results and Silero VAD
together give the barge-in behaviour: start speaking mid-reply and the TTS cuts.
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
from pipecat.services.cartesia.tts import CartesiaTTSService
from pipecat.services.deepgram.stt import DeepgramSTTService
from pipecat.services.ollama.llm import OLLamaLLMService
from pipecat.transports.local.audio import (
    LocalAudioTransport,
    LocalAudioTransportParams,
)
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

# Sliding-window history cap. qwen2.5:0.5b re-prefills the entire context every
# turn, so unbounded history makes LLM latency climb linearly (we measured it
# go 4x over ~15 turns). Keep the leading system/developer messages plus the
# most recent MAX_HISTORY_MESSAGES conversation messages so prefill stays flat.
MAX_HISTORY_MESSAGES = int(os.getenv("MAX_HISTORY_MESSAGES", "8"))


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


async def run() -> None:
    deepgram_key = _require_env("DEEPGRAM_API_KEY")
    cartesia_key = _require_env("CARTESIA_API_KEY")

    ollama_url = os.getenv("OLLAMA_URL", "http://localhost:11434/v1")
    ollama_model = os.getenv("OLLAMA_MODEL", "qwen2.5:0.5b")
    cartesia_voice = os.getenv(
        "CARTESIA_VOICE_ID", "a167e0f3-df7e-4d52-a9c3-f949145efdab"
    )
    cartesia_model = os.getenv("CARTESIA_MODEL", "sonic-2")

    # Deepgram works best at 16 kHz mono; Cartesia Sonic-2 gives us 24 kHz mono.
    transport = LocalAudioTransport(
        params=LocalAudioTransportParams(
            audio_in_enabled=True,
            audio_out_enabled=True,
            audio_in_sample_rate=16000,
            audio_out_sample_rate=24000,
            audio_in_channels=1,
            audio_out_channels=1,
            audio_in_passthrough=True,  # required: VAD sees the audio downstream
        )
    )

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
    # latency = stop_secs (0.2) + user_speech_timeout (0.2) = 0.4 s, down from
    # 0.8 s — snappier replies at the cost of cutting off slow/pausing talkers.
    # stop_secs=0.2 matches Pipecat's built-in STT latency assumptions.
    vad = SileroVADAnalyzer(
        sample_rate=16000,
        params=VADParams(stop_secs=0.2),
    )
    turn_strategies = UserTurnStrategies(
        start=[VADUserTurnStartStrategy(enable_interruptions=True)],
        stop=[SpeechTimeoutUserTurnStopStrategy(user_speech_timeout=0.2)],
    )

    # Hybrid-reasoning qwen3 variants emit reasoning that, over the OpenAI-compatible
    # /v1 endpoint pipecat uses, lands in the content stream and gets spoken aloud
    # (the /no_think token is unreliable and think:false isn't reachable via /v1).
    # For voice, prefer a non-reasoning model — e.g. qwen3:4b-instruct. We warn
    # rather than try to patch it, since stripping reasoning still costs the latency.
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

    pipeline = Pipeline(
        [
            transport.input(),
            stt,
            aggregators.user(),
            llm,
            tts,
            transport.output(),
            aggregators.assistant(),
        ]
    )

    # enable_metrics turns on per-service TTFB collection inside the
    # framework, which the latency observer reads to build the breakdown.
    latency_observer = UserBotLatencyObserver()
    attach_latency_logger(latency_observer)

    worker = PipelineWorker(
        pipeline,
        params=PipelineParams(
            audio_in_sample_rate=16000,
            audio_out_sample_rate=24000,
            enable_metrics=True,
        ),
        observers=[latency_observer],
    )

    # Kick off with a greeting so we know the stack is alive end-to-end:
    # seed a user-side "Greet the user" so the LLM speaks first.
    context.messages.append(
        {
            "role": "user",
            "content": "Briefly greet the user and offer your help.",
        }
    )

    @worker.event_handler("on_pipeline_started")
    async def _on_started(_worker, _frame):
        logger.info("Pipeline up — say hello to Pica.")
        await _worker.queue_frame(LLMRunFrame())

    runner = WorkerRunner(handle_sigint=True)
    await runner.add_workers(worker)
    await runner.run()


if __name__ == "__main__":
    try:
        asyncio.run(run())
    except KeyboardInterrupt:
        pass
