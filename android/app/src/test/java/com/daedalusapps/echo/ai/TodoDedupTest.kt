package com.daedalusapps.echo.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TodoDedupTest {

    @Test
    fun normalizeTodoText_lowercasesAndStripsPunctuation() {
        assertEquals("buy milk", normalizeTodoText("Buy milk!"))
    }

    @Test
    fun normalizeTodoText_collapsesWhitespace() {
        assertEquals("buy milk", normalizeTodoText("  Buy   milk  "))
    }

    @Test
    fun normalizeTodoText_punctuationOnly_normalizesToEmpty() {
        assertEquals("", normalizeTodoText("?!?"))
        assertEquals("", normalizeTodoText(":)"))
    }

    @Test
    fun isDuplicateTodo_exactNormalizedMatch() {
        assertTrue(isDuplicateTodo("Buy milk!", listOf("buy milk")))
    }

    @Test
    fun isDuplicateTodo_containmentCandidateInExisting() {
        assertTrue(isDuplicateTodo("buy milk", listOf("buy milk tomorrow")))
    }

    @Test
    fun isDuplicateTodo_containmentExistingInCandidate() {
        assertTrue(isDuplicateTodo("buy milk tomorrow", listOf("buy milk")))
    }

    @Test
    fun isDuplicateTodo_nonDuplicatesPreserved() {
        assertFalse(isDuplicateTodo("walk the dog", listOf("buy milk")))
    }

    @Test
    fun isDuplicateTodo_emptyNormalizationNeverMatches() {
        assertFalse(isDuplicateTodo("?!?", listOf(":)")))
        assertFalse(isDuplicateTodo("", listOf("")))
    }

    @Test
    fun isDuplicateTodo_shortContainmentNotSuppressed() {
        assertFalse(isDuplicateTodo("call", listOf("call the vendor")))
    }

    @Test
    fun isDuplicateTodo_eightCharContainmentStillDuplicate() {
        assertTrue(isDuplicateTodo("buy milk", listOf("buy milk tomorrow")))
    }

    @Test
    fun isDuplicateTodo_exactMatchShortStringStillDuplicate() {
        assertTrue(isDuplicateTodo("call", listOf("Call!")))
    }

    @Test
    fun isDuplicateTodo_tokenSetParaphraseExtraStopword() {
        assertTrue(isDuplicateTodo("Buy stamps for office", listOf("buy stamps for the office")))
    }

    @Test
    fun isDuplicateTodo_tokenSetParaphraseSynonymStopword() {
        assertTrue(
            isDuplicateTodo(
                "Contact dentists office regarding insurance claim",
                listOf("Contact dentists office about insurance claim")
            )
        )
    }

    @Test
    fun isDuplicateTodo_tokenSetDifferentContentNotDuplicate() {
        assertFalse(isDuplicateTodo("Review policy details", listOf("Review budget details")))
    }

    @Test
    fun isDuplicateTodo_tokenSetShortSubsetNotSuppressed() {
        assertFalse(isDuplicateTodo("call", listOf("call the vendor")))
    }

    @Test
    fun isDuplicateTodo_tokenSetSubsetNotDuplicate() {
        assertFalse(isDuplicateTodo("call dad", listOf("call mom and dad")))
    }

    @Test
    fun isDuplicateTodo_tokenSetAllStopwordsNotDuplicate() {
        assertFalse(isDuplicateTodo("on it", listOf("for the")))
    }
}
