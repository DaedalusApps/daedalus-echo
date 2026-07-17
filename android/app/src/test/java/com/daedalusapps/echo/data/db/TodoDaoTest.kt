package com.daedalusapps.echo.data.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.daedalusapps.echo.data.model.TodoItem
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TodoDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: TodoDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
        dao = db.todoDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun insertReturnsGeneratedId() = runBlocking {
        val id = dao.insert(TodoItem(text = "first"))
        assertTrue(id > 0)
    }

    @Test
    fun getAllFlowOrdersByIsDoneThenCreatedAtDesc() = runBlocking {
        dao.insert(TodoItem(text = "old-open", isDone = false, createdAt = 100))
        dao.insert(TodoItem(text = "new-open", isDone = false, createdAt = 300))
        dao.insert(TodoItem(text = "done", isDone = true, createdAt = 200))

        val items = dao.getAllFlow().first()

        // isDone ASC (false before true), then createdAt DESC.
        assertEquals(listOf("new-open", "old-open", "done"), items.map { it.text })
    }

    @Test
    fun updateChangesRow() = runBlocking {
        val id = dao.insert(TodoItem(text = "before"))
        val loaded = dao.getAllFlow().first().first { it.id == id }
        dao.update(loaded.copy(text = "after"))

        assertEquals("after", dao.getAllFlow().first().first { it.id == id }.text)
    }

    @Test
    fun deleteRemovesRow() = runBlocking {
        val id = dao.insert(TodoItem(text = "doomed"))
        val loaded = dao.getAllFlow().first().first { it.id == id }
        dao.delete(loaded)

        assertTrue(dao.getAllFlow().first().none { it.id == id })
    }

    @Test
    fun setDoneTogglesIsDone() = runBlocking {
        val id = dao.insert(TodoItem(text = "task", isDone = false))

        dao.setDone(id, true)
        assertTrue(dao.getAllFlow().first().first { it.id == id }.isDone)

        dao.setDone(id, false)
        assertFalse(dao.getAllFlow().first().first { it.id == id }.isDone)
    }
}
