package com.daedalusapps.echo

import android.app.Application
import android.util.Log
import com.daedalusapps.echo.data.RecordingRepository
import com.daedalusapps.echo.data.db.AppDatabase
import com.daedalusapps.echo.ai.EmbeddingService
import com.daedalusapps.echo.ai.LocalLlmService
import com.daedalusapps.echo.ai.TranscriptionService
import com.daedalusapps.echo.data.model.Recording
import com.daedalusapps.echo.viewmodel.RecordingViewModel
import io.mockk.*
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import java.io.File
import kotlin.coroutines.CoroutineContext

/**
 * Wraps a real dispatcher and counts how many times coroutines are actually dispatched onto
 * it. Used to pin dispatcher ROUTING deterministically (#76): if production code ignores the
 * injected `ioDispatcher` and uses a raw `Dispatchers.IO` instead, this count stays at 0 no
 * matter how long we wait — it does not depend on real-thread timing, so it can't be a flake
 * in either direction.
 *
 * Delegates [Delay] to the wrapped dispatcher (always a [StandardTestDispatcher] in this file)
 * so virtual time is preserved. Without this, a `delay()` call while this dispatcher is the
 * interceptor would fall back to real wall-clock `DefaultDelay`, `advanceUntilIdle()` would
 * return early, and the coroutine could outlive the test.
 */
