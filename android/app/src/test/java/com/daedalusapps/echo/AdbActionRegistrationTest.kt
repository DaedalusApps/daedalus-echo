package com.daedalusapps.echo

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * Structural guard against the class of bug where MainActivity's debug-only ADB
 * BroadcastReceiver has a `when` branch (a "handled" action) with no matching entry in the
 * dynamic IntentFilter built in onCreate (a "registered" action), so the branch can never run.
 *
 * [AdbActions] is the single source of truth both MainActivity and this test read from. This
 * test does not just assert that object equals itself — it checks that MainActivity's `when`
 * block actually has a branch referencing every [AdbActions.HANDLED] constant, and that onCreate
 * actually builds its IntentFilter from [AdbActions.REGISTERED] rather than a hand-typed list.
 *
 * This file also guards the security fix: AndroidManifest.xml's `.AdbReceiver` declaration must stay
 * `android:exported="true"` (adb shell needs that) but must carry no `<intent-filter>` (an
 * intent-filter would let any other installed app reach it via an IMPLICIT broadcast), and the dynamic
 * receiver registered in onCreate must use `ContextCompat.RECEIVER_NOT_EXPORTED` since it is only
 * ever reached via `.AdbReceiver`'s in-package forward. This closes both the implicit-broadcast
 * path (no intent-filter) and the explicit-component residual (`.AdbReceiver` now also declares
 * `android:permission="android.permission.DUMP"`, so only a sender holding that
 * signature|privileged|development permission — i.e. adb shell — can deliver to it at all).
 */
class AdbActionRegistrationTest {

    private val moduleRoot: File by lazy { findModuleRoot() }
    private val mainActivitySource: String by lazy {
        File(moduleRoot, "src/main/java/com/daedalusapps/echo/MainActivity.kt").readText()
    }
    /** Comments stripped so a comment claiming to do the right thing can't satisfy a
     *  `.contains(...)` check that was meant to verify real code. */
    private val strippedMainActivitySource: String by lazy { stripComments(mainActivitySource) }
    private val receiverWhenBlock: String by lazy { extractReceiverWhenBlock(strippedMainActivitySource) }
    private val onCreateSource: String by lazy {
        strippedMainActivitySource.substringAfter("override fun onCreate")
    }

    private val adbReceiverElement: Element by lazy {
        parseManifestAdbReceiverElement(File(moduleRoot, "src/main/AndroidManifest.xml"))
    }

    @Test
    fun `every AdbActions HANDLED constant has a when branch in MainActivity`() {
        val missingBranches = AdbActions.HANDLED.filterNot { action ->
            receiverWhenBlock.contains("AdbActions.${constantNameOf(action)} ->")
        }
        assertTrue(
            "MainActivity's adbReceiver when-block has no branch for: $missingBranches. " +
                "Every action in AdbActions.HANDLED must be dispatched via its AdbActions " +
                "constant so it stays covered by this check.",
            missingBranches.isEmpty()
        )
    }

    @Test
    fun `when block has no stray branches outside AdbActions HANDLED`() {
        val branchCount = Regex("""AdbActions\.[A-Z0-9_]+\s*->""").findAll(receiverWhenBlock).count()
        assertEquals(
            "The when-block's branch count must match AdbActions.HANDLED.size exactly — a " +
                "branch using a raw string literal instead of an AdbActions constant would be " +
                "invisible to the previous check.",
            AdbActions.HANDLED.size,
            branchCount
        )
    }

    @Test
    fun `onCreate builds the dynamic IntentFilter from AdbActions REGISTERED`() {
        assertTrue(
            "onCreate must build the debug IntentFilter by iterating AdbActions.REGISTERED " +
                "(the single source of truth) rather than a separately hand-typed action list. " +
                "(Checked on comment-stripped source.)",
            onCreateSource.contains("AdbActions.REGISTERED")
        )
    }

