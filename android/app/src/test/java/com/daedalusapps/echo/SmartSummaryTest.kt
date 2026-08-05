package com.daedalusapps.echo

import com.daedalusapps.echo.ai.SmartAnalysis
import com.daedalusapps.echo.ai.SmartAnalysisParser
import com.daedalusapps.echo.data.model.DateUtils
import org.junit.Assert.*
import org.junit.Test

class SmartSummaryTest {

    @Test
    fun dateUtils_parseStandardFilename_returnsFormattedDate() {
        val filename = "20260524213434.mp3"
        val expected = "2026-05-24 21:34:34"
        val actual = DateUtils.parseDateFromFilename(filename)
        assertEquals(expected, actual)
    }

    @Test
    fun dateUtils_parseInvalidFilename_returnsOriginal() {
        val filename = "random_recording.mp3"
        val actual = DateUtils.parseDateFromFilename(filename)
        assertEquals(filename, actual)
    }

    // (#31 / ED.6) Conversation notes (as opposed to audio recordings) get a "Conversation" chip
    // on the library card instead of a duration.
    @Test
    fun isConversationNote_conversationFilename_returnsTrue() {
        assertTrue(DateUtils.isConversationNote("conv_20260804080519.md"))
    }

    @Test
    fun isConversationNote_endedConversationFilename_returnsTrue() {
        assertTrue(DateUtils.isConversationNote("conv_20260804080519.ended.md"))
    }

    @Test
    fun isConversationNote_audioFilename_returnsFalse() {
        assertFalse(DateUtils.isConversationNote("20260804080519.mp3"))
    }

    @Test
    fun isConversationNote_arbitraryFilename_returnsFalse() {
        assertFalse(DateUtils.isConversationNote("recording.m4a"))
    }

    @Test
    fun isConversationNote_emptyFilename_returnsFalse() {
        assertFalse(DateUtils.isConversationNote(""))
    }

    /** An audio file imported via syncFiles keeps its own name, which may start with "conv_". */
    @Test
    fun isConversationNote_importedAudioNamedLikeConversation_returnsFalse() {
        assertFalse(DateUtils.isConversationNote("conv_meeting.mp3"))
        assertFalse(DateUtils.isConversationNote("conv_20260804080519.mp3"))
    }

    @Test
    fun smartAnalysisParser_validJson_returnsAnalysis() {
        val json = """
            {
              "title": "Project Meeting",
              "shortSummary": "Discussed the new mind map feature.",
              "topics": ["MindMap", "Android", "TDD"],
              "fullSummary": "Detailed notes here..."
            }
        """.trimIndent()
        
        val analysis = SmartAnalysisParser.parse(json)
        
        assertEquals("Project Meeting", analysis.title)
        assertEquals("Discussed the new mind map feature.", analysis.shortSummary)
        assertEquals(listOf("MindMap", "Android", "TDD"), analysis.topics)
        assertEquals("Detailed notes here...", analysis.fullSummary)
    }

    @Test
    fun smartAnalysisParser_malformedJson_returnsEmptyFallback() {
        val json = "invalid json"
        val analysis = SmartAnalysisParser.parse(json)
        
        assertEquals("", analysis.title)
        assertEquals("", analysis.shortSummary)
        assertTrue(analysis.topics.isEmpty())
        assertTrue(analysis.fullSummary.contains("invalid json"))
    }
}
