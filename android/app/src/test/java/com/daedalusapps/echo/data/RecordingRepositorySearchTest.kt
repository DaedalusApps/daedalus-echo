package com.daedalusapps.echo.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.daedalusapps.echo.data.db.AppDatabase
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
 * FTS4 full-text search index tests over [Recording] (RecordingDao.searchFtsFlow).
 * Covers tokenization, prefix matching, operator escaping, and Room sync triggers.
 */
@RunWith(RobolectricTestRunner::class)
class RecordingRepositorySearchTest {

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

    private suspend fun search(q: String): List<String> = repo.search(q).first().map { it.filename }

    @Test
    fun search_wholeWordHit_returnsMatchingRow() = runBlocking {
        repo.save(Recording(filename = "a.mp3", transcript = "We discussed the budget", createdAt = 1))

        assertEquals(listOf("a.mp3"), search("budget"))
    }

    @Test
    fun search_prefixHit_matchesLongerWordStartingWithQuery() = runBlocking {
        repo.save(Recording(filename = "a.mp3", transcript = "A new initiative for Q3", createdAt = 1))

        assertEquals(listOf("a.mp3"), search("init"))
    }

    @Test
    fun search_midWordFragment_noLongerMatches_pinnedBehaviourChange() = runBlocking {
        repo.save(Recording(filename = "a.mp3", transcript = "Consider an alternative approach", createdAt = 1))

        assertTrue(search("native").isEmpty())
    }

    @Test
    fun search_ordersByCreatedAtDescending() = runBlocking {
        repo.save(Recording(filename = "old.mp3", transcript = "budget one", createdAt = 1))
        repo.save(Recording(filename = "new.mp3", transcript = "budget two", createdAt = 2))

        assertEquals(listOf("new.mp3", "old.mp3"), search("budget"))
    }

    @Test
    fun search_queryWithDoubleQuote_doesNotThrowAndReturnsMatch() = runBlocking {
        repo.save(Recording(filename = "a.mp3", transcript = "budget review", createdAt = 1))

        assertEquals(listOf("a.mp3"), search("\"budget\""))
    }

    @Test
    fun search_queryWithAsteriskAndDash_doesNotThrow() = runBlocking {
        repo.save(Recording(filename = "a.mp3", transcript = "budget review", createdAt = 1))

        assertEquals(listOf("a.mp3"), search("budget*-"))
    }

    @Test
    fun search_punctuationOnlyQuery_doesNotThrowAndReturnsEmpty() = runBlocking {
        repo.save(Recording(filename = "a.mp3", transcript = "budget review", createdAt = 1))

        assertTrue(search("!!!---***").isEmpty())
    }

    @Test
    fun search_updatingTranscriptViaRawUpdate_removesOldTerms_findsNewTerms() = runBlocking {
        repo.save(Recording(filename = "a.mp3", transcript = "alpha term one", createdAt = 1))
        assertEquals(listOf("a.mp3"), search("alpha"))

        db.openHelper.writableDatabase.execSQL(
            "UPDATE recordings SET transcript = ? WHERE filename = ?",
            arrayOf("beta term two", "a.mp3")
        )

        assertTrue(search("alpha").isEmpty())
        assertEquals(listOf("a.mp3"), search("beta"))
    }

    @Test
    fun search_deletingRecording_removesFromIndex() = runBlocking {
        val recording = Recording(filename = "a.mp3", transcript = "delta term", createdAt = 1)
        repo.save(recording)
        assertEquals(listOf("a.mp3"), search("delta"))

        repo.delete(recording)

        assertTrue(search("delta").isEmpty())
    }

    @Test
    fun search_resavingExistingFilename_replacesIndexEntry() = runBlocking {
        repo.save(Recording(filename = "a.mp3", transcript = "epsilon old text", createdAt = 1))
        assertEquals(listOf("a.mp3"), search("epsilon"))

        repo.save(Recording(filename = "a.mp3", transcript = "zeta new text", createdAt = 1))

        assertTrue(search("epsilon").isEmpty())
        assertEquals(listOf("a.mp3"), search("zeta"))
    }
}
