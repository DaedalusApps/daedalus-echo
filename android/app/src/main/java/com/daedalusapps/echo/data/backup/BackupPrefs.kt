package com.daedalusapps.echo.data.backup

/**
 * Shared SharedPreferences keys, defaults, and failure-message strings for the
 * automatic backup feature. Referenced from BackupManager, BackupWorker,
 * MainActivity, and SettingsScreen so these literals live in exactly one place.
 */
object BackupPrefs {
    const val PREFS_NAME = "daedalus_prefs"

    const val FOLDER_URI = "backup_folder_uri"
    const val MAX_COUNT = "backup_max_count"
    const val INTERVAL_HOURS = "backup_interval_hours"
    const val LAST_BACKUP_TIME = "last_backup_time"
    const val LAST_BACKUP_ERROR = "last_backup_error"

    const val DEFAULT_MAX_COUNT = 7
    const val DEFAULT_INTERVAL_HOURS = 24L

    // Failure messages that indicate a permanent configuration problem rather than
    // a transient I/O error (see BackupWorker.mapBackupResult). Shared between the
    // producer (BackupManager.runAutoBackup) and the matcher (BackupWorker) so the
    // two strings can't drift apart.
    internal const val ERR_NO_FOLDER = "No backup folder configured"
    internal const val ERR_FOLDER_NOT_ACCESSIBLE = "Backup folder is not accessible"
}
