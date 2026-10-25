package com.daedalusapps.echo.ai

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Downloads the four official Parakeet runtime files directly and verifies every
 * byte with the publisher's SHA-256 values before making the model available.
 * This deliberately avoids bzip2/tar extraction on the phone.
 */
class ParakeetDownloader(private val context: Context) {
    private data class ModelFile(val name: String, val bytes: Long, val sha256: String)

    private val files = listOf(
        ModelFile(PARAKEET_ENCODER_FILE, 652_184_281L, "acfc2b4456377e15d04f0243af540b7fe7c992f8d898d751cf134c3a55fd2247"),
        ModelFile(PARAKEET_DECODER_FILE, 11_845_275L, "179e50c43d1a9de79c8a24149a2f9bac6eb5981823f2a2ed88d655b24248db4e"),
        ModelFile(PARAKEET_JOINER_FILE, 6_355_277L, "3164c13fc2821009440d20fcb5fdc78bff28b4db2f8d0f0b329101719c0948b3"),
        ModelFile(PARAKEET_TOKENS_FILE, 93_939L, "d58544679ea4bc6ac563d1f545eb7d474bd6cfa467f0a6e2c1dc1c7d37e3c35d")
    )
    private val _state = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val state: StateFlow<DownloadState> = _state.asStateFlow()
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.MINUTES)
        .build()

    suspend fun download() = withContext(Dispatchers.IO) {
        val finalDir = parakeetModelDir(context)
        if (isParakeetReady(context)) {
            _state.value = DownloadState.Done
            return@withContext
        }
        val stagingDir = File(finalDir.parentFile, "${finalDir.name}.installing")
        stagingDir.deleteRecursively()
        stagingDir.mkdirs()
        try {
            var completeBefore = 0L
            for (modelFile in files) {
                downloadAndVerify(modelFile, stagingDir, completeBefore)
                completeBefore += modelFile.bytes
            }
            File(stagingDir, PARAKEET_READY_MARKER).writeText("complete")
            _state.value = DownloadState.Installing("Finalizing Parakeet model…")
            finalDir.deleteRecursively()
            check(stagingDir.renameTo(finalDir)) { "Could not finalize Parakeet installation" }
            check(isParakeetReady(context)) { "Parakeet installation verification failed" }
            _state.value = DownloadState.Done
        } catch (e: Exception) {
            Log.e("ParakeetDL", "Model download failed", e)
            _state.value = DownloadState.Failed(e.message ?: "Unknown error")
        } finally {
            if (!isParakeetReady(context)) stagingDir.deleteRecursively()
        }
    }

    private fun downloadAndVerify(model: ModelFile, stagingDir: File, completeBefore: Long) {
        val temporary = File(stagingDir, ".${model.name}.part")
        val destination = File(stagingDir, model.name)
        temporary.delete()
        val request = Request.Builder()
            .url(PARAKEET_MODEL_URL_BASE + model.name)
            .addHeader("User-Agent", "DaedalusEcho/ItalianOffline")
            .build()
        client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "HTTP ${response.code} while downloading ${model.name}" }
            val body = response.body ?: error("Empty download for ${model.name}")
            val digest = MessageDigest.getInstance("SHA-256")
            var downloaded = 0L
            FileOutputStream(temporary).use { out ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(65_536)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        out.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        downloaded += read
                        val current = completeBefore + downloaded
                        _state.value = DownloadState.Downloading(
                            ((current * 100) / PARAKEET_TOTAL_BYTES).toInt().coerceIn(0, 99),
                            current,
                            PARAKEET_TOTAL_BYTES
                        )
                    }
                }
            }
            check(downloaded == model.bytes) {
                "${model.name}: downloaded $downloaded bytes, expected ${model.bytes}"
            }
            val actualHash = digest.digest().joinToString("") { "%02x".format(it) }
            check(actualHash.equals(model.sha256, ignoreCase = true)) {
                "${model.name}: integrity check failed"
            }
            check(temporary.renameTo(destination)) { "Could not finalize ${model.name}" }
        }
    }
}
