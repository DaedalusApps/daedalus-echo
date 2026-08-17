package com.daedalusapps.echo.data.model

import androidx.room.Entity
import androidx.room.Fts4

/**
 * FTS4 index over [Recording], external-content-backed so the transcript and summary
 * text isn't duplicated in a second table -- SQLite stores only the tokenized index here
 * and reads the actual column values back from `recordings`.
 *
 * External content tables are kept in sync by Room's generated sync triggers
 * (`room_fts_content_sync_recordings_fts_*`) during `onPostMigrate`.
 */
@Fts4(contentEntity = Recording::class)
@Entity(tableName = "recordings_fts")
data class RecordingFts(
    val filename: String,
    val transcript: String,
    val summary: String
)
