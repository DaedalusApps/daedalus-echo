package com.daedalusapps.echo.ui.screens

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.daedalusapps.echo.ai.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class DownloadStep {
    IDLE,
    EMBEDDING,
    PARAKEET,
    DONE,
    FAILED
}

@Composable
fun ModelDownloadScreen(onReady: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences("daedalus_prefs", Context.MODE_PRIVATE) }

    var downloadStep by remember { mutableStateOf(DownloadStep.IDLE) }
    var progressPct by remember { mutableIntStateOf(0) }
    var statusText by remember { mutableStateOf("Ready to setup") }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val areReady = remember { areAllModelsReady(context) }

    // Start download worker
    suspend fun performSequentialDownload() {
        if (downloadStep == DownloadStep.EMBEDDING ||
            downloadStep == DownloadStep.PARAKEET) {
            return
        }
        errorMessage = null
        prefs.edit().putBoolean("download_started", true).apply()
        
        withContext(Dispatchers.IO) {
            val embeddingDownloader = ModelDownloader(context)
            val parakeetDownloader = ParakeetDownloader(context)

            // Step 1: Embedding Model (26 MB)
            val embeddingModel = LocalModel(
                id = "use_lite",
                displayName = "Universal Sentence Encoder Lite",
                description = "~26 MB · Semantic search",
                downloadUrl = EMBEDDING_MODEL_URL,
                filename = EMBEDDING_MODEL_FILE,
                sizeBytes = EMBEDDING_MODEL_SIZE_BYTES
            )
            if (!embeddingModelFile(context).exists()) {
                withContext(Dispatchers.Main) {
                    downloadStep = DownloadStep.EMBEDDING
                    progressPct = 0
                    statusText = "Downloading Embedding Model (1/2)…"
                }
                val job = launch {
                    embeddingDownloader.state.collect { state ->
                        if (state is DownloadState.Downloading) {
                            withContext(Dispatchers.Main) {
                                progressPct = state.progressPct
                                statusText = "Downloading Embedding Model (1/2): ${state.progressPct}%"
                            }
                        }
                    }
                }
                try {
                    embeddingDownloader.download(embeddingModel)
                    job.cancel()
                    val finalState = embeddingDownloader.state.value
                    if (finalState is DownloadState.Failed) {
                        withContext(Dispatchers.Main) {
                            downloadStep = DownloadStep.FAILED
                            errorMessage = "Embedding model failed: ${finalState.message}"
                        }
                        return@withContext
                    }
                } catch (e: Exception) {
                    job.cancel()
                    withContext(Dispatchers.Main) {
                        downloadStep = DownloadStep.FAILED
                        errorMessage = "Embedding model failed: ${e.message}"
                    }
                    return@withContext
                }
            }

            // Step 2: Parakeet TDT v3 (multilingual, Italian-first)
            if (!isParakeetReady(context)) {
                withContext(Dispatchers.Main) {
                    downloadStep = DownloadStep.PARAKEET
                    progressPct = 0
                    statusText = "Downloading Parakeet Italian model (2/2)…"
                }
                val job = launch {
                    parakeetDownloader.state.collect { state ->
                        if (state is DownloadState.Downloading) {
                            withContext(Dispatchers.Main) {
                                progressPct = state.progressPct
                                statusText = "Downloading Parakeet Italian model (2/2): ${state.progressPct}%"
                            }
                        } else if (state is DownloadState.Installing) {
                            withContext(Dispatchers.Main) {
                                progressPct = 100
                                statusText = "Installing Parakeet Italian model (2/2)…"
                            }
                        }
                    }
                }
                try {
                    parakeetDownloader.download()
                    job.cancel()
                    val finalState = parakeetDownloader.state.value
                    if (finalState is DownloadState.Failed) {
                        withContext(Dispatchers.Main) {
                            downloadStep = DownloadStep.FAILED
                            errorMessage = "Parakeet model failed: ${finalState.message}"
                        }
                        return@withContext
                    }
                } catch (e: Exception) {
                    job.cancel()
                    withContext(Dispatchers.Main) {
                        downloadStep = DownloadStep.FAILED
                        errorMessage = "Parakeet model failed: ${e.message}"
                    }
                    return@withContext
                }
            }

            // All Done!
            prefs.edit().putBoolean("download_started", false).apply()
            withContext(Dispatchers.Main) {
                downloadStep = DownloadStep.DONE
                onReady()
            }
        }
    }

    // Auto-resume download on landing if previously started and not finished
    LaunchedEffect(Unit) {
        if (areReady) {
            onReady()
        } else if (prefs.getBoolean("download_started", false)) {
            scope.launch {
                performSequentialDownload()
            }
        }
    }

    val packageInfo = remember {
        try { context.packageManager.getPackageInfo(context.packageName, 0) } catch (e: Exception) { null }
    }
    val versionName = packageInfo?.versionName ?: "Unknown"
    val versionCode = packageInfo?.let {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) it.longVersionCode
        else @Suppress("DEPRECATION") it.versionCode.toLong()
    } ?: 0L

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "Daedalus Echo",
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "AI Voice Recorder Companion",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(48.dp))

            when (downloadStep) {
                DownloadStep.DONE -> {
                    Text("All AI models ready!", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(20.dp))
                    Button(onClick = onReady, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                        Text("Continue")
                    }
                }

                DownloadStep.EMBEDDING, DownloadStep.PARAKEET -> {
                    Text(statusText, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(20.dp))
                    LinearProgressIndicator(progress = { progressPct / 100f }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    Text("$progressPct% completed", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(20.dp))
                    Text("Please keep the app open during download. This will only run once.", style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                DownloadStep.FAILED -> {
                    Text("Download failed", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(8.dp))
                    Text(errorMessage ?: "Unknown error", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(20.dp))
                    Button(onClick = { scope.launch { performSequentialDownload() } }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                        Text("Retry Download")
                    }
                }

                else -> { // IDLE or not started
                    Text("Required Offline AI Setup", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Daedalus processes transcription and summaries entirely on-device for 100% privacy. To begin, click below to download the required models:\n\n" +
                        "• Universal Sentence Encoder (~26 MB)\n" +
                        "• Parakeet TDT v3 multilingual (~1.25 GB)\n\n" +
                        "Then import Gemma 4 E2B from Settings to create local summaries. Parakeet recognizes Italian and other European languages locally; speaker identity is not inferred.",
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Start,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(28.dp))
                    Button(
                        onClick = { scope.launch { performSequentialDownload() } },
                        modifier = Modifier.fillMaxWidth().height(52.dp)
                    ) {
                        Text("Download required speech models")
                    }
                }
            }
        }

        // Version number
        Text(
            text = "v$versionName ($versionCode)",
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .navigationBarsPadding()
                .padding(16.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        )
    }
}
