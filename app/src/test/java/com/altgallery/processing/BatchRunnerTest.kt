package com.altgallery.processing

import com.altgallery.data.model.ImageMetadata
import com.altgallery.ml.ModelUnavailableException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Pins the batch failure policy (INDX-003):
 * - AC01: one unreadable photo never stops the batch; every other photo is
 *   still processed and only the bad photo is recorded.
 * - AC02: each photo is attempted at most [BatchRunner.MAX_ATTEMPTS] times;
 *   a flaky photo that recovers inside the budget still counts as success.
 * - AC03: an exhausted photo is recorded with its filename and the reason,
 *   and the record is in the returned summary.
 * - AC04: the summary adds up (`attempted == succeeded + failed`); the
 *   [RunSummary] constructor itself rejects an unbalanced triple.
 * - AC05: a missing model is reported once via [ModelsMissingException] —
 *   checked once before any attempt, and a mid-run model loss aborts the
 *   batch instead of repeating one failure per photo.
 */
class BatchRunnerTest {

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

    private suspend fun run(
        items: List<BatchPhoto>,
        processPhoto: suspend (BatchPhoto) -> ImageMetadata,
        checkModelsReady: suspend () -> Unit = {},
        onPhotoSettled: suspend (done: Int, total: Int) -> Unit = { _, _ -> },
    ): RunSummary = BatchRunner.runBatch(items, checkModelsReady, processPhoto, onPhotoSettled)

    // AC01: one bad photo is the only casualty.

    @Test
    fun `unreadable photo does not stop the batch`() = runBlocking {
        val items = listOf(
            photo("content://media/1", "IMG_0001.jpg"),
            photo("content://media/2", "IMG_0002.jpg"),
            photo("content://media/3", "IMG_0003.jpg"),
        )

        val summary = run(items, processPhoto = { item ->
            if (item.contentUri == "content://media/2") {
                throw IllegalStateException("cannot decode file")
            }
            recordFor(item)
        })

        assertEquals(
            listOf("content://media/1", "content://media/3"),
            summary.succeeded.map { it.contentUri },
        )
        assertEquals(1, summary.failures.size)
        assertEquals(3, summary.attempted)
    }

    // AC02: bounded attempts.

    @Test
    fun `persistent failure is attempted exactly three times`() = runBlocking {
        val calls = mutableListOf<String>()
        val summary = run(listOf(photo()), processPhoto = { item ->
            calls += item.contentUri
            throw IllegalStateException("boom")
        })

        assertEquals(BatchRunner.MAX_ATTEMPTS, calls.size)
        assertEquals(0, summary.succeededCount)
        assertEquals(1, summary.failedCount)
        assertEquals(BatchRunner.MAX_ATTEMPTS, summary.failures.single().attempts)
    }

    @Test
    fun `flaky photo recovering inside the budget counts as success`() = runBlocking {
        var calls = 0
        val item = photo()
        val summary = run(listOf(item), processPhoto = {
            calls++
            if (calls < 3) throw IllegalStateException("transient")
            recordFor(item)
        })

        assertEquals(3, calls)
        assertEquals(1, summary.succeededCount)
        assertEquals(0, summary.failedCount)
    }

    @Test
    fun `healthy photo is attempted once`() = runBlocking {
        var calls = 0
        val item = photo()
        run(listOf(item), processPhoto = {
            calls++
            recordFor(item)
        })

        assertEquals(1, calls)
    }

    // AC03: recorded with filename and reason, in the summary.

    @Test
    fun `exhausted photo is recorded with filename and reason`() = runBlocking {
        val item = photo("content://media/9", "IMG_0009.jpg")
        val summary = run(listOf(item), processPhoto = {
            throw IllegalStateException("cannot decode file")
        })

        val failure = summary.failures.single()
        assertEquals("content://media/9", failure.contentUri)
        assertEquals("IMG_0009.jpg", failure.displayName)
        assertTrue(failure.reason.contains("cannot decode file"))
        assertEquals(BatchRunner.MAX_ATTEMPTS, failure.attempts)
    }

