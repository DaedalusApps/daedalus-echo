package com.daedalusapps.echo.ai

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.daedalusapps.echo.data.RecordingRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkStatic
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RecordingAnalysisTest {

    private lateinit var context: Context
    private lateinit var llm: LocalLlmService
    private lateinit var embedder: EmbeddingService
    private lateinit var repo: RecordingRepository

    @Before
    fun setup() {
        val prefs = mockk<SharedPreferences>(relaxed = true)
        every { prefs.getString("custom_prompt", null) } returns null
        every { prefs.getInt(AI_TEXT_BUDGET_KEY, AI_TEXT_BUDGET_DEFAULT) } returns AI_TEXT_BUDGET_DEFAULT
        context = mockk(relaxed = true)
        every { context.getSharedPreferences(any(), any()) } returns prefs

        llm = mockk(relaxed = true)
        embedder = mockk(relaxed = true)
        repo = mockk(relaxed = true)

        every { embedder.isReady } returns false

        mockkStatic(Log::class)
        every { Log.w(any(), any<String>()) } returns 0
    }

    @After
    fun tearDown() {
        unmockkStatic(Log::class)
    }

    private val jsonResponse = """
        {"title": "Standup", "shortSummary": "Quick sync", "topics": ["standup", "sync"], "mindMap": "- point one", "fullSummary": "Discussed standup items."}
    """.trimIndent()

    // Real degraded output captured from a device: Gemma answered as a free-form bullet list of
    // quotes instead of following the JSON/markdown-field instruction. Neither tryParseJson nor
    // tryParseMarkdown can extract any known field from this, so SmartAnalysisParser.parse falls
    // back to SmartAnalysis(fullSummary = rawResponse) — blank title/shortSummary/topics/mindMap.
    private val degradedBulletFixture = """
        - “I need an offsite team building event”
        - “September”
        - “approximately” number – “how many people”
        - “Mid-September” – “better than late-September”
        - “Monday or Tuesday” – “This gives us some decent options”
        - “half day” – “This helps determine logistical feasibility”
        - “Relaxed and creative” – “more appealing”
        - “activity-based and focused on team building”
        - “Resort/Hotels” – “This can provide some space and have beautiful/cool views”
        - “Campgrounds” – “This can be very relaxed and budget friendly but requires more planning regarding space and amenities”
        - “Hotel Ballroom/Event Space” – “This can be more formal and can be customized easily”
        - “More appealing” – “This feels more appealing”
        - “Gunshot” – “This suggests there’s someone reacting negatively”
        - “Venue ideas” – “Let’s find options based on Tuesday or Wednesday”
        - “Resort/Hotels” – “This can provide some space and have beautiful/cool views”
        - “Campgrounds” – “This can be very relaxed and budget friendly but requires more planning regarding space and amenities”
        - “Hotel Ballroom/Event Space” – “This can be more formal and can be customized easily”
    """.trimIndent()

    @Test
    fun updateSummary_degradedFreeFormResponse_derivesNonBlankTitleAndSummary() = runTest {
        val transcript = "offsite planning discussion"
        coEvery { llm.generate(any(), any<String>()) } returns degradedBulletFixture

        analyzeTranscript(context, llm, embedder, repo, "note.mp3", transcript)

        val titleSlot = slot<String>()
        val shortSummarySlot = slot<String>()
        coVerify(exactly = 1) {
            repo.updateSummary(
                filename = "note.mp3",
                summary = any(),
                mindMap = any(),
                title = capture(titleSlot),
                shortSummary = capture(shortSummarySlot),
                topics = any()
            )
        }
        assertTrue("title should not be blank", titleSlot.captured.isNotBlank())
        assertTrue("shortSummary should not be blank", shortSummarySlot.captured.isNotBlank())
    }

    @Test
    fun updateSummary_degradedFreeFormResponse_derivesSensibleTitle() = runTest {
        val transcript = "offsite planning discussion"
        coEvery { llm.generate(any(), any<String>()) } returns degradedBulletFixture

        analyzeTranscript(context, llm, embedder, repo, "note.mp3", transcript)

        val titleSlot = slot<String>()
        coVerify(exactly = 1) {
            repo.updateSummary(
                filename = "note.mp3",
                summary = any(),
                mindMap = any(),
                title = capture(titleSlot),
                shortSummary = any(),
                topics = any()
            )
        }
        val title = titleSlot.captured
        assertTrue("title should not be blank", title.isNotBlank())
        assertTrue("title should not start with '-'", !title.startsWith("-"))
        assertTrue(
            "title should not start with a quote character",
            !title.startsWith("\"") && !title.startsWith("'") &&
                !title.startsWith("“") && !title.startsWith("‘")
        )
        assertTrue("title should be within the length cap", title.length <= 60)
        assertTrue("title should be a single line", !title.contains("\n"))
    }

    @Test
    fun updateSummary_wellFormedJson_isNotAlteredByFallback() = runTest {
        val transcript = "short transcript"
        coEvery { llm.generate(any(), any<String>()) } returns jsonResponse

        analyzeTranscript(context, llm, embedder, repo, "note.mp3", transcript)

        coVerify(exactly = 1) {
            repo.updateSummary(
                filename = "note.mp3",
                summary = "Discussed standup items.",
                mindMap = "- point one",
                title = "Standup",
                shortSummary = "Quick sync",
                topics = listOf("standup", "sync")
            )
        }
    }

    @Test
    fun updateSummary_partiallyParsedResponse_isLeftUntouched() = runTest {
        val transcript = "short transcript"
        // Has a title but blank shortSummary/mindMap/topics: NOT all four fields are blank, so the
        // degraded-fallback guard must not engage and must not overwrite the partially-parsed result.
        val partialJson = """
            {"title": "Partial Title", "shortSummary": "", "topics": [], "mindMap": "", "fullSummary": "Some full summary text."}
        """.trimIndent()
        coEvery { llm.generate(any(), any<String>()) } returns partialJson

        analyzeTranscript(context, llm, embedder, repo, "note.mp3", transcript)

        coVerify(exactly = 1) {
            repo.updateSummary(
                filename = "note.mp3",
                summary = "Some full summary text.",
                mindMap = "",
                title = "Partial Title",
                shortSummary = "",
                topics = emptyList()
            )
        }
    }

    @Test
    fun updateSummary_degradedFreeFormResponse_topicsAndMindMapRemainEmpty() = runTest {
        val transcript = "offsite planning discussion"
        coEvery { llm.generate(any(), any<String>()) } returns degradedBulletFixture

        analyzeTranscript(context, llm, embedder, repo, "note.mp3", transcript)

        coVerify(exactly = 1) {
            repo.updateSummary(
                filename = "note.mp3",
                summary = any(),
                mindMap = "",
                title = any(),
                shortSummary = any(),
                topics = emptyList()
            )
        }
    }

    @Test
    fun singlePass_callsGenerateOnceWithActivePrompt() = runTest {
        val transcript = "short transcript"
        coEvery { llm.generate(any(), any<String>()) } returns jsonResponse

        analyzeTranscript(context, llm, embedder, repo, "note.mp3", transcript)

        coVerify(exactly = 1) { llm.generate(DEFAULT_PROMPT, transcript) }
    }

    @Test
    fun multiChunk_callsPerChunkThenSynthesis() = runTest {
        // aiTextBudget resolves to AI_TEXT_BUDGET_DEFAULT (12,000 chars) via the mocked prefs,
        // so a transcript well beyond that forces multiple chunks.
        val transcript = "word ".repeat(3000)
        coEvery { llm.generate(any(), any<String>()) } returns jsonResponse

        analyzeTranscript(context, llm, embedder, repo, "note.mp3", transcript)

        coVerify(atLeast = 2) { llm.generate(CHUNK_SUMMARY_PROMPT, any<String>()) }
        coVerify(exactly = 1) { llm.generate(DEFAULT_PROMPT, any<String>()) }
    }

    @Test
    fun updateSummary_receivesParsedFields() = runTest {
        val transcript = "short transcript"
        coEvery { llm.generate(any(), any<String>()) } returns jsonResponse

        analyzeTranscript(context, llm, embedder, repo, "note.mp3", transcript)

        coVerify(exactly = 1) {
            repo.updateSummary(
                filename = "note.mp3",
                summary = "Discussed standup items.",
                mindMap = "- point one",
                title = "Standup",
                shortSummary = "Quick sync",
                topics = listOf("standup", "sync")
            )
        }
    }

    @Test
    fun embeddingSaved_whenEmbedderReady() = runTest {
        val transcript = "short transcript"
        coEvery { llm.generate(any(), any<String>()) } returns jsonResponse
        every { embedder.isReady } returns true
        coEvery { embedder.embed(any()) } returns floatArrayOf(0.1f, 0.2f)

        analyzeTranscript(context, llm, embedder, repo, "note.mp3", transcript)

        coVerify(exactly = 1) { embedder.ensureLoaded() }
        coVerify(exactly = 1) { repo.updateEmbedding("note.mp3", floatArrayOf(0.1f, 0.2f)) }
    }

    @Test
    fun embeddingNotSaved_whenEmbedderNotReady() = runTest {
        val transcript = "short transcript"
        coEvery { llm.generate(any(), any<String>()) } returns jsonResponse
        every { embedder.isReady } returns false

        analyzeTranscript(context, llm, embedder, repo, "note.mp3", transcript)

        coVerify(exactly = 0) { embedder.embed(any()) }
        coVerify(exactly = 0) { repo.updateEmbedding(any(), any()) }
    }

    @Test
    fun singlePass_reportsAnalyzingProgress() = runTest {
        val transcript = "short transcript"
        coEvery { llm.generate(any(), any<String>()) } returns jsonResponse
        val updates = mutableListOf<String>()

        analyzeTranscript(context, llm, embedder, repo, "note.mp3", transcript) { updates.add(it) }

        assertEquals(listOf("Analyzing with Gemma…"), updates)
    }

    @Test
    fun multiChunk_reportsPerChunkThenSynthesisProgress() = runTest {
        val transcript = "word ".repeat(3000)
        coEvery { llm.generate(any(), any<String>()) } returns jsonResponse
        val updates = mutableListOf<String>()

        analyzeTranscript(context, llm, embedder, repo, "note.mp3", transcript) { updates.add(it) }

        assertEquals("Synthesizing results…", updates.last())
        assertEquals(updates.size - 1, updates.count { it.startsWith("Analyzing part ") })
        assertEquals("Analyzing part 1 of ${updates.size - 1}…", updates.first())
    }
}
