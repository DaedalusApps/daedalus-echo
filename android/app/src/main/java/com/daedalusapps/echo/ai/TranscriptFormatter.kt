package com.daedalusapps.echo.ai

object TranscriptFormatter {

    /**
     * Breaks a wall of raw transcript text into readable, blank-line-separated paragraphs.
     *
     * This does display-time formatting only: it groups sentences by sentence-boundary
     * punctuation. It has no access to audio, so it makes no claim about who said what - it does
     * not label or attribute speakers.
     */
    fun formatParagraphs(rawTranscript: String): String {
        if (rawTranscript.isBlank()) return ""

        val sentences = rawTranscript.split(Regex("(?<=[.!?])\\s+")).filter { it.isNotBlank() }
        if (sentences.isEmpty()) return rawTranscript.trim()

        val sentencesPerParagraph = 3
        return sentences
            .map { it.trim() }
            .chunked(sentencesPerParagraph)
            .joinToString("\n\n") { it.joinToString(" ") }
    }
}
