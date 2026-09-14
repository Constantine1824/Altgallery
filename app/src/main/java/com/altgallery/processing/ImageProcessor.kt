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
            val labelTexts = labels.map { it.text }

            // Pure tail owns the wiring (normalize → effective dims → classify
            // → describe → tag → assemble → embed-then-save). Stage lambdas
            // close over the Android objects; the tail itself is JVM-testable
            // (see RecordAssembler.processPhoto).
            return RecordAssembler.processPhoto(
                contentUri = image.uriString,
                displayName = image.displayName,
                dateTaken = image.dateTaken,
                mimeType = image.mimeType,
                imageWidth = image.width,
                imageHeight = image.height,
                bitmapWidth = bitmap?.width,
                bitmapHeight = bitmap?.height,
                ocrRaw = ocr.text,
                labelTexts = labelTexts,
                processedAt = System.currentTimeMillis(),
                classify = { _, _, width, height ->
                    memeClassifier.classify(ocr, labels, width, height, bitmap)
                },
                describe = { ocrText -> captionEngine.caption(bitmap, labels, ocrText) },
                tag = { ocrText, description ->
                    tagExtractor.extract(labelTexts, ocrText, description)
                },
                embed = embeddingEngine::embed,
                save = metadataRepository::saveCompleteRecord,
            )
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
