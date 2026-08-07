package com.m15.pica.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import com.m15.pica.VoiceAgentViewModel

/**
 * Drives the Pica voice session for the duration this is composed.
 *
 * Compared to Lika's LiveKitSessionHost there is almost nothing here: no token
 * mint, no rememberSession/SessionScope, no transcription text-stream handler to
 * unregister. The Pipecat client (owned by the ViewModel) connects on enter and
 * disconnects on dispose; all event wiring happens through the VM's
 * PicaVoiceClient.Listener implementation.
 *
 * Compose this only while [VoiceAgentViewModel.ui] sessionActive is true.
 */
@Composable
fun PicaSessionHost(
    vm: VoiceAgentViewModel,
    content: @Composable () -> Unit,
) {
    DisposableEffect(Unit) {
        vm.connect()
        onDispose { vm.disconnect() }
    }

    content()
}
