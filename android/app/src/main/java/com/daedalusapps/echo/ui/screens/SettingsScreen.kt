package com.daedalusapps.echo.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import com.daedalusapps.echo.ai.DownloadState
import com.daedalusapps.echo.ai.EMBEDDING_MODEL_FILE
import com.daedalusapps.echo.ai.EMBEDDING_MODEL_SIZE_BYTES
import com.daedalusapps.echo.ai.EMBEDDING_MODEL_URL
import com.daedalusapps.echo.ai.GEMMA3_1B
import com.daedalusapps.echo.ai.LocalModel
import com.daedalusapps.echo.ai.ModelDownloader
import com.daedalusapps.echo.ai.WHISPER_TOTAL_BYTES
import com.daedalusapps.echo.ai.WhisperDownloader
import com.daedalusapps.echo.ai.embeddingModelFile
import com.daedalusapps.echo.ai.isWhisperReady
import com.daedalusapps.echo.data.backup.BackupManager
import com.daedalusapps.echo.data.backup.BackupPrefs
import com.daedalusapps.echo.data.backup.BackupWorker
import com.daedalusapps.echo.viewmodel.MAX_RECORDING_MINUTES_DEFAULT
import com.daedalusapps.echo.viewmodel.MAX_RECORDING_MINUTES_KEY
import com.daedalusapps.echo.viewmodel.MAX_RECORDING_MINUTES_UNLIMITED
import com.daedalusapps.echo.viewmodel.RecordingViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val BACKUP_INTERVAL_OPTIONS = listOf(
    12L to "Every 12 hours",
    24L to "Daily",
    72L to "Every 3 days",
    168L to "Weekly"
)

private fun backupIntervalLabel(hours: Long): String =
    BACKUP_INTERVAL_OPTIONS.firstOrNull { it.first == hours }?.second ?: "Every $hours hours"

private fun todoLookbackLabel(hours: Long): String =
    LOOKBACK_OPTIONS.firstOrNull { it.hours == hours }?.label ?: "Last $hours hours"

private val MAX_RECORDING_DURATION_OPTIONS = listOf(
    30 to "30 minutes",
    60 to "1 hour",
    120 to "2 hours",
    240 to "4 hours",
    MAX_RECORDING_MINUTES_UNLIMITED to "Unlimited"
)

private fun maxRecordingDurationLabel(minutes: Int): String =
    MAX_RECORDING_DURATION_OPTIONS.firstOrNull { it.first == minutes }?.second ?: "$minutes minutes"

