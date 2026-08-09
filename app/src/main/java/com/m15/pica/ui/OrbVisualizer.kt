package com.m15.pica.ui

import androidx.compose.animation.core.FastOutSlowInEasing
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
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.tooling.preview.Preview
import com.m15.pica.AudioSource
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

// Orange palette — warm amber/bronze family. Overlapping translucent orbs in
// these shades blend additively into a soft, glowing, lava-lamp-like core.
// (Ported from the Cliff app's AudioBlobVisualizer.)
private val Amber = Color(0xFFFFBF00)
private val Apricot = Color(0xFFFBCEB1)
private val Bisque = Color(0xFFF2D2BD)
private val BrightOrange = Color(0xFFFFAC1C)
private val Bronze = Color(0xFFCD7F32)
private val Buff = Color(0xFFDAA06D)
private val BurntOrange = Color(0xFFCC5500)

// Center-bloom tint per speaker: warm for the bot, cool for the user, so the
// orange orb still tells you who's talking without leaving its palette.
private val UserBloom = Color(0xFF9AD6FF)

/**
 * One soft orb in the stack.
 *
 * @param color        shade from the orange palette
 * @param radiusFactor size relative to the base radius
 * @param orbitFactor  how far it drifts from the shared center, relative to base radius
 * @param orbitSpeed   angular speed multiplier for its slow drift
 * @param orbitPhase   starting angle offset so orbs don't bunch up
 * @param wobbleSeed   phase offset for the gentle edge wobble
 * @param alpha        peak opacity of its core
 */
private data class Orb(
    val color: Color,
    val radiusFactor: Float,
    val orbitFactor: Float,
    val orbitSpeed: Float,
    val orbitPhase: Float,
    val wobbleSeed: Float,
    val alpha: Float,
)

// Deeper shades form the base mass; brighter ones float on top as highlights.
private val ORBS = listOf(
    Orb(BurntOrange, radiusFactor = 1.18f, orbitFactor = 0.10f, orbitSpeed = 0.6f, orbitPhase = 0.0f, wobbleSeed = 0.0f, alpha = 0.42f),
    Orb(Bronze, radiusFactor = 1.02f, orbitFactor = 0.20f, orbitSpeed = -0.9f, orbitPhase = 1.1f, wobbleSeed = 1.7f, alpha = 0.40f),
    Orb(Buff, radiusFactor = 0.88f, orbitFactor = 0.30f, orbitSpeed = 1.2f, orbitPhase = 2.4f, wobbleSeed = 3.0f, alpha = 0.36f),
    Orb(BrightOrange, radiusFactor = 0.80f, orbitFactor = 0.26f, orbitSpeed = -1.5f, orbitPhase = 3.7f, wobbleSeed = 4.2f, alpha = 0.40f),
    Orb(Amber, radiusFactor = 0.66f, orbitFactor = 0.34f, orbitSpeed = 1.8f, orbitPhase = 4.9f, wobbleSeed = 5.5f, alpha = 0.38f),
    Orb(Apricot, radiusFactor = 0.52f, orbitFactor = 0.40f, orbitSpeed = -2.2f, orbitPhase = 0.6f, wobbleSeed = 6.1f, alpha = 0.34f),
    Orb(Bisque, radiusFactor = 0.44f, orbitFactor = 0.46f, orbitSpeed = 2.6f, orbitPhase = 5.8f, wobbleSeed = 2.3f, alpha = 0.30f),
)

// Envelope follower (same feel as the scope's).
private const val O_ATTACK  = 0.55f
private const val O_RELEASE = 0.12f
private const val O_GAIN    = 1.5f