@OptIn(kotlinx.coroutines.InternalCoroutinesApi::class)
private class DispatchCountingDispatcher(
    private val delegate: CoroutineDispatcher
) : CoroutineDispatcher(), Delay by (delegate as Delay) {
    var dispatchCount = 0
        private set

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        dispatchCount++
        delegate.dispatch(context, block)
    }
}

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
            embedder = embedder,
            ioDispatcher = testDispatcher
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
        coEvery { repo.semanticSearch(any(), any(), any(), any()) } returns recordings
        coEvery { llm.generate(any(), any<String>()) } returns answer

        viewModel.askLibraryQuestion(question)
        advanceUntilIdle()

        assertEquals(answer, viewModel.libraryAnswer.value)
        assertEquals(recordings, viewModel.librarySources.value)
        assertEquals(question, viewModel.libraryQuestion.value)
    }

    // (#67) A note with a backfilled preview but blank summary must still be a candidate for Ask
    // Library, not silently excluded because summary alone is blank.
    @Test
    fun askLibraryQuestion_includesNoteWithShortSummaryButBlankSummary() = runTest {
        val question = "What did we discuss?"
        val previewOnlyNote = Recording(
            "note1.mp3",
            title = "Note 1",
            summary = "",
            shortSummary = "Short preview only"
        )

        every { embedder.isReady } returns true
        coEvery { embedder.embed(any()) } returns floatArrayOf(0.1f, 0.2f)
        every { repo.allRecordings } returns flowOf(listOf(previewOnlyNote))
        val candidatesSlot = slot<List<Recording>>()
        coEvery {
            repo.semanticSearch(any(), capture(candidatesSlot), any(), any())
        } returns listOf(previewOnlyNote)
        coEvery { llm.generate(any(), any<String>()) } returns "answer"

        viewModel.askLibraryQuestion(question)
        advanceUntilIdle()

        assertTrue(candidatesSlot.captured.any { it.filename == "note1.mp3" })
    }

    // (#67) A note with BOTH summary and shortSummary blank is genuinely unanalyzed and must stay
    // excluded from Ask Library.
    @Test
    fun askLibraryQuestion_excludesNoteWithBothSummaryFieldsBlank() = runTest {
        val question = "What did we discuss?"
        val unanalyzedNote = Recording("note1.mp3", title = "Note 1", summary = "", shortSummary = "")

        every { embedder.isReady } returns true
        every { repo.allRecordings } returns flowOf(listOf(unanalyzedNote))
        val candidatesSlot = slot<List<Recording>>()
        coEvery {
            repo.semanticSearch(any(), capture(candidatesSlot), any(), any())
        } returns emptyList()
        coEvery { embedder.embed(any()) } returns floatArrayOf(0.1f, 0.2f)

        viewModel.askLibraryQuestion(question)
        advanceUntilIdle()

        assertTrue(candidatesSlot.captured.none { it.filename == "note1.mp3" })
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

    // ------------------------------------------------------------------
    // #76 — dispatcher routing. A method that ignores the injected `ioDispatcher` and uses a
    // raw `Dispatchers.IO` internally will dispatch its IO work onto the real IO thread pool
    // instead of the StandardTestDispatcher, so advanceUntilIdle() cannot drain it and the
    // coroutine can outlive the test. These tests pin ROUTING (did the work dispatch
    // through the injected dispatcher at all?), which is deterministic, rather than timing.
    // ------------------------------------------------------------------

    @Test
    fun init_healsMissingDurations_routesOnInjectedIoDispatcher() = runTest {
        val counting = DispatchCountingDispatcher(testDispatcher)
        val audio = File.createTempFile("route-heal", ".mp3").also { it.deleteOnExit() }
        val needsHeal = Recording(filename = "needs-heal.mp3", localPath = audio.absolutePath, durationMillis = 0L)
        every { repo.allRecordings } returns flowOf(listOf(needsHeal))

        RecordingViewModel(
            application = application, db = mockk(relaxed = true), repo = repo, llm = llm,
            transcriber = mockk(relaxed = true), embedder = embedder, ioDispatcher = counting
        )
        advanceUntilIdle()

        assertTrue(
            "init's duration heal must route through the injected ioDispatcher, not a real Dispatchers.IO thread",
            counting.dispatchCount > 0
        )
    }

    @Test
    fun syncFiles_processesUris_routesOnInjectedIoDispatcher() = runTest {
        val counting = DispatchCountingDispatcher(testDispatcher)
        val tempDir = java.nio.file.Files.createTempDirectory("syncfiles_test").toFile()
        every { application.getExternalFilesDir(null) } returns tempDir

        val vm = RecordingViewModel(
            application = application, db = mockk(relaxed = true), repo = repo, llm = llm,
            transcriber = mockk(relaxed = true), embedder = embedder, ioDispatcher = counting
        )
        advanceUntilIdle()
        val before = counting.dispatchCount

        vm.syncFiles(emptyList())
        advanceUntilIdle()

        tempDir.deleteRecursively()

        assertTrue(
            "syncFiles' copy loop must route through the injected ioDispatcher, not a real Dispatchers.IO thread",
            counting.dispatchCount > before
        )
    }

    @Test
    fun exportMarkdown_writesFile_routesOnInjectedIoDispatcher() = runTest {
        val counting = DispatchCountingDispatcher(testDispatcher)
        val tempDir = java.nio.file.Files.createTempDirectory("export_test").toFile()
        every { application.cacheDir } returns tempDir
        val recording = Recording("test.mp3", title = "Test", summary = "Summary")
        coEvery { repo.get("test.mp3") } returns recording

        val vm = RecordingViewModel(
            application = application, db = mockk(relaxed = true), repo = repo, llm = llm,
            transcriber = mockk(relaxed = true), embedder = embedder, ioDispatcher = counting
        )
        advanceUntilIdle()
        val before = counting.dispatchCount

        vm.exportMarkdown("test.mp3")
        advanceUntilIdle()

        tempDir.deleteRecursively()

        assertTrue(
            "exportMarkdown must route through the injected ioDispatcher, not a real Dispatchers.IO thread",
            counting.dispatchCount > before
        )
    }

    @Test
    fun exportLibraryAnswer_writesFile_routesOnInjectedIoDispatcher() = runTest {
        val counting = DispatchCountingDispatcher(testDispatcher)
        val tempDir = java.nio.file.Files.createTempDirectory("export_lib_test").toFile()
        every { application.cacheDir } returns tempDir

        val vm = RecordingViewModel(
            application = application, db = mockk(relaxed = true), repo = repo, llm = llm,
            transcriber = mockk(relaxed = true), embedder = embedder, ioDispatcher = counting
        )
        advanceUntilIdle()

        every { embedder.isReady } returns true
        coEvery { embedder.embed(any()) } returns floatArrayOf(0.1f, 0.2f)
        val recordings = listOf(Recording("note1.mp3", summary = "Summary 1"))
        every { repo.allRecordings } returns flowOf(recordings)
        coEvery { repo.semanticSearch(any(), any(), any(), any()) } returns recordings
        coEvery { llm.generate(any(), any<String>()) } returns "answer"
        vm.askLibraryQuestion("question")
        advanceUntilIdle()

        val before = counting.dispatchCount
        vm.exportLibraryAnswer()
        advanceUntilIdle()

        tempDir.deleteRecursively()

        assertTrue(
            "exportLibraryAnswer must route through the injected ioDispatcher, not a real Dispatchers.IO thread",
            counting.dispatchCount > before
        )
    }
}

