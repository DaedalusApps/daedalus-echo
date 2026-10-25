package com.daedalusapps.echo.recording

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.daedalusapps.echo.R

/**
 * Keeps microphone capture alive while the display is off or the app is no longer
 * foregrounded. Its ongoing notification provides pause/resume and stop controls
 * that remain available on the lock screen when notifications are allowed.
 */
class RecordingForegroundService : Service() {
    private var filename = "Recording"

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Audio recording",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps an active recording running in the background"
                setShowBadge(false)
            }
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        filename = intent?.getStringExtra(EXTRA_FILENAME) ?: filename
        when (intent?.action) {
            ACTION_PAUSE -> {
                RecordingControlBus.send(RecordingControlBus.Command.PAUSE)
                showNotification(isPaused = true)
            }
            ACTION_RESUME -> {
                RecordingControlBus.send(RecordingControlBus.Command.RESUME)
                showNotification(isPaused = false)
            }
            ACTION_STOP_CONTROL -> {
                // The ViewModel finalizes the MediaRecorder, then asks us to finish.
                // Keeping this service alive for that brief hand-off avoids a background
                // service restart race on recent Android versions.
                RecordingControlBus.send(RecordingControlBus.Command.STOP)
            }
            ACTION_FINISH -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            else -> showNotification(isPaused = false)
        }
        return START_NOT_STICKY
    }

    private fun showNotification(isPaused: Boolean) {
        val primaryAction = if (isPaused) ACTION_RESUME else ACTION_PAUSE
        val primaryLabel = if (isPaused) "Resume" else "Pause"
        val primaryIcon = if (isPaused) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(if (isPaused) "Daedalus Echo — Paused" else "Daedalus Echo — Recording")
            .setContentText(filename)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            // Samsung's lock screen otherwise may reduce this to an icon with no controls.
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(
                NotificationCompat.Action.Builder(
                    primaryIcon,
                    primaryLabel,
                    servicePendingIntent(primaryAction, REQUEST_PRIMARY)
                ).build()
            )
            .addAction(
                NotificationCompat.Action.Builder(
                    android.R.drawable.ic_menu_close_clear_cancel,
                    "Stop",
                    servicePendingIntent(ACTION_STOP_CONTROL, REQUEST_STOP)
                ).build()
            )
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun servicePendingIntent(action: String, requestCode: Int): PendingIntent =
        PendingIntent.getService(
            this,
            requestCode,
            Intent(this, RecordingForegroundService::class.java)
                .setAction(action)
                .putExtra(EXTRA_FILENAME, filename),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    companion object {
        private const val CHANNEL_ID = "daedalus_recording_channel"
        private const val NOTIFICATION_ID = 4202
        private const val EXTRA_FILENAME = "extra_filename"
        private const val ACTION_PAUSE = "com.daedalusapps.echo.PAUSE_RECORDING_SERVICE"
        private const val ACTION_RESUME = "com.daedalusapps.echo.RESUME_RECORDING_SERVICE"
        private const val ACTION_STOP_CONTROL = "com.daedalusapps.echo.STOP_RECORDING_CONTROL"
        private const val ACTION_FINISH = "com.daedalusapps.echo.FINISH_RECORDING_SERVICE"
        private const val REQUEST_PRIMARY = 4203
        private const val REQUEST_STOP = 4204

        fun start(context: Context, filename: String) {
            val intent = Intent(context, RecordingForegroundService::class.java)
                .putExtra(EXTRA_FILENAME, filename)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, RecordingForegroundService::class.java).apply {
                    action = ACTION_FINISH
                }
            )
        }
    }
}
