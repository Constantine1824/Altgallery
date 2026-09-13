package com.altgallery.processing

import com.altgallery.ml.ModelUnavailableException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Pins INDX-001 AC5: the embed-then-save tail. A throw from [embed] must skip
 * [save] entirely (photo stays not done); success saves exactly once with the
 * produced vector.
 */
class PipelineCompleteTest {

    private fun blankMetadata() = RecordAssembler.assemble(
        contentUri = "content://media/1",
        displayName = "IMG_0001.jpg",
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
    ).metadata

    @Test
    fun `save never runs when embed throws`() = runBlocking {
        var saved = false
        try {
            RecordAssembler.complete(
                description = "an image",
                embed = { throw ModelUnavailableException("model absent") },
                save = {
                    saved = true
                    blankMetadata()
                },
            )
            throw AssertionError("expected ModelUnavailableException")
        } catch (expected: ModelUnavailableException) {
            // Expected: absent model leaves the photo not done.
        }
        assertFalse(saved)
    }

    @Test
    fun `save runs once with embed vector on success`() = runBlocking {
        val expected = floatArrayOf(0.5f, -0.25f, 1f)
        val metadata = blankMetadata()
        var calls = 0
        val result = RecordAssembler.complete(
            description = "an image",
            embed = { expected },
            save = { vector ->
                calls++
                assertArrayEquals(expected, vector, 0f)
                metadata
            },
        )
        assertEquals(1, calls)
        assertSame(metadata, result)
    }
}
