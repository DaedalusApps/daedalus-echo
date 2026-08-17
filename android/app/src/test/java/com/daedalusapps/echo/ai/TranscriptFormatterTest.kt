package com.daedalusapps.echo.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptFormatterTest {

    @Test
    fun formatParagraphs_neverContainsSpeakerLabel() {
        val raw = "Hello world. This is a test meeting. We are discussing the project. " +
            "Next topic is AI initiative. Everything looks good."
        val formatted = TranscriptFormatter.formatParagraphs(raw)

        assertFalse("Must not contain a Speaker 1 label", formatted.contains("Speaker 1"))
        assertFalse("Must not contain a Speaker 2 label", formatted.contains("Speaker 2"))
        assertFalse("Must not contain any Speaker label", formatted.contains("Speaker"))
    }

    @Test
    fun formatParagraphs_groupsSentencesIntoBlankLineSeparatedParagraphs() {
        val raw = "Hello world. This is a test meeting. We are discussing the project. " +
            "Next topic is AI initiative. Everything looks good."
        val formatted = TranscriptFormatter.formatParagraphs(raw)

        val expected = "Hello world. This is a test meeting. We are discussing the project." +
            "\n\n" +
            "Next topic is AI initiative. Everything looks good."
        assertEquals(expected, formatted)
    }

    @Test
    fun formatParagraphs_blankInput_returnsEmpty() {
        assertEquals("", TranscriptFormatter.formatParagraphs(""))
        assertEquals("", TranscriptFormatter.formatParagraphs("   "))
    }

    @Test
    fun formatParagraphs_singleSentence_producesSingleParagraphNoTrailingBlankLine() {
        val formatted = TranscriptFormatter.formatParagraphs("Just one sentence here.")

        assertEquals("Just one sentence here.", formatted)
        assertFalse(formatted.contains("\n\n"))
    }

    @Test
    fun formatParagraphs_preExistingSpeakerLabelText_isNotSpecialCased() {
        // No passthrough guard: a transcript that happens to already contain "Speaker 1:" text
        // (never produced by Whisper, but not impossible if pasted/imported) is treated like any
        // other text and run through the same sentence grouping - it is not detected or stripped.
        // This input has real sentence terminators so the splitter fires: a restored passthrough
        // guard (early-return on the raw string) would diverge from this expected grouped output.
        val raw = "Speaker 1: Hello there. This is the first update. Things are going well. " +
            "Next quarter looks promising."
        val formatted = TranscriptFormatter.formatParagraphs(raw)

        val expected = "Speaker 1: Hello there. This is the first update. Things are going well." +
            "\n\n" +
            "Next quarter looks promising."
        assertEquals(expected, formatted)
    }

    @Test
    fun formatParagraphs_noSentenceEndingPunctuation_returnsSingleParagraph() {
        // Identity-equivalent output is expected here: with no terminators the splitter never
        // fires, so this is a genuine (documented) edge case, not a pin on the grouping logic.
        val raw = "hello world this has no punctuation at all it just keeps going"
        val formatted = TranscriptFormatter.formatParagraphs(raw)

        assertEquals(raw, formatted)
        assertFalse(formatted.contains("\n\n"))
    }

    @Test
    fun formatParagraphs_textEndingMidSentence_lastFragmentKeptAsIs() {
        val raw = "First sentence done. Second sentence done. and a trailing fragment with no terminator"
        val formatted = TranscriptFormatter.formatParagraphs(raw)

        val expected = "First sentence done. Second sentence done. and a trailing fragment with no terminator"
        assertEquals(expected, formatted)
    }

    @Test
    fun formatParagraphs_consecutiveTerminators_splitIntoTwoSentencesNotThree() {
        // "Wait!!!" must count as ONE sentence (the run of "!" is a single boundary), not three.
        // That is only observable through the paragraph-grouping boundary, since chunking is by
        // sentence count: 5 sentences group as 3 + 2. If "Wait!!!" wrongly split into three
        // sentences at each "!", the total sentence count (7) would shift every paragraph break
        // that follows, and this exact-output assertion would fail.
        val raw = "Wait!!! Really? Yes indeed. Absolutely certain. One more here."
        val formatted = TranscriptFormatter.formatParagraphs(raw)

        val expected = "Wait!!! Really? Yes indeed." +
            "\n\n" +
            "Absolutely certain. One more here."
        assertEquals(expected, formatted)
    }
}
