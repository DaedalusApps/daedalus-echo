package com.daedalusapps.echo.ai

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.daedalusapps.echo.R

class AnalysisForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_STOP) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        val filename = intent?.getStringExtra(EXTRA_FILENAME) ?: "Recording"
        val statusText = intent?.getStringExtra(EXTRA_STATUS) ?: "Processing audio..."

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Daedalus Echo — Processing")
            .setContentText("$filename: $statusText")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        startForeground(NOTIFICATION_ID, notification)
        return START_NOT_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Audio Analysis & Sync",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows real-time progress during audio transcription and AI analysis"
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    companion object {
        const val CHANNEL_ID = "daedalus_processing_channel"
        const val NOTIFICATION_ID = 4201
        const val EXTRA_FILENAME = "extra_filename"
        const val EXTRA_STATUS = "extra_status"
        const val ACTION_START = "com.daedalusapps.echo.START_ANALYSIS_SERVICE"
        const val ACTION_STOP = "com.daedalusapps.echo.STOP_ANALYSIS_SERVICE"

        fun start(context: Context, filename: String, status: String) {
            try {
                val intent = Intent(context, AnalysisForegroundService::class.java).apply {
                    action = ACTION_START
                    putExtra(EXTRA_FILENAME, filename)
                    putExtra(EXTRA_STATUS, status)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (t: Throwable) {
                // Ignore framework/mock errors in unit test environments
            }
        }

        fun stop(context: Context) {
            try {
                val intent = Intent(context, AnalysisForegroundService::class.java).apply {
                    action = ACTION_STOP
                }
                context.startService(intent)
            } catch (t: Throwable) {
                // Ignore framework/mock errors in unit test environments
            }
        }
    }
}
