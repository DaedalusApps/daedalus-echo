package com.daedalus.echo.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import com.daedalus.echo.ai.DownloadState
import com.daedalus.echo.ai.EMBEDDING_MODEL_FILE
import com.daedalus.echo.ai.EMBEDDING_MODEL_SIZE_BYTES
import com.daedalus.echo.ai.EMBEDDING_MODEL_URL
import com.daedalus.echo.ai.GEMMA3_1B
import com.daedalus.echo.ai.LocalModel
import com.daedalus.echo.ai.ModelDownloader
import com.daedalus.echo.ai.WHISPER_TOTAL_BYTES
import com.daedalus.echo.ai.WhisperDownloader
import com.daedalus.echo.ai.embeddingModelFile
import com.daedalus.echo.ai.isWhisperReady
import com.daedalus.echo.viewmodel.RecordingViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    recordingViewModel: RecordingViewModel,
    onBack: () -> Unit,
    onNavigateToPromptEditor: () -> Unit = {}
) {
    val context  = LocalContext.current
    val prefs    = remember { context.getSharedPreferences("daedalus_prefs", Context.MODE_PRIVATE) }
    val scope    = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var autoProcess by remember { mutableStateOf(prefs.getBoolean("auto_process", true)) }

    val useBluetoothMic by recordingViewModel.useBluetoothMic.collectAsState()

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
                        Text("Auto-analyze on stop", style = MaterialTheme.typography.bodyMedium)
                        Text("Analyze recordings automatically when you stop recording", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = autoProcess, onCheckedChange = { autoProcess = it })
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Bluetooth mic compatibility", style = MaterialTheme.typography.bodyMedium)
                        Text("Record audio from connected Bluetooth headsets/microphones", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(
                        checked = useBluetoothMic,
                        onCheckedChange = { toggleBluetoothMic() }
                    )
                }

                // Privacy & Support
                Text("Privacy & Support", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "🔒 100% Private & Local-First",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "Daedalus Echo runs entirely on your device. Your voice recordings, transcripts, and AI summaries are processed locally and never leave your phone. No analytics, tracking, or cloud uploads.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f))
                        Text(
                            text = "Support Open Source",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "This application is free and open-source. If you find it valuable, consider sponsoring development to support privacy-first AI tools.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Button(
                            onClick = {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/DaedalusApps/daedalus-echo"))
                                context.startActivity(intent)
                            },
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                        ) {
                            Text("Sponsor Project")
                        }
                    }
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
