package com.daedalus.echo.ai

import com.daedalus.echo.data.model.Recording

object MarkdownExporter {

    fun export(recording: Recording): String = buildString {
        appendLine("# ${recording.filename}")
        if (recording.transcript.isNotBlank()) {
            appendLine()
            appendLine("## Transcript")
            appendLine(recording.transcript)
        }
        if (recording.summary.isNotBlank()) {
            appendLine()
            appendLine("## Summary")
            appendLine(recording.summary)
        }
        if (recording.mindMap.isNotBlank()) {
            appendLine()
            appendLine("## Mind Map")
            appendLine(recording.mindMap)
        }
    }

    fun exportQa(question: String, answer: String, sources: List<Recording>): String = buildString {
        appendLine("# Ask: $question")
        appendLine()
        appendLine(answer)
        if (sources.isNotEmpty()) {
            appendLine()
            appendLine("## Sources")
            sources.forEach { appendLine("- ${it.title.ifBlank { it.filename }}") }
        }
    }
}
