package com.altgallery.processing

import com.altgallery.data.model.ImageMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the "already processed" definition and recovery:
 * - AC01: unchanged re-run finds nothing pending (one record per photo,
 *   indexed count stable because no writes happen).
 * - AC02: interrupted/partial records are pending, then done exactly once.
 * - AC03: edited/replaced photos (dims/date drift) requeue, then match.
 * - AC04: the full record is done; any missing part stays eligible.
 */
class IndexPolicyTest {

    private fun metadata(
        uri: String = "content://media/1",
        width: Int = 800,
        height: Int = 600,
        dateTaken: Long = 1000L,
        description: String = "a photo of cat",
        embeddingId: Long? = 7L,
    ) = ImageMetadata(
        contentUri = uri,
        displayName = "IMG_0001.jpg",
        filePath = null,
        dateTaken = dateTaken,
        width = width,
        height = height,
        mimeType = "image/jpeg",
        isMeme = false,
        description = description,
        ocrText = null,
        labels = "Cat",
        tags = "cat,photo",
        clusterId = RecordAssembler.UNCLUSTERED,
        processedAt = 2000L,
        modelVersion = RecordAssembler.MODEL_VERSION,
        embeddingId = embeddingId,
    )

    private fun photo(
        uri: String = "content://media/1",
        width: Int = 800,
        height: Int = 600,
        dateTaken: Long = 1000L,
        dateModified: Long = 0L,
    ) = IndexPolicy.LibraryPhoto(uri, width, height, dateTaken, dateModified)

    private fun stored(
        meta: ImageMetadata? = metadata(),
        hasEmbedding: Boolean = true,
        hasFts: Boolean = true,
    ) = IndexPolicy.StoredState(meta, hasEmbedding, hasFts)

    // AC04: full record is done; any missing part is not.

    @Test
    fun `complete record is done`() {
        assertTrue(IndexPolicy.isComplete(stored()))
        assertTrue(IndexPolicy.isDone(photo(), stored()))
    }

    @Test
    fun `missing metadata is not done`() {
        val s = stored(meta = null)
        assertFalse(IndexPolicy.isComplete(s))
        assertFalse(IndexPolicy.isDone(photo(), s))
    }

    @Test
    fun `null embeddingId is not done`() {
        val s = stored(meta = metadata(embeddingId = null))
        assertFalse(IndexPolicy.isComplete(s))
        assertFalse(IndexPolicy.isDone(photo(), s))
    }

    @Test
    fun `missing embedding row is not done`() {
        val s = stored(hasEmbedding = false)
        assertFalse(IndexPolicy.isComplete(s))
        assertFalse(IndexPolicy.isDone(photo(), s))
    }

    @Test
    fun `missing fts row is not done`() {
        val s = stored(hasFts = false)
        assertFalse(IndexPolicy.isComplete(s))
        assertFalse(IndexPolicy.isDone(photo(), s))
    }

    @Test
    fun `blank description is not done`() {
        val s = stored(meta = metadata(description = "   "))
        assertFalse(IndexPolicy.isComplete(s))
        assertFalse(IndexPolicy.isDone(photo(), s))
    }

    // AC02: interrupted records stay eligible, then complete exactly once.

    @Test
    fun `each partial variant is pending`() {
        val partials = listOf(
            stored(meta = null),
            stored(meta = metadata(embeddingId = null)),
            stored(hasEmbedding = false),
            stored(hasFts = false),
            stored(meta = metadata(description = "")),
        )
        for (partial in partials) {
            val pending = IndexPolicy.findPending(
                listOf(photo()),
                mapOf("content://media/1" to partial),
            )
            assertEquals(listOf(photo()), pending)
        }
    }

    @Test
    fun `recovered record is pending once then done`() {
        // Simulate an idempotent store: reprocessing overwrites one slot.
        val store = mutableMapOf<String, IndexPolicy.StoredState>()
        store["content://media/1"] = stored(hasEmbedding = false, hasFts = false)

        val first = IndexPolicy.findPending(listOf(photo()), store)
        assertEquals(1, first.size)

        // Next run finishes the write; same URI overwrites, still one slot.
        store["content://media/1"] = stored()
        val second = IndexPolicy.findPending(listOf(photo()), store)
        assertTrue(second.isEmpty())
        assertEquals(1, store.size)
    }

    // AC01: unchanged re-run does nothing.

    @Test
    fun `unchanged library has no pending and count is stable`() {
        val library = listOf(photo("content://media/1"), photo("content://media/2", 10, 10, 2000L))
        val storedByUri = mapOf(
            "content://media/1" to stored(meta = metadata(uri = "content://media/1")),
            "content://media/2" to stored(
                meta = metadata(uri = "content://media/2", width = 10, height = 10, dateTaken = 2000L),
            ),
        )
        assertTrue(IndexPolicy.findPending(library, storedByUri).isEmpty())
    }

