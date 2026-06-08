package com.daedalus.echo.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import android.content.Intent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Slider
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import java.io.File
import kotlinx.coroutines.delay
import com.daedalus.echo.ui.mindmap.MindMapCanvas
import com.daedalus.echo.viewmodel.RecordingViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteDetailScreen(
    filename: String,
    recordingViewModel: RecordingViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val prefs   = remember { context.getSharedPreferences("daedalus_prefs", android.content.Context.MODE_PRIVATE) }
    val note by recordingViewModel.currentNote.collectAsState()
    val isProcessing by recordingViewModel.isProcessing.collectAsState()
    val isAsking by recordingViewModel.isAsking.collectAsState()
    val syncProgress by recordingViewModel.syncProgress.collectAsState()
    val aiError by recordingViewModel.aiError.collectAsState()
    val askAnswer by recordingViewModel.askAnswer.collectAsState()
    val exportIntent by recordingViewModel.exportIntent.collectAsState()

    // Launch share sheet when export intent is ready
    LaunchedEffect(exportIntent) {
        exportIntent?.let {
            context.startActivity(it)
            recordingViewModel.clearExportIntent()
        }
    }

    // Player setup
    val player = remember { ExoPlayer.Builder(context).build() }
    var isPlaying by remember { mutableStateOf(false) }
    var playbackPosition by remember { mutableLongStateOf(0L) }
    var playbackDuration by remember { mutableLongStateOf(0L) }

    // Sync isPlaying when audio finishes naturally
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED || playbackState == Player.STATE_IDLE) {
                    isPlaying = false
                    playbackPosition = 0L
                }
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    // Poll position while playing
    LaunchedEffect(isPlaying) {
        while (isPlaying) {
            playbackPosition = player.currentPosition.coerceAtLeast(0L)
            playbackDuration = player.duration.takeIf { it > 0L } ?: (note?.durationMillis ?: 0L)
            delay(200)
        }
    }

    val transcript = note?.transcript.orEmpty()
    val summary = note?.summary.orEmpty()
    val mindMap = note?.mindMap.orEmpty()

    // Load note on first composition
    LaunchedEffect(filename) {
        recordingViewModel.loadNote(filename)
    }

    var selectedTab by remember { mutableIntStateOf(0) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showInNoteSearch by remember { mutableStateOf(false) }
    var inNoteQuery by remember { mutableStateOf("") }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete recording?") },
            text = { Text("This will permanently remove the recording and all its AI-generated analysis data.") },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteDialog = false
                        recordingViewModel.deleteRecording(filename)
                        onBack()
                    }
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text("Cancel") }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    val displayTitle = if (!note?.title.isNullOrBlank()) {
                        note!!.title
                    } else {
                        formatFilename(filename)
                    }
                    Text(
                        text = displayTitle,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { showInNoteSearch = !showInNoteSearch; if (!showInNoteSearch) inNoteQuery = "" }) {
                        Icon(Icons.Default.Search, contentDescription = "Search in note")
                    }
                    IconButton(onClick = { showDeleteDialog = true }) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                    navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
                    actionIconContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        },
        bottomBar = {
            Column(modifier = Modifier.navigationBarsPadding()) {
                if (aiError != null) {
                    Text(
                        text = "AI Error: $aiError",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                    )
                }
                if (isProcessing) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                // Playback progress bar
                val totalDuration = playbackDuration.takeIf { it > 0L } ?: (note?.durationMillis ?: 0L)
                if (totalDuration > 0L) {
                    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                        Slider(
                            value = if (totalDuration > 0L) playbackPosition.toFloat() / totalDuration.toFloat() else 0f,
                            onValueChange = { fraction ->
                                val seekTo = (fraction * totalDuration).toLong()
                                player.seekTo(seekTo)
                                playbackPosition = seekTo
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = formatDuration(playbackPosition),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = formatDuration(totalDuration),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Play / Stop Button
                    Button(
                        onClick = {
                            if (isPlaying) {
                                player.stop()
                                isPlaying = false
                                playbackPosition = 0L
                            } else {
                                val file = note?.localPath?.let { File(it) }
                                    ?: File(context.getExternalFilesDir(null), "Recordings/$filename")
                                if (file.exists()) {
                                    player.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
                                    player.prepare()
                                    player.play()
                                    playbackDuration = note?.durationMillis ?: 0L
                                    isPlaying = true
                                } else {
                                    Log.e("Playback", "File not found: ${file.absolutePath}")
                                }
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            if (isPlaying) Icons.Default.Stop else Icons.Default.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(if (isPlaying) "Stop" else "Play", maxLines = 1)
                    }

                    Button(
                        onClick = { recordingViewModel.analyze(filename) },
                        enabled = !isProcessing,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Analyze")
                    }
                    OutlinedButton(
                        onClick = { recordingViewModel.exportMarkdown(filename) },
                        enabled = transcript.isNotEmpty() && !isProcessing,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Export MD")
                    }
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {


            TabRow(selectedTabIndex = selectedTab) {
                listOf("Transcript", "Summary", "Mind Map", "Ask").forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = {
                            selectedTab = index
                            if (index != 3) recordingViewModel.clearAskAnswer()
                        },
                        text = { Text(title) }
                    )
                }
            }

            if (showInNoteSearch) {
                OutlinedTextField(
                    value = inNoteQuery,
                    onValueChange = { inNoteQuery = it },
                    placeholder = { Text("Find in note…") },
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    trailingIcon = {
                        if (inNoteQuery.isNotEmpty()) {
                            IconButton(onClick = { inNoteQuery = "" }) {
                                Icon(Icons.Default.Close, null)
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    singleLine = true
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                when (selectedTab) {
                    0 -> TranscriptTab(transcript, inNoteQuery)
                    1 -> SummaryTab(summary, inNoteQuery)
                    2 -> MindMapTab(mindMap)
                    3 -> AskTab(
                        answer = askAnswer,
                        isAsking = isAsking,
                        onAsk = { q -> recordingViewModel.askNoteQuestion(filename, q) }
                    )
                }
            }
        }
    }
}

@Composable
private fun TranscriptTab(transcript: String, query: String) {
    if (transcript.isEmpty()) {
        PlaceholderText("Tap 'Analyze' to transcribe this recording.")
    } else {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                text = highlightMatches(transcript, query),
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@Composable
private fun SummaryTab(summary: String, query: String) {
    if (summary.isEmpty()) {
        PlaceholderText("No summary yet. Tap 'Analyze' to generate one.")
    } else {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            val sections = parseSummarySections(summary)
            if (sections.isEmpty()) {
                Text(highlightMatches(summary, query), style = MaterialTheme.typography.bodyMedium)
            } else {
                sections.forEach { (key, value) ->
                    Text(
                        text = key,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(text = highlightMatches(value, query), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun MindMapTab(mindMap: String) {
    if (mindMap.isEmpty()) {
        PlaceholderText("No mind map yet. Tap 'Analyze' to generate one.")
    } else {
        MindMapCanvas(markdown = mindMap)
    }
}

@Composable
private fun PlaceholderText(message: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}


@Composable
private fun AskTab(
    answer: String?,
    isAsking: Boolean,
    onAsk: (String) -> Unit
) {
    var question by remember { mutableStateOf("") }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        OutlinedTextField(
            value = question,
            onValueChange = { question = it },
            placeholder = { Text("Ask a question about this note…") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2,
            maxLines = 4
        )
        Button(
            onClick = { if (question.isNotBlank()) onAsk(question) },
            enabled = question.isNotBlank() && !isAsking,
            modifier = Modifier.fillMaxWidth()
        ) {
            if (isAsking) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
            }
            Text(if (isAsking) "Thinking…" else "Ask")
        }
        if (answer != null) {
            Text(
                text = answer,
                style = MaterialTheme.typography.bodyMedium
            )
        } else if (!isAsking) {
            Text(
                text = "Answers come from the note's summary.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Turns a JSON summary blob into readable (header, text) pairs.
 * Falls back to empty list if the input isn't JSON-like.
 */
private fun parseSummarySections(raw: String): List<Pair<String, String>> {
    val trimmed = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
    if (!trimmed.startsWith("{")) return emptyList()
    return try {
        val sections = mutableListOf<Pair<String, String>>()
        // Simple regex-based extraction: "key": value (string or array)
        val fieldRegex = Regex(""""(\w+)"\s*:\s*("(?:[^"\\]|\\.)*"|\[[\s\S]*?]|\{[\s\S]*?})""")
        fieldRegex.findAll(trimmed).forEach { match ->
            val key = match.groupValues[1]
                .replace('_', ' ')
                .replaceFirstChar { it.uppercase() }
            val valueRaw = match.groupValues[2].trim()
            val value = when {
                valueRaw.startsWith('"') -> valueRaw.removeSurrounding("\"").replace("\\n", "\n").replace("\\\"", "\"")
                valueRaw.startsWith('[') -> {
                    // Extract strings from array
                    Regex(""""([^"\\]*)"""").findAll(valueRaw)
                        .map { "• ${it.groupValues[1]}" }
                        .joinToString("\n")
                }
                else -> valueRaw
            }
            if (value.isNotBlank()) sections.add(key to value)
        }
        sections
    } catch (_: Exception) {
        emptyList()
    }
}

private fun formatFilename(filename: String): String {
    val base = filename.substringBeforeLast(".")
    val match = Regex("""(\d{4})(\d{2})(\d{2})(\d{2})(\d{2})(\d{2})""").find(base) ?: return base
    val (year, month, day, hour, min, sec) = match.destructured
    return "$year-$month-$day $hour:$min:$sec"
}

private fun formatDuration(millis: Long): String {
    val totalSeconds = (millis / 1000).coerceAtLeast(0L)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}

private fun highlightMatches(text: String, query: String): AnnotatedString = buildAnnotatedString {
    if (query.isBlank()) { append(text); return@buildAnnotatedString }
    var start = 0
    val lower = text.lowercase()
    val q = query.lowercase()
    while (true) {
        val idx = lower.indexOf(q, start)
        if (idx < 0) { append(text.substring(start)); break }
        append(text.substring(start, idx))
        withStyle(SpanStyle(background = Color(0xFFFFFF00), color = Color.Black)) {
            append(text.substring(idx, idx + q.length))
        }
        start = idx + q.length
    }
}

