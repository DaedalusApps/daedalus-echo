package com.daedalusapps.echo.ai

import android.content.Context
import android.util.Log
import com.daedalusapps.echo.data.RecordingRepository

private const val ANALYSIS_TITLE_MAX_LENGTH = 60
private const val ANALYSIS_SUMMARY_MAX_LENGTH = 200
private const val ANALYSIS_FALLBACK_TITLE = "Untitled Recording"

/**
 * Longest transcript handed straight to the JSON prompt. Beyond this, Gemma 3 1B stops
 * following the "return JSON" instruction and echoes the transcript instead, and the parser
 * falls back to slicing a title out of raw text.
 *
 * Measured on-device: 1,674 chars parsed cleanly; 3,935 and everything from 10,681 to 11,937
 * degraded, 5 times out of 5. The same 11,688-char transcript summarized into bullets first
 * then parsed cleanly — and faster, five short generations beating one long one. So anything
 * above this goes through [CHUNK_SUMMARY_PROMPT] first and the JSON step only ever sees bullets.
 */
private const val DIRECT_ANALYSIS_MAX_CHARS = 2_000

/**
 * Ceiling on the budget used for the bullet stage, independent of the user's AI text budget.
 *
 * Bulletizing alone isn't enough: at the 12,000 default a 10,364-char transcript is one chunk,
 * whose bullets came back 4,274 chars long and the JSON step degraded on them anyway. Chunking
 * the same transcript at this budget produced ~700 chars of bullets per chunk (~2,100 total)
 * and parsed cleanly. What matters is how much text the JSON step sees, and chunk size is the
 * lever that controls it.
 */
private const val SUMMARY_CHUNK_BUDGET = 6_000

/**
 * True when the transcript must be summarized into bullets before the JSON step. The budget
 * controls chunk *size*; this controls whether raw transcript is ever fed to the JSON prompt.
 */
internal fun needsBulletSynthesis(transcriptLength: Int, chunkCount: Int): Boolean =
    chunkCount > 1 || transcriptLength > DIRECT_ANALYSIS_MAX_CHARS

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
    val chunks = chunkTranscript(
        transcript,
        minOf(aiTextBudget(context), SUMMARY_CHUNK_BUDGET)
    )
    val rawResponse = if (!needsBulletSynthesis(transcript.length, chunks.size)) {
        onProgress?.invoke("Analyzing with Gemma…")
        llm.generate(activePrompt(context), chunks[0])
    } else {
        // "Section" rather than "part": a split recording's parts are a different thing, and
        // this progress text sits right under a part's own title.
        val chunkSummaries = chunks.mapIndexed { i, chunk ->
            onProgress?.invoke(
                if (chunks.size == 1) "Summarizing…" else "Summarizing section ${i + 1} of ${chunks.size}…"
            )
            llm.generate(CHUNK_SUMMARY_PROMPT, chunk)
        }
        onProgress?.invoke("Synthesizing results…")
        llm.generate(activePrompt(context), chunkSummaries.joinToString("\n\n"))
    }
    val cleanJson = stripCodeFences(rawResponse)
    val parsedAnalysis = SmartAnalysisParser.parse(cleanJson)
    val degradedOrParsed = if (isDegradedAnalysis(parsedAnalysis)) {
        Log.w(
            "DaedalusAI",
            "Analysis parse degraded to blank fallback; deriving title/summary from raw response (rawLength=${rawResponse.length})"
        )
        deriveFallbackAnalysis(parsedAnalysis, transcript)
    } else {
        parsedAnalysis
    }

    // Independent of the degraded-fallback guard above: a partial parse (e.g. title present but
    // shortSummary/mindMap/topics/fullSummary all blank) doesn't trigger that guard, yet still
    // leaves the note with no list preview and excluded from search. Backfill only shortSummary —
    // never title/topics/mindMap/summary — from fullSummary when available, else the transcript.
    // No-op when shortSummary already has content (#67).
    val analysis = if (degradedOrParsed.shortSummary.isBlank()) {
        val source = degradedOrParsed.fullSummary.ifBlank { transcript }
        degradedOrParsed.copy(shortSummary = deriveFallbackShortSummary(source))
    } else {
        degradedOrParsed
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
    // First line with content *after* cleaning, not merely the first non-blank one: a response
    // opening with a bare "-" or "###" would otherwise title the note from that marker alone.
    val cleaned = source.lineSequence()
        .map { line ->
            // trim() first, as the pre-#64 code did: it also strips non-breaking/unicode
            // spaces, which trimStart (only ' ' and tab) would otherwise leave in front of
            // the bullet, stopping the marker from being stripped.
            line.trim()
                .trimStart('-', '*', '#', ' ', '\t')
                .trim('"', '\'', '“', '”', '‘', '’', ' ')
                .replace(Regex("\\s+"), " ")
                .trim()
        }
        .firstOrNull { it.isNotBlank() } ?: return ANALYSIS_FALLBACK_TITLE
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
