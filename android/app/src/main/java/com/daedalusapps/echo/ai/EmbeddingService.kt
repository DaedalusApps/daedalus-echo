package com.daedalusapps.echo.ai

import android.content.Context
import android.util.Log
import com.google.mediapipe.tasks.text.textembedder.TextEmbedder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.sqrt

class EmbeddingService(private val context: Context) {

    private var embedder: TextEmbedder? = null

    val isReady: Boolean get() = embeddingModelFile(context).exists()

    suspend fun ensureLoaded() {
        if (embedder != null) return
        if (!isReady) return
        withContext(Dispatchers.IO) {
            try {
                val path = embeddingModelFile(context).absolutePath
                Log.i("DaedalusEmbed", "Loading embedding model from $path")
                embedder = TextEmbedder.createFromFile(context, path)
                Log.i("DaedalusEmbed", "Embedding model loaded successfully")
            } catch (e: Exception) {
                Log.e("DaedalusEmbed", "Failed to load embedding model", e)
            }
        }
    }

    suspend fun embed(text: String): FloatArray? = withContext(Dispatchers.IO) {
        val e = embedder ?: return@withContext null
        try {
            val result = e.embed(text)
            val emb = result.embeddingResult().embeddings()[0]
            val floats = emb.floatEmbedding()
            if (floats != null) {
                normalize(FloatArray(floats.size) { floats[it] })
            } else {
                // Quantized model returns byte embeddings — convert to float
                val bytes = emb.quantizedEmbedding() ?: run {
                    Log.e("DaedalusEmbed", "embed returned neither float nor quantized data")
                    return@withContext null
                }
                normalize(FloatArray(bytes.size) { bytes[it].toFloat() })
            }
        } catch (ex: Exception) {
            Log.e("DaedalusEmbed", "embed() failed", ex)
            null
        }
    }

    fun close() {
        embedder?.close()
        embedder = null
    }

    private fun normalize(v: FloatArray): FloatArray {
        val norm = sqrt(v.fold(0f) { acc, x -> acc + x * x })
        if (norm == 0f) return v
        return FloatArray(v.size) { v[it] / norm }
    }
}
