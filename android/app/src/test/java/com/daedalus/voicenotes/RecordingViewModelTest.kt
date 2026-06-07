package com.daedalus.voicenotes

import android.app.Application
import android.util.Log
import com.daedalus.voicenotes.data.RecordingRepository
import com.daedalus.voicenotes.data.db.AppDatabase
import com.daedalus.voicenotes.ai.EmbeddingService
import com.daedalus.voicenotes.ai.LocalLlmService
import com.daedalus.voicenotes.ai.TranscriptionService
import com.daedalus.voicenotes.data.model.Recording
import com.daedalus.voicenotes.viewmodel.RecordingViewModel
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import androidx.arch.core.executor.testing.InstantTaskExecutorRule

@OptIn(ExperimentalCoroutinesApi::class)
class RecordingViewModelTest {

    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    private val application = mockk<Application>(relaxed = true)
    private val repo = mockk<RecordingRepository>(relaxed = true)
    private val embedder = mockk<EmbeddingService>(relaxed = true)
    private val llm = mockk<LocalLlmService>(relaxed = true)
    
    private lateinit var viewModel: RecordingViewModel
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0
        
        every { repo.allRecordings } returns flowOf(emptyList())
        
        val audioManager = mockk<android.media.AudioManager>(relaxed = true)
        every { application.getSystemService(android.content.Context.AUDIO_SERVICE) } returns audioManager
        every { application.contentResolver } returns mockk(relaxed = true)
        
        val db = mockk<AppDatabase>(relaxed = true)
        val transcriber = mockk<TranscriptionService>(relaxed = true)
        
        viewModel = RecordingViewModel(
            application = application,
            db = db,
            repo = repo,
            llm = llm,
            transcriber = transcriber,
            embedder = embedder
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkStatic(Log::class)
    }

    @Test
    fun askLibraryQuestion_updatesLibraryAnswer() = runTest {
        val question = "What is the meaning of life?"
        val answer = "42"
        val recordings = listOf(
            Recording("note1.mp3", title = "Note 1", summary = "Summary 1", shortSummary = "Short 1")
        )
        
        every { embedder.isReady } returns true
        coEvery { embedder.embed(any()) } returns floatArrayOf(0.1f, 0.2f)
        every { repo.allRecordings } returns flowOf(recordings)
        coEvery { repo.semanticSearch(any(), any(), any()) } returns recordings
        coEvery { llm.generate(any(), any()) } returns answer

        viewModel.askLibraryQuestion(question)
        advanceUntilIdle()

        assertEquals(answer, viewModel.libraryAnswer.value)
        assertEquals(recordings, viewModel.librarySources.value)
        assertEquals(question, viewModel.libraryQuestion.value)
    }

    @Test
    fun deleteMultipleRecordings_updatesProgressAndCallsDelete() = runTest {
        val filenames = listOf("file1.mp3", "file2.mp3")
        coEvery { repo.get("file1.mp3") } returns Recording("file1.mp3", durationMillis = 1000L)
        coEvery { repo.get("file2.mp3") } returns Recording("file2.mp3", durationMillis = 2000L)

        viewModel.deleteMultipleRecordings(filenames)
        
        // Advance time to allow coroutine to run
        advanceUntilIdle()
        
        // Verify repo delete called twice
        coVerify(exactly = 2) { repo.delete(any()) }
        
        // Final progress should be null
        assertEquals(null, viewModel.syncProgress.value)
    }

    @Test
    fun updateTitleAndSummary_delegatesToRepo() = runTest {
        viewModel.updateTitleAndSummary("rec.mp3", "New Title", "New summary")
        advanceUntilIdle()
        coVerify(exactly = 1) { repo.updateTitleAndSummary("rec.mp3", "New Title", "New summary") }
    }
}
