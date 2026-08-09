package com.m15.pica

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.ShareCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.m15.pica.ui.ConversationViewerScreen
import com.m15.pica.ui.PicaSessionHost
import com.m15.pica.ui.PicaSetupScreen
import com.m15.pica.ui.RenameDialog
import com.m15.pica.ui.SavedConversationsDrawerContent
import com.m15.pica.ui.VoiceAgentScreen
import com.m15.pica.ui.theme.PicaTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val vm by viewModels<VoiceAgentViewModel>()

    private val requestPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ -> }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            PicaTheme {
                val uiState by vm.ui.collectAsStateWithLifecycle()
                val showViz by vm.showVisualizer.collectAsStateWithLifecycle()
                val audioLevel by vm.audioLevel.collectAsStateWithLifecycle()
                val agents by vm.agents.collectAsStateWithLifecycle()
                val savedSessions by vm.savedSessions.collectAsStateWithLifecycle(initialValue = emptyList())
                val context = LocalContext.current
                // Conversation targeted by the drawer's Rename/Delete overflow (viewer
                // has its own inline dialogs).
                var renameTarget by remember { mutableStateOf<com.m15.pica.data.db.ChatSession?>(null) }
                var deleteTarget by remember { mutableStateOf<com.m15.pica.data.db.ChatSession?>(null) }

                // Request mic (+ notifications for the foreground-service banner) and keep
                // the screen on only while a session is live. The foreground service keeps
                // the mic session alive if the screen does turn off / app backgrounds.
                LaunchedEffect(uiState.sessionActive) {
                    if (uiState.sessionActive) {
                        val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
                        if (Build.VERSION.SDK_INT >= 33) {
                            perms += Manifest.permission.POST_NOTIFICATIONS
                        }
                        requestPermissions.launch(perms.toTypedArray())
                        MicForegroundService.start(this@MainActivity)
                        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    } else {
                        MicForegroundService.stop(this@MainActivity)
                        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    }
                }

                // Hosted at the app root so connection failures (which flip
                // sessionActive→false and swap VoiceAgentScreen out for the setup
                // screen) still surface — a snackbar owned by VoiceAgentScreen would
                // be disposed by that very swap before it could render.
                val snackbarHostState = remember { SnackbarHostState() }
                LaunchedEffect(uiState.error) {
                    val msg = uiState.error ?: return@LaunchedEffect
                    val result = snackbarHostState.showSnackbar(
                        message = msg,
                        actionLabel = "Retry",
                        withDismissAction = true,
                        duration = SnackbarDuration.Long,
                    )
                    vm.clearError()
                    if (result == SnackbarResult.ActionPerformed) vm.startSession()
                }

                Box(Modifier.fillMaxSize()) {
                    when {
                        // 1) Live session.
                        uiState.sessionActive -> {
                            val selected = agents.firstOrNull { it.id == uiState.selectedId }
                            PicaSessionHost(vm = vm) {
                                VoiceAgentScreen(
                                    ui = uiState,
                                    isSpeakerOn = uiState.speakerOn,
                                    onSpeakerToggle = { vm.toggleSpeaker() },
                                    onDismissSession = { vm.stopSession() },
                                    onToggleVisualizer = vm::toggleVisualizer,
                                    showVisualizer = showViz,
                                    visualizerStyle = selected?.visualizerStyle
                                        ?: com.m15.pica.VisualizerStyle.DEFAULT,
                                    audioLevel = audioLevel,
                                    badge = selected?.badge ?: "",
                                    accentArgb = selected?.accentArgb
                                        ?: com.m15.pica.ServerEndpoint.DEFAULT_ACCENT_ARGB,
                                    currentSessionSaved = uiState.currentSessionSaved,
                                    canSave = uiState.messages.isNotEmpty(),
                                    onSave = { vm.saveCurrentSession() },
                                )
                            }
                        }
                        // 2) Read-only history viewer.
                        uiState.viewingSessionId != null -> {
                            val vid = uiState.viewingSessionId!!
                            val session = savedSessions.firstOrNull { it.id == vid }
                            if (session == null) {
                                // The session was deleted out from under us — leave the viewer.
                                LaunchedEffect(vid) { vm.closeViewer() }
                            } else {
                                val msgs by vm.messages(vid)
                                    .collectAsStateWithLifecycle(initialValue = emptyList())
                                ConversationViewerScreen(
                                    session = session,
                                    messages = msgs,
                                    onBack = { vm.closeViewer() },
                                    onShare = {
                                        val text = vm.buildShareText(session.title, session.agentTitle, msgs)
                                        ShareCompat.IntentBuilder(context)
                                            .setType("text/plain")
                                            .setText(text)
                                            .setChooserTitle("Share conversation")
                                            .startChooser()
                                    },
                                    onRename = { vm.renameSession(vid, it) },
                                    onDelete = { vm.deleteSession(vid) },
                                )
                            }
                        }
                        // 3) Idle setup screen, with the history drawer (only mounted
                        //    here, so it can't be edge-swiped open mid-session).
                        else -> {
                            val drawerState = rememberDrawerState(DrawerValue.Closed)
                            val scope = rememberCoroutineScope()
                            ModalNavigationDrawer(
                                drawerState = drawerState,
                                drawerContent = {
                                    SavedConversationsDrawerContent(
                                        sessions = savedSessions,
                                        onOpen = { id ->
                                            scope.launch { drawerState.close() }
                                            vm.openViewer(id)
                                        },
                                        onRename = { id ->
                                            renameTarget = savedSessions.firstOrNull { it.id == id }
                                        },
                                        onDelete = { id ->
                                            deleteTarget = savedSessions.firstOrNull { it.id == id }
                                        },
                                    )
                                },
                            ) {
                                PicaSetupScreen(
                                    agents = agents,
                                    selectedId = uiState.selectedId,
                                    onSelect = vm::selectAgent,
                                    onAdd = vm::addAgent,
                                    onUpdate = vm::updateAgent,
                                    onDelete = vm::deleteAgent,
                                    onStartSession = { vm.startSession() },
                                    onMenuClick = { scope.launch { drawerState.open() } },
                                )
                            }
                        }
                    }

                    // Drawer overflow → rename/delete dialogs.
                    renameTarget?.let { target ->
                        RenameDialog(
                            initial = target.title,
                            onConfirm = { vm.renameSession(target.id, it); renameTarget = null },
                            onDismiss = { renameTarget = null },
                        )
                    }
                    deleteTarget?.let { target ->
                        androidx.compose.material3.AlertDialog(
                            onDismissRequest = { deleteTarget = null },
                            title = { androidx.compose.material3.Text("Delete conversation?") },
                            text = { androidx.compose.material3.Text("This permanently removes \"${target.title}\".") },
                            confirmButton = {
                                androidx.compose.material3.TextButton(onClick = {
                                    vm.deleteSession(target.id); deleteTarget = null
                                }) { androidx.compose.material3.Text("Delete", color = androidx.compose.ui.graphics.Color(0xFFE07A7A)) }
                            },
                            dismissButton = {
                                androidx.compose.material3.TextButton(onClick = { deleteTarget = null }) {
                                    androidx.compose.material3.Text("Cancel")
                                }
                            },
                        )
                    }

                    SnackbarHost(
                        hostState = snackbarHostState,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .navigationBarsPadding()
                            .padding(16.dp),
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        vm.stopSession()
        MicForegroundService.stop(this)
    }
}
