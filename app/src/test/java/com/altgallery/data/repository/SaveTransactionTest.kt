package com.altgallery.data.repository

import com.altgallery.data.db.EmbeddingDao
import com.altgallery.data.db.ImageMetadataDao
import com.altgallery.data.db.toByteArray
import com.altgallery.data.model.ImageEmbedding
import com.altgallery.data.model.ImageMetadata
import com.altgallery.data.model.ImageMetadataFts
import com.altgallery.processing.RecordAssembler
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Pins the save path the crash-recovery guarantee rests on.
 *
 * Drives [saveRecordSteps] — the exact sequence [MetadataRepository] runs
 * inside its Room transaction — with in-memory fake DAOs:
 * - writes run in delete-stale-first order,
 * - a throw aborts before any later write (nothing half-landed),
 * - re-saving the same URI leaves exactly one metadata, one embedding,
 *   and one FTS row (idempotent recovery).
 *
 * The SQLite atomicity around these steps is Room's `withTransaction`
 * guarantee, not something a plain-JVM test can exercise; what this pins is
 * that everything the guarantee must cover is inside the single sequence.
 */
class SaveTransactionTest {

    private class FakeEmbeddingDao : EmbeddingDao {
        val calls = mutableListOf<String>()
        val store = mutableMapOf<String, ImageEmbedding>()
        var nextId = 0L
        var failOnInsert: Throwable? = null

        override suspend fun insert(embedding: ImageEmbedding): Long {
            calls += "insert:${embedding.contentUri}"
            failOnInsert?.let { throw it }
            store.remove(embedding.contentUri)
            val id = ++nextId
            store[embedding.contentUri] = embedding.copy(id = id)
            return id
        }

        override suspend fun getAll(): List<ImageEmbedding> = store.values.toList()
        override suspend fun getByUri(uri: String): ImageEmbedding? = store[uri]
        override suspend fun getAllUris(): List<String> = store.keys.toList()
        override suspend fun count(): Int = store.size
        override suspend fun deleteByUri(uri: String) {
            calls += "delete:$uri"
            store.remove(uri)
        }

        override suspend fun clear() {
            store.clear()
        }
    }

    private class FakeMetadataDao : ImageMetadataDao {
        val calls = mutableListOf<String>()
        val meta = mutableMapOf<String, ImageMetadata>()
        val ftsRows = mutableListOf<ImageMetadataFts>()

        override suspend fun upsert(metadata: ImageMetadata) {
            calls += "upsert:${metadata.contentUri}"
            meta[metadata.contentUri] = metadata
        }

        override suspend fun upsertAll(metadata: List<ImageMetadata>) {
            metadata.forEach { upsert(it) }
        }

        // Suppressions: the test double must implement the raw methods the
        // DAO's own replaceFts is built on; production Kotlin callers are
        // compile-barred from touching them directly.
        @Suppress("DEPRECATION_ERROR")
        override suspend fun upsertFts(fts: ImageMetadataFts) {
            calls += "upsertFts:${fts.contentUri}"
            ftsRows += fts
        }

        @Suppress("DEPRECATION_ERROR")
        override suspend fun deleteFtsByUri(uri: String) {
            calls += "deleteFts:$uri"
            ftsRows.removeAll { it.contentUri == uri }
        }

        override suspend fun getAll(): List<ImageMetadata> = meta.values.toList()
        override fun observeAll(): Flow<List<ImageMetadata>> = emptyFlow()
        override suspend fun getByUri(uri: String): ImageMetadata? = meta[uri]
        override fun observeByCluster(clusterId: String): Flow<List<ImageMetadata>> = emptyFlow()
        override suspend fun getAllProcessedUris(): List<String> = meta.keys.toList()
        override suspend fun getFtsUris(): List<String> = ftsRows.map { it.contentUri }
        override suspend fun getFtsByUri(uri: String): ImageMetadataFts? =
            ftsRows.firstOrNull { it.contentUri == uri }

        override suspend fun getCompleteUris(): List<String> =
            meta.values.filter { it.embeddingId != null }.map { it.contentUri }

