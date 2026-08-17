package com.daedalusapps.echo.data.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.daedalusapps.echo.data.RecordingRepository
import com.daedalusapps.echo.data.model.Recording
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * RecordingDao.upsert compiled to INSERT OR REPLACE, and `recordings.filename` is a TEXT
 * PRIMARY KEY (not the rowid), so REPLACE deletes-then-reinserts on conflict, handing the row a
 * new rowid on every save.
 *
 * @Upsert performs a real UPDATE in place on conflict: rowid stays stable and the write is
 * roughly half the cost (no delete+reinsert of the base row).
 */
@RunWith(RobolectricTestRunner::class)
class RecordingUpsertRowidTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: RecordingRepository

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
        repo = RecordingRepository(db.recordingDao())
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun rowidOf(filename: String): Long {
        db.openHelper.writableDatabase.query(
            "SELECT rowid FROM recordings WHERE filename = ?",
            arrayOf<Any>(filename)
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            return cursor.getLong(0)
        }
    }

    @Test
    fun upsert_reSaveSameFilename_preservesRowid() = runBlocking {
        repo.save(Recording(filename = "a.mp3", transcript = "old text", createdAt = 1))
        val rowidBefore = rowidOf("a.mp3")

        repo.save(Recording(filename = "a.mp3", transcript = "new text", createdAt = 1))
        val rowidAfter = rowidOf("a.mp3")

        assertEquals(rowidBefore, rowidAfter)
    }

    @Test
    fun upsert_noExistingRow_insertsAndIsSearchable() = runBlocking {
        repo.save(Recording(filename = "new.mp3", transcript = "brand new content", createdAt = 1))

        val result = repo.get("new.mp3")
        assertEquals("brand new content", result?.transcript)
        assertEquals(listOf("new.mp3"), repo.search("brand").first().map { it.filename })
    }

    @Test
    fun upsert_reSave_updatesFields() = runBlocking {
        repo.save(Recording(filename = "a.mp3", transcript = "old", summary = "old summary", createdAt = 1))

        repo.save(Recording(filename = "a.mp3", transcript = "new", summary = "new summary", createdAt = 1))

        val result = repo.get("a.mp3")
        assertEquals("new", result?.transcript)
        assertEquals("new summary", result?.summary)
    }

    @Test
    fun upsert_reSaveWithNewTranscript_findableByNewTranscriptOnly() = runBlocking {
        repo.save(Recording(filename = "a.mp3", transcript = "alpha term", createdAt = 1))
        assertEquals(listOf("a.mp3"), repo.search("alpha").first().map { it.filename })

        repo.save(Recording(filename = "a.mp3", transcript = "beta term", createdAt = 1))

        assertTrue(repo.search("alpha").first().isEmpty())
        assertEquals(listOf("a.mp3"), repo.search("beta").first().map { it.filename })
    }
}
