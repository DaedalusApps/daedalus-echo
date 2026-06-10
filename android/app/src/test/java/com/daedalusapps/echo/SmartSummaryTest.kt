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
