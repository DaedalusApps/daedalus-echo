package com.daedalusapps.echo.data.backup

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Periodic WorkManager job that runs [BackupManager.runAutoBackup] in the background.
 */
class BackupWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val result = BackupManager(applicationContext).runAutoBackup()
        return mapBackupResult(result)
    }

    companion object {
        const val UNIQUE_WORK_NAME = "auto_backup"

        // Failure messages from BackupManager.runAutoBackup that indicate a permanent
        // configuration problem (no backup folder set, or the granted folder/permission
        // is gone) rather than a transient I/O error. Retrying these would just fail again,
        // so they map to Result.failure(); everything else is treated as transient and retried.
        private val PERMANENT_FAILURE_MESSAGES = listOf(
            BackupPrefs.ERR_NO_FOLDER,
            BackupPrefs.ERR_FOLDER_NOT_ACCESSIBLE
        )

        internal fun mapBackupResult(result: kotlin.Result<Unit>): ListenableWorker.Result {
            if (result.isSuccess) return ListenableWorker.Result.success()

            val message = result.exceptionOrNull()?.message ?: ""
            return if (PERMANENT_FAILURE_MESSAGES.any { message.contains(it) }) {
                ListenableWorker.Result.failure()
            } else {
                ListenableWorker.Result.retry()
            }
        }

        fun schedule(context: Context, intervalHours: Long) {
            val request = PeriodicWorkRequestBuilder<BackupWorker>(intervalHours, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder().setRequiresBatteryNotLow(true).build()
                )
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK_NAME)
        }
    }
}
