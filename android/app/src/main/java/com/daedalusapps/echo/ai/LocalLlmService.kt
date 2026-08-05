package com.daedalusapps.echo.ai

import android.content.Context
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume

import android.util.Log

enum class Role { USER, MODEL }

data class ChatTurn(val role: Role, val text: String)

// Gemma instruction-tuned chat template: <start_of_turn>user\n...\n<end_of_turn>\n<start_of_turn>model\n
// Gemma has no system role, so the system prompt is folded into the first user turn.
internal fun buildGemmaPrompt(systemPrompt: String, turns: List<ChatTurn>): String {
    require(turns.isNotEmpty()) { "turns must not be empty" }
    require(turns.first().role == Role.USER) { "first turn must be USER" }
    require(turns.last().role == Role.USER) { "last turn must be USER" }
    for (i in 1 until turns.size) {
        require(turns[i].role != turns[i - 1].role) { "consecutive turns must not share the same role" }
    }
    return buildString {
        turns.forEachIndexed { index, turn ->
            append("<start_of_turn>")
            append(if (turn.role == Role.USER) "user" else "model")
            append("\n")
            if (index == 0 && turn.role == Role.USER && systemPrompt.isNotBlank()) {
                append(systemPrompt)
                append("\n\n")
            }
            append(turn.text)
            append("<end_of_turn>\n")
        }
        append("<start_of_turn>model\n")
    }
}

class LocalLlmService(private val context: Context) {

    @Volatile
    private var inference: LlmInference? = null
    private val mutex = Mutex()

    // Independent of any caller's coroutine (e.g. ConversationViewModel's generationJob), so
    // cancelling a caller — stopGenerating() — cannot cancel work already running here. See
    // generate()'s KDoc for why that matters.
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val isReady: Boolean get() = inference != null

    suspend fun ensureLoaded() {
        if (inference != null) return
        mutex.withLock {
            if (inference != null) return@withLock
            withContext(Dispatchers.IO) {
                val model = selectedModel(context)
                Log.i("DaedalusAI", "Loading model: ${model.id}")
                val file = modelFile(context)
                if (!file.exists()) {
                    Log.e("DaedalusAI", "Model file not found: ${file.absolutePath}")
                    error("Model not downloaded: ${file.absolutePath}")
                }
                try {
                    val optionsBuilder = LlmInference.LlmInferenceOptions.builder()
                        .setModelPath(file.absolutePath)
                        .setMaxTokens(4096)
                        .setMaxTopK(40)
                    if (model.useGpu) {
                        Log.i("DaedalusAI", "Requesting GPU backend for ${model.id}")
                        optionsBuilder.setPreferredBackend(LlmInference.Backend.GPU)
                    }
                    val options = optionsBuilder.build()
                    inference = LlmInference.createFromOptions(context, options)
                    Log.i("DaedalusAI", "Model loaded successfully")
                } catch (e: Exception) {
                    Log.e("DaedalusAI", "Failed to load MediaPipe inference engine", e)
                    throw e
                }
            }
        }
    }

    suspend fun generate(systemPrompt: String, userText: String): String =
        generate(systemPrompt, listOf(ChatTurn(Role.USER, userText)))

    /**
     * Runs the actual native call in [serviceScope] rather than directly in the caller's
     * coroutine. `generateResponseAsync`'s callback fires from native code whenever the
     * underlying inference finishes — cancelling the coroutine awaiting it (e.g.
     * ConversationViewModel.stopGenerating()) only abandons that wait; the native call keeps
     * running to completion regardless (cancel-and-ignore-result, which is fine for a single
     * abandoned reply). The hazard that guards against: if the [mutex] were released the moment
     * the *caller* is cancelled, a subsequent `generate()` call could invoke
     * `generateResponseAsync` again on the same [LlmInference] instance while the abandoned call
     * is still in flight — calling it concurrently is not a supported usage pattern for this
     * instance and could interleave or corrupt output. Running the critical section in
     * [serviceScope] — a scope independent of the caller — means the [mutex] stays held until the
     * abandoned call actually finishes or the 3-minute timeout elapses, so the next `generate()`
     * call simply waits rather than racing it.
     */
    suspend fun generate(systemPrompt: String, turns: List<ChatTurn>): String {
        val prompt = buildGemmaPrompt(systemPrompt, turns)
        val deferred = serviceScope.async<String> {
            mutex.withLock {
                val llm = inference ?: error("Model not loaded — call ensureLoaded() first")
                Log.d("DaedalusAI", "Generating response for ${turns.size} turn(s)...")
                try {
                    // generateResponse() crashes in MediaPipe 0.10.35 via nativePredictSync.
                    // generateResponseAsync uses a different native path that is stable.
                    // 3-minute timeout guards against the callback never firing on native error.
                    withTimeout(180_000L) {
                        suspendCancellableCoroutine { cont ->
                            val sb = StringBuilder()
                            llm.generateResponseAsync(prompt) { partialResult, done ->
                                partialResult?.let { sb.append(it) }
                                if (done && cont.isActive) {
                                    val response = sb.toString()
                                    Log.d("DaedalusAI", "Generation complete (response length: ${response.length})")
                                    cont.resume(response)
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e("DaedalusAI", "Inference failed", e)
                    throw e
                }
            }
        }
        return deferred.await()
    }

    companion object {
        // The singleton is intentionally never closed: it lives for the process, and native
        // teardown here previously raced an in-flight generateResponseAsync call and caused
        // a SIGSEGV. Native memory is reclaimed when the process dies.
        @Volatile
        private var INSTANCE: LocalLlmService? = null

        fun getInstance(context: Context): LocalLlmService {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: LocalLlmService(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
