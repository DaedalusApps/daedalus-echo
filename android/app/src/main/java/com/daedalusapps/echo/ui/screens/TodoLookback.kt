package com.daedalusapps.echo.ui.screens

/** Preset lookback options shown in the "update from recordings" dialog. */
data class LookbackOption(val hours: Long, val label: String)

val LOOKBACK_OPTIONS = listOf(
    LookbackOption(24L, "Last 24 hours"),
    LookbackOption(72L, "Last 3 days"),
    LookbackOption(168L, "Last week"),
    LookbackOption(-1L, "All recordings")
)

const val TODO_LOOKBACK_HOURS_KEY = "todo_lookback_hours"
const val TODO_LOOKBACK_HOURS_DEFAULT = 72L

sealed class LookbackSelection {
    data class Standard(val hours: Long) : LookbackSelection()
    data class Custom(val hours: Long) : LookbackSelection()
}

fun lookbackOptionFor(hours: Long): LookbackSelection {
    return if (LOOKBACK_OPTIONS.any { it.hours == hours }) LookbackSelection.Standard(hours)
    else LookbackSelection.Custom(hours)
}
