package com.daedalus.echo.data.model

object DateUtils {
    /**
     * Formats filenames like "20260524213434.mp3" → "2026-05-24 21:34:34".
     * Falls back to the original string if it doesn't match the expected pattern.
     */
    fun parseDateFromFilename(filename: String): String {
        val base = filename.substringBeforeLast(".")
        val match = Regex("""(\d{4})(\d{2})(\d{2})(\d{2})(\d{2})(\d{2})""").find(base) ?: return filename
        val (year, month, day, hour, min, sec) = match.destructured
        return "$year-$month-$day $hour:$min:$sec"
    }
}
