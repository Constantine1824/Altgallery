package com.altgallery.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the MediaStore seconds-to-millis scaling the mtime freshness check
 * depends on. A wrong factor (or a dropped fallback) fails silent-stale:
 * every photo would look either permanently fresh or permanently edited.
 */
class MediaImageTest {

    @Test
    fun `whole seconds scale to millis`() {
        assertEquals(1_000L, MediaImage.mediaStoreSecondsToMillis(1L))
        assertEquals(1_713_095_400_000L, MediaImage.mediaStoreSecondsToMillis(1_713_095_400L))
    }

    @Test
    fun `absent or nonsense mtime maps to unknown zero`() {
        assertEquals(0L, MediaImage.mediaStoreSecondsToMillis(0L))
        assertEquals(0L, MediaImage.mediaStoreSecondsToMillis(-5L))
    }

    @Test
    fun `large timestamps do not overflow`() {
        val seconds = Long.MAX_VALUE / 1000L - 1L
        assertEquals(seconds * 1000L, MediaImage.mediaStoreSecondsToMillis(seconds))
    }

    @Test
    fun `fresh MediaImage defaults to unknown mtime`() {
        assertEquals(0L, MediaImage.mediaStoreSecondsToMillis(0L))
    }
}
