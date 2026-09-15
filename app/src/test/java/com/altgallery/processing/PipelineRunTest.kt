package com.altgallery.processing

import com.altgallery.data.model.ImageMetadata
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Pins the `IndexingPipeline` orchestration wiring through [executeRun] with
 * fake collaborators (no Android runtime): the readiness gate runs before
 * any attempt, the batch result is returned as-is, that run's failures reach
 * persistence exactly once, and progress is forwarded per settled photo.
 * The `runOnce` mapper itself (MediaImage↔[BatchPhoto]) is the only
 * unpinned part — plain-JVM tests cannot construct `android.net.Uri` — and
 * is a 3-line total function by construction.
 */
class PipelineRunTest {

    private fun photo(
        uri: String = "content://media/1",
        displayName: String = "IMG_0001.jpg",
    ) = BatchPhoto(uri, displayName)

    private fun recordFor(item: BatchPhoto): ImageMetadata =
        RecordAssembler.assemble(
            contentUri = item.contentUri,
            displayName = item.displayName,
            dateTaken = 1L,
            width = 10,
            height = 10,
            mimeType = "image/jpeg",
            isMeme = false,
            description = "an image",
            ocrText = null,
            labelTexts = emptyList(),
            tags = listOf("image"),
            processedAt = 2L,
        ).metadata.copy(embeddingId = 1L)

    @Test
    fun `executeRun returns the summary and persists its failures once`() = runBlocking {
        val recorded = mutableListOf<List<PhotoFailure>>()
        val settled = mutableListOf<Pair<Int, Int>>()

        val summary = executeRun(
            items = listOf(photo("content://media/1"), photo("content://media/2")),
            checkModelsReady = {},
            processPhoto = { item ->
                if (item.contentUri == "content://media/2") throw IllegalStateException("bad file")
                recordFor(item)
            },
            recordFailures = { recorded += it },
            onPhotoSettled = { done, total -> settled += done to total },
        )

        assertEquals(2, summary.attempted)
        assertEquals(1, summary.succeededCount)
        assertEquals(1, summary.failedCount)
        assertEquals(1, recorded.size)
        assertEquals(summary.failures, recorded.single())
        assertEquals(listOf(1 to 2, 2 to 2), settled)
    }

    @Test
    fun `executeRun readiness failure attempts and records nothing`() = runBlocking {
        var processCalls = 0
        val recorded = mutableListOf<List<PhotoFailure>>()
        try {
            executeRun(
                items = listOf(photo()),
                checkModelsReady = { throw modelsMissingError() },
                processPhoto = {
                    processCalls++
                    recordFor(it)
                },
                recordFailures = { recorded += it },
            )
            fail("expected ModelsMissingException")
        } catch (expected: ModelsMissingException) {
            // Expected: gate first, nothing attempted, nothing persisted.
        }
        assertEquals(0, processCalls)
        assertTrue(recorded.isEmpty())
    }

    @Test
    fun `executeRun empty run skips readiness and persistence`() = runBlocking {
        var readinessChecks = 0
        val recorded = mutableListOf<List<PhotoFailure>>()

        val summary = executeRun(
            items = emptyList(),
            checkModelsReady = { readinessChecks++ },
            processPhoto = { recordFor(it) },
            recordFailures = { recorded += it },
        )

        assertEquals(0, summary.attempted)
        assertEquals(0, readinessChecks)
        assertTrue(recorded.isEmpty())
    }

    @Test
    fun `models-missing error names the remedy`() {
        val message = modelsMissingError().message!!
        assertTrue(message.contains("Embedding"))
        assertTrue(message.contains("ModelAssets"))
        assertTrue(message.contains("before indexing"))
    }
}
