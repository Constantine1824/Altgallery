package com.altgallery.processing

import com.altgallery.data.model.ImageMetadata
import com.altgallery.data.model.ImageMetadataFts

/**
 * Pure, Android-free assembly of a complete indexed record (INDX-001).
 *
 * Everything here operates on primitives and Room data classes only, so the
 * decisions the sprint pinned by tests live here and run on the plain JVM:
 * - AC1: [resolveDimensions] fills MediaStore gaps from the decoded bitmap,
 *   and [assemble] persists the effective dims (never raw 0/0 when the bitmap
 *   gave real ones).
 * - AC2: [assemble] derives the FTS row by mirroring the metadata text fields,
 *   so the record is text-findable the moment it lands.
 * - AC3: stateless object, fresh collections per call — no shared state between
 *   photos.
 * - AC4: [assemble] rejects a blank description; the blank-input path
 *   (no labels, no OCR) still yields description "an image" / tag "image"
 *   via the captioner + [com.altgallery.ml.TagExtractor] defaults.
 * - AC5: [complete] runs [save] only after [embed] succeeds; an embedding
 *   failure (e.g. absent model files) propagates with nothing recorded.
 */
data class AssembledRecord(
    val metadata: ImageMetadata,
    val fts: ImageMetadataFts,
)

object RecordAssembler {

    const val MODEL_VERSION = "altgallery-v0.1"

    /** Placeholder folder assignment until M5 clustering runs. */
    const val UNCLUSTERED = "unclustered"

    /** Trimmed OCR text, or null when blank (stored nullable per schema). */
    fun normalizeOcr(raw: String): String? = raw.trim().takeIf { it.isNotEmpty() }

    /**
     * Effective dimensions: the library wins when it reports a positive value,
     * otherwise the decoded bitmap fills the gap. Both zero only when neither
     * source knows.
     */
    fun resolveDimensions(
        imageWidth: Int,
        imageHeight: Int,
        bitmapWidth: Int?,
        bitmapHeight: Int?,
    ): Pair<Int, Int> {
        val width = imageWidth.takeIf { it > 0 }
            ?: bitmapWidth?.takeIf { it > 0 }
            ?: 0
        val height = imageHeight.takeIf { it > 0 }
            ?: bitmapHeight?.takeIf { it > 0 }
            ?: 0
        return width to height
    }

    @Suppress("LongParameterList")
    fun assemble(
        contentUri: String,
        displayName: String,
        dateTaken: Long,
        width: Int,
        height: Int,
        mimeType: String,
        isMeme: Boolean,
        description: String,
        ocrText: String?,
        labelTexts: List<String>,
        tags: List<String>,
        clusterId: String = UNCLUSTERED,
        processedAt: Long,
        modelVersion: String = MODEL_VERSION,
    ): AssembledRecord {
        require(description.isNotBlank()) { "description must not be blank" }
        val metadata = ImageMetadata(
            contentUri = contentUri,
            displayName = displayName,
            filePath = null,
            dateTaken = dateTaken,
            width = width,
            height = height,
            mimeType = mimeType,
            isMeme = isMeme,
            description = description,
            ocrText = ocrText,
            labels = labelTexts.joinToString(","),
            tags = tags.joinToString(","),
            clusterId = clusterId,
            processedAt = processedAt,
            modelVersion = modelVersion,
            embeddingId = null,
        )
        val fts = ImageMetadataFts(
            contentUri = metadata.contentUri,
            displayName = metadata.displayName,
            description = metadata.description,
            ocrText = metadata.ocrText,
            labels = metadata.labels,
            tags = metadata.tags,
        )
        return AssembledRecord(metadata, fts)
    }

    /**
     * Embed-then-save tail: [save] runs only after [embed] returns. If [embed]
     * throws (e.g. [com.altgallery.ml.ModelUnavailableException] when model
     * files are absent), [save] is never invoked and nothing is recorded —
     * the photo stays not done for a later run.
     */
    suspend fun complete(
        description: String,
        embed: suspend (String) -> FloatArray,
        save: suspend (FloatArray) -> ImageMetadata,
    ): ImageMetadata = save(embed(description))
}
