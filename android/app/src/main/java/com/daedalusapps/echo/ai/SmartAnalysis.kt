package com.daedalusapps.echo.ai

data class SmartAnalysis(
    val title: String = "",
    val shortSummary: String = "",
    val topics: List<String> = emptyList(),
    val fullSummary: String = "",
    val mindMap: String = ""
)

object SmartAnalysisParser {

    fun parse(rawResponse: String): SmartAnalysis {
        return try {
            val json = tryParseJson(rawResponse)
            if (json != null && (json.title.isNotBlank() || json.fullSummary.isNotBlank() || json.mindMap.isNotBlank())) return json
            tryParseMarkdown(rawResponse) ?: SmartAnalysis(fullSummary = rawResponse)
        } catch (e: Exception) {
            SmartAnalysis(fullSummary = rawResponse)
        }
    }

    // Handles JSON format: {"title": "...", "mindMap": "...", ...}
    private fun tryParseJson(raw: String): SmartAnalysis? {
        val title = extractJsonField(raw, "title")
        val shortSummary = extractJsonField(raw, "shortSummary")
        val fullSummary = extractJsonField(raw, "fullSummary")
        val mindMap = extractJsonField(raw, "mindMap")
        val topicsStr = extractJsonField(raw, "topics")

        if (title.isBlank() && fullSummary.isBlank() && mindMap.isBlank()) return null

        val topics = Regex(""""([^"\\]*)"""").findAll(topicsStr)
            .map { it.groupValues[1] }
            .filter { it.isNotBlank() }
            .toList()

        val mindMapNormalized = when {
            mindMap.trimStart().startsWith("{") || mindMap.contains("\"item\"") || mindMap.contains("\"sub\"") ->
                Regex(""""(?:item|label|text|sub|point|detail|topic|subject)"\s*:\s*"([^"]+)"""")
                    .findAll(mindMap).map { "- ${it.groupValues[1].trim()}" }
                    .filter { it.length > 2 }.joinToString("\n")
            mindMap.trimStart().startsWith("\"") ->
                Regex(""""([^"]+)"""").findAll(mindMap)
                    .map { it.groupValues[1].trim() }.filter { it.isNotBlank() }.joinToString("\n")
            else -> mindMap
        }

        return SmartAnalysis(
            title = title,
            shortSummary = shortSummary,
            topics = topics,
            fullSummary = fullSummary.ifBlank { raw },
            mindMap = mindMapNormalized
        )
    }

    // Handles markdown list format Gemma 3 1B produces:
    //   - title: Some Title
    //   - topics:
    //     - item one
    //   -mindMap:
    //     - bullet
    //   -fullSummary: Long text here...
    private fun tryParseMarkdown(raw: String): SmartAnalysis? {
        val fields = mutableMapOf<String, MutableList<String>>()
        var currentField: String? = null

        for (line in raw.lines()) {
            val fieldMatch = Regex("""^\s*-\s*(\w+)\s*:\s*(.*)""").matchEntire(line)
            if (fieldMatch != null) {
                val key = fieldMatch.groupValues[1]
                val value = fieldMatch.groupValues[2].trim()
                currentField = key
                if (value.isNotEmpty()) fields.getOrPut(key) { mutableListOf() }.add(value)
                else fields.getOrPut(key) { mutableListOf() }
            } else if (currentField != null) {
                val subMatch = Regex("""^\s+-\s+(.+)""").matchEntire(line)
                if (subMatch != null) {
                    val item = subMatch.groupValues[1].trim()
                    if (item.isNotEmpty()) fields[currentField]!!.add(item)
                }
            }
        }

        val knownKeys = setOf("title", "shortSummary", "fullSummary", "mindMap", "topics")
        if (fields.keys.none { it in knownKeys }) return null

        return SmartAnalysis(
            title = fields["title"]?.firstOrNull() ?: "",
            shortSummary = fields["shortSummary"]?.joinToString(" ") ?: "",
            topics = fields["topics"] ?: emptyList(),
            fullSummary = fields["fullSummary"]?.joinToString(" ") ?: "",
            mindMap = (fields["mindMap"] ?: emptyList()).joinToString("\n") { "- $it" }
        )
    }

    private fun extractJsonField(json: String, field: String): String {
        val regex = Regex(""""$field"\s*:\s*(?:"([^"\\]*(?:\\.[^"\\]*)*)"|\[([^\]]*)\])""", RegexOption.DOT_MATCHES_ALL)
        val match = regex.find(json) ?: return ""
        return match.groupValues[1].takeIf { it.isNotBlank() } ?: match.groupValues[2]
    }
}