private fun formatLastBackupTime(millis: Long): String {
    if (millis <= 0L) return "never"
    return java.text.SimpleDateFormat("MMM d, yyyy h:mm a", java.util.Locale.getDefault()).format(java.util.Date(millis))
}

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

    var backupFolderUri by remember { mutableStateOf(prefs.getString(BackupPrefs.FOLDER_URI, null)) }
    var backupIntervalHours by remember { mutableStateOf(prefs.getLong(BackupPrefs.INTERVAL_HOURS, BackupPrefs.DEFAULT_INTERVAL_HOURS)) }
    var backupMaxCountText by remember { mutableStateOf(prefs.getInt(BackupPrefs.MAX_COUNT, BackupPrefs.DEFAULT_MAX_COUNT).toString()) }
    var lastBackupTime by remember { mutableStateOf(prefs.getLong(BackupPrefs.LAST_BACKUP_TIME, 0L)) }
    var lastBackupError by remember { mutableStateOf(prefs.getString(BackupPrefs.LAST_BACKUP_ERROR, null)) }
    var backupIntervalMenuExpanded by remember { mutableStateOf(false) }
    var todoLookbackHours by remember { mutableStateOf(prefs.getLong(TODO_LOOKBACK_HOURS_KEY, TODO_LOOKBACK_HOURS_DEFAULT)) }
    var todoLookbackMenuExpanded by remember { mutableStateOf(false) }
    var maxRecordingMinutes by remember { mutableStateOf(prefs.getInt(MAX_RECORDING_MINUTES_KEY, MAX_RECORDING_MINUTES_DEFAULT)) }
    var maxRecordingMenuExpanded by remember { mutableStateOf(false) }
    var isBackingUp by remember { mutableStateOf(false) }
    val backupFolderName = remember(backupFolderUri) {
        backupFolderUri?.let { uriStr ->
            try {
                val uri = Uri.parse(uriStr)
                DocumentFile.fromTreeUri(context, uri)?.name ?: Uri.decode(uri.lastPathSegment)
            } catch (e: Exception) {
                null
            }
        }
    }

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

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json"),
        onResult = { uri ->
            if (uri != null) {
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) { BackupManager(context).exportToUri(uri) }
                        snackbar.showSnackbar("Backup exported successfully")
                    } catch (e: Exception) {
                        snackbar.showSnackbar("Export failed: ${e.message}")
                    }
                }
            }
        }
    )

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
        onResult = { uri ->
            if (uri != null) {
                scope.launch {
                    try {
                        val count = withContext(Dispatchers.IO) { BackupManager(context).importFromUri(uri) }
                        snackbar.showSnackbar("Imported $count recordings successfully")
                    } catch (e: Exception) {
                        snackbar.showSnackbar("Import failed: ${e.message}")
                    }
                }
            }
        }
    )

    val chooseBackupFolderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
        onResult = { uri ->
            if (uri != null) {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
                prefs.edit().putString(BackupPrefs.FOLDER_URI, uri.toString()).apply()
                backupFolderUri = uri.toString()
                BackupWorker.schedule(context, backupIntervalHours)
            }
        }
    )

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

                // Local Recording
                Text("Local Recording", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                ExposedDropdownMenuBox(
                    expanded = maxRecordingMenuExpanded,
                    onExpandedChange = { maxRecordingMenuExpanded = it }
                ) {
                    OutlinedTextField(
                        readOnly = true,
                        value = maxRecordingDurationLabel(maxRecordingMinutes),
                        onValueChange = {},
                        label = { Text("Max recording duration") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = maxRecordingMenuExpanded) },
                        modifier = Modifier
                            .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                            .fillMaxWidth()
                    )
                    ExposedDropdownMenu(
                        expanded = maxRecordingMenuExpanded,
                        onDismissRequest = { maxRecordingMenuExpanded = false }
                    ) {
                        MAX_RECORDING_DURATION_OPTIONS.forEach { (minutes, label) ->
                            DropdownMenuItem(
                                text = { Text(label) },
                                onClick = {
                                    maxRecordingMinutes = minutes
                                    prefs.edit().putInt(MAX_RECORDING_MINUTES_KEY, minutes).apply()
                                    maxRecordingMenuExpanded = false
                                }
                            )
                        }
                    }
                }

                // Todo List
                Text("Todo List", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                ExposedDropdownMenuBox(
                    expanded = todoLookbackMenuExpanded,
                    onExpandedChange = { todoLookbackMenuExpanded = it }
                ) {
                    OutlinedTextField(
                        readOnly = true,
                        value = todoLookbackLabel(todoLookbackHours),
                        onValueChange = {},
                        label = { Text("Default AI lookback") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = todoLookbackMenuExpanded) },
                        modifier = Modifier
                            .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                            .fillMaxWidth()
                    )
                    ExposedDropdownMenu(
                        expanded = todoLookbackMenuExpanded,
                        onDismissRequest = { todoLookbackMenuExpanded = false }
                    ) {
                        LOOKBACK_OPTIONS.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(option.label) },
                                onClick = {
                                    todoLookbackHours = option.hours
                                    prefs.edit().putLong(TODO_LOOKBACK_HOURS_KEY, option.hours).apply()
                                    todoLookbackMenuExpanded = false
                                }
                            )
                        }
                    }
                }

                // Backup & Recovery
                Text("Backup & Recovery", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)

                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Export or import transcripts and summary metadata as a single JSON file.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { exportLauncher.launch("daedalus_backup.json") },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Export Backup")
                        }

                        Button(
                            onClick = { importLauncher.launch(arrayOf("application/json")) },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Import Backup")
                        }
                    }

                    HorizontalDivider()

                    Text("Automatic Backups", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Backup folder", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                backupFolderName ?: "Not set",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        OutlinedButton(onClick = { chooseBackupFolderLauncher.launch(null) }) {
                            Text("Choose Backup Folder")
                        }
                    }

                    ExposedDropdownMenuBox(
                        expanded = backupIntervalMenuExpanded,
                        onExpandedChange = { backupIntervalMenuExpanded = it }
                    ) {
                        OutlinedTextField(
                            readOnly = true,
                            value = backupIntervalLabel(backupIntervalHours),
                            onValueChange = {},
                            label = { Text("Backup interval (approximately)") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = backupIntervalMenuExpanded) },
                            modifier = Modifier
                                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                                .fillMaxWidth()
                        )
                        ExposedDropdownMenu(
                            expanded = backupIntervalMenuExpanded,
                            onDismissRequest = { backupIntervalMenuExpanded = false }
                        ) {
                            BACKUP_INTERVAL_OPTIONS.forEach { (hours, label) ->
                                DropdownMenuItem(
                                    text = { Text(label) },
                                    onClick = {
                                        backupIntervalHours = hours
                                        prefs.edit().putLong(BackupPrefs.INTERVAL_HOURS, hours).apply()
                                        if (backupFolderUri != null) {
                                            BackupWorker.schedule(context, hours)
                                        }
                                        backupIntervalMenuExpanded = false
                                    }
                                )
                            }
                        }
                    }

                    OutlinedTextField(
                        value = backupMaxCountText,
                        onValueChange = { text ->
                            backupMaxCountText = text
                            val n = text.toIntOrNull()
                            if (n != null && n in 1..100) {
                                prefs.edit().putInt(BackupPrefs.MAX_COUNT, n).apply()
                            }
                        },
                        label = { Text("Max backups to keep") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Button(
                        onClick = {
                            isBackingUp = true
                            scope.launch {
                                val result = withContext(Dispatchers.IO) {
                                    BackupManager(context).runAutoBackup()
                                }
                                lastBackupTime = prefs.getLong(BackupPrefs.LAST_BACKUP_TIME, lastBackupTime)
                                lastBackupError = prefs.getString(BackupPrefs.LAST_BACKUP_ERROR, null)
                                isBackingUp = false
                                if (result.isSuccess) {
                                    snackbar.showSnackbar("Backup completed successfully")
                                } else {
                                    snackbar.showSnackbar("Backup failed: ${result.exceptionOrNull()?.message}")
                                }
                            }
                        },
                        enabled = backupFolderUri != null && !isBackingUp,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Back Up Now")
                    }

                    Text(
                        "Last backup: ${formatLastBackupTime(lastBackupTime)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    lastBackupError?.let { error ->
                        Text(
                            error,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
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
