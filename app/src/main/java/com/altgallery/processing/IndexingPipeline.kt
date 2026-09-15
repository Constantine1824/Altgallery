package com.altgallery.processing

import com.altgallery.data.model.ImageMetadata
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
 * 5. Persists this run's failures via
 *    [MetadataRepository.recordRunFailures], replacing the previous log, so
 *    repeat offenders stay visible across runs. A failed photo writes no
 *    index record and is therefore re-attempted with a fresh budget on the
 *    next run (the deliberate cross-run policy — see [BatchRunner]).
 *
 * Batch policy (AC01–AC05) lives in [BatchRunner], which [executeRun]
 * delegates to: one bad photo never stops the batch, each photo gets up to
 * [BatchRunner.MAX_ATTEMPTS] spaced attempts, exhausted photos are recorded
 * with their filename and reason in the returned [RunSummary], and the
 * summary always adds up (`attempted == succeeded + failed`).
 *
 * Reprocessing overwrites in one transaction, so recovered or edited photos
 * still leave exactly one record per URI.
 *
 * Test scope, stated exactly: the orchestration (readiness gate, batch
 * delegation, failure persistence, progress) is pinned through [executeRun]
 * with fake collaborators (see `PipelineRunTest`); the batch policy itself
 * through `BatchRunnerTest`. The only unpinned lines are the 3-line
 * MediaImage↔[BatchPhoto] total mapping inside [runOnce], which plain-JVM
 * tests cannot construct (`android.net.Uri` has no JVM implementation).
 *
 * Production driver: [com.altgallery.work.IndexingWorker] invokes [runOnce]
 * on a background dispatcher; [com.altgallery.work.IndexingScheduler]
 * enqueues it on every cold start and on permission grant.
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
        return executeRun(
            items = pending.map { BatchPhoto(it.uriString, it.displayName) },
            checkModelsReady = {
                if (!embeddingEngine.isReady()) throw modelsMissingError()
            },
            processPhoto = { item ->
                imageProcessor.process(byUri.getValue(item.contentUri))
            },
            recordFailures = { metadataRepository.recordRunFailures(it) },
            onPhotoSettled = onProgress,
        )
    }
}

/**
 * The JVM-testable orchestration behind [IndexingPipeline.runOnce]:
 * readiness gate, batch delegation, and failure persistence in one place.
 * Top-level (instead of a method) so JVM tests can drive it with fake
 * collaborators and no Android runtime: `runOnce` only maps Android rows
 * onto [BatchPhoto] and supplies the production lambdas, so the wiring the
 * batch ticket added (gate, persistence call, progress forwarding) is pinned
 * here.
 */
internal suspend fun executeRun(
    items: List<BatchPhoto>,
    checkModelsReady: suspend () -> Unit,
    processPhoto: suspend (BatchPhoto) -> ImageMetadata,
    recordFailures: suspend (List<PhotoFailure>) -> Unit,
    onPhotoSettled: suspend (done: Int, total: Int) -> Unit = { _, _ -> },
): RunSummary = BatchRunner.runBatch(
    items = items,
    checkModelsReady = checkModelsReady,
    processPhoto = processPhoto,
    recordFailures = recordFailures,
    onPhotoSettled = onPhotoSettled,
)

/**
 * The single clear condition for absent model files. Extracted (instead of
 * an inline literal) so the wording — which names the remedy — is pinned by
 * JVM tests (see `PipelineRunTest`).
 */
internal fun modelsMissingError(): ModelsMissingException = ModelsMissingException(
    "Embedding model files are missing; run aborted before indexing. " +
        "Install the embedding model and vocabulary (see ModelAssets).",
)
