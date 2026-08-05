package com.daedalusapps.echo.data.backup

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.daedalusapps.echo.ai.normalizeTodoText
import com.daedalusapps.echo.data.db.AppDatabase
import com.daedalusapps.echo.data.model.Recording
import com.daedalusapps.echo.data.model.TodoItem
import com.daedalusapps.echo.ai.AI_TEXT_BUDGET_KEY
import com.daedalusapps.echo.ai.AI_TEXT_BUDGET_DEFAULT
import com.daedalusapps.echo.ui.screens.TODO_LOOKBACK_HOURS_DEFAULT
import com.daedalusapps.echo.viewmodel.MAX_RECORDING_MINUTES_DEFAULT
import com.daedalusapps.echo.viewmodel.MAX_RECORDING_MINUTES_KEY
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BackupManagerTest {

    private lateinit var context: Context

    private fun newDb(): AppDatabase =
        Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()

    private fun prefs() =
        context.getSharedPreferences("daedalus_prefs", Context.MODE_PRIVATE)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // Start from a clean prefs store for every test.
        prefs().edit().clear().commit()
    }

    @After
    fun tearDown() {
        prefs().edit().clear().commit()
    }

    @Test
    fun v2ExportRoundTrip_restoresRecordingsTodosAndSettings() = runBlocking {
        val source = newDb()
        source.recordingDao().upsert(
            Recording(filename = "note1.mp3", title = "First", transcript = "hello", category = 2)
        )
        source.recordingDao().upsert(
            Recording(filename = "note2.mp3", title = "Second", transcript = "world", topics = listOf("a", "b"))
        )
        source.todoDao().insert(TodoItem(text = "Buy milk", isDone = false, sourceFilename = "note1.mp3"))
        source.todoDao().insert(TodoItem(text = "Call Bob", isDone = true, isAiGenerated = true))

        prefs().edit()
            .putBoolean("use_bluetooth_mic", true)
            .putBoolean("auto_process", true)
            .putString("custom_prompt", "my prompt")
            .putInt("backup_max_count", 7)
            .putInt(MAX_RECORDING_MINUTES_KEY, 45)
            .putInt(AI_TEXT_BUDGET_KEY, 9_000)
            .commit()

        val json = BackupManager(context, source).buildBackupJson()
        assertEquals(2, json.getInt("backupVersion"))
        assertTrue(json.has("exportedAt"))
        assertEquals(2, json.getJSONArray("recordings").length())
        assertEquals(2, json.getJSONArray("todos").length())
        source.close()

        // Fresh DB + wiped prefs simulate a restore on another install.
        prefs().edit().clear().commit()
        val target = newDb()
        val imported = BackupManager(context, target).importFromJson(json)

        assertEquals(2, imported)
        val recordings = target.recordingDao().getAllFlow().first()
        assertEquals(setOf("note1.mp3", "note2.mp3"), recordings.map { it.filename }.toSet())
        assertEquals("First", recordings.first { it.filename == "note1.mp3" }.title)

        val todos = target.todoDao().getAllFlow().first()
        assertEquals(setOf("Buy milk", "Call Bob"), todos.map { it.text }.toSet())
        assertTrue(todos.first { it.text == "Call Bob" }.isDone)

        assertTrue(prefs().getBoolean("use_bluetooth_mic", false))
        assertTrue(prefs().getBoolean("auto_process", false))
        assertEquals("my prompt", prefs().getString("custom_prompt", null))
        assertEquals(7, prefs().getInt("backup_max_count", 0))
        assertEquals(45, prefs().getInt(MAX_RECORDING_MINUTES_KEY, 0))
        assertEquals(9_000, prefs().getInt(AI_TEXT_BUDGET_KEY, 0))
        target.close()
    }

    // A restored backup can carry an out-of-range AI text budget (e.g. from a corrupt or
    // hand-edited file). BackupManager restores the raw value verbatim — the clamp lives in
    // aiTextBudget() so every reader benefits, not just the restore path — but the raw restored
    // value must still be readable back out unclamped from prefs, and aiTextBudget() must clamp
    // it when read.
    @Test
    fun v2Import_outOfRangeAiTextBudget_isClampedWhenRead() = runBlocking {
        val db = newDb()
        val manager = BackupManager(context, db)
        val json = JSONObject().apply {
            put("backupVersion", 2)
            put("recordings", JSONArray())
            put("todos", JSONArray())
            put("settings", JSONObject().put(AI_TEXT_BUDGET_KEY, 1_000_000_000))
        }

        manager.importFromJson(json)

        assertEquals(100_000, com.daedalusapps.echo.ai.aiTextBudget(context))
        db.close()
    }

    @Test
    fun v1Import_recordingsOnly_importsWithoutError() = runBlocking {
        val v1 = JSONObject().apply {
            put("backupVersion", 1)
            put("exportedAt", 111L)
            put("recordings", JSONArray().apply {
                put(JSONObject().apply {
                    put("filename", "legacy.mp3")
                    put("title", "Legacy")
                    put("transcript", "old transcript")
                })
            })
            // No "todos" and no "settings" keys.
        }

        val target = newDb()
        val imported = BackupManager(context, target).importFromJson(v1)

        assertEquals(1, imported)
        val recordings = target.recordingDao().getAllFlow().first()
        assertEquals(listOf("legacy.mp3"), recordings.map { it.filename })
        assertTrue(target.todoDao().getAllFlow().first().isEmpty())
        target.close()
    }

    @Test
    fun import_skipsInvalidFilenames_pathTraversalSafe() = runBlocking {
        val json = JSONObject().apply {
            put("backupVersion", 2)
            put("recordings", JSONArray().apply {
                put(JSONObject().apply { put("filename", "../evil.mp3"); put("title", "Bad") })
                put(JSONObject().apply { put("filename", "good.mp3"); put("title", "Good") })
            })
        }

        val target = newDb()
        val imported = BackupManager(context, target).importFromJson(json)

        assertEquals(1, imported)
        val recordings = target.recordingDao().getAllFlow().first()
        assertEquals(listOf("good.mp3"), recordings.map { it.filename })
        target.close()
    }

    @Test
    fun todoImport_isIdempotent_dedupByNormalizedText() = runBlocking {
        val json = JSONObject().apply {
            put("backupVersion", 2)
            put("recordings", JSONArray())
            put("todos", JSONArray().apply {
                put(JSONObject().apply { put("text", "Buy Milk!"); put("isDone", false); put("createdAt", 1L) })
                put(JSONObject().apply { put("text", "Call   Bob"); put("isDone", false); put("createdAt", 2L) })
            })
        }

        val target = newDb()
        val bm = BackupManager(context, target)
        bm.importFromJson(json)
        bm.importFromJson(json) // second import with slightly different-cased duplicate text

        // "buy milk" already exists; re-importing must not duplicate.
        val todos = target.todoDao().getAllFlow().first()
        assertEquals(2, todos.size)
        target.close()
    }

    @Test
    fun buildBackupJson_freshInstall_exportsDocumentedDefaultsForAllSettingsKeys() = runBlocking {
        // Fresh prefs (cleared in setUp): no key has ever been touched by the UI.
        val db = newDb()
        val json = BackupManager(context, db).buildBackupJson()
        val settings = json.getJSONObject("settings")

        assertEquals(false, settings.getBoolean("use_bluetooth_mic"))
        assertEquals(true, settings.getBoolean("auto_process"))
        assertEquals(TODO_LOOKBACK_HOURS_DEFAULT, settings.getLong("todo_lookback_hours"))
        assertEquals(BackupPrefs.DEFAULT_INTERVAL_HOURS, settings.getLong(BackupPrefs.INTERVAL_HOURS))
        assertEquals(BackupPrefs.DEFAULT_MAX_COUNT, settings.getInt(BackupPrefs.MAX_COUNT))
        assertEquals(MAX_RECORDING_MINUTES_DEFAULT, settings.getInt(MAX_RECORDING_MINUTES_KEY))
        assertEquals(AI_TEXT_BUDGET_DEFAULT, settings.getInt(AI_TEXT_BUDGET_KEY))
        db.close()
    }

    @Test
    fun buildBackupJson_customPrompt_absentWhenUnsetPresentWhenSet() = runBlocking {
        val db = newDb()

        val unsetJson = BackupManager(context, db).buildBackupJson()
        assertFalse(unsetJson.getJSONObject("settings").has("custom_prompt"))

        prefs().edit().putString("custom_prompt", "my custom prompt").commit()
        val setJson = BackupManager(context, db).buildBackupJson()
        assertEquals("my custom prompt", setJson.getJSONObject("settings").getString("custom_prompt"))
        db.close()
    }

    @Test
    fun v2ExportRoundTrip_freshDefaults_overwriteNonDefaultTargetValues() = runBlocking {
        // Fresh prefs export: every documented default is exported even though never touched.
        val source = newDb()
        val json = BackupManager(context, source).buildBackupJson()
        source.close()

        // Target prefs hold non-default values for every settings key.
        prefs().edit()
            .putBoolean("use_bluetooth_mic", true)
            .putBoolean("auto_process", false)
            .putLong("todo_lookback_hours", 12L)
            .putLong(BackupPrefs.INTERVAL_HOURS, 6L)
            .putInt(BackupPrefs.MAX_COUNT, 3)
            .putInt(MAX_RECORDING_MINUTES_KEY, 30)
            .putInt(AI_TEXT_BUDGET_KEY, 5_000)
            .commit()

        val target = newDb()
        BackupManager(context, target).importFromJson(json)

        assertEquals(false, prefs().getBoolean("use_bluetooth_mic", true))
        assertEquals(true, prefs().getBoolean("auto_process", false))
        assertEquals(TODO_LOOKBACK_HOURS_DEFAULT, prefs().getLong("todo_lookback_hours", -1L))
        assertEquals(BackupPrefs.DEFAULT_INTERVAL_HOURS, prefs().getLong(BackupPrefs.INTERVAL_HOURS, -1L))
        assertEquals(BackupPrefs.DEFAULT_MAX_COUNT, prefs().getInt(BackupPrefs.MAX_COUNT, -1))
        assertEquals(MAX_RECORDING_MINUTES_DEFAULT, prefs().getInt(MAX_RECORDING_MINUTES_KEY, -1))
        assertEquals(AI_TEXT_BUDGET_DEFAULT, prefs().getInt(AI_TEXT_BUDGET_KEY, -1))
        target.close()
    }

    @Test
    fun settingsRestore_appliesOnlyKeysPresent() = runBlocking {
        // Pre-existing value that must survive an import that omits its key.
        prefs().edit().putBoolean("use_bluetooth_mic", true).commit()

        val json = JSONObject().apply {
            put("backupVersion", 2)
            put("recordings", JSONArray())
            put("settings", JSONObject().apply { put("auto_process", true) })
        }

        val target = newDb()
        BackupManager(context, target).importFromJson(json)

        assertEquals(true, prefs().getBoolean("auto_process", false))
        // Untouched key retains its prior value.
        assertTrue(prefs().getBoolean("use_bluetooth_mic", false))
        assertFalse(prefs().contains("custom_prompt"))
        target.close()
    }

    @Test
    fun settingsRestore_plainJsonIntForLongKey_storedAsLongNoClassCast() = runBlocking {
        // org.json parses `24` as Integer. If applySettings inferred the pref type from
        // the JSON value, backup_interval_hours (written/read as Long everywhere) would be
        // stored as Int and a later getLong() would throw ClassCastException.
        val json = JSONObject().apply {
            put("backupVersion", 2)
            put("recordings", JSONArray())
            put("settings", JSONObject().apply { put("backup_interval_hours", 24) }) // plain int
        }

        val target = newDb()
        BackupManager(context, target).importFromJson(json)

        // Must not throw and must round-trip to 24L.
        assertEquals(24L, prefs().getLong("backup_interval_hours", -1L))
        target.close()
    }

    @Test
    fun todoImport_punctuationOnlyTodos_areNotFoldedIntoOne() = runBlocking {
        // normalizeTodoText("?!?") and normalizeTodoText(":)") both reduce to "".
        // Empty normalized forms must bypass dedup so distinct todos still all insert.
        val json = JSONObject().apply {
            put("backupVersion", 2)
            put("recordings", JSONArray())
            put("todos", JSONArray().apply {
                put(JSONObject().apply { put("text", "?!?"); put("isDone", false); put("createdAt", 1L) })
                put(JSONObject().apply { put("text", ":)"); put("isDone", false); put("createdAt", 2L) })
            })
        }

        val target = newDb()
        BackupManager(context, target).importFromJson(json)

        val todos = target.todoDao().getAllFlow().first()
        assertEquals(setOf("?!?", ":)"), todos.map { it.text }.toSet())

        // Normal-text dedup still works alongside the empty-norm bypass.
        val normalJson = JSONObject().apply {
            put("backupVersion", 2)
            put("recordings", JSONArray())
            put("todos", JSONArray().apply {
                put(JSONObject().apply { put("text", "Buy Milk!"); put("createdAt", 3L) })
                put(JSONObject().apply { put("text", "buy milk"); put("createdAt", 4L) })
            })
        }
        BackupManager(context, target).importFromJson(normalJson)
        assertEquals(1, target.todoDao().getAllFlow().first().count { normalizeMatchesBuyMilk(it.text) })
        target.close()
    }

    private fun normalizeMatchesBuyMilk(text: String): Boolean =
        normalizeTodoText(text) == "buy milk"

    @Test
    fun runAutoBackup_noFolderConfigured_returnsFailureWithoutThrowing() = runBlocking {
        // backup_folder_uri intentionally absent from prefs.
        val db = newDb()
        val result = BackupManager(context, db).runAutoBackup()

        assertTrue(result.isFailure)
        assertEquals("No backup folder configured", result.exceptionOrNull()?.message)
        assertTrue(prefs().contains("last_backup_error"))
        db.close()
    }

    @Test
    fun runAutoBackup_folderUriNotGranted_returnsFailure() = runBlocking {
        prefs().edit().putString("backup_folder_uri", "content://com.example/tree/fake").commit()
        val db = newDb()
        val result = BackupManager(context, db).runAutoBackup()

        assertTrue(result.isFailure)
        assertTrue(prefs().contains("last_backup_error"))
        db.close()
    }
}
