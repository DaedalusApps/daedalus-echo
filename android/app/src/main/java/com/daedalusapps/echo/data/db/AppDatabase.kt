package com.daedalusapps.echo.data.db

import android.content.Context
import androidx.annotation.VisibleForTesting
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.daedalusapps.echo.data.model.Recording
import com.daedalusapps.echo.data.model.RecordingFts
import com.daedalusapps.echo.data.model.TodoItem

@Database(entities = [Recording::class, TodoItem::class, RecordingFts::class], version = 8, exportSchema = true)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun recordingDao(): RecordingDao

    abstract fun todoDao(): TodoDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE recordings ADD COLUMN embedding BLOB")
            }
        }

        internal val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS todos (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        text TEXT NOT NULL,
                        isDone INTEGER NOT NULL DEFAULT 0,
                        createdAt INTEGER NOT NULL,
                        sourceFilename TEXT,
                        isAiGenerated INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent()
                )
            }
        }

        internal val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE VIRTUAL TABLE IF NOT EXISTS `recordings_fts` USING FTS4(`filename` TEXT NOT NULL, `transcript` TEXT NOT NULL, `summary` TEXT NOT NULL, content=`recordings`)"
                )
                db.execSQL("DROP TRIGGER IF EXISTS recordings_ai")
                db.execSQL("DROP TRIGGER IF EXISTS recordings_ad")
                db.execSQL("DROP TRIGGER IF EXISTS recordings_au")
                db.execSQL(
                    "INSERT INTO recordings_fts(docid, filename, transcript, summary) " +
                        "SELECT rowid, filename, transcript, summary FROM recordings"
                )
            }
        }

        @VisibleForTesting
        internal fun buildDatabase(context: Context, name: String): AppDatabase {
            return Room.databaseBuilder(
                context,
                AppDatabase::class.java,
                name
            )
            .addMigrations(MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8)
            .build()
        }

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: buildDatabase(context.applicationContext, "daedalus_echo.db").also { INSTANCE = it }
            }
        }
    }
}
