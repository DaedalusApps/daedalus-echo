package com.daedalusapps.echo.ai

import android.content.Context
import android.util.Log
import com.daedalusapps.echo.data.RecordingRepository

private const val ANALYSIS_TITLE_MAX_LENGTH = 60
private const val ANALYSIS_SUMMARY_MAX_LENGTH = 200
private const val ANALYSIS_FALLBACK_TITLE = "Untitled Recording"

/**
 * Runs the Gemma summarize/mind-map analysis plus embedding generation against an already-known
 * transcript and saves the result via [repo]. Extracted from RecordingViewModel.doAnalyze so this
 * post-transcript pipeline is defined exactly once.
 *
 * [onProgress] receives the user-facing stage labels a caller with a progress indicator can show;
 * chunked transcripts report per-chunk progress because they take minutes.
 */
suspend fun analyzeTranscript(
    context: Context,
    llm: LocalLlmService,
    embedder: EmbeddingService,
    repo: RecordingRepository,
    filename: String,
    transcript: String,
    onProgress: ((String) -> Unit)? = null
) {
    llm.ensureLoaded()
    val chunks = chunkTranscript(transcript, aiTextBudget(context))
    val rawResponse = if (chunks.size == 1) {
        onProgress?.invoke("Analyzing with Gemma…")
        llm.generate(activePrompt(context), chunks[0])
    } else {
        val chunkSummaries = chunks.mapIndexed { i, chunk ->
            onProgress?.invoke("Analyzing part ${i + 1} of ${chunks.size}…")
            llm.generate(CHUNK_SUMMARY_PROMPT, chunk)
        }
        onProgress?.invoke("Synthesizing results…")
        llm.generate(activePrompt(context), chunkSummaries.joinToString("\n\n"))
    }
    val cleanJson = stripCodeFences(rawResponse)
    val parsedAnalysis = SmartAnalysisParser.parse(cleanJson)
    val analysis = if (isDegradedAnalysis(parsedAnalysis)) {
        Log.w(
            "DaedalusAI",
            "Analysis parse degraded to blank fallback; deriving title/summary from raw response (rawLength=${rawResponse.length})"
        )
        deriveFallbackAnalysis(parsedAnalysis, transcript)
    } else {
        parsedAnalysis
    }

    val fullSummaryFinal = if ("## Action Items" !in analysis.fullSummary) {
        val items = extractActionItems(transcript)
        if (items.isNotEmpty()) {
            analysis.fullSummary.trimEnd() + "\n\n## Action Items\n" +
                items.joinToString("\n") { "- [ ] $it" }
        } else {
            analysis.fullSummary
        }
    } else {
        analysis.fullSummary
    }

    repo.updateSummary(
        filename = filename,
        summary = fullSummaryFinal,
        mindMap = analysis.mindMap,
        title = analysis.title,
        shortSummary = analysis.shortSummary,
        topics = analysis.topics
    )

    if (embedder.isReady) {
        embedder.ensureLoaded()
        val embText = "${analysis.shortSummary} ${analysis.topics.joinToString(" ")}"
        embedder.embed(embText)?.let { repo.updateEmbedding(filename, it) }
    }
}

// The model sometimes ignores the JSON/markdown instruction and answers in free-form prose, which
// SmartAnalysisParser can't extract any known field from; it correctly reports that failure via a
// blank SmartAnalysis(fullSummary = rawResponse). Persisting that as-is leaves the user with a note
// that has no title and no preview, so we derive usable title/shortSummary from whatever text is
// available. topics/mindMap are deliberately left empty rather than fabricated.
private fun isDegradedAnalysis(analysis: SmartAnalysis): Boolean =
    analysis.title.isBlank() &&
        analysis.shortSummary.isBlank() &&
        analysis.mindMap.isBlank() &&
        analysis.topics.isEmpty()

private fun deriveFallbackAnalysis(analysis: SmartAnalysis, transcript: String): SmartAnalysis {
    val source = analysis.fullSummary.ifBlank { transcript }
    val title = deriveFallbackTitle(source)
    return analysis.copy(
        title = title,
        // Degenerate sources (empty, or nothing but punctuation) truncate away to nothing, which
        // would leave the note without a preview again; the title is always non-blank.
        shortSummary = deriveFallbackShortSummary(source).ifBlank { title }
    )
}

private fun deriveFallbackTitle(source: String): String {
    val firstLine = source.lineSequence().map { it.trim() }.firstOrNull { it.isNotBlank() } ?: ""
    val cleaned = firstLine
        .trimStart('-', '*', '#', ' ', '\t')
        .trim('"', '\'', '“', '”', '‘', '’', ' ')
        .replace(Regex("\\s+"), " ")
        .trim()
    if (cleaned.isBlank()) return ANALYSIS_FALLBACK_TITLE
    return truncateAnalysisTextAtWordBoundary(cleaned, ANALYSIS_TITLE_MAX_LENGTH)
        .ifBlank { ANALYSIS_FALLBACK_TITLE }
}

private fun deriveFallbackShortSummary(source: String): String {
    val collapsed = source.replace(Regex("\\s+"), " ").trim()
    return truncateAnalysisTextAtWordBoundary(collapsed, ANALYSIS_SUMMARY_MAX_LENGTH)
}

private fun truncateAnalysisTextAtWordBoundary(text: String, maxLength: Int): String {
    if (text.length <= maxLength) return text
    // Don't cut between the two halves of a surrogate pair (emoji), which renders as a stray "?".
    val end = if (Character.isHighSurrogate(text[maxLength - 1])) maxLength - 1 else maxLength
    val cut = text.substring(0, end)
    val lastSpace = cut.lastIndexOf(' ')
    val trimmed = if (lastSpace > 0) cut.substring(0, lastSpace) else cut
    return trimmed.trimEnd('.', ',', ';', ':', '-', ' ')
}