/**
 * The Cliff app's glowing orange orb, adapted to Pica's signal model.
 *
 * Cliff feeds this a real audio level; Pica's SmallWebRTC transport never
 * delivers one, so — exactly like [AcousticScopeVisualizer] and
 * [BasesVisualizer] — the level is *synthesized*: a speech-like envelope gated
 * on `source` (who's speaking) and kicked by `pulse` (each streamed transcript
 * token). That envelope drives everything the orb does with loudness:
 *
 *  - the stack **breathes** (swells) and the orbs **spread apart** when loud;
 *  - orbit **spin rate** rises smoothly from ~0.05 rev/s idle to ~0.5 rev/s;
 *  - edge **wobble** deepens with the envelope.
 *
 * The central bloom is tinted warm amber while the bot speaks and cool blue
 * while the user speaks. If a real level ever shows up it's max'd in for free.
 *
 * @param level   real combined amplitude if available (≈0 with this transport)
 * @param source  who is currently speaking — gates the synth + tints the bloom
 * @param pulse   monotonically increasing transcript-token counter
 */
@Composable
fun OrbVisualizer(
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
    // Live gate target — written every recomposition into a remembered holder so the
    // frame loop (which captures its closure once) always sees the current value.
    val gateTarget = remember { floatArrayOf(0f) }
    gateTarget[0] = if (source != AudioSource.NONE) 1f else 0f
    LaunchedEffect(pulse) {
        if (pulse > 0) syn[2] = (syn[2] + 0.7f).coerceAtMost(1.3f)
    }

    // Frame-rate state: [0]=envelope follower, [1]=integrated orbit angle.
    val dyn = remember { floatArrayOf(0f, 0f) }
    LaunchedEffect(Unit) {
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                val dt = if (last == 0L) 0f else ((now - last) / 1_000_000_000f).coerceIn(0f, 0.05f)
                last = now

                // --- Synthesize a speech-like amplitude (no real level exists) ---
                val gTarget = gateTarget[0]
                syn[0] += (gTarget - syn[0]) * (if (gTarget > 0.5f) 0.10f else 0.05f)
                val gate = syn[0]
                syn[1] += dt * 9f
                val sp = syn[1]
                val texture = 0.45f +
                    0.30f * sin(sp * 2.1f) +
                    0.15f * sin(sp * 5.3f + 1.3f) +
                    0.10f * sin(sp * 11.7f + 0.7f)
                syn[2] *= 0.90f                                   // pulse energy decay
                val synthRaw = ((texture * 0.8f + syn[2]) * gate).coerceIn(0f, 1f)

                // Envelope follower: snap up on attack, ease down on release.
                val raw = max(synthRaw, live[0])
                val f = dyn[0]
                val k = if (raw > f) O_ATTACK else O_RELEASE
                val followed = f + (raw - f) * k
                dyn[0] = followed

                // Orbit angle integrated at an amplitude-dependent rate, so the
                // orbs revolve faster as the voice gets louder while speed changes
                // stay smooth (no position jumps).
                val shaped = (followed * O_GAIN).coerceIn(0f, 1f)
                val revPerSec = 0.05f + 0.6f * shaped
                dyn[1] += dt * revPerSec * 2f * PI.toFloat()
            }
        }
    }

    val t = rememberInfiniteTransition(label = "orbTime")
    val phase by t.animateFloat(
        initialValue = 0f,
        targetValue = 2f * PI.toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 9000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "phase"
    )
    val shimmer by t.animateFloat(
        initialValue = 0.6f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1100, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "shimmer"
    )

    val bloom = when (source) {
        AudioSource.USER -> UserBloom
        else -> Amber
    }

    Canvas(modifier = modifier) {
        // phase/shimmer are animated every frame, so reading them here keeps the
        // Canvas redrawing and the non-snapshot dyn[] state visible.
        val cx = size.width / 2f
        val cy = size.height / 2f
        val center = Offset(cx, cy)

        val minDim = size.minDimension
        val baseR = minDim * 0.38f
        val smooth = (dyn[0] * O_GAIN).coerceIn(0f, 1f)
        val spin = dyn[1]
        val punch = smooth * smooth
        // The whole stack breathes with the audio; orbs also spread apart a
        // little when loud so the colored fringes separate and read distinctly.
        val r = baseR * (1f + 0.85f * punch)
        val spread = 1f + 0.55f * smooth

        // Warm ambient haze behind everything.
        drawHaze(center, minDim, shimmer)

        // Overlapping orbs, additively blended so where they cross they brighten
        // and the orange shades mix toward amber/gold.
        for (orb in ORBS) {
            drawOrb(
                base = center,
                baseRadius = r,
                spin = spin,
                phase = phase,
                level = smooth,
                spread = spread,
                shimmer = shimmer,
                orb = orb
            )
        }

        // Faint accent bloom at the very center to tie the stack together.
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    bloom.copy(alpha = 0.06f + 0.10f * punch),
                    Color.Transparent
                ),
                center = center,
                radius = r * 0.9f
            ),
            radius = r * 0.9f,
            center = center,
            blendMode = BlendMode.Plus
        )
    }
}

