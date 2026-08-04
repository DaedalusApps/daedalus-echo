package com.daedalusapps.echo.viewmodel

import android.app.Application
import android.content.SharedPreferences
import android.util.Log
import com.daedalusapps.echo.ai.AI_TEXT_BUDGET_DEFAULT
import com.daedalusapps.echo.ai.AI_TEXT_BUDGET_KEY
import com.daedalusapps.echo.ai.LocalLlmService
import com.daedalusapps.echo.data.db.AppDatabase
import com.daedalusapps.echo.data.db.RecordingDao
import com.daedalusapps.echo.data.db.TodoDao
import com.daedalusapps.echo.data.model.Recording
import com.daedalusapps.echo.data.model.TodoItem
import io.mockk.*
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

@OptIn(ExperimentalCoroutinesApi::class)
class TodoViewModelTest {

    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    private val application = mockk<Application>(relaxed = true)
    private val prefs = mockk<SharedPreferences>(relaxed = true)
    private val db = mockk<AppDatabase>(relaxed = true)
    private val todoDao = mockk<TodoDao>(relaxed = true)
    private val recordingDao = mockk<RecordingDao>(relaxed = true)
    private val llm = mockk<LocalLlmService>(relaxed = true)

    private lateinit var viewModel: TodoViewModel
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any() as String) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0

        every { application.getSharedPreferences(any(), any()) } returns prefs
        every { prefs.getInt(AI_TEXT_BUDGET_KEY, any()) } returns AI_TEXT_BUDGET_DEFAULT
        every { db.todoDao() } returns todoDao
        every { db.recordingDao() } returns recordingDao
        every { todoDao.getAllFlow() } returns flowOf(emptyList())
        coEvery { recordingDao.getSince(any()) } returns emptyList()
        coEvery { llm.ensureLoaded() } returns Unit

        viewModel = TodoViewModel(
            application = application,
            db = db,
            llm = llm,
            ioDispatcher = testDispatcher
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkStatic(Log::class)
    }

    // Helper: a recording with a summary long enough to force its own batch (> 9000 chars).
    private fun bigRecording(filename: String, title: String, summaryFill: String) =
        Recording(filename = filename, title = title, summary = summaryFill.repeat(5000).take(5000))

    // 1. Merge preserves existing todos + done state: existing rows are never updated or deleted;
    //    only new, non-duplicate items are inserted.
    @Test
    fun updateFromRecordings_preservesExisting_onlyInserts() = runTest {
        coEvery { recordingDao.getSince(any()) } returns listOf(
            Recording(filename = "a.mp3", title = "A", summary = "Discussed the roadmap")
        )
        coEvery { todoDao.getAllFlow() } returns flowOf(listOf(
            TodoItem(id = 1, text = "Existing done task", isDone = true),
            TodoItem(id = 2, text = "Existing open task", isDone = false)
        ))
        coEvery { llm.generate(any(), any<String>()) } returns "- Call the vendor\n- Send the report"

        viewModel.updateFromRecordings(24)
        advanceUntilIdle()

        // Existing todos must never be touched.
        coVerify(exactly = 0) { todoDao.update(any()) }
        coVerify(exactly = 0) { todoDao.delete(any()) }
        // Both new items inserted.
        coVerify(exactly = 1) { todoDao.insert(match { it.text == "Call the vendor" && it.isAiGenerated }) }
        coVerify(exactly = 1) { todoDao.insert(match { it.text == "Send the report" }) }
        assertEquals(2, viewModel.lastExtractCount.value)
    }

    // 2a. Dedup against existing: items duplicating existing todos (normalized/containment) are not inserted.
    @Test
    fun updateFromRecordings_dedupAgainstExisting_skipsDuplicates() = runTest {
        coEvery { recordingDao.getSince(any()) } returns listOf(
            Recording(filename = "a.mp3", title = "A", summary = "notes")
        )
        coEvery { todoDao.getAllFlow() } returns flowOf(listOf(TodoItem(id = 1, text = "Buy milk")))
        // Normalized duplicate ("buy milk") plus a genuinely new one.
        coEvery { llm.generate(any(), any<String>()) } returns "- buy milk\n- Wash the car"

        viewModel.updateFromRecordings(24)
        advanceUntilIdle()

        coVerify(exactly = 0) { todoDao.insert(match { it.text == "buy milk" }) }
        coVerify(exactly = 1) { todoDao.insert(match { it.text == "Wash the car" }) }
        assertEquals(1, viewModel.lastExtractCount.value)
    }

    // 2b. Dedup across batches within one run: same item returned by two batches is inserted once.
    @Test
    fun updateFromRecordings_dedupAcrossBatches_insertsOnce() = runTest {
        // Two big recordings force two separate batches.
        coEvery { recordingDao.getSince(any()) } returns listOf(
            bigRecording("a.mp3", "A", "x"),
            bigRecording("b.mp3", "B", "y")
        )
        coEvery { todoDao.getAllFlow() } returns flowOf(emptyList())
        // Both batches return the same task.
        coEvery { llm.generate(any(), any<String>()) } returns "- Follow up with Sam"

        viewModel.updateFromRecordings(24)
        advanceUntilIdle()

        // Two generate calls (two batches), but the duplicate task inserted only once.
        coVerify(exactly = 2) { llm.generate(any(), any<String>()) }
        coVerify(exactly = 1) { todoDao.insert(match { it.text == "Follow up with Sam" }) }
        assertEquals(1, viewModel.lastExtractCount.value)
    }

    // 3. Lookback: cutoff = now - hours*3_600_000 passed to getSince; -1 -> cutoff 0 (all).
    @Test
    fun updateFromRecordings_lookbackCutoff_computedCorrectly() = runTest {
        val cutoffSlot = slot<Long>()
        coEvery { recordingDao.getSince(capture(cutoffSlot)) } returns emptyList()

        val before = System.currentTimeMillis()
        viewModel.updateFromRecordings(24)
        advanceUntilIdle()
        val after = System.currentTimeMillis()

        val expectedLow = before - 24 * 3_600_000L
        val expectedHigh = after - 24 * 3_600_000L
        assertTrue(
            "cutoff ${cutoffSlot.captured} not in [$expectedLow, $expectedHigh]",
            cutoffSlot.captured in expectedLow..expectedHigh
        )
    }

    @Test
    fun updateFromRecordings_lookbackNegative_cutoffZero() = runTest {
        val cutoffSlot = slot<Long>()
        coEvery { recordingDao.getSince(capture(cutoffSlot)) } returns emptyList()

        viewModel.updateFromRecordings(-1)
        advanceUntilIdle()

        assertEquals(0L, cutoffSlot.captured)
    }

    // 4. Summary preferred over transcript; blank summary falls back to transcript.take(2000).
    @Test
    fun updateFromRecordings_blankSummary_fallsBackToTranscript() = runTest {
        coEvery { recordingDao.getSince(any()) } returns listOf(
            Recording(filename = "a.mp3", title = "Alpha", summary = "SUMMARY_TEXT"),
            Recording(filename = "b.mp3", title = "Beta", summary = "", transcript = "TRANSCRIPT_TEXT")
        )
        coEvery { todoDao.getAllFlow() } returns flowOf(emptyList())
        val userTextSlot = slot<String>()
        coEvery { llm.generate(any(), capture(userTextSlot)) } returns "- none"

        viewModel.updateFromRecordings(24)
        advanceUntilIdle()

        val sent = userTextSlot.captured
        assertTrue("summary used when present", sent.contains("SUMMARY_TEXT"))
        assertTrue("transcript used when summary blank", sent.contains("TRANSCRIPT_TEXT"))
    }

    @Test
    fun updateFromRecordings_bothBlank_recordingSkipped() = runTest {
        coEvery { recordingDao.getSince(any()) } returns listOf(
            Recording(filename = "empty.mp3", title = "", summary = "", transcript = "")
        )
        coEvery { todoDao.getAllFlow() } returns flowOf(emptyList())

        viewModel.updateFromRecordings(24)
        advanceUntilIdle()

        // No usable note blocks -> nothing to send to the LLM.
        coVerify(exactly = 0) { llm.generate(any(), any<String>()) }
        assertEquals(0, viewModel.lastExtractCount.value)
    }

    // 5. Batching: combined note blocks exceeding ~9000 chars split into multiple generate() calls.
    @Test
    fun updateFromRecordings_largeInput_splitIntoMultipleBatches() = runTest {
        coEvery { recordingDao.getSince(any()) } returns listOf(
            bigRecording("a.mp3", "A", "x"),
            bigRecording("b.mp3", "B", "y")
        )
        coEvery { todoDao.getAllFlow() } returns flowOf(emptyList())
        coEvery { llm.generate(any(), any<String>()) } returns "- none"

        viewModel.updateFromRecordings(24)
        advanceUntilIdle()

        coVerify(exactly = 2) { llm.generate(any(), any<String>()) }
    }

    // 5b. Batching respects a smaller injected AI text budget: two moderate-size recordings
    //     that would fit in one batch at the default budget (9,000 = 12,000*3/4) split into
    //     two batches once the configured budget shrinks the derived MAX_BATCH_CHARS.
    @Test
    fun updateFromRecordings_smallerBudget_splitsIntoMoreBatches() = runTest {
        every { prefs.getInt(AI_TEXT_BUDGET_KEY, any()) } returns 4_000 // -> MAX_BATCH_CHARS = 3,000
        coEvery { recordingDao.getSince(any()) } returns listOf(
            Recording(filename = "a.mp3", title = "A", summary = "x".repeat(2_000)),
            Recording(filename = "b.mp3", title = "B", summary = "y".repeat(2_000))
        )
        coEvery { todoDao.getAllFlow() } returns flowOf(emptyList())
        coEvery { llm.generate(any(), any<String>()) } returns "- none"

        viewModel.updateFromRecordings(24)
        advanceUntilIdle()

        // Combined blocks (~4,020 chars) exceed the derived 3,000-char batch cap -> 2 batches.
        coVerify(exactly = 2) { llm.generate(any(), any<String>()) }
    }

    // 6. Error path: llm.generate throws -> extractError set, isExtracting false; items already
    //    inserted from earlier successful batches remain (documented partial-progress behavior).
    @Test
    fun updateFromRecordings_generateThrows_setsErrorAndKeepsPartialProgress() = runTest {
        coEvery { recordingDao.getSince(any()) } returns listOf(
            bigRecording("a.mp3", "A", "x"),
            bigRecording("b.mp3", "B", "y")
        )
        coEvery { todoDao.getAllFlow() } returns flowOf(emptyList())
        // First batch succeeds, second batch throws.
        coEvery { llm.generate(any(), any<String>()) } returnsMany listOf("- First batch task") andThenThrows RuntimeException("boom")

        viewModel.updateFromRecordings(24)
        advanceUntilIdle()

        assertNotNull(viewModel.extractError.value)
        assertEquals(false, viewModel.isExtracting.value)
        // Item from the successful first batch stays inserted.
        coVerify(exactly = 1) { todoDao.insert(match { it.text == "First batch task" }) }
    }

    // 7. lastExtractCount set to number of newly inserted items.
    @Test
    fun updateFromRecordings_setsLastExtractCount() = runTest {
        coEvery { recordingDao.getSince(any()) } returns listOf(
            Recording(filename = "a.mp3", title = "A", summary = "notes")
        )
        coEvery { todoDao.getAllFlow() } returns flowOf(emptyList())
        coEvery { llm.generate(any(), any<String>()) } returns "- Task one\n- Task two\n- Task three"

        viewModel.updateFromRecordings(24)
        advanceUntilIdle()

        assertEquals(3, viewModel.lastExtractCount.value)
    }

    @Test
    fun updateFromRecordings_noRecordings_countZero() = runTest {
        coEvery { recordingDao.getSince(any()) } returns emptyList()

        viewModel.updateFromRecordings(24)
        advanceUntilIdle()

        coVerify(exactly = 0) { llm.generate(any(), any<String>()) }
        assertEquals(0, viewModel.lastExtractCount.value)
    }

    // Manual ops: addTodo / editTodo / deleteTodo / toggleDone call through to the DAO.
    @Test
    fun addTodo_insertsTrimmedText() = runTest {
        viewModel.addTodo("  Water the plants  ")
        advanceUntilIdle()
        coVerify(exactly = 1) { todoDao.insert(match { it.text == "Water the plants" }) }
    }

    @Test
    fun addTodo_blank_ignored() = runTest {
        viewModel.addTodo("   ")
        advanceUntilIdle()
        coVerify(exactly = 0) { todoDao.insert(any()) }
    }

    @Test
    fun editTodo_updatesWithNewText() = runTest {
        val item = TodoItem(id = 5, text = "Old")
        viewModel.editTodo(item, "New text")
        advanceUntilIdle()
        coVerify(exactly = 1) { todoDao.update(match { it.id == 5L && it.text == "New text" }) }
    }

    @Test
    fun deleteTodo_deletesItem() = runTest {
        val item = TodoItem(id = 7, text = "Remove me")
        viewModel.deleteTodo(item)
        advanceUntilIdle()
        coVerify(exactly = 1) { todoDao.delete(item) }
    }

    @Test
    fun toggleDone_flipsDoneState() = runTest {
        val item = TodoItem(id = 9, text = "Task", isDone = false)
        viewModel.toggleDone(item)
        advanceUntilIdle()
        coVerify(exactly = 1) { todoDao.setDone(9, true) }
    }
}
