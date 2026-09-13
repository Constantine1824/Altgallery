package com.altgallery.processing

import com.altgallery.data.model.ImageMetadata
import com.altgallery.data.model.MediaImage
import com.altgallery.data.repository.MetadataRepository
import com.altgallery.ml.BitmapLoader
import com.altgallery.ml.CaptionEngine
import com.altgallery.ml.EmbeddingEngine
import com.altgallery.ml.LabelEngine
import com.altgallery.ml.MemeClassifier
import com.altgallery.ml.OcrEngine
import com.altgallery.ml.TagExtractor
import javax.inject.Inject
import javax.inject.Singleton

/**
 * INDX-001 spine: one photo in, one complete record out.
 *
 * Turns a [MediaImage] from the device library into a fully-indexed
 * [ImageMetadata] with no manual step: library details, generated description
 * (with extracted text appended), labels, tags, and embedding are all captured,
 * and the FTS row lands in the same transaction so the photo is findable by its
 * text from the moment the record lands.
 *
 * Nothing is persisted until every stage succeeds. If OCR, labeling,
 * captioning, or embedding throws, the exception propagates and no partial row
 * is written — the photo is simply not marked done. Batching, skip-sets, and
 * failure policies build on top of this and live outside it.
 */
@Singleton
class ImageProcessor @Inject constructor(
    private val bitmapLoader: BitmapLoader,
    private val ocrEngine: OcrEngine,
    private val labelEngine: LabelEngine,
    private val memeClassifier: MemeClassifier,
    private val captionEngine: CaptionEngine,
    private val tagExtractor: TagExtractor,
    private val embeddingEngine: EmbeddingEngine,
    private val metadataRepository: MetadataRepository,
) {
    /**
     * Runs the full per-photo pipeline and persists the complete record.
     *
     * @return the persisted [ImageMetadata] with [ImageMetadata.embeddingId] set.
     * @throws Exception if any stage fails; nothing is written in that case.
     */
    suspend fun process(image: MediaImage): ImageMetadata {
        val uri = image.contentUri
        val bitmap = bitmapLoader.load(uri)
        try {
            // ML stages first — all must succeed before anything is recorded.
            val ocr = ocrEngine.recognize(uri)
            val labels = labelEngine.label(uri)

            val ocrText = ocr.text.trim().takeIf { it.isNotEmpty() }
            val labelTexts = labels.map { it.text }

            // MediaStore dims are the source of truth; fall back to decoded
            // bitmap dims when the library reports 0 (some devices do).
            val width = image.width.takeIf { it > 0 } ?: bitmap?.width ?: 0
            val height = image.height.takeIf { it > 0 } ?: bitmap?.height ?: 0
            val isMeme = memeClassifier.classify(ocr, labels, width, height, bitmap)

            val description = captionEngine.caption(bitmap, labels, ocrText ?: "")
            val tags = tagExtractor.extract(labelTexts, ocrText ?: "", description)
            val vector = embeddingEngine.embed(description)

            val metadata = ImageMetadata(
                contentUri = image.uriString,
                displayName = image.displayName,
                filePath = null,
                dateTaken = image.dateTaken,
                width = image.width,
                height = image.height,
                mimeType = image.mimeType,
                isMeme = isMeme,
                description = description,
                ocrText = ocrText,
                labels = labelTexts.joinToString(","),
                tags = tags.joinToString(","),
                clusterId = UNCLUSTERED,
                processedAt = System.currentTimeMillis(),
                modelVersion = MODEL_VERSION,
                embeddingId = null,
            )

            // Single transaction lands metadata + embedding + FTS together.
            return metadataRepository.saveCompleteRecord(metadata, vector)
        } finally {
            if (bitmap?.isRecycled == false) bitmap.recycle()
        }
    }

    companion object {
        const val MODEL_VERSION = "altgallery-v0.1"

        /** Placeholder folder assignment until M5 clustering runs. */
        const val UNCLUSTERED = "unclustered"
    }
}
