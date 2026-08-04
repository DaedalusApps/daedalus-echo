package com.daedalusapps.echo.ai

import android.content.Context
import com.daedalusapps.echo.data.RecordingRepository

/**
 * Runs the Gemma summarize/mind-map analysis plus embedding generation against an already-known
 * transcript and saves the result via [repo]. Extracted from RecordingViewModel.doAnalyze so this
 * post-transcript pipeline is defined exactly once.
 */
suspend fun analyzeTranscript(
    context: Context,
    llm: LocalLlmService,
    embedder: EmbeddingService,
    repo: RecordingRepository,
    filename: String,
    transcript: String
) {
    llm.ensureLoaded()
    val chunks = chunkTranscript(transcript, aiTextBudget(context))
    val rawResponse = if (chunks.size == 1) {
        llm.generate(activePrompt(context), chunks[0])
    } else {
        val chunkSummaries = chunks.map { chunk ->
            llm.generate(CHUNK_SUMMARY_PROMPT, chunk)
        }
        llm.generate(activePrompt(context), chunkSummaries.joinToString("\n\n"))
    }
    val cleanJson = stripCodeFences(rawResponse)
    val analysis = SmartAnalysisParser.parse(cleanJson)

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
