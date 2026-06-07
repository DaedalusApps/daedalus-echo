package com.daedalus.voicenotes.ui.screens

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.daedalus.voicenotes.ai.DownloadState
import com.daedalus.voicenotes.ai.EMBEDDING_MODEL_FILE
import com.daedalus.voicenotes.ai.EMBEDDING_MODEL_SIZE_BYTES
import com.daedalus.voicenotes.ai.EMBEDDING_MODEL_URL
import com.daedalus.voicenotes.ai.GEMMA3_1B
import com.daedalus.voicenotes.ai.LocalModel
import com.daedalus.voicenotes.ai.ModelDownloader
import com.daedalus.voicenotes.ai.WHISPER_TOTAL_BYTES
import com.daedalus.voicenotes.ai.WhisperDownloader
import com.daedalus.voicenotes.ai.embeddingModelFile
import com.daedalus.voicenotes.ai.isWhisperReady
import com.daedalus.voicenotes.ui.components.DeviceStatusRow
import com.daedalus.voicenotes.viewmodel.DeviceViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    deviceViewModel: DeviceViewModel,
    onBack: () -> Unit,
    onNavigateToPromptEditor: () -> Unit = {}
) {
    val context  = LocalContext.current
    val prefs    = remember { context.getSharedPreferences("daedalus_prefs", Context.MODE_PRIVATE) }
    val scope    = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var autoProcess by remember { mutableStateOf(prefs.getBoolean("auto_process", false)) }

    val whisperDownloader = remember { WhisperDownloader(context) }
    val whisperState by whisperDownloader.state.collectAsState()
    val whisperReady = remember(whisperState) { isWhisperReady(context) }

    val embeddingModel = remember {
        LocalModel(
            id = "use_lite",
            displayName = "Universal Sentence Encoder Lite",
            description = "~26 MB · Semantic note search · On-device",
            downloadUrl = EMBEDDING_MODEL_URL,
            filename = EMBEDDING_MODEL_FILE,
            sizeBytes = EMBEDDING_MODEL_SIZE_BYTES
        )
    }
    val embeddingDownloader = remember { ModelDownloader(context) }
    val embeddingState by embeddingDownloader.state.collectAsState()
    val embeddingReady = remember(embeddingState) { embeddingModelFile(context).exists() }

    val bleState by deviceViewModel.bleManager.bleState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                    navigationIconContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        },
        snackbarHost = { SnackbarHost(snackbar) { Snackbar(it) } }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            DeviceStatusRow(
                bleState = bleState,
                onScan = { deviceViewModel.scan() },
                onCancelScan = { deviceViewModel.disconnect() }
            )

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                // AI model — single active model, no picker needed
                Text("AI Summarization Engine", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(GEMMA3_1B.displayName, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        Text(GEMMA3_1B.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("Active", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    }
                }

                // STT — Whisper only
                Text("Speech-to-Text Engine", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Card(colors = CardDefaults.cardColors(
                    containerColor = if (whisperReady) MaterialTheme.colorScheme.primaryContainer
                                     else MaterialTheme.colorScheme.surfaceVariant
                )) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Whisper base.en", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        Text("~160 MB · High accuracy · On-device", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        when {
                            whisperReady -> Text("Active", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                            whisperState is DownloadState.Downloading -> {
                                val dl = whisperState as DownloadState.Downloading
                                LinearProgressIndicator(progress = { dl.progressPct / 100f }, modifier = Modifier.fillMaxWidth())
                                Text("Downloading… ${dl.progressPct}%  ·  ${dl.bytesDownloaded / 1_048_576} / ${WHISPER_TOTAL_BYTES / 1_048_576} MB", style = MaterialTheme.typography.bodySmall)
                            }
                            whisperState is DownloadState.Failed -> {
                                Text("Error: ${(whisperState as DownloadState.Failed).message}", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                                OutlinedButton(onClick = { scope.launch { whisperDownloader.download() } }, modifier = Modifier.fillMaxWidth()) { Text("Retry") }
                            }
                            else -> Button(onClick = { scope.launch { whisperDownloader.download() } }, modifier = Modifier.fillMaxWidth()) {
                                Text("Download (~160 MB)")
                            }
                        }
                    }
                }

                // Embedding model (for semantic Ask Library)
                Text("Semantic Search Engine", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Card(colors = CardDefaults.cardColors(
                    containerColor = if (embeddingReady) MaterialTheme.colorScheme.primaryContainer
                                     else MaterialTheme.colorScheme.surfaceVariant
                )) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(embeddingModel.displayName, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        Text(embeddingModel.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        when {
                            embeddingReady -> Text("Active — Ask Library enabled", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                            embeddingState is DownloadState.Downloading -> {
                                val dl = embeddingState as DownloadState.Downloading
                                LinearProgressIndicator(progress = { dl.progressPct / 100f }, modifier = Modifier.fillMaxWidth())
                                Text("Downloading… ${dl.progressPct}%  ·  ${dl.bytesDownloaded / 1_048_576} / ${EMBEDDING_MODEL_SIZE_BYTES / 1_048_576} MB", style = MaterialTheme.typography.bodySmall)
                            }
                            embeddingState is DownloadState.Failed -> {
                                Text("Error: ${(embeddingState as DownloadState.Failed).message}", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                                OutlinedButton(onClick = { scope.launch { embeddingDownloader.download(embeddingModel) } }, modifier = Modifier.fillMaxWidth()) { Text("Retry") }
                            }
                            else -> Button(onClick = { scope.launch { embeddingDownloader.download(embeddingModel) } }, modifier = Modifier.fillMaxWidth()) {
                                Text("Download (~26 MB)")
                            }
                        }
                    }
                }

                // Management
                Text("Management", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Analysis Prompt", style = MaterialTheme.typography.bodyMedium)
                    Text("The prompt sent to Gemma for every analysis. You can view and customize it.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedButton(onClick = onNavigateToPromptEditor, modifier = Modifier.fillMaxWidth()) {
                        Text("Configure Prompt")
                    }
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Auto-analyze on sync", style = MaterialTheme.typography.bodyMedium)
                        Text("Analyze recordings automatically when synced via BLE", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = autoProcess, onCheckedChange = { autoProcess = it })
                }

                Spacer(Modifier.height(8.dp))

                Button(
                    onClick = {
                        prefs.edit().putBoolean("auto_process", autoProcess).apply()
                        scope.launch { snackbar.showSnackbar("Settings saved") }
                        onBack()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Save Settings") }

                val packageInfo = remember {
                    try { context.packageManager.getPackageInfo(context.packageName, 0) } catch (e: Exception) { null }
                }
                val versionName = packageInfo?.versionName ?: "Unknown"
                val versionCode = packageInfo?.let {
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) it.longVersionCode
                    else @Suppress("DEPRECATION") it.versionCode.toLong()
                } ?: 0L

                Box(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.Center) {
                    Text(
                        text = "Version $versionName (Build $versionCode)",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                }
            }
        }
    }
}
