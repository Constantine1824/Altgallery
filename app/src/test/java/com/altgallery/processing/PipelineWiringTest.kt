package com.altgallery.processing

import com.altgallery.data.model.ImageMetadata
import com.altgallery.data.model.ImageMetadataFts
import com.altgallery.ml.ModelUnavailableException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the INDX-001 wiring at the pure tail.
 *
 * Drives [RecordAssembler.processPhoto] — the same tail
 * `ImageProcessor.process` delegates to after mapping Android types — with
 * fake stages, so regressions in the orchestration itself fail here:
 * - AC1: raw 0/0 library dims + real bitmap dims persist the effective values
 *   and the classifier observes them (the exact shape of the bug just fixed).
 * - AC2: the saved FTS row is the assembled row (single derivation point),
 *   mirroring the saved metadata.
 * - AC5: an embed throw skips the save (photo stays not done).
 * - AC3: no shared state between photos.
 */
class PipelineWiringTest {

    private data class Saved(
        val metadata: ImageMetadata,
        val fts: ImageMetadataFts,
        val vector: FloatArray,
    )

    @Test
    fun `effective dims persist and reach classifier`() = runBlocking {
        val seenByClassifier = mutableListOf<Pair<Int, Int>>()
        val saved = mutableListOf<Saved>()
        val vector = floatArrayOf(0.5f, -0.25f, 1f)

        RecordAssembler.processPhoto(
            contentUri = "content://media/1",
            displayName = "IMG_0001.jpg",
            dateTaken = 1L,
            mimeType = "image/jpeg",
            imageWidth = 0,
            imageHeight = 0,
            bitmapWidth = 800,
            bitmapHeight = 600,
            ocrRaw = "hello",
            labelTexts = listOf("Cat"),
            processedAt = 2L,
            classify = { _, _, width, height ->
                seenByClassifier += width to height
                false
            },
            describe = { "a photo of cat" },
            tag = { _, _ -> listOf("cat", "photo") },
            embed = { vector },
            save = { metadata, fts, v ->
                saved += Saved(metadata, fts, v)
                metadata.copy(embeddingId = 7L)
            },
        )

        assertEquals(listOf(800 to 600), seenByClassifier)
        assertEquals(800, saved.single().metadata.width)
        assertEquals(600, saved.single().metadata.height)
        assertArrayEquals(vector, saved.single().vector, 0f)
    }

    @Test
    fun `saved fts is the assembled row mirroring metadata`() = runBlocking {
        val saved = mutableListOf<Saved>()

        RecordAssembler.processPhoto(
            contentUri = "content://media/2",
            displayName = "IMG_0002.jpg",
            dateTaken = 1L,
            mimeType = "image/jpeg",
            imageWidth = 800,
            imageHeight = 600,
            bitmapWidth = null,
            bitmapHeight = null,
            ocrRaw = "hello",
            labelTexts = listOf("Cat"),
            processedAt = 2L,
            classify = { _, _, _, _ -> false },
            describe = { "a photo of cat" },
            tag = { _, _ -> listOf("cat", "photo") },
            embed = { floatArrayOf(1f) },
            save = { metadata, fts, v ->
                saved += Saved(metadata, fts, v)
                metadata.copy(embeddingId = 3L)
            },
        )

        val (metadata, fts) = saved.single().let { it.metadata to it.fts }
        assertEquals(metadata.contentUri, fts.contentUri)
        assertEquals(metadata.displayName, fts.displayName)
        assertEquals(metadata.description, fts.description)
        assertEquals(metadata.ocrText, fts.ocrText)
        assertEquals(metadata.labels, fts.labels)
        assertEquals(metadata.tags, fts.tags)
        assertEquals(RecordAssembler.ftsFor(metadata), fts)
    }

    @Test
    fun `embed throw skips save in the tail`() = runBlocking {
        var saved = false
        try {
            RecordAssembler.processPhoto(
                contentUri = "content://media/3",
                displayName = "IMG_0003.jpg",
                dateTaken = 1L,
                mimeType = "image/jpeg",
                imageWidth = 10,
                imageHeight = 10,
                bitmapWidth = null,
                bitmapHeight = null,
                ocrRaw = "",
                labelTexts = emptyList(),
                processedAt = 2L,
                classify = { _, _, _, _ -> false },
                describe = { "an image" },
                tag = { _, _ -> listOf("image") },
                embed = { throw ModelUnavailableException("model absent") },
                save = { metadata, _, _ ->
                    saved = true
                    metadata
                },
            )
            throw AssertionError("expected ModelUnavailableException")
        } catch (expected: ModelUnavailableException) {
            // Expected: absent model leaves the photo not done.
        }
        assertFalse(saved)
    }

    @Test
    fun `two tail runs share no state`() = runBlocking {
        suspend fun runOne(
            uri: String,
            ocrRaw: String,
            labels: List<String>,
            description: String,
            tags: List<String>,
        ): Saved {
            val saved = mutableListOf<Saved>()
            RecordAssembler.processPhoto(
                contentUri = uri,
                displayName = "$uri.jpg",
                dateTaken = 1L,
                mimeType = "image/jpeg",
                imageWidth = 10,
                imageHeight = 10,
                bitmapWidth = null,
                bitmapHeight = null,
                ocrRaw = ocrRaw,
                labelTexts = labels,
                processedAt = 2L,
                classify = { _, _, _, _ -> false },
                describe = { description },
                tag = { _, _ -> tags },
                embed = { floatArrayOf(1f) },
                save = { metadata, fts, v ->
                    saved += Saved(metadata, fts, v)
                    metadata.copy(embeddingId = 1L)
                },
            )
            return saved.single()
        }

        runOne(
            uri = "content://media/1",
            ocrRaw = "hello world",
            labels = listOf("Cat"),
            description = "a photo of cat [TEXT IN IMAGE: hello world]",
            tags = listOf("cat", "hello", "photo"),
        )
        val second = runOne(
            uri = "content://media/2",
            ocrRaw = "   ",
            labels = emptyList(),
            description = "an image",
            tags = listOf("image"),
        )

        assertEquals("an image", second.metadata.description)
        assertEquals("image", second.metadata.tags)
        for (leak in listOf("hello", "world", "cat")) {
            assertFalse(second.metadata.description.contains(leak, ignoreCase = true))
            assertFalse(second.metadata.labels.contains(leak, ignoreCase = true))
            assertFalse(second.metadata.tags.contains(leak, ignoreCase = true))
        }
        assertTrue(second.fts.description.isNotBlank())
    }
}
