package com.daedalus.voicenotes

import com.daedalus.voicenotes.ai.SmartAnalysisParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartAnalysisParserTest {

    @Test
    fun parse_extractsTitle() {
        val input = """{"title": "My Title", "shortSummary": "Summary"}"""
        val result = SmartAnalysisParser.parse(input)
        assertEquals("My Title", result.title)
    }

    @Test
    fun parse_extractsTopics() {
        val input = """{"topics": ["AI", "Kotlin", "Testing"]}"""
        val result = SmartAnalysisParser.parse(input)
        assertEquals(listOf("AI", "Kotlin", "Testing"), result.topics)
    }

    @Test
    fun parse_bulletMindMap_passedThrough() {
        val mindMap = "- Point 1\n- Point 2"
        val input = """{"mindMap": "$mindMap"}"""
        val result = SmartAnalysisParser.parse(input)
        assertEquals(mindMap, result.mindMap)
    }

    @Test
    fun parse_jsonObjectMindMap_convertsToBullets() {
        val input = """{
            "mindMap": [
                {"item": "Core Idea", "sub": "Detail 1"},
                {"point": "Secondary"}
            ]
        }"""
        val result = SmartAnalysisParser.parse(input)
        assertTrue(result.mindMap.contains("- Core Idea"))
        assertTrue(result.mindMap.contains("- Detail 1"))
        assertTrue(result.mindMap.contains("- Secondary"))
    }

    @Test
    fun parse_jsonStringArrayMindMap_convertsToBullets() {
        val input = """{"mindMap": ["Point A", "Point B"]}"""
        val result = SmartAnalysisParser.parse(input)
        assertEquals("Point A\nPoint B", result.mindMap)
    }

    @Test
    fun parse_malformedInput_returnsRawFallback() {
        val input = "This is not JSON at all."
        val result = SmartAnalysisParser.parse(input)
        assertEquals(input, result.fullSummary)
        assertTrue(result.title.isEmpty())
    }
}
