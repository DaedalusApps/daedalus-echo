package com.daedalusapps.echo.data.backup

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.daedalusapps.echo.ai.AI_TEXT_BUDGET_KEY
import com.daedalusapps.echo.ai.normalizeTodoText
import com.daedalusapps.echo.data.RecordingRepository
import com.daedalusapps.echo.data.db.AppDatabase
import com.daedalusapps.echo.data.model.Recording
import com.daedalusapps.echo.data.model.TodoItem
import com.daedalusapps.echo.viewmodel.MAX_RECORDING_MINUTES_KEY
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Reusable backup export/import logic, usable from a ViewModel or a WorkManager worker
 * without any UI dependency. All JSON build/parse logic is expressed as plain suspend
 * methods testable under Robolectric with an in-memory Room DB.
 */
class BackupManager(
    private val context: Context,
    private val db: AppDatabase = AppDatabase.getInstance(context),
    private val repo: RecordingRepository = RecordingRepository(db.recordingDao())
) {

    /** Builds the v2 backup payload: recordings + todos + settings. */
    suspend fun buildBackupJson(): JSONObject {
        val recordings = repo.allRecordings.first()
        val todos = db.todoDao().getAllFlow().first()
        val prefs = context.getSharedPreferences(BackupPrefs.PREFS_NAME, Context.MODE_PRIVATE)

        return JSONObject().apply {
            put("backupVersion", 2)
            put("exportedAt", System.currentTimeMillis())

            val array = JSONArray()
            recordings.forEach { r ->
                val obj = JSONObject().apply {
                    put("filename", r.filename)
                    put("localPath", r.localPath)
                    put("sizeBytes", r.sizeBytes)
                    put("transcript", r.transcript)
                    put("summary", r.summary)
                    put("mindMap", r.mindMap)
                    put("category", r.category)
                    put("createdAt", r.createdAt)
                    put("title", r.title)
                    put("shortSummary", r.shortSummary)
                    put("durationMillis", r.durationMillis)

                    val topicsArr = JSONArray()
                    r.topics.forEach { topicsArr.put(it) }
                    put("topics", topicsArr)

                    r.embedding?.let { emb ->
                        val embArr = JSONArray()
                        emb.forEach { embArr.put(it.toDouble()) }
                        put("embedding", embArr)
                    }
                }
                array.put(obj)
            }
            put("recordings", array)

            val todosArr = JSONArray()
            todos.forEach { t ->
                todosArr.put(JSONObject().apply {
                    put("text", t.text)
                    put("isDone", t.isDone)
                    put("createdAt", t.createdAt)
                    put("sourceFilename", t.sourceFilename)
                    put("isAiGenerated", t.isAiGenerated)
                })
            }
            put("todos", todosArr)

            val settings = JSONObject()
            val allPrefs = prefs.all
            SETTINGS_KEYS.forEach { key ->
                if (allPrefs.containsKey(key)) {
                    settings.put(key, allPrefs[key])
                }
            }
            put("settings", settings)
        }
    }

    /** Writes the pretty-printed v2 payload to [uri] via the content resolver. */
    suspend fun exportToUri(uri: Uri) {
        val json = buildBackupJson()
        context.contentResolver.openOutputStream(uri)?.use { out ->
            out.write(json.toString(2).toByteArray(Charsets.UTF_8))
        }
    }

    /** Reads a backup from [uri] and imports it. Returns the number of recordings imported. */
    suspend fun importFromUri(uri: Uri): Int {
        val jsonStr = context.contentResolver.openInputStream(uri)?.use { input ->
            input.bufferedReader().readText()
        } ?: throw Exception("Could not open backup file")
        return importFromJson(JSONObject(jsonStr))
    }

    /**
     * Imports a parsed backup payload. Supports both v1 (recordings only) and v2
     * (recordings + todos + settings). Returns the number of recordings imported.
     */
    suspend fun importFromJson(root: JSONObject): Int {
        val array = root.optJSONArray("recordings") ?: throw Exception("Backup JSON is missing recordings list")
        var importedCount = 0

        val currentRecordingsDir = File(context.getExternalFilesDir(null), "Recordings")

        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val filename = obj.optString("filename", "")

            // Security validation: prevent directory traversal via filename characters
            if (filename.isBlank() || !filename.matches(Regex("[A-Za-z0-9._-]+")) || filename == "." || filename == "..") {
                Log.w("BackupManager", "Skipping invalid filename in backup: $filename")
                continue
            }

            val originalLocalPath = obj.optString("localPath", "")
            val fileInCurrentDir = File(currentRecordingsDir, filename)

            // Security validation: prevent directory traversal via resolved paths
            val isOriginalPathSafe = if (originalLocalPath.isNotEmpty()) {
                try {
                    val originalFile = File(originalLocalPath).canonicalFile
                    val appFilesDir = context.getExternalFilesDir(null)?.canonicalFile
                    appFilesDir != null && originalFile.path.startsWith(appFilesDir.path + File.separator) && originalFile.exists()
                } catch (e: Exception) {
                    false
                }
            } else {
                false
            }

            val resolvedLocalPath = when {
                fileInCurrentDir.exists() -> fileInCurrentDir.absolutePath
                isOriginalPathSafe -> originalLocalPath
                else -> ""
            }

            val topicsArr = obj.optJSONArray("topics")
            val topics = mutableListOf<String>()
            if (topicsArr != null) {
                for (j in 0 until topicsArr.length()) {
                    val topic = topicsArr.optString(j, "")
                    if (topic.isNotEmpty()) {
                        topics.add(topic)
                    }
                }
            }

            val embArr = obj.optJSONArray("embedding")
            val embedding = if (embArr != null) {
                FloatArray(embArr.length()) { j -> embArr.optDouble(j, 0.0).toFloat() }
            } else {
                null
            }

            val existing = repo.get(filename)
            val recording = (existing ?: Recording(filename = filename)).copy(
                localPath = resolvedLocalPath.ifBlank { existing?.localPath ?: "" },
                sizeBytes = obj.optLong("sizeBytes", existing?.sizeBytes ?: 0L),
                transcript = obj.optString("transcript", existing?.transcript ?: ""),
                summary = obj.optString("summary", existing?.summary ?: ""),
                mindMap = obj.optString("mindMap", existing?.mindMap ?: ""),
                category = obj.optInt("category", existing?.category ?: 1),
                createdAt = obj.optLong("createdAt", existing?.createdAt ?: System.currentTimeMillis()),
                title = obj.optString("title", existing?.title ?: ""),
                shortSummary = obj.optString("shortSummary", existing?.shortSummary ?: ""),
                topics = topics,
                durationMillis = obj.optLong("durationMillis", existing?.durationMillis ?: 0L),
                embedding = embedding ?: existing?.embedding
            )

            repo.save(recording)
            importedCount++
        }

        importTodos(root.optJSONArray("todos"))
        applySettings(root.optJSONObject("settings"))

        return importedCount
    }

    private suspend fun importTodos(todosArr: JSONArray?) {
        if (todosArr == null) return
        val existingNorms = db.todoDao().getAllFlow().first()
            .map { normalizeTodoText(it.text) }
            .toMutableSet()

        for (i in 0 until todosArr.length()) {
            val obj = todosArr.optJSONObject(i) ?: continue
            val text = obj.optString("text", "")
            if (text.isBlank()) continue
            val norm = normalizeTodoText(text)
            // A normalized form of "" (punctuation/emoji-only todos) carries no
            // identity, so skip dedup for it and always insert rather than folding
            // every such todo into a single "" bucket.
            if (norm.isNotEmpty() && !existingNorms.add(norm)) continue // duplicate: already present

            db.todoDao().insert(
                TodoItem(
                    text = text,
                    isDone = obj.optBoolean("isDone", false),
                    createdAt = obj.optLong("createdAt", System.currentTimeMillis()),
                    sourceFilename = if (obj.isNull("sourceFilename") || !obj.has("sourceFilename")) null else obj.optString("sourceFilename", ""),
                    isAiGenerated = obj.optBoolean("isAiGenerated", false)
                )
            )
        }
    }

    /**
     * Runs an automatic backup to the configured folder (SAF tree URI stored in prefs),
     * then applies retention by deleting the oldest backups beyond `backup_max_count`.
     * Never throws; failures are reported via the returned Result and recorded in
     * the `last_backup_error` pref.
     */
    suspend fun runAutoBackup(): Result<Unit> {
        val prefs = context.getSharedPreferences(BackupPrefs.PREFS_NAME, Context.MODE_PRIVATE)
        try {
            val storedUri = prefs.getString(BackupPrefs.FOLDER_URI, null)
            if (storedUri.isNullOrBlank()) {
                return failAutoBackup(prefs, BackupPrefs.ERR_NO_FOLDER)
            }

            val hasWriteGrant = context.contentResolver.persistedUriPermissions.any {
                it.uri.toString() == storedUri && it.isWritePermission
            }
            val uri = Uri.parse(storedUri)
            val dir = if (hasWriteGrant) DocumentFile.fromTreeUri(context, uri) else null
            if (dir == null || !dir.exists() || !dir.canWrite()) {
                return failAutoBackup(prefs, BackupPrefs.ERR_FOLDER_NOT_ACCESSIBLE)
            }

            // Build the payload BEFORE creating the file so a build failure never
            // leaves a zero-byte backup whose newest-timestamp name would survive
            // retention (and fail on import) while deleting a good older backup.
            val payload = buildBackupJson().toString(2)

            val filename = "daedalus_backup_" +
                SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.US).format(Date()) + ".json"
            val file = dir.createFile("application/json", filename)
                ?: return failAutoBackup(prefs, "Could not create backup file")

            val stream = context.contentResolver.openOutputStream(file.uri)
            if (stream == null) {
                file.delete()
                return failAutoBackup(prefs, "Could not open backup file for writing")
            }
            try {
                stream.use { out -> out.write(payload.toByteArray(Charsets.UTF_8)) }
            } catch (e: Exception) {
                file.delete()
                return failAutoBackup(prefs, e.message ?: "Could not write backup file")
            }

            val maxCount = prefs.getInt(BackupPrefs.MAX_COUNT, BackupPrefs.DEFAULT_MAX_COUNT)
            val existingFiles = dir.listFiles()
            val toDelete = selectBackupsToDelete(existingFiles.mapNotNull { it.name }, maxCount)
            existingFiles.filter { it.name in toDelete }.forEach { it.delete() }

            prefs.edit()
                .putLong(BackupPrefs.LAST_BACKUP_TIME, System.currentTimeMillis())
                .remove(BackupPrefs.LAST_BACKUP_ERROR)
                .apply()

            return Result.success(Unit)
        } catch (e: Exception) {
            return failAutoBackup(prefs, e.message ?: "Unknown backup error")
        }
    }

    private fun failAutoBackup(prefs: android.content.SharedPreferences, message: String): Result<Unit> {
        prefs.edit().putString(BackupPrefs.LAST_BACKUP_ERROR, message).apply()
        return Result.failure(Exception(message))
    }

    /**
     * Applies restored settings with a typed whitelist. Each key is read and written
     * with its canonical SharedPreferences type — never inferred from the JSON value's
     * runtime type. org.json parses `24` as Integer, so type-sniffing would store an
     * interval/lookback key as Int and make a later getLong() throw ClassCastException.
     */
    private fun applySettings(settings: JSONObject?) {
        if (settings == null) return
        val prefs = context.getSharedPreferences(BackupPrefs.PREFS_NAME, Context.MODE_PRIVATE)
        val editor = prefs.edit()

        if (settings.has("use_bluetooth_mic")) editor.putBoolean("use_bluetooth_mic", settings.optBoolean("use_bluetooth_mic"))
        if (settings.has("auto_process")) editor.putBoolean("auto_process", settings.optBoolean("auto_process"))
        if (settings.has("custom_prompt")) editor.putString("custom_prompt", settings.optString("custom_prompt"))
        if (settings.has("todo_lookback_hours")) editor.putLong("todo_lookback_hours", settings.optLong("todo_lookback_hours"))
        if (settings.has(BackupPrefs.INTERVAL_HOURS)) editor.putLong(BackupPrefs.INTERVAL_HOURS, settings.optLong(BackupPrefs.INTERVAL_HOURS))
        if (settings.has(BackupPrefs.MAX_COUNT)) editor.putInt(BackupPrefs.MAX_COUNT, settings.optInt(BackupPrefs.MAX_COUNT))
        if (settings.has(MAX_RECORDING_MINUTES_KEY)) editor.putInt(MAX_RECORDING_MINUTES_KEY, settings.optInt(MAX_RECORDING_MINUTES_KEY))
        if (settings.has(AI_TEXT_BUDGET_KEY)) editor.putInt(AI_TEXT_BUDGET_KEY, settings.optInt(AI_TEXT_BUDGET_KEY))

        editor.apply()
    }

    companion object {
        private val BACKUP_FILENAME_REGEX = Regex("daedalus_backup_.*\\.json")

        private val SETTINGS_KEYS = listOf(
            "use_bluetooth_mic",
            "auto_process",
            "custom_prompt",
            "todo_lookback_hours",
            BackupPrefs.INTERVAL_HOURS,
            BackupPrefs.MAX_COUNT,
            MAX_RECORDING_MINUTES_KEY,
            AI_TEXT_BUDGET_KEY
        )

        /**
         * Given all filenames in the backup folder, returns the names of backups that
         * should be deleted to keep at most [maxCount] backups. Non-matching filenames
         * are ignored entirely (neither counted nor deleted). Oldest backups (by ascending
         * lexicographic sort, since names embed a sortable timestamp) are selected first.
         * [maxCount] <= 0 is coerced to 1 (always keep at least the newest backup).
         */
        fun selectBackupsToDelete(names: List<String>, maxCount: Int): List<String> {
            val effectiveMax = maxCount.coerceAtLeast(1)
            val backups = names.filter { it.matches(BACKUP_FILENAME_REGEX) }.sorted()
            val overflow = backups.size - effectiveMax
            if (overflow <= 0) return emptyList()
            return backups.take(overflow)
        }
    }
}
