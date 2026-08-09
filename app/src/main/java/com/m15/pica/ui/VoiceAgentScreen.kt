package com.m15.pica.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Headset
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import com.m15.pica.AgentUiState
import com.m15.pica.VisualizerStyle

@Composable
fun VoiceAgentScreen(
    ui: AgentUiState,
    isSpeakerOn: Boolean,
    onSpeakerToggle: () -> Unit,
    onDismissSession: () -> Unit,
    onToggleVisualizer: () -> Unit,
    showVisualizer: Boolean,
    visualizerStyle: VisualizerStyle,
    audioLevel: Float,
    badge: String,
    accentArgb: Long,
    currentSessionSaved: Boolean,
    canSave: Boolean,
    onSave: () -> Unit,
) {
    // Shared with the text-window list so the FAB labels collapse to icons while the
    // user scrolls back through history and re-expand when they return to the latest.
    val transcriptListState = rememberLazyListState()
    val fabsExpanded = transcriptListState.isScrollingUp()

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = Color.Black,
        topBar = {
            // Plain centered stack instead of a TopAppBar: the app bar clamps its
            // title to a fixed ~64dp height and vertically centers it, which clipped
            // the badge + HUD under the tall "Pipecat Client" wordmark. A Column has no such
            // clamp, so all three lay out at their natural heights.
            // statusBarsPadding() keeps the wordmark clear of the status-bar icons and
            // the camera cutout — without it the custom (non-TopAppBar) header draws
            // under the system bar (a fixed top padding can't track the cutout height).
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(top = 8.dp, bottom = 6.dp)
            ) {
                Text(
                    text = "Pipecat Client",
                    color = Color.White,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 28.sp,
                    letterSpacing = 1.5.sp,
                    maxLines = 1
                )
                // Which agent/backend this session is talking to.
                ModeBadge(badge = badge, accentArgb = accentArgb)
                // Latency HUD — the one directly-comparable A/B number:
                // silence (user stops) → first bot audio.
                LatencyHud(lastTurnMs = ui.lastTurnMs)
            }
        },
        floatingActionButton = {
            // Right-align so the pill labels grow/shrink against a fixed right edge.
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // End Session FAB
                ExtendedFloatingActionButton(
                    onClick = onDismissSession,
                    expanded = fabsExpanded,
                    containerColor = Color.White,
                    contentColor = Color.Black,
                    icon = { Icon(Icons.Default.Close, contentDescription = "End Session") },
                    text = { Text("End") },
                )

                // Speakerphone FAB
                ExtendedFloatingActionButton(
                    onClick = onSpeakerToggle,
                    expanded = fabsExpanded,
                    containerColor = if (isSpeakerOn) Color.White else Color(0xFF1A1A1A),
                    contentColor = if (isSpeakerOn) Color.Black else Color.White,
                    icon = {
                        Icon(
                            imageVector = if (isSpeakerOn) Icons.Default.VolumeUp else Icons.Default.Headset,
                            contentDescription = if (isSpeakerOn) "Speaker On" else "Speaker Off",
                        )
                    },
                    text = { Text("Speaker") },
                )

                // Toggle Visualizer/Text
                ExtendedFloatingActionButton(
                    onClick = onToggleVisualizer,
                    expanded = fabsExpanded,
                    containerColor = if (showVisualizer) Color.White else Color(0xFF1A1A1A),
                    contentColor = if (showVisualizer) Color.Black else Color.White,
                    icon = {
                        Icon(
                            imageVector = if (showVisualizer) Icons.Default.Chat else Icons.Default.GraphicEq,
                            contentDescription = "Toggle Visualizer",
                        )
                    },
                    text = { Text(if (showVisualizer) "Text" else "Visualize") },
                )

                // Save conversation FAB — fills once saved; inert until there's something
                // to save and after it's already saved.
                val saveEnabled = canSave && !currentSessionSaved
                ExtendedFloatingActionButton(
                    onClick = { if (saveEnabled) onSave() },
                    expanded = fabsExpanded,
                    containerColor = if (currentSessionSaved) Color.White else Color(0xFF1A1A1A),
                    contentColor = if (currentSessionSaved) Color.Black
                        else if (saveEnabled) Color.White else Color(0xFF555555),
                    icon = {
                        Icon(
                            imageVector = if (currentSessionSaved) Icons.Default.Bookmark
                                else Icons.Default.BookmarkBorder,
                            contentDescription = if (currentSessionSaved) "Saved" else "Save conversation",
                        )
                    },
                    text = { Text(if (currentSessionSaved) "Saved" else "Save") },
                )
            }
        },
    ) { pad ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .padding(pad)
        ) {
            if (showVisualizer) {
                val vizModifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 18.dp, vertical = 12.dp)
                when (visualizerStyle) {
                    VisualizerStyle.SCOPE -> AcousticScopeVisualizer(
                        level = audioLevel,
                        source = ui.activeSource,
                        pulse = ui.speechPulse,
                        modifier = vizModifier,
                    )
                    VisualizerStyle.BASES -> BasesVisualizer(
                        level = audioLevel,
                        source = ui.activeSource,
                        pulse = ui.speechPulse,
                        modifier = vizModifier,
                    )
                    VisualizerStyle.ORB -> OrbVisualizer(
                        level = audioLevel,
                        source = ui.activeSource,
                        pulse = ui.speechPulse,
                        modifier = vizModifier,
                    )
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp)
                ) {
                    LazyColumn(
                        state = transcriptListState,
                        modifier = Modifier.weight(1f),
                        reverseLayout = true
                    ) {
                        items(ui.messages.asReversed()) { (role, msg) ->
                            val color = if (role == "assistant") Color.White else Color(0xFF888888)
                            ChatBubble(role, msg, color)
                        }
                    }

                    if (ui.isThinking) {
                        Text(
                            "thinking...",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.align(Alignment.CenterHorizontally),
                            textAlign = TextAlign.Center,
                            color = Color.White.copy(alpha = 0.6f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ModeBadge(badge: String, accentArgb: Long) {
    // The active agent's accent (data-driven) tints the pill so the backend is
    // recognizable at a glance.
    val accent = Color(accentArgb)
    Box(
        modifier = Modifier
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(accent.copy(alpha = 0.18f))
            .border(1.dp, accent.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 3.dp),
    ) {
        Text(
            text = badge,
            color = accent,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.5.sp,
            maxLines = 1,
        )
    }
}

@Composable
private fun LatencyHud(lastTurnMs: Long?) {
    val text = lastTurnMs?.let { "⏱ ${it} ms  (silence → first audio)" } ?: "⏱ —"
    Text(
        text = text,
        color = Color(0xFF66E08A),
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        maxLines = 1,
    )
}

@Composable
fun ChatBubble(role: String, text: String, color: Color) {
    val alignment = if (role == "assistant") Alignment.CenterStart else Alignment.CenterEnd
    val bubbleColor = color.copy(alpha = 0.12f)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        contentAlignment = alignment
    ) {
        Box(
            modifier = Modifier
                .background(bubbleColor, MaterialTheme.shapes.large)
                .padding(14.dp)
                .widthIn(max = 320.dp)
        ) {
            Text(
                text = text,
                color = color,
                style = MaterialTheme.typography.bodyLarge
            )
        }
    }
}

/**
 * True while the list is at rest or scrolling back toward the latest messages; false
 * while scrolling into history. With `reverseLayout` the newest message sits at index 0,
 * so a *decreasing* first-visible index means "returning to the latest" → expand the FABs.
 */
@Composable
private fun LazyListState.isScrollingUp(): Boolean {
    var prevIndex by remember(this) { mutableIntStateOf(firstVisibleItemIndex) }
    var prevOffset by remember(this) { mutableIntStateOf(firstVisibleItemScrollOffset) }
    return remember(this) {
        derivedStateOf {
            if (prevIndex != firstVisibleItemIndex) {
                prevIndex > firstVisibleItemIndex
            } else {
                prevOffset >= firstVisibleItemScrollOffset
            }.also {
                prevIndex = firstVisibleItemIndex
                prevOffset = firstVisibleItemScrollOffset
            }
        }
    }.value
}
