package com.altgallery.processing

import com.altgallery.data.model.ImageMetadata
import com.altgallery.ml.ModelUnavailableException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * Android-free identity of one photo inside a batch run: the URI is the stable
 * key, the display name is what a failure record shows the user.
 */
data class BatchPhoto(
    val contentUri: String,
    val displayName: String,
)

/**
 * One photo that could not be processed after its attempts: its identity plus
 * the reason from the last attempt. Kept in [RunSummary.failures] and handed
 * to [BatchRunner.runBatch]'s required `recordFailures` callback, which the
 * production wiring persists
 * (`MetadataRepository.recordRunFailures`) — so the record survives the run
 * and process death, and appears in the run's summary.
 */
data class PhotoFailure(
    val contentUri: String,
    val displayName: String,
    val reason: String,
    val attempts: Int,
)

/**
 * What a run accomplished, in one place. The invariant
 * `attempted == succeeded.size + failures.size` is enforced by construction,
 * so the app can never claim a library is indexed when part of it is not:
 * every attempted photo is either indexed or recorded with its reason.
 */
data class RunSummary(
    val attempted: Int,
    val succeeded: List<ImageMetadata>,
    val failures: List<PhotoFailure>,
) {
    val succeededCount: Int get() = succeeded.size
    val failedCount: Int get() = failures.size

    init {
        require(attempted == succeeded.size + failures.size) {
            "unbalanced summary: attempted=$attempted but " +
                "${succeeded.size} succeeded + ${failures.size} failed"
        }
    }
}

/**
 * The embedding (or any other required) model files are absent, so the run
 * cannot index anything. Thrown once at the start of a run — never recorded
 * as a per-photo failure — so a missing model reads as one clear condition
 * instead of hundreds of identical photo errors.
 */
class ModelsMissingException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)

/**
 * Batch failure policy: one bad photo never stops the batch.
 *
 * Per photo, in input order:
 * - attempts up to [MAX_ATTEMPTS] within the run, first success wins, with a
 *   pause between attempts (`retryDelayMs` via `nap`) so a burst of
 *   back-to-back retries neither hot-spins a deterministically corrupt file
 *   nor denies a transient failure (locked file, half-written download) time
 *   to clear;
 * - a photo that still fails is recorded ([PhotoFailure] with its filename
 *   and the last attempt's reason) and the run moves on;
 * - [ModelUnavailableException] is not a photo failure but a global
 *   condition: the run aborts immediately with [ModelsMissingException] so
 *   the missing model is reported once, not once per photo;
 * - coroutine cancellation is never swallowed or recorded: it rethrows
 *   (including a cancellation that lands inside a retry pause).
 *
 * Across runs (the deliberate recommendation, not an accident of missing
 * rows): a failed photo writes no index record, so it stays pending and is
 * re-attempted with a fresh budget on the next run — a failure may be
 * transient, and the done-definition stays the single source of truth, so a
 * failure can never masquerade as done. The persisted failure log is what
 * keeps terminally failed photos visible across runs instead of failing
 * silently: a photo that recovers drops out of the log on the recovering
 * run.
 *
 * `checkModelsReady` runs once before any photo is attempted (and is skipped
 * entirely when there is nothing pending, so an idle run never false-alarms).
 * `recordFailures` is a required argument (not a caller-side afterthought):
 * it runs once per non-empty run with that run's failures — empty after a
 * clean run, which clears the log — so the persistence cannot be dropped
 * from the wiring without failing the tail tests. An empty run records
 * nothing and preserves the previous log. Everything here is Android-free so
 * the policy is pinned by plain-JVM tests; `IndexingPipeline.runOnce` only
 * maps library rows onto [BatchPhoto] and delegates here, so this is the
 * single policy path for batch runs.
 *
 * Time ceiling (INDX-004 follow-up): [shouldStop] is checked before each
 * photo AND inside the per-photo retry loop (before every retry attempt and
 * before every pause), so a slice yields cooperatively before WorkManager's
 * ~10-minute cap instead of being killed mid-run — checking only between
 * photos would let 50 photos × 3 slow attempts run ~20 minutes worst case.
 * An early stop returns a partial summary (`attempted == succeeded + failed
 * == settled so far`, still balanced). A photo cut short by the budget is
 * left unsettled (pending and unlogged, NOT recorded as a failure) so the
 * appended continuation retries it in the same pass; only exhausted photos
 * are recorded. `recordFailures` runs once per non-empty run even when zero
 * photos settled: for a fresh pass that is the replace that drops the
 * previous pass's log (so a stopped-first-slice's continuation retries
 * everything instead of mistaking the old log for this pass's attempt log),
 * while a continuation's append of an empty list is a no-op preserving its
 * pass's slices (see `IndexingPipeline.runSlice`).
 */
