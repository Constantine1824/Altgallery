package com.altgallery.processing

import com.altgallery.data.model.ImageMetadata

/**
 * Definition of "already processed" for the indexing pipeline.
 *
 * A photo is done only when its FULL record landed:
 * - an [ImageMetadata] row exists,
 * - it links to an embedding ([ImageMetadata.embeddingId] != null),
 * - the embedding row for that URI exists,
 * - the FTS row for that URI exists,
 * - the description is non-blank (blank is rejected at assembly time,
 *   so a blank stored description means a corrupt/legacy row).
 *
 * Anything missing -> not done -> eligible for the next run. Reprocessing
 * overwrites via [com.altgallery.data.repository.MetadataRepository.saveCompleteRecord]
 * (same transaction deletes stale rows first), so a recovered photo is still
 * counted exactly once.
 *
 * Freshness is separate from completeness: a complete record whose library
 * content changed (edited/replaced photo) is pending again. The signals are
 * dimensions and capture date. Library dims of 0 mean "unknown" (MediaStore
 * gap) and never count as a change, otherwise a bitmap-fallback record would
 * look stale forever.
 */
object IndexPolicy {

    /**
     * Minimal snapshot of one library photo, Android-free so it runs on the
     * plain JVM. Map from [com.altgallery.data.model.MediaImage] via its
     * uriString/width/height/dateTaken fields.
     */
    data class LibraryPhoto(
        val contentUri: String,
        val width: Int,
        val height: Int,
        val dateTaken: Long,
    )

    /**
     * What the store knows about one URI: the metadata row (null when absent)
     * plus existence flags for the two companion rows.
     */
    data class StoredState(
        val metadata: ImageMetadata?,
        val hasEmbedding: Boolean,
        val hasFts: Boolean,
    )

    fun isComplete(
        metadata: ImageMetadata?,
        hasEmbedding: Boolean,
        hasFts: Boolean,
    ): Boolean = isComplete(StoredState(metadata, hasEmbedding, hasFts))

    fun isComplete(stored: StoredState): Boolean {
        val metadata = stored.metadata ?: return false
        if (metadata.description.isBlank()) return false
        if (metadata.embeddingId == null) return false
        if (!stored.hasEmbedding) return false
        if (!stored.hasFts) return false
        return true
    }

    /**
     * True when the stored record reflects the current library content.
     * Unknown library dims (<= 0) are ignored so bitmap-fallback records
     * don't flap.
     */
    fun isFresh(
        metadata: ImageMetadata,
        imageWidth: Int,
        imageHeight: Int,
        imageDateTaken: Long,
    ): Boolean {
        if (imageWidth > 0 && metadata.width != imageWidth) return false
        if (imageHeight > 0 && metadata.height != imageHeight) return false
        if (metadata.dateTaken != imageDateTaken) return false
        return true
    }

    fun isFresh(metadata: ImageMetadata, photo: LibraryPhoto): Boolean =
        isFresh(metadata, photo.width, photo.height, photo.dateTaken)

    /** A photo is done only when complete AND fresh. */
    fun isDone(photo: LibraryPhoto, stored: StoredState): Boolean {
        val metadata = stored.metadata ?: return false
        return isComplete(stored) && isFresh(metadata, photo)
    }

    /**
     * Pending subset of [library] in library order: missing/incomplete rows,
     * plus complete rows whose dims/date drifted (edited or replaced photo).
     */
    fun findPending(
        library: List<LibraryPhoto>,
        storedByUri: Map<String, StoredState>,
    ): List<LibraryPhoto> =
        library.filter { photo ->
            val stored = storedByUri[photo.contentUri] ?: return@filter true
            !isDone(photo, stored)
        }
}
