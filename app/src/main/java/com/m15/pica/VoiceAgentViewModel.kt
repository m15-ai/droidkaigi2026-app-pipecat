package com.m15.pica

import android.app.Application
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.m15.pica.data.db.MessageItem
import com.m15.pica.di.ServiceLocator
import com.m15.pica.net.PicaVoiceClient
import com.m15.pica.prefs.PicaLocalPrefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.max

/** Who is currently producing audio — drives the scope visualizer's trace color. */
enum class AudioSource { NONE, USER, BOT }

data class AgentUiState(
    val sessionId: String? = null,
    val sessionActive: Boolean = false,
    val isThinking: Boolean = false,
    val error: String? = null,
    val messages: List<Pair<String, String>> = emptyList(),
    val speakerOn: Boolean = true,
    /** Silence → first bot audio for the last turn, in ms. Null until measured. */
    val lastTurnMs: Long? = null,
    /** Id of the agent this session talks to; resolved against the agent list. */
    val selectedId: String = InitialAgents.DEFAULT_ID,
    /** Who is currently speaking; tints the visualizer trace. */
    val activeSource: AudioSource = AudioSource.NONE,
    /**
     * Increments on every streamed transcript token. The SmallWebRTC client never
     * emits audio levels, so the scope can't see real amplitude — instead it pulses
     * on this to stay loosely correlated with the live speech cadence.
     */
    val speechPulse: Int = 0,
    /** Whether the live session has been explicitly saved (drives the Save FAB). */
    val currentSessionSaved: Boolean = false,
    /** When non-null, the history viewer is open on this saved session's id. */
    val viewingSessionId: String? = null,
)

/**
 * Owns the Pica voice session across config changes and bridges the Pipecat
 * transport to the orb/chat UI (AgentUiState, audioLevel).
 *
 * The actual WebRTC/RTVI work lives in [PicaVoiceClient]; this class is
 * transport-agnostic. It implements [PicaVoiceClient.Listener] and maps each
 * event onto UI state, transcript persistence, the orb amplitude, and the
 * latency HUD (the silence→first-audio number that powers the A/B vs Lika).
 *
 * Contrast with Lika's VoiceAgentViewModel, which held a LiveKit Room and mapped
 * AgentState→amplitude. Here there is no SFU, no token, no participant metadata.
 */
