package com.daedalusapps.echo.viewmodel

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.daedalusapps.echo.ai.activePrompt
import com.daedalusapps.echo.ai.CHUNK_SUMMARY_PROMPT
import com.daedalusapps.echo.ai.chunkTranscript
import com.daedalusapps.echo.ai.EmbeddingService
import com.daedalusapps.echo.ai.extractActionItems
import com.daedalusapps.echo.ai.LocalLlmService
import com.daedalusapps.echo.ai.MarkdownExporter
import com.daedalusapps.echo.ai.SmartAnalysisParser
import com.daedalusapps.echo.ai.TranscriptionService
import com.daedalusapps.echo.ai.isWhisperReady
import com.daedalusapps.echo.data.RecordingRepository
import com.daedalusapps.echo.data.db.AppDatabase
import com.daedalusapps.echo.data.model.AudioUtils
import com.daedalusapps.echo.data.model.Recording
import com.daedalusapps.echo.recording.AudioRecorder
import com.daedalusapps.echo.ui.mindmap.GlobalGraph
import com.daedalusapps.echo.ui.mindmap.GraphBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalCoroutinesApi::class)
class RecordingViewModel @JvmOverloads constructor(
    application: Application,
    private val db: AppDatabase = AppDatabase.getInstance(application),
    private val repo: RecordingRepository = RecordingRepository(db.recordingDao()),
    private val llm: LocalLlmService = LocalLlmService(application),
    private val transcriber: TranscriptionService = TranscriptionService(application),
    private val embedder: EmbeddingService = EmbeddingService(application)
) : AndroidViewModel(application) {

    private val _syncProgress = MutableStateFlow<String?>(null)
    val syncProgress: StateFlow<String?> = _syncProgress

    private val _isProcessing = MutableStateFlow(false)
    val isProcessing: StateFlow<Boolean> = _isProcessing

    private val _aiError = MutableStateFlow<String?>(null)
    val aiError: StateFlow<String?> = _aiError

    private val _isAsking = MutableStateFlow(false)
    val isAsking: StateFlow<Boolean> = _isAsking

    private val _askAnswer = MutableStateFlow<String?>(null)
    val askAnswer: StateFlow<String?> = _askAnswer

    private val _libraryAnswer = MutableStateFlow<String?>(null)
    val libraryAnswer: StateFlow<String?> = _libraryAnswer

    private val _librarySources = MutableStateFlow<List<Recording>>(emptyList())
    val librarySources: StateFlow<List<Recording>> = _librarySources

    private val _libraryQuestion = MutableStateFlow("")
    val libraryQuestion: StateFlow<String> = _libraryQuestion

    private val _currentNote = MutableStateFlow<Recording?>(null)
    val currentNote: StateFlow<Recording?> = _currentNote

    private val _exportIntent = MutableStateFlow<Intent?>(null)
    val exportIntent: StateFlow<Intent?> = _exportIntent

    // Local Audio Recording Engine
    private val audioRecorder = AudioRecorder(application)
    private var recordingTimerJob: Job? = null
    private var currentRecordingFile: File? = null
    private var recordingStartMillis: Long = 0L

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording

    private val _isPaused = MutableStateFlow(false)
    val isPaused: StateFlow<Boolean> = _isPaused

    private val _recordingDurationSeconds = MutableStateFlow(0L)
    val recordingDurationSeconds: StateFlow<Long> = _recordingDurationSeconds

    val useBluetoothMic = MutableStateFlow(false)

    val allRecordings: StateFlow<List<Recording>> = repo.allRecordings
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val globalGraph: StateFlow<GlobalGraph> = allRecordings
        .map { GraphBuilder.build(it) }
        .stateIn(viewModelScope, SharingStarted.Lazily, GlobalGraph(emptyList(), emptyList()))

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery

    val filteredRecordings: StateFlow<List<Recording>> = _searchQuery
        .flatMapLatest { q ->
            if (q.isBlank()) repo.allRecordings else repo.search(q)
        }
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    init {
        val prefs = application.getSharedPreferences("daedalus_prefs", Context.MODE_PRIVATE)
        val savedVal = prefs.getBoolean("use_bluetooth_mic", false)
        if (savedVal && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val hasPermission = ContextCompat.checkSelfPermission(
                application,
                android.Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
            useBluetoothMic.value = hasPermission
            if (!hasPermission) {
                prefs.edit().putBoolean("use_bluetooth_mic", false).apply()
            }
        } else {
            useBluetoothMic.value = savedVal
        }

        // Heal missing durations for already synced/recorded files
        viewModelScope.launch(Dispatchers.IO) {
            repo.allRecordings.first().forEach { recording ->
                if (recording.durationMillis == 0L && recording.localPath.isNotBlank()) {
                    val duration = AudioUtils.getDurationMillis(recording.localPath)
                    if (duration > 0) {
                        repo.save(recording.copy(durationMillis = duration))
                    }
                }
            }
        }
    }

    fun setSearchQuery(q: String) { _searchQuery.value = q }

    fun setUseBluetoothMic(enabled: Boolean) {
        useBluetoothMic.value = enabled
        getApplication<Application>()
            .getSharedPreferences("daedalus_prefs", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("use_bluetooth_mic", enabled)
            .apply()
    }

    // Recording Controls
    fun startLocalRecording() {
        if (_isRecording.value) return
        val context = getApplication<Application>()
        val dir = File(context.getExternalFilesDir(null), "Recordings").also { it.mkdirs() }
        val sdf = SimpleDateFormat("yyyyMMddHHmmss", Locale.US)
        val filename = "${sdf.format(Date())}.m4a"
        val file = File(dir, filename)
        currentRecordingFile = file

        _recordingDurationSeconds.value = 0L
        recordingStartMillis = System.currentTimeMillis()
        
        try {
            audioRecorder.start(file, useBluetoothMic.value)
            _isRecording.value = true
            _isPaused.value = false
            _aiError.value = null
            
            // Start duration timer
            recordingTimerJob?.cancel()
            recordingTimerJob = viewModelScope.launch(Dispatchers.Default) {
                var elapsed = 0L
                while (true) {
                    delay(1000)
                    elapsed++
                    _recordingDurationSeconds.value = elapsed
                }
            }
            Log.i("RecordingViewModel", "Started local recording: ${file.name}")
        } catch (e: Exception) {
            Log.e("RecordingViewModel", "Failed to start local recording", e)
            _aiError.value = "Failed to start recording: ${e.message}"
        }
    }

    fun pauseLocalRecording() {
        if (!_isRecording.value || _isPaused.value) return
        audioRecorder.pause()
        _isPaused.value = true
        recordingTimerJob?.cancel()
    }

    fun resumeLocalRecording() {
        if (!_isRecording.value || !_isPaused.value) return
        audioRecorder.resume()
        _isPaused.value = false
        recordingTimerJob = viewModelScope.launch(Dispatchers.Default) {
            var elapsed = _recordingDurationSeconds.value
            while (true) {
                delay(1000)
                elapsed++
                _recordingDurationSeconds.value = elapsed
            }
        }
    }

    fun stopLocalRecording() {
        if (!_isRecording.value) return
        audioRecorder.stop()
        recordingTimerJob?.cancel()
        _isRecording.value = false
        _isPaused.value = false

        val file = currentRecordingFile ?: return
        if (file.exists() && file.length() > 0) {
            val duration = System.currentTimeMillis() - recordingStartMillis
            val name = file.name
            viewModelScope.launch {
                val recording = Recording(
                    filename = name,
                    localPath = file.absolutePath,
                    sizeBytes = file.length(),
                    durationMillis = duration,
                    createdAt = System.currentTimeMillis()
                )
                repo.save(recording)
                Log.i("RecordingViewModel", "Saved local recording: $name (${file.length()} bytes)")
                
                // Auto-analyze if settings permit
                val prefs = getApplication<Application>().getSharedPreferences("daedalus_prefs", Context.MODE_PRIVATE)
                if (prefs.getBoolean("auto_process", true)) {
                    doAnalyze(name)
                }
            }
        }
        currentRecordingFile = null
    }

    private suspend fun autoAnalyzePending() {
        val context = getApplication<Application>()
        val prefs = context.getSharedPreferences("daedalus_prefs", Context.MODE_PRIVATE)
        val autoProcess = prefs.getBoolean("auto_process", true)

        if (!autoProcess) return

        val recordings = repo.allRecordings.first()
        for (recording in recordings) {
            if (recording.summary.isBlank() && recording.localPath.isNotBlank()) {
                val file = File(recording.localPath)
                if (file.exists()) {
                    _syncProgress.value = "Auto-analyzing ${recording.filename}…"
                    doAnalyze(recording.filename)
                    delay(500)
                }
            }
        }
    }

    fun syncFiles(uris: List<Uri>) {
        viewModelScope.launch {
            val context = getApplication<Application>()
            val localDir = File(context.getExternalFilesDir(null), "Recordings").also { it.mkdirs() }

            withContext(Dispatchers.IO) {
                uris.forEach { uri ->
                    val docFile = DocumentFile.fromSingleUri(context, uri) ?: return@forEach
                    val name = docFile.name ?: "REC_${System.currentTimeMillis()}.m4a"
                    val destFile = File(localDir, name)

                    if (destFile.exists() && destFile.length() == docFile.length()) return@forEach

                    _syncProgress.value = "Syncing $name..."

                    try {
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            FileOutputStream(destFile).use { output ->
                                input.copyTo(output)
                            }
                        }
                        val duration = AudioUtils.getDurationMillis(destFile.absolutePath)
                        val recording = repo.get(name) ?: Recording(filename = name)
                        repo.save(recording.copy(
                            localPath = destFile.absolutePath, 
                            sizeBytes = destFile.length(),
                            durationMillis = duration
                        ))
                    } catch (e: Exception) {
                        _aiError.value = "Failed to sync $name: ${e.message}"
                    }
                }
            }
            autoAnalyzePending()
            _syncProgress.value = null
            _currentNote.value?.let { loadNote(it.filename) }
        }
    }

    fun loadNote(filename: String) {
        viewModelScope.launch {
            _currentNote.value = repo.get(filename)
        }
    }

    fun analyze(filename: String) {
        viewModelScope.launch { doAnalyze(filename) }
    }

    private suspend fun doAnalyze(filename: String) {
        _isProcessing.value = true
        _aiError.value = null
        try {
            val note = repo.get(filename) ?: run {
                _aiError.value = "Recording not found."
                return
            }

            val localFile = note.localPath.let { java.io.File(it) }.takeIf { it.exists() } ?: run {
                _aiError.value = "Audio file missing."
                return
            }

            // Step 1: Always re-transcribe to get fresh text
            _syncProgress.value = "Transcribing audio…"
            Log.i("DaedalusAI", "Transcribing ${localFile.name}")
            val transcript = transcriber.transcribe(localFile)
            if (transcript.isBlank()) {
                val modelReady = isWhisperReady(getApplication())
                _aiError.value = if (modelReady) {
                    "No speech detected in this recording (too short or silent)."
                } else {
                    "Transcription model not found. Please download it in Settings."
                }
                return
            }
            repo.save(note.copy(transcript = transcript))

            // Step 2: Summarize + mind map with Gemma (chunked for long transcripts)
            llm.ensureLoaded()
            val chunks = chunkTranscript(transcript)
            val rawResponse = if (chunks.size == 1) {
                _syncProgress.value = "Analyzing with Gemma…"
                llm.generate(activePrompt(getApplication()), chunks[0])
            } else {
                val chunkSummaries = chunks.mapIndexed { i, chunk ->
                    _syncProgress.value = "Analyzing part ${i + 1} of ${chunks.size}…"
                    llm.generate(CHUNK_SUMMARY_PROMPT, chunk)
                }
                _syncProgress.value = "Synthesizing results…"
                llm.generate(activePrompt(getApplication()), chunkSummaries.joinToString("\n\n"))
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

            // Generate semantic embedding for library-wide Q&A (silent if model not ready)
            if (embedder.isReady) {
                embedder.ensureLoaded()
                val embText = "${analysis.shortSummary} ${analysis.topics.joinToString(" ")}"
                embedder.embed(embText)?.let { repo.updateEmbedding(filename, it) }
            }

            _currentNote.value = repo.get(filename)
        } catch (e: Exception) {
            Log.e("DaedalusAI", "Analysis failed", e)
            _aiError.value = e.message ?: "Unknown AI error"
        } finally {
            _isProcessing.value = false
            _syncProgress.value = null
        }
    }

    private fun stripCodeFences(text: String): String {
        return text.trim()
            .removePrefix("```json").removePrefix("```")
            .removeSuffix("```").trim()
    }

    fun clearExportIntent() { _exportIntent.value = null }

    fun updateTitleAndSummary(filename: String, title: String, shortSummary: String) {
        viewModelScope.launch { repo.updateTitleAndSummary(filename, title, shortSummary) }
    }

    fun deleteRecording(filename: String) {
        viewModelScope.launch {
            val recording = repo.get(filename) ?: return@launch
            recording.localPath.takeIf { it.isNotBlank() }?.let { File(it).delete() }
            repo.delete(recording)
            _syncProgress.value = "Deleted successfully"
            delay(1000)
            _syncProgress.value = null
        }
    }

    fun deleteMultipleRecordings(filenames: List<String>) {
        viewModelScope.launch {
            _isProcessing.value = true
            var count = 0
            val total = filenames.size
            for (filename in filenames) {
                count++
                _syncProgress.value = "Deleting $count of $total..."
                val recording = repo.get(filename) ?: continue
                recording.localPath.takeIf { it.isNotBlank() }?.let { File(it).delete() }
                repo.delete(recording)
            }
            _syncProgress.value = "Deleted $total items"
            delay(1000)
            _syncProgress.value = null
            _isProcessing.value = false
        }
    }

    fun exportMarkdown(filename: String) {
        viewModelScope.launch {
            val recording = repo.get(filename) ?: return@launch
            val content = MarkdownExporter.export(recording)
            val context = getApplication<Application>()

            val cacheDir = context.cacheDir
            val cleanName = filename.removeSuffix(".mp3").removeSuffix(".m4a")
            val outFile = File(cacheDir, "$cleanName.md")
            withContext(Dispatchers.IO) { outFile.writeText(content) }

            val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", outFile)
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/markdown"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            _exportIntent.value = Intent.createChooser(shareIntent, "Export as Markdown")
        }
    }

    fun exportLibraryAnswer() {
        val answer = _libraryAnswer.value ?: return
        viewModelScope.launch {
            val content = MarkdownExporter.exportQa(_libraryQuestion.value, answer, _librarySources.value)
            val context = getApplication<Application>()

            val cacheDir = context.cacheDir
            val outFile = File(cacheDir, "ask-${System.currentTimeMillis()}.md")
            withContext(Dispatchers.IO) { outFile.writeText(content) }

            val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", outFile)
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/markdown"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            _exportIntent.value = Intent.createChooser(shareIntent, "Export answer as Markdown")
        }
    }

    fun clearAskAnswer() {
        _askAnswer.value = null
        _libraryAnswer.value = null
        _librarySources.value = emptyList()
    }

    fun askNoteQuestion(filename: String, question: String) {
        viewModelScope.launch {
            _isAsking.value = true
            _askAnswer.value = null
            _aiError.value = null
            try {
                val note = repo.get(filename) ?: run {
                    _aiError.value = "Note not found."
                    return@launch
                }
                if (note.shortSummary.isBlank() && note.summary.isBlank()) {
                    _aiError.value = "Analyze this note first to enable Q&A."
                    return@launch
                }
                llm.ensureLoaded()
                val context = "You are answering a question about a specific note. " +
                    "Note title: ${note.title}. " +
                    "Note summary: ${note.shortSummary.ifBlank { note.summary.take(400) }}. " +
                    "Answer concisely based only on the note content. " +
                    "If the answer is not in the note, say so clearly."
                _askAnswer.value = llm.generate(context, question)
            } catch (e: Exception) {
                Log.e("DaedalusAI", "askNoteQuestion failed", e)
                _aiError.value = e.message ?: "Q&A failed"
            } finally {
                _isAsking.value = false
            }
        }
    }

    fun askLibraryQuestion(question: String) {
        viewModelScope.launch {
            _isAsking.value = true
            _libraryQuestion.value = question
            _libraryAnswer.value = null
            _librarySources.value = emptyList()
            _aiError.value = null
            try {
                if (!embedder.isReady) {
                    _aiError.value = "Download the embedding model in Settings to use Ask Library."
                    return@launch
                }
                embedder.ensureLoaded()
                val queryEmbed = embedder.embed(question) ?: run {
                    _aiError.value = "Could not embed question."
                    return@launch
                }
                val all = repo.allRecordings.first().filter { it.summary.isNotBlank() }

                Log.d("DaedalusAI", "askLibrary: ${all.size} analyzed notes, embedding backfill starting")
                val withEmbeddings = mutableListOf<Recording>()
                for (r in all) {
                    val resolved = if (r.embedding != null) r
                    else {
                        val text = "${r.shortSummary} ${r.topics.joinToString(" ")}"
                        val emb = embedder.embed(text)
                        Log.d("DaedalusAI", "Backfill embed '${r.filename}': ${if (emb != null) "ok" else "null"}")
                        if (emb != null) {
                            repo.updateEmbedding(r.filename, emb)
                            r.copy(embedding = emb)
                        } else r
                    }
                    withEmbeddings.add(resolved)
                }

                val sources = repo.semanticSearch(queryEmbed, withEmbeddings, topK = 5)
                if (sources.isEmpty()) {
                    _aiError.value = "No note embeddings found. Re-analyze your notes to enable library search."
                    return@launch
                }
                _librarySources.value = sources
                val context = buildString {
                    append("Answer the question using the notes below. ")
                    append("Cite note titles when relevant. ")
                    append("If the answer is not in the notes, say so.\n\n")
                    sources.forEachIndexed { i, r ->
                        append("Note ${i + 1}: ${r.title.ifBlank { r.filename }}\n")
                        append(r.shortSummary.ifBlank { r.summary.take(200) })
                        append("\n\n")
                    }
                }
                llm.ensureLoaded()
                _libraryAnswer.value = llm.generate(context, question)
            } catch (e: Exception) {
                Log.e("DaedalusAI", "askLibraryQuestion failed", e)
                _aiError.value = e.message ?: "Library Q&A failed"
            } finally {
                _isAsking.value = false
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        llm.close()
        embedder.close()
    }
}
