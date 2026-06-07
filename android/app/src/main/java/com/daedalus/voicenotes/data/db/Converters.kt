package com.daedalus.voicenotes.data.db

import androidx.room.TypeConverter
import java.nio.ByteBuffer

class Converters {
    @TypeConverter
    fun fromStringList(value: List<String>): String = value.joinToString("|")

    @TypeConverter
    fun toStringList(value: String): List<String> =
        if (value.isEmpty()) emptyList() else value.split("|")

    @TypeConverter
    fun fromFloatArray(value: FloatArray?): ByteArray? {
        value ?: return null
        val buf = ByteBuffer.allocate(value.size * 4)
        value.forEach { buf.putFloat(it) }
        return buf.array()
    }

    @TypeConverter
    fun toFloatArray(value: ByteArray?): FloatArray? {
        value ?: return null
        val buf = ByteBuffer.wrap(value)
        return FloatArray(value.size / 4) { buf.getFloat() }
    }
}
