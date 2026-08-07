package com.m15.pica.prefs

import android.content.Context
import android.content.SharedPreferences
import com.m15.pica.InitialAgents
import com.m15.pica.ServerEndpoint
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class PicaLocalPrefs(context: Context) {

    private val sp: SharedPreferences =
        context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun getSpeakerOn(): Boolean = sp.getBoolean(KEY_SPEAKER_ON, DEFAULT_SPEAKER_ON)
    fun setSpeakerOn(on: Boolean) = sp.edit().putBoolean(KEY_SPEAKER_ON, on).apply()

    /**
     * The agent list. Seeded once with [InitialAgents] — tracked by [KEY_SEEDED] so
     * deleting every agent does NOT re-seed — then fully user-owned. Corrupt or
     * missing JSON degrades to an empty list instead of crashing, and obviously
     * invalid rows (blank title/host, out-of-range port) are dropped.
     */
    fun getAgents(): List<ServerEndpoint> {
        if (!sp.getBoolean(KEY_SEEDED, false)) {
            setAgents(InitialAgents.all())
            sp.edit().putBoolean(KEY_SEEDED, true).apply()
        }
        val raw = sp.getString(KEY_AGENTS, null) ?: return emptyList()
        return runCatching { json.decodeFromString<List<ServerEndpoint>>(raw) }
            .getOrElse { emptyList() }
            .filter { it.title.isNotBlank() && it.host.isNotBlank() && it.port in 1..65535 }
    }

    fun setAgents(list: List<ServerEndpoint>) =
        sp.edit().putString(KEY_AGENTS, json.encodeToString(list)).apply()

    /**
     * Selected agent id. On the first read after upgrading from the old enum-based
     * build, migrates the legacy "pica_mode" value (a `PicaMode.name`) to the
     * matching seed id, then forgets the old key.
     */
    fun getSelectedId(): String {
        sp.getString(KEY_SELECTED_ID, null)?.let { return it }
        val legacy = sp.getString(KEY_MODE, null)
        val migrated = InitialAgents.LEGACY_MODE_TO_ID[legacy] ?: InitialAgents.DEFAULT_ID
        sp.edit().putString(KEY_SELECTED_ID, migrated).remove(KEY_MODE).apply()
        return migrated
    }

    fun setSelectedId(id: String) = sp.edit().putString(KEY_SELECTED_ID, id).apply()

    companion object {
        private const val FILE_NAME = "pica_local_prefs"
        private const val KEY_SPEAKER_ON = "speaker_on"
        private const val DEFAULT_SPEAKER_ON = true
        private const val KEY_AGENTS = "agents_json"
        private const val KEY_SEEDED = "agents_seeded"
        private const val KEY_SELECTED_ID = "selected_agent_id"
        private const val KEY_MODE = "pica_mode" // legacy; read once for migration
    }
}
