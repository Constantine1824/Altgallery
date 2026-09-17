package com.altgallery.work

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the execution-time ceiling behind sliced indexing: each worker
 * execution is bounded by count and wall-clock time, skips photos already
 * attempted in the pass, and appends a continuation while unattempted photos
 * remain — so a few-hundred-photo first run walks the library instead of
 * holding one execution past WorkManager's ~10-minute cap.
 */
class RunSliceTest {

    @Test
    fun `fifty photos per execution`() {
        assertEquals(50, MAX_PHOTOS_PER_EXECUTION)
    }

    @Test
    fun `eight minute budget leaves headroom before the ten minute cap`() {
        assertEquals(8L * 60L * 1000L, RUN_TIME_BUDGET_MS)
        assertTrue(RUN_TIME_BUDGET_MS < 10L * 60L * 1000L)
    }

    @Test
    fun `slice runs inside the budget and yields on stop or expiry`() {
        assertFalse(shouldStopSlice(0L, false))
        assertFalse(shouldStopSlice(RUN_TIME_BUDGET_MS - 1, false))
        assertTrue(shouldStopSlice(RUN_TIME_BUDGET_MS, false))
        assertTrue(shouldStopSlice(RUN_TIME_BUDGET_MS + 1, false))
        assertTrue(shouldStopSlice(0L, true))
    }

    @Test
    fun `logged uris are skipped so a bad head cannot starve the tail`() {
        val pending = listOf("a", "b", "c")
        assertEquals(listOf("a", "b", "c"), filterUnattempted(pending, emptySet()))
        assertEquals(listOf("b", "c"), filterUnattempted(pending, setOf("a")))
        assertEquals(emptyList<String>(), filterUnattempted(pending, pending.toSet()))
    }

    @Test
    fun `continuation while any unattempted photo remains`() {
        assertFalse(needsContinuation(filteredTotal = 0, settled = 0))
        assertFalse(needsContinuation(filteredTotal = 50, settled = 50))
        assertTrue(needsContinuation(filteredTotal = 70, settled = 50))
        assertTrue(needsContinuation(filteredTotal = 50, settled = 30))
        assertTrue(needsContinuation(filteredTotal = 50, settled = 0))
    }
}
