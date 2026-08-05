package com.daedalusapps.echo.data.model

private val CONVERSATION_FILENAME_REGEX = Regex("""conv_\d{14}(\.ended)?\.md""")

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

    /**
     * True for text-only conversation notes, which are the only files named
     * "conv_20260804080519.md" (or "conv_20260804080519.ended.md" once the session has ended).
     * Matches the whole name rather than just the "conv_" prefix so an imported audio file that
     * happens to start with "conv_" is still treated as the audio recording it is.
     */
    fun isConversationNote(filename: String): Boolean =
        CONVERSATION_FILENAME_REGEX.matches(filename)
}
