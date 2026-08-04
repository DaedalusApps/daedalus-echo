package com.daedalusapps.echo.ai

import android.content.Context
import android.content.SharedPreferences
import com.daedalusapps.echo.data.RecordingRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
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
        context = mockk(relaxed = true)
        every { context.getSharedPreferences(any(), any()) } returns prefs

        llm = mockk(relaxed = true)
        embedder = mockk(relaxed = true)
        repo = mockk(relaxed = true)

        every { embedder.isReady } returns false
    }

    private val jsonResponse = """
        {"title": "Standup", "shortSummary": "Quick sync", "topics": ["standup", "sync"], "mindMap": "- point one", "fullSummary": "Discussed standup items."}
    """.trimIndent()

    @Test
    fun singlePass_callsGenerateOnceWithActivePrompt() = runTest {
        val transcript = "short transcript"
        coEvery { llm.generate(any(), any()) } returns jsonResponse

        analyzeTranscript(context, llm, embedder, repo, "note.mp3", transcript)

        coVerify(exactly = 1) { llm.generate(DEFAULT_PROMPT, transcript) }
    }

    @Test
    fun multiChunk_callsPerChunkThenSynthesis() = runTest {
        // Default aiTextBudget resolves to the clamped minimum (2000 chars) against the relaxed
        // SharedPreferences mock, so a transcript well beyond that forces multiple chunks.
        val transcript = "word ".repeat(1000)
        coEvery { llm.generate(any(), any()) } returns jsonResponse

        analyzeTranscript(context, llm, embedder, repo, "note.mp3", transcript)

        coVerify(atLeast = 2) { llm.generate(CHUNK_SUMMARY_PROMPT, any()) }
        coVerify(exactly = 1) { llm.generate(DEFAULT_PROMPT, any()) }
    }

    @Test
    fun updateSummary_receivesParsedFields() = runTest {
        val transcript = "short transcript"
        coEvery { llm.generate(any(), any()) } returns jsonResponse

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
        coEvery { llm.generate(any(), any()) } returns jsonResponse
        every { embedder.isReady } returns true
        coEvery { embedder.embed(any()) } returns floatArrayOf(0.1f, 0.2f)

        analyzeTranscript(context, llm, embedder, repo, "note.mp3", transcript)

        coVerify(exactly = 1) { embedder.ensureLoaded() }
        coVerify(exactly = 1) { repo.updateEmbedding("note.mp3", floatArrayOf(0.1f, 0.2f)) }
    }

    @Test
    fun embeddingNotSaved_whenEmbedderNotReady() = runTest {
        val transcript = "short transcript"
        coEvery { llm.generate(any(), any()) } returns jsonResponse
        every { embedder.isReady } returns false

        analyzeTranscript(context, llm, embedder, repo, "note.mp3", transcript)

        coVerify(exactly = 0) { embedder.embed(any()) }
        coVerify(exactly = 0) { repo.updateEmbedding(any(), any()) }
    }
}
