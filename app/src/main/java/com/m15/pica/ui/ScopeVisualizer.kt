package com.m15.pica.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.tooling.preview.Preview
import com.m15.pica.AudioSource
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sin

// Phosphor scope palette — terminal green core, cyan accent for the user's voice.
private val Phosphor = Color(0xFF66E08A) // matches the latency HUD green
private val Cyan     = Color(0xFF73E8FF) // bright cyan, user trace
private val GridDim  = Color(0xFF0E3B22) // dim graticule green

private const val BUFFER = 200          // history depth (samples), one pushed per frame
private val TWO_PI = (2.0 * PI).toFloat()

// Carrier density (wiggles across the width) as a function of the live signal:
// quiet ≈ a few slow waves; loud (and during fast onsets) ≈ many tight waves.
private const val BASE_CYCLES  = 1.17f  // ~1/3 of prior density — calmer trace
private const val LEVEL_CYCLES = 5.33f  // extra cycles at full amplitude
private const val FLUX_CYCLES  = 10f    // extra cycles driven by rate-of-change

// Envelope follower: fast attack reveals transients, slower release lets them
// trail. GAIN lifts typical (often quiet) speech RMS toward full scale — tune
// against the "level=" logcat values from the ViewModel if it clips or stays flat.
private const val ATTACK  = 0.55f
private const val RELEASE = 0.12f
private const val GAIN    = 1.5f

// How fast the wave rolls across the screen (temporal phase scroll), independent of
// the carrier density. 1f = original speed; raise to roll faster, lower to slow it.
private const val ROLL_SPEED = 2f

/**
 * An oscilloscope-style visualizer for the voice session.
 *
 * IMPORTANT: the SmallWebRTC RTVI client never delivers real audio levels (no
 * audio-level message type; the transport computes none), so `level` is ≈0 in
 * practice. The visible deformation is therefore *synthesized*: a speech-like
 * envelope gated on `source` (who's speaking) and kicked by `pulse` (each streamed
 * transcript token), so the trace comes alive in time with the conversation. It's
 * activity-driven, not the literal waveform. If a real level ever shows up (e.g. a
 * future server-sent level message) it's max'd in for free.
 *
 * The real signal then drives everything that reads as "nuance":
 *  - **height**  — a scrolling history buffer, so loud moments bulge and travel left;
 *  - **frequency** — the carrier densifies with level and its rate-of-change (`flux`);
 *  - **scroll speed / brightness** — both rise with the level.
 *
 * Motion is driven by a `rememberInfiniteTransition` clock read in the draw block, so
 * the trace redraws every frame regardless of how often events arrive.
 *
 * @param level   real combined amplitude if available (≈0 with this transport)
 * @param source  who is currently speaking — gates the synth + tints the trace
 * @param pulse   monotonically increasing transcript-token counter
 */
