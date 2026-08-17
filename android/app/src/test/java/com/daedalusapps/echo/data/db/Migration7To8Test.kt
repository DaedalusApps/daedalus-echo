package com.daedalusapps.echo.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class Migration7To8Test {

    private val testDbName = "migration-7-8-test"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java
    )

    @Test
    fun migrate7To8_createsFtsTableAndBackfillsRecordings() {
        helper.createDatabase(testDbName, 7).apply {
            execSQL(
                """
                INSERT INTO recordings
                (filename, localPath, sizeBytes, transcript, summary, mindMap, category,
                 createdAt, title, shortSummary, topics, durationMillis)
                VALUES
                ('20260816120000.mp3', '', 0, 'budget report transcript', 'quarterly summary', '', 1, 1000, '', '', '', 0)
                """.trimIndent()
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(testDbName, 8, true, AppDatabase.MIGRATION_7_8)

        // Existing recordings row survived
        db.query("SELECT filename, transcript, summary FROM recordings").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("20260816120000.mp3", cursor.getString(0))
            assertEquals("budget report transcript", cursor.getString(1))
            assertEquals("quarterly summary", cursor.getString(2))
        }

        // FTS virtual table exists and has backfilled row
        db.query("SELECT docid, filename, transcript, summary FROM recordings_fts").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("20260816120000.mp3", cursor.getString(1))
            assertEquals("budget report transcript", cursor.getString(2))
            assertEquals("quarterly summary", cursor.getString(3))
        }

        // FTS match query works on backfilled data
        db.query("SELECT recordings.filename FROM recordings JOIN recordings_fts ON recordings.rowid = recordings_fts.rowid WHERE recordings_fts MATCH 'budget*'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("20260816120000.mp3", cursor.getString(0))
        }

        db.close()
    }
}
