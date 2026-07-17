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
import com.daedalusapps.echo.viewmodel.RecordingViewModel

class MainActivity : ComponentActivity() {

    private val recordingViewModel: RecordingViewModel by viewModels()

    private val adbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "com.daedalusapps.echo.ANALYZE") {
                val filename = intent.getStringExtra("filename") ?: ""
                Log.i("DaedalusADB", "Analyze triggered for '$filename'")
                if (filename.isNotBlank()) {
                    lifecycleScope.launch { recordingViewModel.analyze(filename) }
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
                addAction("com.daedalusapps.echo.ANALYZE")
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
                        recordingViewModel = recordingViewModel
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
