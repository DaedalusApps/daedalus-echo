package com.daedalusapps.echo.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.daedalusapps.echo.ai.aiTextBudget
import com.daedalusapps.echo.ai.LocalLlmService
import com.daedalusapps.echo.ai.TODO_EXTRACTION_PROMPT
import com.daedalusapps.echo.ai.isDuplicateTodoNormalized
import com.daedalusapps.echo.ai.normalizeTodoText
import com.daedalusapps.echo.ai.parseTodoLines
import com.daedalusapps.echo.data.db.AppDatabase
import com.daedalusapps.echo.data.model.TodoItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TodoViewModel @JvmOverloads constructor(
    application: Application,
    private val db: AppDatabase = AppDatabase.getInstance(application),
    private val llm: LocalLlmService = LocalLlmService.getInstance(application),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : AndroidViewModel(application) {

    val todos: StateFlow<List<TodoItem>> = db.todoDao().getAllFlow()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    private val _isExtracting = MutableStateFlow(false)
    val isExtracting: StateFlow<Boolean> = _isExtracting

    private val _extractError = MutableStateFlow<String?>(null)
    val extractError: StateFlow<String?> = _extractError

    private val _lastExtractCount = MutableStateFlow<Int?>(null)
    val lastExtractCount: StateFlow<Int?> = _lastExtractCount

    fun clearError() { _extractError.value = null }
    fun clearLastExtractCount() { _lastExtractCount.value = null }

    // ------------------------------------------------------------------
    // Manual todo operations
    // ------------------------------------------------------------------

    fun addTodo(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        launchIo { db.todoDao().insert(TodoItem(text = trimmed)) }
    }

    fun editTodo(item: TodoItem, newText: String) {
        launchIo { db.todoDao().update(item.copy(text = newText)) }
    }

    fun deleteTodo(item: TodoItem) {
        launchIo { db.todoDao().delete(item) }
    }

    fun toggleDone(item: TodoItem) {
        launchIo { db.todoDao().setDone(item.id, !item.isDone) }
    }

    private fun launchIo(block: suspend () -> Unit) {
        viewModelScope.launch { withContext(ioDispatcher) { block() } }
    }

    /**
     * Extracts action items from recordings in the last [lookbackHours] hours and inserts any new,
     * non-duplicate todos. Existing todos (including their done state) are never modified or deleted.
     *
     * Pass a negative [lookbackHours] to scan all recordings (cutoff 0).
     *
     * Partial-progress-on-error: batches are processed sequentially and each extracted item is
     * inserted immediately. If the LLM fails on a later batch, todos already inserted from earlier
     * successful batches remain persisted; [extractError] is set and [lastExtractCount] is left
     * unchanged (not updated for the aborted run).
     */
    fun updateFromRecordings(lookbackHours: Long) {
        if (_isExtracting.value) return
        viewModelScope.launch {
            _isExtracting.value = true
            _extractError.value = null
            try {
                withContext(ioDispatcher) { extractCore(lookbackHours) }
            } catch (e: CancellationException) {
                throw e // don't surface normal coroutine cancellation as an error
            } catch (e: Exception) {
                Log.e("TodoViewModel", "Todo extraction failed", e)
                _extractError.value = e.message ?: "Todo extraction failed"
            } finally {
                _isExtracting.value = false
            }
        }
    }

    private suspend fun extractCore(lookbackHours: Long) {
        val cutoff = if (lookbackHours < 0) 0L else System.currentTimeMillis() - lookbackHours * 3_600_000L
        val recordings = db.recordingDao().getSince(cutoff)
        if (recordings.isEmpty()) {
            _lastExtractCount.value = 0
            return
        }

        val blocks = recordings.mapNotNull { r ->
            val body = r.summary.ifBlank { r.transcript.take(2000) }
            if (body.isBlank()) null
            else "Note: ${r.title.ifBlank { r.filename }}\n$body"
        }
        if (blocks.isEmpty()) {
            _lastExtractCount.value = 0
            return
        }

        val maxBatchChars = aiTextBudget(getApplication()) * 3 / 4
        val batches = packBatches(blocks, maxBatchChars)

        llm.ensureLoaded()
        val existing = db.todoDao().getAllFlow().first().map { it.text }
        val trackedTexts = existing.toMutableList()
        val trackedNorms = existing.mapTo(mutableListOf()) { normalizeTodoText(it) }
        val insertedThisRun = mutableListOf<String>()

        for (batchText in batches) {
            val trackedBlock = if (trackedTexts.isEmpty()) "(none)"
                else trackedTexts.takeLast(30).joinToString("\n") { "- $it" }
            val prompt = TODO_EXTRACTION_PROMPT +
                "\n\nAlready tracked (do not repeat):\n" + trackedBlock
            val response = llm.generate(prompt, batchText)
            parseTodoLines(response).forEach { candidate ->
                val candidateNorm = normalizeTodoText(candidate)
                if (!isDuplicateTodoNormalized(candidateNorm, trackedNorms)) {
                    db.todoDao().insert(TodoItem(text = candidate, isAiGenerated = true))
                    insertedThisRun += candidate
                    trackedTexts += candidate
                    trackedNorms += candidateNorm
                }
            }
        }

        _lastExtractCount.value = insertedThisRun.size
    }

    /** Greedy-packs note blocks into batches of at most [maxBatchChars] chars. */
    private fun packBatches(blocks: List<String>, maxBatchChars: Int): List<String> {
        val batches = mutableListOf<String>()
        val current = StringBuilder()
        for (block in blocks) {
            val b = if (block.length > maxBatchChars) block.take(maxBatchChars) else block
            if (current.isNotEmpty() && current.length + SEPARATOR.length + b.length > maxBatchChars) {
                batches.add(current.toString())
                current.setLength(0)
            }
            if (current.isNotEmpty()) current.append(SEPARATOR)
            current.append(b)
        }
        if (current.isNotEmpty()) batches.add(current.toString())
        return batches
    }

    private companion object {
        const val SEPARATOR = "\n\n"
    }
}
