"""Per-turn latency logger built on Pipecat's UserBotLatencyObserver.

Pipecat already ships an observer that measures user-stop → bot-speaks
latency using the framework's internal frame IDs (the right way to dedupe
across the many pipeline edges each frame crosses). With ``enable_metrics=True``
on the pipeline params, that observer also collects per-service TTFB and emits
a ``LatencyBreakdown``.

We just attach event handlers and print one tidy line per turn:

    ⏱  turn 3 │ total 412ms │ STT 312ms (DeepgramSTTService) │
                            LLM 184ms (OLLamaLLMService nova-3) │
                            TTS 246ms (CartesiaTTSService)
"""

from __future__ import annotations

from loguru import logger

from pipecat.observers.user_bot_latency_observer import (
    LatencyBreakdown,
    UserBotLatencyObserver,
)


def attach_latency_logger(observer: UserBotLatencyObserver) -> None:
    """Wire INFO-level logging onto ``observer``'s latency events."""

    state = {"turn": 0, "total_ms": None}

    @observer.event_handler("on_latency_measured")
    async def _on_total(_obs, seconds: float) -> None:
        state["turn"] += 1
        state["total_ms"] = seconds * 1000
        # Breakdown fires immediately after — we log there so the line is whole.

    @observer.event_handler("on_latency_breakdown")
    async def _on_breakdown(_obs, breakdown: LatencyBreakdown) -> None:
        legs: list[str] = []

        # The big one most people miss: time from actual silence to
        # UserStoppedSpeakingFrame — VAD stop_secs + Deepgram finalize +
        # turn-stop strategy wait. Usually dominates total.
        if breakdown.user_turn_secs is not None:
            legs.append(f"turn-detect {breakdown.user_turn_secs * 1000:.0f}ms")

        for m in breakdown.ttfb:
            kind = _short_kind(m.processor)
            tag = f"{m.processor}"
            if getattr(m, "model", None):
                tag += f" {m.model}"
            legs.append(f"{kind} {m.duration_secs * 1000:.0f}ms ({tag})")

        if breakdown.text_aggregation:
            ta = breakdown.text_aggregation
            legs.append(f"text-agg {ta.duration_secs * 1000:.0f}ms ({ta.processor})")

        total = state["total_ms"]
        total_str = f"{total:.0f}ms" if total is not None else "—"
        legs_str = " │ ".join(legs) if legs else "(no breakdown — set enable_metrics=True)"
        logger.info(f"⏱  turn {state['turn']} │ total {total_str} │ {legs_str}")

    @observer.event_handler("on_first_bot_speech_latency")
    async def _on_first(_obs, seconds: float) -> None:
        logger.info(f"⏱  greeting up in {seconds * 1000:.0f}ms")


def _short_kind(processor_name: str) -> str:
    """Map e.g. 'DeepgramSTTService' → 'STT' for a tidy log line."""
    n = processor_name.lower()
    if "stt" in n or "deepgram" in n:
        return "STT"
    if "tts" in n or "cartesia" in n:
        return "TTS"
    if "llm" in n or "ollama" in n or "openai" in n:
        return "LLM"
    return "svc"
