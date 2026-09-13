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

            val ocrText = RecordAssembler.normalizeOcr(ocr.text)
            val labelTexts = labels.map { it.text }

            // Effective dims: library wins, decoded bitmap fills gaps — and the
            // effective values are what get persisted (AC1), so records never
            // carry 0/0 when the bitmap gave real dimensions.
            val (width, height) = RecordAssembler.resolveDimensions(
                image.width, image.height, bitmap?.width, bitmap?.height,
            )
            val isMeme = memeClassifier.classify(ocr, labels, width, height, bitmap)

            val description = captionEngine.caption(bitmap, labels, ocrText ?: "")
            val tags = tagExtractor.extract(labelTexts, ocrText ?: "", description)
            val assembled = RecordAssembler.assemble(
                contentUri = image.uriString,
                displayName = image.displayName,
                dateTaken = image.dateTaken,
                width = width,
                height = height,
                mimeType = image.mimeType,
                isMeme = isMeme,
                description = description,
                ocrText = ocrText,
                labelTexts = labelTexts,
                tags = tags,
                processedAt = System.currentTimeMillis(),
            )

            // Embed-then-save tail (AC5): a throw here skips the write, so the
            // photo stays not done; the repo transaction lands metadata +
            // embedding + FTS together on success.
            return RecordAssembler.complete(assembled.metadata.description, embeddingEngine::embed) { vector ->
                metadataRepository.saveCompleteRecord(assembled.metadata, vector)
            }
        } finally {
            if (bitmap?.isRecycled == false) bitmap.recycle()
        }
    }

    companion object {
        const val MODEL_VERSION = RecordAssembler.MODEL_VERSION

        /** Placeholder folder assignment until M5 clustering runs. */
        const val UNCLUSTERED = RecordAssembler.UNCLUSTERED
    }
}
