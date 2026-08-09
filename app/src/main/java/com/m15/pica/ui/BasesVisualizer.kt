package com.m15.pica.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.m15.pica.AudioSource
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlin.math.sin

// Dodger-blue night-game palette.
private val DodgerBlue  = Color(0xFF005A9C) // official Dodger blue — field lines
private val DodgerLight = Color(0xFF6EB4F7) // bright accent — bot runner glow
private val DodgerDim   = Color(0xFF0B2C4D) // dim outfield/graticule blue
private val DodgerRed   = Color(0xFFEF3E42) // uniform-number red — user runner
private val BallWhite   = Color(0xFFF7F7F2) // baseball white — bases, ball core

private const val TRAIL = 40            // runner ghost-trail depth (frames)

// Envelope follower (same feel as the scope's).
private const val B_ATTACK  = 0.55f
private const val B_RELEASE = 0.12f
private const val B_GAIN    = 1.5f

// Runner pace, in bases per second along the basepath.
private const val PACE_FLOOR = 0.25f    // minimum jog while someone is speaking
private const val PACE_LEVEL = 1.30f    // extra sprint at full envelope

/**
 * "Running the Bases" — an MLB-themed session visualizer in Dodger blue.
 *
 * Same signal model as [AcousticScopeVisualizer]: the SmallWebRTC transport never
 * delivers real audio levels, so activity is *synthesized* — a speech-like envelope
 * gated on `source` and kicked by `pulse` (each streamed transcript token). Here
 * that envelope drives a runner around the basepath:
 *
 *  - **speech** — the runner sprints, pace rising with the envelope; each base
 *    reached flashes as it's rounded;
 *  - **silence** — the runner eases onto the nearest base and holds there, bobbing;
 *  - **crossing home** — scores a run on the scoreboard pill (top-right).
 *
 * The runner is tinted per speaker: Dodger-blue/white for the bot, uniform-number
 * red for the user. If a real level ever shows up it's max'd in for free.
 *
 * @param level   real combined amplitude if available (≈0 with this transport)
 * @param source  who is currently speaking — gates the synth + tints the runner
 * @param pulse   monotonically increasing transcript-token counter
 */
