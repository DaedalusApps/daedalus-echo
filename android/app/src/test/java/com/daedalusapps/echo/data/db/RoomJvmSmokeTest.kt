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

/**
 * Wiring smoke test only: proves Room + Robolectric run as a JVM unit test
 * (no device). Real DAO behavior coverage lives in androidTest/RecordingDaoTest
 * and in the JVM DB tests added alongside new features — don't grow this file.
 */
@RunWith(RobolectricTestRunner::class)
class RoomJvmSmokeTest {

    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun insertAndReadBackRecording() = runBlocking {
        val recording = Recording(filename = "20260716120000.mp3")

        db.recordingDao().upsert(recording)
        val loaded = db.recordingDao().get(recording.filename)

        assertEquals(recording.filename, loaded?.filename)
    }
}
