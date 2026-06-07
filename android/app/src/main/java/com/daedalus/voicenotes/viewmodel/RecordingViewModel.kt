package com.daedalus.voicenotes.viewmodel

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.daedalus.voicenotes.ai.activePrompt
import com.daedalus.voicenotes.ai.EmbeddingService
import com.daedalus.voicenotes.ai.extractActionItems
import com.daedalus.voicenotes.ai.LocalLlmService
import com.daedalus.voicenotes.ai.MarkdownExporter
import com.daedalus.voicenotes.ai.SmartAnalysisParser
import com.daedalus.voicenotes.ai.TranscriptionService
import com.daedalus.voicenotes.ai.isWhisperReady
import com.daedalus.voicenotes.ai.selectedModel
import com.daedalus.voicenotes.ble.BleManager
import com.daedalus.voicenotes.ble.ConnectionState
import com.daedalus.voicenotes.data.RecordingRepository
import com.daedalus.voicenotes.data.db.AppDatabase
import com.daedalus.voicenotes.data.model.AudioUtils
import com.daedalus.voicenotes.data.model.Recording
import com.daedalus.voicenotes.ui.mindmap.GlobalGraph
import com.daedalus.voicenotes.ui.mindmap.GraphBuilder
import kotlinx.coroutines.CancellationException
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

    private var syncJob: Job? = null

    fun cancelSync() {
        syncJob?.cancel()
        syncJob = null
        _syncProgress.value = null
    }

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
        // Heal missing durations for already synced files
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

    fun syncAllBleFiles(bleManager: BleManager) {
        syncJob = viewModelScope.launch {
            try {
            if (!isBleConnected(bleManager)) {
                _aiError.value = "Device not connected. Connect the FW920 before syncing."
                return@launch
            }
            _syncProgress.value = "Listing files on device…"
            bleManager.listFiles()
            val files = bleManager.bleState.value.files
            if (files.isEmpty()) {
                _syncProgress.value = null
                _aiError.value = "No files found on device. Make sure FW920 is connected."
                return@launch
            }
            _aiError.value = null
            var synced = 0
            val newFilenames = mutableListOf<String>()

            files.forEach { entry ->
                if (!entry.filename.matches(Regex("[A-Za-z0-9._-]+"))) {
                    Log.w("DaedalusSync", "Skipping suspicious filename: ${entry.filename}")
                    return@forEach
                }
                val existing = repo.get(entry.filename)

                val localFile = existing?.localPath?.let { java.io.File(it) }
                val localSize = localFile?.takeIf { it.exists() }?.length() ?: 0L
                Log.i("DaedalusSync", "file=${entry.filename} localSize=$localSize deviceSize=${entry.sizeBytes}")
                if (localSize > 0) return@forEach
                _syncProgress.value = "Downloading ${entry.filename} via BLE…"
                val file = bleManager.downloadFile(entry.filename) { bytes ->
                    _syncProgress.value = "Downloading ${entry.filename} (${bytes / 1024} KB)…"
                }
                if (file != null) {
                    val duration = AudioUtils.getDurationMillis(file.absolutePath)
                    val recording = existing ?: Recording(filename = entry.filename)
                    repo.save(recording.copy(
                        localPath = file.absolutePath, 
                        sizeBytes = file.length(),
                        durationMillis = duration
                    ))
                    newFilenames.add(entry.filename)
                    synced++
                }
            }

            _syncProgress.value = if (synced > 0) "Synced $synced file(s)" else "All files already synced"
            delay(1000)
            autoAnalyzePending()
            _syncProgress.value = null
            } catch (e: CancellationException) {
                _syncProgress.value = null
                throw e
            }
        }
    }

    private suspend fun autoAnalyzePending() {
        val context = getApplication<Application>()
        val prefs = context.getSharedPreferences("daedalus_prefs", Context.MODE_PRIVATE)
        val autoProcess = prefs.getBoolean("auto_process", false)

        if (!autoProcess) return

        // Fetch current list from repo
        val recordings = repo.allRecordings.first()
        for (recording in recordings) {
            if (recording.summary.isBlank() && recording.localPath.isNotBlank()) {
                val file = File(recording.localPath)
                if (file.exists()) {
                    _syncProgress.value = "Auto-analyzing ${recording.filename}…"
                    doAnalyze(recording.filename)
                    delay(500) // Brief pause between analyses
                }
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    fun fullAutoSync() {
        viewModelScope.launch {
            val context = getApplication<Application>()
            val localDir = File(context.getExternalFilesDir(null), "Recordings").also { it.mkdirs() }
            
            Log.i("DaedalusSync", "Starting Auto-Sync...")
            _syncProgress.value = "Searching for USB..."
            _aiError.value = null

            withContext(Dispatchers.IO) {
                val storageManager = context.getSystemService(android.os.storage.StorageManager::class.java)
                val volumes = storageManager.storageVolumes
                
                Log.d("DaedalusSync", "Found ${volumes.size} storage volumes")
                var foundAny = false
                val commonFolders = listOf("RECORD", "Record", "RECORDER", "VOICE", "Voice")

                volumes.forEach { volume ->
                    Log.d("DaedalusSync", "Volume: ${volume.getDescription(context)} (Primary: ${volume.isPrimary}, Emulated: ${volume.isEmulated})")
                    if (volume.isPrimary || volume.isEmulated) return@forEach
                    
                    val mountPath = volume.directory
                    if (mountPath == null) {
                        Log.w("DaedalusSync", "Volume ${volume.getDescription(context)} has no directory path")
                        return@forEach
                    }

                    Log.i("DaedalusSync", "Scanning drive: $mountPath")

                    val recordDir = commonFolders.map { File(mountPath, it) }.find { it.exists() && it.isDirectory }
                    
                    if (recordDir != null) {
                        foundAny = true
                        Log.i("DaedalusSync", "Found recorder folder at: ${recordDir.absolutePath}")
                        val files = recordDir.listFiles()?.filter { it.name.endsWith(".mp3", ignoreCase = true) } ?: emptyList()
                        
                        Log.d("DaedalusSync", "Found ${files.size} MP3 files in recorder")

                        files.forEach { file ->
                            val destFile = File(localDir, file.name)
                            if (destFile.exists() && destFile.length() == file.length()) {
                                Log.d("DaedalusSync", "Skipping existing file: ${file.name}")
                                return@forEach
                            }

                            Log.i("DaedalusSync", "Syncing: ${file.name} (${file.length()} bytes)")
                            _syncProgress.value = "Syncing ${file.name}..."
                            
                            try {
                                file.inputStream().use { input ->
                                    FileOutputStream(destFile).use { output ->
                                        input.copyTo(output)
                                    }
                                }
                                val duration = AudioUtils.getDurationMillis(destFile.absolutePath)
                                val recording = repo.get(file.name) ?: Recording(filename = file.name)
                                repo.save(recording.copy(
                                    localPath = destFile.absolutePath, 
                                    sizeBytes = destFile.length(),
                                    durationMillis = duration
                                ))
                            } catch (e: Exception) {
                                Log.e("DaedalusSync", "Error copying ${file.name}", e)
                                _aiError.value = "Failed to copy ${file.name}: ${e.message}"
                            }
                        }
                    }
                }
                
                if (!foundAny) {
                    Log.e("DaedalusSync", "No compatible recorder folder found on any external volume")
                    _aiError.value = "Recorder not found. Ensure USB OTG is connected and has a 'RECORD' folder."
                } else {
                    Log.i("DaedalusSync", "Auto-Sync complete")
                }
            }
            autoAnalyzePending()
            _syncProgress.value = null
            _currentNote.value?.let { loadNote(it.filename) }
        }
    }

    fun syncFiles(uris: List<Uri>) {
        viewModelScope.launch {
            val context = getApplication<Application>()
            val localDir = File(context.getExternalFilesDir(null), "Recordings").also { it.mkdirs() }

            withContext(Dispatchers.IO) {
                uris.forEach { uri ->
                    val docFile = DocumentFile.fromSingleUri(context, uri) ?: return@forEach
                    val name = docFile.name ?: "REC_${System.currentTimeMillis()}.mp3"
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
                    _aiError.value = "Recording not synced. Download it first."
                    return
                }

                val localFile = note.localPath.let { java.io.File(it) }.takeIf { it.exists() } ?: run {
                    _aiError.value = "Audio file missing — sync the recording first."
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

                // Step 2: Summarize + mind map with Gemma (Single Pass)
                _syncProgress.value = "Analyzing with Gemma…"
                llm.ensureLoaded()

                val rawResponse = llm.generate(activePrompt(getApplication()), transcript)
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
        // Gemma sometimes wraps output in ```json ... ``` fences — strip them
        val stripped = text.trim()
            .removePrefix("```json").removePrefix("```")
            .removeSuffix("```").trim()
        return stripped
    }

    fun clearExportIntent() { _exportIntent.value = null }

    private fun isBleConnected(bleManager: BleManager): Boolean =
        bleManager.bleState.value.connectionState == ConnectionState.CONNECTED

    fun updateTitleAndSummary(filename: String, title: String, shortSummary: String) {
        viewModelScope.launch { repo.updateTitleAndSummary(filename, title, shortSummary) }
    }

    fun deleteRecording(filename: String, bleManager: BleManager) {
        viewModelScope.launch {
            if (!isBleConnected(bleManager)) {
                _aiError.value = "Device not connected. Connect the FW920 before deleting."
                return@launch
            }
            val recording = repo.get(filename) ?: return@launch

            // 1. Try to delete from physical device via BLE
            _syncProgress.value = "Deleting from device…"
            val bleSuccess = bleManager.deleteFile(filename)
            Log.i("RecordingViewModel", "BLE delete result: $bleSuccess")
            
            if (bleSuccess) {
                // 2. Remove local file
                recording.localPath.takeIf { it.isNotBlank() }?.let { File(it).delete() }
                // 3. Remove from database
                repo.delete(recording)
                _syncProgress.value = "Deleted successfully"
            } else {
                _aiError.value = "Hardware delete failed. File still on FW920."
                _syncProgress.value = "Delete failed"
            }
            delay(1500)
            _syncProgress.value = null
        }
    }

    fun deleteMultipleRecordings(filenames: List<String>, bleManager: BleManager) {
        viewModelScope.launch {
            if (!isBleConnected(bleManager)) {
                _aiError.value = "Device not connected. Connect the FW920 before deleting."
                return@launch
            }
            _isProcessing.value = true
            var count = 0
            var failedCount = 0
            val total = filenames.size
            
            for (filename in filenames) {
                count++
                _syncProgress.value = "Deleting $count of $total..."
                
                val recording = repo.get(filename) ?: continue
                
                // 1. Hardware wipe
                val bleSuccess = bleManager.deleteFile(filename)
                
                if (bleSuccess) {
                    // 2. Local cleanup
                    recording.localPath.takeIf { it.isNotBlank() }?.let { java.io.File(it).delete() }
                    repo.delete(recording)
                } else {
                    Log.w("RecordingViewModel", "Failed to wipe $filename from hardware")
                    failedCount++
                }
            }
            
            if (failedCount > 0) {
                _syncProgress.value = "Done ($failedCount failed)"
                _aiError.value = "Some files could not be deleted from the FW920 hardware."
            } else {
                _syncProgress.value = "Deleted $total items"
            }
            delay(1500)
            _syncProgress.value = null
            _isProcessing.value = false
        }
    }

    fun exportMarkdown(filename: String) {
        viewModelScope.launch {
            val recording = repo.get(filename) ?: return@launch
            val content = MarkdownExporter.export(recording)
            val context = getApplication<Application>()

            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val outFile = File(downloadsDir, "${filename.removeSuffix(".mp3")}.md")
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

            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val outFile = File(downloadsDir, "ask-${System.currentTimeMillis()}.md")
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
                // Wait for the DB to emit rather than reading the potentially-empty initial StateFlow value
                val all = repo.allRecordings.first().filter { it.summary.isNotBlank() }

                Log.d("DaedalusAI", "askLibrary: ${all.size} analyzed notes, embedding backfill starting")
                // Backfill embeddings for any notes that don't have them yet, updating DB and memory together
                val withEmbeddings = mutableListOf<Recording>()
                for (r in all) {
                    val resolved = if (r.embedding != null) r
                    else {
                        val text = "${r.shortSummary} ${r.topics.joinToString(" ")}"
                        val emb = embedder.embed(text)
                        Log.d("DaedalusAI", "Backfill embed '${r.filename}': ${if (emb != null) "ok (${emb.size}d)" else "null"}")
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
