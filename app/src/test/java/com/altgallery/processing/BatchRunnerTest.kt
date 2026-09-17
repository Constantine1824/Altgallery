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
 * - Persistence wiring: [BatchRunner.runBatch] takes `recordFailures` as a
 *   required argument and calls it once per non-empty run with that run's
 *   failures (empty clears the log); an empty run records nothing.
 * - Spacing: attempts pause between tries ([BatchRunner.defaultRetryDelayMs]
 *   via an injectable nap) instead of burning the budget back-to-back; no
 *   pause after settling.
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
        recordFailures: suspend (List<PhotoFailure>) -> Unit = {},
        retryDelayMs: (Int) -> Long = { 0L },
        nap: suspend (Long) -> Unit = {},
        onPhotoSettled: suspend (done: Int, total: Int) -> Unit = { _, _ -> },
    ): RunSummary = BatchRunner.runBatch(
        items, checkModelsReady, processPhoto, recordFailures, retryDelayMs, nap, onPhotoSettled,
    )

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

    // Persistence wiring: the required recordFailures callback.

    @Test
    fun `failures are handed to recordFailures once per run`() = runBlocking {
        val recorded = mutableListOf<List<PhotoFailure>>()
        val summary = run(
            items = listOf(photo("content://media/1"), photo("content://media/2")),
            processPhoto = { item ->
                if (item.contentUri == "content://media/2") throw IllegalStateException("bad file")
                recordFor(item)
            },
            recordFailures = { recorded += it },
        )

        assertEquals(1, recorded.size)
        assertEquals(summary.failures, recorded.single())
    }

    @Test
    fun `clean run records an empty list to clear the log`() = runBlocking {
        val recorded = mutableListOf<List<PhotoFailure>>()
        val summary = run(
            items = listOf(photo()),
            processPhoto = { recordFor(it) },
            recordFailures = { recorded += it },
        )

        assertTrue(summary.failures.isEmpty())
        assertEquals(1, recorded.size)
        assertTrue(recorded.single().isEmpty())
    }

    @Test
    fun `empty run records nothing and preserves the previous log`() = runBlocking {
        val recorded = mutableListOf<List<PhotoFailure>>()
        run(
            items = emptyList(),
            processPhoto = { recordFor(it) },
            recordFailures = { recorded += it },
        )

        assertTrue(recorded.isEmpty())
    }

    @Test
    fun `readiness throw records nothing`() = runBlocking {
        val recorded = mutableListOf<List<PhotoFailure>>()
        var processCalls = 0
        try {
            run(
                items = listOf(photo()),
                checkModelsReady = { throw ModelsMissingException("missing") },
                processPhoto = {
                    processCalls++
                    recordFor(it)
                },
                recordFailures = { recorded += it },
            )
            fail("expected ModelsMissingException")
        } catch (expected: ModelsMissingException) {
            // Expected: aborted before any attempt, nothing to persist.
        }
        assertEquals(0, processCalls)
        assertTrue(recorded.isEmpty())
    }

    // Spacing: pauses between attempts, never after settling.

    @Test
    fun `default backoff doubles from 250ms`() {
        assertEquals(250L, BatchRunner.defaultRetryDelayMs(1))
        assertEquals(500L, BatchRunner.defaultRetryDelayMs(2))
    }

    @Test
    fun `persistent failure pauses between attempts with the default schedule`() = runBlocking {
        val naps = mutableListOf<Long>()
        val summary = BatchRunner.runBatch(
            items = listOf(photo()),
            checkModelsReady = {},
            processPhoto = { throw IllegalStateException("boom") },
            recordFailures = {},
            nap = { naps += it },
        )

        assertEquals(listOf(250L, 500L), naps)
        assertEquals(1, summary.failedCount)
    }

    @Test
    fun `no pause on first-try success`() = runBlocking {
        val naps = mutableListOf<Long>()
        BatchRunner.runBatch(
            items = listOf(photo()),
            checkModelsReady = {},
            processPhoto = { recordFor(it) },
            recordFailures = {},
            nap = { naps += it },
        )

        assertTrue(naps.isEmpty())
    }

    @Test
    fun `single pause when the second attempt succeeds`() = runBlocking {
        val naps = mutableListOf<Long>()
        var calls = 0
        val item = photo()
        val summary = BatchRunner.runBatch(
            items = listOf(item),
            checkModelsReady = {},
            processPhoto = {
                calls++
                if (calls == 1) throw IllegalStateException("transient")
                recordFor(item)
            },
            recordFailures = {},
            nap = { naps += it },
        )

        assertEquals(listOf(250L), naps)
        assertEquals(1, summary.succeededCount)
    }

    @Test
    fun `custom schedule is honored`() = runBlocking {
        val naps = mutableListOf<Long>()
        BatchRunner.runBatch(
            items = listOf(photo()),
            checkModelsReady = {},
            processPhoto = { throw IllegalStateException("boom") },
            recordFailures = {},
            retryDelayMs = { 42L },
            nap = { naps += it },
        )

        assertEquals(listOf(42L, 42L), naps)
    }

    // Time ceiling: the stop flag is consulted inside the retry loop, not
    // just between photos, and a cut-short photo stays unsettled for the
    // continuation instead of being recorded or retried past the budget.

    @Test
    fun `stop before first photo settles nothing yet still records empty`() = runBlocking {
        val recorded = mutableListOf<List<PhotoFailure>>()
        val settled = mutableListOf<Pair<Int, Int>>()
        var processCalls = 0

        val summary = BatchRunner.runBatch(
            items = listOf(photo(), photo("content://media/2", "IMG_0002.jpg")),
            checkModelsReady = {},
            processPhoto = {
                processCalls++
                recordFor(it)
            },
            recordFailures = { recorded += it },
            nap = {},
            onPhotoSettled = { done, total -> settled += done to total },
            shouldStop = { true },
        )

        assertEquals(0, summary.attempted)
        assertEquals(0, summary.succeededCount)
        assertEquals(0, summary.failedCount)
        assertEquals(0, processCalls)
        // A fresh pass's replace-with-empty: drops the previous pass's log
        // so the continuation cannot mistake it for this pass's attempt log
        // (a continuation's append-with-empty is a no-op instead).
        assertEquals(1, recorded.size)
        assertTrue(recorded.single().isEmpty())
        assertTrue(settled.isEmpty())
    }

    @Test
    fun `stop inside retry loop abandons remaining attempts without recording`() = runBlocking {
        val recorded = mutableListOf<List<PhotoFailure>>()
        val settled = mutableListOf<Pair<Int, Int>>()
        val naps = mutableListOf<Long>()
        var processCalls = 0

        val summary = BatchRunner.runBatch(
            items = listOf(photo()),
            checkModelsReady = {},
            processPhoto = {
                processCalls++
                throw IllegalStateException("slow failure")
            },
            recordFailures = { recorded += it },
            nap = { naps += it },
            onPhotoSettled = { done, total -> settled += done to total },
            shouldStop = { processCalls >= 1 },
        )

        // One attempt, not three: no burning the remaining budget, no pause.
        assertEquals(1, processCalls)
        assertTrue(naps.isEmpty())
        // Cut short, not exhausted: unsettled (pending, unlogged) for the
        // continuation, never recorded as a failure.
        assertEquals(0, summary.attempted)
        assertEquals(0, summary.failedCount)
        assertEquals(1, recorded.size)
        assertTrue(recorded.single().isEmpty())
        assertTrue(settled.isEmpty())
    }

    @Test
    fun `stop after first photo keeps its settlement and skips the rest`() = runBlocking {
        val recorded = mutableListOf<List<PhotoFailure>>()
        val settled = mutableListOf<Pair<Int, Int>>()
        var processCalls = 0

        val summary = BatchRunner.runBatch(
            items = listOf(photo("content://media/1"), photo("content://media/2")),
            checkModelsReady = {},
            processPhoto = {
                processCalls++
                recordFor(it)
            },
            recordFailures = { recorded += it },
            nap = {},
            onPhotoSettled = { done, total -> settled += done to total },
            shouldStop = { processCalls >= 1 },
        )

        assertEquals(1, processCalls)
        assertEquals(1, summary.attempted)
        assertEquals(1, summary.succeededCount)
        assertEquals(0, summary.failedCount)
        assertEquals(1, recorded.size)
        assertTrue(recorded.single().isEmpty())
        assertEquals(listOf(1 to 2), settled)
    }
}
