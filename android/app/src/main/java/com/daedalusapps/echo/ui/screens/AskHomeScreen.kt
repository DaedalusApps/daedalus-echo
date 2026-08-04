package com.daedalusapps.echo.ui.screens

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.daedalusapps.echo.ai.MarkdownExporter
import com.daedalusapps.echo.data.model.AudioUtils
import com.daedalusapps.echo.data.model.Recording
import com.daedalusapps.echo.viewmodel.RecordingViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AskHomeScreen(
    recordingViewModel: RecordingViewModel,
    onNavigateToNote: (String) -> Unit,
    onNavigateToRecordings: () -> Unit,
    onNavigateToExpandedMap: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToTodos: () -> Unit,
    onNavigateToConversation: () -> Unit
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current

    // Local Recording State
    val isRecording by recordingViewModel.isRecording.collectAsState()
    val isPaused by recordingViewModel.isPaused.collectAsState()
    val recordingDurationSeconds by recordingViewModel.recordingDurationSeconds.collectAsState()
    val useBluetoothMic by recordingViewModel.useBluetoothMic.collectAsState()
    val autoStopNotice by recordingViewModel.autoStopNotice.collectAsState()
    val snackbar = remember { SnackbarHostState() }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        recordingViewModel.setUseBluetoothMic(isGranted)
    }

    val toggleBluetoothMic = {
        if (useBluetoothMic) {
            recordingViewModel.setUseBluetoothMic(false)
        } else {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                val hasPermission = androidx.core.content.ContextCompat.checkSelfPermission(
                    context,
                    android.Manifest.permission.BLUETOOTH_CONNECT
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                
                if (hasPermission) {
                    recordingViewModel.setUseBluetoothMic(true)
                } else {
                    permissionLauncher.launch(android.Manifest.permission.BLUETOOTH_CONNECT)
                }
            } else {
                recordingViewModel.setUseBluetoothMic(true)
            }
        }
    }

    val libraryAnswer by recordingViewModel.libraryAnswer.collectAsState()
    val librarySources by recordingViewModel.librarySources.collectAsState()
    val libraryQuestion by recordingViewModel.libraryQuestion.collectAsState()
    val isAsking by recordingViewModel.isAsking.collectAsState()
    val aiError by recordingViewModel.aiError.collectAsState()
    val graph by recordingViewModel.globalGraph.collectAsState()
    val exportIntent by recordingViewModel.exportIntent.collectAsState()

    var question by remember { mutableStateOf("") }

    // Launch share sheet when an export intent is ready.
    LaunchedEffect(exportIntent) {
        exportIntent?.let {
            context.startActivity(it)
            recordingViewModel.clearExportIntent()
        }
    }

    LaunchedEffect(autoStopNotice) {
        autoStopNotice?.let {
            snackbar.showSnackbar(it)
            recordingViewModel.clearAutoStopNotice()
        }
    }

    Scaffold(
        modifier = Modifier.navigationBarsPadding(),
        snackbarHost = { SnackbarHost(snackbar) { Snackbar(it) } },
        topBar = {
            TopAppBar(
                title = { Text("Daedalus Echo") },
                actions = {
                    IconButton(onClick = onNavigateToTodos) {
                        Icon(Icons.Default.Checklist, contentDescription = "Todos")
                    }
                    IconButton(onClick = onNavigateToConversation) {
                        Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = "Conversation")
                    }
                    IconButton(onClick = onNavigateToRecordings) {
                        Icon(Icons.AutoMirrored.Filled.List, contentDescription = "Recordings")
                    }
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                    actionIconContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        }
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            // Main content
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Spacer(Modifier.height(4.dp))

                OutlinedTextField(
                    value = question,
                    onValueChange = { question = it },
                    placeholder = { Text("Ask anything across all your notes…") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    maxLines = 4
                )
                Button(
                    onClick = { if (question.isNotBlank()) recordingViewModel.askLibraryQuestion(question) },
                    enabled = question.isNotBlank() && !isAsking,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (isAsking) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(if (isAsking) "Searching notes…" else "Ask")
                }

                if (aiError != null && !isAsking) {
                    Text(
                        text = aiError!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                if (libraryAnswer != null) {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 200.dp)
                                .verticalScroll(rememberScrollState())
                                .padding(12.dp)
                        ) {
                            AskAnswerContent(
                                answer = libraryAnswer!!,
                                sources = librarySources,
                                onNavigateToNote = onNavigateToNote
                            )
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.End
                        ) {
                            TextButton(onClick = {
                                val text = MarkdownExporter.exportQa(libraryQuestion, libraryAnswer!!, librarySources)
                                clipboard.setText(AnnotatedString(text))
                                Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
                            }) {
                                Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Copy")
                            }
                            TextButton(onClick = { recordingViewModel.exportLibraryAnswer() }) {
                                Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Share")
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Knowledge Graph",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = onNavigateToExpandedMap) {
                        Icon(Icons.Default.Fullscreen, contentDescription = "Expand graph")
                    }
                }

                Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                    if (graph.nodes.isEmpty()) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                "No topics analyzed yet.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        GlobalMindMapCanvas(graph, onNavigateToNote)
                    }
                }
                
                // Bottom spacing to avoid overlapping with floating recording panel
                Spacer(modifier = Modifier.height(80.dp))
            }

            // Floating Local Recording Control Panel Overlay
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                if (isRecording) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            // Pulsing/Glowing recording indicator
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .clip(CircleShape)
                                    .background(if (isPaused) Color.Gray else Color.Red)
                            )
                            
                            // Elapsed duration text
                            Text(
                                text = AudioUtils.formatDuration(recordingDurationSeconds * 1000),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(1f)
                            )

                            // Bluetooth Mic Selector
                            IconButton(onClick = toggleBluetoothMic) {
                                Icon(
                                    imageVector = if (useBluetoothMic) Icons.Default.BluetoothConnected else Icons.Default.Bluetooth,
                                    contentDescription = "Bluetooth Microphone",
                                    tint = if (useBluetoothMic) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            // Pause/Resume button
                            IconButton(onClick = {
                                if (isPaused) recordingViewModel.resumeLocalRecording()
                                else recordingViewModel.pauseLocalRecording()
                            }) {
                                Icon(
                                    imageVector = if (isPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                                    contentDescription = if (isPaused) "Resume" else "Pause"
                                )
                            }

                            // Stop button
                            Button(
                                onClick = { recordingViewModel.stopLocalRecording() },
                                colors = ButtonDefaults.buttonColors(containerColor = Color.Red, contentColor = Color.White)
                            ) {
                                Icon(Icons.Default.Stop, contentDescription = "Stop recording", modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Stop")
                            }
                        }
                    }
                } else {
                    // Bluetooth quick toggle next to FAB
                    Row(
                        modifier = Modifier.align(Alignment.BottomEnd),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Card(
                            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                        ) {
                            Row(
                                modifier = Modifier
                                    .clickable { toggleBluetoothMic() }
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    imageVector = if (useBluetoothMic) Icons.Default.BluetoothConnected else Icons.Default.Bluetooth,
                                    contentDescription = null,
                                    tint = if (useBluetoothMic) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp)
                                )
                                Text(
                                    text = "BT Mic",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (useBluetoothMic) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                        
                        FloatingActionButton(
                            onClick = { recordingViewModel.startLocalRecording() },
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        ) {
                            Icon(Icons.Default.Mic, contentDescription = "Start recording")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AskAnswerContent(
    answer: String,
    sources: List<Recording>,
    onNavigateToNote: (String) -> Unit
) {
    Text(
        text = answer,
        style = MaterialTheme.typography.bodyMedium
    )
    if (sources.isNotEmpty()) {
        Spacer(Modifier.height(8.dp))
        HorizontalDivider()
        Spacer(Modifier.height(4.dp))
        Text(
            text = "Sources",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold
        )
        sources.forEach { note ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onNavigateToNote(note.filename) }
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = note.title.ifBlank { note.filename },
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    if (note.shortSummary.isNotBlank()) {
                        Text(
                            text = note.shortSummary,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                }
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = "Open note",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}
