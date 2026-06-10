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
import java.util.concurrent.TimeUnit

class WhisperDownloader(private val context: Context) {

    private val _state = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val state: StateFlow<DownloadState> = _state.asStateFlow()

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .build()

    suspend fun download() = withContext(Dispatchers.IO) {
        val dir = whisperModelDir(context).also { it.mkdirs() }
        val files = listOf(
            WHISPER_ENCODER_FILE to WHISPER_ENCODER_URL,
            WHISPER_DECODER_FILE to WHISPER_DECODER_URL,
            WHISPER_TOKENS_FILE  to WHISPER_TOKENS_URL,
        )

        var totalDownloaded = files.sumOf { (name, _) ->
            File(dir, name).takeIf { it.exists() }?.length() ?: 0L
        }

        try {
            for ((filename, url) in files) {
                val dest = File(dir, filename)
                if (dest.exists()) continue

                Log.i("WhisperDL", "Downloading $filename")
                val tmp = File(dir, "$filename.tmp")
                val request = Request.Builder()
                    .url(url)
                    .addHeader("User-Agent", "DaedalusEcho/1.1")
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        tmp.delete()
                        _state.value = DownloadState.Failed("HTTP ${response.code} for $filename")
                        return@withContext
                    }
                    val body = response.body ?: run {
                        _state.value = DownloadState.Failed("Empty body for $filename")
                        return@withContext
                    }
                    FileOutputStream(tmp).use { out ->
                        body.byteStream().use { inp ->
                            val buf = ByteArray(65_536)
                            var n: Int
                            while (inp.read(buf).also { n = it } >= 0) {
                                out.write(buf, 0, n)
                                totalDownloaded += n
                                _state.value = DownloadState.Downloading(
                                    ((totalDownloaded * 100) / WHISPER_TOTAL_BYTES).toInt().coerceIn(0, 99),
                                    totalDownloaded,
                                    WHISPER_TOTAL_BYTES
                                )
                            }
                        }
                    }
                    if (!tmp.renameTo(dest)) {
                        tmp.inputStream().use { it.copyTo(dest.outputStream()) }
                        tmp.delete()
                    }
                    Log.i("WhisperDL", "Saved $filename (${dest.length()} bytes)")
                }
            }
            _state.value = DownloadState.Done
        } catch (e: Exception) {
            files.forEach { (name, _) -> File(dir, "$name.tmp").delete() }
            _state.value = DownloadState.Failed(e.message ?: "Unknown error")
        }
    }
}
