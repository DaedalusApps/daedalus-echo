package com.daedalusapps.echo.data

import com.daedalusapps.echo.data.db.Converters
import com.daedalusapps.echo.data.db.RecordingDao
import com.daedalusapps.echo.data.model.Recording
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

class RecordingRepository(private val dao: RecordingDao) {

    val allRecordings: Flow<List<Recording>> = dao.getAllFlow()

    fun search(query: String): Flow<List<Recording>> {
        val ftsQuery = buildFtsMatchQuery(query) ?: return flowOf(emptyList())
        return dao.searchFtsFlow(ftsQuery)
    }

    companion object {
        internal fun buildFtsMatchQuery(query: String): String? {
            val tokens = Regex("[\\p{L}\\p{N}]+").findAll(query).map { it.value }.toList()
            if (tokens.isEmpty()) return null
            return tokens.joinToString(" ") { "$it*" }
        }
    }

    suspend fun get(filename: String): Recording? = dao.get(filename)

    suspend fun save(recording: Recording) = dao.upsert(recording)

    suspend fun updateTranscript(filename: String, transcript: String) {
        val r = dao.get(filename) ?: Recording(filename = filename)
        dao.upsert(r.copy(transcript = transcript))
    }

    suspend fun delete(recording: Recording) = dao.delete(recording)

    suspend fun updateTitleAndSummary(filename: String, title: String, shortSummary: String) =
        dao.updateTitleAndSummary(filename, title, shortSummary)

    suspend fun updateEmbedding(filename: String, embedding: FloatArray) {
        val bytes = Converters().fromFloatArray(embedding) ?: return
        dao.updateEmbeddingBytes(filename, bytes)
    }

    fun semanticSearch(
        queryEmbedding: FloatArray,
        candidates: List<Recording>,
        topK: Int = 5,
        minScore: Float = Float.NEGATIVE_INFINITY
    ): List<Recording> {
        return candidates
            .mapNotNull { r ->
                val emb = r.embedding ?: return@mapNotNull null
                val score = cosineSimilarity(queryEmbedding, emb)
                r to score
            }
            .filter { it.second >= minScore }
            .sortedByDescending { it.second }
            .take(topK)
            .map { it.first }
    }

    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) return 0f
        var dot = 0f
        for (i in a.indices) dot += a[i] * b[i]
        return dot
    }

    suspend fun updateSummary(
        filename: String,
        summary: String,
        mindMap: String,
        title: String = "",
        shortSummary: String = "",
        topics: List<String> = emptyList()
    ) {
        val r = dao.get(filename) ?: Recording(filename = filename)
        dao.upsert(r.copy(
            summary = summary,
            mindMap = mindMap,
            title = title,
            shortSummary = shortSummary,
            topics = topics
        ))
    }
}
