package com.altgallery.processing

import com.altgallery.data.model.ImageMetadata
import com.altgallery.data.model.MediaImage
import com.altgallery.data.repository.MediaRepository
import com.altgallery.data.repository.MetadataRepository
import com.altgallery.ml.EmbeddingEngine
import com.altgallery.work.MAX_PHOTOS_PER_EXECUTION
import com.altgallery.work.RUN_TIME_BUDGET_MS
import com.altgallery.work.filterUnattempted
import com.altgallery.work.needsContinuation
import com.altgallery.work.shouldStopSlice
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
 * Production driver: [com.altgallery.work.IndexingWorker] invokes [runSlice]
 * (bounded: count + time budget, foreground, appended continuation) on a
 * background dispatcher; [com.altgallery.work.IndexingScheduler] enqueues it
 * on every cold start and on permission grant. [runOnce] remains for the
 * unbounded single-pass shape the JVM tests pin.
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

    /**
     * One bounded execution of the current library pass (the 10-minute-cap
     * fix). Settles at most [maxPhotos] unattempted photos, stopping earlier
     * on the time budget or [isStopped], then reports whether the pass needs
     * another execution ([SliceResult.hasMore], via `needsContinuation`).
     *
     * Resumption: a fresh pass ([isContinuation] false, from cold start or
     * permission grant) attempts the whole pending set and replaces the
     * failure log; a continuation (appended by the worker) skips URIs already
     * logged by earlier slices of the same pass and appends its failures, so
     * terminally-failing heads cannot starve the tail and every slice's
     * failures survive process death. An empty pending set settles nothing
     * and preserves the log. Models-missing throws before any attempt or
     * persistence, as in [runOnce].
     */
    suspend fun runSlice(
        maxPhotos: Int = MAX_PHOTOS_PER_EXECUTION,
        isContinuation: Boolean = false,
        timeBudgetMs: Long = RUN_TIME_BUDGET_MS,
        isStopped: () -> Boolean = { false },
        clockMs: () -> Long = { System.currentTimeMillis() },
        onProgress: suspend (done: Int, total: Int) -> Unit = { _, _ -> },
    ): SliceResult {
        val pending = pending()
        if (pending.isEmpty()) {
            return SliceResult(
                summary = RunSummary(attempted = 0, succeeded = emptyList(), failures = emptyList()),
                hasMore = false,
                pendingTotal = 0,
                filteredTotal = 0,
            )
        }
        val logged: Set<String> = if (isContinuation) {
            metadataRepository.getRecordedFailures().map { it.contentUri }.toSet()
        } else {
            emptySet()
        }
        val unattempted = filterUnattempted(pending.map { it.uriString }, logged).toSet()
        val filtered = pending.filter { it.uriString in unattempted }
        if (filtered.isEmpty()) {
            return SliceResult(
                summary = RunSummary(attempted = 0, succeeded = emptyList(), failures = emptyList()),
                hasMore = false,
                pendingTotal = pending.size,
                filteredTotal = 0,
            )
        }
        val slice = filtered.take(maxPhotos)
        val byUri = pending.associateBy { it.uriString }
        val started = clockMs()
        val summary = executeRun(
            items = slice.map { BatchPhoto(it.uriString, it.displayName) },
            checkModelsReady = {
                if (!embeddingEngine.isReady()) throw modelsMissingError()
            },
            processPhoto = { item ->
                imageProcessor.process(byUri.getValue(item.contentUri))
            },
            recordFailures = { failures ->
                if (isContinuation) metadataRepository.appendRunFailures(failures)
                else metadataRepository.recordRunFailures(failures)
            },
            onPhotoSettled = onProgress,
            shouldStop = { shouldStopSlice(clockMs() - started, isStopped(), timeBudgetMs) },
        )
        return SliceResult(
            summary = summary,
            hasMore = needsContinuation(filtered.size, summary.attempted),
            pendingTotal = pending.size,
            filteredTotal = filtered.size,
        )
    }
}

/**
 * What one bounded execution accomplished plus whether the library pass
 * needs another execution. `hasMore` is true while unattempted photos from
 * this pass remain (count bound left a tail or the slice stopped early);
 * the worker appends a continuation then. Failed photos stay pending for
 * the NEXT pass, not the next slice — continuations skip logged URIs so one
 * bad head cannot starve the tail.
 */
data class SliceResult(
    val summary: RunSummary,
    val hasMore: Boolean,
    val pendingTotal: Int,
    val filteredTotal: Int,
)

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
    shouldStop: () -> Boolean = { false },
): RunSummary = BatchRunner.runBatch(
    items = items,
    checkModelsReady = checkModelsReady,
    processPhoto = processPhoto,
    recordFailures = recordFailures,
    onPhotoSettled = onPhotoSettled,
    shouldStop = shouldStop,
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
