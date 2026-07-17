package com.daedalusapps.echo.data.backup

import androidx.work.ListenableWorker
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure JUnit tests for BackupWorker.mapBackupResult — no Robolectric/WorkManager needed.
 */
class BackupWorkerTest {

    @Test
    fun success_mapsToSuccess() {
        val result = BackupWorker.mapBackupResult(Result.success(Unit))
        assertEquals(ListenableWorker.Result.success(), result)
    }

    @Test
    fun noBackupFolderConfigured_mapsToFailure() {
        val result = BackupWorker.mapBackupResult(
            Result.failure(Exception("No backup folder configured"))
        )
        assertEquals(ListenableWorker.Result.failure(), result)
    }

    @Test
    fun backupFolderNotAccessible_mapsToFailure() {
        val result = BackupWorker.mapBackupResult(
            Result.failure(Exception("Backup folder is not accessible"))
        )
        assertEquals(ListenableWorker.Result.failure(), result)
    }

    @Test
    fun transientIoError_mapsToRetry() {
        val result = BackupWorker.mapBackupResult(
            Result.failure(Exception("Could not create backup file"))
        )
        assertEquals(ListenableWorker.Result.retry(), result)
    }

    @Test
    fun unknownErrorMessage_mapsToRetry() {
        val result = BackupWorker.mapBackupResult(
            Result.failure(Exception("Disk full"))
        )
        assertEquals(ListenableWorker.Result.retry(), result)
    }

    @Test
    fun nullMessage_mapsToRetry() {
        val result = BackupWorker.mapBackupResult(Result.failure(Exception()))
        assertEquals(ListenableWorker.Result.retry(), result)
    }
}