        override fun observeCount(): Flow<Int> = emptyFlow()
        override suspend fun getIndexedCount(): Int = getCompleteUris().size
        override fun observeMemeCount(): Flow<Int> = emptyFlow()
        override suspend fun searchLike(term: String): List<ImageMetadata> = emptyList()
        override suspend fun searchFts(query: String): List<ImageMetadata> = emptyList()
        override suspend fun clear() {
            meta.clear()
        }

        override suspend fun clearFts() {
            ftsRows.clear()
        }
    }

    private fun metadata(uri: String = "content://media/1") = ImageMetadata(
        contentUri = uri,
        displayName = "IMG_0001.jpg",
        filePath = null,
        dateTaken = 1000L,
        width = 800,
        height = 600,
        mimeType = "image/jpeg",
        isMeme = false,
        description = "a photo of cat",
        ocrText = null,
        labels = "Cat",
        tags = "cat,photo",
        clusterId = RecordAssembler.UNCLUSTERED,
        processedAt = 2000L,
        modelVersion = RecordAssembler.MODEL_VERSION,
        embeddingId = null,
    )

    private fun ftsFor(meta: ImageMetadata) = ImageMetadataFts(
        contentUri = meta.contentUri,
        displayName = meta.displayName,
        description = meta.description,
        ocrText = meta.ocrText,
        labels = meta.labels,
        tags = meta.tags,
    )

    @Test
    fun `writes run delete-stale-first in order`() = runBlocking {
        val metas = FakeMetadataDao()
        val embs = FakeEmbeddingDao()
        val meta = metadata()

        saveRecordSteps(metas, embs, meta, ftsFor(meta), floatArrayOf(0.5f, 1f))

        assertEquals(
            listOf("delete:${meta.contentUri}", "insert:${meta.contentUri}"),
            embs.calls,
        )
        assertEquals(
            listOf(
                "upsert:${meta.contentUri}",
                "deleteFts:${meta.contentUri}",
                "upsertFts:${meta.contentUri}",
            ),
            metas.calls,
        )
    }

    @Test
    fun `insert throw skips every later write`() = runBlocking {
        val metas = FakeMetadataDao()
        val embs = FakeEmbeddingDao().apply {
            failOnInsert = IllegalStateException("disk full mid-run")
        }
        val meta = metadata()

        try {
            saveRecordSteps(metas, embs, meta, ftsFor(meta), floatArrayOf(1f))
            fail("expected insert to throw")
        } catch (expected: IllegalStateException) {
            // Expected: the run dies here with nothing half-landed.
        }

        assertTrue(metas.calls.none { it.startsWith("upsert") })
        assertTrue(metas.meta.isEmpty())
        assertTrue(metas.ftsRows.isEmpty())
        assertTrue(embs.store.isEmpty())
    }

    @Test
    fun `re-saving same uri leaves exactly one of each row`() = runBlocking {
        val metas = FakeMetadataDao()
        val embs = FakeEmbeddingDao()
        val meta = metadata()

        val firstId = saveRecordSteps(metas, embs, meta, ftsFor(meta), floatArrayOf(0.5f))
        val secondId = saveRecordSteps(metas, embs, meta, ftsFor(meta), floatArrayOf(0.75f))

        assertEquals(1, metas.meta.size)
        assertEquals(1, embs.store.size)
        assertEquals(1, metas.ftsRows.count { it.contentUri == meta.contentUri })
        assertEquals(secondId, metas.meta.getValue(meta.contentUri).embeddingId)
        assertArrayEquals(
            floatArrayOf(0.75f).toByteArray(),
            embs.store.getValue(meta.contentUri).vector,
        )
        assertTrue(firstId != secondId)
    }

    @Test
    fun `replaceFts twice still leaves one row`() = runBlocking {
        // Direct DAO-level pin for the no-unique-constraint trap: the blessed
        // path deduplicates even when invoked repeatedly on its own.
        val metas = FakeMetadataDao()
        val fts = ftsFor(metadata())

        metas.replaceFts(fts)
        metas.replaceFts(fts)

        assertEquals(1, metas.ftsRows.count { it.contentUri == fts.contentUri })
    }
}
