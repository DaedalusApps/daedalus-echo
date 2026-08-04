package com.daedalusapps.echo.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Red tests for issue #18: pure Gemma chat-template builder for multi-turn support.
 */
class LocalLlmPromptBuilderTest {

    // (a) 3-turn history (user/model/user) interleaves correctly; system prompt only in
    // the first user block; prompt ends with an opened model turn.
    @Test
    fun threeTurnHistory_interleavesCorrectly() {
        val turns = listOf(
            ChatTurn(Role.USER, "hello"),
            ChatTurn(Role.MODEL, "hi there"),
            ChatTurn(Role.USER, "how are you")
        )

        val prompt = buildGemmaPrompt("You are helpful.", turns)

        val expected = "<start_of_turn>user\n" +
            "You are helpful.\n\n" +
            "hello<end_of_turn>\n" +
            "<start_of_turn>model\n" +
            "hi there<end_of_turn>\n" +
            "<start_of_turn>user\n" +
            "how are you<end_of_turn>\n" +
            "<start_of_turn>model\n"

        assertEquals(expected, prompt)
    }

    // (b) single-turn delegation produces the exact current (pre-change) template string.
    @Test
    fun singleTurn_matchesGoldenCurrentFormat() {
        val systemPrompt = "You are helpful."
        val userText = "hello"

        val prompt = buildGemmaPrompt(systemPrompt, listOf(ChatTurn(Role.USER, userText)))

        val golden = "<start_of_turn>user\n" +
            systemPrompt + "\n\n" +
            userText + "<end_of_turn>\n" +
            "<start_of_turn>model\n"

        assertEquals(golden, prompt)
    }

    // (c) empty turns: last turn must be USER, otherwise throw.
    @Test
    fun emptyTurns_throwsIllegalArgument() {
        assertThrows(IllegalArgumentException::class.java) {
            buildGemmaPrompt("sys", emptyList())
        }
    }

    // (c) last turn must be USER, otherwise throw.
    @Test
    fun lastTurnNotUser_throwsIllegalArgument() {
        assertThrows(IllegalArgumentException::class.java) {
            buildGemmaPrompt("sys", listOf(ChatTurn(Role.USER, "hi"), ChatTurn(Role.MODEL, "hey")))
        }
    }

    // (d) consecutive same-role turns are rejected.
    @Test
    fun consecutiveSameRoleTurns_throwsIllegalArgument() {
        assertThrows(IllegalArgumentException::class.java) {
            buildGemmaPrompt(
                "sys",
                listOf(ChatTurn(Role.USER, "hi"), ChatTurn(Role.USER, "again"))
            )
        }
    }

    // (e) MODEL-first turn list must throw, not silently drop the system prompt.
    @Test
    fun modelFirstTurns_throwsIllegalArgument() {
        assertThrows(IllegalArgumentException::class.java) {
            buildGemmaPrompt(
                "sys",
                listOf(ChatTurn(Role.MODEL, "hi there"), ChatTurn(Role.USER, "how are you"))
            )
        }
    }
}
