package com.daedalus.voicenotes

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
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.Lifecycle
import com.daedalus.voicenotes.ble.ConnectionState
import kotlinx.coroutines.launch
import com.daedalus.voicenotes.ui.NavGraph
import com.daedalus.voicenotes.ui.theme.DaedalusTheme
import com.daedalus.voicenotes.viewmodel.DeviceViewModel
import com.daedalus.voicenotes.viewmodel.RecordingViewModel

class MainActivity : ComponentActivity() {

    private val deviceViewModel: DeviceViewModel by viewModels()
    private val recordingViewModel: RecordingViewModel by viewModels()

    private val adbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                "com.daedalus.voicenotes.SYNC" -> {
                    Log.i("DaedalusADB", "ADB BLE sync triggered")
                    recordingViewModel.syncAllBleFiles(deviceViewModel.bleManager)
                }
                "com.daedalus.voicenotes.PROBE" -> {
                    Log.i("DaedalusADB", "BLE probe triggered")
                    lifecycleScope.launch {
                        deviceViewModel.bleManager.runProbe()
                    }
                }
                "com.daedalus.voicenotes.PROBE2" -> {
                    Log.i("DaedalusADB", "Service probe triggered")
                    lifecycleScope.launch {
                        deviceViewModel.bleManager.runServiceProbe()
                    }
                }
                "com.daedalus.voicenotes.PROBE_DELETE" -> {
                    val filename = intent?.getStringExtra("filename") ?: ""
                    Log.i("DaedalusADB", "Delete probe triggered for '$filename'")
                    if (filename.isNotBlank()) {
                        lifecycleScope.launch {
                            deviceViewModel.bleManager.probeDeleteCmds(filename)
                        }
                    }
                }
                "com.daedalus.voicenotes.ANALYZE" -> {
                    val filename = intent?.getStringExtra("filename") ?: ""
                    Log.i("DaedalusADB", "Analyze triggered for '$filename'")
                    if (filename.isNotBlank()) {
                        lifecycleScope.launch { recordingViewModel.analyze(filename) }
                    }
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
                addAction("com.daedalus.voicenotes.SYNC")
                addAction("com.daedalus.voicenotes.PROBE")
                addAction("com.daedalus.voicenotes.PROBE2")
                addAction("com.daedalus.voicenotes.PROBE_DELETE")
                addAction("com.daedalus.voicenotes.ANALYZE")
            }
            ContextCompat.registerReceiver(this, adbReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        }

        // Auto-sync on first BLE connect. lastState lives outside repeatOnLifecycle so it
        // survives stop/start cycles and doesn't re-trigger sync on every app resume.
        var lastState = ConnectionState.DISCONNECTED
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                deviceViewModel.state.collect { bleState ->
                    if (bleState.connectionState == ConnectionState.CONNECTED &&
                        lastState != ConnectionState.CONNECTED) {
                        Log.i("DaedalusADB", "BLE connected — auto-syncing")
                        recordingViewModel.syncAllBleFiles(deviceViewModel.bleManager)
                    }
                    lastState = bleState.connectionState
                }
            }
        }

        setContent {
            DaedalusTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val navController = rememberNavController()
                    NavGraph(
                        navController = navController,
                        deviceViewModel = deviceViewModel,
                        recordingViewModel = recordingViewModel
                    )
                }
            }
        }
    }

    private fun requestRequiredPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions += listOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
            )
        }
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
