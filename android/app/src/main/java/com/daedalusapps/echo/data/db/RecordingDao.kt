package com.daedalusapps.echo.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Query
import androidx.room.Upsert
import com.daedalusapps.echo.data.model.Recording
import kotlinx.coroutines.flow.Flow

@Dao
interface RecordingDao {

    @Query("SELECT * FROM recordings ORDER BY createdAt DESC")
    fun getAllFlow(): Flow<List<Recording>>

    @Query("SELECT * FROM recordings WHERE filename = :filename")
    suspend fun get(filename: String): Recording?

    @Query("""SELECT * FROM recordings WHERE
    filename LIKE '%' || :q || '%' OR
    transcript LIKE '%' || :q || '%' OR
    summary LIKE '%' || :q || '%'
    ORDER BY createdAt DESC""")
    fun searchFlow(q: String): Flow<List<Recording>>

    @Query("SELECT * FROM recordings WHERE createdAt >= :cutoff ORDER BY createdAt DESC")
    suspend fun getSince(cutoff: Long): List<Recording>

    @Upsert
    suspend fun upsert(recording: Recording)

    @Query("UPDATE recordings SET title = :title, shortSummary = :shortSummary WHERE filename = :filename")
    suspend fun updateTitleAndSummary(filename: String, title: String, shortSummary: String)

    @Query("UPDATE recordings SET embedding = :embedding WHERE filename = :filename")
    suspend fun updateEmbeddingBytes(filename: String, embedding: ByteArray)

    @Delete
    suspend fun delete(recording: Recording)
}
