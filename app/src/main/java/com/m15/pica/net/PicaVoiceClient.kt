package com.m15.pica.net

import android.content.Context
import android.util.Log
import ai.pipecat.client.PipecatClient
import ai.pipecat.client.PipecatClientOptions
import ai.pipecat.client.PipecatEventCallbacks
import ai.pipecat.client.small_webrtc_transport.SmallWebRTCTransport
import ai.pipecat.client.small_webrtc_transport.SmallWebRTCTransportConnectParams
import ai.pipecat.client.types.APIRequest
import ai.pipecat.client.types.BotReadyData
import ai.pipecat.client.types.Participant
import ai.pipecat.client.types.Transcript
import ai.pipecat.client.types.Value

/**
 * The one file that touches the Pipecat SDK. Everything else in Pica is
 * transport-agnostic and talks to this through [Listener], so if the SDK API
 * shifts between versions only this wrapper needs adjusting.
 *
 * It opens a direct P2P WebRTC connection to the Pipecat server's `/api/offer`
 * (no JWT, no SFU, no rooms — that's the whole Pipecat-vs-LiveKit contrast) and
 * fans the RTVI events out to the ViewModel.
 *
 * Verified against ai.pipecat:small-webrtc-transport:1.1.0 (pulls
 * ai.pipecat:client:1.1.0, RTVI protocol 1.0.0). Post-1.0 API shape:
 * `PipecatClient(transport, options)` — the transport is constructed directly
 * (no TransportFactory) and the callbacks live inside [PipecatClientOptions];
 * the offer endpoint moves from construction time to `connect()` via
 * [SmallWebRTCTransportConnectParams]. connect()/disconnect() return Futures
 * whose await() is a member suspend fun.
 */
class PicaVoiceClient(
    context: Context,
    private val serverUrl: String,
    private val listener: Listener,
) {

    /** Transport-agnostic events the ViewModel cares about. */
    interface Listener {
        fun onConnected()
        fun onDisconnected()
        fun onError(message: String)
        fun onUserStartedSpeaking()
        fun onUserStoppedSpeaking()
        fun onBotStartedSpeaking()
        fun onBotStoppedSpeaking()
        fun onUserAudioLevel(level: Float)
        fun onRemoteAudioLevel(level: Float)
        fun onUserTranscript(text: String, final: Boolean)
        fun onBotTranscript(text: String, final: Boolean)
    }

    private val callbacks = object : PipecatEventCallbacks() {
        override fun onConnected() = listener.onConnected()
        override fun onDisconnected() = listener.onDisconnected()
        override fun onBackendError(message: String) = listener.onError(message)
        override fun onBotReady(data: BotReadyData) {
            Log.i(TAG, "Bot ready (rtvi ${data.version})")
        }
        override fun onUserStartedSpeaking() = listener.onUserStartedSpeaking()
        override fun onUserStoppedSpeaking() = listener.onUserStoppedSpeaking()
        override fun onBotStartedSpeaking() = listener.onBotStartedSpeaking()
        override fun onBotStoppedSpeaking() = listener.onBotStoppedSpeaking()
        override fun onUserAudioLevel(level: Float) = listener.onUserAudioLevel(level)
        override fun onRemoteAudioLevel(level: Float, participant: Participant) =
            listener.onRemoteAudioLevel(level)
        override fun onUserTranscript(data: Transcript) =
            listener.onUserTranscript(data.text, data.`final`)
        // onBotTranscript is deprecated in 1.x in favour of onBotOutput(BotOutputData),
        // but it still fires and preserves our existing plain-text transcript behaviour.
        // Switching to onBotOutput is a follow-up, not part of the version bump.
        @Suppress("DEPRECATION")
        override fun onBotTranscript(text: String) =
            listener.onBotTranscript(text, true)
    }

    // 1.x: the transport is constructed directly (no Factory) and passed to the
    // client; the server URL is supplied later at connect() time, not here.
    private val transport = SmallWebRTCTransport(context)

    private val client = PipecatClient(
        transport,
        PipecatClientOptions(
            callbacks = callbacks,
            enableMic = true,
            enableCam = false,
        ),
    )

    suspend fun connect() {
        // The SDP offer is POSTed to serverUrl verbatim (the full /api/offer URL);
        // requestData is empty for the SmallWebRTC handshake.
        client.connect(
            SmallWebRTCTransportConnectParams(
                webrtcRequestParams = APIRequest(
                    endpoint = serverUrl,
                    requestData = Value.Object(),
                )
            )
        ).await()
    }

    suspend fun disconnect() {
        client.disconnect().await()
    }

    fun enableMic(enable: Boolean) {
        // Returns a Future in 1.x; fire-and-forget matches prior behaviour.
        client.enableMic(enable)
    }

    fun release() {
        client.release()
    }

    private companion object {
        private const val TAG = "PicaVoiceClient"
    }
}