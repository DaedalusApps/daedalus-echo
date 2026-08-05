package com.daedalusapps.echo

import com.daedalusapps.echo.ai.expandWithTopicSiblings
import com.daedalusapps.echo.data.model.Recording
import org.junit.Assert.assertEquals
import org.junit.Test

class GraphRetrievalTest {

    @Test
    fun expandWithTopicSiblings_siblingSharingTopic_appendedAfterSeeds() {
        val seed = Recording(filename = "seed.mp3", title = "Seed", shortSummary = "s", topics = listOf("AI"))
        val sibling = Recording(filename = "sib.mp3", title = "Sibling", shortSummary = "sib", topics = listOf("AI"))
        val unrelated = Recording(filename = "unrelated.mp3", title = "Unrelated", shortSummary = "u", topics = listOf("Cooking"))

        val result = expandWithTopicSiblings(
            seeds = listOf(seed),
            all = listOf(seed, sibling, unrelated),
            budgetChars = 10_000
        )

        assertEquals(listOf(seed, sibling), result)
    }

    @Test
    fun expandWithTopicSiblings_ranksByMoreSharedTopicsThenNewer() {
        val seed = Recording(
            filename = "seed.mp3", title = "Seed", shortSummary = "s",
            topics = listOf("AI", "Android"), createdAt = 1000
        )
        val oneShared = Recording(
            filename = "one.mp3", title = "One", shortSummary = "o",
            topics = listOf("AI"), createdAt = 5000
        )
        val twoSharedOlder = Recording(
            filename = "twoOld.mp3", title = "TwoOld", shortSummary = "to",
            topics = listOf("AI", "Android"), createdAt = 2000
        )
        val twoSharedNewer = Recording(
            filename = "twoNew.mp3", title = "TwoNew", shortSummary = "tn",
            topics = listOf("AI", "Android"), createdAt = 3000
        )

        val result = expandWithTopicSiblings(
            seeds = listOf(seed),
            all = listOf(seed, oneShared, twoSharedOlder, twoSharedNewer),
            budgetChars = 10_000
        )

        assertEquals(listOf(seed, twoSharedNewer, twoSharedOlder, oneShared), result)
    }

    @Test
    fun expandWithTopicSiblings_seedsNeverEvicted_budgetCapsSiblings() {
        val seed = Recording(filename = "seed.mp3", title = "Seed", shortSummary = "s".repeat(50), topics = listOf("AI"))
        val fitsSibling = Recording(filename = "fits.mp3", title = "Fits", shortSummary = "f".repeat(30), topics = listOf("AI"), createdAt = 2000)
        val tooBigSibling = Recording(filename = "big.mp3", title = "Big", shortSummary = "b".repeat(100), topics = listOf("AI"), createdAt = 1000)

        // seed = 50 chars. budget = 90 -> fitsSibling (30) fits (50+30=80<=90), tooBigSibling (100) doesn't (80+100>90)
        val result = expandWithTopicSiblings(
            seeds = listOf(seed),
            all = listOf(seed, fitsSibling, tooBigSibling),
            budgetChars = 90
        )

        assertEquals(listOf(seed, fitsSibling), result)
    }

    @Test
    fun expandWithTopicSiblings_noTopicsAnywhere_seedsUnchanged() {
        val seed = Recording(filename = "seed.mp3", title = "Seed", shortSummary = "s", topics = emptyList())
        val other = Recording(filename = "other.mp3", title = "Other", shortSummary = "o", topics = emptyList())

        val result = expandWithTopicSiblings(
            seeds = listOf(seed),
            all = listOf(seed, other),
            budgetChars = 10_000
        )

        assertEquals(listOf(seed), result)
    }

    @Test
    fun expandWithTopicSiblings_blankTopicsDoNotLinkNotes() {
        val seed = Recording(filename = "seed.mp3", title = "Seed", shortSummary = "s", topics = listOf("  "))
        val other = Recording(filename = "other.mp3", title = "Other", shortSummary = "o", topics = listOf(""))

        val result = expandWithTopicSiblings(
            seeds = listOf(seed),
            all = listOf(seed, other),
            budgetChars = 10_000
        )

        assertEquals(listOf(seed), result)
    }

    @Test
    fun expandWithTopicSiblings_topicMatchingIsCaseAndWhitespaceInsensitive() {
        val seed = Recording(filename = "seed.mp3", title = "Seed", shortSummary = "s", topics = listOf(" AI "))
        val sibling = Recording(filename = "sib.mp3", title = "Sibling", shortSummary = "sib", topics = listOf("ai"))

        val result = expandWithTopicSiblings(
            seeds = listOf(seed),
            all = listOf(seed, sibling),
            budgetChars = 10_000
        )

        assertEquals(listOf(seed, sibling), result)
    }
}
