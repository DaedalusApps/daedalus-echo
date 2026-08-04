package com.daedalusapps.echo.ai

import android.content.Context
import com.daedalusapps.echo.data.model.Recording

const val OFFLINE_GUARDRAIL = "Note: you are an offline assistant. You have no internet access " +
    "and cannot search the web or fetch current information. Your built-in knowledge may be " +
    "outdated. Base your answers on the provided notes and conversation."

// Transcripts longer than this are split into chunks before LLM analysis.
// Budget: 4096 total tokens − ~135 prompt − ~800 output = ~3160 tokens ≈ 12,600 chars.
private const val SINGLE_PASS_CHAR_LIMIT = 12_000
private const val CHUNK_CHAR_SIZE = 10_000
private const val CHUNK_OVERLAP_CHARS = 500

const val CHUNK_SUMMARY_PROMPT = OFFLINE_GUARDRAIL + "\n\n" + """Summarize this section of a meeting transcript as concise bullet points.

Return ONLY bullet points: main points starting with "- ", sub-points with "  - ". Include any action items. No JSON, no headers, no preamble.

Section:"""

fun chunkTranscript(transcript: String): List<String> {
    if (transcript.length <= SINGLE_PASS_CHAR_LIMIT) return listOf(transcript)
    val chunks = mutableListOf<String>()
    var start = 0
    while (start < transcript.length) {
        val end = minOf(start + CHUNK_CHAR_SIZE, transcript.length)
        val splitAt = if (end < transcript.length) {
            val wb = transcript.lastIndexOf(' ', end)
            if (wb > start + CHUNK_CHAR_SIZE / 2) wb else end
        } else end
        chunks.add(transcript.substring(start, splitAt))
        if (splitAt >= transcript.length) break
        val overlapStart = transcript.indexOf(' ', maxOf(start + 1, splitAt - CHUNK_OVERLAP_CHARS))
        start = if (overlapStart in (start + 1) until splitAt) overlapStart + 1 else splitAt
    }
    return chunks
}

const val DEFAULT_PROMPT = OFFLINE_GUARDRAIL + "\n\n" + """Read the voice recording transcript below and extract its contents.

Return ONLY a JSON object with exactly these 5 keys. No markdown, no code fences.

- "title": a specific title of up to 8 words describing what was actually discussed
- "shortSummary": one sentence summarizing what the speaker said, using their specific words
- "topics": JSON array of 3-5 keywords from the transcript
- "mindMap": bullet list of main points from the transcript, each on its own line starting with "- ", sub-points starting with "  - "
- "fullSummary": 2-3 sentences describing what was discussed, using specific subjects from the transcript

Transcript:"""

fun activePrompt(context: Context): String {
    val custom = context.getSharedPreferences("daedalus_prefs", Context.MODE_PRIVATE)
        .getString("custom_prompt", null) ?: return DEFAULT_PROMPT
    // A saved custom prompt is usually an edit of DEFAULT_PROMPT, so it already carries the
    // guardrail — appending unconditionally would repeat it.
    return if (custom.contains(OFFLINE_GUARDRAIL)) custom else "$custom\n\n$OFFLINE_GUARDRAIL"
}

fun buildNoteQuestionPrompt(title: String, summary: String): String =
    "You are answering a question about a specific note. " +
        "Note title: $title. " +
        "Note summary: $summary. " +
        "Answer concisely based only on the note content. " +
        "If the answer is not in the note, say so clearly.\n\n$OFFLINE_GUARDRAIL"

fun buildLibraryQuestionPrompt(sources: List<Recording>): String = buildString {
    append("Answer the question using the notes below. ")
    append("Cite note titles when relevant. ")
    append("If the answer is not in the notes, say so.\n\n")
    sources.forEachIndexed { i, r ->
        append("Note ${i + 1}: ${r.title.ifBlank { r.filename }}\n")
        append(r.shortSummary.ifBlank { r.summary.take(200) })
        append("\n\n")
    }
    append(OFFLINE_GUARDRAIL)
}

private val ACTION_PHRASES = listOf(
    "to do", "todo", "need to", "should ", "want to", "going to",
    "remember", "remind", "add ", "create ", "implement", "aggregate",
    "extract", "figure out", "look into", "try to", "have to"
)

fun extractActionItems(transcript: String): List<String> {
    val lower = transcript.lowercase()
    if (ACTION_PHRASES.none { lower.contains(it) }) return emptyList()

    return transcript
        .split(Regex("[,.]\\s+|\\s+(?:and then|but then|also|so |then )"))
        .map { it.trim() }
        .filter { chunk ->
            chunk.length in 15..120 &&
            ACTION_PHRASES.any { chunk.lowercase().contains(it) }
        }
        .map { it.replaceFirstChar { c -> c.uppercaseChar() }.trimEnd('.', ',') }
        .distinctBy { it.lowercase().take(25) }
        .take(5)
}
