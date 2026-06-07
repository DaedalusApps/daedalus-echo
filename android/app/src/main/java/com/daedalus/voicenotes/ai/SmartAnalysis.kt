package com.daedalus.voicenotes.ai

data class SmartAnalysis(
    val title: String = "",
    val shortSummary: String = "",
    val topics: List<String> = emptyList(),
    val fullSummary: String = "",
    val mindMap: String = ""
)

object SmartAnalysisParser {
    /**
     * Parses the combined JSON analysis from the LLM.
     * Uses regex to extract fields for robustness against malformed JSON or markdown blocks.
     */
    fun parse(rawResponse: String): SmartAnalysis {
        return try {
            val title = extractField(rawResponse, "title")
            val shortSummary = extractField(rawResponse, "shortSummary")
            val fullSummary = extractField(rawResponse, "fullSummary")
            val mindMap = extractField(rawResponse, "mindMap")
            val topicsStr = extractField(rawResponse, "topics")
            
            val topics = Regex(""""([^"\\]*)"""").findAll(topicsStr)
                .map { it.groupValues[1] }
                .filter { it.isNotBlank() }
                .toList()

            // Normalize mindMap regardless of format the model chose
            val mindMapNormalized = when {
                mindMap.trimStart().startsWith("{") || mindMap.contains("\"item\"") || mindMap.contains("\"sub\"") -> {
                    // JSON object/array format — extract all string values by known keys
                    Regex(""""(?:item|label|text|sub|point|detail|topic|subject)"\s*:\s*"([^"]+)"""")
                        .findAll(mindMap)
                        .map { it.groupValues[1].trim() }
                        .filter { it.isNotBlank() }
                        .joinToString("\n") { "- $it" }
                }
                mindMap.trimStart().startsWith("\"") -> {
                    Regex(""""([^"]+)"""").findAll(mindMap)
                        .map { it.groupValues[1].trim() }
                        .filter { it.isNotBlank() }
                        .joinToString("\n")
                }
                else -> mindMap
            }

            SmartAnalysis(
                title = title,
                shortSummary = shortSummary,
                topics = topics,
                fullSummary = if (fullSummary.isEmpty()) rawResponse else fullSummary,
                mindMap = mindMapNormalized
            )
        } catch (e: Exception) {
            SmartAnalysis(fullSummary = rawResponse)
        }
    }

    private fun extractField(json: String, field: String): String {
        // Matches "field": "value" or "field": ["value1", "value2"]
        val regex = Regex(""""$field"\s*:\s*(?:"([^"\\]*(?:\\.[^"\\]*)*)"|\[([^\]]*)\])""", RegexOption.DOT_MATCHES_ALL)
        val match = regex.find(json) ?: return ""
        return match.groupValues[1].takeIf { it.isNotBlank() } ?: match.groupValues[2]
    }
}
