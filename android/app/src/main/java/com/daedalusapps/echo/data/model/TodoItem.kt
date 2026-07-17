package com.daedalusapps.echo.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "todos")
data class TodoItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val text: String,
    val isDone: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val sourceFilename: String? = null,
    val isAiGenerated: Boolean = false
)
