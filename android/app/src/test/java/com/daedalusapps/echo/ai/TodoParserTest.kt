package com.daedalusapps.echo.ai

import org.junit.Assert.assertEquals
import org.junit.Test

class TodoParserTest {

    @Test
    fun parseTodoLines_dashBullet() {
        assertEquals(listOf("Buy milk"), parseTodoLines("- Buy milk"))
    }

    @Test
    fun parseTodoLines_starBullet() {
        assertEquals(listOf("Buy milk"), parseTodoLines("* Buy milk"))
    }

    @Test
    fun parseTodoLines_dotBullet() {
        assertEquals(listOf("Buy milk"), parseTodoLines("• Buy milk"))
    }

    @Test
    fun parseTodoLines_numberedDotBullet() {
        assertEquals(listOf("Buy milk"), parseTodoLines("1. Buy milk"))
    }

    @Test
    fun parseTodoLines_numberedParenBullet() {
        assertEquals(listOf("Buy milk"), parseTodoLines("2) Buy milk"))
    }

    @Test
    fun parseTodoLines_checkboxEmpty() {
        assertEquals(listOf("Buy milk"), parseTodoLines("- [ ] Buy milk"))
    }

    @Test
    fun parseTodoLines_checkboxChecked() {
        assertEquals(listOf("Buy milk"), parseTodoLines("- [x] Buy milk"))
    }

    @Test
    fun parseTodoLines_codeFencedOutput() {
        val raw = "```\n- Buy milk\n- Walk dog\n```"
        assertEquals(listOf("Buy milk", "Walk dog"), parseTodoLines(raw))
    }

    @Test
    fun parseTodoLines_codeFencedWithLanguageTag() {
        val raw = "```markdown\n- Buy milk\n```"
        assertEquals(listOf("Buy milk"), parseTodoLines(raw))
    }

    @Test
    fun parseTodoLines_proseOnlyResponse_returnsEmpty() {
        val raw = "There are no action items to extract from this transcript."
        assertEquals(emptyList<String>(), parseTodoLines(raw))
    }

    @Test
    fun parseTodoLines_noneBullet_returnsEmpty() {
        assertEquals(emptyList<String>(), parseTodoLines("- none"))
    }

    @Test
    fun parseTodoLines_noneBullet_caseInsensitive() {
        assertEquals(emptyList<String>(), parseTodoLines("- None"))
    }

    @Test
    fun parseTodoLines_nonePeriod_returnsEmpty() {
        assertEquals(emptyList<String>(), parseTodoLines("- None."))
    }

    @Test
    fun parseTodoLines_noNewTasks_returnsEmpty() {
        assertEquals(emptyList<String>(), parseTodoLines("- No new tasks."))
    }

    @Test
    fun parseTodoLines_realTaskContainingNoneMidSentence_stillParses() {
        assertEquals(
            listOf("Leave none of the boxes unpacked"),
            parseTodoLines("- Leave none of the boxes unpacked")
        )
    }

    @Test
    fun parseTodoLines_cappedAtTen() {
        val raw = (1..15).joinToString("\n") { "- Task number $it is here" }
        assertEquals(10, parseTodoLines(raw).size)
    }

    @Test
    fun parseTodoLines_punctuationOnlyBullet_dropped() {
        // Normalizes to empty -> must not become a garbage todo
        assertEquals(emptyList<String>(), parseTodoLines("- ----\n- ???"))
    }

    @Test
    fun parseTodoLines_dropsTooShortItems() {
        // normalized item text "ok" is 2 chars, below the 3-char minimum
        assertEquals(emptyList<String>(), parseTodoLines("- ok"))
    }

    @Test
    fun parseTodoLines_dropsTooLongItems() {
        val longItem = "a".repeat(201)
        assertEquals(emptyList<String>(), parseTodoLines("- $longItem"))
    }

    @Test
    fun parseTodoLines_keepsItemAtLengthBoundaries() {
        val shortest = "abc" // 3 chars
        val longest = "a".repeat(200) // 200 chars
        assertEquals(listOf(shortest, longest), parseTodoLines("- $shortest\n- $longest"))
    }

    @Test
    fun parseTodoLines_trimsSurroundingWhitespace() {
        assertEquals(listOf("Buy milk"), parseTodoLines("-   Buy milk   "))
    }

    @Test
    fun parseTodoLines_multipleMixedBulletStyles() {
        val raw = """
            - Buy milk
            * Walk dog
            1. Call mom
        """.trimIndent()
        assertEquals(listOf("Buy milk", "Walk dog", "Call mom"), parseTodoLines(raw))
    }
}
