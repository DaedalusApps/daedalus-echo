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
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
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
@RunWith(RobolectricTestRunner::class)
class RecordingViewModelTest {

    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    private val application = mockk<Application>(relaxed = true)
    private val repo = mockk<RecordingRepository>(relaxed = true)
    private val embedder = mockk<EmbeddingService>(relaxed = true)
    private val llm = mockk<LocalLlmService>(relaxed = true)
    
    private val transcriber = mockk<TranscriptionService>(relaxed = true)
    
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
    fun analyze_resetsIsProcessingAndSyncProgressOnCompletion() = runTest {
        val audioFile = File.createTempFile("test_audio", ".mp3").also { it.deleteOnExit() }
        val note = Recording("rec.mp3", localPath = audioFile.absolutePath)
        coEvery { repo.get("rec.mp3") } returns note
        coEvery { transcriber.transcribe(any()) } returns "test transcript"
        coEvery { llm.generate(any(), any<String>()) } returns """{"title":"T","shortSummary":"S","topics":[],"mindMap":"","fullSummary":"F"}"""

        viewModel.analyze("rec.mp3")
        advanceUntilIdle()

        assertEquals(false, viewModel.isProcessing.value)
        assertNull(viewModel.syncProgress.value)
    }

    @Test
    fun analyze_resetsIsProcessingAndSyncProgressOnException() = runTest {
        val audioFile = File.createTempFile("test_audio_err", ".mp3").also { it.deleteOnExit() }
        val note = Recording("rec.mp3", localPath = audioFile.absolutePath)
        coEvery { repo.get("rec.mp3") } returns note
        coEvery { transcriber.transcribe(any()) } throws RuntimeException("Transcription failed")

        viewModel.analyze("rec.mp3")
        advanceUntilIdle()

        assertEquals(false, viewModel.isProcessing.value)
        assertNull(viewModel.syncProgress.value)
        assertEquals("Transcription failed", viewModel.aiError.value)
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
    fun formatParagraphsPreview_returnsFormattedTranscriptWhenExists() = runTest {
        val note = Recording("rec.mp3", transcript = "First sentence. Second sentence. Third sentence. Fourth sentence.")
        coEvery { repo.get("rec.mp3") } returns note

        val result = viewModel.formatParagraphsPreview("rec.mp3")

        val expected = "First sentence. Second sentence. Third sentence.\n\nFourth sentence."
        assertEquals(expected, result)
    }

    @Test
    fun formatParagraphsPreview_returnsNullWhenNoteNotFoundOrBlankTranscript() = runTest {
        coEvery { repo.get("missing.mp3") } returns null
        coEvery { repo.get("blank.mp3") } returns Recording("blank.mp3", transcript = "")

        assertNull(viewModel.formatParagraphsPreview("missing.mp3"))
        assertNull(viewModel.formatParagraphsPreview("blank.mp3"))
    }

    @Test
    fun searchPreview_returnsFilenamesFromRepoSearch() = runTest {
        val results = listOf(
            Recording("rec1.mp3", title = "Meeting"),
            Recording("rec2.mp3", title = "Meeting 2")
        )
        every { repo.search("Meeting") } returns flowOf(results)

        val filenames = viewModel.searchPreview("Meeting")

        assertEquals(listOf("rec1.mp3", "rec2.mp3"), filenames)
    }

    @Test
    fun exportAudio_emitsShareIntentWithAudioMimeType_whenFileExists() = runTest {
        val audioFile = File.createTempFile("test_audio", ".mp3").also { it.deleteOnExit() }
        val note = Recording("rec.mp3", localPath = audioFile.absolutePath)
        coEvery { repo.get("rec.mp3") } returns note

        val mockUri = mockk<Uri>()
        mockkStatic(FileProvider::class)
        every { FileProvider.getUriForFile(any(), any(), any()) } returns mockUri

        viewModel.exportAudio("rec.mp3")
        advanceUntilIdle()

        val intent = viewModel.exportIntent.value
        assertNotNull(intent)
        assertEquals(Intent.ACTION_CHOOSER, intent?.action)
        @Suppress("DEPRECATION")
        val shareIntent = intent?.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
        assertNotNull(shareIntent)
        assertEquals(Intent.ACTION_SEND, shareIntent?.action)
        assertEquals("audio/mpeg", shareIntent?.type)
        assertEquals(mockUri, shareIntent?.getParcelableExtra(Intent.EXTRA_STREAM))
        assertTrue(((shareIntent?.flags ?: 0) and Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0)

        unmockkStatic(FileProvider::class)
    }

    @Test
    fun exportAudio_doesNotEmitIntent_whenFileNotFound() = runTest {
        coEvery { repo.get("missing.mp3") } returns null

        viewModel.exportAudio("missing.mp3")
        advanceUntilIdle()

        assertNull(viewModel.exportIntent.value)

        coEvery { repo.get("not_on_disk.mp3") } returns Recording("not_on_disk.mp3", localPath = "/non/existent/path/not_on_disk.mp3")
        viewModel.exportAudio("not_on_disk.mp3")
        advanceUntilIdle()

        assertNull(viewModel.exportIntent.value)
    }
}

