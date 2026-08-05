package com.daedalusapps.echo.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.daedalusapps.echo.ai.Role
import com.daedalusapps.echo.ai.VoiceInfo
import com.daedalusapps.echo.viewmodel.ChatMessage
import com.daedalusapps.echo.viewmodel.ConversationViewModel

/**
 * Minimal text-chat surface for conversation mode (#20 / EB.3), plus an End session action that
 * saves and analyzes the transcript (#22 / EB.5), an inline Stop for the in-flight send/end,
 * push-to-talk voice input (#23 / EC.1), a spoken-replies toggle (#24 / EC.2), and an overflow
 * menu with speed and voice pickers (#25 / EC.3) plus an instant-send toggle (#26 / ED.1). Also
 * includes per-message replay (#28 / ED.3): each agent bubble embeds a small play/stop icon in its
 * bottom-right corner (see [ChatBubble]).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationScreen(
    conversationViewModel: ConversationViewModel,
    onBack: () -> Unit
) {
    val messages by conversationViewModel.messages.collectAsState()
    val isGenerating by conversationViewModel.isGenerating.collectAsState()
    val error by conversationViewModel.error.collectAsState()
    val isRecordingVoice by conversationViewModel.isRecordingVoice.collectAsState()
    val isTranscribing by conversationViewModel.isTranscribing.collectAsState()
    val voiceTranscript by conversationViewModel.voiceTranscript.collectAsState()
    val ttsEnabled by conversationViewModel.ttsEnabled.collectAsState()
    val isSpeaking by conversationViewModel.isSpeaking.collectAsState()
    val speakingMessageId by conversationViewModel.speakingMessageId.collectAsState()
    val instantSend by conversationViewModel.instantSend.collectAsState()

    val context = LocalContext.current
    var input by remember { mutableStateOf("") }
    val snackbar = remember { SnackbarHostState() }
    val listState = rememberLazyListState()
    var showSpeedDialog by remember { mutableStateOf(false) }
    var showVoiceDialog by remember { mutableStateOf(false) }

    val recordAudioPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) conversationViewModel.startVoiceInput() }

    val startVoiceInput = {
        val hasPermission = ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (hasPermission) conversationViewModel.startVoiceInput()
        else recordAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    LaunchedEffect(error) {
        error?.let {
            snackbar.showSnackbar(it)
            conversationViewModel.clearError()
        }
    }

    // Appended rather than assigned: the field stays editable while transcription runs, and
    // overwriting would silently drop whatever the user typed in the meantime.
    LaunchedEffect(voiceTranscript) {
        voiceTranscript?.let {
            input = if (input.isBlank()) it else "${input.trimEnd()} $it"
            conversationViewModel.clearVoiceTranscript()
        }
    }

    // Leaving the screen abandons an in-progress recording and stops any in-progress speech; the
    // ViewModel outlives this composable, so leaving either running would otherwise hold the mic
    // or keep talking after the user navigated away.
    DisposableEffect(Unit) {
        onDispose {
            conversationViewModel.cancelVoiceInput()
            conversationViewModel.stopSpeaking()
        }
    }

    val sendCurrentInput = {
        if (input.isNotBlank()) {
            conversationViewModel.send(input)
            input = ""
        }
    }

    if (showSpeedDialog) {
        SpeedDialog(
            conversationViewModel = conversationViewModel,
            onDismiss = { showSpeedDialog = false }
        )
    }

    if (showVoiceDialog) {
        VoiceSheet(
            conversationViewModel = conversationViewModel,
            onDismiss = { showVoiceDialog = false }
        )
    }

    Scaffold(
        modifier = Modifier.navigationBarsPadding(),
        topBar = {
            TopAppBar(
                title = { Text("Conversation") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(
                        // Tapping while speaking stops that reply's speech without flipping the
                        // enabled toggle; otherwise it behaves as a mute/unmute switch.
                        onClick = {
                            if (isSpeaking) conversationViewModel.stopSpeaking()
                            else conversationViewModel.setTtsEnabled(!ttsEnabled)
                        }
                    ) {
                        if (ttsEnabled) {
                            Icon(
                                Icons.AutoMirrored.Filled.VolumeUp,
                                contentDescription = if (isSpeaking) "Speaking — tap to stop" else "Spoken replies on",
                                tint = if (isSpeaking) MaterialTheme.colorScheme.primary else LocalContentColor.current
                            )
                        } else {
                            Icon(Icons.AutoMirrored.Filled.VolumeOff, contentDescription = "Spoken replies off")
                        }
                    }
                    IconButton(
                        onClick = { conversationViewModel.endSession() },
                        enabled = messages.isNotEmpty() && !isGenerating
                    ) {
                        Icon(Icons.Default.Done, contentDescription = "End session")
                    }
                    var menuExpanded by remember { mutableStateOf(false) }
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "More options")
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Speed…") },
                            onClick = {
                                menuExpanded = false
                                showSpeedDialog = true
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Voice…") },
                            onClick = {
                                menuExpanded = false
                                showVoiceDialog = true
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Instant send") },
                            trailingIcon = {
                                // Null so the whole row is the single tap target: tapping the
                                // switch itself otherwise toggles without closing the menu.
                                Switch(checked = instantSend, onCheckedChange = null)
                            },
                            onClick = {
                                menuExpanded = false
                                conversationViewModel.setInstantSend(!instantSend)
                            }
                        )
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
        snackbarHost = { SnackbarHost(snackbar) { Snackbar(it) } }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                itemsIndexed(messages) { index, message ->
                    val id = index.toString()
                    ChatBubble(
                        message = message,
                        isReplaying = speakingMessageId == id,
                        onReplayClick = { conversationViewModel.replayMessage(id) },
                        onStopReplayClick = { conversationViewModel.stopSpeaking() }
                    )
                }
                if (isGenerating) {
                    item { GeneratingIndicator() }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Message…") },
                    enabled = !isGenerating,
                    maxLines = 4
                )
                IconButton(
                    // Tap to start, tap again to stop. Stays tappable while recording so the user
                    // can always stop — otherwise a send/end started mid-recording would lock the
                    // mic until it finishes.
                    onClick = {
                        if (isRecordingVoice) conversationViewModel.stopVoiceInput() else startVoiceInput()
                    },
                    enabled = isRecordingVoice || (!isGenerating && !isTranscribing)
                ) {
                    when {
                        isTranscribing -> CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        isRecordingVoice -> Icon(
                            Icons.Default.Stop,
                            contentDescription = "Stop recording",
                            tint = MaterialTheme.colorScheme.error
                        )
                        else -> Icon(Icons.Default.Mic, contentDescription = "Voice input")
                    }
                }
                IconButton(
                    // While generating (a send, or an endSession() analysis), this becomes an
                    // inline Stop button: tapping it aborts the in-flight work instead of sending.
                    onClick = {
                        if (isGenerating) conversationViewModel.stopGenerating() else sendCurrentInput()
                    },
                    enabled = isGenerating || input.isNotBlank()
                ) {
                    if (isGenerating) {
                        Icon(Icons.Default.Stop, contentDescription = "Stop")
                    } else {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                    }
                }
            }
        }
    }
}

/**
 * A single message bubble. AGENT messages (never USER ones) get a small "Read aloud" play icon
 * embedded in the bottom-right corner of the bubble (#28 / ED.3): tapping it replays that
 * message's text via [onReplayClick]; while this is the message currently replaying
 * ([isReplaying]), the icon becomes Stop and tapping it calls [onStopReplayClick] instead.
 *
 * The icon button is placed with `.align(Alignment.End)` directly in the Card's own (Column-
 * scoped) content, rather than inside a nested `fillMaxWidth()` Row — a `fillMaxWidth()` alignment
 * container inside the bubble would stretch the bubble itself to the full available width instead
 * of keeping its intrinsic size.
 */