private fun DrawScope.drawHaze(center: Offset, minDim: Float, shimmer: Float) {
    val fogR = minDim * 0.66f
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                BurntOrange.copy(alpha = 0.07f * shimmer),
                Bronze.copy(alpha = 0.04f * shimmer),
                Color.Transparent
            ),
            center = center,
            radius = fogR
        ),
        radius = fogR,
        center = center
    )
}

private fun DrawScope.drawOrb(
    base: Offset,
    baseRadius: Float,
    spin: Float,
    phase: Float,
    level: Float,
    spread: Float,
    shimmer: Float,
    orb: Orb,
) {
    val angle = spin * orb.orbitSpeed + orb.orbitPhase
    val drift = baseRadius * orb.orbitFactor * spread
    val c = Offset(
        base.x + drift * cos(angle),
        base.y + drift * sin(angle)
    )

    val radius = baseRadius * orb.radiusFactor
    // Gentle, rounded wobble — soft organic edge, no spikes.
    val path = orbPath(c, radius, phase, orb.wobbleSeed, level)

    val coreAlpha = orb.alpha * (0.6f + 0.4f * shimmer)
    drawPath(
        path = path,
        brush = Brush.radialGradient(
            colors = listOf(
                orb.color.copy(alpha = coreAlpha),
                orb.color.copy(alpha = coreAlpha * 0.55f),
                Color.Transparent
            ),
            center = c,
            radius = radius * 1.35f
        ),
        blendMode = BlendMode.Plus
    )
}

private fun orbPath(
    center: Offset,
    radius: Float,
    phase: Float,
    seed: Float,
    level: Float,
): Path {
    val path = Path()
    val cx = center.x
    val cy = center.y
    val points = 96

    // Only low harmonics → smooth, slow lobes. Wobble grows modestly with level.
    val k1 = 2.0f
    val k2 = 3.0f
    val strength = 0.05f + 0.07f * level

    for (i in 0..points) {
        val a = (i / points.toFloat()) * (2f * PI.toFloat())
        val wobble =
            sin(a * k1 + phase + seed) * 0.65f +
                sin(a * k2 - phase * 0.7f + seed) * 0.35f
        val rr = radius * (1f + strength * wobble)
        val x = cx + rr * cos(a)
        val y = cy + rr * sin(a)
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.close()
    return path
}

@Preview(widthDp = 360, heightDp = 480, backgroundColor = 0xFF000000, showBackground = true)
@Composable
private fun OrbPreview_Idle() {
    OrbVisualizer(level = 0f, source = AudioSource.NONE, pulse = 0, modifier = Modifier.fillMaxSize())
}

@Preview(widthDp = 360, heightDp = 480, backgroundColor = 0xFF000000, showBackground = true)
@Composable
private fun OrbPreview_User() {
    OrbVisualizer(level = 0f, source = AudioSource.USER, pulse = 1, modifier = Modifier.fillMaxSize())
}

@Preview(widthDp = 360, heightDp = 480, backgroundColor = 0xFF000000, showBackground = true)
@Composable
private fun OrbPreview_Bot() {
    OrbVisualizer(level = 0f, source = AudioSource.BOT, pulse = 2, modifier = Modifier.fillMaxSize())
}