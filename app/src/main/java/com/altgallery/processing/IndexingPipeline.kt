package com.altgallery.processing

import com.altgallery.data.model.MediaImage
import com.altgallery.data.repository.MediaRepository
import com.altgallery.data.repository.MetadataRepository
import com.altgallery.ml.EmbeddingEngine
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
 * 3. Checks the required model files once via [EmbeddingEngine.isReady].
 *    When they are absent the run throws [ModelsMissingException] before any
 *    photo is attempted: one clear condition, not one failure per photo.
 * 4. Processes each pending photo via [ImageProcessor.process], which persists
 *    the full record (metadata + embedding + FTS) atomically. A throw leaves
 *    nothing behind, so the photo stays pending for the next run.
 *
 * Batch policy (AC01–AC05) lives in [BatchRunner], which this delegates to:
 * one bad photo never stops the batch, each photo gets up to
 * [BatchRunner.MAX_ATTEMPTS] attempts, exhausted photos are recorded with
 * their filename and reason in the returned [RunSummary], and the summary
 * always adds up (`attempted == succeeded + failed`).
 *
 * Reprocessing overwrites in one transaction, so recovered or edited photos
 * still leave exactly one record per URI.
 *
 * Note: no production caller invokes [runOnce] yet; the batch/WorkManager
 * wiring that will own scheduling lives in M4. Until then the pipeline is
 * exercised by unit tests only (see `BatchRunnerTest` for the policy pin).
 */
@Singleton
class IndexingPipeline @Inject constructor(
    private val mediaRepository: MediaRepository,
    private val metadataRepository: MetadataRepository,
    private val imageProcessor: ImageProcessor,
    private val embeddingEngine: EmbeddingEngine,
) {
    /** Pending photos in library order (newest first). */
    suspend fun pending(): List<MediaImage> =
        metadataRepository.findPending(mediaRepository.queryAllImages())

    /**
     * Processes every pending photo, in order, without letting one bad photo
     * stop the batch.
     *
     * @param onProgress optional per-settled-photo callback with (done, total).
     * @return the run's summary: indexed records plus recorded failures.
     * @throws ModelsMissingException once, before any attempt, when the
     *   required model files are absent.
     */
    suspend fun runOnce(onProgress: suspend (done: Int, total: Int) -> Unit = { _, _ -> }): RunSummary {
        val pending = pending()
        val byUri = pending.associateBy { it.uriString }
        return BatchRunner.runBatch(
            items = pending.map { BatchPhoto(it.uriString, it.displayName) },
            checkModelsReady = {
                if (!embeddingEngine.isReady()) {
                    throw ModelsMissingException(
                        "Embedding model files are missing; run aborted before indexing. " +
                            "Install the embedding model and vocabulary (see ModelAssets).",
                    )
                }
            },
            processPhoto = { item ->
                imageProcessor.process(byUri.getValue(item.contentUri))
            },
            onPhotoSettled = onProgress,
        )
    }
}
