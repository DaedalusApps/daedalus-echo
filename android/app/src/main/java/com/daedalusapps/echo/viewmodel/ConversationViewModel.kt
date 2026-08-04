package com.daedalusapps.echo.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.daedalusapps.echo.ai.ChatTurn
import com.daedalusapps.echo.ai.LocalLlmService
import com.daedalusapps.echo.ai.OFFLINE_GUARDRAIL
import com.daedalusapps.echo.ai.Role
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** A single chat turn in a conversation session. */
data class ChatMessage(val role: Role, val text: String, val timestampMillis: Long)

// `internal` (rather than `private`) so ai/OfflineGuardrailTest.kt can assert the guardrail
// appears exactly once, mirroring the other prompt-guardrail tests there.
internal const val IDEATION_SYSTEM_PROMPT = "You are a thoughtful ideation partner in a live " +
    "conversation with the user, like a working session with a colleague. Be concise, help " +
    "develop their thinking, and ask good clarifying follow-up questions rather than lecturing." +
    "\n\n" + OFFLINE_GUARDRAIL

private val SESSION_FILENAME_REGEX = Regex("""conv_(\d{14})\.md""")
private val TURN_HEADER_REGEX = Regex("""^\*\*(Me|Agent)\*\* \((\d{2}):(\d{2})\):$""")

/**
 * Text-only conversation core (#20 / EB.3): persistent per-day session files, single-shot send
 * guard, and consecutive-role merging so a failed send never bricks the session.
 *
 * Deliberately excluded for now (later issues): the rolling live-context summary (EB.4 — the
 * full history is sent as turns every time, see [performSend]), endSession/analysis (EB.5), and
 * all voice input / TTS / instant-send / auto-listen / replay behavior.
 */
