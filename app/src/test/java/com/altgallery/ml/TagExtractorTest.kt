package com.altgallery.ml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the tag half of AC4 plus per-photo isolation (AC3) for tags.
 */
class TagExtractorTest {

    private val extractor = TagExtractor()

    @Test
    fun `blank inputs still yield a non-blank tag`() {
        assertEquals(listOf("image"), extractor.extract(emptyList(), "", "an image"))
    }

    @Test
    fun `labels are lowercased and description keywords added`() {
        val tags = extractor.extract(listOf("Cat", "Text"), "hello", "a photo of cat")
        assertTrue(tags.contains("cat"))
        assertTrue(tags.contains("text"))
        assertTrue(tags.contains("photo"))
        assertFalse(tags.any { it.any(Char::isUpperCase) })
    }

    @Test
    fun `no leakage between photos`() {
        val first = extractor.extract(
            listOf("Cat"),
            "hello world",
            "a photo of cat [TEXT IN IMAGE: hello world]",
        )
        assertTrue(first.contains("cat"))

        val second = extractor.extract(emptyList(), "", "an image")
        assertEquals(listOf("image"), second)
        assertFalse(second.contains("cat"))
        assertFalse(second.contains("hello"))
    }
}
