package com.m15.pica

/**
 * The session visualizers an agent can choose between. Persisted on
 * [ServerEndpoint.visualizer] as [key] (a plain string, not the enum) so that
 * a build encountering an unknown key — e.g. after a downgrade, or a future
 * style — degrades to [DEFAULT] instead of failing to deserialize.
 *
 * To add a new visualizer: add an entry here, then branch on it at the single
 * call site in [com.m15.pica.ui.VoiceAgentScreen]. The setup editor's picker
 * enumerates [entries] and needs no change.
 */
enum class VisualizerStyle(val key: String, val label: String) {
    SCOPE("scope", "Oscilloscope"),
    BASES("bases", "Running the Bases"),
    ORB("orb", "Orange Orb");

    companion object {
        val DEFAULT = SCOPE

        fun fromKey(key: String?): VisualizerStyle =
            entries.firstOrNull { it.key == key } ?: DEFAULT
    }
}