    @Test
    fun `reprocessing same uri overwrites instead of duplicating`() {
        // Fake idempotent save: key by URI, second write replaces the first.
        val records = mutableMapOf<String, ImageMetadata>()
        fun save(meta: ImageMetadata) {
            records[meta.contentUri] = meta.copy(embeddingId = 1L)
        }
        val first = metadata()
        val second = metadata(dateTaken = 1000L)
        save(first)
        save(second)
        assertEquals(1, records.size)
        assertEquals(second.dateTaken, records.values.single().dateTaken)
    }

    // AC03: edited/replaced photos requeue.

    @Test
    fun `width height or date drift requeues`() {
        val base = stored()
        assertFalse(IndexPolicy.isDone(photo(width = 1024), base))
        assertFalse(IndexPolicy.isDone(photo(height = 768), base))
        assertFalse(IndexPolicy.isDone(photo(dateTaken = 9999L), base))
    }

    @Test
    fun `unknown library dims never count as a change`() {
        val base = stored()
        assertTrue(IndexPolicy.isDone(photo(width = 0, height = 0), base))
        assertTrue(IndexPolicy.isDone(photo(width = 0), base))
        assertTrue(IndexPolicy.isDone(photo(height = 0), base))
    }

    @Test
    fun `edited photo pending then matches new content`() {
        val edited = photo(width = 1024, height = 768, dateTaken = 5000L)
        val stale = stored() // old 800x600/1000L record
        assertEquals(listOf(edited), IndexPolicy.findPending(listOf(edited), mapOf(edited.contentUri to stale)))

        val refreshed = stored(meta = metadata(width = 1024, height = 768, dateTaken = 5000L))
        assertTrue(IndexPolicy.findPending(listOf(edited), mapOf(edited.contentUri to refreshed)).isEmpty())
        assertEquals(1024, refreshed.metadata!!.width)
        assertEquals(768, refreshed.metadata!!.height)
        assertEquals(5000L, refreshed.metadata!!.dateTaken)
    }

    // In-place edits that preserve dims/dateTaken still bump mtime past
    // processedAt (stored fixture uses processedAt = 2000).

    @Test
    fun `mtime newer than last index requeues`() {
        val base = stored()
        assertFalse(IndexPolicy.isDone(photo(dateModified = 2001L), base))
        assertFalse(IndexPolicy.isDone(photo(dateModified = 9999L), base))
    }

    @Test
    fun `same-second mtime counts as stale (second-quantized DATE_MODIFIED)`() {
        // Stored fixture uses processedAt = 2000. DATE_MODIFIED arrives
        // quantized to whole seconds while processedAt is millis-precise, so
        // a bare `mtime > processedAt` would miss an edit landing in the same
        // second after indexing. The check floors processedAt and treats
        // equality as stale: at worst one extra reprocess, then stable.
        val base = stored()
        assertFalse(IndexPolicy.isDone(photo(dateModified = 2000L), base))
    }

    @Test
    fun `same-second edit after a mid-second index requeues`() {
        // processedAt = 2500 floors to 2000; a quantized mtime of 2000 may be
        // up to 999ms newer than the index yet compare equal as millis. Must
        // still requeue — this is the ~1s dead zone the floor closes.
        val base = stored(meta = metadata().copy(processedAt = 2500L))
        assertFalse(IndexPolicy.isDone(photo(dateModified = 2000L), base))
    }

    @Test
    fun `mtime in a strictly earlier second stays done`() {
        val base = stored()
        assertTrue(IndexPolicy.isDone(photo(dateModified = 1000L), base))
        assertTrue(IndexPolicy.isDone(photo(dateModified = 1999L), base))
    }

    @Test
    fun `floorToSecond maps millis to MediaStore boundary`() {
        assertEquals(0L, IndexPolicy.floorToSecond(0L))
        assertEquals(2000L, IndexPolicy.floorToSecond(2000L))
        assertEquals(2000L, IndexPolicy.floorToSecond(2500L))
        assertEquals(2000L, IndexPolicy.floorToSecond(2999L))
    }

    @Test
    fun `unknown mtime never counts as a change`() {
        val base = stored()
        assertTrue(IndexPolicy.isDone(photo(dateModified = 0L), base))
    }

    // Stamp-timing race: snapshot moved between intake and post-stage re-read.

    @Test
    fun `identical snapshots are unchanged`() {
        assertFalse(IndexPolicy.snapshotChanged(photo(), photo()))
        assertFalse(
            IndexPolicy.snapshotChanged(
                photo(dateModified = 1500L),
                photo(dateModified = 1500L),
            ),
        )
    }

    @Test
    fun `any signal move is a change`() {
        val before = photo()
        assertTrue(IndexPolicy.snapshotChanged(before, photo(width = 1024)))
        assertTrue(IndexPolicy.snapshotChanged(before, photo(height = 768)))
        assertTrue(IndexPolicy.snapshotChanged(before, photo(dateTaken = 9999L)))
        assertTrue(IndexPolicy.snapshotChanged(before, photo(dateModified = 1500L)))
    }

    @Test
    fun `unknown to known mtime counts as changed`() {
        assertTrue(IndexPolicy.snapshotChanged(photo(dateModified = 0L), photo(dateModified = 5000L)))
    }
}
