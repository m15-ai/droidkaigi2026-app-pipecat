package com.m15.pica

import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.UUID

/**
 * One selectable Pipecat backend ("agent"). All agents speak the same WebRTC+RTVI
 * protocol — the only thing that differs is the offer URL the transport POSTs to,
 * so a new agent is purely data: host + port (+ path/scheme) and a label.
 *
 * Persisted as JSON by [com.m15.pica.prefs.PicaLocalPrefs]; [InitialAgents] seeds
 * the first three. [url] and [badge] are derived (not stored) so there is a single
 * source of truth.
 *
 * @param accentArgb 0xAARRGGBB badge tint as a [Long] — the historic literals
 *        (e.g. 0xFFE0A066) overflow a signed Int, so Long is used to round-trip
 *        cleanly through JSON and [androidx.compose.ui.graphics.Color].
 * @param visualizer session visualizer as a [VisualizerStyle.key] string — stored
 *        as a string (with a default) so old persisted JSON and unknown future
 *        keys both decode cleanly. Read it via [visualizerStyle].
 */
@Serializable
data class ServerEndpoint(
    val id: String,
    val title: String,
    val host: String,
    val port: Int,
    val path: String = DEFAULT_PATH,
    val scheme: String = "http",
    val accentArgb: Long = DEFAULT_ACCENT_ARGB,
    val visualizer: String = VisualizerStyle.DEFAULT.key,
) {
    /** Full offer endpoint the SmallWebRTC transport POSTs the SDP to, verbatim. */
    val url: String get() = "$scheme://$host:$port$path"

    /** The persisted [visualizer] key resolved to a style (unknown → default). */
    val visualizerStyle: VisualizerStyle get() = VisualizerStyle.fromKey(visualizer)

    /** All-caps pill shown in the session header. */
    val badge: String get() = title.uppercase()

    companion object {
        const val DEFAULT_PATH = "/api/offer"
        const val DEFAULT_ACCENT_ARGB: Long = 0xFF888888

        /** Ids for user-added agents — random so they can never collide with seeds. */
        fun newId(): String = "user_" + UUID.randomUUID()
    }
}

/**
 * The three agents seeded into the database on first launch (from `BuildConfig`,
 * which is fed by `local.properties`). After seeding they are ordinary editable /
 * deletable rows — they are NOT recomputed from BuildConfig on later launches.
 */
object InitialAgents {
    const val ID_HERMES = "hermes"
    const val ID_DIRECT = "direct"
    const val ID_OPENCLAW = "openclaw"

    /** Fallback selection when nothing valid is persisted. */
    const val DEFAULT_ID = ID_HERMES

    /** Legacy `PicaMode.name` (old "pica_mode" pref) → new stable agent id. */
    val LEGACY_MODE_TO_ID = mapOf(
        "HERMES" to ID_HERMES,
        "DIRECT" to ID_DIRECT,
        "OPENCLAW" to ID_OPENCLAW,
    )

    fun all(): List<ServerEndpoint> = listOf(
        seed(ID_HERMES, "Hermes", BuildConfig.HERMES_SERVER_URL, 0xFFE0A066),
        seed(ID_DIRECT, "Direct", BuildConfig.PICA_SERVER_URL, 0xFF66B2E0),
        seed(ID_OPENCLAW, "OpenClaw", BuildConfig.OPENCLAW_SERVER_URL, 0xFFB28CE0),
    )

    /** Parse a full BuildConfig offer URL into endpoint fields. */
    private fun seed(id: String, title: String, url: String, accent: Long): ServerEndpoint {
        val parsed = url.toHttpUrlOrNull()
        return ServerEndpoint(
            id = id,
            title = title,
            host = parsed?.host ?: url,
            port = parsed?.port ?: 80,
            path = parsed?.encodedPath ?: ServerEndpoint.DEFAULT_PATH,
            scheme = parsed?.scheme ?: "http",
            accentArgb = accent,
        )
    }
}
