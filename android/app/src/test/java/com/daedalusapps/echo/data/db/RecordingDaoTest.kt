package com.daedalusapps.echo.data.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.daedalusapps.echo.data.model.Recording
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RecordingDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: RecordingDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
        dao = db.recordingDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun getSince_returnsOnlyFreshRows() = runBlocking {
        val cutoff = 1_000L
        dao.upsert(Recording(filename = "before.mp3", createdAt = cutoff - 1))
        dao.upsert(Recording(filename = "at.mp3", createdAt = cutoff))
        dao.upsert(Recording(filename = "after.mp3", createdAt = cutoff + 1))

        val result = dao.getSince(cutoff)

        // >= cutoff is inclusive; newest first
        assertEquals(listOf("after.mp3", "at.mp3"), result.map { it.filename })
    }
}
