package com.daedalusapps.echo

/**
 * Single source of truth for the debug-only ADB test-harness action set.
 *
 * [HANDLED] is every action MainActivity's dynamic receiver has a `when` branch for.
 * [REGISTERED] is the subset actually wired onto the dynamic IntentFilter (and mirrored in
 * AndroidManifest.xml's `.AdbReceiver` declaration). Building the IntentFilter from
 * [REGISTERED] instead of a hand-typed list makes "a handler with no registration"
 * impossible to express by omission.
 */
object AdbActions {
    const val ANALYZE = "com.daedalusapps.echo.ANALYZE"
    const val START_RECORDING = "com.daedalusapps.echo.START_RECORDING"
    const val STOP_RECORDING = "com.daedalusapps.echo.STOP_RECORDING"
    const val FORMAT_PARAGRAPHS = "com.daedalusapps.echo.FORMAT_PARAGRAPHS"
    const val SEARCH_FTS = "com.daedalusapps.echo.SEARCH_FTS"

    /** Every action the dynamic receiver's `when` block handles. */
    val HANDLED: List<String> = listOf(
        ANALYZE, START_RECORDING, STOP_RECORDING, FORMAT_PARAGRAPHS, SEARCH_FTS
    )

    /**
     * Actions registered on the dynamic IntentFilter in MainActivity.onCreate — the sole source
     * of truth for that filter (the manifest's `.AdbReceiver` declaration carries no
     * intent-filter of its own). Equal to [HANDLED] today.
     */
    val REGISTERED: List<String> = HANDLED
}