object BatchRunner {

    /** Defined attempts per photo within a single run. */
    const val MAX_ATTEMPTS = 3

    /**
     * Pause after [failedAttempt] (1-based) before the next attempt.
     * Doubling from 250ms: enough for a transient lock to clear without
     * stalling the batch behind a corrupt file. Public so the schedule is
     * pinned by tests rather than hidden.
     */
    fun defaultRetryDelayMs(failedAttempt: Int): Long =
        250L * (1L shl (failedAttempt - 1))

    suspend fun runBatch(
        items: List<BatchPhoto>,
        checkModelsReady: suspend () -> Unit,
        processPhoto: suspend (BatchPhoto) -> ImageMetadata,
        recordFailures: suspend (List<PhotoFailure>) -> Unit,
        retryDelayMs: (failedAttempt: Int) -> Long = ::defaultRetryDelayMs,
        nap: suspend (Long) -> Unit = { delay(it) },
        onPhotoSettled: suspend (done: Int, total: Int) -> Unit = { _, _ -> },
        shouldStop: () -> Boolean = { false },
    ): RunSummary {
        if (items.isEmpty()) {
            return RunSummary(attempted = 0, succeeded = emptyList(), failures = emptyList())
        }
        checkModelsReady()

        val succeeded = ArrayList<ImageMetadata>(items.size)
        val failures = ArrayList<PhotoFailure>()
        for (item in items) {
            if (shouldStop()) break
            var attempts = 0
            var record: ImageMetadata? = null
            var lastError: Exception? = null
            var cutShort = false
            while (attempts < MAX_ATTEMPTS) {
                // Budget check inside the retry loop: without it a slice of
                // slow photos would burn all remaining attempts past the
                // execution ceiling before the next between-photo check.
                if (attempts > 0 && shouldStop()) {
                    cutShort = true
                    break
                }
                attempts++
                try {
                    record = processPhoto(item)
                    lastError = null
                    break
                } catch (e: CancellationException) {
                    throw e
                } catch (e: ModelUnavailableException) {
                    throw ModelsMissingException(
                        "Model unavailable mid-run after ${succeeded.size + failures.size} of ${items.size} " +
                            "photos settled; aborting the batch so the missing model is " +
                            "reported once: ${reasonOf(e)}",
                        e,
                    )
                } catch (e: Exception) {
                    lastError = e
                    if (attempts < MAX_ATTEMPTS) {
                        if (shouldStop()) {
                            cutShort = true
                            break
                        }
                        nap(retryDelayMs(attempts))
                    }
                }
            }
            if (cutShort) break
            if (record != null) {
                succeeded += record
            } else {
                failures += PhotoFailure(
                    contentUri = item.contentUri,
                    displayName = item.displayName,
                    reason = reasonOf(lastError),
                    attempts = attempts,
                )
            }
            onPhotoSettled(succeeded.size + failures.size, items.size)
        }
        // Settled count, not items.size: identical for a full run, smaller
        // after a cooperative stop — the invariant still holds by construction.
        val summary = RunSummary(
            attempted = succeeded.size + failures.size,
            succeeded = succeeded,
            failures = failures,
        )
        // Unconditional for a non-empty run (even zero settled): a fresh
        // pass's replace-with-empty drops the previous pass's log so the
        // continuation cannot mistake it for this pass's attempt log, while
        // a continuation's append-with-empty is a no-op. The empty-items
        // early return above is what preserves the log on idle runs.
        recordFailures(summary.failures)
        return summary
    }

    /** Human-readable reason: the last error's message, or its type when blank. */
    internal fun reasonOf(error: Throwable?): String {
        val message = error?.message?.takeIf { it.isNotBlank() }
        if (message != null) return message
        return error?.javaClass?.simpleName?.takeIf { it.isNotBlank() }
            ?: "Unknown failure"
    }
}