class VoiceAgentViewModel(application: Application) : AndroidViewModel(application),
    PicaVoiceClient.Listener {

    companion object {
        private const val TAG = "VoiceAgentViewModel"
    }

    private val prefs = PicaLocalPrefs(application)
    private val am: AudioManager = ServiceLocator.audioManager

    /**
     * The Pipecat client for the current session. Created per-connect with the
     * URL of the selected agent and released on disconnect — only one backend is
     * ever live at once (see [connect]/[disconnect]). Null between sessions.
     */
    private var client: PicaVoiceClient? = null

    // The editable agent list (seeded on first read), exposed for the setup screen.
    private val _agents = MutableStateFlow(prefs.getAgents())
    val agents: StateFlow<List<ServerEndpoint>> = _agents

    private val _ui = MutableStateFlow(
        AgentUiState(speakerOn = prefs.getSpeakerOn(), selectedId = initialSelectedId())
    )
    val ui: StateFlow<AgentUiState> = _ui

    /** Persisted selection, validated against the current list; falls back if stale. */
    private fun initialSelectedId(): String {
        val stored = prefs.getSelectedId()
        return if (_agents.value.any { it.id == stored }) stored
        else _agents.value.firstOrNull()?.id ?: InitialAgents.DEFAULT_ID
    }

    // Combined live amplitude (0..1) feeding the scope visualizer: the max of the
    // bot's output level and the user's mic level, whichever is currently louder.
    private val _audioLevel = MutableStateFlow(0f)
    val audioLevel: StateFlow<Float> = _audioLevel
    private var userLvl = 0f
    private var botLvl = 0f

    private fun pushLevel() {
        _audioLevel.value = max(userLvl, botLvl).coerceIn(0f, 1f)
    }

    private val _showVisualizer = MutableStateFlow(true)
    val showVisualizer: StateFlow<Boolean> = _showVisualizer

    private var audioDeviceCallback: AudioDeviceCallback? = null

    /** Transcript state keyed on a synthesized id; same id grows as text streams in. */
    private val messageById = LinkedHashMap<String, Pair<String, String>>()
    private val messageCreatedAt = mutableMapOf<String, Long>()
    /** Last appended chunk per accumulating (bot) id — guards identical re-delivery. */
    private val lastChunkById = mutableMapOf<String, String>()
    private var userTurnSeq = 0
    private var botTurnSeq = 0

    /** Set when the user stops speaking; cleared once we measure the bot's first audio. */
    private var userStoppedAtMs: Long = 0L

    /**
     * True when the teardown was initiated locally (user ended the session, a
     * failed connect, or onCleared). Lets [onDisconnected] tell an intentional
     * stop apart from the server/network dropping us mid-session — only the
     * latter should raise a "connection lost" message.
     */
    private var expectedDisconnect = false

    init {
        // Sweep any unsaved sessions left by a crash / OS kill that bypassed the
        // on-exit cleanup, so the history drawer's DB only ever holds saved chats.
        viewModelScope.launch { ServiceLocator.repo.purgeUnsaved() }
    }

    // ---- Session lifecycle ---------------------------------------------------

    fun startSession() {
        if (ui.value.sessionActive) return
        expectedDisconnect = false
        _showVisualizer.value = true
        messageById.clear()
        messageCreatedAt.clear()
        lastChunkById.clear()
        userTurnSeq = 0
        botTurnSeq = 0

        audioDeviceCallback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) = applyRouting()
            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) = applyRouting()
        }
        am.registerAudioDeviceCallback(audioDeviceCallback, null)
        applyRouting()

        // Snapshot the agent now (it can be edited/deleted later) for saved history.
        val agent = selectedAgent()
        viewModelScope.launch {
            val sid = ServiceLocator.repo.newSession(
                title = "Voice Chat",
                agentId = agent?.id ?: "",
                agentTitle = agent?.title ?: "",
                agentAccent = agent?.accentArgb ?: ServerEndpoint.DEFAULT_ACCENT_ARGB,
            )
            _ui.update {
                it.copy(
                    sessionId = sid,
                    sessionActive = true,
                    error = null,
                    messages = emptyList(),
                    currentSessionSaved = false,
                )
            }
            Log.i(TAG, "Session $sid started")
        }
    }

    fun stopSession() {
        if (!ui.value.sessionActive) return
        // Locally driven (user tapped End, or a failed connect tore us down):
        // mark the upcoming onDisconnected as expected so it stays silent.
        expectedDisconnect = true
        // Capture before we flip state: an unsaved session is discarded on exit so it
        // never reaches the history drawer (best-effort; startup purge is the guarantee).
        val sid = ui.value.sessionId
        val wasSaved = ui.value.currentSessionSaved
        _ui.update { it.copy(sessionActive = false, isThinking = false, activeSource = AudioSource.NONE) }
        if (!wasSaved && sid != null) {
            viewModelScope.launch { ServiceLocator.repo.deleteSession(sid) }
        }
        userLvl = 0f
        botLvl = 0f
        _audioLevel.value = 0f
        am.mode = AudioManager.MODE_NORMAL
        audioDeviceCallback?.let { am.unregisterAudioDeviceCallback(it) }
        audioDeviceCallback = null
        Log.i(TAG, "Session stopped")
    }

    // ---- Saved conversations (history) --------------------------------------

    /** Drawer feed of explicitly saved conversations. */
    val savedSessions = ServiceLocator.repo.savedSessions

    /** Transcript for the read-only viewer. */
    fun messages(sid: String) = ServiceLocator.repo.messages(sid)

    /** Save the current session: auto-title from the first user turn, then persist. */
    fun saveCurrentSession() {
        val sid = ui.value.sessionId ?: return
        val msgs = ui.value.messages
        if (msgs.isEmpty()) return
        if (ui.value.currentSessionSaved) return
        val title = autoTitle(msgs)
        // Flip the flag synchronously so a subsequent End doesn't purge this session.
        _ui.update { it.copy(currentSessionSaved = true) }
        viewModelScope.launch { ServiceLocator.repo.saveSession(sid, title) }
        Log.i(TAG, "Session $sid saved as \"$title\"")
    }

    fun renameSession(id: String, title: String) {
        val clean = title.trim()
        if (clean.isEmpty()) return
        viewModelScope.launch { ServiceLocator.repo.renameSession(id, clean) }
    }

    fun deleteSession(id: String) {
        if (ui.value.viewingSessionId == id) closeViewer()
        viewModelScope.launch { ServiceLocator.repo.deleteSession(id) }
    }

    fun openViewer(id: String) = _ui.update { it.copy(viewingSessionId = id) }
    fun closeViewer() = _ui.update { it.copy(viewingSessionId = null) }

    /** First user turn (truncated) → first message → date. */
    private fun autoTitle(msgs: List<Pair<String, String>>): String {
        val pick = msgs.firstOrNull { it.first == "user" }?.second
            ?: msgs.firstOrNull()?.second
        val text = pick?.trim()?.replace('\n', ' ')
        return if (!text.isNullOrEmpty()) {
            if (text.length <= 40) text else text.take(39).trimEnd() + "…"
        } else {
            "Conversation " + android.text.format.DateFormat.format("MMM d, h:mm a", System.currentTimeMillis())
        }
    }

    /** Plain-text/markdown transcript for the Android share sheet. */
    fun buildShareText(title: String, agentTitle: String, msgs: List<MessageItem>): String {
        val sb = StringBuilder()
        sb.append("# ").append(title).append('\n')
        if (agentTitle.isNotBlank()) sb.append("_Agent: ").append(agentTitle).append("_\n")
        sb.append('\n')
        for (m in msgs) {
            val who = if (m.role == "user") "You" else agentTitle.ifBlank { "Assistant" }
            sb.append("**").append(who).append(":** ").append(m.text.trim()).append("\n\n")
        }
        return sb.toString().trimEnd()
    }

    /** Pick which agent the next session connects to. Persisted. Ignored mid-session —
     *  switch by ending the session first, as backends are torn down/brought up one
     *  at a time. */
    fun selectAgent(id: String) {
        if (ui.value.sessionActive) return
        if (ui.value.selectedId == id) return
        if (_agents.value.none { it.id == id }) return
        prefs.setSelectedId(id)
        _ui.update { it.copy(selectedId = id) }
        Log.i(TAG, "Agent selected: $id")
    }

    /** The currently selected agent, or null if the list is empty / the selection is
     *  stale (e.g. the agent was just deleted). */
    fun selectedAgent(): ServerEndpoint? =
        _agents.value.firstOrNull { it.id == ui.value.selectedId }

    /** Add a new user agent and select it if nothing valid is currently selected. */
    fun addAgent(
        title: String,
        host: String,
        port: Int,
        path: String = ServerEndpoint.DEFAULT_PATH,
        visualizer: VisualizerStyle = VisualizerStyle.DEFAULT,
    ) {
        if (ui.value.sessionActive) return
        val agent = ServerEndpoint(
            id = ServerEndpoint.newId(),
            title = title.trim(),
            host = host.trim(),
            port = port,
            path = path.trim().ifBlank { ServerEndpoint.DEFAULT_PATH },
            visualizer = visualizer.key,
        )
        prefs.setAgents(_agents.value + agent)
        _agents.value = prefs.getAgents()
        if (selectedAgent() == null) selectFirstAvailable()
        Log.i(TAG, "Agent added: ${agent.id}")
    }

    /** Edit an existing agent in place (preserves its id and accent color). */
    fun updateAgent(
        id: String,
        title: String,
        host: String,
        port: Int,
        path: String = ServerEndpoint.DEFAULT_PATH,
        visualizer: VisualizerStyle = VisualizerStyle.DEFAULT,
    ) {
        if (ui.value.sessionActive) return
        val next = _agents.value.map {
            if (it.id == id) it.copy(
                title = title.trim(),
                host = host.trim(),
                port = port,
                path = path.trim().ifBlank { ServerEndpoint.DEFAULT_PATH },
                visualizer = visualizer.key,
            ) else it
        }
        prefs.setAgents(next)
        _agents.value = prefs.getAgents()
        Log.i(TAG, "Agent updated: $id")
    }

    /** Delete an agent; if it was selected, fall back to the first remaining one. */
    fun deleteAgent(id: String) {
        if (ui.value.sessionActive) return
        prefs.setAgents(_agents.value.filterNot { it.id == id })
        _agents.value = prefs.getAgents()
        if (ui.value.selectedId == id) selectFirstAvailable()
        Log.i(TAG, "Agent deleted: $id")
    }

    private fun selectFirstAvailable() {
        val id = _agents.value.firstOrNull()?.id ?: ""
        prefs.setSelectedId(id)
        _ui.update { it.copy(selectedId = id) }
    }

    /** Called by [com.m15.pica.ui.PicaSessionHost] once the session UI is composed. */
    fun connect() {
        val ep = selectedAgent()
        if (ep == null) {
            reportSessionError("No agent selected.")
            stopSession()
            return
        }
        // Fresh client for this session, bound to the selected agent's URL.
        val c = PicaVoiceClient(
            context = getApplication(),
            serverUrl = ep.url,
            listener = this,
        )
        client = c
        Log.i(TAG, "Connecting to ${ep.title} (${ep.url})")
        viewModelScope.launch {
            runCatching { c.connect() }
                .onFailure {
                    // Keep the full stack/cause in logcat; show the user something plain.
                    Log.e(TAG, "connect failed", it)
                    reportSessionError(friendlyConnectError(it))
                    stopSession()
                }
        }
    }

    fun disconnect() {
        expectedDisconnect = true
        // Fully tear the client down before any other mode can start — never keep
        // two backend connections live at once.
        val c = client
        client = null
        viewModelScope.launch {
            runCatching { c?.disconnect() }
            c?.release()
        }
    }

    /**
     * Map a connect() failure to a short, user-facing line. The most common case
     * while the backend is being stood up is a refused/timed-out TCP connect to
     * the Pipecat server, which arrives wrapped (RTVIException → … → ConnectException);
     * walk the cause chain to classify it. Full detail is already in logcat.
     */
    private fun friendlyConnectError(t: Throwable): String {
        var cause: Throwable? = t
        while (cause != null) {
            if (cause is java.net.ConnectException || cause is java.net.SocketTimeoutException) {
                return "Can't reach the Pipecat server. Is it running?"
            }
            if (cause is java.net.UnknownHostException) {
                return "Pipecat server address not found. Check the connection."
            }
            cause = cause.cause
        }
        return "Couldn't connect. Tap Retry to try again."
    }

    // ---- UI controls ---------------------------------------------------------

    fun toggleSpeaker() = setSpeaker(!ui.value.speakerOn)

    fun setSpeaker(enabled: Boolean) {
        _ui.update { it.copy(speakerOn = enabled) }
        prefs.setSpeakerOn(enabled)
        applyRouting()
    }

    fun toggleVisualizer() {
        _showVisualizer.value = !_showVisualizer.value
    }

    fun reportSessionError(message: String?) {
        _ui.update { it.copy(error = message ?: "Session error") }
    }

    /** Clear the one-shot error after the UI has surfaced it (e.g. snackbar shown/dismissed). */
    fun clearError() {
        _ui.update { it.copy(error = null) }
    }

    // ---- PicaVoiceClient.Listener -------------------------------------------

    override fun onConnected() {
        Log.i(TAG, "Connected")
    }

    override fun onDisconnected() {
        userLvl = 0f
        botLvl = 0f
        _audioLevel.value = 0f
        if (!ui.value.sessionActive) return
        // sessionActive is still true, so this wasn't a local teardown — the
        // transport dropped us mid-session. Surface it; the snackbar's Retry
        // action restarts the session.
        if (!expectedDisconnect) {
            Log.w(TAG, "Unexpected disconnect mid-session")
            reportSessionError("Connection lost. Tap Retry to reconnect.")
        }
        stopSession()
    }

    override fun onError(message: String) {
        // A backend/RTVI error reported by the Pipecat server while connected
        // (distinct from the connect-time exception handled in connect()).
        Log.e(TAG, "Backend error: $message")
        reportSessionError("Server error: $message")
    }

    override fun onUserStartedSpeaking() {
        Log.d(TAG, "evt user-started-speaking")
        _ui.update { it.copy(activeSource = AudioSource.USER) }
    }

    override fun onUserStoppedSpeaking() {
        // Start the silence→first-audio stopwatch. Stamped here, read in
        // onBotStartedSpeaking. SystemClock is monotonic (immune to wall-clock jumps).
        userStoppedAtMs = SystemClock.elapsedRealtime()
        userLvl = 0f
        pushLevel()
        _ui.update { it.copy(isThinking = true, activeSource = AudioSource.NONE) }
    }

    override fun onBotStartedSpeaking() {
        if (userStoppedAtMs != 0L) {
            val turnMs = SystemClock.elapsedRealtime() - userStoppedAtMs
            userStoppedAtMs = 0L
            _ui.update { it.copy(lastTurnMs = turnMs, isThinking = false) }
            Log.i(TAG, "⏱ silence→first-audio ${turnMs}ms")
        }
        Log.d(TAG, "evt bot-started-speaking")
        _ui.update { it.copy(activeSource = AudioSource.BOT) }
    }

    override fun onBotStoppedSpeaking() {
        Log.d(TAG, "evt bot-stopped-speaking")
        botLvl = 0f
        pushLevel()
        _ui.update { it.copy(activeSource = AudioSource.NONE) }
        botTurnSeq++ // next bot utterance is a new transcript row
    }

    // NOTE: the SmallWebRTC RTVI client (1.1.0) never invokes these — it has no
    // audio-level message type and the transport computes no level. They're kept
    // (and still feed audioLevel) only so a future server-sent level message could
    // light them up for free; today they stay at 0 and the scope is event-driven.
    override fun onUserAudioLevel(level: Float) {
        userLvl = level
        pushLevel()
    }

    override fun onRemoteAudioLevel(level: Float) {
        botLvl = level
        pushLevel()
    }

    override fun onUserTranscript(text: String, final: Boolean) {
        if (text.isBlank()) return
        // Interim user text updates a single "current" row; finalizing advances the id.
        val id = "u-$userTurnSeq"
        ingest(id, "user", text)
        if (final) userTurnSeq++
    }

    override fun onBotTranscript(text: String, final: Boolean) {
        if (text.isBlank()) return
        Log.d(TAG, "evt bot-transcription")
        // Bot transcripts arrive as discrete sentence chunks within one turn (the id
        // only advances in onBotStoppedSpeaking), so accumulate them — otherwise each
        // sentence overwrites the last and only the final one is kept.
        ingest("b-$botTurnSeq", "assistant", text, append = true)
    }

    // ---- Transcript persistence (ported from Lika) ---------------------------

    /**
     * @param append true for bot turns (chunks are deltas → concatenate); false for user
     *   turns (interim text is cumulative → replace the row with the latest text).
     */
    private fun ingest(id: String, role: String, text: String, append: Boolean = false) {
        val sid = ui.value.sessionId ?: return
        val prev = messageById[id]?.second
        val next = if (append) {
            // Skip a chunk identical to the one just appended (re-delivery).
            if (lastChunkById[id] == text) return
            lastChunkById[id] = text
            if (prev.isNullOrEmpty()) text else "$prev $text"
        } else {
            text
        }
        if (prev == next) return

        val createdAt = messageCreatedAt.getOrPut(id) { System.currentTimeMillis() }
        // Row-level trace: which id/role each transcript lands in, so a turn-counter
        // off-by-one (two bot turns merging into one row) is visible directly in logs
        // instead of inferred from bot-transcription/bot-stopped-speaking ordering.
        Log.d(TAG, "ingest row=$id role=$role len=${next.length} text=\"${next.take(60)}\"")
        messageById[id] = role to next
        // Bump the pulse so the visualizer twitches in time with incoming speech.
        _ui.update { it.copy(messages = messageById.values.toList(), speechPulse = it.speechPulse + 1) }

        viewModelScope.launch {
            ServiceLocator.repo.upsertMessage(sid, id, role, next, createdAt)
        }
    }

    // ---- Speakerphone routing (unchanged from Lika — pure AudioManager) -------

    private fun applyRouting() {
        am.mode = AudioManager.MODE_IN_COMMUNICATION
        val speakerOn = ui.value.speakerOn
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                val preferredTypes = if (speakerOn) {
                    listOf(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
                } else {
                    listOf(
                        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                        AudioDeviceInfo.TYPE_WIRED_HEADSET,
                        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE,
                    )
                }
                val devices = am.availableCommunicationDevices
                for (type in preferredTypes) {
                    val dev = devices.firstOrNull { it.type == type }
                    if (dev != null) {
                        am.setCommunicationDevice(dev)
                        Log.i(TAG, "Routed to type $type (speakerOn=$speakerOn)")
                        return
                    }
                }
            } else {
                @Suppress("DEPRECATION")
                if (speakerOn) {
                    am.stopBluetoothSco()
                    am.isBluetoothScoOn = false
                    am.isSpeakerphoneOn = true
                } else {
                    val allDevices = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                    if (allDevices.any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }) {
                        am.startBluetoothSco()
                        am.isBluetoothScoOn = true
                        am.isSpeakerphoneOn = false
                    } else {
                        am.isSpeakerphoneOn = false
                    }
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "applyRouting failed (speakerOn=$speakerOn)", t)
        }
    }

    override fun onCleared() {
        super.onCleared()
        if (ui.value.sessionActive) stopSession()
        client?.release()
        client = null
    }
}
