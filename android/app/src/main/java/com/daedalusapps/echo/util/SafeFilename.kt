package com.daedalusapps.echo.util

/**
 * Shared filename allowlist. Recording filenames come from `adb shell am broadcast` extras,
 * and backup JSON — all attacker-influenced, and all get used to build [java.io.File] paths
 * or look up records directly.
 *
 * `.`, `..`, and any other name consisting solely of dots (e.g. `....`) are rejected even though
 * they pass the character allowlist below: they are never a legitimate recording or import name
 * and are path-traversal shaped.
 */
object SafeFilename {
    private val ALLOWED_CHARS = Regex("[A-Za-z0-9._-]+")
    private val DOTS_ONLY = Regex("\\.+")

    /**
     * True if [name] is non-blank, contains only letters, digits, `.`, `_`, `-`, and is not
     * composed entirely of dots (`.`, `..`, `....`, etc).
     */
    fun isSafe(name: String): Boolean =
        name.isNotBlank() && name.matches(ALLOWED_CHARS) && !name.matches(DOTS_ONLY)
}
