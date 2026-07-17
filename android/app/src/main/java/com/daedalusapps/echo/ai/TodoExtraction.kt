package com.daedalusapps.echo.ai

const val TODO_EXTRACTION_PROMPT = """From the notes below, extract action items: things the SPEAKER commits to do or says they must do in the future.

NOT action items: narration of what the speaker is currently doing, comments about the recording or app itself, past events, opinions.

Examples:
Notes: "I need to pick up dry cleaning and call the dentist to reschedule."
- Pick up dry cleaning
- Call dentist to reschedule

Notes: "This is me testing the app, reading output from the agent to check transcription quality."
- none

Notes: "Just narrating my day here. Also remember to pay the electric bill before Friday."
- Pay electric bill before Friday

Return ONLY a bullet list, one task per line starting with "- ". Each task must be short (under 15 words), specific, and actionable. Do not repeat tasks from the "Already tracked" list. If there are no new tasks, return exactly "- none".

Notes:"""

private const val MIN_TODO_LENGTH = 3
private const val MAX_TODO_LENGTH = 200
private const val MAX_TODO_COUNT = 10

private val BULLET_LINE_REGEX = Regex("""^\s*(?:[-*•]|\d+[.)])\s*(?:\[[ xX]?\]\s*)?(.+)""")

private val NONE_SENTINELS = setOf("none", "no new tasks")

fun stripCodeFences(text: String): String {
    // Gemma sometimes wraps output in ```json ... ``` fences — strip them
    return text.trim()
        .removePrefix("```json").removePrefix("```")
        .removeSuffix("```").trim()
}

fun parseTodoLines(raw: String): List<String> {
    val cleaned = stripCodeFences(raw)
    return cleaned.lines()
        .mapNotNull { line -> BULLET_LINE_REGEX.matchEntire(line)?.groupValues?.get(1)?.trim() }
        .filter { item ->
            // Non-empty normalized text also drops punctuation-only bullets like "- ----"
            val norm = normalizeTodoText(item)
            norm.isNotEmpty() && norm !in NONE_SENTINELS
        }
        .filter { it.length in MIN_TODO_LENGTH..MAX_TODO_LENGTH }
        .take(MAX_TODO_COUNT)
}

fun normalizeTodoText(s: String): String =
    s.lowercase()
        .replace(Regex("[^a-z0-9\\s]"), "")
        .replace(Regex("\\s+"), " ")
        .trim()

private const val MIN_CONTAINMENT_LENGTH = 8

private val DEDUP_STOPWORDS = setOf(
    "the", "a", "an", "to", "for", "of", "about", "regarding", "re", "your", "my", "our",
    "his", "her", "their", "on", "in", "at", "with", "and"
)

fun isDuplicateTodo(candidate: String, existing: Collection<String>): Boolean {
    val norm = normalizeTodoText(candidate)
    if (norm.isEmpty()) return false
    return isDuplicateTodoNormalized(norm, existing.map { normalizeTodoText(it) })
}

/**
 * Same dedup rule as [isDuplicateTodo], operating on already-normalized strings so callers
 * that maintain a running normalized list don't have to re-normalize on every check.
 * Exact normalized equality always counts as a duplicate. Containment (either direction)
 * only counts when the SHORTER of the two normalized strings is at least
 * [MIN_CONTAINMENT_LENGTH] chars, so short todos like "buy" or "call" don't suppress every
 * longer todo that happens to contain them. A third rule catches paraphrases: if the
 * stopword-filtered word sets of both strings are non-empty and exactly equal (again guarded
 * by [MIN_CONTAINMENT_LENGTH] on the shorter normalized string), they count as a duplicate.
 * Subset relationships are deliberately NOT treated as duplicates here — e.g. "call dad" is
 * not a duplicate of "call mom and dad" — since that would over-suppress distinct todos that
 * merely share some words against the full persistent todo history.
 */
internal fun isDuplicateTodoNormalized(candidateNorm: String, trackedNorms: List<String>): Boolean {
    if (candidateNorm.isEmpty()) return false
    val candidateWords = candidateNorm.split(" ").filter { it.isNotEmpty() && it !in DEDUP_STOPWORDS }.toSet()
    return trackedNorms.any { existingNorm ->
        if (existingNorm.isEmpty()) return@any false
        if (existingNorm == candidateNorm) return@any true
        val shorterLength = minOf(existingNorm.length, candidateNorm.length)
        if (shorterLength >= MIN_CONTAINMENT_LENGTH &&
            (existingNorm.contains(candidateNorm) || candidateNorm.contains(existingNorm))
        ) {
            return@any true
        }
        val existingWords = existingNorm.split(" ").filter { it.isNotEmpty() && it !in DEDUP_STOPWORDS }.toSet()
        if (candidateWords.isEmpty() || existingWords.isEmpty()) return@any false
        candidateWords == existingWords && shorterLength >= MIN_CONTAINMENT_LENGTH
    }
}
