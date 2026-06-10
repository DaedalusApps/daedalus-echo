package com.daedalusapps.echo

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.daedalusapps.echo.ai.LocalLlmService
import com.daedalusapps.echo.ai.SmartAnalysisParser
import com.daedalusapps.echo.ai.TranscriptionService
import com.daedalusapps.echo.ai.activePrompt
import com.daedalusapps.echo.ai.extractActionItems
import com.daedalusapps.echo.data.RecordingRepository
import com.daedalusapps.echo.data.db.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

class AdbReceiver : BroadcastReceiver() {

    companion object {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    override fun onReceive(context: Context, intent: Intent?) {
        if (!BuildConfig.DEBUG) return
        val action = intent?.action ?: return

        if (action == "com.daedalusapps.echo.ANALYZE") {
            val filename = intent.getStringExtra("filename")?.takeIf { it.isNotBlank() } ?: return
            Log.i("DaedalusADB", "ANALYZE triggered for '$filename'")
            val app = context.applicationContext as Application
            scope.launch { runAnalysis(app, filename) }
            return
        }

        if (intent.getBooleanExtra("_forwarded", false)) return
        Log.i("DaedalusADB", "AdbReceiver forwarding: $action")
        context.sendBroadcast(Intent(action).setPackage(context.packageName).also { fwd ->
            intent.extras?.let { fwd.putExtras(it) }
            fwd.putExtra("_forwarded", true)
        })
    }

    private suspend fun runAnalysis(app: Application, filename: String) {
        val repo = RecordingRepository(AppDatabase.getInstance(app).recordingDao())
        val transcriber = TranscriptionService(app)
        val llm = LocalLlmService(app)
        val key = filename.removeSuffix(".mp3")
        try {
            val note = repo.get(key) ?: run {
                Log.e("DaedalusAI", "Recording not found: $key (tried both with and without .mp3)")
                return
            }
            val localFile = File(note.localPath).takeIf { it.exists() } ?: run {
                Log.e("DaedalusAI", "Audio file missing: ${note.localPath}")
                return
            }

            Log.i("DaedalusAI", "Transcribing $filename")
            val transcript = transcriber.transcribe(localFile)
            if (transcript.isBlank()) {
                Log.e("DaedalusAI", "Blank transcript for $filename")
                return
            }
            repo.save(note.copy(transcript = transcript))

            Log.i("DaedalusAI", "Running Gemma on $filename")
            llm.ensureLoaded()
            val rawResponse = llm.generate(activePrompt(app), transcript)
            val cleanJson = rawResponse.trim()
                .removePrefix("```json").removePrefix("```")
                .removeSuffix("```").trim()
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
                filename = key,
                summary = fullSummaryFinal,
                mindMap = analysis.mindMap,
                title = analysis.title,
                shortSummary = analysis.shortSummary,
                topics = analysis.topics
            )
            Log.i("DaedalusAI", "Analysis complete for $filename: title='${analysis.title}'")
        } catch (e: Exception) {
            Log.e("DaedalusAI", "ADB analysis failed for $filename", e)
        } finally {
            llm.close()
        }
    }
}
