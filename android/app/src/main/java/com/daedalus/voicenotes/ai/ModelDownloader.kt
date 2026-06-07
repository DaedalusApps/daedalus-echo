package com.daedalus.voicenotes.ai

import android.content.Context
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
import java.util.zip.ZipInputStream

sealed class DownloadState {
    object Idle : DownloadState()
    data class Downloading(val progressPct: Int, val bytesDownloaded: Long, val totalBytes: Long) : DownloadState()
    object Done : DownloadState()
    data class Failed(val message: String) : DownloadState()
}

class ModelDownloader(private val context: Context) {

    private val _state = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val state: StateFlow<DownloadState> = _state.asStateFlow()

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .build()

    suspend fun download(model: LocalModel) = withContext(Dispatchers.IO) {
        _state.value = DownloadState.Downloading(0, 0, model.sizeBytes)
        val dir      = modelsDir(context)
        val destFile = File(dir, model.filename)
        val tmpFile  = File(dir, "${model.filename}.tmp")

        try {
            val request = Request.Builder()
                .url(model.downloadUrl)
                .addHeader("User-Agent", "DaedalusVoicenotes/1.1")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    _state.value = DownloadState.Failed("HTTP ${response.code}: ${response.message}")
                    return@withContext
                }
                val body = response.body ?: run {
                    _state.value = DownloadState.Failed("Empty response body")
                    return@withContext
                }
                val total = body.contentLength().takeIf { it > 0 } ?: model.sizeBytes
                var downloaded = 0L

                FileOutputStream(tmpFile).use { out ->
                    body.byteStream().use { inp ->
                        val buf = ByteArray(65_536)
                        var n: Int
                        while (inp.read(buf).also { n = it } >= 0) {
                            out.write(buf, 0, n)
                            downloaded += n
                            _state.value = DownloadState.Downloading(
                                ((downloaded * 100) / total).toInt(), downloaded, total
                            )
                        }
                    }
                }
                if (model.filename.endsWith(".zip")) {
                    val outDir = modelsDir(context)
                    val outDirCanonical = outDir.canonicalPath
                    ZipInputStream(tmpFile.inputStream()).use { zis ->
                        var entry = zis.nextEntry
                        while (entry != null) {
                            val outFile = File(outDir, entry.name)
                            // Zip-slip guard: reject entries that escape the output directory
                            check(outFile.canonicalPath.startsWith(outDirCanonical + File.separator)) {
                                "Zip entry escapes output directory: ${entry.name}"
                            }
                            if (entry.isDirectory) {
                                outFile.mkdirs()
                            } else {
                                outFile.parentFile?.mkdirs()
                                FileOutputStream(outFile).use { zis.copyTo(it) }
                            }
                            entry = zis.nextEntry
                        }
                    }
                    tmpFile.delete()
                } else {
                    if (!tmpFile.renameTo(destFile)) {
                        // renameTo fails across filesystems; fall back to copy then delete
                        tmpFile.inputStream().use { it.copyTo(destFile.outputStream()) }
                        tmpFile.delete()
                    }
                }
                _state.value = DownloadState.Done
            }
        } catch (e: Exception) {
            tmpFile.delete()
            _state.value = DownloadState.Failed(e.message ?: "Unknown error")
        }
    }
}