class ConversationViewModel @JvmOverloads constructor(
    application: Application,
    private val llm: LocalLlmService = LocalLlmService.getInstance(application),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = { System.currentTimeMillis() }
) : AndroidViewModel(application) {

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages

    private val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    /** File backing the current session; exposed internally so tests can assert on its content. */
    internal lateinit var sessionFile: File
        private set

    /** Locating and parsing the session file touches disk, so it runs off the main thread. */
    private val loadJob = viewModelScope.launch {
        val (file, restored) = withContext(ioDispatcher) {
            val dir = conversationsDir(application)
            val existing = findTodaysSessionFile(dir)
            if (existing != null) existing to parseSessionFile(existing) else newSessionFile(dir) to emptyList()
        }
        sessionFile = file
        _messages.value = restored
    }

    fun clearError() { _error.value = null }

    // Tracks the coroutine running performSend(), so a future Stop control (later issue) has
    // something to cancel.
    private var generationJob: Job? = null

    fun send(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || _isGenerating.value) return
        // Claimed synchronously on the caller (main) thread so a rapid double-send cannot slip
        // through before the coroutine body runs.
        _isGenerating.value = true
        _error.value = null
        generationJob = viewModelScope.launch { performSend(trimmed) }
    }

    /** Cancels the in-flight generation started by [send]. Wired to UI in a later issue. */
    fun stopGenerating() {
        generationJob?.cancel()
    }

    /**
     * The actual send pipeline. Callers MUST have already claimed [_isGenerating] (synchronously,
     * on the main thread) before launching this — it only releases the claim, in the `finally`
     * block.
     */
    private suspend fun performSend(trimmed: String) {
        try {
            loadJob.join()
            val userMessage = ChatMessage(Role.USER, trimmed, clock())
            _messages.value = _messages.value + userMessage
            appendToFile(userMessage)

            llm.ensureLoaded()
            val turns = toChatTurns(_messages.value)
            val reply = llm.generate(IDEATION_SYSTEM_PROMPT, turns)
            val modelMessage = ChatMessage(Role.MODEL, reply, clock())
            _messages.value = _messages.value + modelMessage
            appendToFile(modelMessage)
        } catch (e: TimeoutCancellationException) {
            // generate()'s 3-minute timeout surfaces as a CancellationException subtype, but it is
            // a real failure rather than a stopGenerating() cancellation — must be caught before
            // the CancellationException branch below so it still reaches the user as an error.
            Log.e("ConversationViewModel", "Generation failed", e)
            _error.value = e.message ?: "Failed to generate a response"
        } catch (e: CancellationException) {
            // stopGenerating() cancellation, not a failure: no error, no model turn. Rethrown so
            // the coroutine actually completes as cancelled.
            throw e
        } catch (e: Exception) {
            Log.e("ConversationViewModel", "Generation failed", e)
            _error.value = e.message ?: "Failed to generate a response"
        } finally {
            _isGenerating.value = false
        }
    }

    /**
     * Maps messages to LLM turns, merging consecutive same-role messages: the chat template
     * requires strictly alternating roles, and a failed generation leaves two user turns in a row.
     */
    private fun toChatTurns(messages: List<ChatMessage>): List<ChatTurn> {
        val turns = mutableListOf<ChatTurn>()
        for (message in messages) {
            val last = turns.lastOrNull()
            if (last != null && last.role == message.role) {
                turns[turns.lastIndex] = ChatTurn(message.role, last.text + "\n\n" + message.text)
            } else {
                turns.add(ChatTurn(message.role, message.text))
            }
        }
        return turns
    }

    private suspend fun appendToFile(message: ChatMessage) {
        withContext(ioDispatcher) {
            val label = if (message.role == Role.USER) "Me" else "Agent"
            val time = SimpleDateFormat("HH:mm", Locale.US).format(Date(message.timestampMillis))
            sessionFile.appendText("**$label** ($time):\n${message.text}\n\n")
        }
    }

    /** Finds the most recent conv_*.md file created today, if any, to resume an unfinished session. */
    private fun findTodaysSessionFile(dir: File): File? {
        val today = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date(clock()))
        return dir.listFiles()
            ?.filter { SESSION_FILENAME_REGEX.matchEntire(it.name)?.groupValues?.get(1)?.startsWith(today) == true }
            ?.maxByOrNull { it.name }
    }

    /** Builds a not-yet-taken session filename, so rotating twice never reuses an existing file. */
    private fun newSessionFile(dir: File): File {
        val format = SimpleDateFormat("yyyyMMddHHmmss", Locale.US)
        var millis = clock()
        var file = File(dir, "conv_${format.format(Date(millis))}.md")
        while (file.exists()) {
            millis += 1000
            file = File(dir, "conv_${format.format(Date(millis))}.md")
        }
        return file
    }

    /** Parses this ViewModel's own markdown session format back into messages (see [appendToFile]). */
    private fun parseSessionFile(file: File): List<ChatMessage> {
        if (!file.exists()) return emptyList()
        val messages = mutableListOf<ChatMessage>()
        var currentRole: Role? = null
        var currentTime: String? = null
        val text = StringBuilder()

        fun flush() {
            val role = currentRole
            val time = currentTime
            val body = text.toString().trim()
            text.setLength(0)
            // Content before the first turn header (corrupted or foreign file) is discarded,
            // as are empty turns; neither is representable as a message.
            if (role != null && time != null && body.isNotEmpty()) {
                messages.add(ChatMessage(role, body, reconstructMillis(time)))
            }
        }

        file.forEachLine { line ->
            val match = TURN_HEADER_REGEX.matchEntire(line)
            if (match != null) {
                flush()
                currentRole = if (match.groupValues[1] == "Me") Role.USER else Role.MODEL
                currentTime = "${match.groupValues[2]}:${match.groupValues[3]}"
            } else {
                text.append(line).append("\n")
            }
        }
        flush()
        return messages
    }

    /** Reconstructs an approximate timestamp (today's date + parsed HH:mm) for a reloaded turn. */
    private fun reconstructMillis(hhmm: String): Long {
        val (hour, minute) = hhmm.split(":").map { it.toInt() }
        val calendar = Calendar.getInstance().apply {
            timeInMillis = clock()
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return calendar.timeInMillis
    }

    private fun conversationsDir(application: Application): File =
        File(application.filesDir, "conversations").also { it.mkdirs() }

    override fun onCleared() {
        super.onCleared()
        generationJob?.cancel()
    }
}
