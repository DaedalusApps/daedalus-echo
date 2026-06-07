package com.daedalus.voicenotes.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "recordings")
data class Recording(
    @PrimaryKey val filename: String,   // e.g. "20260507121415.mp3"
    val localPath: String = "",         // path if imported locally
    val sizeBytes: Long = 0,
    val transcript: String = "",
    val summary: String = "",
    val mindMap: String = "",
    val category: Int = 1,
    val createdAt: Long = System.currentTimeMillis(),
    val title: String = "",
    val shortSummary: String = "",
    val topics: List<String> = emptyList(),
    val durationMillis: Long = 0,
    @ColumnInfo(typeAffinity = ColumnInfo.BLOB) val embedding: FloatArray? = null
)
