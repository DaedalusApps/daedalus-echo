package com.daedalusapps.echo.data.backup

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure JUnit tests for BackupManager.selectBackupsToDelete — no Android/Robolectric needed.
 */
class BackupRetentionTest {

    @Test
    fun underMax_returnsEmpty() {
        val names = listOf(
            "daedalus_backup_2026-01-01_0000.json",
            "daedalus_backup_2026-01-02_0000.json"
        )
        assertEquals(emptyList<String>(), BackupManager.selectBackupsToDelete(names, 5))
    }

    @Test
    fun exactlyMax_returnsEmpty() {
        val names = listOf(
            "daedalus_backup_2026-01-01_0000.json",
            "daedalus_backup_2026-01-02_0000.json",
            "daedalus_backup_2026-01-03_0000.json"
        )
        assertEquals(emptyList<String>(), BackupManager.selectBackupsToDelete(names, 3))
    }

    @Test
    fun overMax_returnsOldestFirstByLexicographicName() {
        val names = listOf(
            "daedalus_backup_2026-01-03_0000.json",
            "daedalus_backup_2026-01-01_0000.json",
            "daedalus_backup_2026-01-02_0000.json",
            "daedalus_backup_2026-01-04_0000.json"
        )
        // maxCount = 2 -> keep 2 newest, delete 2 oldest
        val result = BackupManager.selectBackupsToDelete(names, 2)
        assertEquals(
            listOf(
                "daedalus_backup_2026-01-01_0000.json",
                "daedalus_backup_2026-01-02_0000.json"
            ),
            result
        )
    }

    @Test
    fun nonMatchingFilenames_areIgnoredEntirely() {
        val names = listOf(
            "daedalus_backup_2026-01-01_0000.json",
            "daedalus_backup_2026-01-02_0000.json",
            "some_other_file.txt",
            "daedalus_backup_2026-01-03_0000.json",
            "notes.json"
        )
        // Only the 3 matching files count toward maxCount; the other 2 are never touched.
        val result = BackupManager.selectBackupsToDelete(names, 2)
        assertEquals(listOf("daedalus_backup_2026-01-01_0000.json"), result)
    }

    @Test
    fun maxCountZeroOrNegative_coercedToKeepAtLeastOne() {
        val names = listOf(
            "daedalus_backup_2026-01-01_0000.json",
            "daedalus_backup_2026-01-02_0000.json",
            "daedalus_backup_2026-01-03_0000.json"
        )
        // maxCount <= 0 is coerced to 1 -> keep newest 1, delete the rest.
        val resultZero = BackupManager.selectBackupsToDelete(names, 0)
        assertEquals(
            listOf(
                "daedalus_backup_2026-01-01_0000.json",
                "daedalus_backup_2026-01-02_0000.json"
            ),
            resultZero
        )

        val resultNegative = BackupManager.selectBackupsToDelete(names, -5)
        assertEquals(
            listOf(
                "daedalus_backup_2026-01-01_0000.json",
                "daedalus_backup_2026-01-02_0000.json"
            ),
            resultNegative
        )
    }

    @Test
    fun emptyList_returnsEmpty() {
        assertEquals(emptyList<String>(), BackupManager.selectBackupsToDelete(emptyList(), 5))
    }
}