@Composable
fun AcousticScopeVisualizer(
    level: Float,
    source: AudioSource,
    pulse: Int,
    modifier: Modifier = Modifier,
) {
    // Latest real level (≈0 today), readable live by the frame loop.
    val live = remember { floatArrayOf(0f) }
    live[0] = level

    // Synth state: [0]=speaking gate, [1]=synth phase, [2]=pulse energy.
    val syn = remember { floatArrayOf(0f, 0f, 0f) }
    // Live gate target (1 while someone speaks). Written on EVERY recomposition into a
    // remembered holder, because the frame loop below captures its closure once — a
    // plain `val speaking` would freeze at its first value and never re-gate.
    val gateTarget = remember { floatArrayOf(0f) }
    gateTarget[0] = if (source != AudioSource.NONE) 1f else 0f
    // Each new transcript token injects a transient so the trace twitches with speech.
    LaunchedEffect(pulse) {
        if (pulse > 0) syn[2] = (syn[2] + 0.7f).coerceAtMost(1.3f)
    }

    // Free-running clock: monotonic seconds, read in the Canvas to force a redraw
    // every frame (the reliable way to animate a Canvas in Compose).
    val clock = rememberInfiniteTransition(label = "scopeClock")
    val tSec by clock.animateFloat(
        initialValue = 0f,
        targetValue = 3600f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 3_600_000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "tSec"
    )

    // Plain (non-snapshot) state advanced on the frame clock. The Canvas re-reads
    // these every frame because it also reads tSec, so they need not be observable.
    val env = remember { FloatArray(BUFFER) }              // shaped amplitude history
    val writeIdx = remember { intArrayOf(0) }              // next write position
    val dyn = remember { floatArrayOf(0f, 0f, 0f) }        // [0]=phase [1]=flux [2]=follower
    LaunchedEffect(Unit) {
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                val dt = if (last == 0L) 0f else ((now - last) / 1_000_000_000f).coerceIn(0f, 0.05f)
                last = now

                // --- Synthesize a speech-like amplitude (no real level exists) ---
                // Gate ramps up while someone speaks, down to silence otherwise.
                val gTarget = gateTarget[0]
                syn[0] += (gTarget - syn[0]) * (if (gTarget > 0.5f) 0.10f else 0.05f)
                val gate = syn[0]
                // Layered incommensurate AM → a wobble that reads like a voice envelope.
                syn[1] += dt * 9f
                val sp = syn[1]
                val texture = 0.45f +
                    0.30f * sin(sp * 2.1f) +
                    0.15f * sin(sp * 5.3f + 1.3f) +
                    0.10f * sin(sp * 11.7f + 0.7f)
                syn[2] *= 0.90f                                   // pulse energy decay
                val synthRaw = ((texture * 0.8f + syn[2]) * gate).coerceIn(0f, 1f)

                // Envelope follower: snap up on attack, ease down on release, so the
                // trace tracks speech transients (the "rapid changes") without jitter.
                // max() lets a real level (if ever delivered) override the synth.
                val raw = max(synthRaw, live[0])
                val f = dyn[2]
                val k = if (raw > f) ATTACK else RELEASE
                val followed = f + (raw - f) * k
                dyn[2] = followed
                val shaped = (followed * GAIN).coerceIn(0f, 1f)

                val i = writeIdx[0]
                val prev = env[(i - 1 + BUFFER) % BUFFER]
                dyn[1] = dyn[1] * 0.80f + abs(shaped - prev) * 0.20f   // rate-of-change
                env[i] = shaped
                writeIdx[0] = (i + 1) % BUFFER
                // Scroll faster when loud or changing → the wave "comes alive" on speech.
                // ROLL_SPEED scales the rolling frequency (how fast the wave travels
                // across the screen), independent of the carrier density.
                dyn[0] += dt * ROLL_SPEED * (4.5f + 9f * shaped + 26f * dyn[1])
            }
        }
    }

    val traceColor = when (source) {
        AudioSource.BOT  -> Phosphor
        AudioSource.USER -> Cyan
        AudioSource.NONE -> lerp(GridDim, Phosphor, 0.5f)
    }

    Canvas(modifier = modifier) {
        @Suppress("UNUSED_EXPRESSION") tSec   // touch the clock → redraw this frame

        val w = size.width
        val h = size.height
        val midY = h / 2f
        val amp = h * 0.40f

        drawGraticule(midY)

        val phase = dyn[0]
        val flux = dyn[1]
        val lvl = dyn[2]
        val cycles = BASE_CYCLES + lvl * LEVEL_CYCLES + flux * FLUX_CYCLES
        val head = writeIdx[0]

        val path = Path()
        var lastY = midY
        for (i in 0 until BUFFER) {
            val frac = i / (BUFFER - 1f)
            val e = env[(head + i) % BUFFER]          // oldest at left, newest at right
            val theta = frac * cycles * TWO_PI + phase
            // Two harmonics for texture; a faint idle wave keeps silence alive + moving.
            val carrier = sin(theta) * 0.72f + sin(theta * 2f + phase * 0.5f) * 0.28f
            val idle = sin(frac * 3f * TWO_PI - phase * 0.6f) * 0.05f
            val x = frac * w
            val y = midY - (e * carrier + idle) * amp
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            lastY = y
        }

        // Phosphor bloom: wide dim passes underneath, bright thin core on top. Both
        // brightness and core thickness swell with the level for a "louder = hotter" feel.
        val bright = (0.5f + 0.5f * lvl).coerceIn(0f, 1f)
        val core = size.minDimension * 0.006f * (1f + 0.7f * lvl)
        drawPath(path, traceColor.copy(alpha = 0.10f * bright + 0.04f),
            style = Stroke(width = core * 6f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawPath(path, traceColor.copy(alpha = 0.30f * bright + 0.08f),
            style = Stroke(width = core * 3f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawPath(path, traceColor.copy(alpha = 0.55f + 0.45f * bright),
            style = Stroke(width = core, cap = StrokeCap.Round, join = StrokeJoin.Round))

        // Leading "beam" dot at the newest sample (right edge) — the live cursor.
        drawCircle(
            color = traceColor.copy(alpha = 0.6f + 0.4f * bright),
            radius = core * 2.2f,
            center = Offset(w, lastY)
        )
    }
}

/** Faint scope grid: center axis + evenly spaced horizontal/vertical divisions. */
private fun DrawScope.drawGraticule(midY: Float) {
    val w = size.width
    val h = size.height
    val divs = 8
    for (i in 1 until divs) {
        val x = w * i / divs
        drawLine(GridDim.copy(alpha = 0.85f), Offset(x, 0f), Offset(x, h), strokeWidth = 1f)
    }
    for (i in 1 until divs) {
        val y = h * i / divs
        drawLine(GridDim.copy(alpha = 0.85f), Offset(0f, y), Offset(w, y), strokeWidth = 1f)
    }
    // Brighter center axis.
    drawLine(GridDim, Offset(0f, midY), Offset(w, midY), strokeWidth = 2f)
}

@Preview(widthDp = 360, heightDp = 480, backgroundColor = 0xFF000000, showBackground = true)
@Composable
private fun ScopePreview_Idle() {
    AcousticScopeVisualizer(level = 0f, source = AudioSource.NONE, pulse = 0, modifier = Modifier.fillMaxSize())
}

@Preview(widthDp = 360, heightDp = 480, backgroundColor = 0xFF000000, showBackground = true)
@Composable
private fun ScopePreview_User() {
    AcousticScopeVisualizer(level = 0f, source = AudioSource.USER, pulse = 1, modifier = Modifier.fillMaxSize())
}

@Preview(widthDp = 360, heightDp = 480, backgroundColor = 0xFF000000, showBackground = true)
@Composable
private fun ScopePreview_Bot() {
    AcousticScopeVisualizer(level = 0f, source = AudioSource.BOT, pulse = 2, modifier = Modifier.fillMaxSize())
}
