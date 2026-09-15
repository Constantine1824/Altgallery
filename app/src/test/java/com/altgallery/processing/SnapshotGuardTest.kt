package com.altgallery.processing

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

/**
 * Pins the stale-snapshot check itself.
 *
 * [guardSnapshot] is the exact check the tail runs after embed and before the
 * transaction opens ([RecordAssembler.processPhoto] requires the snapshot
 * pair, so `ImageProcessor.process` cannot bypass it — see
 * `PipelineWiringTest` for the wiring pin): a vanished row throws
 * [PhotoGoneException], a row that moved mid-run throws
 * [StaleSnapshotException] (nothing is written), an identical row passes
 * silently. The predicate itself ([IndexPolicy.snapshotChanged]) is pinned
 * separately in `IndexPolicyTest`.
 */
class SnapshotGuardTest {

    private fun photo(
        width: Int = 800,
        height: Int = 600,
        dateTaken: Long = 1000L,
        dateModified: Long = 1500L,
    ) = IndexPolicy.LibraryPhoto("content://media/1", width, height, dateTaken, dateModified)

    @Test
    fun `identical re-read passes silently`() = runBlocking {
        guardSnapshot(photo()) { photo() }
    }

    @Test
    fun `moved row throws stale and names the uri`() = runBlocking {
        try {
            guardSnapshot(photo()) { photo(width = 1024) }
            fail("expected StaleSnapshotException")
        } catch (e: StaleSnapshotException) {
            assertEquals(
                "library row changed mid-run, will retry: content://media/1",
                e.message,
            )
        }
    }

    @Test
    fun `mtime-only move inside caption window throws stale`() = runBlocking {
        try {
            guardSnapshot(photo()) { photo(dateModified = 9999L) }
            fail("expected StaleSnapshotException")
        } catch (expected: StaleSnapshotException) {
            // Expected: edit during the slow stages discards the result.
        }
    }

    @Test
    fun `vanished row throws gone`() = runBlocking {
        try {
            guardSnapshot(photo()) { null }
            fail("expected PhotoGoneException")
        } catch (e: PhotoGoneException) {
            assertEquals("photo gone mid-run: content://media/1", e.message)
        }
    }
}
