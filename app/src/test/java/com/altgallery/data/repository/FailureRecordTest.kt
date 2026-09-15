package com.altgallery.data.repository

import com.altgallery.data.db.ProcessingFailureDao
import com.altgallery.data.model.ProcessingFailure
import com.altgallery.processing.PhotoFailure
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the durable failure log behind `MetadataRepository.recordRunFailures`
 * (see `replaceFailureSteps`): each run replaces the whole set — clear first,
 * then insert — so a recovered photo drops out, a re-attempted photo
 * overwrites its row instead of duplicating, and a clean run empties the log.
 * Drives the steps function with a fake DAO; the SQLite atomicity around
 * them is Room's `withTransaction` guarantee.
 */
class FailureRecordTest {

    private class FakeFailureDao : ProcessingFailureDao {
        val calls = mutableListOf<String>()
        val store = mutableListOf<ProcessingFailure>()

        override suspend fun upsertAll(failures: List<ProcessingFailure>) {
            calls += "upsertAll:${failures.size}"
            store += failures
        }

        override suspend fun getAll(): List<ProcessingFailure> = store.toList()
        override fun observeAll(): Flow<List<ProcessingFailure>> = emptyFlow()

        override suspend fun clear() {
            calls += "clear"
            store.clear()
        }
    }

    private fun failure(
        uri: String = "content://media/1",
        displayName: String = "IMG_0001.jpg",
        reason: String = "cannot decode file",
        attempts: Int = 3,
    ) = PhotoFailure(uri, displayName, reason, attempts)

    @Test
    fun `replace runs clear-then-insert with run-stamped rows`() = runBlocking {
        val dao = FakeFailureDao()

        replaceFailureSteps(dao, listOf(failure()), failedAt = 777L)

        assertEquals(listOf("clear", "upsertAll:1"), dao.calls)
        assertEquals(
            listOf(
                ProcessingFailure(
                    contentUri = "content://media/1",
                    displayName = "IMG_0001.jpg",
                    reason = "cannot decode file",
                    attempts = 3,
                    failedAt = 777L,
                ),
            ),
            dao.store,
        )
    }

    @Test
    fun `empty failures clear without insert`() = runBlocking {
        val dao = FakeFailureDao().apply {
            store += ProcessingFailure("content://media/old", "OLD.jpg", "stale", 3, 1L)
        }

        replaceFailureSteps(dao, emptyList(), failedAt = 999L)

        assertEquals(listOf("clear"), dao.calls)
        assertTrue(dao.store.isEmpty())
    }

    @Test
    fun `two runs leave exactly one row per uri`() = runBlocking {
        val dao = FakeFailureDao()

        replaceFailureSteps(dao, listOf(failure(reason = "first")), failedAt = 1L)
        replaceFailureSteps(dao, listOf(failure(reason = "second")), failedAt = 2L)

        assertEquals(1, dao.store.size)
        assertEquals("second", dao.store.single().reason)
        assertEquals(2L, dao.store.single().failedAt)
    }
}
