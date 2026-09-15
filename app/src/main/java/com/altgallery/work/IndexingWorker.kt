package com.altgallery.work

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.altgallery.permissions.MediaPermissions
import com.altgallery.processing.IndexingPipeline
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.yield

/**
 * Runs one indexing pass over the pending library photos in the background.
 *
 * Behavior contract (INDX-004):
 * - No photo access → succeed quietly with no work (denied permission is a
 *   normal state, not an error; the home screen still loads its count).
 * - A throw is mapped through [actionForRunThrow]: missing models succeed
 *   without retry (stable condition), transient throws retry under the
 *   scheduler's backoff until [MAX_RUN_ATTEMPTS] executions, then fail
 *   instead of looping forever — the next cold start enqueues a fresh run,
 *   so a healed backend recovers. A killed run is safe to repeat: completed
 *   photos are skipped via the already-processed definition, failed ones
 *   stay pending.
 * - Cancellation always propagates (including from the per-photo pause), so
 *   a replaced run stops promptly.
 *
 * Responsiveness: [CoroutineWorker] runs on a background dispatcher, the
 * repositories already confine I/O off the main thread, and the per-photo
 * callback yields — so a few-hundred-photo run keeps the screen rendering
 * and the home count (a Room `Flow`) climbing without a freeze. Progress is
 * published via `setProgress` for future UI; the home count itself updates
 * through the database, not through this channel.
 *
 * Test scope: the throw→disposition table is pinned by plain-JVM tests (see
 * `RunDecisionTest`); the worker body only maps that table onto `Result`.
 * Enqueue behavior and the end-to-end flows are verified on-device per the
 * ticket's acceptance criteria.
 */
@HiltWorker
class IndexingWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val indexingPipeline: IndexingPipeline,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        if (!MediaPermissions.hasReadImages(applicationContext)) return Result.success()
        return try {
            indexingPipeline.runOnce { done, total ->
                setProgress(workDataOf(PROGRESS_DONE to done, PROGRESS_TOTAL to total))
                yield()
            }
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            when (actionForRunThrow(e, runAttemptCount)) {
                RunAction.SUCCEED -> Result.success()
                RunAction.RETRY -> Result.retry()
                RunAction.FAIL -> {
                    Log.e(TAG, "Indexing run failed terminally; will resume on next cold start", e)
                    Result.failure()
                }
            }
        }
    }

    companion object {
        const val PROGRESS_DONE = "done"
        const val PROGRESS_TOTAL = "total"
        private const val TAG = "IndexingWorker"
    }
}
