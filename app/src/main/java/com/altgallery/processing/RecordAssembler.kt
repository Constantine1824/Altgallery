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
 * - AC2: [assemble] derives the FTS row via [ftsFor] by mirroring the
 *   metadata text fields, so the record is text-findable the moment it lands.
 *   [ftsFor] is the single derivation point: [processPhoto] hands the assembled
 *   FTS row to the repository, which persists it as given instead of
 *   re-deriving it, so the copy the tests pin is the copy that ships.
 * - AC3: stateless object, fresh collections per call — no shared state between
 *   photos.
 * - AC4: [assemble] rejects a blank description; the blank-input path
 *   (no labels, no OCR) still yields description "an image" / tag "image"
 *   via the captioner + [com.altgallery.ml.TagExtractor] defaults.
 * - AC5: [complete] runs [save] only after [embed] succeeds; an embedding
 *   failure (e.g. absent model files) propagates with nothing recorded.
 * - Wiring: [processPhoto] is the pure, JVM-testable orchestration of the
 *   per-photo tail (normalize → resolve dims → classify → describe → tag →
 *   assemble → embed-then-save). `ImageProcessor.process` maps Android types
 *   to primitives and delegates here, so the wiring the dims bug lived in is
 *   pinned by tests. The stale-snapshot guard is a required argument of
 *   `processPhoto` (not a caller-side lambda detail): the save step re-reads
 *   via `snapshotRequery` and throws before any write on a move/vanish, so
 *   the guard cannot be deleted or misplaced from `ImageProcessor.process`
 *   without failing the tail tests.
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
        return AssembledRecord(metadata, ftsFor(metadata))
    }

    /**
     * Single derivation point for the FTS row: mirrors the metadata text
     * fields. [assemble] builds the shipped row through this, and the
     * repository persists that row as given — so there is exactly one copy
     * and the tested copy is the shipped copy.
     */
    fun ftsFor(metadata: ImageMetadata): ImageMetadataFts = ImageMetadataFts(
        contentUri = metadata.contentUri,
        displayName = metadata.displayName,
        description = metadata.description,
        ocrText = metadata.ocrText,
        labels = metadata.labels,
        tags = metadata.tags,
    )

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

    /**
     * Pure per-photo orchestration tail (no Android types).
     *
     * Owns the exact wiring the dims bug lived in: raw library dims +
     * decoded-bitmap dims resolve to the effective values, the classifier
     * observes those effective values, and the assembled metadata + assembled
     * FTS row are what reach [save] via [complete] (embed-then-save, so an
     * embed throw skips the write). Stage lambdas close over Android objects
     * in `ImageProcessor.process`, keeping this runnable on the plain JVM.
     *
     * The stale-snapshot guard is structural: `snapshotBefore` is the intake
     * snapshot and `snapshotRequery` re-reads the row after embed (the slowest
     * stage). Any move throws [StaleSnapshotException], a vanished row throws
     * [PhotoGoneException], before `save` runs — so nothing is written and the
     * photo stays pending. Callers cannot opt out: omitting the guard is a
     * compile error, and moving it after `save` fails the ordering test.
     */
    @Suppress("LongParameterList")
    suspend fun processPhoto(
        contentUri: String,
        displayName: String,
        dateTaken: Long,
        mimeType: String,
        imageWidth: Int,
        imageHeight: Int,
        bitmapWidth: Int?,
        bitmapHeight: Int?,
        ocrRaw: String,
        labelTexts: List<String>,
        clusterId: String = UNCLUSTERED,
        processedAt: Long,
        modelVersion: String = MODEL_VERSION,
        snapshotBefore: IndexPolicy.LibraryPhoto,
        snapshotRequery: suspend () -> IndexPolicy.LibraryPhoto?,
        classify: (ocrText: String?, labelTexts: List<String>, width: Int, height: Int) -> Boolean,
        describe: suspend (ocrText: String) -> String,
        tag: (ocrText: String, description: String) -> List<String>,
        embed: suspend (String) -> FloatArray,
        save: suspend (metadata: ImageMetadata, fts: ImageMetadataFts, vector: FloatArray) -> ImageMetadata,
    ): ImageMetadata {
        val ocrText = normalizeOcr(ocrRaw)
        val (width, height) = resolveDimensions(imageWidth, imageHeight, bitmapWidth, bitmapHeight)
        val isMeme = classify(ocrText, labelTexts, width, height)
        val description = describe(ocrText ?: "")
        val tags = tag(ocrText ?: "", description)
        val assembled = assemble(
            contentUri = contentUri,
            displayName = displayName,
            dateTaken = dateTaken,
            width = width,
            height = height,
            mimeType = mimeType,
            isMeme = isMeme,
            description = description,
            ocrText = ocrText,
            labelTexts = labelTexts,
            tags = tags,
            clusterId = clusterId,
            processedAt = processedAt,
            modelVersion = modelVersion,
        )
        return complete(assembled.metadata.description, embed) { vector ->
            guardSnapshot(snapshotBefore, snapshotRequery)
            save(assembled.metadata, assembled.fts, vector)
        }
    }
}
