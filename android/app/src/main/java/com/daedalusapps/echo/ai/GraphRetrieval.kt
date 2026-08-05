package com.daedalusapps.echo.ai

import com.daedalusapps.echo.data.model.Recording

/** Text used per note when it's fed to the LLM, mirroring [buildLibraryQuestionPrompt]'s fallback. */
internal fun sourceText(r: Recording): String = r.shortSummary.ifBlank { r.summary.take(200) }

private fun normalizedTopics(r: Recording): Set<String> =
    r.topics.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()

/**
 * Expands the embedding-search [seeds] with topic-graph siblings from [all]: recordings that
 * share at least one topic with any seed (topic matching is case-insensitive and trimmed, as in
 * [com.daedalusapps.echo.ui.mindmap.GraphBuilder]). Siblings are ranked by number of shared topics
 * (desc), then createdAt (desc), and appended after the seeds until [budgetChars] (summed over
 * each note's [sourceText]) is exhausted. Seeds are always included and never evicted.
 */
fun expandWithTopicSiblings(
    seeds: List<Recording>,
    all: List<Recording>,
    budgetChars: Int
): List<Recording> {
    if (seeds.isEmpty()) return seeds

    val seedFilenames = seeds.map { it.filename }.toSet()
    val seedTopics = seeds.flatMap { normalizedTopics(it) }.toSet()
    if (seedTopics.isEmpty()) return seeds

    val rankedSiblings = all
        .filter { it.filename !in seedFilenames }
        .mapNotNull { candidate ->
            val shared = normalizedTopics(candidate).intersect(seedTopics).size
            if (shared > 0) candidate to shared else null
        }
        .sortedWith(
            compareByDescending<Pair<Recording, Int>> { it.second }
                .thenByDescending { it.first.createdAt }
        )
        .map { it.first }

    val result = seeds.toMutableList()
    var used = seeds.sumOf { sourceText(it).length }
    for (sibling in rankedSiblings) {
        val len = sourceText(sibling).length
        if (used + len > budgetChars) break
        result.add(sibling)
        used += len
    }
    return result
}
