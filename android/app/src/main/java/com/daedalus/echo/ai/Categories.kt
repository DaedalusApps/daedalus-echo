package com.daedalus.echo.ai

import android.content.Context

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
