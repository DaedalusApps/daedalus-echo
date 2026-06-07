package com.daedalus.voicenotes.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.daedalus.voicenotes.ai.*
import kotlinx.coroutines.launch

@Composable
fun ModelDownloadScreen(onReady: () -> Unit) {
    val context    = LocalContext.current
    val scope      = rememberCoroutineScope()
    val model      = selectedModel(context)
    val downloader = remember { ModelDownloader(context) }
    val state      by downloader.state.collectAsState()
    val alreadyDownloaded = remember { modelFile(context).exists() }

    val packageInfo = remember {
        try {
            context.packageManager.getPackageInfo(context.packageName, 0)
        } catch (e: Exception) {
            null
        }
    }
    val versionName = packageInfo?.versionName ?: "Unknown"
    val versionCode = packageInfo?.let {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            it.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            it.versionCode.toLong()
        }
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
                text = "Daedalus Voicenotes",
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

            when {
                alreadyDownloaded || state is DownloadState.Done -> {
                    Text(
                        text = "${model.displayName} ready",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(20.dp))
                    Button(
                        onClick = onReady,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                    ) { Text("Continue") }
                }

                state is DownloadState.Downloading -> {
                    val s = state as DownloadState.Downloading
                    Text("Downloading ${model.displayName}", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(20.dp))
                    LinearProgressIndicator(
                        progress = { s.progressPct / 100f },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "${s.progressPct}%  ·  ${s.bytesDownloaded / 1_048_576} MB / ${s.totalBytes / 1_048_576} MB",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(20.dp))
                    Text(
                        "Keep the app open. This only happens once.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                state is DownloadState.Failed -> {
                    val s = state as DownloadState.Failed
                    Text(
                        "Download failed",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        s.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(20.dp))
                    Button(
                        onClick = { scope.launch { downloader.download(model) } },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Retry") }
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = onReady) { Text("Skip (AI disabled)") }
                }

                else -> {
                    Text(
                        "One-time setup",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Download ${model.displayName} (~${model.sizeBytes / 1_000_000_000}GB) to enable AI summarization. Stored on-device, never sent to the cloud.",
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(28.dp))
                    Button(
                        onClick = { scope.launch { downloader.download(model) } },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                    ) { Text("Download AI Model") }
                    Spacer(Modifier.height(12.dp))
                    TextButton(onClick = onReady) { Text("Skip for now (AI disabled)") }
                }
            }
        }

        // Version number in bottom right
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

