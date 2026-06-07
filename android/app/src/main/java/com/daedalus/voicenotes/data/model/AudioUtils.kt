package com.daedalus.voicenotes.data.model

import android.media.MediaMetadataRetriever
import java.io.File
import java.util.Locale

object AudioUtils {
    /**
     * Extracts the duration of an audio file in milliseconds.
     */
    fun getDurationMillis(path: String): Long {
        if (path.isBlank()) return 0
        val file = File(path)
        if (!file.exists()) return 0
        
        return try {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(path)
            val time = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            retriever.release()
            time?.toLong() ?: 0L
        } catch (e: Exception) {
            0L
        }
    }

    /**
     * Formats milliseconds into a string like "MM:SS" or "H:MM:SS".
     */
    fun formatDuration(millis: Long): String {
        if (millis <= 0) return "0:00"
        val totalSeconds = millis / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60

        return if (hours > 0) {
            String.format(Locale.getDefault(), "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.getDefault(), "%d:%02d", minutes, seconds)
        }
    }
}