    @Test
    fun `MainActivity contains no hand-typed addAction string literal`() {
        val literalAddActionCalls = Regex("""addAction\(\s*"[^"]*"""")
            .findAll(strippedMainActivitySource)
            .map { it.value }
            .toList()
        assertTrue(
            "MainActivity.kt must call addAction(...) only via AdbActions.REGISTERED.forEach — " +
                "found hand-typed literal call(s): $literalAddActionCalls",
            literalAddActionCalls.isEmpty()
        )
    }

    @Test
    fun `the debug IntentFilter registration is gated by BuildConfig DEBUG`() {
        val debugGateBlock = extractDebugGateBlock(strippedMainActivitySource)
        assertTrue(
            "The BuildConfig.DEBUG-gated block in onCreate must build the filter from " +
                "AdbActions.REGISTERED and register adbReceiver with it.",
            debugGateBlock.contains("AdbActions.REGISTERED") &&
                debugGateBlock.contains("registerReceiver(this, adbReceiver, filter")
        )
    }

    @Test
    fun `the debug IntentFilter registration uses RECEIVER_NOT_EXPORTED`() {
        val debugGateBlock = extractDebugGateBlock(strippedMainActivitySource)
        assertTrue(
            "The BuildConfig.DEBUG-gated block in onCreate must register adbReceiver with " +
                "ContextCompat.RECEIVER_NOT_EXPORTED, not RECEIVER_EXPORTED — this receiver is " +
                "only ever reached via AdbReceiver's same-package forward, and exporting it lets " +
                "any app on the device trigger actions directly.",
            debugGateBlock.contains("ContextCompat.RECEIVER_NOT_EXPORTED")
        )
        assertTrue(
            "The BuildConfig.DEBUG-gated block must not reference ContextCompat.RECEIVER_EXPORTED " +
                "at all, even alongside a correct RECEIVER_NOT_EXPORTED reference — any use of the " +
                "EXPORTED constant here re-exports the receiver to every app on the device.",
            !debugGateBlock.contains("ContextCompat.RECEIVER_EXPORTED")
        )
    }

    @Test
    fun `the dynamic receiver ignores broadcasts AdbReceiver has not forwarded`() {
        val onReceiveBody = extractOnReceiveBody(strippedMainActivitySource)
        val whenIndex = onReceiveBody.indexOf("when (intent")
        assertTrue("Could not find the when-block inside onReceive", whenIndex >= 0)
        val guardSnippet = onReceiveBody.substring(0, whenIndex)
        assertTrue(
            "onReceive must check the \"_forwarded\" extra before dispatching",
            guardSnippet.contains("_forwarded")
        )
        assertTrue(
            "The \"_forwarded\" check must return early (not just log) when the broadcast was " +
                "not forwarded by AdbReceiver, or an implicit -a ACTION broadcast still reaches " +
                "the when-block directly and double-dispatches.",
            guardSnippet.contains("return")
        )
    }

    @Test
    fun `manifest AdbReceiver declares no intent-filter`() {
        val intentFilterCount = adbReceiverElement.getElementsByTagName("intent-filter").length
        assertEquals(
            "AndroidManifest.xml's .AdbReceiver must have no <intent-filter>. An intent-filter " +
                "reopens implicit third-party delivery to a receiver; adb shell only ever uses the " +
                "explicit -n component form, which does not need one.",
            0,
            intentFilterCount
        )
    }

    @Test
    fun `manifest AdbReceiver stays exported for adb shell delivery`() {
        assertEquals(
            "AndroidManifest.xml's .AdbReceiver must keep android:exported=\"true\" — without " +
                "it, adb shell's explicit -n broadcasts stop being delivered and the whole ADB " +
                "test harness breaks.",
            "true",
            adbReceiverElement.getAttribute("android:exported")
        )
    }

    @Test
    fun `manifest AdbReceiver requires DUMP permission from the sender`() {
        assertEquals(
            "AndroidManifest.xml's .AdbReceiver must declare " +
                "android:permission=\"android.permission.DUMP\". Without it, any app on a debug " +
                "build can deliver an EXPLICIT-component broadcast to the receiver via the in-package forward.",
            "android.permission.DUMP",
            adbReceiverElement.getAttribute("android:permission")
        )
    }

    @Test
    fun `ANALYZE and FORMAT_PARAGRAPHS branches guard filename with SafeFilename`() {
        val analyzeBranch = extractWhenBranch(receiverWhenBlock, "AdbActions.ANALYZE")
        val formatParagraphsBranch = extractWhenBranch(receiverWhenBlock, "AdbActions.FORMAT_PARAGRAPHS")
        assertTrue(
            "The AdbActions.ANALYZE branch must guard its filename with SafeFilename.isSafe.",
            analyzeBranch.contains("SafeFilename")
        )
        assertTrue(
            "The AdbActions.FORMAT_PARAGRAPHS branch must guard its filename with SafeFilename.isSafe.",
            formatParagraphsBranch.contains("SafeFilename")
        )
    }

    @Test
    fun `no variant manifest other than src main reintroduces AdbReceiver`() {
        val srcDir = File(moduleRoot, "src")
        val offendingManifests = srcDir.walkTopDown()
            .filter { it.isFile && it.name == "AndroidManifest.xml" }
            .filterNot { it.canonicalFile == File(moduleRoot, "src/main/AndroidManifest.xml").canonicalFile }
            .filter { manifestDeclaresAdbReceiver(it) }
            .map { it.path }
            .toList()
        assertTrue(
            "Found AndroidManifest.xml file(s) other than src/main declaring .AdbReceiver: " +
                "$offendingManifests. A variant or library manifest can merge an <intent-filter> " +
                "back onto .AdbReceiver via manifest merger and defeat the guard, even though " +
                "src/main/AndroidManifest.xml itself has none.",
            offendingManifests.isEmpty()
        )
    }

    companion object {
        /** "com.daedalusapps.echo.ANALYZE" -> "ANALYZE", matching AdbActions' own naming. */
        private fun constantNameOf(action: String): String = action.substringAfterLast('.')

        /**
         * Strips `//` line comments and `/* */` block comments from Kotlin source so source-text
         * assertions can't be satisfied by a comment instead of real code.
         */
        private fun stripComments(source: String): String {
            val noBlockComments = source.replace(Regex("""/\*[\s\S]*?\*/"""), "")
            return noBlockComments.lineSequence().joinToString("\n") { line ->
                val idx = line.indexOf("//")
                if (idx >= 0) line.substring(0, idx) else line
            }
        }

        /** Slices out the adbReceiver's whole `onReceive` function body (guard clause and all). */
        private fun extractOnReceiveBody(source: String): String {
            val start = source.indexOf("override fun onReceive")
            require(start >= 0) { "Could not find 'override fun onReceive' in MainActivity.kt" }
            return balancedBraceBlock(source, source.indexOf('{', start))
        }

        /** Slices out just the adbReceiver's `onReceive` `when` block. */
        private fun extractReceiverWhenBlock(source: String): String {
            val start = source.indexOf("when (intent")
            require(start >= 0) { "Could not find the when-block in MainActivity.kt's onReceive" }
            return balancedBraceBlock(source, source.indexOf('{', start))
        }

        /** Slices out a single `AdbActions.X -> { ... }` branch's body from a `when`-block's text. */
        private fun extractWhenBranch(whenBlock: String, branchLabel: String): String {
            val labelStart = whenBlock.indexOf("$branchLabel ->")
            require(labelStart >= 0) { "Could not find branch '$branchLabel ->' in the when-block" }
            val openBrace = whenBlock.indexOf('{', labelStart)
            require(openBrace >= 0) { "Branch '$branchLabel' has no '{' body" }
            return balancedBraceBlock(whenBlock, openBrace)
        }

        /** Slices out the `if (BuildConfig.DEBUG) { ... }` block in onCreate. */
        private fun extractDebugGateBlock(source: String): String {
            val start = source.indexOf("if (BuildConfig.DEBUG)")
            require(start >= 0) { "Could not find 'if (BuildConfig.DEBUG)' in MainActivity.kt" }
            return balancedBraceBlock(source, source.indexOf('{', start))
        }

        /** Returns the text between a `{` at [openBraceIndex] and its matching `}`. */
        private fun balancedBraceBlock(source: String, openBraceIndex: Int): String {
            var depth = 0
            for (i in openBraceIndex until source.length) {
                when (source[i]) {
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) return source.substring(openBraceIndex, i + 1)
                    }
                }
            }
            error("Unbalanced braces starting at index $openBraceIndex")
        }

        /** Locates the `<receiver android:name=".AdbReceiver">` element in AndroidManifest.xml. */
        private fun parseManifestAdbReceiverElement(manifestFile: File): Element {
            val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(manifestFile)
            val receivers = doc.getElementsByTagName("receiver")
            for (i in 0 until receivers.length) {
                val receiver = receivers.item(i) as Element
                if (receiver.getAttribute("android:name") == ".AdbReceiver") return receiver
            }
            error("Could not find a <receiver android:name=\".AdbReceiver\"> in AndroidManifest.xml")
        }

        /** True if [manifestFile] declares a `<receiver android:name=".AdbReceiver">`. */
        private fun manifestDeclaresAdbReceiver(manifestFile: File): Boolean {
            val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(manifestFile)
            val receivers = doc.getElementsByTagName("receiver")
            for (i in 0 until receivers.length) {
                val receiver = receivers.item(i) as Element
                if (receiver.getAttribute("android:name") == ".AdbReceiver") return true
            }
            return false
        }

        /** Walks up from the test JVM's working directory to find the `:app` module root. */
        private fun findModuleRoot(): File {
            var dir: File? = File(".").canonicalFile
            repeat(6) {
                val candidate = dir?.let { File(it, "src/main/java/com/daedalusapps/echo/MainActivity.kt") }
                if (candidate != null && candidate.exists()) return dir!!
                dir = dir?.parentFile
            }
            error("Could not locate the :app module root (looked for src/main/java/com/daedalusapps/echo/MainActivity.kt) from ${File(".").canonicalPath}")
        }
    }
}
