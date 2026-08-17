package com.daedalusapps.echo.util

import android.content.Context
import android.content.Intent
import android.provider.CalendarContract

object CalendarIntegration {
    /**
     * Pre-fills an Android Calendar INSERT intent for the specified action item/todo text.
     * Defaults to a 1-hour duration starting at [beginTimeMs] (default: now).
     */
    fun addToCalendar(
        context: Context,
        title: String,
        description: String = "",
        beginTimeMs: Long = System.currentTimeMillis()
    ) {
        val intent = Intent(Intent.ACTION_INSERT).apply {
            data = CalendarContract.Events.CONTENT_URI
            putExtra(CalendarContract.Events.TITLE, title)
            putExtra(CalendarContract.Events.DESCRIPTION, description)
            putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, beginTimeMs)
            putExtra(CalendarContract.EXTRA_EVENT_END_TIME, beginTimeMs + (60 * 60 * 1000L)) // 1 hour default
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (intent.resolveActivity(context.packageManager) != null) {
            context.startActivity(intent)
        } else {
            // Fallback for devices without a default calendar handler
            context.startActivity(Intent.createChooser(intent, "Add to Calendar").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        }
    }
}
