package com.altgallery.processing

import com.altgallery.data.model.ImageMetadata
import com.altgallery.data.model.MediaImage
import com.altgallery.data.repository.MediaRepository
import com.altgallery.data.repository.MetadataRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Crash-safe, idempotent indexing runs over the device library.
 *
 * Each run:
 * 1. Lists the library ([MediaRepository.queryAllImages]).
 * 2. Keeps only pending photos ([MetadataRepository.findPending]): missing or
 *    partial records (killed mid-run) plus edited/replaced photos whose
 *    dims/capture date drifted or whose file was modified after indexing.
 *    Unchanged, fully-recorded photos are skipped,
 *    so a second run over the same library does no work and the indexed count
 *    cannot move.
 * 3. Processes each pending photo via [ImageProcessor.process], which persists
 *    the full record (metadata + embedding + FTS) atomically. A throw leaves
 *    nothing behind, so the photo stays pending for the next run.
 *
 * Reprocessing overwrites in one transaction, so recovered or edited photos
 * still leave exactly one record per URI.
 *
 * Note: no production caller invokes [runOnce] yet; the batch/WorkManager
 * wiring that will own scheduling lives in M4. Until then the pipeline is
 * exercised by unit tests only.
 */
@Singleton
class IndexingPipeline @Inject constructor(
    private val mediaRepository: MediaRepository,
    private val metadataRepository: MetadataRepository,
    private val imageProcessor: ImageProcessor,
) {
    /** Pending photos in library order (newest first). */
    suspend fun pending(): List<MediaImage> =
        metadataRepository.findPending(mediaRepository.queryAllImages())

    /**
     * Processes every pending photo, in order.
     *
     * @param onProgress optional per-photo callback with (done, total).
     * @return persisted records in processing order.
     */
    suspend fun runOnce(onProgress: suspend (done: Int, total: Int) -> Unit = { _, _ -> }): List<ImageMetadata> {
        val pending = pending()
        val out = ArrayList<ImageMetadata>(pending.size)
        for ((index, image) in pending.withIndex()) {
            out += imageProcessor.process(image)
            onProgress(index + 1, pending.size)
        }
        return out
    }
}
