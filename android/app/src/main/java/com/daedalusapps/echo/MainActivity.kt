package com.daedalusapps.echo

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.navigation.compose.rememberNavController
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import com.daedalusapps.echo.data.backup.BackupPrefs
import com.daedalusapps.echo.data.backup.BackupWorker
import com.daedalusapps.echo.ui.NavGraph
import com.daedalusapps.echo.ui.theme.DaedalusTheme
import com.daedalusapps.echo.util.CalendarIntegration
import com.daedalusapps.echo.util.SafeFilename
import com.daedalusapps.echo.viewmodel.ConversationViewModel
import com.daedalusapps.echo.viewmodel.RecordingViewModel
import com.daedalusapps.echo.viewmodel.TodoViewModel

class MainActivity : ComponentActivity() {

    private val recordingViewModel: RecordingViewModel by viewModels()
    private val todoViewModel: TodoViewModel by viewModels()
    private val conversationViewModel: ConversationViewModel by viewModels()

    private val adbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null || !intent.getBooleanExtra("_forwarded", false)) return
            when (intent.action) {
                AdbActions.ANALYZE -> {
                    val filename = intent.getStringExtra("filename") ?: ""
                    Log.i("DaedalusADB", "Analyze triggered for '$filename'")
                    if (filename.isNotBlank() && SafeFilename.isSafe(filename)) {
                        lifecycleScope.launch { recordingViewModel.analyze(filename) }
                    } else if (filename.isNotBlank()) {
                        Log.i("DaedalusADB", "Rejected analyze for unsafe filename '$filename'")
                    }
                }
                AdbActions.START_RECORDING -> {
                    Log.i("DaedalusADB", "Start recording triggered")
                    lifecycleScope.launch { recordingViewModel.startLocalRecording() }
                }
                AdbActions.STOP_RECORDING -> {
                    Log.i("DaedalusADB", "Stop recording triggered")
                    lifecycleScope.launch { recordingViewModel.stopLocalRecording() }
                }
                AdbActions.FORMAT_PARAGRAPHS -> {
                    val filename = intent.getStringExtra("filename") ?: ""
                    Log.i("DaedalusADB", "Format paragraphs triggered for '$filename'")
                    if (filename.isNotBlank() && SafeFilename.isSafe(filename)) {
                        lifecycleScope.launch {
                            val formatted = recordingViewModel.formatParagraphsPreview(filename)
                            Log.i("DaedalusADB", "Paragraph format result for '$filename': ${formatted ?: "no transcript"}")
                        }
                    } else if (filename.isNotBlank()) {
                        Log.i("DaedalusADB", "Rejected format paragraphs for unsafe filename '$filename'")
                    }
                }
                AdbActions.SEARCH_FTS -> {
                    val query = intent.getStringExtra("query") ?: ""
                    Log.i("DaedalusADB", "Search triggered for '$query'")
                    if (query.isNotBlank()) {
                        lifecycleScope.launch {
                            val results = recordingViewModel.searchPreview(query)
                            Log.i("DaedalusADB", "Search result for '$query': ${results.size} match(es) -> $results")
                        }
                    }
                }
                AdbActions.ADD_CALENDAR -> {
                    val title = intent.getStringExtra("title") ?: "Action Item"
                    val note = intent.getStringExtra("note") ?: ""
                    Log.i("DaedalusADB", "Add to calendar triggered for '$title'")
                    CalendarIntegration.addToCalendar(this@MainActivity, title, note)
                }
            }
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* permissions handled */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestRequiredPermissions()

        if (BuildConfig.DEBUG) {
            val filter = IntentFilter().apply {
                AdbActions.REGISTERED.forEach { addAction(it) }
            }
            ContextCompat.registerReceiver(this, adbReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        }

        val prefs = getSharedPreferences(BackupPrefs.PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.getString(BackupPrefs.FOLDER_URI, null).isNullOrBlank()) {
            BackupWorker.schedule(this, prefs.getLong(BackupPrefs.INTERVAL_HOURS, BackupPrefs.DEFAULT_INTERVAL_HOURS))
        }

        setContent {
            DaedalusTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val navController = rememberNavController()
                    NavGraph(
                        navController = navController,
                        recordingViewModel = recordingViewModel,
                        todoViewModel = todoViewModel,
                        conversationViewModel = conversationViewModel
                    )
                }
            }
        }
    }

    private fun requestRequiredPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.MODIFY_AUDIO_SETTINGS
        )
        permissionLauncher.launch(permissions.toTypedArray())
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(adbReceiver)
        } catch (e: Exception) {
            // ignore
        }
    }
}
