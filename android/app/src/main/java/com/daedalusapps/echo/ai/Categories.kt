package com.daedalusapps.echo.ai

import android.content.Context

// Transcripts longer than this are split into chunks before LLM analysis.
// Budget: 4096 total tokens − ~135 prompt − ~800 output = ~3160 tokens ≈ 12,600 chars.
private const val SINGLE_PASS_CHAR_LIMIT = 12_000
private const val CHUNK_CHAR_SIZE = 10_000
private const val CHUNK_OVERLAP_CHARS = 500

const val CHUNK_SUMMARY_PROMPT = """Summarize this section of a meeting transcript as concise bullet points.

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

const val DEFAULT_PROMPT = """Read the voice recording transcript below and extract its contents.

Return ONLY a JSON object with exactly these 5 keys. No markdown, no code fences.

- "title": a specific title of up to 8 words describing what was actually discussed
- "shortSummary": one sentence summarizing what the speaker said, using their specific words
- "topics": JSON array of 3-5 keywords from the transcript
- "mindMap": bullet list of main points from the transcript, each on its own line starting with "- ", sub-points starting with "  - "
- "fullSummary": 2-3 sentences describing what was discussed, using specific subjects from the transcript

Transcript:"""

fun activePrompt(context: Context): String =
    context.getSharedPreferences("daedalus_prefs", Context.MODE_PRIVATE)
        .getString("custom_prompt", null) ?: DEFAULT_PROMPT

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
