package com.daedalusapps.echo.ai

import android.content.Context
import com.daedalusapps.echo.data.model.Recording

const val OFFLINE_GUARDRAIL = "Note: you are an offline assistant. You have no internet access " +
    "and cannot search the web or fetch current information. Your built-in knowledge may be " +
    "outdated. Base your answers on the provided notes and conversation."

const val AI_TEXT_BUDGET_KEY = "ai_text_budget_chars"
const val MEETING_GLOSSARY_KEY = "meeting_glossary"
// Text longer than the budget is split into chunks before LLM analysis.
// Default: 4096 total tokens − ~135 prompt − ~800 output = ~3160 tokens ≈ 12,600 chars.
const val AI_TEXT_BUDGET_DEFAULT = 12_000
private const val AI_TEXT_BUDGET_MIN = 2_000
// Ceiling keeps derived values (chunk size, todo batch = budget * 3 / 4) clear of Int overflow
// when a restored backup carries an out-of-range value.
private const val AI_TEXT_BUDGET_MAX = 100_000
// Headroom subtracted from the budget so a chunk plus its prompt still fits.
private const val CHUNK_HEADROOM_CHARS = 2_000
private const val CHUNK_OVERLAP_CHARS = 500

/** Reads the configured AI text budget (chars), clamped to a sane range. */
fun aiTextBudget(context: Context): Int =
    context.getSharedPreferences("daedalus_prefs", Context.MODE_PRIVATE)
        .getInt(AI_TEXT_BUDGET_KEY, AI_TEXT_BUDGET_DEFAULT)
        .coerceIn(AI_TEXT_BUDGET_MIN, AI_TEXT_BUDGET_MAX)

const val CHUNK_SUMMARY_PROMPT = OFFLINE_GUARDRAIL + "\n\n" + """Riassumi questa sezione della trascrizione di una riunione in punti concisi, in italiano.

Restituisci SOLO punti elenco: punti principali con "- ", sotto-punti con "  - ". Includi decisioni, rischi, scadenze e azioni. Non inventare persone, ruoli o assegnatari. Nessun JSON, titolo o preambolo.

Section:"""

fun chunkTranscript(transcript: String, budget: Int = AI_TEXT_BUDGET_DEFAULT): List<String> {
    if (transcript.length <= budget) return listOf(transcript)
    val chunkSize = (budget - CHUNK_HEADROOM_CHARS).coerceAtLeast(1_000)
    val chunks = mutableListOf<String>()
    var start = 0
    while (start < transcript.length) {
        val end = minOf(start + chunkSize, transcript.length)
        val splitAt = if (end < transcript.length) {
            val wb = transcript.lastIndexOf(' ', end)
            if (wb > start + chunkSize / 2) wb else end
        } else end
        chunks.add(transcript.substring(start, splitAt))
        if (splitAt >= transcript.length) break
        val overlapStart = transcript.indexOf(' ', maxOf(start + 1, splitAt - CHUNK_OVERLAP_CHARS))
        start = if (overlapStart in (start + 1) until splitAt) overlapStart + 1 else splitAt
    }
    return chunks
}

const val DEFAULT_PROMPT = OFFLINE_GUARDRAIL + "\n\n" + """Agisci come segretario di riunione. Leggi integralmente la trascrizione e produci un resoconto fedele, conciso e in italiano naturale.

Usa soltanto informazioni presenti nella trascrizione. Non correggere fatti incerti, non inventare partecipanti, decisioni, date o assegnatari. Se un elemento non è chiaro, omettilo.

Restituisci SOLO JSON valido, senza Markdown, commenti o testo prima/dopo. Le cinque chiavi devono essere esattamente:
- "title": titolo concreto, massimo 8 parole
- "shortSummary": 1-2 frasi chiare
- "topics": array JSON di 3-5 argomenti realmente trattati
- "mindMap": punti e sotto-punti in testo semplice, con "- " e "  - "
- "fullSummary": sezioni "## Contesto", "## Punti principali", "## Decisioni", "## Questioni aperte", "## Azioni". Inserisci un'azione solo se esplicitamente concordata; se manca l'assegnatario scrivi "Da assegnare".

Ogni valore testuale deve essere italiano leggibile: non usare token, abbreviazioni senza spiegazione, markup tecnico o frammenti di parole.

Trascrizione:"""

fun activePrompt(context: Context): String {
    val prefs = context.getSharedPreferences("daedalus_prefs", Context.MODE_PRIVATE)
    val custom = prefs.getString("custom_prompt", null)
    val base = custom ?: DEFAULT_PROMPT
    // A saved custom prompt is usually an edit of DEFAULT_PROMPT, so it already carries the
    // guardrail — appending unconditionally would repeat it.
    val guarded = if (base.contains(OFFLINE_GUARDRAIL)) base else "$base\n\n$OFFLINE_GUARDRAIL"
    val terms = prefs.getString(MEETING_GLOSSARY_KEY, "").orEmpty()
        .lineSequence().map(String::trim).filter(String::isNotBlank).take(100).toList()
    return if (terms.isEmpty()) guarded else guarded + "\n\nGlossario da preservare (non inventare varianti): " + terms.joinToString(", ")
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

/**
 * Checks whether a transcript contains readable, meaningful speech suitable for summarization.
 *
 * Filters out:
 * - Empty or blank transcripts
 * - Transcripts containing only non-speech descriptors (e.g. `[laughter]`, `(music)`)
 * - Very short transcripts (< 3 words)
 * - Whisper loop hallucinations where a 1 to 4 word phrase repeats across the entire transcript
 *
 * @param transcript The raw transcript text to validate.
 * @return `true` if the transcript is readable and suitable for AI analysis; `false` otherwise.
 */
fun isTranscriptReadable(transcript: String): Boolean {
    val trimmed = transcript.trim()
    if (trimmed.isEmpty()) return false

    // Remove bracket descriptors like [laughter], (music)
    val clean = trimmed.replace(Regex("\\[.*?\\]|\\(.*?\\)"), "").trim()
    if (clean.isEmpty()) return false

    val words = clean.split(Regex("\\s+")).filter { it.isNotBlank() }
    if (words.size < 3) return false

    // Normalize words for repetition checks (strip punctuation, lowercase)
    val normalizedWords = words.map { word ->
        word.filter { it.isLetterOrDigit() }.lowercase()
    }.filter { it.isNotBlank() }

    if (normalizedWords.size < 3) return false

    // Check for Whisper loop hallucinations (repeating word sequences)
    if (normalizedWords.size >= 8) {
        for (phraseLen in 1..4) {
            val chunk = normalizedWords.take(phraseLen)
            var isRepeating = true
            var index = 0
            while (index < normalizedWords.size) {
                val remaining = normalizedWords.size - index
                if (remaining >= phraseLen) {
                    val nextChunk = normalizedWords.subList(index, index + phraseLen)
                    if (nextChunk != chunk) {
                        isRepeating = false
                        break
                    }
                    index += phraseLen
                } else {
                    val tailChunk = normalizedWords.subList(index, normalizedWords.size)
                    if (tailChunk != chunk.subList(0, remaining)) {
                        isRepeating = false
                        break
                    }
                    index += remaining
                }
            }
            if (isRepeating) return false
        }
    }

    return true
}