@Composable
private fun ChatBubble(
    message: ChatMessage,
    isReplaying: Boolean,
    onReplayClick: () -> Unit,
    onStopReplayClick: () -> Unit
) {
    val isUser = message.role == Role.USER
    Box(modifier = Modifier.fillMaxWidth()) {
        Card(
            modifier = Modifier
                .widthIn(max = 300.dp)
                .align(if (isUser) Alignment.CenterEnd else Alignment.CenterStart),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (isUser) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Text(
                text = message.text,
                style = MaterialTheme.typography.bodyMedium,
                color = if (isUser) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
            )
            if (!isUser) {
                IconButton(
                    onClick = { if (isReplaying) onStopReplayClick() else onReplayClick() },
                    modifier = Modifier
                        .align(Alignment.End)
                        .size(48.dp)
                ) {
                    Icon(
                        imageVector = if (isReplaying) Icons.Default.Stop else Icons.Default.PlayArrow,
                        contentDescription = if (isReplaying) "Stop reading" else "Read aloud",
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

private val SPEED_OPTIONS = listOf(0.75f to "0.75×", 1.0f to "1×", 1.25f to "1.25×", 1.5f to "1.5×", 2.0f to "2×")

/** Radio list of speed presets; each selection applies (and previews) immediately. Stays open
 *  until dismissed so the user can compare presets. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SpeedDialog(
    conversationViewModel: ConversationViewModel,
    onDismiss: () -> Unit
) {
    val ttsRate by conversationViewModel.ttsRate.collectAsState()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Speech speed") },
        text = {
            Column(Modifier.selectableGroup()) {
                SPEED_OPTIONS.forEach { (rate, label) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = ttsRate == rate,
                                onClick = { conversationViewModel.setTtsRate(rate) }
                            )
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = ttsRate == rate, onClick = { conversationViewModel.setTtsRate(rate) })
                        Spacer(Modifier.width(8.dp))
                        Text(label)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

/** Full-width selectable list of "System default" + the engine's available voices; each
 *  selection applies (and previews) immediately. Stays open until dismissed (swipe/scrim) so the
 *  user can compare voices. Shows a loading row while the engine is still initializing —
 *  [ConversationViewModel.ttsReady] is observed, so the list replaces the loading row as soon as
 *  init finishes without the user needing to reopen the sheet, and a failed init gets its own
 *  message rather than spinning forever. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VoiceSheet(
    conversationViewModel: ConversationViewModel,
    onDismiss: () -> Unit
) {
    val ttsVoiceId by conversationViewModel.ttsVoiceId.collectAsState()
    val ttsEnabled by conversationViewModel.ttsEnabled.collectAsState()
    val ttsReady by conversationViewModel.ttsReady.collectAsState()
    // Evaluated on every composition, not just in the branch that shows the list: this call is
    // what lazily builds the engine (and so what starts init and eventually flips ttsReady).
    // Moving it inside the `else` branch below would leave the loading row spinning forever.
    val voices = remember(ttsReady, ttsEnabled) { conversationViewModel.availableVoices() }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState()
    ) {
        Text(
            "Voice",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
        when {
            !ttsEnabled -> {
                Text(
                    "Turn spoken replies on to see available voices.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp)
                )
            }
            ttsReady == null -> {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(16.dp))
                    Text("Starting speech engine…")
                }
            }
            ttsReady == false -> {
                Text(
                    "This device's speech engine could not be started, so no voices are available.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp)
                )
            }
            else -> {
                LazyColumn(modifier = Modifier.selectableGroup()) {
                    item {
                        VoiceRow(
                            label = "System default",
                            // A persisted id that isn't in the list (unusable/uninstalled voice
                            // data) is one the engine has already fallen back to the default
                            // for, so show that here rather than leaving nothing selected.
                            selected = ttsVoiceId.isEmpty() || voices.none { it.id == ttsVoiceId },
                            onClick = { conversationViewModel.setTtsVoice("") }
                        )
                    }
                    if (voices.isEmpty()) {
                        item {
                            Text(
                                "No other voices available — your speech engine offers only the " +
                                    "system default.",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                            )
                        }
                    }
                    items(voices) { voice: VoiceInfo ->
                        VoiceRow(
                            label = voice.label,
                            selected = ttsVoiceId == voice.id,
                            onClick = { conversationViewModel.setTtsVoice(voice.id) }
                        )
                    }
                }
            }
        }
    }
}

/** A single full-width, tappable "radio + label" row used by [VoiceSheet]. */
@Composable
private fun VoiceRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .selectable(selected = selected, onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Spacer(Modifier.width(8.dp))
        Text(label)
    }
}

@Composable
private fun GeneratingIndicator() {
    Box(modifier = Modifier.fillMaxWidth()) {
        Card(
            modifier = Modifier.align(Alignment.CenterStart),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Text("Thinking…", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