@Composable
fun BasesVisualizer(
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

    // Runs scored this session view. Snapshot state (unlike the frame-rate arrays)
    // because the scoreboard Text below recomposes only when it changes.
    var runs by remember { mutableIntStateOf(0) }

    // Free-running clock read in the Canvas to force a redraw every frame.
    val clock = rememberInfiniteTransition(label = "basesClock")
    val tSec by clock.animateFloat(
        initialValue = 0f,
        targetValue = 3600f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 3_600_000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "tSec"
    )

    // Plain (non-snapshot) state advanced on the frame clock.
    // dyn: [0]=basepath position t in [0,4) (0=home 1=1st 2=2nd 3=3rd), [1]=envelope
    // follower, [2]=current pace (bases/sec, smoothed).
    val dyn = remember { floatArrayOf(0f, 0f, 0f) }
    val flash = remember { FloatArray(4) }          // per-base rounded-it glow
    val trail = remember { FloatArray(TRAIL) }      // t history ring → ghost trail
    val trailIdx = remember { intArrayOf(0) }

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
                val f = dyn[1]
                val k = if (raw > f) B_ATTACK else B_RELEASE
                val followed = f + (raw - f) * k
                dyn[1] = followed
                val shaped = (followed * B_GAIN).coerceIn(0f, 1f)

                // --- Advance the runner ---
                val prevT = dyn[0]
                val paceTarget = if (gate > 0.15f) PACE_FLOOR + PACE_LEVEL * shaped else 0f
                dyn[2] += (paceTarget - dyn[2]) * 0.12f

                if (dyn[2] > 0.02f) {
                    // Running: sprint along the path, flash each base as it's rounded.
                    dyn[0] += dt * dyn[2]
                    if (dyn[0].toInt() > prevT.toInt()) flash[dyn[0].toInt() % 4] = 1f
                    if (dyn[0] >= 4f) { dyn[0] -= 4f; runs++ }
                } else {
                    // Silence: ease onto the nearest base and hold there.
                    val nearest = round(dyn[0])
                    val d = nearest - dyn[0]
                    if (abs(d) < 0.004f && d != 0f) {
                        dyn[0] = nearest
                        flash[nearest.toInt() % 4] = max(flash[nearest.toInt() % 4], 0.8f)
                        if (dyn[0] >= 4f) { dyn[0] = 0f; runs++ }
                    } else {
                        dyn[0] += d * 0.10f
                    }
                }

                for (i in 0..3) flash[i] = (flash[i] - dt * 1.8f).coerceAtLeast(0f)
                trail[trailIdx[0]] = dyn[0]
                trailIdx[0] = (trailIdx[0] + 1) % TRAIL
            }
        }
    }

    val runnerColor = when (source) {
        AudioSource.BOT  -> DodgerLight
        AudioSource.USER -> DodgerRed
        AudioSource.NONE -> lerp(DodgerDim, DodgerLight, 0.5f)
    }

    Box(modifier = modifier) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            @Suppress("UNUSED_EXPRESSION") tSec   // touch the clock → redraw this frame

            val w = size.width
            val h = size.height
            val cx = w / 2f
            // Diamond sized so the outfield wall (≈2.5r from home, drawn in
            // drawField) still fits: its 45° foul-pole ends bound the width and
            // its center-field top bounds the height. Home sits low (cy + r) to
            // leave outfield room above.
            val r = min(w * 0.275f, h * 0.30f)
            val cy = h * 0.55f                       // diamond center; home sits at cy + r
            val home = Offset(cx, cy + r)
            // Corner order matches basepath t: 0=home, 1=first, 2=second, 3=third.
            val corners = arrayOf(
                home,
                Offset(cx + r, cy),
                Offset(cx, cy - r),
                Offset(cx - r, cy),
            )

            fun posFor(t: Float): Offset {
                val seg = (t.toInt() % 4 + 4) % 4
                val frac = t - t.toInt()
                val a = corners[seg]
                val b = corners[(seg + 1) % 4]
                return Offset(a.x + (b.x - a.x) * frac, a.y + (b.y - a.y) * frac)
            }

            drawField(home, corners, r, flash)

            // Ghost trail: oldest→newest, fading in and growing toward the runner.
            val head = trailIdx[0]
            val ball = r * 0.055f
            for (i in 0 until TRAIL) {
                val p = posFor(trail[(head + i) % TRAIL])
                val age = i / (TRAIL - 1f)          // 0 = oldest, 1 = newest
                drawCircle(
                    color = runnerColor.copy(alpha = 0.04f + 0.22f * age * age),
                    radius = ball * (0.35f + 0.55f * age),
                    center = p,
                )
            }

            // The runner: a baseball with a team-colored bloom. Bobs in place while
            // holding a base, swells with the envelope while sprinting.
            val lvl = dyn[1]
            val paceNorm = (dyn[2] / (PACE_FLOOR + PACE_LEVEL)).coerceIn(0f, 1f)
            val bob = sin(syn[1] * 2.5f) * r * 0.02f * (1f - paceNorm)
            val p = posFor(dyn[0]) + Offset(0f, bob)
            val rr = ball * (1f + 0.5f * lvl)
            val bright = (0.5f + 0.5f * lvl).coerceIn(0f, 1f)
            drawCircle(runnerColor.copy(alpha = 0.10f * bright + 0.05f), rr * 3.2f, p)
            drawCircle(runnerColor.copy(alpha = 0.30f * bright + 0.10f), rr * 1.9f, p)
            drawCircle(lerp(runnerColor, BallWhite, 0.6f).copy(alpha = 0.6f + 0.4f * bright), rr, p)
        }

        // Scoreboard pill — runs scored (laps completed).
        Row(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 10.dp, end = 14.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF06182B))
                .border(1.dp, DodgerBlue.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                .padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = "RUNS",
                color = DodgerLight,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.5.sp,
            )
            Text(
                text = "$runs",
                color = BallWhite,
                fontSize = 14.sp,
                fontWeight = FontWeight.ExtraBold,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

/** Static field furniture: outfield arcs, foul lines, basepath, mound, bases. */
private fun DrawScope.drawField(
    home: Offset,
    corners: Array<Offset>,
    r: Float,
    flash: FloatArray,
) {
    // Outfield arcs fanning up from home plate (the dim "graticule" of this viz),
    // spaced through the grass between the infield (2nd base is 2r out) and the wall.
    for (ar in floatArrayOf(r * 1.55f, r * 1.95f)) {
        drawArc(
            color = DodgerDim,
            startAngle = 225f,
            sweepAngle = 90f,
            useCenter = false,
            topLeft = Offset(home.x - ar, home.y - ar),
            size = Size(ar * 2f, ar * 2f),
            style = Stroke(width = 1.5f),
        )
    }

    // Outfield wall: a solid Dodger-blue band closing the field, with a brighter
    // cap line along its top (outer) edge so it reads as a wall, not another arc.
    // Deep of 2nd base (2r from home) so the outfielders have grass to roam.
    val wallR = r * 2.42f
    val wallW = r * 0.12f
    drawArc(
        color = DodgerBlue.copy(alpha = 0.40f),
        startAngle = 225f,
        sweepAngle = 90f,
        useCenter = false,
        topLeft = Offset(home.x - wallR, home.y - wallR),
        size = Size(wallR * 2f, wallR * 2f),
        style = Stroke(width = wallW),
    )
    val wallCapR = wallR + wallW / 2f
    drawArc(
        color = DodgerLight.copy(alpha = 0.75f),
        startAngle = 225f,
        sweepAngle = 90f,
        useCenter = false,
        topLeft = Offset(home.x - wallCapR, home.y - wallCapR),
        size = Size(wallCapR * 2f, wallCapR * 2f),
        style = Stroke(width = 2f),
    )

    // Foul lines from home through 1st and 3rd out to the wall.
    val foul = wallCapR * 0.7071f
    drawLine(DodgerDim, home, Offset(home.x + foul, home.y - foul), strokeWidth = 1.5f)
    drawLine(DodgerDim, home, Offset(home.x - foul, home.y - foul), strokeWidth = 1.5f)

    // Basepath diamond.
    val path = Path().apply {
        moveTo(corners[0].x, corners[0].y)
        for (i in 1..3) lineTo(corners[i].x, corners[i].y)
        close()
    }
    drawPath(
        path,
        DodgerBlue.copy(alpha = 0.85f),
        style = Stroke(width = 2.5f, cap = StrokeCap.Round, join = StrokeJoin.Round),
    )

    // Pitcher's mound.
    val center = Offset((corners[1].x + corners[3].x) / 2f, (corners[0].y + corners[2].y) / 2f)
    drawCircle(DodgerDim, radius = r * 0.10f, center = center, style = Stroke(width = 1.5f))
    drawCircle(DodgerBlue.copy(alpha = 0.5f), radius = r * 0.02f, center = center)

    // Bases: white diamonds; a rounded base blooms and fades.
    val s = r * 0.075f
    for (i in 0..3) {
        val c = corners[i]
        val f = flash[i]
        if (f > 0f) drawBase(c, s * (1.6f + 1.2f * f), BallWhite.copy(alpha = 0.30f * f))
        drawBase(c, s, if (i == 0) BallWhite else BallWhite.copy(alpha = 0.85f))
    }
}

/** A base as a small axis-aligned diamond (rotated square) centered on [c]. */
private fun DrawScope.drawBase(c: Offset, s: Float, color: Color) {
    val p = Path().apply {
        moveTo(c.x, c.y - s)
        lineTo(c.x + s, c.y)
        lineTo(c.x, c.y + s)
        lineTo(c.x - s, c.y)
        close()
    }
    drawPath(p, color)
}

@Preview(widthDp = 360, heightDp = 480, backgroundColor = 0xFF000000, showBackground = true)
@Composable
private fun BasesPreview_Idle() {
    BasesVisualizer(level = 0f, source = AudioSource.NONE, pulse = 0, modifier = Modifier.fillMaxSize())
}

@Preview(widthDp = 360, heightDp = 480, backgroundColor = 0xFF000000, showBackground = true)
@Composable
private fun BasesPreview_User() {
    BasesVisualizer(level = 0f, source = AudioSource.USER, pulse = 1, modifier = Modifier.fillMaxSize())
}

@Preview(widthDp = 360, heightDp = 480, backgroundColor = 0xFF000000, showBackground = true)
@Composable
private fun BasesPreview_Bot() {
    BasesVisualizer(level = 0f, source = AudioSource.BOT, pulse = 2, modifier = Modifier.fillMaxSize())
}