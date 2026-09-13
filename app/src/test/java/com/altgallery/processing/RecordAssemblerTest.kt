package com.altgallery.processing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins INDX-001 AC1–AC4 against the pure assembler (plain JVM, no device).
 */
class RecordAssemblerTest {

    // AC1: effective dims fill MediaStore gaps from the decoded bitmap.

    @Test
    fun `library dims win when positive`() {
        assertEquals(1024 to 768, RecordAssembler.resolveDimensions(1024, 768, 800, 600))
    }

    @Test
    fun `bitmap dims fill library gaps`() {
        assertEquals(800 to 600, RecordAssembler.resolveDimensions(0, 0, 800, 600))
        assertEquals(800 to 768, RecordAssembler.resolveDimensions(0, 768, 800, 600))
        assertEquals(1024 to 600, RecordAssembler.resolveDimensions(1024, 0, 800, 600))
    }

    @Test
    fun `zero dims only when neither source knows`() {
        assertEquals(0 to 0, RecordAssembler.resolveDimensions(0, 0, null, null))
        assertEquals(0 to 0, RecordAssembler.resolveDimensions(0, 0, 0, 0))
    }

    // AC1: one end-to-end assembly carries every required field.

    @Test
    fun `assemble carries library details description text labels tags`() {
        val record = RecordAssembler.assemble(
            contentUri = "content://media/external/images/media/123",
            displayName = "IMG_2041.jpg",
            dateTaken = 1713095400000L,
            width = 800,
            height = 600,
            mimeType = "image/jpeg",
            isMeme = false,
            description = "a photo of cat [TEXT IN IMAGE: hello world]",
            ocrText = "hello world",
            labelTexts = listOf("Cat", "Text"),
            tags = listOf("cat", "text", "hello", "photo"),
            processedAt = 1713095500000L,
        ).metadata

        assertEquals("content://media/external/images/media/123", record.contentUri)
        assertEquals("IMG_2041.jpg", record.displayName)
        assertEquals(1713095400000L, record.dateTaken)
        assertEquals(800, record.width)
        assertEquals(600, record.height)
        assertEquals("image/jpeg", record.mimeType)
        assertEquals("a photo of cat [TEXT IN IMAGE: hello world]", record.description)
        assertEquals("hello world", record.ocrText)
        assertEquals("Cat,Text", record.labels)
        assertEquals("cat,text,hello,photo", record.tags)
        assertNull(record.embeddingId)
    }

    // AC2: FTS row mirrors the metadata text fields.

    @Test
    fun `fts mirrors metadata text for immediate findability`() {
        val assembled = RecordAssembler.assemble(
            contentUri = "content://media/external/images/media/7",
            displayName = "IMG_2041.jpg",
            dateTaken = 1L,
            width = 10,
            height = 10,
            mimeType = "image/jpeg",
            isMeme = false,
            description = "a photo of cat",
            ocrText = "hello",
            labelTexts = listOf("Cat"),
            tags = listOf("cat", "photo"),
            processedAt = 2L,
        )

        assertEquals(assembled.metadata.contentUri, assembled.fts.contentUri)
        assertEquals(assembled.metadata.displayName, assembled.fts.displayName)
        assertEquals(assembled.metadata.description, assembled.fts.description)
        assertEquals(assembled.metadata.ocrText, assembled.fts.ocrText)
        assertEquals(assembled.metadata.labels, assembled.fts.labels)
        assertEquals(assembled.metadata.tags, assembled.fts.tags)
    }

    // AC3: per-photo isolation — no leakage between records.

    @Test
    fun `two assemblies share no text labels or tags`() {
        val first = RecordAssembler.assemble(
            contentUri = "content://media/1",
            displayName = "IMG_0001.jpg",
            dateTaken = 1L,
            width = 10,
            height = 10,
            mimeType = "image/jpeg",
            isMeme = false,
            description = "a photo of cat [TEXT IN IMAGE: hello world]",
            ocrText = "hello world",
            labelTexts = listOf("Cat"),
            tags = listOf("cat", "hello", "photo"),
            processedAt = 2L,
        ).metadata
        val second = RecordAssembler.assemble(
            contentUri = "content://media/2",
            displayName = "IMG_0002.jpg",
            dateTaken = 3L,
            width = 20,
            height = 20,
            mimeType = "image/png",
            isMeme = false,
            description = "an image",
            ocrText = null,
            labelTexts = emptyList(),
            tags = listOf("image"),
            processedAt = 4L,
        ).metadata

        for (leak in listOf("hello", "world", "cat")) {
            assertFalse(second.description.contains(leak, ignoreCase = true))
            assertFalse(second.labels.contains(leak, ignoreCase = true))
            assertFalse(second.tags.contains(leak, ignoreCase = true))
        }
        assertFalse(first.contentUri == second.contentUri)
    }

    // AC4: blank-input path still yields a non-blank record.

    @Test
    fun `blank inputs still assemble a non-blank record`() {
        val record = RecordAssembler.assemble(
            contentUri = "content://media/9",
            displayName = "IMG_0009.jpg",
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

        assertTrue(record.description.isNotBlank())
        assertTrue(record.tags.isNotBlank())
    }

    @Test
    fun `normalizeOcr blanks to null`() {
        assertNull(RecordAssembler.normalizeOcr("   "))
        assertNull(RecordAssembler.normalizeOcr(""))
        assertEquals("hello", RecordAssembler.normalizeOcr("  hello\n"))
    }
}
