package com.m15.pica.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.m15.pica.ServerEndpoint
import com.m15.pica.VisualizerStyle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PicaSetupScreen(
    agents: List<ServerEndpoint>,
    selectedId: String,
    onSelect: (String) -> Unit,
    onAdd: (title: String, host: String, port: Int, path: String, visualizer: VisualizerStyle) -> Unit,
    onUpdate: (id: String, title: String, host: String, port: Int, path: String, visualizer: VisualizerStyle) -> Unit,
    onDelete: (String) -> Unit,
    onStartSession: () -> Unit,
    onMenuClick: () -> Unit,
) {
    // null = closed. Holds the agent being edited, or a blank one in "add" mode.
    var editorTarget by remember { mutableStateOf<EditorTarget?>(null) }
    val canStart = agents.any { it.id == selectedId }

    Scaffold(
        containerColor = Color.Black,
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(top = 24.dp)
                    ) {
                        Text(
                            text = "Pipecat Client",
                            color = Color.White,
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 28.sp,
                            letterSpacing = 1.5.sp,
                            maxLines = 1
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onMenuClick) {
                        Icon(
                            imageVector = Icons.Default.Menu,
                            contentDescription = "History",
                            tint = Color.White,
                        )
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = Color.Transparent
                )
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .padding(padding)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // --- Agent selector (which backend the session connects to) ---
                Text(
                    text = "Agent",
                    color = Color(0xFF888888),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 1.sp,
                )
                AgentList(
                    agents = agents,
                    selectedId = selectedId,
                    onSelect = onSelect,
                    onEdit = { editorTarget = EditorTarget(it) },
                    onAdd = { editorTarget = EditorTarget(null) },
                    modifier = Modifier.weight(1f),
                )

                // --- Primary START button ---
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .clip(RoundedCornerShape(24.dp))
                            .background(if (canStart) Color.White else Color(0xFF333333))
                            .then(
                                if (canStart) Modifier.clickable(onClick = onStartSession)
                                else Modifier
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (canStart) "Start" else "Add an agent to start",
                            color = if (canStart) Color.Black else Color(0xFF888888),
                            fontSize = if (canStart) 22.sp else 16.sp,
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = 1.sp
                        )
                    }
                }
            }
        }
    }

    editorTarget?.let { target ->
        AgentEditorDialog(
            initial = target.agent,
            onDismiss = { editorTarget = null },
            onConfirm = { title, host, port, path, visualizer ->
                val existing = target.agent
                if (existing == null) onAdd(title, host, port, path, visualizer)
                else onUpdate(existing.id, title, host, port, path, visualizer)
                editorTarget = null
            },
            onDelete = target.agent?.let { agent ->
                {
                    onDelete(agent.id)
                    editorTarget = null
                }
            },
        )
    }
}

/** Wrapper so the dialog state distinguishes "add" (agent == null) from "edit". */
private class EditorTarget(val agent: ServerEndpoint?)

@Composable
private fun AgentList(
    agents: List<ServerEndpoint>,
    selectedId: String,
    onSelect: (String) -> Unit,
    onEdit: (ServerEndpoint) -> Unit,
    onAdd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Color(0xFF1A1A1A))
            .padding(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // Fill the available space so short lists don't scroll inside a cramped
        // window; weight(1f) leaves the Add row pinned below and the Start button
        // below that. The list still scrolls on its own if it ever overflows.
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(agents, key = { it.id }) { agent ->
                AgentRow(
                    agent = agent,
                    selected = agent.id == selectedId,
                    onSelect = { onSelect(agent.id) },
                    onEdit = { onEdit(agent) },
                )
            }
        }
        AddAgentRow(onAdd = onAdd)
    }
}

@Composable
private fun AgentRow(
    agent: ServerEndpoint,
    selected: Boolean,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) Color.White else Color.Transparent)
            .clickable(onClick = onSelect)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = agent.title,
                color = if (selected) Color.Black else Color(0xFFDDDDDD),
                fontSize = 16.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            )
            Text(
                text = "${agent.host}:${agent.port}",
                color = if (selected) Color(0xFF555555) else Color(0xFF888888),
                fontSize = 12.sp,
            )
        }
        IconButton(onClick = onEdit) {
            Icon(
                imageVector = Icons.Default.Edit,
                contentDescription = "Edit ${agent.title}",
                tint = if (selected) Color(0xFF555555) else Color(0xFF888888),
            )
        }
    }
}

@Composable
private fun AddAgentRow(onAdd: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onAdd)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = Icons.Default.Add,
            contentDescription = null,
            tint = Color(0xFF66E08A),
        )
        Text(
            text = "Add agent",
            color = Color(0xFF66E08A),
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

/**
 * Add/edit dialog. [initial] is null for "add". Validates locally — title and host
 * non-blank, host free of scheme/slashes, port in 1..65535, path leading-slash —
 * and only enables Save when the input is usable.
 */
@Composable
private fun AgentEditorDialog(
    initial: ServerEndpoint?,
    onDismiss: () -> Unit,
    onConfirm: (title: String, host: String, port: Int, path: String, visualizer: VisualizerStyle) -> Unit,
    onDelete: (() -> Unit)?,
) {
    var title by remember { mutableStateOf(initial?.title ?: "") }
    var host by remember { mutableStateOf(initial?.host ?: "") }
    var portText by remember { mutableStateOf(initial?.port?.toString() ?: "") }
    var path by remember { mutableStateOf(initial?.path ?: ServerEndpoint.DEFAULT_PATH) }
    var visualizer by remember { mutableStateOf(initial?.visualizerStyle ?: VisualizerStyle.DEFAULT) }

    val port = portText.toIntOrNull()
    val titleOk = title.isNotBlank()
    val hostOk = host.isNotBlank() && !host.contains("/") && !host.contains(" ") &&
        !host.contains("://")
    val portOk = port != null && port in 1..65535
    val pathOk = path.startsWith("/")
    val valid = titleOk && hostOk && portOk && pathOk

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Add agent" else "Edit agent") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Title") },
                    singleLine = true,
                    isError = title.isNotEmpty() && !titleOk,
                )
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it },
                    label = { Text("Host") },
                    placeholder = { Text("pi5-16.tail1f4ac9.ts.net") },
                    singleLine = true,
                    isError = host.isNotEmpty() && !hostOk,
                )
                OutlinedTextField(
                    value = portText,
                    onValueChange = { portText = it.filter(Char::isDigit) },
                    label = { Text("Port") },
                    placeholder = { Text("7860") },
                    singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = KeyboardType.Number
                    ),
                    isError = portText.isNotEmpty() && !portOk,
                )
                OutlinedTextField(
                    value = path,
                    onValueChange = { path = it },
                    label = { Text("Path") },
                    singleLine = true,
                    isError = !pathOk,
                )
                // --- Visualizer style (radio list scales as styles are added) ---
                Text(
                    text = "Visualizer",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(top = 4.dp),
                )
                VisualizerStyle.entries.forEach { style ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { visualizer = style },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = visualizer == style,
                            onClick = { visualizer = style },
                        )
                        Text(style.label)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (valid) onConfirm(title, host, port!!, path, visualizer) },
                enabled = valid,
            ) { Text("Save") }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (onDelete != null) {
                    TextButton(onClick = onDelete) {
                        Text("Delete", color = Color(0xFFE07A7A))
                    }
                }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}
