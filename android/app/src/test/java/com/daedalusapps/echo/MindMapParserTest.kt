package com.daedalusapps.echo

import com.daedalusapps.echo.ui.mindmap.MindMapParser
import org.junit.Assert.*
import org.junit.Test

class MindMapParserTest {

    private fun allLabels(markdown: String): List<String> {
        fun collect(node: com.daedalusapps.echo.ui.mindmap.MindMapNode): List<String> =
            listOf(node.label) + node.children.flatMap { collect(it) }
        return collect(MindMapParser.parse(markdown)).drop(1) // skip synthetic Root
    }

    @Test
    fun `bare brace and bracket labels are filtered`() {
        val md = "- Root\n  - {\n  - }\n  - [\n  - ]\n  - Real node"
        val labels = allLabels(md)
        assertFalse(labels.contains("{"))
        assertFalse(labels.contains("}"))
        assertFalse(labels.contains("["))
        assertFalse(labels.contains("]"))
        assertTrue(labels.contains("Real node"))
    }

    @Test
    fun `pure punctuation labels are filtered`() {
        listOf("{}", "[]", "{ }", "[ ]", ",", "{}[]").forEach { bad ->
            val labels = allLabels("- Root\n  - $bad")
            assertFalse("Expected '$bad' to be filtered", labels.contains(bad))
        }
    }

    @Test
    fun `valid labels containing braces pass through`() {
        val md = "- Root\n  - API {v2}\n  - config: {key}"
        val labels = allLabels(md)
        assertTrue(labels.contains("API {v2}"))
        assertTrue(labels.contains("config: {key}"))
    }

    @Test
    fun `blank labels are still filtered`() {
        val md = "- Root\n  -  \n  - Valid"
        val labels = allLabels(md)
        assertFalse(labels.contains(""))
        assertFalse(labels.contains(" "))
        assertTrue(labels.contains("Valid"))
    }
}
