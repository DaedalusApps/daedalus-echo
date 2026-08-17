package com.daedalusapps.echo.data.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Regression test: a database file left behind at a version with no
 * registered migration path to current must fail loudly on open, not be
 * silently wiped and recreated by fallbackToDestructiveMigration().
 */
@RunWith(RobolectricTestRunner::class)
class MissingMigrationTest {

    private val dbName = "missing-migration-test.db"

    @Test
    fun opening_dbWithNoMigrationPath_throwsInsteadOfWiping() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase(dbName)

        // Simulate a DB stuck at a version for which no migration was registered
        // (e.g. a future @Database version bump that forgot to add its Migration).
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(dbName), null).use { seed ->
            seed.version = 2
        }

        val db = AppDatabase.buildDatabase(context, dbName)
        try {
            assertThrows(IllegalStateException::class.java) {
                db.openHelper.writableDatabase
            }
        } finally {
            db.close()
            context.deleteDatabase(dbName)
        }
    }
}
