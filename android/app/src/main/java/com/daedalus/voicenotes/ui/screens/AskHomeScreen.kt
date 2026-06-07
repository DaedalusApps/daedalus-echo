package com.daedalus.voicenotes.ui.screens

import android.widget.Toast
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.daedalus.voicenotes.ai.MarkdownExporter
import com.daedalus.voicenotes.ble.ConnectionState
import com.daedalus.voicenotes.data.model.Recording
import com.daedalus.voicenotes.ui.components.DeviceStatusRow
import com.daedalus.voicenotes.viewmodel.DeviceViewModel
import com.daedalus.voicenotes.viewmodel.RecordingViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AskHomeScreen(
    recordingViewModel: RecordingViewModel,
    deviceViewModel: DeviceViewModel,
    onNavigateToNote: (String) -> Unit,
    onNavigateToRecordings: () -> Unit,
    onNavigateToExpandedMap: () -> Unit,
    onNavigateToSettings: () -> Unit
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current

    val bleState by deviceViewModel.bleManager.bleState.collectAsState()
    val libraryAnswer by recordingViewModel.libraryAnswer.collectAsState()
    val librarySources by recordingViewModel.librarySources.collectAsState()
    val libraryQuestion by recordingViewModel.libraryQuestion.collectAsState()
    val isAsking by recordingViewModel.isAsking.collectAsState()
    val aiError by recordingViewModel.aiError.collectAsState()
    val graph by recordingViewModel.globalGraph.collectAsState()
    val exportIntent by recordingViewModel.exportIntent.collectAsState()

    var question by remember { mutableStateOf("") }

    // Keep the device connecting in the background as on app open (no BLE chrome here).
    LaunchedEffect(bleState.connectionState) {
        when (bleState.connectionState) {
            ConnectionState.DISCONNECTED -> deviceViewModel.scan()
            ConnectionState.CONNECTED -> deviceViewModel.refreshFiles()
            else -> Unit
        }
    }

    // Launch share sheet when an export intent is ready.
    LaunchedEffect(exportIntent) {
        exportIntent?.let {
            context.startActivity(it)
            recordingViewModel.clearExportIntent()
        }
    }

    Scaffold(
        modifier = Modifier.navigationBarsPadding(),
        topBar = {
            TopAppBar(
                title = { Text("Daedalus Voicenotes") },
                actions = {
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            DeviceStatusRow(
                bleState = bleState,
                onScan = { deviceViewModel.scan() },
                onCancelScan = { deviceViewModel.disconnect() }
            )

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
                                .heightIn(max = 240.dp)
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