    @Test
    fun `blank exception message falls back to the exception type`() = runBlocking {
        val summary = run(listOf(photo()), processPhoto = {
            throw IllegalStateException()
        })

        assertEquals("IllegalStateException", summary.failures.single().reason)
    }

    // AC04: numbers add up.

    @Test
    fun `summary attempted equals succeeded plus failed`() = runBlocking {
        val items = (1..5).map { photo("content://media/$it", "IMG_000$it.jpg") }
        val summary = run(items, processPhoto = { item ->
            if (item.contentUri.endsWith("2") || item.contentUri.endsWith("4")) {
                throw IllegalStateException("bad file")
            }
            recordFor(item)
        })

        assertEquals(5, summary.attempted)
        assertEquals(3, summary.succeededCount)
        assertEquals(2, summary.failedCount)
        assertEquals(
            summary.attempted,
            summary.succeeded.size + summary.failures.size,
        )
    }

    @Test
    fun `unbalanced summary cannot be constructed`() {
        try {
            RunSummary(attempted = 2, succeeded = emptyList(), failures = emptyList())
            fail("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            // Expected: the invariant holds by construction.
        }
    }

    // AC05: missing model reported once.

    @Test
    fun `missing models abort before any attempt with a single error`() = runBlocking {
        var readinessChecks = 0
        var processCalls = 0
        try {
            run(
                items = listOf(photo(), photo()),
                checkModelsReady = {
                    readinessChecks++
                    throw ModelsMissingException("Embedding model files are missing")
                },
                processPhoto = {
                    processCalls++
                    recordFor(it)
                },
            )
            fail("expected ModelsMissingException")
        } catch (expected: ModelsMissingException) {
            assertTrue(expected.message!!.contains("missing"))
        }
        assertEquals(1, readinessChecks)
        assertEquals(0, processCalls)
    }

    @Test
    fun `mid-run model loss aborts instead of repeating per-photo failures`() = runBlocking {
        val items = listOf(
            photo("content://media/1", "IMG_0001.jpg"),
            photo("content://media/2", "IMG_0002.jpg"),
            photo("content://media/3", "IMG_0003.jpg"),
        )
        val attempted = mutableListOf<String>()
        try {
            run(items, processPhoto = { item ->
                attempted += item.contentUri
                if (item.contentUri == "content://media/2") {
                    throw ModelUnavailableException("model absent")
                }
                recordFor(item)
            })
            fail("expected ModelsMissingException")
        } catch (expected: ModelsMissingException) {
            // Expected: one report, not one failure per remaining photo.
        }
        assertEquals(
            listOf("content://media/1", "content://media/2"),
            attempted,
        )
    }

    // Boundaries.

    @Test
    fun `empty run settles nothing and never checks models`() = runBlocking {
        var readinessChecks = 0
        var processCalls = 0
        val settled = mutableListOf<Pair<Int, Int>>()

        val summary = run(
            items = emptyList(),
            checkModelsReady = { readinessChecks++ },
            processPhoto = {
                processCalls++
                recordFor(it)
            },
            onPhotoSettled = { done, total -> settled += done to total },
        )

        assertEquals(0, summary.attempted)
        assertEquals(0, summary.succeededCount)
        assertEquals(0, summary.failedCount)
        assertEquals(0, readinessChecks)
        assertEquals(0, processCalls)
        assertTrue(settled.isEmpty())
    }

    @Test
    fun `progress fires once per settled photo in order`() = runBlocking {
        val items = listOf(photo("content://media/1"), photo("content://media/2"))
        val settled = mutableListOf<Pair<Int, Int>>()

        run(
            items = items,
            processPhoto = { item ->
                if (item.contentUri == "content://media/1") throw IllegalStateException("bad")
                recordFor(item)
            },
            onPhotoSettled = { done, total -> settled += done to total },
        )

        assertEquals(listOf(1 to 2, 2 to 2), settled)
    }

    @Test
    fun `cancellation is never swallowed or recorded`() = runBlocking {
        try {
            run(listOf(photo()), processPhoto = {
                throw CancellationException("stop")
            })
            fail("expected CancellationException")
        } catch (expected: CancellationException) {
            // Expected: cooperative cancellation propagates untouched.
        }
    }
}
