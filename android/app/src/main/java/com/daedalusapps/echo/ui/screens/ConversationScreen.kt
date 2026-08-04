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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import com.daedalusapps.echo.viewmodel.ChatMessage
import com.daedalusapps.echo.viewmodel.ConversationViewModel

/**
 * Minimal text-chat surface for conversation mode (#20 / EB.3), plus an End session action that
 * saves and analyzes the transcript (#22 / EB.5), an inline Stop for the in-flight send/end,
 * push-to-talk voice input (#23 / EC.1), and a spoken-replies toggle (#24 / EC.2). The voice/rate
 * pickers and instant-send are added in later issues.
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

    val context = LocalContext.current
    var input by remember { mutableStateOf("") }
    val snackbar = remember { SnackbarHostState() }
    val listState = rememberLazyListState()

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
                items(messages) { message ->
                    ChatBubble(message)
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

@Composable
private fun ChatBubble(message: ChatMessage) {
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
        }
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
