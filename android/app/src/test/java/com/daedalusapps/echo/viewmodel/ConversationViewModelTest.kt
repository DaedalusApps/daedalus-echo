package com.daedalusapps.echo.viewmodel

import android.app.Application
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import com.daedalusapps.echo.ai.ChatTurn
import com.daedalusapps.echo.ai.LocalLlmService
import com.daedalusapps.echo.ai.Role
import com.daedalusapps.echo.ai.buildGemmaPrompt
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Ported (text-only) subset of notetaker's ConversationViewModelTest covering session file
 * format/append/resume/parsing, consecutive-role merging, the single-shot send guard, and
 * TimeoutCancellationException-vs-CancellationException ordering (#20 / EB.3). Voice, TTS,
 * instant send, auto-listen, endSession, and the rolling-summary live context are out of scope
 * here (later issues) — see HANDOFF_NOTETAKER_PARITY.md / issue #20 for the scope split.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ConversationViewModelTest {

    private lateinit var application: Application
    private val llm = mockk<LocalLlmService>(relaxed = true)
    private val testDispatcher = StandardTestDispatcher()

    // Fixed instant so filenames/day comparisons are deterministic across the test run.
    private val nowMillis = 1_700_000_000_000L

    private fun conversationsDir(): File = File(application.filesDir, "conversations")

    @Before
    fun setup() {
        application = ApplicationProvider.getApplicationContext()
        Dispatchers.setMain(testDispatcher)
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any() as String) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0
        coEvery { llm.ensureLoaded() } returns Unit

        conversationsDir().deleteRecursively()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkStatic(Log::class)
        conversationsDir().deleteRecursively()
    }

    private fun newViewModel(): ConversationViewModel = ConversationViewModel(
        application = application,
        llm = llm,
        ioDispatcher = testDispatcher,
        clock = { nowMillis }
    )

    // (a) send() twice produces user+model messages in order, and the session file contains
    //     all four turns, in order, after two exchanges.
    @Test
    fun send_twoExchanges_appendsMessagesAndFileHasAllFourTurnsInOrder() = runTest {
        coEvery { llm.generate(any(), any<List<ChatTurn>>()) } returnsMany listOf(
            "Sounds interesting, tell me more.",
            "Great, here's a follow-up idea."
        )
        val vm = newViewModel()

        vm.send("I want to build a note app")
        advanceUntilIdle()
        vm.send("It should support voice")
        advanceUntilIdle()

        val messages = vm.messages.value
        assertEquals(4, messages.size)
        assertEquals(Role.USER, messages[0].role)
        assertEquals(Role.MODEL, messages[1].role)
        assertEquals(Role.USER, messages[2].role)
        assertEquals(Role.MODEL, messages[3].role)

        val fileContent = vm.sessionFile.readText()
        val order = listOf("**Me**", "**Agent**", "**Me**", "**Agent**")
        var lastIndex = -1
        order.forEach { marker ->
            val idx = fileContent.indexOf(marker, lastIndex + 1)
            assertTrue("expected $marker after index $lastIndex in:\n$fileContent", idx > lastIndex)
            lastIndex = idx
        }
        assertTrue(fileContent.contains("I want to build a note app"))
        assertTrue(fileContent.contains("Sounds interesting, tell me more."))
        assertTrue(fileContent.contains("It should support voice"))
        assertTrue(fileContent.contains("Great, here's a follow-up idea."))
    }

    // (b) Turns are appended incrementally: the file already has the user turn while
    //     generation is still in flight.
    @Test
    fun send_fileHasUserTurnWhileGenerationInFlight() = runTest {
        val gate = CompletableDeferred<String>()
        coEvery { llm.generate(any(), any<List<ChatTurn>>()) } coAnswers { gate.await() }
        val vm = newViewModel()

        vm.send("Thinking out loud")
        testDispatcher.scheduler.runCurrent()

        assertTrue(vm.isGenerating.value)
        val content = vm.sessionFile.readText()
        assertTrue(content.contains("**Me**"))
        assertTrue(content.contains("Thinking out loud"))
        assertFalse(content.contains("**Agent**"))

        gate.complete("ok")
        advanceUntilIdle()
    }

    // (c) LLM failure -> error state set, no MODEL message appended, user turn persisted
    //     in both the message list and the file.
    @Test
    fun send_llmThrows_setsErrorNoModelMessageUserTurnPersisted() = runTest {
        coEvery { llm.generate(any(), any<List<ChatTurn>>()) } throws RuntimeException("boom")
        val vm = newViewModel()

        vm.send("Will this fail?")
        advanceUntilIdle()

        assertEquals(1, vm.messages.value.size)
        assertEquals(Role.USER, vm.messages.value[0].role)
        assertNotNull(vm.error.value)
        assertFalse(vm.isGenerating.value)

        val content = vm.sessionFile.readText()
        assertTrue(content.contains("Will this fail?"))
        assertFalse(content.contains("**Agent**"))
    }

    // (d) Reload: a new ViewModel constructed while an unfinished session file from today
    //     exists restores its messages, and a subsequent send() continues appending to the
    //     SAME file, correctly, alongside the prior turns.
    @Test
    fun reload_existingTodaysSessionFile_restoresMessagesAndContinuesAppending() = runTest {
        coEvery { llm.generate(any(), any<List<ChatTurn>>()) } returns "First reply"
        val vm1 = newViewModel()
        vm1.send("Original message")
        advanceUntilIdle()
        val originalFile = vm1.sessionFile
        assertTrue(originalFile.exists())

        val vm2 = newViewModel()
        advanceUntilIdle()

        assertEquals(2, vm2.messages.value.size)
        assertEquals("Original message", vm2.messages.value[0].text)
        assertEquals(Role.USER, vm2.messages.value[0].role)
        assertEquals("First reply", vm2.messages.value[1].text)
        assertEquals(Role.MODEL, vm2.messages.value[1].role)
        assertEquals(originalFile.absolutePath, vm2.sessionFile.absolutePath)

        coEvery { llm.generate(any(), any<List<ChatTurn>>()) } returns "Second reply"
        vm2.send("Continuing")
        advanceUntilIdle()

        val content = originalFile.readText()
        assertTrue(content.contains("Original message"))
        assertTrue(content.contains("First reply"))
        assertTrue(content.contains("Continuing"))
        assertTrue(content.contains("Second reply"))
        assertEquals(4, vm2.messages.value.size)
    }

    // (e) After a failed generation the message list holds two USER turns in a row. The Gemma
    //     chat template rejects consecutive same-role turns, so they must be merged before the
    //     next generate() call — otherwise the session is permanently broken.
    @Test
    fun send_afterFailedGeneration_mergesConsecutiveUserTurns() = runTest {
        val captured = mutableListOf<List<ChatTurn>>()
        coEvery { llm.generate(any(), capture(captured)) } throws RuntimeException("boom")
        val vm = newViewModel()
        vm.send("First thought")
        advanceUntilIdle()

        coEvery { llm.generate(any(), capture(captured)) } returns "Recovered"
        vm.send("Second thought")
        advanceUntilIdle()

        val turns = captured.last()
        assertEquals(1, turns.size)
        assertEquals(Role.USER, turns[0].role)
        assertTrue(turns[0].text.contains("First thought"))
        assertTrue(turns[0].text.contains("Second thought"))
        // The merged turns must satisfy the real prompt builder's contract.
        buildGemmaPrompt("system", turns)
        assertEquals("Recovered", vm.messages.value.last().text)
        assertNull(vm.error.value)
    }

    // (f) A malformed session file must never crash the parser: garbage preamble, multiline
    //     bodies, a user-typed line that looks like a turn header, empty turns, trailing blanks.
    @Test
    fun reload_malformedSessionFile_parsesWithoutCrashing() = runTest {
        val dir = conversationsDir().apply { mkdirs() }
        val name = "conv_" + SimpleDateFormat("yyyyMMddHHmmss", Locale.US).format(Date(nowMillis)) + ".md"
        File(dir, name).writeText(
            """
            garbage preamble not written by us
            **Not a header**
            **Me** (09:15):
            line one
            line two

            **Agent** (09:16):
            **Me** (99:99):
            **Me** (09:17):
            quoting a header: **Me** (12:00):
            still the same message

            """.trimIndent() + "\n\n\n"
        )

        val vm = newViewModel()
        advanceUntilIdle()

        val messages = vm.messages.value
        // Preamble and the empty Agent turn are dropped; the rest survives.
        assertEquals(2, messages.size)
        assertEquals(Role.USER, messages[0].role)
        assertEquals("line one\nline two", messages[0].text)
        assertEquals(Role.USER, messages[1].role)
        assertTrue(messages[1].text.contains("still the same message"))
        assertFalse(messages.any { it.text.contains("garbage preamble") })
    }

    // Note: newSessionFile()'s collision-avoiding retry loop (ported verbatim from notetaker) is
    // only reachable in this slice's construction path when NO today-dated file exists yet — any
    // existing conv_<today>*.md is instead picked up by findTodaysSessionFile() and *resumed*, not
    // rotated past. The loop's actual collision branch only fires via startNewSession(), which is
    // out of scope here (no UI control for it yet) — see #20 scope notes. Not separately unit
    // tested for that reason; it will get direct coverage when startNewSession() is ported.

    // (P8.4-a) stopGenerating() during an in-flight generate: no model message appended, no
    //     error surfaced (cancellation is not a failure), isGenerating clears, and the user's
    //     turn stays in both the message list and the session file. A subsequent send() must
    //     work normally afterward.
    @Test
    fun stopGenerating_duringGeneration_noModelMessageNoErrorUserPersistedNextSendWorks() = runTest {
        val gate = CompletableDeferred<String>()
        coEvery { llm.generate(any(), any<List<ChatTurn>>()) } coAnswers { gate.await() }
        val vm = newViewModel()

        vm.send("Thinking out loud")
        testDispatcher.scheduler.runCurrent()
        assertTrue(vm.isGenerating.value)

        vm.stopGenerating()
        advanceUntilIdle()

        assertFalse("isGenerating must clear on cancellation", vm.isGenerating.value)
        assertNull("cancellation must not surface as an error", vm.error.value)
        assertEquals(1, vm.messages.value.size)
        assertEquals(Role.USER, vm.messages.value[0].role)
        val content = vm.sessionFile.readText()
        assertTrue(content.contains("Thinking out loud"))
        assertFalse("no model turn should have been appended", content.contains("**Agent**"))

        coEvery { llm.generate(any(), any<List<ChatTurn>>()) } returns "Reply after stop"
        vm.send("Second try")
        advanceUntilIdle()

        assertEquals(3, vm.messages.value.size)
        assertEquals(Role.MODEL, vm.messages.value[2].role)
        assertEquals("Reply after stop", vm.messages.value[2].text)
        assertNull(vm.error.value)
    }

    // (P8.4-a) generate()'s 3-minute timeout arrives as a CancellationException subtype, so it
    //     must NOT be mistaken for a stopGenerating() cancellation: it is a real failure and has
    //     to reach the user as an error, exactly as any other generation failure does.
    @Test
    fun send_llmTimesOut_setsErrorNoModelMessage() = runTest {
        coEvery { llm.generate(any(), any<List<ChatTurn>>()) } coAnswers {
            withTimeout(1) { delay(10_000) }
            "unreachable"
        }
        val vm = newViewModel()

        vm.send("Will this time out?")
        advanceUntilIdle()

        assertEquals(1, vm.messages.value.size)
        assertEquals(Role.USER, vm.messages.value[0].role)
        assertNotNull("a timeout must surface as an error", vm.error.value)
        assertFalse(vm.isGenerating.value)
        assertFalse(vm.sessionFile.readText().contains("**Agent**"))
    }

    // Single-shot guard: send() claims _isGenerating synchronously before any suspension point,
    // so a second send() call issued while a generation is already in flight is a no-op — it
    // must not queue a second user message or a second generate() call.
    @Test
    fun send_calledAgainWhileGenerating_isNoOp() = runTest {
        val gate = CompletableDeferred<String>()
        coEvery { llm.generate(any(), any<List<ChatTurn>>()) } coAnswers { gate.await() }
        val vm = newViewModel()

        vm.send("First message")
        testDispatcher.scheduler.runCurrent()
        assertTrue(vm.isGenerating.value)

        vm.send("Second message, should be dropped")
        testDispatcher.scheduler.runCurrent()

        assertEquals(1, vm.messages.value.size)
        assertEquals("First message", vm.messages.value[0].text)

        gate.complete("ok")
        advanceUntilIdle()
        assertEquals(2, vm.messages.value.size)
    }

    // send() ignores blank/whitespace-only input, and never claims _isGenerating for it.
    @Test
    fun send_blankText_isNoOp() = runTest {
        val vm = newViewModel()

        vm.send("   ")
        advanceUntilIdle()

        assertTrue(vm.messages.value.isEmpty())
        assertFalse(vm.isGenerating.value)
    }
}
