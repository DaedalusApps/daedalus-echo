package com.daedalusapps.echo.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Regression coverage for MEDIUM-1: global search (RecordingDao.searchFtsFlow) matches against
 * the raw stored transcript, but the in-note view renders TranscriptFormatter.formatParagraphs()
 * output, which can rejoin a search hit's whitespace into "\n\n" at a paragraph boundary. If
 * highlighting only did exact substring matching, a hit found by global search could silently
 * fail to highlight once the note is opened.
 */
class NoteDetailHighlightTest {

    @Test
    fun findWhitespaceInsensitiveMatch_matchesAcrossParagraphBreak() {
        // "done. Next" spans a single-space boundary in the raw transcript that
        // TranscriptFormatter turns into "done.\n\nNext" (a paragraph break).
        val formatted = "Almost done.\n\nNext topic is budget."
        val text = formatted.lowercase()
        val query = "done. next"

        val match = findWhitespaceInsensitiveMatch(text, query, 0)

        requireNotNull(match)
        val (start, end) = match
        // Offsets must land on the actual formatted string, including the "\n\n".
        assertEquals("done.\n\nNext", formatted.substring(start, end))
    }

    @Test
    fun findWhitespaceInsensitiveMatch_ordinaryQuery_stillMatchesExactly() {
        val text = "hello world, this is a test".lowercase()
        val query = "this is a test"

        val match = findWhitespaceInsensitiveMatch(text, query, 0)

        requireNotNull(match)
        val (start, end) = match
        assertEquals("this is a test", text.substring(start, end))
    }

    @Test
    fun findWhitespaceInsensitiveMatch_noMatch_returnsNull() {
        val text = "hello world".lowercase()
        val query = "goodbye"

        assertNull(findWhitespaceInsensitiveMatch(text, query, 0))
    }

    @Test
    fun highlightMatches_queryAcrossParagraphBreak_highlightsCorrectSpan() {
        val formatted = "Almost done.\n\nNext topic is budget."
        val query = "done. Next"

        val result = highlightMatches(formatted, query)

        assertEquals(formatted, result.text)
        assertEquals(1, result.spanStyles.size)
        val span = result.spanStyles.single()
        assertEquals("done.\n\nNext", result.text.substring(span.start, span.end))
    }

    @Test
    fun highlightMatches_ordinaryQuery_highlightsCorrectSpan() {
        val text = "The quick brown fox jumps over the lazy dog."
        val query = "brown fox"

        val result = highlightMatches(text, query)

        assertEquals(text, result.text)
        assertEquals(1, result.spanStyles.size)
        val span = result.spanStyles.single()
        assertEquals("brown fox", result.text.substring(span.start, span.end))
    }
}
