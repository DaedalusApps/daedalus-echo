package com.daedalus.voicenotes.ai

import android.content.Context
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

import android.util.Log

class LocalLlmService(private val context: Context) {

    private var inference: LlmInference? = null

    val isReady: Boolean get() = inference != null

    suspend fun ensureLoaded() {
        if (inference != null) return
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
                    .setMaxTokens(768)
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

    suspend fun generate(systemPrompt: String, userText: String): String =
        withContext(Dispatchers.IO) {
            val llm = inference ?: error("Model not loaded — call ensureLoaded() first")
            Log.d("DaedalusAI", "Generating response for input (length: ${userText.length})...")
            // Gemma 2B instruction-tuned format: <start_of_turn>user\n...\n<end_of_turn>\n<start_of_turn>model\n
            val prompt = buildString {
                append("<start_of_turn>user\n")
                if (systemPrompt.isNotBlank()) {
                    append(systemPrompt)
                    append("\n\n")
                }
                append(userText)
                append("<end_of_turn>\n")
                append("<start_of_turn>model\n")
            }
            try {
                val response = llm.generateResponse(prompt)
                Log.d("DaedalusAI", "Generation complete (response length: ${response.length})")
                response
            } catch (e: Exception) {
                Log.e("DaedalusAI", "Inference failed", e)
                throw e
            }
        }

    fun close() {
        inference?.close()
        inference = null
    }
}
