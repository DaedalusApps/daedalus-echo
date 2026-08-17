package com.daedalusapps.echo.ai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the routing decision behind the degraded-analysis bug: Gemma 3 1B stops emitting JSON
 * when the prompt carries more than a couple of thousand characters of raw transcript, so only
 * short transcripts may go straight to the JSON prompt.
 */
class BulletSynthesisRoutingTest {

    @Test
    fun shortTranscript_goesStraightToJson() {
        // 1,674 chars parsed cleanly on-device.
        assertFalse(needsBulletSynthesis(transcriptLength = 1_674, chunkCount = 1))
    }

    @Test
    fun mediumTranscriptUnderBudget_stillSummarizesFirst() {
        // The regression: 3,935 chars fits one chunk of a 12,000 budget, took the direct path,
        // and degraded. A single chunk is no longer sufficient reason to skip bullets.
        assertTrue(needsBulletSynthesis(transcriptLength = 3_935, chunkCount = 1))
    }

    @Test
    fun splitPartSizedTranscript_summarizesFirst() {
        // Every measured split part (10,681–11,937 chars) degraded on the direct path.
        assertTrue(needsBulletSynthesis(transcriptLength = 11_688, chunkCount = 1))
    }

    @Test
    fun multipleChunks_alwaysSummarizeFirst() {
        assertTrue(needsBulletSynthesis(transcriptLength = 40_000, chunkCount = 4))
    }

    @Test
    fun splitPartSizedTranscript_isChunkedForTheBulletStage() {
        // The 12,000 default left a 10,364-char transcript as a single chunk, whose 4,274-char
        // bullets still degraded the JSON step. The bullet stage caps the budget so the same
        // transcript is split and each chunk yields a short summary.
        assertTrue(chunkTranscript(transcript = "x".repeat(10_364), budget = 6_000).size > 1)
    }

    @Test
    fun tinyTranscript_goesStraightToJson() {
        assertFalse(needsBulletSynthesis(transcriptLength = 200, chunkCount = 1))
    }

    @Test
    fun isTranscriptReadable_blankOrEmpty_returnsFalse() {
        assertFalse(isTranscriptReadable(""))
        assertFalse(isTranscriptReadable("   "))
    }

    @Test
    fun isTranscriptReadable_bracketTagsOnly_returnsFalse() {
        assertFalse(isTranscriptReadable("[laughter]"))
        assertFalse(isTranscriptReadable("  [music]  "))
        assertFalse(isTranscriptReadable("(silent)"))
        assertFalse(isTranscriptReadable("[laughter] (cough)"))
    }

    @Test
    fun isTranscriptReadable_tooShort_returnsFalse() {
        assertFalse(isTranscriptReadable("Hello."))
        assertFalse(isTranscriptReadable("Yes sure."))
        assertFalse(isTranscriptReadable("[laughter] Hello you")) // clean text has 2 words
    }

    @Test
    fun isTranscriptReadable_whisperLoopHallucination_returnsFalse() {
        assertFalse(isTranscriptReadable("Thank you. Thank you. Thank you. Thank you. Thank you. Thank you. Thank you. Thank you."))
        assertFalse(isTranscriptReadable("Is there Is there Is there Is there Is there Is there Is there Is there"))
        assertFalse(isTranscriptReadable("one two three four one two three four one two three four one two three four"))
    }

    @Test
    fun isTranscriptReadable_punctuatedWhisperLoopHallucination_returnsFalse() {
        assertFalse(isTranscriptReadable("Thank you. Thank you, thank you! Thank you?"))
    }

    @Test
    fun isTranscriptReadable_naturalSpeechWithRepeatedPhraseAndTail_returnsTrue() {
        assertTrue(isTranscriptReadable("Thank you very much, thank you very much everyone for coming today."))
        assertTrue(isTranscriptReadable("Thank you very much, thank you very much everyone"))
        assertTrue(isTranscriptReadable("We need to go, we need to go now"))
    }

    @Test
    fun isTranscriptReadable_normalReadableSpeech_returnsTrue() {
        assertTrue(isTranscriptReadable("This is a normal recording with some readable speech."))
        assertTrue(isTranscriptReadable("Meeting starts today at three PM [laughter] to discuss the project roadmap."))
    }
}


