package com.daedalusapps.echo

import com.daedalusapps.echo.ai.MarkdownExporter
import com.daedalusapps.echo.data.model.Recording
import org.junit.Assert.*
import org.junit.Test

class MarkdownExporterTest {

    private val full = Recording(
        filename   = "20260524213434.mp3",
        transcript = "Hello world this is a test.",
        summary    = "Some summary text.",
        mindMap    = "- Topic\n  - Branch"
    )

    @Test
    fun export_fullRecording_containsAllSections() {
        val md = MarkdownExporter.export(full)
        assertTrue(md.contains("## Transcript"))
        assertTrue(md.contains("## Summary"))
        assertTrue(md.contains("## Mind Map"))
    }

    @Test
    fun export_fullRecording_containsContent() {
        val md = MarkdownExporter.export(full)
        assertTrue(md.contains("Hello world this is a test."))
        assertTrue(md.contains("Some summary text."))
        assertTrue(md.contains("- Topic"))
    }

    @Test
    fun export_filename_usedAsTitle() {
        val md = MarkdownExporter.export(full)
        assertTrue(md.startsWith("# 20260524213434.mp3"))
    }

    @Test
    fun export_emptySummary_skipsSection() {
        val rec = full.copy(summary = "")
        val md = MarkdownExporter.export(rec)
        assertFalse(md.contains("## Summary"))
        assertTrue(md.contains("## Transcript"))
        assertTrue(md.contains("## Mind Map"))
    }

    @Test
    fun export_emptyTranscript_skipsSection() {
        val rec = full.copy(transcript = "")
        val md = MarkdownExporter.export(rec)
        assertFalse(md.contains("## Transcript"))
    }

    @Test
    fun export_emptyMindMap_skipsSection() {
        val rec = full.copy(mindMap = "")
        val md = MarkdownExporter.export(rec)
        assertFalse(md.contains("## Mind Map"))
    }

    @Test
    fun export_allEmpty_onlyTitle() {
        val rec = Recording(filename = "test.mp3")
        val md = MarkdownExporter.export(rec)
        assertTrue(md.contains("# test.mp3"))
        assertFalse(md.contains("##"))
    }

    @Test
    fun exportQa_containsQuestionAnswerAndSources() {
        val sources = listOf(
            Recording(filename = "note1.mp3", title = "Note One"),
            Recording(filename = "note2.mp3", title = "")
        )
        val md = MarkdownExporter.exportQa("What is AI?", "AI is cool.", sources)
        
        assertTrue(md.contains("# Ask: What is AI?"))
        assertTrue(md.contains("AI is cool."))
        assertTrue(md.contains("## Sources"))
        assertTrue(md.contains("- Note One"))
        assertTrue(md.contains("- note2.mp3"))
    }
}
