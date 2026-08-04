package com.daedalusapps.echo.ai

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.daedalusapps.echo.data.model.Recording
import com.daedalusapps.echo.viewmodel.IDEATION_SYSTEM_PROMPT
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class OfflineGuardrailTest {

    private lateinit var context: Context

    private fun prefs() =
        context.getSharedPreferences("daedalus_prefs", Context.MODE_PRIVATE)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        prefs().edit().clear().commit()
    }

    @Test
    fun defaultPrompt_containsGuardrailExactlyOnce() {
        assertEquals(1, countOccurrences(DEFAULT_PROMPT, OFFLINE_GUARDRAIL))
    }

    @Test
    fun chunkSummaryPrompt_containsGuardrailExactlyOnce() {
        assertEquals(1, countOccurrences(CHUNK_SUMMARY_PROMPT, OFFLINE_GUARDRAIL))
    }

    @Test
    fun todoExtractionPrompt_containsGuardrailExactlyOnce() {
        assertEquals(1, countOccurrences(TODO_EXTRACTION_PROMPT, OFFLINE_GUARDRAIL))
    }

    /** TodoViewModel appends an "Already tracked" block; that must not add a second guardrail. */
    @Test
    fun todoExtractionPrompt_withTrackedBlock_containsGuardrailExactlyOnce() {
        val prompt = TODO_EXTRACTION_PROMPT + "\n\nAlready tracked (do not repeat):\n- buy milk"
        assertEquals(1, countOccurrences(prompt, OFFLINE_GUARDRAIL))
    }

    @Test
    fun activePrompt_default_containsGuardrailExactlyOnce() {
        val prompt = activePrompt(context)
        assertEquals(1, countOccurrences(prompt, OFFLINE_GUARDRAIL))
    }

    @Test
    fun activePrompt_custom_containsGuardrailExactlyOnce() {
        prefs().edit().putString("custom_prompt", "My custom instructions.").commit()
        val prompt = activePrompt(context)
        assertEquals(1, countOccurrences(prompt, OFFLINE_GUARDRAIL))
    }

    /** The prompt editor pre-fills DEFAULT_PROMPT, so a saved edit already has the guardrail. */
    @Test
    fun activePrompt_customEditedFromDefault_containsGuardrailExactlyOnce() {
        prefs().edit().putString("custom_prompt", "$DEFAULT_PROMPT\nAlso be brief.").commit()
        val prompt = activePrompt(context)
        assertEquals(1, countOccurrences(prompt, OFFLINE_GUARDRAIL))
    }

    @Test
    fun activePrompt_noCustom_fallsBackToDefault() {
        assertEquals(DEFAULT_PROMPT, activePrompt(context))
    }

    @Test
    fun noteQuestionPrompt_containsGuardrailExactlyOnce() {
        val prompt = buildNoteQuestionPrompt("Title", "Summary")
        assertEquals(1, countOccurrences(prompt, OFFLINE_GUARDRAIL))
    }

    @Test
    fun ideationSystemPrompt_containsGuardrailExactlyOnce() {
        assertEquals(1, countOccurrences(IDEATION_SYSTEM_PROMPT, OFFLINE_GUARDRAIL))
    }

    @Test
    fun libraryQuestionPrompt_containsGuardrailExactlyOnce() {
        val source = Recording(filename = "a.wav", title = "Note A", shortSummary = "Summary A")
        val prompt = buildLibraryQuestionPrompt(listOf(source))
        assertEquals(1, countOccurrences(prompt, OFFLINE_GUARDRAIL))
    }

    private fun countOccurrences(haystack: String, needle: String): Int =
        haystack.split(needle).size - 1
}
